package com.lunashare.app.adb

import kotlinx.serialization.Serializable

/**
 * 独立的 ADB 端口穿透配置（不放在 ShareConfig 里，因为它是全局单例功能，
 * 与某个文件共享配置无关）。
 *
 * 整体链路：
 *   手机本地 adbd 监听 TCP 5555（通过 root 或电脑 `adb tcpip` 命令开启）
 *     → frpc 把 127.0.0.1:5555 反向映射到 OpenFrp 公网节点
 *     → PC 端执行 `adb connect <nodeHost>:<remotePort>` 即可在任意网络（含蜂窝）远程控制手机。
 */
@Serializable
data class AdbTunnelConfig(
    /** UI/总开关：用户是否希望开启 ADB 穿透。 */
    val enabled: Boolean = false,
    /**
     * 穿透服务商：
     * - "openfrp" —— OpenFrp 官方定制 frpc（libfrpc.so），令牌用 `-u <frpToken>`；
     * - "mefrp"  —— 幻缘映射，独立 mefrpc 二进制（libmefrpc.so），令牌用 `-t <启动令牌>`。
     * 决定启动隧道时的二进制与令牌语义，与「共享」里的 TunnelConfig.provider 同义。
     */
    val provider: String = "openfrp",
    /** OpenFrp 隧道代理 ID（frpc -p <proxyId>）。0 表示尚未创建。 */
    val proxyId: Int = 0,
    /** OpenFrp 节点 ID。 */
    val nodeId: Int = 0,
    /** OpenFrp 节点公网域名/IP（用于合成 `adb connect` 命令）。 */
    val nodeHost: String = "",
    /** OpenFrp 隧道远程端口（公网监听端口，用于合成 `adb connect` 命令）。 */
    val remotePort: Int? = null,
    /** OpenFrp 隧道名（用于自愈/按名查找）。 */
    val name: String = "",
    /** frpc 登录令牌（来自 OpenFrp getUserInfo().token）。 */
    val frpcToken: String = "",
    /** 本地 adbd 监听端口，固定 5555。 */
    val localPort: Int = 5555,
    /**
     * 是否启用「开机自启永久开启 5555」：仅在有 root 时有效，
     * 对应 `setprop persist.adb.tcp.port 5555`（重启后仍生效）。
     * 无 root 时此项无效（只有 root 方案才能开机自启永久开启 5555）。
     */
    val rootAutoEnabled: Boolean = false,
    /**
     * 是否开机自动启动此 ADB 穿透（独立于总开关 [enabled]）。
     * BootReceiver 启动 ShareService 后，若此项为 true 则自动拉起 ADB 隧道。
     * 注意：开机后需手机已开启 5555 监听（root 模式可完全自启；否则需先用电脑 adb 命令开启）。
     */
    val bootAutoStart: Boolean = false
)
