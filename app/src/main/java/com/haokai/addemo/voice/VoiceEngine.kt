package com.haokai.addemo.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.haokai.addemo.BuildConfig
import com.haokai.addemo.config.ConfigRepository
import com.haokai.addemo.device.ActionExecutor
import com.haokai.addemo.device.DeviceApiClient
import com.haokai.addemo.device.DeviceApiException
import com.haokai.addemo.device.DeviceSessionStore
import com.haokai.addemo.device.ServerAssetCatalog
import com.haokai.addemo.device.VoiceIntentService
import com.haokai.addemo.device.VoiceTaskCreated
import com.haokai.addemo.media.MediaDirector
import com.haokai.addemo.speech.AecAudioProcessor
import com.haokai.addemo.speech.AudioPcmCapture
import com.haokai.addemo.speech.VolcAsrClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class VoiceDebugSnapshot(
    val status: String = "未开麦",
    val listening: Boolean = false,
    val partialText: String = "",
    val lastFinal: String = "",
    /** 实时字幕：已确认文本 + 当前 interim */
    val captionText: String = "",
    val error: String? = null,
    val credentialsOk: Boolean = false,
    val aecEnabled: Boolean = false,
    val serverIntent: Boolean = false,
    val lastIntent: String = "",
)

/**
 * 路线 A：本地密钥 + 定稿 → IntentTagMatcher。
 * 路线 B：Device API 激活/凭证/建任务 → 动态 WSS → asr-final → 长轮询 → ActionExecutor；
 *        未匹配或失败时回退本地 tag。
 */
class VoiceEngine(
    context: Context,
    private val configRepository: ConfigRepository,
    private val media: MediaDirector,
    private val onAsrFinal: (String) -> Unit,
    private val onServerMediaPlaying: (Boolean) -> Unit = {},
) {
    companion object {
        private const val TAG = "VoiceEngine"
        private const val RESTART_DELAY_MS = 600L
        private const val MAX_RECORD_MS = 30_000L
        /** 发完尾包后等待协议 final；超时则用已确认文本上报 */
        private const val PROTOCOL_FINAL_WAIT_MS = 3_500L
        /** 断句后静音多久收尾并上报（definite 本身已含 VAD 静音） */
        private const val DEFINITE_FINISH_DELAY_MS = 900L
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lifecycleLock = Any()
    private val micDesired = AtomicBoolean(false)
    private val sessionActive = AtomicBoolean(false)
    private val capture = AudioPcmCapture()
    private val aec = AecAudioProcessor()
    private var client: VolcAsrClient? = null
    private var useAec = false

    private val micExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "voice-mic").apply { isDaemon = true }
    }
    private val pipelineExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "voice-pipeline").apply { isDaemon = true }
    }

    private val turnGen = AtomicInteger(0)
    private val submitting = AtomicBoolean(false)

    private var intentService: VoiceIntentService? = null
    private var actionExecutor: ActionExecutor? = null
    private var assetCatalog: ServerAssetCatalog? = null
    private var cachedSmartDeviceId: String = ""
    /** 本轮已确认的 ASR 文本（用于实时字幕累计） */
    private var captionCommitted: String = ""

    private val _debug = MutableStateFlow(
        VoiceDebugSnapshot(credentialsOk = hasLocalCredentials()),
    )
    val debug: StateFlow<VoiceDebugSnapshot> = _debug.asStateFlow()

    private fun buildCaption(partial: String, committed: String = captionCommitted): String {
        val c = committed.trim()
        val p = partial.trim()
        return when {
            c.isNotEmpty() && p.isNotEmpty() -> "$c $p"
            p.isNotEmpty() -> p
            else -> c
        }
    }

    private fun resetCaption() {
        captionCommitted = ""
    }

    private fun appendCaptionCommitted(segment: String) {
        val t = segment.trim()
        if (t.isEmpty()) return
        captionCommitted = if (captionCommitted.isBlank()) t else "${captionCommitted.trim()} $t"
    }

    fun hasLocalCredentials(): Boolean {
        if (BuildConfig.VOLC_API_KEY.isNotBlank()) return true
        return BuildConfig.VOLC_APP_KEY.isNotBlank() &&
            BuildConfig.VOLC_ACCESS_KEY.isNotBlank()
    }

    fun isServerIntentEnabled(): Boolean {
        val report = configRepository.load().report
        if (!report.enabled) return false
        val base = VoiceIntentService.resolveBaseUrl(report.baseUrl)
        val serial = VoiceIntentService.resolveSerial(report.serialNumber)
        return base.isNotBlank() && serial.isNotBlank()
    }

    fun setMicEnabled(enabled: Boolean) {
        if (micDesired.getAndSet(enabled) == enabled) return
        Log.i(TAG, "micDesired=$enabled")
        if (enabled) {
            startSession()
        } else {
            turnGen.incrementAndGet()
            stopSession(status = "已关麦", error = null, restartIfDesired = false)
        }
    }

    /** 回 Idle 等场景：清掉字幕/断句残留，避免海报内容留在屏上。 */
    fun clearCaptions() {
        mainHandler.post {
            resetCaption()
            publish {
                it.copy(
                    partialText = "",
                    lastFinal = "",
                    captionText = "",
                    status = if (micDesired.get()) it.status else "未开麦",
                    listening = micDesired.get() && it.listening,
                )
            }
        }
    }

    fun release() {
        micDesired.set(false)
        turnGen.incrementAndGet()
        actionExecutor?.cancel()
        stopSession(status = "已释放", error = null, restartIfDesired = false)
        aec.release()
        micExecutor.shutdownNow()
        pipelineExecutor.shutdownNow()
    }

    /** 仅断开连接。尾包请事先调用 [VolcAsrClient.finish]，避免 finish+release 竞态丢包。 */
    private fun disposeClient() {
        val c = client ?: return
        client = null
        runCatching { c.release() }
    }

    /** 优先用协议全文，其次累计断句，再次当前 interim。 */
    private fun bestSubmitText(protocolText: String = "", lastDefinite: String = ""): String {
        return protocolText.trim()
            .ifBlank { captionCommitted.trim() }
            .ifBlank { lastDefinite.trim() }
            .ifBlank { _debug.value.partialText.trim() }
            .ifBlank { _debug.value.captionText.trim() }
    }

    private fun startSession() {
        if (isServerIntentEnabled()) {
            startServerTurn()
        } else {
            startLocalSession()
        }
    }

    private fun ensureIntentPipeline(): VoiceIntentService? {
        intentService?.let { return it }
        val report = configRepository.load().report
        val base = VoiceIntentService.resolveBaseUrl(report.baseUrl)
        if (base.isBlank()) return null
        val api = DeviceApiClient(base)
        val store = DeviceSessionStore(appContext)
        val svc = VoiceIntentService(api, store)
        intentService = svc
        val catalog = assetCatalog ?: ServerAssetCatalog(appContext, configRepository).also {
            assetCatalog = it
            Log.i(TAG, "server asset catalog size=${it.size}")
        }
        actionExecutor = ActionExecutor(
            media = media,
            catalog = catalog,
            autoPlayVideo = report.auto_play_video,
            onStatus = { msg ->
                publish { it.copy(lastIntent = msg) }
            },
            onServerMediaPlaying = { playing ->
                mainHandler.post { onServerMediaPlaying(playing) }
            },
        )
        return svc
    }

    private fun startServerTurn() {
        val myTurn = turnGen.incrementAndGet()
        synchronized(lifecycleLock) {
            if (!micDesired.get()) return
            if (sessionActive.get()) return
            sessionActive.set(true)
            submitting.set(false)
            resetCaption()
            useAec = configRepository.load().features.aec_enabled && aec.init()
            publish {
                it.copy(
                    status = "服务端：准备中…",
                    listening = true,
                    credentialsOk = true,
                    aecEnabled = useAec,
                    serverIntent = true,
                    error = null,
                )
            }
        }

        pipelineExecutor.execute {
            if (myTurn != turnGen.get() || !micDesired.get()) {
                sessionActive.set(false)
                return@execute
            }
            val report = configRepository.load().report
            val serial = VoiceIntentService.resolveSerial(report.serialNumber)
            val svc = ensureIntentPipeline()
            if (svc == null || serial.isBlank()) {
                Log.w(TAG, "server intent misconfigured, fallback local")
                sessionActive.set(false)
                if (hasLocalCredentials()) {
                    mainHandler.post { startLocalSession() }
                } else {
                    publish {
                        it.copy(
                            status = "未配置序列号/BaseURL",
                            listening = false,
                            error = "report.enabled 需 baseUrl + serialNumber",
                            serverIntent = true,
                        )
                    }
                }
                return@execute
            }

            try {
                publish { it.copy(status = "服务端：激活…") }
                var session = try {
                    svc.ensureSession(serial)
                } catch (e: DeviceApiException.Http) {
                    if (e.httpCode == 401) {
                        svc.invalidateSession()
                        svc.ensureSession(serial)
                    } else {
                        throw e
                    }
                }
                cachedSmartDeviceId = session.smartDeviceId

                publish { it.copy(status = "服务端：拉凭证…") }
                val bundle = svc.waitCredentialsReady(session.deviceToken) {
                    myTurn == turnGen.get() && micDesired.get()
                }
                val (cred, isNext) = svc.selectCredential(bundle)
                if (cred.apiKey.isBlank()) {
                    throw DeviceApiException.Protocol("empty apiKey")
                }

                if (myTurn != turnGen.get() || !micDesired.get()) {
                    sessionActive.set(false)
                    return@execute
                }

                publish { it.copy(status = "服务端：建任务…") }
                val task = svc.createTask(session.deviceToken)
                Log.i(
                    TAG,
                    "voiceTaskId=${task.voiceTaskId} providerRequestId=${task.providerRequestId}",
                )

                if (myTurn != turnGen.get() || !micDesired.get()) {
                    sessionActive.set(false)
                    return@execute
                }

                mainHandler.post {
                    connectServerAsr(
                        myTurn = myTurn,
                        apiKey = cred.apiKey,
                        resourceId = bundle.resourceId,
                        wsUrl = bundle.websocketUrl,
                        recognitionMode = bundle.recognitionMode,
                        task = task,
                        token = session.deviceToken,
                        smartDeviceId = session.smartDeviceId,
                        ackNextVersion = if (isNext) cred.version else null,
                        localFallback = report.local_fallback,
                    )
                }
            } catch (e: Exception) {
                sessionActive.set(false)
                if (useAec) {
                    aec.release()
                    useAec = false
                }
                val msg = e.message ?: e.javaClass.simpleName
                val cancelled = msg.contains("cancelled", ignoreCase = true)
                if (cancelled || !micDesired.get() || myTurn != turnGen.get()) {
                    Log.i(TAG, "server turn aborted: $msg")
                    return@execute
                }
                Log.e(TAG, "server turn prepare failed", e)
                publish {
                    it.copy(
                        status = "服务端失败→本地",
                        listening = false,
                        error = msg,
                        serverIntent = true,
                        aecEnabled = false,
                    )
                }
                if (hasLocalCredentials()) {
                    mainHandler.postDelayed({ startLocalSession() }, RESTART_DELAY_MS)
                } else {
                    mainHandler.postDelayed({ startServerTurn() }, 2_000L)
                }
            }
        }
    }

    private fun connectServerAsr(
        myTurn: Int,
        apiKey: String,
        resourceId: String,
        wsUrl: String,
        recognitionMode: String?,
        task: VoiceTaskCreated,
        token: String,
        smartDeviceId: String,
        ackNextVersion: Int?,
        localFallback: Boolean,
    ) {
        synchronized(lifecycleLock) {
            if (myTurn != turnGen.get() || !micDesired.get()) {
                sessionActive.set(false)
                return
            }
            var lastDefinite = ""
            var finished = false
            val micStarted = AtomicBoolean(false)

            // 先占位，再在 beginSubmit 里 removeCallbacks（避免局部函数前向引用编译失败）
            var synthesizeFinal: Runnable = Runnable {}
            var maxRecordRunnable: Runnable = Runnable {}
            var definiteFinishRunnable: Runnable = Runnable {}

            fun beginSubmit(reason: String, protocolText: String = "") {
                if (myTurn != turnGen.get()) return
                if (!submitting.compareAndSet(false, true)) {
                    Log.w(TAG, "skip submit($reason): already submitting")
                    return
                }
                mainHandler.removeCallbacks(maxRecordRunnable)
                mainHandler.removeCallbacks(definiteFinishRunnable)
                mainHandler.removeCallbacks(synthesizeFinal)
                val finalText = bestSubmitText(protocolText, lastDefinite)
                Log.i(TAG, "beginSubmit reason=$reason text=\"$finalText\"")
                capture.stop()
                disposeClient()
                sessionActive.set(false)
                if (useAec) {
                    aec.release()
                    useAec = false
                }
                if (finalText.isEmpty()) {
                    submitting.set(false)
                    publish {
                        it.copy(status = "无识别文本", listening = false, aecEnabled = false)
                    }
                    if (micDesired.get()) {
                        mainHandler.postDelayed({ startServerTurn() }, RESTART_DELAY_MS)
                    }
                    return
                }
                publish {
                    it.copy(
                        lastFinal = finalText,
                        captionText = finalText,
                        partialText = "",
                        status = "服务端：上报意图…",
                        listening = false,
                        aecEnabled = false,
                    )
                }
                pipelineExecutor.execute {
                    handleServerResult(
                        myTurn = myTurn,
                        token = token,
                        task = task,
                        text = finalText,
                        localFallback = localFallback,
                    )
                }
            }

            synthesizeFinal = Runnable {
                Log.w(TAG, "protocol final timeout, fallback submit")
                beginSubmit("timeout")
            }
            maxRecordRunnable = Runnable {
                if (myTurn != turnGen.get()) return@Runnable
                if (!sessionActive.get()) return@Runnable
                Log.i(TAG, "max record reached, finish ASR")
                finished = true
                capture.stop()
                runCatching { client?.finish() }
                mainHandler.postDelayed(synthesizeFinal, PROTOCOL_FINAL_WAIT_MS)
            }
            definiteFinishRunnable = Runnable {
                if (myTurn != turnGen.get()) return@Runnable
                if (!sessionActive.get() || finished) return@Runnable
                finished = true
                Log.i(TAG, "definite silence, finish ASR for upload")
                capture.stop()
                runCatching { client?.finish() }
                mainHandler.postDelayed(synthesizeFinal, PROTOCOL_FINAL_WAIT_MS)
            }

            publish { it.copy(status = "服务端：连火山…", error = null) }

            val asr = VolcAsrClient(
                appKey = "",
                accessKey = "",
                apiKey = apiKey,
                resourceId = resourceId,
                wsUrl = wsUrl,
                providerRequestId = task.providerRequestId,
                uid = smartDeviceId,
                recognitionMode = recognitionMode,
                listener = object : VolcAsrClient.Listener {
                    override fun onConnecting(logId: String) {
                        if (myTurn != turnGen.get()) return
                        publish { it.copy(status = "服务端：握手中…") }
                    }

                    override fun onSessionReady(logId: String) {
                        Log.i(TAG, "server asr ready logId=$logId")
                        if (myTurn != turnGen.get() || !micDesired.get()) return
                        if (ackNextVersion != null) {
                            pipelineExecutor.execute {
                                intentService?.ackCredentialQuietly(token, ackNextVersion)
                            }
                        }
                        publish { it.copy(status = "服务端：识别中", error = null) }
                        // 同一连接可能多次 FULL_SERVER；只开一次麦、只挂一次超时
                        if (!micStarted.compareAndSet(false, true)) {
                            Log.w(TAG, "ignore duplicate onSessionReady logId=$logId")
                            return
                        }
                        mainHandler.postDelayed(maxRecordRunnable, MAX_RECORD_MS)
                        micExecutor.execute {
                            if (myTurn != turnGen.get() || !sessionActive.get()) return@execute
                            val ok = capture.start { pcm ->
                                if (!sessionActive.get() || myTurn != turnGen.get()) return@start
                                if (useAec) {
                                    aec.process(pcm, refPcm = null) { clean ->
                                        client?.writePcm(clean)
                                    }
                                } else {
                                    client?.writePcm(pcm)
                                }
                            }
                            if (!ok) {
                                mainHandler.removeCallbacks(maxRecordRunnable)
                                stopSession("错误", "无法打开麦克风", restartIfDesired = false)
                            }
                        }
                    }

                    override fun onPartial(text: String) {
                        if (myTurn != turnGen.get()) return
                        publish {
                            it.copy(
                                partialText = text,
                                captionText = buildCaption(text),
                                status = "服务端：识别中",
                            )
                        }
                    }

                    override fun onDefinite(text: String) {
                        if (myTurn != turnGen.get()) return
                        val trimmed = text.trim()
                        if (trimmed.isEmpty()) return
                        lastDefinite = if (lastDefinite.isBlank()) {
                            trimmed
                        } else {
                            "${lastDefinite.trim()} $trimmed"
                        }
                        appendCaptionCommitted(trimmed)
                        publish {
                            it.copy(
                                lastFinal = captionCommitted,
                                partialText = "",
                                captionText = buildCaption(""),
                                status = "服务端：断句",
                            )
                        }
                        mainHandler.removeCallbacks(definiteFinishRunnable)
                        mainHandler.postDelayed(definiteFinishRunnable, DEFINITE_FINISH_DELAY_MS)
                    }

                    override fun onProtocolFinal(text: String) {
                        if (myTurn != turnGen.get()) return
                        finished = true
                        beginSubmit("protocol_final", text)
                    }

                    override fun onError(message: String) {
                        Log.e(TAG, "server asr error: $message")
                        mainHandler.removeCallbacks(maxRecordRunnable)
                        mainHandler.removeCallbacks(definiteFinishRunnable)
                        mainHandler.removeCallbacks(synthesizeFinal)
                        if (myTurn != turnGen.get()) return
                        val pending = bestSubmitText(lastDefinite = lastDefinite)
                        if (pending.isNotEmpty()) {
                            // 尾包/断连错误时仍上报已识别文本，避免「有字幕不上报」
                            Log.w(TAG, "asr error with text, still submit: $message")
                            beginSubmit("error_fallback")
                        } else {
                            stopSession("错误", message, restartIfDesired = true)
                        }
                    }

                    override fun onClosed() {
                        Log.i(TAG, "server asr closed finished=$finished")
                        if (myTurn != turnGen.get()) return
                        if (!finished) return
                        val pending = bestSubmitText(lastDefinite = lastDefinite)
                        if (pending.isNotEmpty() && !submitting.get()) {
                            Log.w(TAG, "ws closed after finish, fallback submit")
                            beginSubmit("closed_fallback")
                        }
                    }
                },
            )
            client = asr
            asr.connect()
        }
    }

    private fun handleServerResult(
        myTurn: Int,
        token: String,
        task: VoiceTaskCreated,
        text: String,
        localFallback: Boolean,
    ) {
        if (myTurn != turnGen.get()) {
            submitting.set(false)
            return
        }
        val svc = intentService
        if (svc == null) {
            Log.e(TAG, "submit aborted: intentService null")
            submitting.set(false)
            if (micDesired.get()) {
                mainHandler.postDelayed({ startServerTurn() }, RESTART_DELAY_MS)
            }
            return
        }
        var suppressMicRestart = false
        try {
            var activeText = text
            Log.i(TAG, "submit asr-final text=\"$activeText\" task=${task.voiceTaskId}")
            val result = svc.submitAndPoll(token, task, activeText)
            val match = result.decision?.matchState
            val intentCode = result.decision?.intentCode.orEmpty()
            val directives = result.interactionPlan?.presentationDirectives.orEmpty()
            val backendSummary =
                "status=${result.resultStatus} match=$match intent=$intentCode " +
                    "dirs=${directives.size} fb=${result.feedback?.messageKey.orEmpty()}"
            Log.i(TAG, "poll result: $backendSummary")
            val matched = result.resultStatus == "succeeded" && match == "matched"

            if (matched && result.interactionPlan != null) {
                mainHandler.post {
                    publish {
                        it.copy(
                            status = "服务端：执行中",
                            lastIntent = intentCode.ifBlank { "matched" },
                            error = null,
                        )
                    }
                    actionExecutor?.execute(result)
                }
            } else {
                val fb = result.feedback?.messageKey.orEmpty()
                val localPlayed = tryPlayLocalAlias(activeText)
                if (localPlayed) suppressMicRestart = true
                if (!localPlayed) {
                    publish {
                        it.copy(
                            status = "服务端：未匹配",
                            lastIntent = match.orEmpty().ifBlank { result.resultStatus.orEmpty() },
                            error = fb.ifBlank { null },
                        )
                    }
                    if (localFallback && activeText.isNotBlank()) {
                        Log.i(TAG, "fallback local tag for: $activeText")
                        mainHandler.post { onAsrFinal(activeText) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "submit/poll failed", e)
            publish {
                it.copy(
                    status = "上报失败→本地",
                    error = e.message,
                    lastIntent = "",
                )
            }
            if (localFallback && text.isNotBlank()) {
                mainHandler.post { onAsrFinal(text) }
            }
        } finally {
            submitting.set(false)
            if (!suppressMicRestart && micDesired.get() && myTurn == turnGen.get()) {
                mainHandler.postDelayed({ startServerTurn() }, RESTART_DELAY_MS)
            }
        }
    }

    /**
     * 后端 ambiguous/unknown 且无 plan 时：按演示关键词本地播片。
     * @return true 已开始本地播放
     */
    private fun tryPlayLocalAlias(text: String): Boolean {
        val alias = IntentPhraseResolver.findAlias(text) ?: return false
        ensureIntentPipeline()
        val catalog = assetCatalog ?: return false
        val uri = catalog.resolveUri(alias.assetId)
        if (uri.isNullOrBlank()) {
            Log.w(TAG, "local alias miss assetId=${alias.assetId}")
            return false
        }
        Log.i(TAG, "local alias play canonical=${alias.canonical}")
        mainHandler.post {
            publish {
                it.copy(
                    status = "本地播片：${alias.canonical}",
                    lastIntent = "local:${alias.canonical}",
                    error = null,
                )
            }
            media.setEndInterceptor(null)
            onServerMediaPlaying(true)
            media.playVideoUri(uri)
        }
        return true
    }

    private fun startLocalSession() {
        synchronized(lifecycleLock) {
            if (!micDesired.get()) return
            if (sessionActive.get()) return
            if (!hasLocalCredentials()) {
                _debug.value = VoiceDebugSnapshot(
                    status = "未配置密钥",
                    credentialsOk = false,
                    serverIntent = false,
                    error = "请在 local.properties 填写 volc.apiKey（或开启 report 服务端意图）",
                )
                return
            }

            useAec = configRepository.load().features.aec_enabled && aec.init()
            resetCaption()
            _debug.value = VoiceDebugSnapshot(
                status = "连接中…",
                listening = true,
                credentialsOk = true,
                aecEnabled = useAec,
                serverIntent = false,
                captionText = "",
            )
            sessionActive.set(true)
            val localMicStarted = AtomicBoolean(false)

            val asr = VolcAsrClient(
                appKey = BuildConfig.VOLC_APP_KEY,
                accessKey = BuildConfig.VOLC_ACCESS_KEY,
                apiKey = BuildConfig.VOLC_API_KEY,
                resourceId = BuildConfig.VOLC_RESOURCE_ID,
                wsUrl = BuildConfig.VOLC_WS_URL,
                listener = object : VolcAsrClient.Listener {
                    override fun onConnecting(logId: String) {
                        if (!sessionActive.get()) return
                        publish { it.copy(status = "握手中…", error = null) }
                    }

                    override fun onSessionReady(logId: String) {
                        Log.i(TAG, "local session ready logId=$logId")
                        if (!sessionActive.get() || !micDesired.get()) return
                        if (!localMicStarted.compareAndSet(false, true)) {
                            Log.w(TAG, "ignore duplicate local onSessionReady")
                            return
                        }
                        publish { it.copy(status = "识别中", error = null) }
                        micExecutor.execute {
                            if (!sessionActive.get() || !micDesired.get()) return@execute
                            val ok = capture.start { pcm ->
                                if (!sessionActive.get()) return@start
                                if (useAec) {
                                    aec.process(pcm, refPcm = null) { clean ->
                                        client?.writePcm(clean)
                                    }
                                } else {
                                    client?.writePcm(pcm)
                                }
                            }
                            if (!ok) {
                                stopSession("错误", "无法打开麦克风", restartIfDesired = false)
                            }
                        }
                    }

                    override fun onPartial(text: String) {
                        if (!sessionActive.get()) return
                        publish {
                            it.copy(
                                partialText = text,
                                captionText = buildCaption(text),
                                status = "识别中",
                            )
                        }
                    }

                    override fun onDefinite(text: String) {
                        if (!sessionActive.get()) return
                        val trimmed = text.trim()
                        if (trimmed.isEmpty()) return
                        Log.i(TAG, "asr final: $trimmed")
                        appendCaptionCommitted(trimmed)
                        publish {
                            it.copy(
                                lastFinal = captionCommitted,
                                partialText = "",
                                captionText = buildCaption(""),
                                status = "识别中",
                            )
                        }
                        mainHandler.post { onAsrFinal(trimmed) }
                    }

                    override fun onError(message: String) {
                        Log.e(TAG, "asr error: $message")
                        stopSession("错误", message, restartIfDesired = true)
                    }

                    override fun onClosed() {
                        Log.i(TAG, "asr closed")
                    }
                },
            )
            client = asr
            asr.connect()
        }
    }

    private fun stopSession(status: String, error: String?, restartIfDesired: Boolean) {
        synchronized(lifecycleLock) {
            val wasActive = sessionActive.getAndSet(false)
            capture.stop()
            if (wasActive || client != null) {
                disposeClient()
            } else {
                client = null
            }
            if (useAec) {
                aec.release()
                useAec = false
            }
            if (error == null) {
                resetCaption()
            }
            publish {
                it.copy(
                    status = status,
                    listening = false,
                    partialText = if (error != null) it.partialText else "",
                    lastFinal = if (error != null) it.lastFinal else "",
                    captionText = if (error != null) it.captionText else "",
                    error = error,
                    aecEnabled = false,
                    credentialsOk = hasLocalCredentials() || isServerIntentEnabled(),
                    serverIntent = isServerIntentEnabled(),
                )
            }
        }
        if (restartIfDesired && micDesired.get()) {
            mainHandler.postDelayed({ startSession() }, RESTART_DELAY_MS)
        }
    }

    private fun publish(block: (VoiceDebugSnapshot) -> VoiceDebugSnapshot) {
        _debug.value = block(_debug.value)
    }
}
