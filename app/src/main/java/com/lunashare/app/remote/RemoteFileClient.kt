package com.lunashare.app.remote

import com.lunashare.app.model.RemoteConnection

/** 远程文件条目（协议无关）。 */
data class RemoteEntry(
    val name: String,
    /** 完整远程路径（协议语义内，如 webdav 的 /a/b、smb 的 smb://host/share/a/b、ftp 的 /a/b）。 */
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long = 0L,
)

/**
 * 远程共享客户端统一接口。实现：[WebDavClient] / [SmbClient] / [LunaFtpClient]。
 *
 * 所有方法均为阻塞 IO，调用方需在 Dispatchers.IO 上执行。
 */
interface RemoteFileClient {
    /** 建立连接并验证可达（失败抛异常，消息面向用户可读）。 */
    fun connect()

    /** 列目录；path 为空表示根目录/初始目录。 */
    fun list(path: String): List<RemoteEntry>

    /** 下载远程文件到本地 File。 */
    fun download(remotePath: String, localFile: java.io.File)

    /** 上传本地文件到远程目录（remoteDir 为目录路径）。 */
    fun upload(localFile: java.io.File, remoteDir: String)

    /** 删除文件或空目录。 */
    fun delete(remotePath: String, isDirectory: Boolean)

    /** 新建目录。 */
    fun mkdir(parentPath: String, name: String)

    /** 断开并释放资源。 */
    fun disconnect()
}

/** 客户端工厂。 */
object RemoteClientFactory {
    fun create(cfg: RemoteConnection): RemoteFileClient = when (cfg.protocol) {
        "webdav" -> WebDavClient(cfg)
        "smb" -> SmbClient(cfg)
        "ftp" -> LunaFtpClient(cfg)
        else -> throw IllegalArgumentException("未知协议: ${cfg.protocol}")
    }
}
