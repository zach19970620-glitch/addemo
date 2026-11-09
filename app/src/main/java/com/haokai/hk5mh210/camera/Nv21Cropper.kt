package com.haokai.hk5mh210.camera

/**
 * 从紧密排列的 NV21 中裁剪偶数坐标、偶数尺寸区域。
 */
object Nv21Cropper {
    data class CroppedImage(
        val nv21: ByteArray,
        val width: Int,
        val height: Int,
        val offsetX: Int,
        val offsetY: Int,
    )

    /**
     * 裁取中央 2/3 区域，相当于约 1.5 倍数字放大，供全图漏检后的二次检测使用。
     */
    fun centerZoom(source: ByteArray, width: Int, height: Int): CroppedImage {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
        require(source.size >= width * height * 3 / 2)

        val cropWidth = ((width * 2 / 3) / 2 * 2).coerceAtLeast(2)
        val cropHeight = ((height * 2 / 3) / 2 * 2).coerceAtLeast(2)
        val offsetX = ((width - cropWidth) / 2 / 2) * 2
        val offsetY = ((height - cropHeight) / 2 / 2) * 2
        val output = ByteArray(cropWidth * cropHeight * 3 / 2)

        for (row in 0 until cropHeight) {
            source.copyInto(
                destination = output,
                destinationOffset = row * cropWidth,
                startIndex = (offsetY + row) * width + offsetX,
                endIndex = (offsetY + row) * width + offsetX + cropWidth,
            )
        }

        val sourceChromaOffset = width * height
        val targetChromaOffset = cropWidth * cropHeight
        for (row in 0 until cropHeight / 2) {
            source.copyInto(
                destination = output,
                destinationOffset = targetChromaOffset + row * cropWidth,
                startIndex = sourceChromaOffset + (offsetY / 2 + row) * width + offsetX,
                endIndex = sourceChromaOffset + (offsetY / 2 + row) * width + offsetX + cropWidth,
            )
        }

        return CroppedImage(
            nv21 = output,
            width = cropWidth,
            height = cropHeight,
            offsetX = offsetX,
            offsetY = offsetY,
        )
    }
}
