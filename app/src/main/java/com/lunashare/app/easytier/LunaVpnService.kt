package com.lunashare.app.easytier

import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.easytier.jni.EasyTierJNI
import com.lunashare.app.service.FgNotificationHelper
import kotlin.concurrent.thread

/**
 * LunaShare 的组网 VpnService：建立 tun 设备并把 fd 注入 EasyTier Rust 核心。
 *
 * 时序（对标官方 easytier-gui mobile_vpn.ts）：
 * 1. EasyTierManager 先启动核心（mobile cfg：核心挂起等 fd，控制面照常）；
 * 2. 核心经协调服务器完成 DHCP，分配虚拟 IP（collectNetworkInfos 可查到）；
 * 3. Manager 拿到虚拟 IP 后 startService 本类 → establish() 建立 tun；
 * 4. 本类把 fd 经 EasyTierJNI.setTunFd 注入核心，数据面开始工作；
 * 5. 虚拟 IP / proxy_cidrs 变化时 Manager 重启本服务重建 tun。
 *
 * 路由只添加虚拟网段（+ proxy_cidrs），不劫持 DNS、不设全局代理。
 *
 * 前台常驻通知（防杀）：与共享服务（ShareService）同思路——运行期挂
 * IMPORTANCE_LOW 常驻通知，点击回到主界面；systemExempted 前台类型。
 */
class LunaVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private var instanceName: String? = null
    private var currentIp: String = ""

    companion object {
        private const val TAG = "LunaVpnService"
        const val EXTRA_IPV4 = "ipv4_address"
        const val EXTRA_PROXY_CIDRS = "proxy_cidrs"
        const val EXTRA_INSTANCE = "instance_name"
        /** 显式停止：stopService 与轮询 startService 竞态时保证服务一定被停。 */
        const val ACTION_STOP = "com.lunashare.app.easytier.VPN_STOP"
    }

    override fun onCreate() {
        super.onCreate()
        FgNotificationHelper.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "收到显式停止指令")
            cleanup()
            stopSelf()
            return START_NOT_STICKY
        }
        val ipv4Address = intent?.getStringExtra(EXTRA_IPV4)
        val proxyCidrs = intent?.getStringArrayListExtra(EXTRA_PROXY_CIDRS) ?: arrayListOf()
        instanceName = intent?.getStringExtra(EXTRA_INSTANCE)

        if (ipv4Address.isNullOrEmpty() || instanceName.isNullOrEmpty()) {
            Log.e(TAG, "缺少必要参数: ipv4=$ipv4Address, instance=$instanceName")
            stopSelf()
            return START_NOT_STICKY
        }
        currentIp = ipv4Address

        // 立即升前台：建立 tun 期间也挂常驻通知（Android 12+ 后台启动限制下更稳）
        startForegroundCompat("正在建立隧道 ($ipv4Address)...")

        thread {
            try {
                setupVpnInterface(ipv4Address, proxyCidrs)
            } catch (t: Throwable) {
                Log.e(TAG, "VPN 设置失败", t)
                EasyTierStateHolder.addLog("tun 建立失败: ${t.message}")
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun setupVpnInterface(ipv4Address: String, proxyCidrs: List<String>) {
        val (ip, prefix) = parseIpv4(ipv4Address)

        val builder = Builder()
            .setSession("LunaShare EasyTier")
            .setMtu(1300) // 与官方 GUI 一致，穿透隧道下更稳
            .addAddress(ip, prefix)
            .addDisallowedApplication("com.lunashare.app") // 防止组网流量自环

        // 虚拟网段自身 + 各节点 proxy_cidrs
        builder.addRoute(ip, 32)
        proxyCidrs.forEach { cidr ->
            runCatching {
                val (rip, rlen) = parseCidr(cidr)
                builder.addRoute(rip, rlen)
                Log.d(TAG, "添加路由: $rip/$rlen")
            }.onFailure { Log.w(TAG, "跳过无效 CIDR: $cidr") }
        }

        vpnInterface = builder.establish()
        if (vpnInterface == null) {
            EasyTierStateHolder.addLog("establish() 返回 null（VPN 授权被拒或参数非法）")
            stopSelf()
            return
        }

        val fd = vpnInterface!!.fd
        val rc = EasyTierJNI.setTunFd(instanceName!!, fd)
        if (rc == 0) {
            Log.i(TAG, "TUN fd 已注入: $fd")
            EasyTierStateHolder.addLog("tun 设备已建立并注入核心 (fd=$fd)")
            EasyTierStateHolder.setVpnRunning(true)
            updateNotification("组网运行中 · 虚拟 IP $ipv4Address")
        } else {
            Log.e(TAG, "setTunFd 失败: $rc")
            EasyTierStateHolder.addLog("fd 注入失败: $rc")
        }

        // 守住 tun 生命周期：isRunning=false（onDestroy/onStop）时退出线程
        isRunning = true
        while (isRunning && vpnInterface != null) {
            Thread.sleep(1000)
        }
    }

    // ── 前台常驻通知（与共享服务共用一条聚合通知，见 FgNotificationHelper）──

    private fun startForegroundCompat(text: String) {
        FgNotificationHelper.updateVpn(text)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    FgNotificationHelper.NOTIFICATION_ID,
                    FgNotificationHelper.buildNotification(this),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
                )
            } else {
                startForeground(
                    FgNotificationHelper.NOTIFICATION_ID,
                    FgNotificationHelper.buildNotification(this)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
    }

    private fun updateNotification(text: String) {
        FgNotificationHelper.updateVpn(text)
        FgNotificationHelper.refresh(this)
    }

    private fun parseIpv4(s: String): Pair<String, Int> =
        if (s.contains("/")) {
            val p = s.split("/")
            p[0] to (p.getOrNull(1)?.toIntOrNull() ?: 24)
        } else s to 24

    private fun parseCidr(cidr: String): Pair<String, Int> {
        val p = cidr.split("/")
        require(p.size == 2) { "无效 CIDR: $cidr" }
        return p[0] to (p[1].toIntOrNull() ?: throw IllegalArgumentException("无效 CIDR: $cidr"))
    }

    private fun cleanup() {
        isRunning = false
        EasyTierStateHolder.setVpnRunning(false)
        vpnInterface?.close()
        vpnInterface = null
        // DETACH 不移除通知（共享服务可能还在用同一条），再按剩余状态重绘
        runCatching { stopForeground(STOP_FOREGROUND_DETACH) }
        FgNotificationHelper.clearVpn()
        FgNotificationHelper.refresh(this)
        Log.i(TAG, "VPN 接口已清理")
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }
}
