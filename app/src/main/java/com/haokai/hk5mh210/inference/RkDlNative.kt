package com.haokai.hk5mh210.inference

import android.util.Log

/**
 * JNI 封装 rk_inference [DL_Init] / [DL_Process] / [DL_Exit]。
 *
 * 依赖 jniLibs/arm64-v8a 中的 librk_dl.so 及其依赖；
 * 薄 JNI 库名为 librk_dl_jni.so（由 CMake 编译）。
 */
object RkDlNative {

    private const val TAG = "RkDlNative"

    /** DL_ALGO_PROC_TYPE_FACE_DET */
    const val PROC_FACE_DET = 0

    /** DL_ALGO_PROC_TYPE_FACE_REC（同时加载表情模型） */
    const val PROC_FACE_REC = 1

    /** 人脸检测 + 识别/表情 */
    val PROC_TYPE_FACE: Int
        get() = (1 shl PROC_FACE_DET) or (1 shl PROC_FACE_REC)

    /** 仅做人脸检测，不运行属性模型 */
    val PROC_TYPE_FACE_DET_ONLY: Int
        get() = 1 shl PROC_FACE_DET

    @Volatile
    var available: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    init {
        available = loadLibraries()
    }

    private fun loadLibraries(): Boolean {
        // 依赖库：按依赖顺序尝试加载；缺失时仅标记不可用，不影响相机预览
        val deps = listOf(
            "c++_shared",
            "rknnrt",
            "rga",
            "opencv_java4",
            "rk_dl",
        )
        for (name in deps) {
            try {
                System.loadLibrary(name)
                Log.i(TAG, "loaded lib$name")
            } catch (t: Throwable) {
                Log.w(TAG, "optional load lib$name skipped: ${t.message}")
            }
        }
        return try {
            System.loadLibrary("rk_dl_jni")
            Log.i(TAG, "loaded librk_dl_jni")
            true
        } catch (t: Throwable) {
            lastError = "load rk_dl_jni failed: ${t.message}"
            Log.e(TAG, lastError!!, t)
            false
        }
    }

    fun init(modelRootPath: String, procId: Int = 0, procType: Int = PROC_TYPE_FACE): Boolean {
        if (!available) {
            lastError = lastError ?: "native lib not available"
            return false
        }
        return try {
            val ret = nativeInit(modelRootPath, procId, procType)
            if (ret != 0) {
                lastError = "DL_Init failed: $ret"
                Log.e(TAG, lastError!!)
                false
            } else {
                lastError = null
                true
            }
        } catch (t: Throwable) {
            lastError = "DL_Init exception: ${t.message}"
            Log.e(TAG, lastError!!, t)
            false
        }
    }

    fun processNv21(
        nv21: ByteArray,
        width: Int,
        height: Int,
        frameNo: Long,
        procType: Int = PROC_TYPE_FACE,
        includeDebug: Boolean = true,
    ): ProcessResult {
        if (!available) {
            return ProcessResult(emptyArray(), null, width, height)
        }
        return try {
            nativeProcessNv21(nv21, width, height, frameNo, procType, includeDebug)
                ?: ProcessResult(emptyArray(), null, width, height)
        } catch (t: Throwable) {
            Log.e(TAG, "processNv21 failed", t)
            ProcessResult(emptyArray(), null, width, height)
        }
    }

    fun release(procId: Int = 0) {
        if (!available) return
        try {
            nativeRelease(procId)
        } catch (t: Throwable) {
            Log.e(TAG, "release failed", t)
        }
    }

    private external fun nativeInit(modelRootPath: String, procId: Int, procType: Int): Int

    private external fun nativeProcessNv21(
        nv21: ByteArray,
        width: Int,
        height: Int,
        frameNo: Long,
        procType: Int,
        includeDebug: Boolean,
    ): ProcessResult?

    private external fun nativeRelease(procId: Int): Int
}
