package com.haokai.hk5mh210.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * 基于 CameraX 的逐帧实时抓取管理器。
 *
 * 优先选择 [LENS_FACING_EXTERNAL]（USB UVC / HK5M-H210），
 * 若系统未枚举外置相机则回退到任意可用相机。
 * 分析流优先锁定 1920×1080（16:9），避免回退到 1600×1200 等 4:3。
 */
@OptIn(ExperimentalCamera2Interop::class)
class FrameCaptureManager(private val context: Context) {

    companion object {
        private const val TAG = "FrameCaptureManager"
        /** 官方模型口径：1080p */
        private val TARGET_SIZE = Size(1920, 1080)
        /** 业务抓帧锁定 5 fps（间隔 200ms） */
        private const val TARGET_FPS = 5
        private const val FRAME_INTERVAL_NS = 1_000_000_000L / TARGET_FPS
    }

    data class CaptureStats(
        val fps: Float = 0f,
        val frameCount: Long = 0,
        val width: Int = 0,
        val height: Int = 0,
        val cameraLabel: String = "",
        val isRunning: Boolean = false,
        val error: String? = null,
    )

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null

    private val frameIndex = AtomicLong(0)
    private val grabbing = AtomicBoolean(false)

    private var fpsWindowStart = 0L
    private var fpsWindowCount = 0
    private var latestFps = 0f
    private var lastEmittedNs = 0L

    private val _stats = MutableStateFlow(CaptureStats())
    val stats: StateFlow<CaptureStats> = _stats.asStateFlow()

    private val _cameras = MutableStateFlow<List<CameraInfoSummary>>(emptyList())
    val cameras: StateFlow<List<CameraInfoSummary>> = _cameras.asStateFlow()

    /** 业务侧逐帧回调（在分析线程执行，勿做重 UI 操作） */
    @Volatile
    var onFrame: ((FrameData) -> Unit)? = null

    fun refreshCameraList() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val list = manager.cameraIdList.mapNotNull { id ->
            runCatching {
                val chars = manager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val facingLabel = when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "前置"
                    CameraCharacteristics.LENS_FACING_BACK -> "后置"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "外置 USB"
                    else -> "未知($facing)"
                }
                val configs = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val sizes = configs?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
                    ?.map { "${it.width}x${it.height}" }
                    ?.distinct()
                    ?: emptyList()
                CameraInfoSummary(
                    cameraId = id,
                    facing = facingLabel,
                    isExternal = facing == CameraCharacteristics.LENS_FACING_EXTERNAL,
                    supportedSizes = sizes,
                )
            }.getOrNull()
        }
        _cameras.value = list
        list.forEach { cam ->
            val has1080 = cam.supportedSizes.any { it == "1920x1080" }
            Log.i(
                TAG,
                "camera #${cam.cameraId} ${cam.facing} has1080p=$has1080 " +
                    "yuvSizes=${cam.supportedSizes.joinToString()}",
            )
        }
    }

    suspend fun start(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        preferredCameraId: String? = null,
    ) {
        stop()
        refreshCameraList()

        val provider = awaitCameraProvider()
        cameraProvider = provider
        provider.unbindAll()

        val selector = buildSelector(preferredCameraId)
        val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val supportsExact1080 = cameraSupportsExact1080(preferredCameraId)

        frameIndex.set(0)
        fpsWindowStart = System.nanoTime()
        fpsWindowCount = 0
        lastEmittedNs = 0L
        grabbing.set(true)

        val bound = try {
            bindUseCases(
                provider = provider,
                lifecycleOwner = lifecycleOwner,
                selector = selector,
                previewView = previewView,
                targetRotation = targetRotation,
                forceExact1080 = supportsExact1080,
            )
        } catch (first: Throwable) {
            if (!supportsExact1080) throw first
            Log.w(
                TAG,
                "bind exact 1080p failed (${first.message}), fallback to 16:9 closest",
                first,
            )
            provider.unbindAll()
            bindUseCases(
                provider = provider,
                lifecycleOwner = lifecycleOwner,
                selector = selector,
                previewView = previewView,
                targetRotation = targetRotation,
                forceExact1080 = false,
            )
        }
        boundCamera = bound.camera

        val label = describeBoundCamera(preferredCameraId)
        _stats.value = _stats.value.copy(
            isRunning = true,
            cameraLabel = label,
            error = null,
        )
        Log.i(
            TAG,
            "started camera=$label target=1920x1080 forceExact=$supportsExact1080 " +
                "preview=${bound.previewWidth}x${bound.previewHeight} " +
                "analysis=${bound.analysisWidth}x${bound.analysisHeight} fps=$TARGET_FPS",
        )
    }

    private data class BoundUseCases(
        val camera: Camera,
        val previewWidth: Int,
        val previewHeight: Int,
        val analysisWidth: Int,
        val analysisHeight: Int,
    )

    private fun bindUseCases(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        selector: CameraSelector,
        previewView: PreviewView,
        targetRotation: Int,
        forceExact1080: Boolean,
    ): BoundUseCases {
        val analysisSelector = buildResolutionSelector(forceExact1080 = forceExact1080)
        // 预览同样偏 16:9；强制精确 1080 时与分析流对齐，避免再选成 4:3。
        val previewSelector = buildResolutionSelector(forceExact1080 = forceExact1080)

        val preview = Preview.Builder()
            .setResolutionSelector(previewSelector)
            .setTargetRotation(targetRotation)
            .build()
            .also { it.surfaceProvider = previewView.surfaceProvider }

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(analysisSelector)
            .setTargetRotation(targetRotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()

        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
            try {
                if (!grabbing.get()) return@setAnalyzer

                // 锁定 5 fps：未到间隔则丢弃本帧
                val now = System.nanoTime()
                if (lastEmittedNs != 0L && now - lastEmittedNs < FRAME_INTERVAL_NS) {
                    return@setAnalyzer
                }
                lastEmittedNs = now

                val index = frameIndex.incrementAndGet()
                val nv21 = YuvConverter.imageProxyToNv21(imageProxy)
                val frame = FrameData(
                    nv21 = nv21,
                    width = imageProxy.width,
                    height = imageProxy.height,
                    timestampNs = imageProxy.imageInfo.timestamp,
                    rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                    frameIndex = index,
                )
                updateFps(frame.width, frame.height)
                onFrame?.invoke(frame)
            } catch (t: Throwable) {
                Log.e(TAG, "analyze failed", t)
                _stats.value = _stats.value.copy(error = t.message)
            } finally {
                imageProxy.close()
            }
        }

        val camera = provider.bindToLifecycle(
            lifecycleOwner,
            selector,
            preview,
            analysis,
        )
        val previewSize = preview.resolutionInfo?.resolution
        val analysisSize = analysis.resolutionInfo?.resolution
        return BoundUseCases(
            camera = camera,
            previewWidth = previewSize?.width ?: 0,
            previewHeight = previewSize?.height ?: 0,
            analysisWidth = analysisSize?.width ?: 0,
            analysisHeight = analysisSize?.height ?: 0,
        )
    }

    private fun buildResolutionSelector(forceExact1080: Boolean): ResolutionSelector {
        val builder = ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                AspectRatioStrategy(
                    AspectRatio.RATIO_16_9,
                    AspectRatioStrategy.FALLBACK_RULE_AUTO,
                ),
            )
            .setResolutionStrategy(
                ResolutionStrategy(
                    TARGET_SIZE,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
        if (forceExact1080) {
            builder.setResolutionFilter { supportedSizes, _ ->
                val exact = supportedSizes.filter {
                    it.width == TARGET_SIZE.width && it.height == TARGET_SIZE.height
                }
                if (exact.isNotEmpty()) {
                    Log.i(TAG, "resolutionFilter: lock ${TARGET_SIZE.width}x${TARGET_SIZE.height}")
                    exact
                } else {
                    // 列表方向可能是竖屏交换宽高
                    val swapped = supportedSizes.filter {
                        it.width == TARGET_SIZE.height && it.height == TARGET_SIZE.width
                    }
                    if (swapped.isNotEmpty()) {
                        Log.i(TAG, "resolutionFilter: lock ${TARGET_SIZE.height}x${TARGET_SIZE.width}")
                        swapped
                    } else {
                        Log.w(TAG, "resolutionFilter: no 1080p in $supportedSizes")
                        supportedSizes
                    }
                }
            }
        }
        return builder.build()
    }

    private fun cameraSupportsExact1080(preferredCameraId: String?): Boolean {
        val cam = preferredCameraId?.let { id ->
            _cameras.value.firstOrNull { it.cameraId == id }
        } ?: _cameras.value.firstOrNull { it.isExternal } ?: _cameras.value.firstOrNull()
        return cam?.supportedSizes?.any { it == "1920x1080" } == true
    }

    fun stop() {
        grabbing.set(false)
        analysisExecutor.execute {
            // drain analyzer thread
        }
        cameraProvider?.unbindAll()
        boundCamera = null
        _stats.value = _stats.value.copy(isRunning = false, fps = 0f)
    }

    fun release() {
        stop()
        analysisExecutor.shutdown()
    }

    /** 保存最近一帧为 JPEG（由调用方传入 FrameData） */
    fun frameToJpeg(frame: FrameData, quality: Int = 90): ByteArray {
        return YuvConverter.nv21ToJpeg(frame.nv21, frame.width, frame.height, quality)
    }

    private fun updateFps(width: Int, height: Int) {
        fpsWindowCount++
        val now = System.nanoTime()
        val elapsed = now - fpsWindowStart
        if (elapsed >= 1_000_000_000L) {
            latestFps = fpsWindowCount * 1_000_000_000f / elapsed
            fpsWindowStart = now
            fpsWindowCount = 0
        }
        _stats.value = _stats.value.copy(
            fps = latestFps,
            frameCount = frameIndex.get(),
            width = width,
            height = height,
            isRunning = true,
        )
    }

    private fun buildSelector(preferredCameraId: String?): CameraSelector {
        if (preferredCameraId != null) {
            return CameraSelector.Builder()
                .addCameraFilter { cameraInfos ->
                    val byId = cameraInfos.filter { info ->
                        Camera2CameraInfo.from(info).cameraId == preferredCameraId
                    }
                    if (byId.isNotEmpty()) byId else cameraInfos
                }
                .build()
        }

        // 优先外置 USB 相机（HK5M-H210）
        val external = CameraSelector.Builder()
            .requireLensFacing(CameraCharacteristics.LENS_FACING_EXTERNAL)
            .build()
        val provider = cameraProvider
        if (provider != null && provider.hasCamera(external)) {
            return external
        }

        val back = CameraSelector.DEFAULT_BACK_CAMERA
        if (provider != null && provider.hasCamera(back)) {
            return back
        }
        return CameraSelector.DEFAULT_FRONT_CAMERA
    }

    private fun describeBoundCamera(preferredCameraId: String?): String {
        val list = _cameras.value
        preferredCameraId?.let { id ->
            list.firstOrNull { it.cameraId == id }?.let {
                return "#${it.cameraId} ${it.facing}"
            }
        }
        list.firstOrNull { it.isExternal }?.let {
            return "#${it.cameraId} ${it.facing}"
        }
        return list.firstOrNull()?.let { "#${it.cameraId} ${it.facing}" } ?: "未知"
    }

    private suspend fun awaitCameraProvider(): ProcessCameraProvider =
        suspendCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    try {
                        cont.resume(future.get())
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
        }
}
