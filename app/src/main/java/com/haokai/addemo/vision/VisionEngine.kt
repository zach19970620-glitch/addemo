package com.haokai.addemo.vision

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.haokai.addemo.config.ConfigRepository
import com.haokai.addemo.session.SessionEvent
import com.haokai.hk5mh210.camera.FrameCaptureManager
import com.haokai.hk5mh210.camera.UsbCameraMonitor
import com.haokai.hk5mh210.inference.FaceResult
import com.haokai.hk5mh210.inference.InferenceBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class VisionDebugSnapshot(
    val status: String = "未启动",
    val faceCount: Int = 0,
    val hasPrimary: Boolean = false,
    val dwellMs: Long = 0,
    val enterMs: Long = 5000,
    val emotion: String = "-",
    val age: Int = -1,
    val gender: String = "-",
    val audience: String = "-",
    val cameraLabel: String = "",
    val engaged: Boolean = false,
)

/**
 * 时长检测：主脸稳定 ≥ enter_ms → FaceStable。
 * 表情不主动打断播片；由状态机在打招呼结束后采样 [currentEmotion]。
 */
class VisionEngine(
    context: Context,
    private val configRepository: ConfigRepository,
    private val onEvent: (SessionEvent) -> Unit,
) {
    companion object {
        private const val TAG = "VisionEngine"
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val captureManager = FrameCaptureManager(appContext)
    private val inferenceBridge = InferenceBridge(appContext)
    private val usbMonitor = UsbCameraMonitor(appContext)

    private var dwellTracker: DwellLeaveTracker? = null
    private var collectJob: Job? = null
    private var running = false
    private var enterMs: Long = 5_000L
    private var lifecycleOwner: LifecycleOwner? = null
    private var previewView: PreviewView? = null

    private val _debug = MutableStateFlow(VisionDebugSnapshot())
    val debug: StateFlow<VisionDebugSnapshot> = _debug.asStateFlow()

    @Volatile
    private var latestAudience: AudienceProfile = AudienceProfile.UNKNOWN

    @Volatile
    private var latestEmotion: Int = -1

    /** 当前主脸受众画像，供打招呼选片 / 菜品门控 */
    fun currentAudience(): AudienceProfile = latestAudience

    /** 当前主脸表情枚举（见 [FaceResult]），供打招呼结束后心情分支 */
    fun currentEmotion(): Int = latestEmotion

    /** Session 回 Idle 后重置停留/离开边沿，允许下一轮 FaceStable */
    fun onSessionReturnedToIdle() {
        dwellTracker?.reset()
        latestAudience = AudienceProfile.UNKNOWN
        latestEmotion = -1
    }

    /**
     * 启动视觉管线。RKNN 模型初始化较慢，必须在 IO 线程执行，避免主线程 ANR。
     * CameraX / Flow 收集仍回到主线程。
     */
    suspend fun start(owner: LifecycleOwner, preview: PreviewView) {
        if (running) return
        lifecycleOwner = owner
        previewView = preview

        val timeouts = configRepository.load().timeouts
        enterMs = timeouts.enter_ms
        dwellTracker = DwellLeaveTracker(
            enterMs = timeouts.enter_ms,
            leaveMs = timeouts.leave_ms,
            cooldownMs = timeouts.cooldown_ms,
        )
        latestEmotion = -1
        _debug.value = VisionDebugSnapshot(
            status = "正在加载人脸模型…",
            enterMs = enterMs,
        )

        val ok = withContext(Dispatchers.IO) {
            inferenceBridge.start()
        }
        if (!ok) {
            _debug.value = VisionDebugSnapshot(
                status = "推理初始化失败: ${com.haokai.hk5mh210.inference.RkDlNative.lastError}",
                enterMs = enterMs,
            )
            Log.e(TAG, "inference start failed: ${com.haokai.hk5mh210.inference.RkDlNative.lastError}")
            return
        }

        captureManager.onFrame = { frame ->
            inferenceBridge.onFrame(frame)
        }

        // 先置位，避免子协程 while(running) 在启动瞬间直接退出
        running = true
        collectJob = owner.lifecycleScope.launch {
            launch {
                inferenceBridge.faces.collectLatest { faces ->
                    onFaces(faces)
                }
            }
            launch {
                inferenceBridge.status.collectLatest { status ->
                    _debug.value = _debug.value.copy(status = status)
                }
            }
            launch {
                captureManager.stats.collectLatest { stats ->
                    _debug.value = _debug.value.copy(cameraLabel = stats.cameraLabel)
                }
            }
            /**
             * faces 为 StateFlow：连续 emptyList 因 equals 不重发，
             * onFaces/onPrimaryMissing 只会跑一次，leave_ms 计时冻住。
             * 定时用当前 faces 快照推进停留/离开边沿。
             */
            launch {
                while (isActive) {
                    delay(250)
                    if (!running) continue
                    onFaces(inferenceBridge.faces.value)
                }
            }
        }

        usbMonitor.start()
        owner.lifecycleScope.launch {
            runCatching {
                captureManager.start(owner, preview)
            }.onFailure { e ->
                Log.e(TAG, "camera start failed", e)
                _debug.value = _debug.value.copy(status = "相机启动失败: ${e.message}")
            }
        }
    }

    fun stop() {
        running = false
        collectJob?.cancel()
        collectJob = null
        captureManager.onFrame = null
        captureManager.stop()
        inferenceBridge.stop()
        usbMonitor.stop()
        dwellTracker?.reset()
        latestAudience = AudienceProfile.UNKNOWN
        latestEmotion = -1
        _debug.value = VisionDebugSnapshot(status = "已停止", enterMs = enterMs)
    }

    fun release() {
        stop()
        captureManager.release()
        inferenceBridge.release()
        lifecycleOwner = null
        previewView = null
    }

    private fun onFaces(faces: List<FaceResult>) {
        val now = System.currentTimeMillis()
        val tracker = dwellTracker ?: return
        val (fw, fh) = inferenceBridge.frameSize.value
        val primary = PrimaryFaceSelector.select(faces, fw, fh)
        latestAudience = if (primary != null) {
            AudienceProfile(age = primary.age, gender = primary.gender)
        } else {
            AudienceProfile.UNKNOWN
        }
        latestEmotion = primary?.emotion ?: -1

        val edge = if (primary != null) {
            tracker.onPrimaryPresent(now)
        } else {
            tracker.onPrimaryMissing(now)
        }

        when (edge) {
            DwellLeaveTracker.Edge.FaceStable -> emit(SessionEvent.FaceStable)
            DwellLeaveTracker.Edge.PersonLeft -> {
                latestEmotion = -1
                emit(SessionEvent.PersonLeft)
            }
            DwellLeaveTracker.Edge.None -> Unit
        }

        _debug.value = _debug.value.copy(
            faceCount = faces.size,
            hasPrimary = primary != null,
            dwellMs = tracker.dwellMs(now),
            enterMs = enterMs,
            emotion = primary?.emotionText ?: "-",
            age = primary?.age ?: -1,
            gender = primary?.genderText ?: "-",
            audience = latestAudience.label,
            engaged = tracker.isEngaged(),
            status = inferenceBridge.status.value,
        )
    }

    private fun emit(event: SessionEvent) {
        Log.i(TAG, "emit $event")
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onEvent(event)
        } else {
            mainHandler.post { onEvent(event) }
        }
    }
}
