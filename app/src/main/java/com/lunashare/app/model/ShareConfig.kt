package com.lunashare.app.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Configuration for a single file share.
 *
 * Each share exposes a local directory over HTTP/WebDAV and/or FTP.
 * Each service can optionally be punched through to the public internet
 * via an OpenFrp (frpc1) remote tunnel.
 */
@Serializable
data class ShareConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "新共享",
    val localDir: String = "",
    val username: String = "",
    val password: String = "",
    val httpEnabled: Boolean = true,
    val ftpEnabled: Boolean = false,
    val smbEnabled: Boolean = false,
    val httpPort: Int = 8080,
    val ftpPort: Int = 8021,
    val smbPort: Int = 8445,
    val autoStart: Boolean = false,
    /** 应用层文件加密口令（独立于连接账号密码）。电脑端脚本上传时用它加密，手机端自动解密还原。 */
    val encPassword: String = "",

    // ── OpenFrp / frpc1 穿透设置 ──────────────────────────────────
    /** OpenFrp auth token for frpc connection (shared across all tunnels). */
    val openFrpFrpcToken: String = "",
    /** Use KCP transport instead of TCP for the node connection. */
    val openFrpUseKcp: Boolean = false,

    /** HTTP/WebDAV tunnel config. */
    val httpTunnel: TunnelConfig? = null,
    /** FTP tunnel config. */
    val ftpTunnel: TunnelConfig? = null,
    /** SMB tunnel config. */
    val smbTunnel: TunnelConfig? = null
) {
    /** Check if HTTP/WebDAV service is configured and ready. */
    fun isHttpActive(): Boolean = httpEnabled && localDir.isNotBlank()

    /** Check if FTP service is configured and ready. */
    fun isFtpActive(): Boolean = ftpEnabled && localDir.isNotBlank()

    /** Check if SMB service is configured and ready. */
    fun isSmbActive(): Boolean = smbEnabled && localDir.isNotBlank()

    /** True if HTTP has an enabled tunnel. */
    fun hasHttpTunnel(): Boolean = httpTunnel?.enabled == true

    /** True if FTP has an enabled tunnel. */
    fun hasFtpTunnel(): Boolean = ftpTunnel?.enabled == true

    /** True if SMB has an enabled tunnel. */
    fun hasSmbTunnel(): Boolean = smbTunnel?.enabled == true

    /** Check if any service is configured. */
    fun hasAnyService(): Boolean = (httpEnabled || ftpEnabled || smbEnabled) && localDir.isNotBlank()

    /** Get all enabled tunnel configs for active services. */
    fun activeTunnels(): List<Pair<String, TunnelConfig>> {
        val result = mutableListOf<Pair<String, TunnelConfig>>()
        if (hasHttpTunnel()) result += "http" to httpTunnel!!
        if (hasFtpTunnel()) result += "ftp" to ftpTunnel!!
        if (hasSmbTunnel()) result += "smb" to smbTunnel!!
        return result
    }
}

/**
 * Configuration for a single OpenFrp tunnel (frpc proxy).
 *
 * Created via the in-app "Create Tunnel" button in ServiceConfigScreen.
 */
@Serializable
data class TunnelConfig(
    /** Whether this tunnel is currently enabled. */
    val enabled: Boolean = false,
    /** OpenFrp proxy ID (used to identify the tunnel). */
    val proxyId: Int,
    /** OpenFrp node ID. */
    val nodeId: Int,
    /** OpenFrp node hostname for frpc connection. */
    val nodeHost: String,
    /** frps server port for this node (null → default 7000). */
    val nodePort: Int? = null,
    /** Remote port assigned by OpenFrp (null if not yet created). */
    val remotePort: Int? = null,
    /** Local port this tunnel maps to. */
    val localPort: Int,
    /** Tunnel type (tcp, udp, etc). */
    val type: String = "tcp",
    /** Tunnel name on OpenFrp. */
    val name: String = "",
    /** 穿透服务商："openfrp" / "mefrp"。启动 frpc 时按此取对应令牌。 */
    val provider: String = "openfrp"
)
