package com.haokai.hk5mh210.inference

/**
 * 将低频属性结果挂到高频检测框上。仅由 InferenceBridge 的单线程执行器访问。
 */
internal class FaceAttributeTracker(
    private val matchIouThreshold: Float = 0.35f,
    /** 表情/年龄属性刷新间隔；广告机笑容检测需要更快，默认 800ms */
    private val attributeIntervalMs: Long = 800L,
    private val trackTtlMs: Long = 1_500L,
    private val boxHoldMs: Long = 400L,
) {
    private data class Track(
        val trackId: Int,
        var detection: FaceResult,
        var attributes: FaceResult? = null,
        var lastSeenAtMs: Long,
        var lastAttributeAttemptAtMs: Long = 0L,
    )

    private val tracks = mutableListOf<Track>()
    private var nextTrackId = 1
    private var currentFrameHasDetections = false

    fun updateDetections(detections: List<FaceResult>, nowMs: Long) {
        prune(nowMs)
        currentFrameHasDetections = detections.isNotEmpty()
        // 按 trackId 标记未匹配项：Track 含 var 字段，不能放进依赖 equals/hashCode 的 Set。
        val unmatchedIds = tracks.mapTo(mutableSetOf()) { it.trackId }

        detections.sortedByDescending { it.score }.forEach { detection ->
            val match = tracks
                .asSequence()
                .filter { it.trackId in unmatchedIds }
                .map { it to iou(it.detection, detection) }
                .filter { it.second >= matchIouThreshold }
                .maxByOrNull { it.second }
                ?.first

            if (match == null) {
                tracks += Track(
                    trackId = nextTrackId++,
                    detection = detection,
                    lastSeenAtMs = nowMs,
                )
            } else {
                match.detection = detection
                match.lastSeenAtMs = nowMs
                unmatchedIds.remove(match.trackId)
            }
        }
    }

    fun shouldRunAttributes(nowMs: Long): Boolean {
        prune(nowMs)
        return tracks.any { track ->
            nowMs - track.lastSeenAtMs <= trackTtlMs &&
                (track.lastAttributeAttemptAtMs == 0L ||
                    nowMs - track.lastAttributeAttemptAtMs >= attributeIntervalMs)
        }
    }

    fun markAttributeAttempt(nowMs: Long) {
        tracks
            .filter { nowMs - it.lastSeenAtMs <= trackTtlMs }
            .forEach { it.lastAttributeAttemptAtMs = nowMs }
    }

    fun ingestAttributes(results: List<FaceResult>, nowMs: Long) {
        results
            .filter { it.hasAttributes }
            .sortedByDescending { it.score }
            .forEach { result ->
                val match = tracks
                    .filter { nowMs - it.lastSeenAtMs <= trackTtlMs }
                    .map { it to iou(it.detection, result) }
                    .filter { it.second >= matchIouThreshold }
                    .maxByOrNull { it.second }
                    ?.first
                    ?: return@forEach

                match.attributes = result
            }
    }

    fun mergedFaces(nowMs: Long): List<FaceResult> {
        prune(nowMs)
        return tracks
            // 有新框时只画新框；整帧漏检时短暂保持，抑制远距离隔帧闪烁。
            .filter { track ->
                if (currentFrameHasDetections) {
                    track.lastSeenAtMs == nowMs
                } else {
                    nowMs - track.lastSeenAtMs <= boxHoldMs
                }
            }
            .sortedBy { it.trackId }
            .map { track ->
                val attr = track.attributes
                if (attr == null) {
                    track.detection
                } else {
                    track.detection.copy(
                        age = attr.age,
                        gender = attr.gender,
                        race = attr.race,
                        emotion = attr.emotion,
                    )
                }
            }
    }

    fun status(nowMs: Long): String {
        prune(nowMs)
        if (tracks.isEmpty()) return "ATTR idle"
        return "ATTR tracked=${tracks.size} refresh=2s"
    }

    fun clear() {
        tracks.clear()
        nextTrackId = 1
        currentFrameHasDetections = false
    }

    private fun prune(nowMs: Long) {
        tracks.removeAll { nowMs - it.lastSeenAtMs > trackTtlMs }
    }

    companion object {
        internal fun iou(first: FaceResult, second: FaceResult): Float {
            val intersectionLeft = maxOf(first.left, second.left)
            val intersectionTop = maxOf(first.top, second.top)
            val intersectionRight = minOf(first.right, second.right)
            val intersectionBottom = minOf(first.bottom, second.bottom)
            val intersectionWidth = (intersectionRight - intersectionLeft + 1).coerceAtLeast(0)
            val intersectionHeight = (intersectionBottom - intersectionTop + 1).coerceAtLeast(0)
            val intersection = intersectionWidth.toLong() * intersectionHeight.toLong()
            if (intersection <= 0L) return 0f

            val firstArea =
                (first.right - first.left + 1).coerceAtLeast(0).toLong() *
                    (first.bottom - first.top + 1).coerceAtLeast(0).toLong()
            val secondArea =
                (second.right - second.left + 1).coerceAtLeast(0).toLong() *
                    (second.bottom - second.top + 1).coerceAtLeast(0).toLong()
            val union = firstArea + secondArea - intersection
            return if (union > 0L) intersection.toFloat() / union else 0f
        }
    }
}
