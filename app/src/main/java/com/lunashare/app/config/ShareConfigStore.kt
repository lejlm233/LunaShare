package com.lunashare.app.config

import android.content.Context
import android.util.Log
import com.lunashare.app.model.ShareConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Lightweight config store for share configurations.
 *
 * Uses SharedPreferences with JSON serialization.
 * Each share is stored as a JSON string keyed by its UUID.
 */
class ShareConfigStore(private val context: Context) {

    companion object {
        private const val TAG = "ShareConfigStore"
        private const val PREFS_NAME = "lunashare_configs"
        private const val CONFIG_LIST_KEY = "share_config_list"
        private const val AUTO_START_SET_KEY = "auto_start_set"
        private const val BOOT_AUTO_START_KEY = "boot_auto_start"
        private const val BATTERY_WHITELIST_KEY = "battery_whitelist"
        private const val MDNS_ENABLED_KEY = "mdns_enabled"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    // ── Public API ──

    /** List all share configurations ordered by name. */
    fun listConfigs(): List<ShareConfig> {
        val raw = prefs.getString(CONFIG_LIST_KEY, null) ?: return emptyList()
        return try {
            val list = json.decodeFromString<ShareConfigList>(raw)
            list.configs.sortedBy { it.name }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse config list", e)
            emptyList()
        }
    }

    /** Load a single share config by ID. */
    fun loadConfig(id: String): ShareConfig? {
        return listConfigs().firstOrNull { it.id == id }
    }

    /** Save (create or update) a share config. */
    fun saveConfig(config: ShareConfig) {
        val configs = listConfigs().toMutableList()
        val idx = configs.indexOfFirst { it.id == config.id }
        if (idx >= 0) {
            configs[idx] = config
        } else {
            configs.add(config)
        }
        saveList(configs)
        Log.d(TAG, "Saved share: ${config.name} (${config.id})")
    }

    /** Delete a share config. */
    fun deleteConfig(id: String) {
        val configs = listConfigs().toMutableList()
        configs.removeAll { it.id == id }
        saveList(configs)
        removeFromAutoStart(id)
        Log.d(TAG, "Deleted share: $id")
    }

    // ── Auto-start ──

    fun getAutoStartIds(): Set<String> =
        prefs.getStringSet(AUTO_START_SET_KEY, emptySet()) ?: emptySet()

    fun setAutoStart(id: String, enabled: Boolean) {
        val set = getAutoStartIds().toMutableSet()
        if (enabled) set.add(id) else set.remove(id)
        prefs.edit().putStringSet(AUTO_START_SET_KEY, set).apply()
    }

    // ── App-level boot auto-start ──

    /** Whether LunaShare should auto-start after device boot (app-level switch). */
    fun isBootAutoStart(): Boolean = prefs.getBoolean(BOOT_AUTO_START_KEY, false)

    fun setBootAutoStart(enabled: Boolean) {
        prefs.edit().putBoolean(BOOT_AUTO_START_KEY, enabled).apply()
    }

    // ── Battery whitelist (keep service alive in background / screen off) ──

    /** Whether the user opted in to battery optimization exemption. */
    fun isBatteryWhitelist(): Boolean = prefs.getBoolean(BATTERY_WHITELIST_KEY, false)

    fun setBatteryWhitelist(enabled: Boolean) {
        prefs.edit().putBoolean(BATTERY_WHITELIST_KEY, enabled).apply()
    }

    // ── mDNS 广播（开共享时用 lunashare.local 让同网设备访问本机）──

    /** 是否启用 mDNS 广播。默认开启（开启共享后同网设备可用 lunashare.local 访问）。 */
    fun isMdnsEnabled(): Boolean = prefs.getBoolean(MDNS_ENABLED_KEY, true)

    fun setMdnsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(MDNS_ENABLED_KEY, enabled).apply()
    }

    private fun removeFromAutoStart(id: String) {
        val set = getAutoStartIds().toMutableSet()
        if (set.remove(id)) {
            prefs.edit().putStringSet(AUTO_START_SET_KEY, set).apply()
        }
    }

    // ── Internal ──

    @Serializable
    private data class ShareConfigList(
        val configs: List<ShareConfig> = emptyList()
    )

    private fun saveList(configs: List<ShareConfig>) {
        val list = ShareConfigList(configs)
        prefs.edit().putString(CONFIG_LIST_KEY, json.encodeToString(list)).apply()
    }
}
