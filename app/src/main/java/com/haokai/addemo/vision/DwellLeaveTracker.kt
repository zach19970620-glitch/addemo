package com.haokai.addemo.vision

/**
 * 停留 / 离开边沿检测（不含 EngageTimeout，由 SessionSM 负责）。
 */
class DwellLeaveTracker(
    private val enterMs: Long,
    private val leaveMs: Long,
    private val cooldownMs: Long,
) {
    enum class Edge {
        None,
        FaceStable,
        PersonLeft,
    }

    private var presentSinceMs: Long? = null
    private var missingSinceMs: Long? = null
    private var engaged: Boolean = false
    private var cooldownUntilMs: Long = 0L

    fun onPrimaryPresent(nowMs: Long): Edge {
        missingSinceMs = null
        if (nowMs < cooldownUntilMs) {
            presentSinceMs = null
            return Edge.None
        }
        val start = presentSinceMs ?: nowMs.also { presentSinceMs = it }
        if (!engaged && nowMs - start >= enterMs) {
            engaged = true
            return Edge.FaceStable
        }
        return Edge.None
    }

    fun onPrimaryMissing(nowMs: Long): Edge {
        presentSinceMs = null
        if (!engaged) {
            missingSinceMs = null
            return Edge.None
        }
        val start = missingSinceMs ?: nowMs.also { missingSinceMs = it }
        if (nowMs - start >= leaveMs) {
            // 保持 engaged：回 Idle 时由 VisionEngine.reset 清掉。
            // 否则 Greeting 误触发 PersonLeft 后进不了海报、海报也无法再离开。
            missingSinceMs = null
            return Edge.PersonLeft
        }
        return Edge.None
    }

    fun reset() {
        presentSinceMs = null
        missingSinceMs = null
        engaged = false
        cooldownUntilMs = 0L
    }

    fun isEngaged(): Boolean = engaged

    fun dwellMs(nowMs: Long): Long {
        val start = presentSinceMs ?: return 0L
        return (nowMs - start).coerceAtLeast(0L)
    }
}
