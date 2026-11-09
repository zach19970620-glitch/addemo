package com.haokai.hk5mh210.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * YUV_420_888 → NV21 / JPEG 转换工具。
 */
object YuvConverter {

    fun imageProxyToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        require(width % 2 == 0 && height % 2 == 0) {
            "NV21 requires even dimensions, got ${width}x$height"
        }

        // 输出必须是紧密排列的 width*height Y 数据。USB 相机经常带行尾 padding，
        // 不能直接按 buffer.remaining() 拷贝，否则色度平面会从错误位置开始。
        val nv21 = ByteArray(width * height * 3 / 2)
        copyPlane(
            buffer = image.planes[0].buffer,
            rowStride = image.planes[0].rowStride,
            pixelStride = image.planes[0].pixelStride,
            width = width,
            height = height,
            output = nv21,
            outputOffset = 0,
            outputPixelStride = 1,
        )

        // YUV_420_888 的 U/V 平面可能是 planar，也可能是共享内存的交错布局。
        // 分别按各自 stride 取样，统一写成真正的 NV21（VU VU ...）。
        val chromaOffset = width * height
        copyPlane(
            buffer = image.planes[2].buffer,
            rowStride = image.planes[2].rowStride,
            pixelStride = image.planes[2].pixelStride,
            width = width / 2,
            height = height / 2,
            output = nv21,
            outputOffset = chromaOffset,
            outputPixelStride = 2,
        )
        copyPlane(
            buffer = image.planes[1].buffer,
            rowStride = image.planes[1].rowStride,
            pixelStride = image.planes[1].pixelStride,
            width = width / 2,
            height = height / 2,
            output = nv21,
            outputOffset = chromaOffset + 1,
            outputPixelStride = 2,
        )
        return nv21
    }

    private fun copyPlane(
        buffer: java.nio.ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        output: ByteArray,
        outputOffset: Int,
        outputPixelStride: Int,
    ) {
        val source = buffer.duplicate()
        val sourceStart = source.position()
        var destinationRow = outputOffset

        if (pixelStride == 1 && outputPixelStride == 1 && rowStride == width) {
            source.get(output, outputOffset, width * height)
            return
        }

        for (row in 0 until height) {
            val sourceRow = sourceStart + row * rowStride
            var destination = destinationRow
            for (column in 0 until width) {
                output[destination] = source.get(sourceRow + column * pixelStride)
                destination += outputPixelStride
            }
            destinationRow += width * outputPixelStride
        }
    }

    fun nv21ToJpeg(nv21: ByteArray, width: Int, height: Int, quality: Int = 90): ByteArray {
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val stream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, stream)
        return stream.toByteArray()
    }

    fun imageToNv21(image: Image): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        return nv21
    }
}
