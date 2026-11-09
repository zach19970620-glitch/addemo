package com.haokai.hk5mh210.inference

/**
 * 单个人脸检测结果，对应 [DL_ODData]。
 */
data class FaceResult(
    val id: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val score: Float,
    /** 年龄 0–100；-1 表示未识别 */
    val age: Int = -1,
    /** 见 [GENDER_WOMAN] / [GENDER_MAN]；-1 表示未识别 */
    val gender: Int = -1,
    /** 种族枚举索引，见 [raceText]；-1 表示未识别 */
    val race: Int = -1,
    /** 表情枚举索引，见 [emotionText]；-1 表示未识别 */
    val emotion: Int = -1,
) {
    companion object {
        const val GENDER_WOMAN = 0
        const val GENDER_MAN = 1

        const val RACE_ASIAN = 0
        const val RACE_INDIAN = 1
        const val RACE_BLACK = 2
        const val RACE_WHITE = 3
        const val RACE_MIDDLE_EASTERN = 4
        const val RACE_LATINO_HISPANIC = 5

        const val EMOTION_ANGRY = 0
        const val EMOTION_DISGUST = 1
        const val EMOTION_FEAR = 2
        const val EMOTION_HAPPY = 3
        const val EMOTION_SAD = 4
        const val EMOTION_SURPRISE = 5
        const val EMOTION_NEUTRAL = 6
    }

    val genderText: String
        get() = when (gender) {
            GENDER_WOMAN -> "女"
            GENDER_MAN -> "男"
            else -> "-"
        }

    val raceText: String
        get() = when (race) {
            RACE_ASIAN -> "亚洲"
            RACE_INDIAN -> "印度"
            RACE_BLACK -> "黑人"
            RACE_WHITE -> "白人"
            RACE_MIDDLE_EASTERN -> "中东"
            RACE_LATINO_HISPANIC -> "拉丁"
            else -> "-"
        }

    val emotionText: String
        get() = when (emotion) {
            EMOTION_ANGRY -> "愤怒"
            EMOTION_DISGUST -> "厌恶"
            EMOTION_FEAR -> "恐惧"
            EMOTION_HAPPY -> "开心"
            EMOTION_SAD -> "悲伤"
            EMOTION_SURPRISE -> "惊讶"
            EMOTION_NEUTRAL -> "中性"
            else -> "-"
        }

    val ageText: String
        get() = if (age in 0..100) "${age}岁" else "-"

    /** 框上方一行摘要 */
    val overlayLabel: String
        get() = buildString {
            append(ageText)
            append(' ')
            append(genderText)
            append(' ')
            append(emotionText)
            if (race >= 0) {
                append(' ')
                append(raceText)
            }
        }

    val hasAttributes: Boolean
        get() = age >= 0 || gender >= 0 || race >= 0 || emotion >= 0
}
