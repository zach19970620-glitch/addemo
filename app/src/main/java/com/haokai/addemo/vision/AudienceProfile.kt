package com.haokai.addemo.vision

import com.haokai.hk5mh210.inference.FaceResult

/**
 * 主脸观众画像（年龄/性别来自 RK 属性模型）。
 * 用于打招呼选片；菜品展示完全由服务端意图计划驱动，不再做本地受众门控。
 */
data class AudienceProfile(
    val age: Int = -1,
    val gender: Int = -1,
) {
    val isAdult: Boolean
        get() = age < 0 || age >= ADULT_AGE_MIN

    val isChild: Boolean
        get() = age in 0 until ADULT_AGE_MIN

    val isWoman: Boolean
        get() = gender == FaceResult.GENDER_WOMAN

    val isMan: Boolean
        get() = gender == FaceResult.GENDER_MAN

    val label: String
        get() = buildString {
            append(if (age in 0..100) "${age}岁" else "年龄?")
            append(' ')
            append(
                when (gender) {
                    FaceResult.GENDER_WOMAN -> "女"
                    FaceResult.GENDER_MAN -> "男"
                    else -> "性别?"
                },
            )
        }

    companion object {
        const val ADULT_AGE_MIN = 18
        val UNKNOWN = AudienceProfile()
    }
}
