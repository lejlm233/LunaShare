package com.lunashare.app.link

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单个网页的通知行为配置（复刻「ZCode 远程任务通知」扩展的 options 页设置项）。
 *
 * 关键设计：**按「页面来源」维度存储，无配置 = 留空**。
 * 这样将来给别的 Web 页面（非 zcode）加通知能力时，直接以该页面的 key 复用同一份结构；
 * 没有配置过的页面读取到 null，UI 上就不显示「通知行为」入口。
 *
 * 存储：`SharedPreferences("luna_notify_settings")` → `cfg_<key>` = JSON。
 */
data class NotifySettings(
    /** 总开关：关掉后该页面完全不发通知 */
    val enabled: Boolean = true,
    /** 三类事件的通知开关 */
    val notifyOnConfirm: Boolean = true,
    val notifyOnCompleted: Boolean = true,
    val notifyOnFailed: Boolean = true,
    /** 「需要确认」通知常驻（不自动消失） */
    val requireInteractionConfirm: Boolean = true,
    /** 页面正被查看时也通知（默认不打扰） */
    val notifyWhenVisible: Boolean = false,
    /** 页面刚打开时，对已存在的「等待确认」也提醒一次 */
    val checkOnOpen: Boolean = true,
    /** 严格模式：完成/失败仅在观察到「运行中/待确认 → 终态」转变时才通知 */
    val strictRunning: Boolean = true,
    /** 通知声音（关闭则走静音渠道） */
    val sound: Boolean = true,
    /** 同一任务同一事件的冷却时间（秒） */
    val cooldownSec: Int = 90,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("notifyOnConfirm", notifyOnConfirm)
        put("notifyOnCompleted", notifyOnCompleted)
        put("notifyOnFailed", notifyOnFailed)
        put("requireInteractionConfirm", requireInteractionConfirm)
        put("notifyWhenVisible", notifyWhenVisible)
        put("checkOnOpen", checkOnOpen)
        put("strictRunning", strictRunning)
        put("sound", sound)
        put("cooldownSec", cooldownSec)
    }

    /** 生成注入脚本用的 JS 字面量（供 ZCODE_WATCHER_JS 读取运行时配置） */
    fun toJsLiteral(): String = buildString {
        append("{")
        append("enabled:").append(enabled).append(",")
        append("notifyOnConfirm:").append(notifyOnConfirm).append(",")
        append("notifyOnCompleted:").append(notifyOnCompleted).append(",")
        append("notifyOnFailed:").append(notifyOnFailed).append(",")
        append("notifyWhenVisible:").append(notifyWhenVisible).append(",")
        append("checkOnOpen:").append(checkOnOpen).append(",")
        append("strictRunning:").append(strictRunning).append(",")
        append("cooldownMs:").append(cooldownSec.coerceIn(0, 3600) * 1000L)
        append("}")
    }

    companion object {
        fun fromJson(o: JSONObject): NotifySettings = NotifySettings(
            enabled = o.optBoolean("enabled", true),
            notifyOnConfirm = o.optBoolean("notifyOnConfirm", true),
            notifyOnCompleted = o.optBoolean("notifyOnCompleted", true),
            notifyOnFailed = o.optBoolean("notifyOnFailed", true),
            requireInteractionConfirm = o.optBoolean("requireInteractionConfirm", true),
            notifyWhenVisible = o.optBoolean("notifyWhenVisible", false),
            checkOnOpen = o.optBoolean("checkOnOpen", true),
            strictRunning = o.optBoolean("strictRunning", true),
            sound = o.optBoolean("sound", true),
            cooldownSec = o.optInt("cooldownSec", 90).coerceIn(0, 3600),
        )
    }
}

/**
 * 通知配置仓库：按 key（页面地址）存取 [NotifySettings]。
 *
 * - [getOrNull] 返回 null 表示「该页面尚无通知配置」——UI 据此决定是否提供「通知行为」入口；
 * - [getOrDefault] 返回默认配置，供已确认支持通知能力的页面（如 zcode）直接使用；
 * - [hasConfig] / [clear] 支持将来给任意 Web 页增删配置。
 */
class NotifySettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("luna_notify_settings", Context.MODE_PRIVATE)

    /** 已配置通知行为的页面 key 列表（如将来在设置里做总览用） */
    fun configuredKeys(): List<String> {
        val raw = prefs.getString("keys", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun hasConfig(key: String): Boolean = prefs.contains("cfg_$key")

    fun getOrNull(key: String): NotifySettings? {
        val raw = prefs.getString("cfg_$key", null) ?: return null
        return try {
            NotifySettings.fromJson(JSONObject(raw))
        } catch (_: Exception) {
            null
        }
    }

    /** 无配置时返回默认值（用于「已确认支持通知」的页面，如 zcode） */
    fun getOrDefault(key: String): NotifySettings = getOrNull(key) ?: NotifySettings()

    fun save(key: String, settings: NotifySettings) {
        prefs.edit().putString("cfg_$key", settings.toJson().toString()).apply()
        val keys = configuredKeys().toMutableList()
        if (!keys.contains(key)) {
            keys.add(key)
            prefs.edit().putString("keys", JSONArray(keys).toString()).apply()
        }
    }

    fun clear(key: String) {
        val keys = configuredKeys().toMutableList()
        keys.remove(key)
        prefs.edit()
            .remove("cfg_$key")
            .putString("keys", JSONArray(keys).toString())
            .apply()
    }

    companion object {
        /**
         * 把任意 URL 归一为稳定的配置 key（scheme://host:port）。
         * 同域不同路径共享一份配置；解析失败则回退原始字符串。
         */
        fun keyOf(url: String?): String {
            if (url.isNullOrBlank()) return ""
            return try {
                val uri = android.net.Uri.parse(url)
                val host = uri.host ?: return url
                val scheme = uri.scheme ?: "http"
                if (uri.port > 0) "$scheme://$host:${uri.port}" else "$scheme://$host"
            } catch (_: Exception) {
                url
            }
        }
    }
}
