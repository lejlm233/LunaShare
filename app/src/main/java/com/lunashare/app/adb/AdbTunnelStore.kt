package com.lunashare.app.adb

import android.content.Context
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * ADB 穿透配置的轻量持久化（SharedPreferences）。
 *
 * 与 OpenFrpConfigStore 类似，采用明文 SharedPreferences（非 root 设备下沙箱隔离）。
 * 这里只存隧道元信息，不涉及账号密码。
 */
class AdbTunnelStore(private val context: Context) {

    companion object {
        private const val TAG = "AdbTunnelStore"
        private const val PREFS_NAME = "lunashare_adb_tunnel"
        private const val CFG_KEY = "adb_tunnel_config"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    /** 读取配置；未保存过则返回 null（调用方应回退到默认配置）。 */
    fun loadConfig(): AdbTunnelConfig? {
        val raw = prefs.getString(CFG_KEY, null) ?: return null
        return try {
            json.decodeFromString<AdbTunnelConfig>(raw)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse saved ADB tunnel config", e)
            null
        }
    }

    /** 保存配置（partial update 由调用方负责：先 load 再 copy）。 */
    fun saveConfig(cfg: AdbTunnelConfig) {
        prefs.edit()
            .putString(CFG_KEY, json.encodeToString(cfg))
            .apply()
        Log.d(TAG, "Saved ADB tunnel config (enabled=${cfg.enabled}, proxyId=${cfg.proxyId})")
    }

    /** 清空配置（删除隧道时调用）。 */
    fun clear() {
        prefs.edit().remove(CFG_KEY).apply()
        Log.d(TAG, "Cleared ADB tunnel config")
    }
}
