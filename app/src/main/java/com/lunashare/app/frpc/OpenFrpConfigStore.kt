package com.lunashare.app.frpc

import android.content.Context
import android.util.Log
import com.lunashare.app.frpc.model.OpenFrpAccount
import com.lunashare.app.frpc.model.OpenFrpNode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Lightweight store for OpenFrp account credentials & settings.
 *
 * Security note: credentials are stored in SharedPreferences. On
 * non-rooted devices this is sandboxed to the app. A production app
 * might want to use EncryptedSharedPreferences or the Android
 * Keystore; we keep it simple to match the rest of LunaShare.
 */
class OpenFrpConfigStore(private val context: Context) {

    companion object {
        private const val TAG = "OpenFrpCfgStore"
        private const val PREFS_NAME = "lunashare_openfrp"
        private const val ACCOUNT_KEY = "openfrp_account"
        private const val FRPC_VERSION_KEY = "frpc_version"
        private const val NODES_KEY = "openfrp_nodes"
        private const val SELECTED_PROVIDER_KEY = "selected_provider"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    // ── Account ───────────────────────────────────────────────────

    fun loadAccount(): OpenFrpAccount {
        val raw = prefs.getString(ACCOUNT_KEY, null) ?: return OpenFrpAccount()
        return try {
            json.decodeFromString<OpenFrpAccount>(raw)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse saved OpenFrp account", e)
            OpenFrpAccount()
        }
    }

    fun saveAccount(account: OpenFrpAccount) {
        prefs.edit()
            .putString(ACCOUNT_KEY, json.encodeToString(account))
            .apply()
        Log.d(TAG, "Saved OpenFrp account (loggedIn=${account.isLoggedIn})")
    }

    fun clearAccount() {
        prefs.edit().remove(ACCOUNT_KEY).apply()
        Log.d(TAG, "Cleared OpenFrp account")
    }

    // ── Frpc binary metadata ──────────────────────────────────────

    fun getFrpcVersion(): String? =
        prefs.getString(FRPC_VERSION_KEY, null)

    fun setFrpcVersion(version: String?) {
        prefs.edit().putString(FRPC_VERSION_KEY, version).apply()
    }

    // ── Node cache (for ServiceConfigScreen tunnel creation) ─────

    fun loadNodes(): List<OpenFrpNode> {
        val raw = prefs.getString(NODES_KEY, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<OpenFrpNode>>(raw)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse saved nodes", e)
            emptyList()
        }
    }

    fun saveNodes(nodes: List<OpenFrpNode>) {
        prefs.edit()
            .putString(NODES_KEY, json.encodeToString(nodes))
            .apply()
        Log.d(TAG, "Saved ${nodes.size} OpenFrp nodes")
    }

    fun clearNodes() {
        prefs.edit().remove(NODES_KEY).apply()
    }

    // ── 全局穿透服务商（openfrp / mefrp） ──────────────────────
    /** 全 App 当前选中的穿透服务商，编辑共享/节点状态都按它显示。 */
    var selectedProvider: String
        get() = prefs.getString(SELECTED_PROVIDER_KEY, "openfrp") ?: "openfrp"
        set(value) = prefs.edit().putString(SELECTED_PROVIDER_KEY, value).apply()
}
