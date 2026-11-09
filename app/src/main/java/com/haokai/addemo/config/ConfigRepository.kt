package com.haokai.addemo.config

import android.content.Context
import com.google.gson.Gson
import java.io.File

class ConfigRepository(
    private val context: Context,
    private val gson: Gson = Gson(),
) {
    companion object {
        const val ASSET_ROOT = "demo"
        const val CONFIG_ASSET = "$ASSET_ROOT/config.json"
        const val EXTERNAL_DIR_NAME = "ad-demo"
    }

    @Volatile
    private var cached: DemoConfig? = null

    fun load(forceReload: Boolean = false): DemoConfig {
        if (!forceReload) {
            cached?.let { return it }
        }
        val config = readExternalConfig() ?: readAssetConfig() ?: DemoConfig()
        cached = config
        return config
    }

    fun assetPath(relative: String): String = "$ASSET_ROOT/$relative"

    fun resolveMediaUri(relative: String): String? {
        if (relative.isBlank()) return null
        // 拒绝路径穿越，避免 externalRoot 外任意读文件
        if (relative.contains("..")) return null
        val root = externalRoot().canonicalFile
        val external = File(root, relative).canonicalFile
        if (!external.path.startsWith(root.path + File.separator) && external.path != root.path) {
            return null
        }
        if (external.isFile) {
            return external.toURI().toString()
        }
        return "asset:///$ASSET_ROOT/$relative"
    }

    fun externalRoot(): File = File(context.getExternalFilesDir(null), EXTERNAL_DIR_NAME)

    private fun readExternalConfig(): DemoConfig? {
        val file = File(externalRoot(), "config.json")
        if (!file.isFile) return null
        return runCatching {
            gson.fromJson(file.readText(Charsets.UTF_8), DemoConfig::class.java)
        }.getOrNull()
    }

    private fun readAssetConfig(): DemoConfig? = runCatching {
        context.assets.open(CONFIG_ASSET).bufferedReader().use { reader ->
            gson.fromJson(reader, DemoConfig::class.java)
        }
    }.getOrNull()
}
