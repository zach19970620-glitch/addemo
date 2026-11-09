package com.haokai.addemo.device

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

class DeviceApiClient(
    private val baseUrl: String,
    private val gson: Gson = Gson(),
) {
    companion object {
        private const val TAG = "DeviceApi"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    fun activate(serialNumber: String): DeviceSession {
        val body = gson.toJson(mapOf("serialNumber" to serialNumber))
        val root = requestJson(
            method = "POST",
            path = "/api/v1/device/activate",
            token = null,
            jsonBody = body,
            expectHttp = setOf(200),
        )
        val data = root.getAsJsonObject("data")
            ?: throw DeviceApiException.Protocol("activate missing data")
        val smartDeviceId = data.get("smartDeviceId")?.asString.orEmpty()
        val deviceToken = data.get("deviceToken")?.asString.orEmpty()
        val expiresAt = data.get("tokenExpiresAt")?.asString
        if (smartDeviceId.isBlank() || deviceToken.isBlank()) {
            throw DeviceApiException.Protocol("activate incomplete")
        }
        return DeviceSession(
            smartDeviceId = smartDeviceId,
            deviceToken = deviceToken,
            tokenExpiresAtEpochMs = parseIsoToEpochMs(expiresAt),
        )
    }

    fun getAsrCredentials(token: String): Pair<Int, AsrCredentialBundle> {
        val (code, root) = requestJsonWithCode(
            method = "GET",
            path = "/api/v1/device/asr-credentials",
            token = token,
            jsonBody = null,
            acceptHttp = setOf(200, 202, 503),
        )
        val data = root.getAsJsonObject("data")
            ?: throw DeviceApiException.Protocol("credentials missing data")
        return code to gson.fromJson(data, AsrCredentialBundle::class.java)
    }

    fun ackCredential(token: String, version: Int) {
        val body = gson.toJson(mapOf("version" to version))
        requestJson(
            method = "POST",
            path = "/api/v1/device/asr-credentials/ack",
            token = token,
            jsonBody = body,
            expectHttp = setOf(200),
        )
    }

    fun createVoiceTask(
        token: String,
        clientRequestId: String,
        retryOfVoiceTaskId: String? = null,
    ): VoiceTaskCreated {
        val payload = mutableMapOf<String, Any>("clientRequestId" to clientRequestId)
        if (!retryOfVoiceTaskId.isNullOrBlank()) {
            payload["retryOfVoiceTaskId"] = retryOfVoiceTaskId
        }
        val root = requestJson(
            method = "POST",
            path = "/api/v1/voice-tasks",
            token = token,
            jsonBody = gson.toJson(payload),
            expectHttp = setOf(201),
        )
        val data = root.getAsJsonObject("data")
            ?: throw DeviceApiException.Protocol("create task missing data")
        return gson.fromJson(data, VoiceTaskCreated::class.java)
    }

    fun submitAsrFinal(
        token: String,
        voiceTaskId: String,
        clientRequestId: String,
        providerRequestId: String,
        text: String,
        language: String = "zh-CN",
    ) {
        val payload = mapOf(
            "clientRequestId" to clientRequestId,
            "providerRequestId" to providerRequestId,
            "text" to text,
            "language" to language,
        )
        requestJson(
            method = "POST",
            path = "/api/v1/voice-tasks/$voiceTaskId/asr-final",
            token = token,
            jsonBody = gson.toJson(payload),
            expectHttp = setOf(202),
        )
    }

    /**
     * @return null if still processing (HTTP 202)
     */
    fun pollResult(token: String, voiceTaskId: String, waitMs: Int = 15_000): InteractionResult? {
        val (code, root) = requestJsonWithCode(
            method = "GET",
            path = "/api/v1/voice-tasks/$voiceTaskId/result?waitMs=$waitMs",
            token = token,
            jsonBody = null,
            acceptHttp = setOf(200, 202),
        )
        if (code == 202) return null
        val data = root.getAsJsonObject("data")
            ?: throw DeviceApiException.Protocol("result missing data")
        return parseInteractionResult(data)
    }

    fun refreshAsset(token: String, voiceTaskId: String, assetId: String): MediaPayload {
        val root = requestJson(
            method = "GET",
            path = "/api/v1/voice-tasks/$voiceTaskId/assets/$assetId",
            token = token,
            jsonBody = null,
            expectHttp = setOf(200),
        )
        val data = root.getAsJsonObject("data")
            ?: throw DeviceApiException.Protocol("asset missing data")
        return gson.fromJson(data, MediaPayload::class.java)
    }

    private fun parseInteractionResult(data: JsonObject): InteractionResult {
        // Flexible parse: decision / feedback / interactionPlan may be absent.
        return InteractionResult(
            schemaVersion = data.get("schemaVersion")?.asString,
            voiceTaskId = data.get("voiceTaskId")?.asString,
            catalogVersion = data.get("catalogVersion")?.asString,
            resultStatus = data.get("resultStatus")?.asString,
            decision = data.getAsJsonObject("decision")?.let {
                gson.fromJson(it, InteractionDecision::class.java)
            },
            feedback = data.getAsJsonObject("feedback")?.let {
                gson.fromJson(it, InteractionFeedback::class.java)
            },
            interactionPlan = data.getAsJsonObject("interactionPlan")?.let { plan ->
                val directives = plan.getAsJsonArray("presentationDirectives")
                    ?.let { arr ->
                        gson.fromJson<List<PresentationDirective>>(
                            arr,
                            object : TypeToken<List<PresentationDirective>>() {}.type,
                        )
                    }.orEmpty()
                val ops = plan.getAsJsonArray("availableOperations")
                    ?.let { arr ->
                        gson.fromJson<List<AvailableOperation>>(
                            arr,
                            object : TypeToken<List<AvailableOperation>>() {}.type,
                        )
                    }.orEmpty()
                InteractionPlan(
                    schemaVersion = plan.get("schemaVersion")?.asString,
                    voiceTaskId = plan.get("voiceTaskId")?.asString,
                    presentationDirectives = directives,
                    availableOperations = ops,
                )
            },
        )
    }

    private fun requestJson(
        method: String,
        path: String,
        token: String?,
        jsonBody: String?,
        expectHttp: Set<Int>,
    ): JsonObject {
        val (code, root) = requestJsonWithCode(method, path, token, jsonBody, expectHttp)
        if (code !in expectHttp) {
            val biz = root.get("code")?.asString
            throw DeviceApiException.Http(code, biz, root.get("messageKey")?.asString ?: "")
        }
        val biz = root.get("code")?.asString
        if (biz != null && biz != "ok") {
            throw DeviceApiException.Http(code, biz, root.get("messageKey")?.asString ?: biz)
        }
        return root
    }

    private fun requestJsonWithCode(
        method: String,
        path: String,
        token: String?,
        jsonBody: String?,
        acceptHttp: Set<Int>,
    ): Pair<Int, JsonObject> {
        val url = baseUrl.trimEnd('/') + path
        val builder = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("X-Request-Id", UUID.randomUUID().toString())
            .header("X-Locale", "zh-CN")
        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $token")
        }
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post((jsonBody ?: "{}").toRequestBody(JSON))
            else -> error("unsupported $method")
        }
        http.newCall(builder.build()).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            val code = resp.code
            Log.d(TAG, "$method $path -> $code bytes=${raw.length}")
            val root = try {
                JsonParser.parseString(raw).asJsonObject
            } catch (e: Exception) {
                throw DeviceApiException.Protocol("invalid json HTTP $code: ${raw.take(200)}")
            }
            if (code !in acceptHttp) {
                val biz = root.get("code")?.asString
                throw DeviceApiException.Http(
                    code,
                    biz,
                    root.get("messageKey")?.asString ?: raw.take(160),
                )
            }
            return code to root
        }
    }

    private fun parseIsoToEpochMs(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0L
        val patterns = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX",
        )
        for (p in patterns) {
            runCatching {
                val sdf = SimpleDateFormat(p, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                return sdf.parse(iso)?.time ?: 0L
            }
        }
        return 0L
    }
}
