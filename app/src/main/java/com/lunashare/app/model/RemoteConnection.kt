package com.lunashare.app.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 「连接共享」配置：连接别人分享的 WebDAV / SMB / FTP 服务。
 *
 * 创建后出现在服务面板（可连接/断开），连接成功后文件 Tab 出现对应的
 * 远程文件管理 Tab（浏览 / 下载 / 上传）。
 */
@Serializable
data class RemoteConnection(
    val id: String = UUID.randomUUID().toString(),
    /** 显示名（服务面板卡片与文件 Tab 标题）。 */
    val name: String = "远程共享",
    /** 协议类型：webdav / smb / ftp。 */
    val protocol: String = "webdav",
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
    /** 匿名访问（webdav/ftp 有效；smb 需 guest 账号则填用户名 guest 空密码）。 */
    val anonymous: Boolean = false,
    /** 初始远程目录（webdav 为服务器上的路径，smb 为共享名+子路径，ftp 为起始目录）。 */
    val remotePath: String = "/",
    /** 是否在 app 启动时自动连接。 */
    val autoConnect: Boolean = false,
) {
    /** 默认端口（未显式配置时使用）。 */
    fun effectivePort(): Int = when (port) {
        in 1..65535 -> port
        else -> when (protocol) {
            "webdav" -> if (host.startsWith("https")) 443 else 80
            "smb" -> 445
            "ftp" -> 21
            else -> 0
        }
    }

    /** 连接地址展示（卡片副标题）。 */
    fun displayAddress(): String = "$host:${effectivePort()}"

    companion object {
        val PROTOCOLS = listOf("webdav", "smb", "ftp")
        fun protocolLabel(p: String): String = when (p) {
            "webdav" -> "WebDAV"
            "smb" -> "SMB"
            "ftp" -> "FTP"
            else -> p.uppercase()
        }
    }
}
