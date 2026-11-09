package com.haokai.addemo.config

data class DemoConfig(
    val timeouts: TimeoutsConfig = TimeoutsConfig(),
    val yuki: YukiConfig = YukiConfig(),
    val posters: PostersConfig = PostersConfig(),
    val intents: IntentsConfig = IntentsConfig(),
    val products: List<TagMediaItem> = emptyList(),
    val dishes: List<TagMediaItem> = emptyList(),
    val report: ReportConfig = ReportConfig(),
    val features: FeaturesConfig = FeaturesConfig(),
)

data class TimeoutsConfig(
    /** 主脸稳定停留后触发 Greeting */
    val enter_ms: Long = 5000,
    /** 兼容旧配置；主路径已不再使用 10s 档 */
    val engage_ms: Long = 5000,
    val leave_ms: Long = 4000,
    val cooldown_ms: Long = 6000,
    val poster_fade_ms: Long = 350,
    /** UNICO / 点餐海报最短展示时长；未满时 PersonLeft 延后回 Idle */
    val poster_min_ms: Long = 20_000,
)

data class YukiConfig(
    val idle: List<String> = emptyList(),
    /** 属性未知时的打招呼回退列表 */
    val greetings: List<String> = emptyList(),
    val greet_young_female: String = "",
    val greet_young_male: String = "",
    val greet_older_female: String = "",
    val greet_older_male: String = "",
    /** ≥ 该年龄用年长打招呼片 */
    val older_age_min: Int = 45,
    val mood_good: String = "",
    val mood_bad: String = "",
)

data class PostersConfig(
    val unico: String = "",
    val dining: String = "",
    /** 非空时，进海报态从此列表随机选一张（商品海报 / 餐饮海报等） */
    val random: List<String> = emptyList(),
)

data class IntentsConfig(
    val dining: List<String> = emptyList(),
    val back_to_unico: List<String> = emptyList(),
)

data class TagMediaItem(
    val id: String = "",
    val tags: List<String> = emptyList(),
    val video: String = "",
    val image: String = "",
)

data class ReportConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val path: String = "",
    /** 预导入设备序列号；也可用 local.properties device.serialNumber */
    val serialNumber: String = "",
    /** PLAY_VIDEO 文档为 user_triggered；广告机无点击时可自动播 */
    val auto_play_video: Boolean = true,
    /** 服务端未匹配/失败时回退本地 IntentTagMatcher */
    /** 服务端未匹配时是否回退本地 IntentTagMatcher（菜品已清空，默认 false） */
    val local_fallback: Boolean = false,
)

data class FeaturesConfig(
    val back_to_unico: Boolean = false,
    val debug_overlay: Boolean = true,
    /** 可选 AEC（audioprocessor.aar）；海报态无可靠 ref 时默认关闭 */
    val aec_enabled: Boolean = false,
)
