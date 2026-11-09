package com.haokai.addemo.speech

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 火山引擎豆包大模型流式 ASR WebSocket 二进制协议（V3）。
 *
 * 文档：https://www.volcengine.com/docs/6561/1354869
 */
internal object VolcAsrProtocol {

    const val WS_URL_ASYNC = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
    const val WS_URL_BIDIRECTIONAL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel"
    const val DEFAULT_RESOURCE_ID = "volc.seedasr.sauc.duration"

    private const val MSG_FULL_CLIENT = 0x1
    private const val MSG_AUDIO_ONLY = 0x2
    const val MSG_FULL_SERVER = 0x9
    const val MSG_ERROR = 0xF

    private const val FLAG_NO_SEQUENCE = 0x0
    private const val FLAG_POS_SEQUENCE = 0x1
    private const val FLAG_FINAL_NO_SEQUENCE = 0x2
    private const val FLAG_NEG_WITH_SEQUENCE = 0x3

    private const val SER_NONE = 0x0
    private const val SER_JSON = 0x1
    private const val COMP_GZIP = 0x1

    data class ServerFrame(
        val msgType: Int,
        val flags: Int,
        val sequence: Int?,
        val payload: ByteArray,
        val errorCode: Int = 0,
    ) {
        val isFinal: Boolean
            get() = flags == FLAG_FINAL_NO_SEQUENCE ||
                flags == FLAG_NEG_WITH_SEQUENCE ||
                (sequence != null && sequence < 0)
    }

    data class Utterance(
        val text: String,
        val startMs: Int,
        val endMs: Int,
        val definite: Boolean,
    ) {
        val dedupeKey: String get() = "$startMs|$endMs|$text"
    }

    data class AsrResult(
        val text: String,
        val definiteUtterances: List<Utterance>,
        val interimText: String,
        val code: Int,
        val message: String?,
    )

    fun buildFullClientRequest(
        uid: String,
        sequence: Int,
        recognitionMode: String? = null,
    ): ByteArray {
        val mode = recognitionMode?.lowercase().orEmpty()
        val request = JSONObject()
            .put("model_name", "bigmodel")
            .put("enable_itn", true)
            .put("enable_punc", true)
            .put("enable_ddc", false)
            .put("show_utterances", true)
            .put("result_type", "full")
            .put("enable_lid", true)
            // 更快出 interim / 断句，利于实时字幕
            .put("end_window_size", 600)
            .put("force_to_speech_time", 1_000)
        when (mode) {
            "nostream" -> {
                request.put("enable_auto_lang", true)
            }
            else -> {
                request.put("enable_nonstream", true)
            }
        }
        val payload = JSONObject().apply {
            put(
                "user",
                JSONObject()
                    .put("uid", uid)
                    .put("did", uid)
                    .put("platform", "Android"),
            )
            put(
                "audio",
                JSONObject()
                    .put("format", "pcm")
                    .put("codec", "raw")
                    .put("rate", 16_000)
                    .put("bits", 16)
                    .put("channel", 1),
            )
            put("request", request)
        }
        val compressed = gzip(payload.toString().toByteArray(Charsets.UTF_8))
        return buildFrame(
            msgType = MSG_FULL_CLIENT,
            flags = FLAG_POS_SEQUENCE,
            serialization = SER_JSON,
            sequence = sequence,
            payload = compressed,
        )
    }

    fun buildAudioOnlyRequest(pcm: ByteArray, sequence: Int, final: Boolean): ByteArray {
        val compressed = gzip(pcm)
        val flags = if (final) FLAG_NEG_WITH_SEQUENCE else FLAG_POS_SEQUENCE
        val seq = if (final) -sequence else sequence
        return buildFrame(
            msgType = MSG_AUDIO_ONLY,
            flags = flags,
            serialization = SER_NONE,
            sequence = seq,
            payload = compressed,
        )
    }

    fun parseServerFrame(data: ByteArray): ServerFrame {
        require(data.size >= 4) { "frame too short" }
        val headerSize = (data[0].toInt() and 0x0F) * 4
        require(headerSize >= 4 && data.size >= headerSize) { "invalid header size" }
        val msgType = (data[1].toInt() shr 4) and 0x0F
        val flags = data[1].toInt() and 0x0F
        val compression = data[2].toInt() and 0x0F
        var offset = headerSize

        if (msgType == MSG_ERROR) {
            require(data.size >= offset + 8) { "error frame truncated" }
            val errorCode = readInt32Be(data, offset)
            offset += 4
            val payloadSize = readUInt32Be(data, offset)
            offset += 4
            var payload = data.copyOfRange(offset, (offset + payloadSize).coerceAtMost(data.size))
            if (compression == COMP_GZIP && payload.isNotEmpty()) {
                payload = gunzip(payload)
            }
            return ServerFrame(msgType, flags, null, payload, errorCode)
        }

        var sequence: Int? = null
        // bit0 = has sequence, bit1 = last packet
        if ((flags and 0x01) != 0) {
            require(data.size >= offset + 4) { "missing sequence" }
            sequence = readInt32Be(data, offset)
            offset += 4
        }

        require(data.size >= offset + 4) { "missing payload size" }
        val payloadSize = readUInt32Be(data, offset)
        offset += 4
        var payload = data.copyOfRange(offset, (offset + payloadSize).coerceAtMost(data.size))
        if (compression == COMP_GZIP && payload.isNotEmpty()) {
            payload = gunzip(payload)
        }
        return ServerFrame(msgType, flags, sequence, payload)
    }

    fun parseAsrResult(payload: ByteArray): AsrResult {
        if (payload.isEmpty()) {
            return AsrResult("", emptyList(), "", 0, null)
        }
        val root = JSONObject(String(payload, Charsets.UTF_8))
        val code = when {
            root.has("code") -> root.optInt("code", 0)
            root.has("status_code") -> root.optInt("status_code", 0)
            else -> 0
        }
        val message = listOf("message", "error")
            .mapNotNull { key ->
                if (!root.has(key)) return@mapNotNull null
                root.optString(key).takeIf { it.isNotBlank() }
            }
            .firstOrNull()

        val result = root.optJSONObject("result")
        val fullText = result?.optString("text").orEmpty()
        val utterances = result?.optJSONArray("utterances") ?: JSONArray()

        val definite = mutableListOf<Utterance>()
        val interimParts = mutableListOf<String>()
        for (i in 0 until utterances.length()) {
            val u = utterances.optJSONObject(i) ?: continue
            val text = u.optString("text").orEmpty()
            if (text.isBlank()) continue
            val item = Utterance(
                text = text,
                startMs = u.optInt("start_time", 0),
                endMs = u.optInt("end_time", 0),
                definite = u.optBoolean("definite", false),
            )
            if (item.definite) {
                definite += item
            } else {
                interimParts += text
            }
        }
        val interim = when {
            interimParts.isNotEmpty() -> interimParts.joinToString("")
            else -> {
                val definiteJoined = definite.joinToString("") { it.text }
                when {
                    fullText.isNotBlank() && fullText != definiteJoined -> {
                        if (definiteJoined.isNotEmpty() && fullText.startsWith(definiteJoined)) {
                            fullText.removePrefix(definiteJoined).trimStart()
                        } else {
                            fullText
                        }
                    }
                    definite.isEmpty() -> fullText
                    else -> ""
                }
            }
        }
        return AsrResult(
            text = fullText,
            definiteUtterances = definite,
            interimText = interim,
            code = code,
            message = message,
        )
    }

    fun isSuccessCode(code: Int): Boolean =
        code == 0 || code == 1000 || code == 20_000_000

    private fun buildFrame(
        msgType: Int,
        flags: Int,
        serialization: Int,
        sequence: Int?,
        payload: ByteArray,
    ): ByteArray {
        val header = byteArrayOf(
            0x11,
            ((msgType shl 4) or flags).toByte(),
            ((serialization shl 4) or COMP_GZIP).toByte(),
            0x00,
        )
        val out = ByteArrayOutputStream(header.size + 8 + payload.size)
        out.write(header)
        if (sequence != null) {
            out.write(int32Be(sequence))
        }
        out.write(uint32Be(payload.size))
        out.write(payload)
        return out.toByteArray()
    }

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(data.size.coerceAtLeast(64))
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray {
        return GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
    }

    private fun int32Be(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()

    private fun uint32Be(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()

    private fun readInt32Be(data: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).int

    private fun readUInt32Be(data: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).int
}
