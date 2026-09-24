package com.lunashare.app.config

import android.content.Context
import android.util.Log
import com.lunashare.app.model.RemoteConnection
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 「连接共享」（远程共享）配置存储，模式照抄 [ShareConfigStore]：
 * SharedPreferences + kotlinx.serialization JSON 单 key 列表。
 */
class RemoteConnectionStore(private val context: Context) {

    companion object {
        private const val TAG = "RemoteConnStore"
        private const val PREFS_NAME = "lunashare_remote_configs"
        private const val CONFIG_LIST_KEY = "remote_config_list"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    /** 列出全部远程连接配置（按名称排序）。 */
    fun listConfigs(): List<RemoteConnection> {
        val raw = prefs.getString(CONFIG_LIST_KEY, null) ?: return emptyList()
        return try {
            json.decodeFromString<RemoteConfigList>(raw).configs.sortedBy { it.name }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse remote config list", e)
            emptyList()
        }
    }

    fun loadConfig(id: String): RemoteConnection? =
        listConfigs().firstOrNull { it.id == id }

    /** 保存（新建或更新，按 id upsert）。 */
    fun saveConfig(config: RemoteConnection) {
        val configs = listConfigs().toMutableList()
        val idx = configs.indexOfFirst { it.id == config.id }
        if (idx >= 0) configs[idx] = config else configs.add(config)
        saveList(configs)
        Log.d(TAG, "Saved remote connection: ${config.name} (${config.id})")
    }

    fun deleteConfig(id: String) {
        val configs = listConfigs().toMutableList()
        configs.removeAll { it.id == id }
        saveList(configs)
        Log.d(TAG, "Deleted remote connection: $id")
    }

    @Serializable
    private data class RemoteConfigList(
        val configs: List<RemoteConnection> = emptyList()
    )

    private fun saveList(configs: List<RemoteConnection>) {
        prefs.edit().putString(CONFIG_LIST_KEY, json.encodeToString(RemoteConfigList(configs))).apply()
    }
}
