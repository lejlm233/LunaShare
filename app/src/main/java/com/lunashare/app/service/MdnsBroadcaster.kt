package com.lunashare.app.service

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * 用 jmDNS 在局域网广播 `lunashare.local`（mDNS），让同网设备无需记 IP 即可访问本机共享。
 *
 * 核心：JmDNS.create(addr, "lunashare") 会向组播宣告主机名 A 记录（lunashare.local -> 手机 WiFi IPv4），
 * Windows（装了 Bonjour/iTunes）或 macOS / Linux 即可 `ping lunashare.local` 并直接用名字访问，
 * 不受手机 DHCP 每次换 IP 影响。额外注册 _http._tcp / _ftp._tcp 服务，便于支持 DNS-SD 的客户端发现。
 *
 * 需 CHANGE_WIFI_MULTICAST_STATE + ACCESS_WIFI_STATE（Manifest 已声明）以收发组播包。
 */
class MdnsBroadcaster(private val context: Context) {

    companion object {
        private const val TAG = "MdnsBroadcaster"
        const val HOST_NAME = "lunashare"
        private const val HTTP_TYPE = "_http._tcp.local."
        private const val FTP_TYPE = "_ftp._tcp.local."
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var jmdns: JmDNS? = null
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null
    private var job: Job? = null

    /** 启动广播。httpPort 为 WebDAV 端口；ftpPort<=0 表示不广播 FTP 服务。 */
    fun start(httpPort: Int, ftpPort: Int) {
        job?.cancel()
        job = scope.launch {
            try {
                val ip = getLanIpv4()
                if (ip == null) {
                    Log.w(TAG, "未找到局域网 IPv4，跳过 mDNS 广播")
                    return@launch
                }
                // 组播锁：否则部分 ROM 不会把 mDNS 应答送达本机/对端
                val wifiMgr = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val lock = wifiMgr.createMulticastLock("LunaShare::mDNS").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                multicastLock = lock

                val dns = JmDNS.create(ip, HOST_NAME)
                jmdns = dns

                dns.registerService(
                    ServiceInfo.create(HTTP_TYPE, HOST_NAME, httpPort, "LunaShare 文件共享（WebDAV/HTTP）")
                )
                if (ftpPort > 0) {
                    dns.registerService(
                        ServiceInfo.create(FTP_TYPE, HOST_NAME, ftpPort, "LunaShare 文件共享（FTP）")
                    )
                }
                Log.i(
                    TAG, "mDNS 已广播：$HOST_NAME.local -> ${ip.hostAddress} (http:$httpPort" +
                        (if (ftpPort > 0) ", ftp:$ftpPort" else "") + ")"
                )
            } catch (e: Throwable) {
                // 必须 catch Throwable：jmDNS 内部依赖缺失时会抛 NoClassDefFoundError（Error 非 Exception），
                // 若仅 catch Exception 会逃逸出协程、直接杀掉整个进程导致 app 闪退
                Log.e(TAG, "mDNS 启动失败（已降级，不影响共享功能）", e)
            }
        }
    }

    /**
     * 停止广播并释放组播锁。在新线程执行，避免阻塞调用方（Service.onDestroy 在主线程）。
     */
    fun stop() {
        job?.cancel()
        job = null
        val dns = jmdns
        jmdns = null
        val lock = multicastLock
        multicastLock = null
        if (dns == null && lock == null) return
        Thread {
            try { dns?.unregisterAllServices() } catch (_: Exception) {}
            try { dns?.close() } catch (_: Exception) {}
            try { lock?.release() } catch (_: Exception) {}
        }.start()
    }

    /**
     * 选取局域网 IPv4：优先 wlan/eth/ap/rmnet/p2p 等接口，回退到任一非回环 IPv4。
     * 不依赖 WifiManager.connectionInfo，避免位置权限 / API 弃用问题。
     */
    private fun getLanIpv4(): InetAddress? {
        return try {
            val ifaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val preferred = ifaces.filter {
                it.isUp && !it.isLoopback &&
                    it.name?.matches(Regex("^(wlan|eth|ap|rmnet|p2p).*")) == true
            }
            val pool = if (preferred.isNotEmpty()) preferred else
                ifaces.filter { it.isUp && !it.isLoopback }
            for (ni in pool) {
                for (addr in ni.inetAddresses) {
                    val host = addr.hostAddress
                    if (addr is Inet4Address && !addr.isLoopbackAddress &&
                        host != null && host != "0.0.0.0" && !host.startsWith("169.254")
                    ) {
                        return addr
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "获取局域网 IPv4 失败", e)
            null
        }
    }
}
