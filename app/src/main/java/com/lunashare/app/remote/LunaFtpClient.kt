package com.lunashare.app.remote

import android.util.Log
import com.lunashare.app.model.RemoteConnection
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import java.io.File
import java.io.IOException

/** FTP 客户端（Apache Commons Net，被动模式，UTF-8）。 */
class LunaFtpClient(private val cfg: RemoteConnection) : RemoteFileClient {

    companion object {
        private const val TAG = "LunaFtpClient"
    }

    private val client = FTPClient()

    private fun root(): String = if (cfg.remotePath.isBlank()) "/" else cfg.remotePath

    override fun connect() {
        client.connectTimeout = 10000
        client.defaultTimeout = 30000
        client.connect(cfg.host, cfg.effectivePort())
        if (cfg.anonymous || cfg.username.isBlank()) {
            if (!client.login("anonymous", "lunashare@local")) throw IOException("匿名登录被拒绝")
        } else {
            if (!client.login(cfg.username, cfg.password)) throw IOException("登录失败：用户名或密码错误")
        }
        client.enterLocalPassiveMode()
        client.setFileType(FTP.BINARY_FILE_TYPE)
        runCatching { client.setControlEncoding("UTF-8") }
        // 验证初始目录可达
        if (!client.changeWorkingDirectory(root())) {
            throw IOException("初始目录不存在：${root()}")
        }
        Log.i(TAG, "FTP connected ${cfg.host}:${cfg.effectivePort()} root=${root()}")
    }

    override fun list(path: String): List<RemoteEntry> {
        ensureConnected()
        val dir = if (path.isBlank()) root() else path
        if (!client.changeWorkingDirectory(dir)) throw IOException("无法进入目录 $dir")
        val files: Array<FTPFile> = client.listFiles() ?: throw IOException("列目录失败")
        return files.filter { it.name != "." && it.name != ".." }
            .map {
                RemoteEntry(
                    name = it.name,
                    path = joinPath(dir, it.name),
                    isDirectory = it.isDirectory,
                    size = it.size,
                    lastModified = it.timestamp?.timeInMillis ?: 0L,
                )
            }
            .sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    override fun download(remotePath: String, localFile: File) {
        ensureConnected()
        // 用 retrieveFile 绝对路径更稳（避免 CWD 状态漂移）
        val ok = client.retrieveFile(encodePath(remotePath), localFile.outputStream().buffered())
        if (!ok) throw IOException("下载失败（服务器拒绝）：$remotePath")
    }

    override fun upload(localFile: File, remoteDir: String) {
        ensureConnected()
        val target = joinPath(if (remoteDir.isBlank()) root() else remoteDir, localFile.name)
        val ok = client.storeFile(encodePath(target), localFile.inputStream().buffered())
        if (!ok) throw IOException("上传失败（服务器拒绝）：$target")
    }

    override fun delete(remotePath: String, isDirectory: Boolean) {
        ensureConnected()
        val ok = if (isDirectory) client.removeDirectory(encodePath(remotePath))
        else client.deleteFile(encodePath(remotePath))
        if (!ok) throw IOException("删除失败：$remotePath")
    }

    override fun mkdir(parentPath: String, name: String) {
        ensureConnected()
        val target = joinPath(if (parentPath.isBlank()) root() else parentPath, name)
        if (!client.makeDirectory(encodePath(target))) throw IOException("新建目录失败：$target")
    }

    override fun disconnect() {
        runCatching {
            if (client.isConnected) {
                client.logout()
                client.disconnect()
            }
        }
    }

    private fun ensureConnected() {
        if (!client.isConnected) {
            connect()
        } else {
            // 验证连接活性
            if (!runCatching { client.sendNoOp() }.getOrDefault(false)) {
                disconnect()
                connect()
            }
        }
    }

    private fun encodePath(p: String): String = p

    private fun joinPath(dir: String, name: String): String =
        dir.trimEnd('/') + "/" + name
}
