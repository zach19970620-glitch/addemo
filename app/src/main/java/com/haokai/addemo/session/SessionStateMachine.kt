package com.haokai.addemo.session

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.haokai.addemo.config.ConfigRepository
import com.haokai.addemo.config.DemoConfig
import com.haokai.addemo.media.MediaDirector
import com.haokai.addemo.vision.AudienceProfile
import com.haokai.addemo.vision.MoodClassifier
import com.haokai.addemo.vision.MoodKind
import com.haokai.addemo.voice.IntentTagMatcher
import com.haokai.addemo.voice.MatchResult
import java.util.UUID

/**
 * 非 IDLE 视频必须播完再切状态；人离开也等当前片结束再回 Idle。
 * 打招呼 / 心情片播放中忽略新的表情检测事件。
 */
class SessionStateMachine(
    private val configRepository: ConfigRepository,
    private val media: MediaDirector,
    private val matcher: IntentTagMatcher = IntentTagMatcher(),
    private val audienceProvider: () -> AudienceProfile = { AudienceProfile.UNKNOWN },
    private val emotionProvider: () -> Int = { -1 },
    private val onSnapshot: (SessionUiSnapshot) -> Unit = {},
    private val onSessionIdle: () -> Unit = {},
) {
    companion object {
        private const val TAG = "SessionSM"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var config: DemoConfig = configRepository.load()

    private var state: SessionState = SessionState.Idle
    private var sessionId: String = newSessionId()
    private var lastEvent: String = ""
    private var lastHit: String = ""
    private var micEnabled: Boolean = false

    /** 正在播必须播完的非 IDLE 片 */
    private var blockingPlayback: Boolean = false

    private var pendingLeave: Boolean = false
    /** Debug 强制心情；打招呼结束后优先于实时表情采样 */
    private var forcedMood: MoodKind? = null
    private var pendingDiningIntent: Boolean = false
    private var pendingBackUnico: Boolean = false
    private var pendingProductId: String? = null
    private var pendingDishId: String? = null

    /** 海报开始展示时间（elapsedRealtime）；用于 poster_min_ms */
    private var posterShownAtMs: Long = 0L
    private var posterMinLeaveRunnable: Runnable? = null

    fun start() {
        config = configRepository.load()
        clearPendings()
        resetToIdle(reason = "start")
    }

    fun reloadConfig() {
        config = configRepository.load(forceReload = true)
        media.reloadConfig()
    }

    fun currentSnapshot(): SessionUiSnapshot = SessionUiSnapshot(
        state = state,
        micEnabled = micEnabled,
        lastEvent = lastEvent,
        lastHit = lastHit,
        sessionId = sessionId,
        blockingPlayback = blockingPlayback,
        pendingLeave = pendingLeave,
    )

    fun onEvent(event: SessionEvent) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { onEvent(event) }
            return
        }
        lastEvent = event::class.simpleName.orEmpty()
        Log.d(TAG, "state=$state block=$blockingPlayback event=$lastEvent")

        if (event is SessionEvent.PersonLeft || event is SessionEvent.SystemRecover) {
            handleLeaveRequest(lastEvent)
            publish()
            return
        }

        when (state) {
            SessionState.Idle -> handleIdle(event)
            SessionState.Greeting -> handleGreeting(event)
            SessionState.MoodPlay -> handleMoodPlay(event)
            SessionState.UnicoPoster -> handleUnico(event)
            SessionState.ProductPlay -> handleProductPlay(event)
            SessionState.DiningPoster -> handleDining(event)
            SessionState.DishPlay -> handleDishPlay(event)
        }
        publish()
    }

    fun onAsrFinal(text: String) {
        if (!micEnabled) {
            Log.d(TAG, "ignore ASR (mic off): $text")
            return
        }
        // 菜品/菜单展示走服务端计划；本地仅保留产品 tag 等非菜品回退
        val diningScene = state == SessionState.DiningPoster || state == SessionState.DishPlay
        when (val result = matcher.match(text, config, diningScene = diningScene && state != SessionState.UnicoPoster)) {
            is MatchResult.Product -> onEvent(SessionEvent.VoiceProductHit(result.item.id))
            is MatchResult.Dish -> {
                // 兼容旧 config.dishes；默认已清空，展示以服务端 assets 为准
                onEvent(SessionEvent.VoiceDishHit(result.item.id))
            }
            MatchResult.DiningIntent -> onEvent(SessionEvent.VoiceDiningIntent)
            MatchResult.BackToUnico -> onEvent(SessionEvent.VoiceBackUnico)
            MatchResult.None -> {
                lastHit = "miss:$text"
                publish()
            }
        }
    }

    private fun handleLeaveRequest(reason: String) {
        // 打招呼 / 心情片播完前忽略离开：短暂漏检不应打断进海报路径
        if (state == SessionState.Greeting || state == SessionState.MoodPlay) {
            Log.i(TAG, "ignore leave during $state ($reason)")
            return
        }
        if (blockingPlayback) {
            pendingLeave = true
            forcedMood = null
            pendingDiningIntent = false
            pendingBackUnico = false
            pendingProductId = null
            pendingDishId = null
            lastHit = "pending:leave"
            Log.i(TAG, "leave deferred until MediaEnded ($reason)")
            return
        }
        if (isPosterState() && !posterMinElapsed()) {
            pendingLeave = true
            lastHit = "pending:leave:poster-min"
            schedulePosterMinLeave(reason)
            Log.i(TAG, "leave deferred until poster_min_ms ($reason)")
            return
        }
        resetToIdle(reason = reason)
    }

    private fun handleIdle(event: SessionEvent) {
        when (event) {
            SessionEvent.FaceStable -> enterGreeting()
            // Debug：Idle 下直接开打招呼并强制心情分支
            SessionEvent.EmotionHappy -> {
                forcedMood = MoodKind.Good
                enterGreeting()
            }
            SessionEvent.EmotionBad -> {
                forcedMood = MoodKind.Bad
                enterGreeting()
            }
            else -> Unit
        }
    }

    private fun handleGreeting(event: SessionEvent) {
        when (event) {
            // 播片中不打断；Debug 仅记录强制心情，结束后使用
            SessionEvent.EmotionHappy -> {
                forcedMood = MoodKind.Good
                lastHit = "force:mood_good"
            }
            SessionEvent.EmotionBad -> {
                forcedMood = MoodKind.Bad
                lastHit = "force:mood_bad"
            }
            SessionEvent.MediaEnded -> onBlockingMediaEndedFromGreeting()
            else -> Unit
        }
    }

    private fun handleMoodPlay(event: SessionEvent) {
        when (event) {
            SessionEvent.MediaEnded -> onBlockingMediaEndedFromMood()
            SessionEvent.EmotionHappy, SessionEvent.EmotionBad -> Unit
            else -> Unit
        }
    }

    private fun handleUnico(event: SessionEvent) {
        when (event) {
            is SessionEvent.VoiceProductHit -> startProduct(event.productId)
            SessionEvent.VoiceDiningIntent -> enterDining()
            SessionEvent.FaceStable -> {
                // 人回来了：取消「海报最短时长」延后离开
                if (pendingLeave) {
                    pendingLeave = false
                    cancelPosterMinLeave()
                    lastHit = "poster:leave-cancel"
                }
            }
            SessionEvent.MediaEnded -> {
                blockingPlayback = false
                // 先回到海报并完全重新起算 poster_min，再处理播放中离开
                val leavePending = pendingLeave
                pendingLeave = false
                cancelPosterMinLeave()
                micEnabled = true
                markPosterShown()
                media.showUnicoPoster()
                rearmLeaveAfterPosterRestart(leavePending)
                lastHit = "server-media:end→unico"
            }
            else -> Unit
        }
    }

    /** 服务端视频播放中临时关麦，避免误识别 */
    fun setServerMediaPlaying(playing: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { setServerMediaPlaying(playing) }
            return
        }
        if (state != SessionState.UnicoPoster && state != SessionState.DiningPoster) return
        if (playing) {
            micEnabled = false
            blockingPlayback = true
            // 取消基于进视频前海报起点的延后离开；播完回海报后会重新起算
            cancelPosterMinLeave()
            lastHit = "server-media:playing"
        } else {
            micEnabled = true
            blockingPlayback = false
        }
        publish()
    }

    private fun handleProductPlay(event: SessionEvent) {
        when (event) {
            is SessionEvent.VoiceProductHit -> {
                if (blockingPlayback) {
                    pendingProductId = event.productId
                    lastHit = "pending:product:${event.productId}"
                } else {
                    startProduct(event.productId)
                }
            }
            SessionEvent.VoiceDiningIntent -> {
                if (blockingPlayback) {
                    pendingDiningIntent = true
                    lastHit = "pending:dining"
                } else {
                    enterDining()
                }
            }
            SessionEvent.MediaEnded -> onBlockingMediaEndedFromProduct()
            else -> Unit
        }
    }

    private fun handleDining(event: SessionEvent) {
        when (event) {
            is SessionEvent.VoiceDishHit -> startDish(event.dishId)
            SessionEvent.VoiceBackUnico -> {
                if (config.features.back_to_unico) enterUnico()
            }
            SessionEvent.FaceStable -> {
                if (pendingLeave) {
                    pendingLeave = false
                    cancelPosterMinLeave()
                    lastHit = "poster:leave-cancel"
                }
            }
            SessionEvent.MediaEnded -> {
                blockingPlayback = false
                val leavePending = pendingLeave
                pendingLeave = false
                cancelPosterMinLeave()
                micEnabled = true
                markPosterShown()
                media.showDiningPoster()
                rearmLeaveAfterPosterRestart(leavePending)
                lastHit = "server-media:end→dining"
            }
            else -> Unit
        }
    }

    private fun handleDishPlay(event: SessionEvent) {
        when (event) {
            is SessionEvent.VoiceDishHit -> {
                if (blockingPlayback) {
                    pendingDishId = event.dishId
                    lastHit = "pending:dish:${event.dishId}"
                } else {
                    startDish(event.dishId)
                }
            }
            SessionEvent.MediaEnded -> onBlockingMediaEndedFromDish()
            SessionEvent.VoiceDiningIntent -> {
                if (blockingPlayback) pendingDiningIntent = true
            }
            SessionEvent.VoiceBackUnico -> {
                if (blockingPlayback) {
                    pendingBackUnico = true
                } else if (config.features.back_to_unico) {
                    enterUnico()
                }
            }
            else -> Unit
        }
    }

    private fun onBlockingMediaEndedFromGreeting() {
        blockingPlayback = false
        if (consumeLeaveIfPending()) return

        val mood = forcedMood ?: MoodClassifier.classify(emotionProvider())
        forcedMood = null
        when (mood) {
            MoodKind.Good -> enterMoodPlay(good = true)
            MoodKind.Bad -> enterMoodPlay(good = false)
            MoodKind.Neutral -> enterUnico()
        }
    }

    private fun onBlockingMediaEndedFromMood() {
        blockingPlayback = false
        if (consumeLeaveIfPending()) return
        enterUnico()
    }

    private fun onBlockingMediaEndedFromProduct() {
        blockingPlayback = false
        // 必须先回海报并 markPosterShown 完全重新起算；播放中离开延后到新的最短展示之后
        val leavePending = pendingLeave
        pendingLeave = false
        cancelPosterMinLeave()

        val next = pendingProductId
        pendingProductId = null
        if (next != null) {
            if (leavePending) pendingLeave = true
            startProduct(next)
            return
        }
        if (pendingDiningIntent) {
            pendingDiningIntent = false
            enterDining()
            rearmLeaveAfterPosterRestart(leavePending)
            return
        }
        enterUnico()
        rearmLeaveAfterPosterRestart(leavePending)
    }

    private fun onBlockingMediaEndedFromDish() {
        blockingPlayback = false
        val leavePending = pendingLeave
        pendingLeave = false
        cancelPosterMinLeave()

        val next = pendingDishId
        pendingDishId = null
        if (next != null) {
            if (leavePending) pendingLeave = true
            startDish(next)
            return
        }
        if (pendingBackUnico && config.features.back_to_unico) {
            pendingBackUnico = false
            enterUnico()
            rearmLeaveAfterPosterRestart(leavePending)
            return
        }
        pendingBackUnico = false
        enterDining()
        rearmLeaveAfterPosterRestart(leavePending)
    }

    private fun consumeLeaveIfPending(): Boolean {
        if (!pendingLeave) return false
        pendingLeave = false
        clearPendings()
        resetToIdle(reason = "PersonLeft")
        return true
    }

    private fun startProduct(productId: String) {
        val item = config.products.find { it.id == productId } ?: return
        lastHit = "product:${item.id}"
        micEnabled = false
        state = SessionState.ProductPlay
        blockingPlayback = true
        // 取消进视频前的延后离开回调；回海报时 markPosterShown 会完全重新起算
        cancelPosterMinLeave()
        media.playProduct(item)
    }

    private fun startDish(dishId: String) {
        val item = config.dishes.find { it.id == dishId } ?: return
        lastHit = "dish:${item.id}"
        micEnabled = false
        state = SessionState.DishPlay
        blockingPlayback = true
        cancelPosterMinLeave()
        media.playDish(item)
    }

    private fun enterGreeting() {
        val keepForced = forcedMood
        clearPendings()
        forcedMood = keepForced
        sessionId = newSessionId()
        micEnabled = false
        state = SessionState.Greeting
        val audience = audienceProvider()
        lastHit = "greet:${audience.label}"
        blockingPlayback = true
        media.playGreetingForAudience(audience.age, audience.gender)
    }

    private fun enterMoodPlay(good: Boolean) {
        micEnabled = false
        state = SessionState.MoodPlay
        lastHit = if (good) "mood:good" else "mood:bad"
        blockingPlayback = true
        media.playMood(good = good)
    }

    private fun enterUnico() {
        clearPendingsExceptLeave()
        if (consumeLeaveIfPending()) return
        micEnabled = true
        state = SessionState.UnicoPoster
        blockingPlayback = false
        markPosterShown()
        media.showUnicoPoster()
    }

    private fun enterDining() {
        clearPendingsExceptLeave()
        if (consumeLeaveIfPending()) return
        micEnabled = true
        state = SessionState.DiningPoster
        lastHit = "intent:dining"
        blockingPlayback = false
        markPosterShown()
        media.showDiningPoster()
    }

    private fun resetToIdle(reason: String) {
        cancelPosterMinLeave()
        clearPendings()
        micEnabled = false
        blockingPlayback = false
        lastHit = ""
        lastEvent = reason
        state = SessionState.Idle
        sessionId = newSessionId()
        media.playIdleLoop()
        onSessionIdle()
        publish()
    }

    private fun isPosterState(): Boolean =
        state == SessionState.UnicoPoster || state == SessionState.DiningPoster

    private fun markPosterShown() {
        // 每次真正展示海报时完全重新起算最短展示时长
        posterShownAtMs = SystemClock.elapsedRealtime()
        cancelPosterMinLeave()
        Log.i(TAG, "poster_min timer restarted (poster_min_ms=${config.timeouts.poster_min_ms})")
    }

    /**
     * 回海报并 markPosterShown 之后：若视频播放中曾离开，
     * 按「全新海报起点」重新调度最短展示，再回 Idle。
     */
    private fun rearmLeaveAfterPosterRestart(leavePending: Boolean) {
        if (!leavePending || !isPosterState()) return
        pendingLeave = true
        lastHit = "pending:leave:poster-min"
        schedulePosterMinLeave("PersonLeft")
        Log.i(TAG, "leave re-armed after poster_min full restart")
    }

    private fun posterMinElapsed(): Boolean {
        val minMs = config.timeouts.poster_min_ms.coerceAtLeast(0L)
        if (minMs <= 0L || posterShownAtMs <= 0L) return true
        return SystemClock.elapsedRealtime() - posterShownAtMs >= minMs
    }

    private fun schedulePosterMinLeave(reason: String) {
        cancelPosterMinLeave()
        val remain = (posterShownAtMs + config.timeouts.poster_min_ms - SystemClock.elapsedRealtime())
            .coerceAtLeast(0L)
        val runnable = Runnable {
            posterMinLeaveRunnable = null
            if (!pendingLeave) return@Runnable
            if (!isPosterState() || blockingPlayback) return@Runnable
            if (!posterMinElapsed()) {
                schedulePosterMinLeave(reason)
                return@Runnable
            }
            pendingLeave = false
            resetToIdle(reason = reason)
        }
        posterMinLeaveRunnable = runnable
        mainHandler.postDelayed(runnable, remain)
    }

    private fun cancelPosterMinLeave() {
        posterMinLeaveRunnable?.let { mainHandler.removeCallbacks(it) }
        posterMinLeaveRunnable = null
    }

    private fun clearPendings() {
        pendingLeave = false
        forcedMood = null
        pendingDiningIntent = false
        pendingBackUnico = false
        pendingProductId = null
        pendingDishId = null
    }

    private fun clearPendingsExceptLeave() {
        forcedMood = null
        pendingDiningIntent = false
        pendingBackUnico = false
        pendingProductId = null
        pendingDishId = null
    }

    private fun publish() {
        onSnapshot(currentSnapshot())
    }

    private fun newSessionId(): String = UUID.randomUUID().toString().take(8)
}
