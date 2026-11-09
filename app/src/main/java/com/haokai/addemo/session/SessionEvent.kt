package com.haokai.addemo.session

sealed interface SessionEvent {
    data object FaceStable : SessionEvent
    /** Debug：强制在打招呼后走好心情片 */
    data object EmotionHappy : SessionEvent
    /** Debug：强制在打招呼后走差心情片 */
    data object EmotionBad : SessionEvent
    data class VoiceProductHit(val productId: String) : SessionEvent
    data object VoiceDiningIntent : SessionEvent
    data class VoiceDishHit(val dishId: String) : SessionEvent
    data object VoiceBackUnico : SessionEvent
    data object MediaEnded : SessionEvent
    data object PersonLeft : SessionEvent
    data object SystemRecover : SessionEvent
}
