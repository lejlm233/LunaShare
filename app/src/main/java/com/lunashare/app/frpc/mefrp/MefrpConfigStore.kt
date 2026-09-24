package com.lunashare.app.frpc.mefrp

import android.content.Context
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * mefrp 凭证持久化（与 OpenFrpConfigStore 并行，互不覆盖）。
 *
 * - 访问令牌 accessToken：用户在 mefrp 网页控制台获取的「用户 Token」，API 认证用（Bearer）
 * - frpcToken：GET /auth/user/frpToken 返回的启动令牌，frpc `-u` 用
 * - 节点缓存：创建隧道时展示节点列表用
 * - 用户信息缓存：OpenFrpScreen 显示账号名/剩余隧道数
 */
class MefrpConfigStore(private val context: Context) {

    companion object {
        private const val TAG = "MefrpCfgStore"
        private const val PREFS_NAME = "lunashare_mefrp"
        private const val TOKEN_KEY = "mefrp_access_token"
        private const val FRPC_TOKEN_KEY = "mefrp_frpc_token"
        private const val USER_INFO_KEY = "mefrp_user_info"
        private const val NODES_KEY = "mefrp_nodes"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    // ── 访问令牌 ─────────────────────────────────────────────────

    var accessToken: String
        get() = prefs.getString(TOKEN_KEY, "") ?: ""
        set(value) = prefs.edit().putString(TOKEN_KEY, value).apply()

    // ── frpc 启动令牌 ────────────────────────────────────────────

    var frpcToken: String
        get() = prefs.getString(FRPC_TOKEN_KEY, "") ?: ""
        set(value) = prefs.edit().putString(FRPC_TOKEN_KEY, value).apply()

    // ── 用户信息缓存 ─────────────────────────────────────────────

    fun loadUserInfo(): MefrpUserInfo? {
        val raw = prefs.getString(USER_INFO_KEY, null) ?: return null
        return try {
            json.decodeFromString<MefrpUserInfo>(raw)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse saved mefrp user info", e)
            null
        }
    }

    fun saveUserInfo(info: MefrpUserInfo) {
        prefs.edit().putString(USER_INFO_KEY, json.encodeToString(info)).apply()
    }

    // ── 节点缓存 ─────────────────────────────────────────────────

    fun loadNodes(): List<MefrpNode> {
        val raw = prefs.getString(NODES_KEY, null) ?: return emptyList()
        return try {
            // 重新派生 vip：缓存里不存 vip 字段（默认值 false 不序列化），
            // 必须按 allowGroup 即时计算，否则旧缓存一律显示 vip=false。
            json.decodeFromString<List<MefrpNode>>(raw).map { it.deriveVip() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse saved mefrp nodes", e)
            emptyList()
        }
    }

    fun saveNodes(nodes: List<MefrpNode>) {
        prefs.edit().putString(NODES_KEY, json.encodeToString(nodes)).apply()
    }

    /** 清除凭证（重置访问令牌时调用）。 */
    fun clear() {
        prefs.edit()
            .remove(TOKEN_KEY)
            .remove(FRPC_TOKEN_KEY)
            .remove(USER_INFO_KEY)
            .apply()
    }
}