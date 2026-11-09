package com.haokai.hk5mh210.camera

/**
 * 轻量 NV21 最近邻缩放。属性推理低频调用时用于微调检测 ROI 的像素宽度，
 * 避免经过 Bitmap/JPEG 带来的额外拷贝和颜色损失。
 */
object Nv21Resizer {

    fun resize(
        source: ByteArray,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): ByteArray {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(targetWidth > 0 && targetHeight > 0)
        require(sourceWidth % 2 == 0 && sourceHeight % 2 == 0)
        require(targetWidth % 2 == 0 && targetHeight % 2 == 0)
        require(source.size >= sourceWidth * sourceHeight * 3 / 2)

        if (sourceWidth == targetWidth && sourceHeight == targetHeight) {
            return source
        }

        val output = ByteArray(targetWidth * targetHeight * 3 / 2)

        for (y in 0 until targetHeight) {
            val sourceY = y * sourceHeight / targetHeight
            val sourceRow = sourceY * sourceWidth
            val targetRow = y * targetWidth
            for (x in 0 until targetWidth) {
                val sourceX = x * sourceWidth / targetWidth
                output[targetRow + x] = source[sourceRow + sourceX]
            }
        }

        val sourceChromaOffset = sourceWidth * sourceHeight
        val targetChromaOffset = targetWidth * targetHeight
        val sourceChromaWidth = sourceWidth / 2
        val sourceChromaHeight = sourceHeight / 2
        val targetChromaWidth = targetWidth / 2
        val targetChromaHeight = targetHeight / 2
        for (y in 0 until targetChromaHeight) {
            val sourceY = y * sourceChromaHeight / targetChromaHeight
            for (x in 0 until targetChromaWidth) {
                val sourceX = x * sourceChromaWidth / targetChromaWidth
                val sourceIndex = sourceChromaOffset + sourceY * sourceWidth + sourceX * 2
                val targetIndex = targetChromaOffset + y * targetWidth + x * 2
                output[targetIndex] = source[sourceIndex]
                output[targetIndex + 1] = source[sourceIndex + 1]
            }
        }
        return output
    }
}
