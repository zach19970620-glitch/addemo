package com.haokai.addemo.vision

import com.haokai.hk5mh210.inference.FaceResult

/**
 * 打招呼结束后的心情分支。
 * 负向：sad / disgust / angry / fear；neutral / surprise / 未识别 → 中性。
 */
enum class MoodKind {
    Good,
    Bad,
    Neutral,
}

object MoodClassifier {
    fun classify(emotion: Int): MoodKind = when (emotion) {
        FaceResult.EMOTION_HAPPY -> MoodKind.Good
        FaceResult.EMOTION_SAD,
        FaceResult.EMOTION_DISGUST,
        FaceResult.EMOTION_ANGRY,
        FaceResult.EMOTION_FEAR,
        -> MoodKind.Bad
        else -> MoodKind.Neutral
    }
}
