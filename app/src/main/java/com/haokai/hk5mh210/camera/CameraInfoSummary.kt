package com.haokai.hk5mh210.camera

/**
 * 相机信息摘要，用于 UI 展示与选择。
 */
data class CameraInfoSummary(
    val cameraId: String,
    val facing: String,
    val isExternal: Boolean,
    val supportedSizes: List<String>,
)
