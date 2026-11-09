package com.haokai.addemo.speech

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 火山引擎双向流式 ASR WebSocket 客户端。
 *
 * 流程：建连 → 发送 full client request → 收到首包 ACK 后再开始推音频。
 */
class VolcAsrClient(
    private val appKey: String,
    private val accessKey: String,
    private val apiKey: String,
    private val resourceId: String,
    private val wsUrl: String,
    private val listener: Listener,
    /** 生产任务下发的 X-Api-Request-Id；为空则本地生成 */
    private val providerRequestId: String? = null,
    /** 火山 user.uid / did，生产用 smartDeviceId */
    private val uid: String? = null,
    /** 凭证接口 recognitionMode：async / nostream */
    private val recognitionMode: String? = null,
) {
    interface Listener {
        /** WebSocket 已升级（尚未确认会话）。 */
        fun onConnecting(logId: String)
        /** 服务端已确认会话，可以开始推麦克风。 */
        fun onSessionReady(logId: String)
        fun onPartial(text: String)
        fun onDefinite(text: String)
        /** 协议最终帧（含 result.text），用于 asr-final */
        fun onProtocolFinal(text: String) {}
        fun onError(message: String)
        fun onClosed()
    }

    companion object {
        private const val TAG = "VolcAsrClient"
        const val SEND_CHUNK_BYTES = 3200 // ~100ms @ 16kHz/16bit/mono，降低首包与 interim 延迟
        private const val MAX_AUTO_RETRY = 1

        private val sharedHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    private val sendExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "volc-asr-send").apply { isDaemon = true }
    }

    private val lock = Any()
    private var webSocket: WebSocket? = null
    private val open = AtomicBoolean(false)
    private val sessionReady = AtomicBoolean(false)
    private val closingSend = AtomicBoolean(false)
    private val pcmBuffer = ByteArrayOutputBuffer()
    private val sessionGen = AtomicInteger(0)
    private var activeGen = 0
    private val committedKeys = LinkedHashSet<String>()
    private val seq = AtomicInteger(1)
    private var lastLogId: String = ""
    private var autoRetryLeft = MAX_AUTO_RETRY
    private var intentionalClose = false
    private var useFallbackEndpoint = false

    fun connect() {
        synchronized(lock) {
            autoRetryLeft = MAX_AUTO_RETRY
            intentionalClose = false
            useFallbackEndpoint = false
            connectInternal(retry = false)
        }
    }

    private fun resolveUrl(): String {
        val configured = wsUrl.ifBlank { VolcAsrProtocol.WS_URL_ASYNC }
        return if (useFallbackEndpoint) {
            if (configured.contains("bigmodel_async")) {
                VolcAsrProtocol.WS_URL_BIDIRECTIONAL
            } else {
                VolcAsrProtocol.WS_URL_ASYNC
            }
        } else {
            configured
        }
    }

    private fun connectInternal(retry: Boolean) {
        disconnectSocketOnly()
        activeGen = sessionGen.incrementAndGet()
        committedKeys.clear()
        pcmBuffer.clear()
        sessionReady.set(false)
        open.set(false)
        closingSend.set(false)
        seq.set(1)

        val connectId = UUID.randomUUID().toString()
        val requestId = providerRequestId?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        val rid = resourceId.ifBlank { VolcAsrProtocol.DEFAULT_RESOURCE_ID }
        val url = resolveUrl()

        val builder = Request.Builder()
            .url(url)
            .header("X-Api-Resource-Id", rid)
            .header("X-Api-Connect-Id", connectId)
            .header("X-Api-Request-Id", requestId)
            .header("X-Api-Sequence", "-1")

        // 新版控制台：X-Api-Key；旧版：App-Key + Access-Key。
        // 若两者都配了，优先旧版双头（豆包语音文档主路径），同时带上 Key 兼容。
        when {
            appKey.isNotBlank() && accessKey.isNotBlank() -> {
                builder.header("X-Api-App-Key", appKey)
                builder.header("X-Api-Access-Key", accessKey)
                if (apiKey.isNotBlank()) builder.header("X-Api-Key", apiKey)
            }
            apiKey.isNotBlank() -> {
                builder.header("X-Api-Key", apiKey)
                // 兼容：部分控制台把同一串既当 Key 也当 Token
                builder.header("X-Api-Access-Key", apiKey)
            }
            else -> {
                listener.onError("未配置鉴权密钥")
                return
            }
        }

        Log.i(TAG, "connecting url=$url resource=$rid retry=$retry")
        val gen = activeGen
        webSocket = sharedHttp.newWebSocket(
            builder.build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (gen != activeGen) return
                    lastLogId = response.header("X-Tt-Logid").orEmpty()
                    Log.i(TAG, "ws open logid=$lastLogId code=${response.code}")
                    open.set(true)
                    listener.onConnecting(lastLogId)
                    sendExecutor.execute {
                        if (gen != activeGen) return@execute
                        try {
                            val s = seq.getAndIncrement()
                            val userId = uid?.takeIf { it.isNotBlank() } ?: connectId
                            val frame = VolcAsrProtocol.buildFullClientRequest(
                                userId,
                                s,
                                recognitionMode,
                            )
                            val ok = webSocket.send(frame.toByteString())
                            if (!ok) {
                                failAndMaybeRetry("发送开场请求失败", allowRetry = true)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "send full request failed", e)
                            failAndMaybeRetry(e.message ?: "开场请求异常", allowRetry = true)
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (gen != activeGen) return
                    handleBinary(bytes.toByteArray())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (gen != activeGen) return
                    val code = response?.code
                    val body = try {
                        response?.body?.string()?.take(240)
                    } catch (_: Exception) {
                        null
                    }
                    Log.e(TAG, "ws failure code=$code body=$body logid=$lastLogId", t)
                    open.set(false)
                    sessionReady.set(false)
                    val detail = buildString {
                        append(t.message ?: t.javaClass.simpleName)
                        if (code != null) append(" (HTTP $code)")
                        if (!body.isNullOrBlank()) append(": $body")
                        if (lastLogId.isNotBlank()) append(" [logid=$lastLogId]")
                    }
                    val retryable = !intentionalClose &&
                        (t is SocketException || detail.contains("abort", ignoreCase = true))
                    failAndMaybeRetry(detail, allowRetry = retryable)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "ws closing code=$code reason=$reason")
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (gen != activeGen) return
                    Log.i(TAG, "ws closed code=$code reason=$reason")
                    open.set(false)
                    sessionReady.set(false)
                    if (!intentionalClose && code != 1000) {
                        failAndMaybeRetry(
                            "连接关闭 code=$code reason=$reason",
                            allowRetry = true,
                        )
                    } else {
                        listener.onClosed()
                    }
                }
            },
        )
    }

    fun writePcm(pcm: ByteArray) {
        if (closingSend.get() || !sessionReady.get() || !open.get()) return
        sendExecutor.execute {
            if (closingSend.get() || !sessionReady.get() || !open.get()) return@execute
            synchronized(pcmBuffer) {
                pcmBuffer.write(pcm)
                while (pcmBuffer.size() >= SEND_CHUNK_BYTES) {
                    val chunk = pcmBuffer.read(SEND_CHUNK_BYTES)
                    sendAudioLocked(chunk, final = false)
                }
            }
        }
    }

    fun finish() {
        closingSend.set(true)
        sessionReady.set(false)
        sendExecutor.execute {
            synchronized(pcmBuffer) {
                if (!open.get()) return@execute
                val rest = pcmBuffer.drain()
                if (rest.isNotEmpty()) {
                    sendAudioLocked(rest, final = false)
                }
                sendAudioLocked(ByteArray(0), final = true)
                Log.i(TAG, "sent audio final frame")
            }
        }
    }

    fun disconnect() {
        synchronized(lock) {
            intentionalClose = true
            autoRetryLeft = 0
            activeGen = sessionGen.incrementAndGet()
            sessionReady.set(false)
            open.set(false)
            disconnectSocketOnly()
        }
    }

    fun release() {
        disconnect()
        sendExecutor.shutdownNow()
    }

    private fun disconnectSocketOnly() {
        try {
            webSocket?.cancel()
        } catch (_: Exception) {
        }
        webSocket = null
    }

    private fun failAndMaybeRetry(message: String, allowRetry: Boolean) {
        synchronized(lock) {
            if (intentionalClose) {
                listener.onClosed()
                return
            }
            if (allowRetry && autoRetryLeft > 0) {
                autoRetryLeft -= 1
                useFallbackEndpoint = true
                Log.w(TAG, "retry after failure: $message")
                connectInternal(retry = true)
                return
            }
        }
        listener.onError(message)
        listener.onClosed()
    }

    private fun sendAudioLocked(pcm: ByteArray, final: Boolean) {
        val ws = webSocket ?: return
        try {
            val s = seq.getAndIncrement()
            val frame = VolcAsrProtocol.buildAudioOnlyRequest(pcm, s, final)
            val ok = ws.send(frame.toByteString())
            if (!ok) {
                Log.w(TAG, "send audio failed final=$final")
            }
        } catch (e: Exception) {
            Log.e(TAG, "send audio exception", e)
            failAndMaybeRetry(e.message ?: "发送音频失败", allowRetry = false)
        }
    }

    private fun handleBinary(data: ByteArray) {
        try {
            val frame = VolcAsrProtocol.parseServerFrame(data)
            when (frame.msgType) {
                VolcAsrProtocol.MSG_ERROR -> {
                    val detail = String(frame.payload, Charsets.UTF_8)
                    failAndMaybeRetry(
                        "服务端错误 ${frame.errorCode}: $detail",
                        allowRetry = false,
                    )
                }
                VolcAsrProtocol.MSG_FULL_SERVER -> {
                    val result = VolcAsrProtocol.parseAsrResult(frame.payload)
                    if (!VolcAsrProtocol.isSuccessCode(result.code)) {
                        failAndMaybeRetry(
                            "识别错误 ${result.code}: ${result.message ?: "unknown"}",
                            allowRetry = false,
                        )
                        return
                    }
                    if (!sessionReady.getAndSet(true)) {
                        Log.i(TAG, "session ready logid=$lastLogId")
                        listener.onSessionReady(lastLogId)
                    }
                    for (utt in result.definiteUtterances) {
                        if (committedKeys.add(utt.dedupeKey)) {
                            if (committedKeys.size > 200) {
                                committedKeys.remove(committedKeys.first())
                            }
                            listener.onDefinite(utt.text)
                        }
                    }
                    val partial = result.interimText
                    if (partial.isNotEmpty()) {
                        listener.onPartial(partial)
                    } else if (result.definiteUtterances.isNotEmpty()) {
                        listener.onPartial("")
                    }
                    if (frame.isFinal) {
                        val finalText = result.text.trim().ifBlank {
                            result.definiteUtterances.lastOrNull()?.text.orEmpty()
                        }
                        listener.onProtocolFinal(finalText)
                    }
                }
                else -> Log.w(TAG, "ignore msgType=${frame.msgType}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "parse frame failed", e)
            failAndMaybeRetry(e.message ?: "解析响应失败", allowRetry = false)
        }
    }
}

private class ByteArrayOutputBuffer {
    private var buf = ByteArray(0)
    private var len = 0

    fun size(): Int = len

    fun clear() {
        len = 0
    }

    fun write(data: ByteArray) {
        ensure(len + data.size)
        System.arraycopy(data, 0, buf, len, data.size)
        len += data.size
    }

    fun read(n: Int): ByteArray {
        val out = buf.copyOfRange(0, n)
        val remain = len - n
        if (remain > 0) {
            System.arraycopy(buf, n, buf, 0, remain)
        }
        len = remain
        return out
    }

    fun drain(): ByteArray {
        val out = buf.copyOfRange(0, len)
        len = 0
        return out
    }

    private fun ensure(capacity: Int) {
        if (buf.size >= capacity) return
        var next = if (buf.isEmpty()) 8192 else buf.size
        while (next < capacity) next *= 2
        buf = buf.copyOf(next)
    }
}
