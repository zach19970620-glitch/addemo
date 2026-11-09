package com.haokai.addemo.voice

/**
 * 演示关键词 → 本地 assetId（仅用于后端未匹配时的本地播片兜底，不改写上报文本）。
 * catalog: test-20260728001 r11
 */
object IntentPhraseResolver {
    data class Alias(
        val keywords: List<String>,
        val canonical: String,
        val assetId: String,
    )

    private val aliases: List<Alias> = listOf(
        Alias(
            keywords = listOf("麻婆豆腐"),
            canonical = "麻婆豆腐",
            assetId = "f5535ac7-87c1-454b-96e1-6f02e8f1e0ea",
        ),
        Alias(
            keywords = listOf("红烧牛肉面", "牛肉面"),
            canonical = "红烧牛肉面",
            assetId = "42725735-7f63-4104-81de-63a9b2dfaa95",
        ),
        Alias(
            keywords = listOf("机械键盘", "键盘", "PB2", "pb2"),
            canonical = "键盘",
            assetId = "14f67064-b3f3-4455-b47e-19d68b820611",
        ),
        Alias(
            keywords = listOf("手表", "这款手表", "看看产品", "SS1"),
            canonical = "看看这款手表",
            assetId = "6e8f204b-2d02-4ceb-98db-5917ec5c1068",
        ),
        Alias(
            keywords = listOf("ULW10", "ulw10"),
            canonical = "ULW10",
            assetId = "0b1237dd-4dc7-4462-a87b-7da448837ff3",
        ),
        Alias(
            keywords = listOf("UQ71", "uq71"),
            canonical = "UQ71",
            assetId = "25aa7dfb-4fd8-4a7c-b6db-66109243267c",
        ),
        Alias(
            keywords = listOf("R19", "r19"),
            canonical = "R19",
            assetId = "b317b260-7b9d-408f-864e-374d75b08d04",
        ),
        Alias(
            keywords = listOf("MX10", "mx10"),
            canonical = "MX10",
            assetId = "ca946603-bc0d-4ccb-8716-205894de103d",
        ),
    ).map { a ->
        a.copy(keywords = a.keywords.sortedByDescending { it.length })
    }

    fun findAlias(text: String): Alias? {
        val t = text.trim()
        if (t.isEmpty()) return null
        for (alias in aliases) {
            for (key in alias.keywords) {
                if (t.contains(key, ignoreCase = true)) return alias
            }
        }
        return null
    }
}
