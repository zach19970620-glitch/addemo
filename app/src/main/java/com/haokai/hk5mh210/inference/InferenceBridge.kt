package com.haokai.hk5mh210.inference

import android.content.Context
import android.util.Log
import com.haokai.hk5mh210.camera.FrameData
import com.haokai.hk5mh210.camera.Nv21Cropper
import com.haokai.hk5mh210.camera.Nv21Resizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/**
 * 将抓帧回调接到 RKNN 人脸推理。
 * 推理在独立单线程执行；空结果会短暂保持上一帧，避免 UI 闪烁。
 */
class InferenceBridge(private val context: Context) {

    companion object {
        private const val TAG = "InferenceBridge"
        private const val MAX_ATTRIBUTE_ATTEMPTS = 3
        private const val MIN_ATTRIBUTE_ROI_WIDTH = 32
        /** 官方：1080P 源图人脸宽 ≥45px 可稳定检测 */
        private const val STABLE_FACE_WIDTH_PX = 45
        /** 官方：极限约 25px */
        private const val LIMIT_FACE_WIDTH_PX = 25
        private const val DIAG_LOG_INTERVAL_MS = 1_000L
    }

    private val ready = AtomicBoolean(false)
    private val inferBusy = AtomicBoolean(false)
    private val pendingFrame = AtomicReference<FrameData?>(null)
    private val inferExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    /** 串行化 start/stop/release，避免与异步 DL_Exit / 模型拷贝竞态。 */
    private val lifecycleLock = Any()
    private val attributeTracker = FaceAttributeTracker()
    private var lastDiagLogAtMs = 0L
    private var lastDiagKey: String? = null

    private val _faces = MutableStateFlow<List<FaceResult>>(emptyList())
    val faces: StateFlow<List<FaceResult>> = _faces.asStateFlow()

    private val _status = MutableStateFlow("未初始化")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _frameSize = MutableStateFlow(0 to 0)
    val frameSize: StateFlow<Pair<Int, Int>> = _frameSize.asStateFlow()

    private val _frameRotation = MutableStateFlow(0)
    val frameRotation: StateFlow<Int> = _frameRotation.asStateFlow()

    private val _liveStatus = MutableStateFlow("DET idle · ATTR idle")
    val liveStatus: StateFlow<String> = _liveStatus.asStateFlow()

    /**
     * 实时核对：对照官方 1080P 人脸宽 45/25px 阈值。
     * [faceWidthPx] 为映射回相机源帧后的框宽；漏检时为 null。
     */
    data class FaceScaleDiag(
        val inputWidth: Int,
        val inputHeight: Int,
        val path: String,
        val faceCount: Int,
        val faceWidthPx: Int?,
        val faceHeightPx: Int?,
        val score: Float?,
        val verdict: String,
    )

    private val _faceScaleDiag = MutableStateFlow<FaceScaleDiag?>(null)
    val faceScaleDiag: StateFlow<FaceScaleDiag?> = _faceScaleDiag.asStateFlow()

    private data class LiveDetection(
        val faces: List<FaceResult>,
        val usedZoomFallback: Boolean,
        val attributeFrame: FrameData,
        val attributeDetections: List<FaceResult>,
        val offsetX: Int = 0,
        val offsetY: Int = 0,
        val inputWidth: Int,
        val inputHeight: Int,
    )

    fun start(): Boolean {
        synchronized(lifecycleLock) {
            if (ready.get()) return true
            return try {
                val modelDir = ModelInstaller.install(context)
                val ok = RkDlNative.init(modelDir.absolutePath)
                if (ok) {
                    ready.set(true)
                    _status.value = "人脸推理就绪"
                    Log.i(TAG, "init ok, models=${modelDir.absolutePath}")
                } else {
                    _status.value = "推理初始化失败: ${RkDlNative.lastError}"
                    Log.e(TAG, _status.value)
                }
                ok
            } catch (t: Throwable) {
                _status.value = "推理初始化异常: ${t.message}"
                Log.e(TAG, "start failed", t)
                false
            }
        }
    }

    fun onFrame(frame: FrameData) {
        if (!ready.get()) return
        _frameSize.value = frame.width to frame.height
        _frameRotation.value = frame.rotationDegrees
        // 只保留最新待处理帧，避免推理较慢时持续处理过期画面。
        // FrameData 持有独立 ByteArray，ImageProxy 关闭后仍可安全使用，无需再次复制。
        pendingFrame.set(frame)
        scheduleInference()
    }

    /**
     * 单张照片识别：与实时流共用同一推理线程，避免与 [onFrame] 并发踩库。
     * 返回检测结果，并带库写入的叠加调试图（属性文字通常画在这张图上）。
     */
    suspend fun recognizeStill(
        nv21: ByteArray,
        width: Int,
        height: Int,
        frameNo: Long = System.currentTimeMillis(),
    ): ProcessResult {
        if (!start()) {
            throw IllegalStateException(_status.value)
        }
        return suspendCancellableCoroutine { cont ->
            inferExecutor.execute {
                try {
                    pendingFrame.set(null)
                    val output = RkDlNative.processNv21(
                        nv21 = nv21,
                        width = width,
                        height = height,
                        frameNo = frameNo,
                        procType = RkDlNative.PROC_TYPE_FACE,
                        includeDebug = true,
                    )
                    val faces = output.faces.toList()
                    attributeTracker.clear()
                    _frameSize.value = width to height
                    _faces.value = faces
                    if (cont.isActive) cont.resume(output)
                } catch (t: Throwable) {
                    Log.e(TAG, "recognizeStill failed", t)
                    _status.value = "照片识别异常: ${t.message}"
                    if (cont.isActive) cont.resumeWithException(t)
                }
            }
        }
    }

    private fun scheduleInference() {
        if (!ready.get() || !inferBusy.compareAndSet(false, true)) return
        inferExecutor.execute {
            try {
                while (ready.get()) {
                    val frame = pendingFrame.getAndSet(null) ?: break
                    val detection = detectLiveFrame(frame)
                    if (ready.get()) {
                        processLiveResults(
                            detection = detection,
                        )
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "onFrame inference failed", t)
                _status.value = "推理异常: ${t.message}"
            } finally {
                inferBusy.set(false)
                if (ready.get() && pendingFrame.get() != null) {
                    scheduleInference()
                }
            }
        }
    }

    private fun detectLiveFrame(frame: FrameData): LiveDetection {
        val fullFrameFaces = RkDlNative.processNv21(
            nv21 = frame.nv21,
            width = frame.width,
            height = frame.height,
            frameNo = frame.frameIndex * 10,
            procType = RkDlNative.PROC_TYPE_FACE_DET_ONLY,
            includeDebug = false,
        ).faces.toList()
        if (fullFrameFaces.isNotEmpty()) {
            return LiveDetection(
                faces = fullFrameFaces,
                usedZoomFallback = false,
                attributeFrame = frame,
                attributeDetections = fullFrameFaces,
                inputWidth = frame.width,
                inputHeight = frame.height,
            )
        }

        val crop = Nv21Cropper.centerZoom(frame.nv21, frame.width, frame.height)
        val localZoomFaces = RkDlNative.processNv21(
            nv21 = crop.nv21,
            width = crop.width,
            height = crop.height,
            frameNo = frame.frameIndex * 10 + 1,
            procType = RkDlNative.PROC_TYPE_FACE_DET_ONLY,
            includeDebug = false,
        ).faces.toList()
        val zoomFaces = localZoomFaces.map { face ->
            face.copy(
                left = face.left + crop.offsetX,
                top = face.top + crop.offsetY,
                right = face.right + crop.offsetX,
                bottom = face.bottom + crop.offsetY,
            )
        }
        return LiveDetection(
            faces = zoomFaces,
            usedZoomFallback = true,
            attributeFrame = FrameData(
                nv21 = crop.nv21,
                width = crop.width,
                height = crop.height,
                timestampNs = frame.timestampNs,
                rotationDegrees = frame.rotationDegrees,
                frameIndex = frame.frameIndex,
            ),
            attributeDetections = localZoomFaces,
            offsetX = crop.offsetX,
            offsetY = crop.offsetY,
            inputWidth = frame.width,
            inputHeight = frame.height,
        )
    }

    private fun processLiveResults(detection: LiveDetection) {
        val now = System.currentTimeMillis()
        val detections = detection.faces
        attributeTracker.updateDetections(detections, now)

        if (detections.isNotEmpty() && attributeTracker.shouldRunAttributes(now)) {
            attributeTracker.markAttributeAttempt(now)
            val attributes = recognizeLiveAttributes(
                frame = detection.attributeFrame,
                initialDetections = detection.attributeDetections,
            ).map { face ->
                face.copy(
                    left = face.left + detection.offsetX,
                    top = face.top + detection.offsetY,
                    right = face.right + detection.offsetX,
                    bottom = face.bottom + detection.offsetY,
                )
            }
            attributeTracker.ingestAttributes(attributes, now)
        }

        val merged = attributeTracker.mergedFaces(now)
        _faces.value = merged
        val diag = buildFaceScaleDiag(detection, merged)
        _faceScaleDiag.value = diag
        maybeLogFaceScaleDiag(diag, now)
        _liveStatus.value =
            "DET${if (detection.usedZoomFallback) " ZOOM" else ""} ${detections.size} · " +
                attributeTracker.status(now) +
                " · ${formatFaceScaleShort(diag)}"
    }

    private fun buildFaceScaleDiag(
        detection: LiveDetection,
        merged: List<FaceResult>,
    ): FaceScaleDiag {
        val primary = detection.faces.maxByOrNull { faceWidthPx(it) }
            ?: merged.maxByOrNull { faceWidthPx(it) }
        val widthPx = primary?.let { faceWidthPx(it) }
        val heightPx = primary?.let { faceHeightPx(it) }
        val path = when {
            detection.faces.isEmpty() && detection.usedZoomFallback -> "MISS"
            detection.usedZoomFallback -> "ZOOM"
            else -> "FULL"
        }
        return FaceScaleDiag(
            inputWidth = detection.inputWidth,
            inputHeight = detection.inputHeight,
            path = path,
            faceCount = detection.faces.size,
            faceWidthPx = widthPx,
            faceHeightPx = heightPx,
            score = primary?.score,
            verdict = scaleVerdict(widthPx, path),
        )
    }

    private fun scaleVerdict(faceWidthPx: Int?, path: String): String {
        if (faceWidthPx == null || path == "MISS") {
            return "MISS(无框,无法量宽)"
        }
        return when {
            faceWidthPx >= STABLE_FACE_WIDTH_PX -> "OK(≥${STABLE_FACE_WIDTH_PX})"
            faceWidthPx >= LIMIT_FACE_WIDTH_PX ->
                "BORDER(${LIMIT_FACE_WIDTH_PX}-${STABLE_FACE_WIDTH_PX - 1})"
            else -> "TINY(<${LIMIT_FACE_WIDTH_PX})"
        }
    }

    private fun formatFaceScaleShort(diag: FaceScaleDiag): String {
        val size = "${diag.inputWidth}x${diag.inputHeight}"
        val w = diag.faceWidthPx?.let { "w=${it}px" } ?: "w=-"
        return "$size ${diag.path} $w ${diag.verdict}"
    }

    private fun maybeLogFaceScaleDiag(diag: FaceScaleDiag, nowMs: Long) {
        val key = "${diag.path}|${diag.faceWidthPx}|${diag.verdict}|${diag.inputWidth}x${diag.inputHeight}"
        val due = nowMs - lastDiagLogAtMs >= DIAG_LOG_INTERVAL_MS
        if (!due && key == lastDiagKey) return
        lastDiagLogAtMs = nowMs
        lastDiagKey = key
        val scoreText = diag.score?.let { String.format("%.3f", it) } ?: "-"
        val note = when {
            diag.inputWidth != 1920 || diag.inputHeight != 1080 ->
                " 注意:输入非1080P,官方45/25阈值需按比例折算"
            diag.path == "MISS" ->
                " 漏检:请目视确认画面中是否有脸;若目视脸明显大于45px则属接入/库问题"
            diag.faceWidthPx != null && diag.faceWidthPx >= STABLE_FACE_WIDTH_PX &&
                diag.path == "ZOOM" ->
                " 全图漏检但映射框宽已≥45:优先查全图预处理"
            else -> ""
        }
        Log.i(
            TAG,
            "faceScale in=${diag.inputWidth}x${diag.inputHeight} path=${diag.path} " +
                "count=${diag.faceCount} faceW=${diag.faceWidthPx ?: -1}px " +
                "faceH=${diag.faceHeightPx ?: -1}px score=$scoreText " +
                "verdict=${diag.verdict} (stable≥$STABLE_FACE_WIDTH_PX limit≥$LIMIT_FACE_WIDTH_PX)" +
                note,
        )
    }

    private fun faceWidthPx(face: FaceResult): Int =
        (face.right - face.left + 1).coerceAtLeast(0)

    private fun faceHeightPx(face: FaceResult): Int =
        (face.bottom - face.top + 1).coerceAtLeast(0)

    /**
     * 实时属性帧复用照片模式的 ROI 16 对齐策略。返回坐标始终映射回原始相机帧。
     */
    private fun recognizeLiveAttributes(
        frame: FrameData,
        initialDetections: List<FaceResult>,
    ): List<FaceResult> {
        var nv21 = frame.nv21
        var width = frame.width
        var height = frame.height
        var detections = initialDetections
        var bestMapped = emptyList<FaceResult>()

        repeat(MAX_ATTRIBUTE_ATTEMPTS) { attempt ->
            val primaryFace = detections.maxByOrNull {
                (it.right - it.left + 1).coerceAtLeast(0) *
                    (it.bottom - it.top + 1).coerceAtLeast(0)
            } ?: return bestMapped
            val roiWidth = (primaryFace.right - primaryFace.left + 1).coerceAtLeast(1)
            val remainder = roiWidth % 16

            if (remainder != 0 && roiWidth >= MIN_ATTRIBUTE_ROI_WIDTH) {
                val alignedRoiWidth = roiWidth - remainder
                val scale = alignedRoiWidth.toFloat() / roiWidth
                var targetWidth = ((width * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
                var targetHeight = ((height * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
                if (targetWidth == width && targetHeight == height) {
                    targetWidth = (targetWidth - 2).coerceAtLeast(2)
                    targetHeight = (targetHeight - 2).coerceAtLeast(2)
                }
                nv21 = Nv21Resizer.resize(nv21, width, height, targetWidth, targetHeight)
                width = targetWidth
                height = targetHeight
            }

            val output = RkDlNative.processNv21(
                nv21 = nv21,
                width = width,
                height = height,
                frameNo = frame.frameIndex * 10 + 2 + attempt,
                procType = RkDlNative.PROC_TYPE_FACE,
                includeDebug = false,
            )
            val current = output.faces.toList()
            val mapped = current.map { face ->
                face.copy(
                    left = mapCoordinate(face.left, width, frame.width),
                    top = mapCoordinate(face.top, height, frame.height),
                    right = mapCoordinate(face.right, width, frame.width),
                    bottom = mapCoordinate(face.bottom, height, frame.height),
                )
            }
            if (mapped.count { it.hasAttributes } > bestMapped.count { it.hasAttributes }) {
                bestMapped = mapped
            }
            if (mapped.any { it.hasAttributes }) {
                return mapped
            }
            if (remainder == 0 || roiWidth < MIN_ATTRIBUTE_ROI_WIDTH || current.isEmpty()) {
                return bestMapped
            }
            detections = current
        }
        return bestMapped
    }

    private fun mapCoordinate(value: Int, fromSize: Int, toSize: Int): Int {
        if (fromSize <= 0) return value
        return (value.toFloat() * toSize / fromSize)
            .roundToInt()
            .coerceIn(0, (toSize - 1).coerceAtLeast(0))
    }

    fun stop() {
        synchronized(lifecycleLock) {
            stopLocked()
        }
    }

    /** 仅清空叠加用的 UI 状态，不释放原生库（切 Tab 时用）。 */
    fun clearUiState() {
        _faces.value = emptyList()
        _faceScaleDiag.value = null
        _liveStatus.value = "DET idle · ATTR idle"
        if (inferExecutor.isShutdown) {
            attributeTracker.clear()
            lastDiagKey = null
            return
        }
        // tracker 仅应由推理线程访问
        runCatching {
            inferExecutor.execute {
                attributeTracker.clear()
                lastDiagKey = null
            }
        }
    }

    fun release() {
        synchronized(lifecycleLock) {
            stopLocked()
            inferExecutor.shutdown()
            runCatching {
                inferExecutor.awaitTermination(5, TimeUnit.SECONDS)
            }
        }
    }

    /** 调用方须已持有 [lifecycleLock]。先置 ready=false，再在推理线程上同步 DL_Exit。 */
    private fun stopLocked() {
        if (!ready.getAndSet(false)) return
        pendingFrame.set(null)
        awaitNativeRelease()
        attributeTracker.clear()
        _faces.value = emptyList()
        _faceScaleDiag.value = null
        lastDiagKey = null
        _frameRotation.value = 0
        _liveStatus.value = "DET idle · ATTR idle"
        _status.value = "已释放"
    }

    /**
     * 把 DL_Exit 排到推理线程队尾并等待，保证：
     * 1) 不与进行中的 process 并发；
     * 2) 返回后 start() 可安全再次 DL_Init。
     */
    private fun awaitNativeRelease() {
        if (inferExecutor.isShutdown) {
            RkDlNative.release(0)
            return
        }
        val done = CountDownLatch(1)
        runCatching {
            inferExecutor.execute {
                try {
                    RkDlNative.release(0)
                } finally {
                    done.countDown()
                }
            }
            if (!done.await(30, TimeUnit.SECONDS)) {
                Log.e(TAG, "native release timed out")
            }
        }.onFailure { t ->
            Log.e(TAG, "native release schedule failed, fallback direct", t)
            RkDlNative.release(0)
            done.countDown()
        }
    }
}
