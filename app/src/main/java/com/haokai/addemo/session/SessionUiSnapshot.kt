package com.haokai.addemo.session

data class SessionUiSnapshot(
    val state: SessionState = SessionState.Idle,
    val micEnabled: Boolean = false,
    val lastEvent: String = "",
    val lastHit: String = "",
    val sessionId: String = "",
    /** 非 IDLE 内容片正在播，须播完才能切 */
    val blockingPlayback: Boolean = false,
    val pendingLeave: Boolean = false,
)
