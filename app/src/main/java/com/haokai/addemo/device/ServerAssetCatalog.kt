package com.haokai.addemo.device

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.haokai.addemo.config.ConfigRepository
import java.io.File

data class ServerAssetEntry(
    val assetId: String,
    val contentHash: String,
    val kind: String? = null,
    val mimeType: String? = null,
    val originalFileName: String? = null,
    val sizeBytes: Long? = null,
    val suggestedLocalPath: String? = null,
)

private data class ServerAssetCatalogFile(
    val catalog: Map<String, Any?>? = null,
    val assets: List<ServerAssetEntry> = emptyList(),
)

/**
 * assetId → 本地文件（contentHash 命名）。
 * 查找顺序（与 doc/assets、manifest suggestedLocalPath 对齐）：
 * 1. external ad-demo/server_assets、ad-demo/assets
 * 2. APK assets/demo/server_assets、assets/demo/assets
 */
class ServerAssetCatalog(
    private val context: Context,
    private val configRepository: ConfigRepository,
    private val gson: Gson = Gson(),
) {
    companion object {
        private const val TAG = "ServerAssetCatalog"
        private const val CATALOG_ASSET = "demo/server_assets_catalog.json"
        private val LOCAL_DIRS = listOf("server_assets", "assets")
    }

    private val byId: Map<String, ServerAssetEntry> = load()

    val size: Int get() = byId.size

    fun find(assetId: String?): ServerAssetEntry? {
        if (assetId.isNullOrBlank()) return null
        return byId[assetId]
    }

    /**
     * @return playable URI string，或 null（调用方回退 payload.url）
     */
    fun resolveUri(assetId: String?): String? {
        val entry = find(assetId) ?: return null
        val fileName = fileNameFor(entry) ?: return null

        val root = configRepository.externalRoot()
        for (dir in LOCAL_DIRS) {
            val external = File(root, "$dir/$fileName")
            if (external.isFile && external.length() > 0L) {
                return Uri.fromFile(external).toString()
            }
        }

        for (dir in LOCAL_DIRS) {
            val assetPath = "demo/$dir/$fileName"
            val exists = runCatching {
                context.assets.open(assetPath).use { }
                true
            }.getOrDefault(false)
            if (exists) return "asset:///$assetPath"
        }
        Log.w(TAG, "local miss assetId=$assetId file=$fileName")
        return null
    }

    private fun fileNameFor(entry: ServerAssetEntry): String? {
        val hash = entry.contentHash.trim()
        if (hash.isBlank()) return null
        val suggested = entry.suggestedLocalPath?.substringAfterLast('/')
        if (!suggested.isNullOrBlank()) return suggested
        val ext = when {
            entry.mimeType?.contains("png") == true -> "png"
            entry.mimeType?.contains("jpeg") == true || entry.mimeType?.contains("jpg") == true -> "jpg"
            entry.mimeType?.contains("mp4") == true || entry.kind == "video" -> "mp4"
            entry.kind == "image" -> "png"
            else -> "bin"
        }
        return "$hash.$ext"
    }

    private fun load(): Map<String, ServerAssetEntry> {
        val fromExternal = runCatching {
            val f = File(configRepository.externalRoot(), "server_assets_catalog.json")
            if (!f.isFile) return@runCatching null
            gson.fromJson(f.readText(Charsets.UTF_8), ServerAssetCatalogFile::class.java)
        }.getOrNull()

        val file = fromExternal ?: runCatching {
            context.assets.open(CATALOG_ASSET).bufferedReader().use { reader ->
                gson.fromJson(reader, ServerAssetCatalogFile::class.java)
            }
        }.getOrNull()

        if (file == null) {
            Log.w(TAG, "catalog missing")
            return emptyMap()
        }
        val map = file.assets
            .filter { it.assetId.isNotBlank() && it.contentHash.isNotBlank() }
            .associateBy { it.assetId }
        Log.i(TAG, "loaded ${map.size} assets catalogVersion=${file.catalog?.get("catalogVersion")}")
        return map
    }
}
