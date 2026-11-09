package com.haokai.addemo.device

import android.util.Log
import com.haokai.addemo.BuildConfig
import java.util.UUID

/**
 * 激活 / 凭证 / 建任务 / asr-final / 长轮询结果。
 */
class VoiceIntentService(
    private val api: DeviceApiClient,
    private val store: DeviceSessionStore,
) {
    companion object {
        private const val TAG = "VoiceIntentService"
        private const val CRED_WAIT_MS = 120_000L
        private const val RESULT_WAIT_TOTAL_MS = 90_000L

        fun resolveBaseUrl(configBase: String): String {
            val fromConfig = configBase.trim()
            if (fromConfig.isNotBlank()) return fromConfig.trimEnd('/')
            return BuildConfig.DEVICE_API_BASE.trim().trimEnd('/')
        }

        fun resolveSerial(configSerial: String): String {
            val fromConfig = configSerial.trim()
            if (fromConfig.isNotBlank()) return fromConfig
            return BuildConfig.DEVICE_SERIAL.trim()
        }
    }

    fun ensureSession(serialNumber: String): DeviceSession {
        val existing = store.load()
        if (existing != null && !store.isExpired()) return existing
        store.clear()
        val session = api.activate(serialNumber)
        store.save(session)
        Log.i(TAG, "activated smartDeviceId=${session.smartDeviceId}")
        return session
    }

    fun invalidateSession() {
        store.clear()
    }

    fun waitCredentialsReady(
        token: String,
        shouldContinue: () -> Boolean = { true },
    ): AsrCredentialBundle {
        val deadline = System.currentTimeMillis() + CRED_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!shouldContinue()) {
                throw DeviceApiException.Protocol("credentials wait cancelled")
            }
            val (http, bundle) = api.getAsrCredentials(token)
            when (bundle.status) {
                "ready" -> {
                    if (bundle.current == null && bundle.next == null) {
                        throw DeviceApiException.Protocol("ready but no credential object")
                    }
                    Log.i(TAG, "credentials ready http=$http")
                    return bundle
                }
                "provisioning" -> {
                    val wait = (bundle.retryAfterMs ?: 1000L).coerceAtLeast(250L)
                    Log.i(TAG, "credentials provisioning, wait ${wait}ms (http=$http)")
                    Thread.sleep(wait)
                }
                "failed" -> throw DeviceApiException.Protocol(
                    "credentials failed code=${bundle.errorCode}",
                )
                else -> throw DeviceApiException.Protocol("unknown status=${bundle.status}")
            }
        }
        throw DeviceApiException.Protocol("credentials timeout")
    }

    fun selectCredential(bundle: AsrCredentialBundle): Pair<CredentialVersion, Boolean> {
        val next = bundle.next
        val current = bundle.current
        return when {
            next != null -> next to true
            current != null -> current to false
            else -> throw DeviceApiException.Protocol("no usable credential")
        }
    }

    fun ackCredentialQuietly(token: String, version: Int) {
        runCatching { api.ackCredential(token, version) }
            .onFailure { Log.w(TAG, "ack failed version=$version: ${it.message}") }
    }

    fun createTask(token: String, retryOfVoiceTaskId: String? = null): VoiceTaskCreated {
        val clientRequestId = UUID.randomUUID().toString()
        return api.createVoiceTask(token, clientRequestId, retryOfVoiceTaskId)
    }

    fun submitAndPoll(
        token: String,
        task: VoiceTaskCreated,
        text: String,
        language: String = "zh-CN",
    ): InteractionResult {
        api.submitAsrFinal(
            token = token,
            voiceTaskId = task.voiceTaskId,
            clientRequestId = task.clientRequestId,
            providerRequestId = task.providerRequestId,
            text = text,
            language = language,
        )
        val deadline = System.currentTimeMillis() + RESULT_WAIT_TOTAL_MS
        while (System.currentTimeMillis() < deadline) {
            val result = api.pollResult(token, task.voiceTaskId, waitMs = 15_000)
            if (result != null) return result
            Thread.sleep(300)
        }
        throw DeviceApiException.Protocol("poll result timeout")
    }
}
