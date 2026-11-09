package com.haokai.addemo.vision

import com.haokai.hk5mh210.inference.FaceResult
import kotlin.math.abs
import kotlin.math.max

/**
 * 主顾客：面积最大，并略偏向画面中心。
 */
object PrimaryFaceSelector {

    fun select(
        faces: List<FaceResult>,
        frameWidth: Int,
        frameHeight: Int,
        minScore: Float = 0.35f,
    ): FaceResult? {
        if (faces.isEmpty() || frameWidth <= 0 || frameHeight <= 0) return null
        val cx = frameWidth / 2f
        val cy = frameHeight / 2f
        val maxDim = max(frameWidth, frameHeight).toFloat().coerceAtLeast(1f)

        return faces
            .asSequence()
            .filter { it.score >= minScore }
            .filter { it.right > it.left && it.bottom > it.top }
            .maxByOrNull { face ->
                val area = (face.right - face.left).toFloat() * (face.bottom - face.top).toFloat()
                val fcx = (face.left + face.right) / 2f
                val fcy = (face.top + face.bottom) / 2f
                val distNorm = (abs(fcx - cx) + abs(fcy - cy)) / maxDim
                // Prefer large area; mild penalty for off-center faces.
                area * (1.2f - distNorm.coerceIn(0f, 1f))
            }
    }
}
