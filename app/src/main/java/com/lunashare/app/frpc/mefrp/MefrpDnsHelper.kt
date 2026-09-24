package com.lunashare.app.frpc.mefrp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress

/**
 * 用 Android 系统 DNS（netd）把域名解析成 IPv4，绕过 mefrpc 二进制硬编码的
 * 8.8.8.8:53（墙内/部分运营商网络下不可达，导致 mefrp 控制面 api.mefrp.com
 * 解析超时、隧道起不来）。
 *
 * 根因：mefrpc 是纯 Go 编译（CGO_ENABLED=0）。它不调用系统 getaddrinfo，只读
 * 不存在的 /etc/resolv.conf，于是回退到 Go 默认的 8.8.8.8:53；也没有
 * --server-addr / --dns-server 这类命令行参数可覆盖，节点地址完全由服务端下发的
 * 配置决定。App 进程内无法 setprop net.dns1（需要 root），也写不了 /etc/resolv.conf。
 *
 * 因此唯一的「无 root 根治」是把域名解析成 IP、注入 mefrpc 的 --api-root-url，
 * 让控制面走 IP、跳过其内置 8.8.8.8。本类用 [InetAddress]（走系统解析器 netd，
 * 即手机当前网络的 DNS）做这件事，不影响系统/WiFi 的其它 DNS 设置。
 */
object MefrpDnsHelper {

    private const val TAG = "MefrpDns"
    private const val CACHE_MS = 5 * 60_000L

    private data class CacheEntry(val ip: String, val ts: Long)
    private val cache = mutableMapOf<String, CacheEntry>()

    /**
     * 解析 [host] 到 IPv4（优先），失败返回 null。结果缓存 5 分钟，避免同一会话内
     * 反复解析；过期后重解析以容忍 CDN 节点 IP 漂移。
     */
    suspend fun resolveToIp(host: String): String? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = synchronized(cache) { cache[host] }
        if (cached != null && now - cached.ts < CACHE_MS) {
            Log.d(TAG, "命中缓存 $host -> ${cached.ip}")
            return@withContext cached.ip
        }
        val ip = runCatching {
            val addrs = InetAddress.getAllByName(host)
            // 优先 IPv4（mefrpc 控制面用 IPv4 连接最稳），否则退回到第一个结果。
            addrs.firstOrNull { it is Inet4Address }?.hostAddress
                ?: addrs.firstOrNull()?.hostAddress
        }.getOrNull()
        if (ip != null) {
            synchronized(cache) { cache[host] = CacheEntry(ip, now) }
            Log.i(TAG, "系统 DNS 解析 $host -> $ip")
        } else {
            Log.w(TAG, "系统 DNS 解析 $host 失败")
        }
        ip
    }

    /** 仅供测试/排查：清除缓存，下次解析强制走系统 DNS。 */
    fun clearCache() = synchronized(cache) { cache.clear() }
}
