package com.haokai.hk5mh210.inference

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 将 assets/models 下的 .rknn 拷到 filesDir/models，供 DL_Init 使用。
 *
 * VGGFace 约 375MB：若首次拷贝中断留下不完整文件，检测仍可能成功，但属性会一直为 -1。
 * 因此按 assets 大小校验，不匹配则强制重拷。
 */
object ModelInstaller {

    private const val TAG = "ModelInstaller"
    private const val ASSET_DIR = "models"

    private val REQUIRED = listOf(
        "RetinaFace.rknn",
        "VGGFace.rknn",
        "facial_expression.rknn",
    )

    @Synchronized
    fun install(context: Context): File {
        val outDir = File(context.filesDir, ASSET_DIR).apply { mkdirs() }
        val am = context.assets
        for (name in REQUIRED) {
            val dest = File(outDir, name)
            val assetLen = try {
                am.openFd("$ASSET_DIR/$name").use { it.length }
            } catch (t: Throwable) {
                Log.w(TAG, "openFd $name failed, fallback stream copy: ${t.message}")
                -1L
            }
            val needCopy = when {
                !dest.exists() -> true
                assetLen > 0L && dest.length() != assetLen -> true
                dest.length() <= 0L -> true
                else -> false
            }
            if (!needCopy) {
                Log.i(TAG, "ok $name size=${dest.length()}")
                continue
            }
            if (dest.exists()) {
                Log.w(
                    TAG,
                    "re-copy $name: local=${dest.length()} asset=$assetLen (incomplete model breaks FACE_REC attrs)",
                )
                dest.delete()
            }
            Log.i(TAG, "copying asset $name (expect=$assetLen) -> ${dest.absolutePath}")
            am.open("$ASSET_DIR/$name").use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
            if (assetLen > 0L) {
                check(dest.length() == assetLen) {
                    "model copy size mismatch $name: got ${dest.length()} expect $assetLen"
                }
            }
            check(dest.length() > 0L) { "model copy empty: $name" }
            Log.i(TAG, "copied $name ok size=${dest.length()}")
        }
        return outDir
    }
}
