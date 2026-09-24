package com.lunashare.app.remote

import android.util.Log
import com.lunashare.app.model.RemoteConnection
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.io.File
import java.io.IOException

/**
 * SMB 客户端（jcifs-ng，SMB1/2/3）。
 *
 * remotePath 语义："/共享名/子目录/"（服务器绝对路径），
 * 实际 URL = smb://host:port + remotePath。
 */
class SmbClient(private val cfg: RemoteConnection) : RemoteFileClient {

    companion object {
        private const val TAG = "SmbClient"
    }

    private lateinit var ctx: CIFSContext
    private val base: String

    init {
        base = "smb://${cfg.host}:${cfg.effectivePort()}" +
            (if (cfg.remotePath.isBlank()) "/" else cfg.remotePath.trimEnd('/') + "/")
        Log.i(TAG, "SMB base=$base")
    }

    private fun url(path: String): String {
        if (path.isBlank()) return base
        val p = if (path.startsWith("/")) path else "/" + path
        val root = base.substringBefore(cfg.remotePath.trimEnd('/')).trimEnd('/')
        return (root + p).let { if (it.endsWith("/")) it else "$it/" }
    }

    private fun smb(path: String): SmbFile = SmbFile(url(path), ctx)

    override fun connect() {
        val props = java.util.Properties().apply {
            // 纯直连（禁广播/WINS），兼容 SMB1..3.1.1 老旧 NAS
            setProperty("jcifs.smb.client.minVersion", "SMB1")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.resolveOrder", "DNS")
            setProperty("jcifs.smb.client.responseTimeout", "15000")
            setProperty("jcifs.smb.client.connTimeout", "10000")
            setProperty("jcifs.smb.client.soTimeout", "35000")
        }
        ctx = BaseContext(PropertyConfiguration(props))
        ctx = if (cfg.anonymous || (cfg.username.isBlank() && cfg.password.isBlank())) {
            ctx.withAnonymousCredentials()
        } else {
            ctx.withCredentials(NtlmPasswordAuthenticator(null, cfg.username, cfg.password))
        }
        // 探测：列根（共享列表或初始目录）
        val f = smb(cfg.remotePath)
        try {
            f.listFiles()
        } catch (e: Exception) {
            throw IOException("连接失败：${readableError(e)}", e)
        }
    }

    private fun readableError(e: Exception): String = when {
        e.message?.contains("Connection refused", true) == true -> "连接被拒绝（端口未开放）"
        e.message?.contains("timed out", true) == true ||
            e.message?.contains("timeout", true) == true -> "连接超时（主机不可达）"
        e.message?.contains("Logon failure", true) == true ||
            e.message?.contains("Access is denied", true) == true ||
            e.message?.contains("NT_STATUS_LOGON_FAILURE", true) == true -> "认证失败：用户名或密码错误"
        e.message?.contains("NT_STATUS_ACCESS_DENIED", true) == true -> "无访问权限"
        e.message?.contains("unknown host", true) == true -> "主机名无法解析"
        else -> e.message ?: e.javaClass.simpleName
    }

    override fun list(path: String): List<RemoteEntry> {
        val dir = if (path.isBlank()) cfg.remotePath else path
        val files = smb(dir).listFiles()
        return files.map {
            val full = it.path.removePrefix("smb://${cfg.host}:${cfg.effectivePort()}")
            RemoteEntry(
                name = it.name.trimEnd('/').substringAfterLast('/'),
                path = full.trimEnd('/').let { p -> if (it.isDirectory) p else p },
                isDirectory = it.isDirectory,
                size = it.length(),
                lastModified = it.lastModified,
            )
        }.sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    override fun download(remotePath: String, localFile: File) {
        smb(remotePath).inputStream.use { input ->
            localFile.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
    }

    override fun upload(localFile: File, remoteDir: String) {
        val dir = if (remoteDir.isBlank()) cfg.remotePath else remoteDir
        val target = dir.trimEnd('/') + "/" + localFile.name
        smb(target).outputStream.use { output ->
            localFile.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
        }
    }

    override fun delete(remotePath: String, isDirectory: Boolean) {
        smb(remotePath).delete()
    }

    override fun mkdir(parentPath: String, name: String) {
        val dir = if (parentPath.isBlank()) cfg.remotePath else parentPath
        smb(dir.trimEnd('/') + "/" + name).mkdir()
    }

    override fun disconnect() { /* jcifs context 按连接复用，无需显式断开 */ }
}
