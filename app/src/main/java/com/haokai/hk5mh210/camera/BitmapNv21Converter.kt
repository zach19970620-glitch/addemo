package com.haokai.hk5mh210.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 将 [Bitmap] 转为推理用的紧密排列 NV21，并保证宽高为偶数。
 */
object BitmapNv21Converter {

    data class Nv21Image(
        val nv21: ByteArray,
        val width: Int,
        val height: Int,
        val displayBitmap: Bitmap,
    )

    /**
     * @param maxSide 长边上限，过大图会等比缩小以降低推理耗时
     */
    fun fromBitmap(source: Bitmap, maxSide: Int = 1280): Nv21Image {
        val prepared = prepareBitmap(source, maxSide)
        val width = prepared.width
        val height = prepared.height
        val argb = IntArray(width * height)
        prepared.getPixels(argb, 0, width, 0, 0, width, height)

        val nv21 = ByteArray(width * height * 3 / 2)
        encodeArgbToNv21(argb, width, height, nv21)
        return Nv21Image(
            nv21 = nv21,
            width = width,
            height = height,
            displayBitmap = prepared,
        )
    }

    private fun prepareBitmap(source: Bitmap, maxSide: Int): Bitmap {
        var bitmap = source
        val longSide = max(bitmap.width, bitmap.height)
        if (longSide > maxSide) {
            val scale = maxSide.toFloat() / longSide
            val w = max(2, (bitmap.width * scale).roundToInt())
            val h = max(2, (bitmap.height * scale).roundToInt())
            bitmap = Bitmap.createScaledBitmap(bitmap, w, h, true)
        }

        val evenW = (bitmap.width / 2) * 2
        val evenH = (bitmap.height / 2) * 2
        if (evenW != bitmap.width || evenH != bitmap.height) {
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, evenW.coerceAtLeast(2), evenH.coerceAtLeast(2))
        }
        return if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
    }

    fun applyExifRotation(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun encodeArgbToNv21(argb: IntArray, width: Int, height: Int, nv21: ByteArray) {
        var yIndex = 0
        var uvIndex = width * height
        var index = 0
        for (j in 0 until height) {
            for (i in 0 until width) {
                val color = argb[index++]
                val r = (color shr 16) and 0xff
                val g = (color shr 8) and 0xff
                val b = color and 0xff

                // ITU-R BT.601
                var y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                var u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                var v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                y = y.coerceIn(0, 255)
                u = u.coerceIn(0, 255)
                v = v.coerceIn(0, 255)

                nv21[yIndex++] = y.toByte()
                if (j % 2 == 0 && i % 2 == 0 && uvIndex + 1 < nv21.size) {
                    nv21[uvIndex++] = v.toByte()
                    nv21[uvIndex++] = u.toByte()
                }
            }
        }
    }
}
