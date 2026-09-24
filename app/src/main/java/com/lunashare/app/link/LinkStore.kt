package com.lunashare.app.link

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * 复刻 HermesMobile 的 [AddressStore]，用于 link Tab 的连接地址持久化。
 * 存到 LunaShare 自己的 SharedPreferences（与 HermesMobile 的 "hermes_mobile" 隔离，
 * 因为两个 App 的存储不可互读），key 前缀统一用 lunashare_link。
 */
class LinkStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("lunashare_link", Context.MODE_PRIVATE)

    fun getAddresses(): List<String> {
        val list = mutableListOf<String>()
        val raw = prefs.getString("addresses", null) ?: return list
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) list.add(arr.getString(i))
        } catch (_: Exception) {
        }
        return list
    }

    fun addAddress(address: String) {
        val list = getAddresses().toMutableList()
        list.remove(address)
        list.add(0, address)
        while (list.size > 10) list.removeAt(list.size - 1)
        save(list)
    }

    fun removeAddress(address: String) {
        val list = getAddresses().toMutableList()
        list.remove(address)
        save(list)
        prefs.edit().remove("title_$address").remove("renamed_$address").apply()
    }

    private fun save(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString("addresses", arr.toString()).apply()
    }

    fun getLastAddress(): String? = prefs.getString("last_address", null)

    fun setLastAddress(address: String) =
        prefs.edit().putString("last_address", address).apply()

    var allowInsecureCert: Boolean
        get() = prefs.getBoolean("allow_insecure_cert", false)
        set(value) = prefs.edit().putBoolean("allow_insecure_cert", value).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen_on", true)
        set(value) = prefs.edit().putBoolean("keep_screen_on", value).apply()

    /** WebView 页悬浮按钮位置（仿 HermesMobile 持久化 FAB 坐标，-1 = 未设置） */
    val hasFabPosition: Boolean
        get() = prefs.contains("fab_x") && prefs.contains("fab_y")

    var fabX: Float
        get() = prefs.getFloat("fab_x", -1f)
        set(value) = prefs.edit().putFloat("fab_x", value).apply()

    var fabY: Float
        get() = prefs.getFloat("fab_y", -1f)
        set(value) = prefs.edit().putFloat("fab_y", value).apply()

    /** 地址对应的显示标题（未自定义时自动取 host:port） */
    fun getTitle(url: String): String =
        prefs.getString("title_$url", null) ?: defaultTitle(url)

    fun setTitle(url: String, title: String) =
        prefs.edit().putString("title_$url", title).apply()

    /**
     * 用户是否手动重命名过该连接。
     * 重命名后网页自己的 <title> 不再覆盖用户设置（否则下次加载页面名字就白改了）。
     */
    fun isRenamed(url: String): Boolean = prefs.getBoolean("renamed_$url", false)

    /** 用户手动重命名：写标题并打上「已重命名」标记，阻止网页标题覆盖 */
    fun setCustomTitle(url: String, title: String) {
        prefs.edit()
            .putString("title_$url", title)
            .putBoolean("renamed_$url", true)
            .apply()
    }

    /** 默认标题：URL 的 host:port（去掉协议与路径），解析失败则用原始地址 */
    fun defaultTitle(url: String): String {
        return try {
            val uri = android.net.Uri.parse(url)
            val host = uri.host?.takeIf { it.isNotBlank() } ?: url
            if (uri.port > 0) "$host:${uri.port}" else host
        } catch (_: Exception) {
            url
        }
    }

    /**
     * 该地址是否为 DSH 移动端页面（dsh-bridge 的移动端皮肤已生效）。
     *
     * 由 [DshPageAdapter] 的页面脚本自辨识后经控制台通道回报、这里落盘：
     * DSH 页面可能挂在任意 origin（局域网 IP / mefrp 穿透域名 / 反代），
     * 单看 URL 判不出来，只能靠"页面自己承认"。
     * 用途：抽屉长按菜单是否提供「通知行为」入口等。
     */
    fun isDshPage(url: String): Boolean = prefs.getBoolean("dsh_$url", false)

    fun setDshPage(url: String) = prefs.edit().putBoolean("dsh_$url", true).apply()

    fun clearDshPage(url: String) = prefs.edit().remove("dsh_$url").apply()

    companion object {
        /** 与 HermesMobile 一致：缺失 scheme 时默认补 http:// */
        fun normalizeUrl(input: String): String {
            var s = input.trim()
            if (s.isEmpty()) return ""
            if (!s.startsWith("http://") && !s.startsWith("https://")) {
                s = "http://$s"
            }
            return s
        }
    }
}
