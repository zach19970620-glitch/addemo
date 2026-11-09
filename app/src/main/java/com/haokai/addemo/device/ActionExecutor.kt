package com.haokai.addemo.device

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.haokai.addemo.media.MediaDirector

/**
 * 白名单：SHOW_IMAGE(auto) / PLAY_VIDEO / START_ORDER（仅日志）。
 * 媒体：assetId 本地优先，否则签名 HTTPS URL；按 sequence 串行（含视频后继续）。
 */
class ActionExecutor(
    private val media: MediaDirector,
    private val catalog: ServerAssetCatalog,
    private val autoPlayVideo: Boolean = true,
    private val onStatus: (String) -> Unit = {},
    /** 整条指令链忙（含图片 hold / 视频），用于关麦与 defer leave */
    private val onServerMediaPlaying: (Boolean) -> Unit = {},
) {
    companion object {
        private const val TAG = "ActionExecutor"
        private const val IMAGE_HOLD_MS = 1_200L
    }

    private val main = Handler(Looper.getMainLooper())
    private var chainToken = 0

    fun execute(result: InteractionResult) {
        val plan = result.interactionPlan
        if (plan == null) {
            onStatus("无 interactionPlan")
            return
        }
        executeOperations(result)
        val steps = plan.presentationDirectives
            .sortedBy { it.sequence }
            .mapNotNull { d -> toStep(d) }
        if (steps.isEmpty()) {
            onStatus("无可执行指令")
            return
        }
        val token = ++chainToken
        main.post {
            if (token != chainToken) return@post
            // 整链期间关麦，避免 600ms 后 ASR 抢跑（尤其 IMAGE→VIDEO）
            onServerMediaPlaying(true)
            runStep(token, steps, 0)
        }
    }

    /** 取消未完成的串行链（新计划或释放时）。 */
    fun cancel() {
        chainToken++
        media.setEndInterceptor(null)
        main.removeCallbacksAndMessages(null)
        main.post { onServerMediaPlaying(false) }
    }

    private fun toStep(d: PresentationDirective): MediaStep? {
        val assetId = d.payload?.assetId
        val localUri = catalog.resolveUri(assetId)
        val rawUrl = d.payload?.url
        val remoteUrl = rawUrl?.takeIf { it.isNotBlank() && media.isAllowedMediaUri(it) }
        if (rawUrl?.isNotBlank() == true && remoteUrl == null && localUri == null) {
            Log.w(TAG, "skip ${d.type} blocked url assetId=$assetId")
            return null
        }
        val uri = localUri ?: remoteUrl
        if (uri.isNullOrBlank()) {
            Log.w(TAG, "skip ${d.type} no uri assetId=$assetId")
            return null
        }
        val source = if (localUri != null) "local" else "remote"
        return when (d.type) {
            "SHOW_IMAGE" -> {
                if (d.executionMode != "auto") {
                    Log.w(TAG, "skip SHOW_IMAGE mode=${d.executionMode}")
                    null
                } else {
                    MediaStep.Image(uri, assetId, source)
                }
            }
            "PLAY_VIDEO" -> {
                if (d.executionMode != "user_triggered" && d.executionMode != "auto") {
                    Log.w(TAG, "skip PLAY_VIDEO mode=${d.executionMode}")
                    null
                } else if (!autoPlayVideo && d.executionMode == "user_triggered") {
                    onStatus("PLAY_VIDEO 待点击(已跳过)")
                    null
                } else {
                    MediaStep.Video(uri, assetId, source)
                }
            }
            else -> {
                Log.w(TAG, "unknown directive type=${d.type}")
                null
            }
        }
    }

    private fun runStep(token: Int, steps: List<MediaStep>, index: Int) {
        if (token != chainToken) return
        if (index >= steps.size) {
            media.setEndInterceptor(null)
            onServerMediaPlaying(false)
            return
        }
        when (val step = steps[index]) {
            is MediaStep.Image -> {
                main.post {
                    if (token != chainToken) return@post
                    media.setEndInterceptor(null)
                    media.showImageUri(step.uri)
                    onStatus("SHOW_IMAGE ${step.source} ${step.assetId?.take(8) ?: ""}")
                    val hasMore = index + 1 < steps.size
                    if (hasMore) {
                        main.postDelayed({ runStep(token, steps, index + 1) }, IMAGE_HOLD_MS)
                    } else {
                        onServerMediaPlaying(false)
                    }
                }
            }
            is MediaStep.Video -> {
                main.post {
                    if (token != chainToken) return@post
                    val hasMore = index + 1 < steps.size
                    media.setEndInterceptor {
                        if (token != chainToken) return@setEndInterceptor false
                        if (hasMore) {
                            runStep(token, steps, index + 1)
                            true // 消费结束事件，继续串行
                        } else {
                            // 最后一步视频：交给 SessionSM MediaEnded（含 pendingLeave）
                            false
                        }
                    }
                    media.playVideoUri(step.uri)
                    onStatus("PLAY_VIDEO ${step.source} ${step.assetId?.take(8) ?: ""}")
                }
            }
        }
    }

    private fun executeOperations(result: InteractionResult) {
        val ops = result.interactionPlan?.availableOperations.orEmpty()
        for (op in ops) {
            if (op.type == "START_ORDER") {
                Log.i(TAG, "START_ORDER requiresConfirmation=${op.requiresConfirmation}")
                onStatus("START_ORDER(需确认·演示仅记录)")
            }
        }
    }

    private sealed class MediaStep {
        data class Image(val uri: String, val assetId: String?, val source: String) : MediaStep()
        data class Video(val uri: String, val assetId: String?, val source: String) : MediaStep()
    }
}
