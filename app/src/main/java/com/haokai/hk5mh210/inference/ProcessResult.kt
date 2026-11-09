package com.haokai.hk5mh210.inference

import android.graphics.Bitmap

/**
 * [RkDlNative.processNv21] 的返回：检测框属性 + 库写入的叠加调试图（RGB/BGR 同源）。
 */
data class ProcessResult(
    val faces: Array<FaceResult>,
    /** 与输入同尺寸的 RGB888（JNI 侧已从库的 debugBuf 转好） */
    val debugRgb: ByteArray?,
    val width: Int,
    val height: Int,
) {
    fun toDebugBitmap(): Bitmap? {
        val rgb = debugRgb ?: return null
        val need = width * height * 3
        if (width <= 0 || height <= 0 || rgb.size < need) return null
        val pixels = IntArray(width * height)
        var si = 0
        for (i in pixels.indices) {
            val r = rgb[si++].toInt() and 0xff
            val g = rgb[si++].toInt() and 0xff
            val b = rgb[si++].toInt() and 0xff
            pixels[i] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProcessResult) return false
        return width == other.width &&
            height == other.height &&
            faces.contentEquals(other.faces) &&
            debugRgb.contentEquals(other.debugRgb)
    }

    override fun hashCode(): Int {
        var result = faces.contentHashCode()
        result = 31 * result + (debugRgb?.contentHashCode() ?: 0)
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}
