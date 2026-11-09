package com.haokai.hk5mh210.camera

/**
 * 单帧图像数据。
 *
 * [nv21] 为 NV21 格式原始字节，便于 OpenCV / 自研算法处理。
 * [width] / [height] 为图像尺寸。
 * [timestampNs] 为帧时间戳（纳秒）。
 * [rotationDegrees] 为相对传感器的旋转角度。
 */
data class FrameData(
    val nv21: ByteArray,
    val width: Int,
    val height: Int,
    val timestampNs: Long,
    val rotationDegrees: Int,
    val frameIndex: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FrameData) return false
        return frameIndex == other.frameIndex &&
            width == other.width &&
            height == other.height &&
            timestampNs == other.timestampNs
    }

    override fun hashCode(): Int {
        var result = frameIndex.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + timestampNs.hashCode()
        return result
    }
}
