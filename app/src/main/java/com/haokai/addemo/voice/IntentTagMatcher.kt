package com.haokai.addemo.voice

import com.haokai.addemo.config.DemoConfig
import com.haokai.addemo.config.TagMediaItem

sealed interface MatchResult {
    data class Product(val item: TagMediaItem) : MatchResult
    data class Dish(val item: TagMediaItem) : MatchResult
    data object DiningIntent : MatchResult
    data object BackToUnico : MatchResult
    data object None : MatchResult
}

class IntentTagMatcher {

    fun match(text: String, config: DemoConfig, diningScene: Boolean): MatchResult {
        val normalized = normalize(text)
        if (normalized.isBlank()) return MatchResult.None

        if (config.features.back_to_unico && containsAny(normalized, config.intents.back_to_unico)) {
            return MatchResult.BackToUnico
        }
        if (!diningScene && containsAny(normalized, config.intents.dining)) {
            return MatchResult.DiningIntent
        }

        val pool = if (diningScene) config.dishes else config.products
        val hit = pool
            .mapNotNull { item ->
                val tag = item.tags
                    .map { normalize(it) }
                    .filter { it.isNotBlank() }
                    .filter { normalized.contains(it) }
                    .maxByOrNull { it.length }
                tag?.let { item to it.length }
            }
            .maxByOrNull { it.second }
            ?.first

        return when {
            hit == null -> MatchResult.None
            diningScene -> MatchResult.Dish(hit)
            else -> MatchResult.Product(hit)
        }
    }

    private fun containsAny(text: String, keywords: List<String>): Boolean =
        keywords.any { normalize(it).isNotBlank() && text.contains(normalize(it)) }

    private fun normalize(raw: String): String =
        raw.lowercase()
            .replace("\\s+".toRegex(), "")
            .replace("　", "")
}
