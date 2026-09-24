package com.lunashare.app.service

import android.util.Log
import com.lunashare.app.util.EncodingFixer
import java.io.*
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.*

/**
 * Minimal FTP server handler for a single control connection.
 * Supports PORT (active) and PASV (passive) modes for data transfer.
 *
 * The FtpHandler is instantiated per-connection and handles the full
 * FTP control session lifecycle.
 *
 * 编码：控制命令按字节读取，严格 UTF-8 优先、GBK 回退（Windows ftp.exe /
 * 资源管理器拖拽上传默认发 GBK 文件名，宽松 UTF-8 解码会把中文名写成 U+FFFD 落盘）。
 * 一旦检测到 GBK 客户端，响应与 LIST 列表也切换为 GBK 回写，双向不乱码。
 */
class FtpHandler(
    private val socket: Socket,
    rawInput: InputStream?,
    private val firstCmd: String?,
    private val rootDir: File,
    private val ftpUsername: String = "",
    private val ftpPassword: String = ""
) {
    companion object {
        private const val TAG = "FtpHandler"
    }

    private val input: InputStream = rawInput ?: socket.getInputStream()

    /** 控制连接编码：检测到 GBK 客户端后切换，sendResponse / LIST 同步使用。 */
    private var ctrlCharset: Charset = Charsets.UTF_8

    /** Current working directory relative to rootDir */
    private var cwd: String = "/"
    /** Stored PORT-mode data address */
    private var dataHost: String? = null
    private var dataPort: Int = 0
    /** PASV-mode listening socket (null when not in passive mode) */
    private var pasvServer: ServerSocket? = null
    /** UTF-8 filename encoding enabled (defaults true; FTP names are UTF-8) */
    private var utf8Enabled: Boolean = true
    /** Login state */
    private var userPassed: String = ""
    private var loggedIn: Boolean = false

    fun handle() {
        try {
            if (firstCmd != null) {
                sendResponse("220 LunaShare FTP Server ready")
                if (!handleCommand(firstCmd)) return
            } else {
                sendResponse("220 LunaShare FTP Server ready")
            }
            while (true) {
                val line = readControlLine() ?: break
                if (line.isEmpty()) continue
                if (!handleCommand(line)) break
            }
        } catch (e: IOException) {
            Log.d(TAG, "Connection closed: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "FTP error", e)
        } finally {
            try { pasvServer?.close() } catch (_: Exception) {}
            pasvServer = null
        }
    }

    /**
     * 从控制连接读一行（到 \n），按字节解码：严格 UTF-8 成功则用 UTF-8；
     * 失败且整体像 GBK 时切换到 GBK（Windows ftp.exe 等老客户端）。
     * 返回 null 表示连接结束 / 超时。
     */
    private fun readControlLine(): String? {
        val bytes = readLineBytes(input) ?: return null
        if (bytes.isEmpty()) return ""
        if (ctrlCharset != Charsets.UTF_8) {
            return String(bytes, ctrlCharset)
        }
        val strict = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val decoded = try {
            strict.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            null
        }
        if (decoded != null) return decoded
        // 非 UTF-8：整行按 GBK 重解（含文件名的命令行才可能带非 ASCII 字节）
        val gbk = EncodingFixer.GBK
        if (gbk != null) {
            val retry = try { String(bytes, gbk) } catch (_: Exception) { null }
            // GBK 解出来的行至少不应包含替换符
            if (retry != null && !retry.contains('\uFFFD')) {
                ctrlCharset = gbk
                Log.d(TAG, "FTP control line not UTF-8, switching to GBK")
                return retry
            }
        }
        // 兜底：宽松 UTF-8（保持旧行为，不因无法识别而断连）
        return String(bytes, Charsets.UTF_8)
    }

    /** 读到 \n 为止的原始字节；超时 / EOF 返回已读内容或 null。 */
    private fun readLineBytes(input: InputStream): ByteArray? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = try { input.read() } catch (_: SocketTimeoutException) { -1 }
            if (b < 0) return if (buf.size() > 0) buf.toByteArray() else null
            if (b == '\n'.code) break
            buf.write(b)
        }
        return buf.toByteArray()
    }

    private fun handleCommand(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return true

        val idx = trimmed.indexOf(' ')
        val cmd = (if (idx < 0) trimmed else trimmed.substring(0, idx)).uppercase()
        val arg = if (idx < 0) "" else trimmed.substring(idx + 1).trim()

        Log.d(TAG, "FTP CMD: $cmd $arg")

        try {
            when (cmd) {
                "USER" -> handleUser(arg)
                "PASS" -> handlePass(arg)
                "SYST" -> sendResponse("215 UNIX Type: L8")
                "PWD" -> sendResponse("257 \"$cwd\" is the current directory")
                "TYPE" -> handleType(arg)
                "MODE" -> sendResponse("200 MODE S ok")
                "STRU" -> sendResponse("200 STRU F ok")
                "PORT" -> handlePort(arg)
                "PASV" -> handlePasv()
                "EPSV" -> handleEpsv()
                "LIST" -> handleList(arg)
                "NLST" -> handleList(arg)
                "RETR" -> handleRetr(arg)
                "STOR" -> handleStor(arg, append = false)
                "APPE" -> handleStor(arg, append = true)
                "CWD" -> handleCwd(arg)
                "CDUP" -> handleCdup()
                "SIZE" -> handleSize(arg)
                "MDTM" -> handleMdtm(arg)
                "NOOP" -> sendResponse("200 OK")
                "FEAT" -> handleFeat()
                "OPTS" -> handleOpts(arg)
                "AUTH" -> sendResponse("502 AUTH not available")
                "PROT" -> sendResponse("200 PROT P ok")
                "PBSZ" -> sendResponse("200 PBSZ 0 ok")
                "QUIT" -> {
                    sendResponse("221 Goodbye")
                    return false
                }
                else -> sendResponse("502 Command not implemented: $cmd")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling $cmd", e)
            sendResponse("451 Requested action aborted: local error")
        }
        return true
    }

    // ── Auth ──

    private fun handleUser(arg: String) {
        userPassed = arg
        loggedIn = false
        if (ftpUsername.isBlank()) {
            loggedIn = true
            sendResponse("230 User logged in, proceed")
        } else {
            sendResponse("331 User name ok, need password")
        }
    }

    private fun handlePass(arg: String) {
        if (ftpUsername.isBlank()) {
            loggedIn = true
            sendResponse("230 Already logged in")
            return
        }
        if (userPassed == ftpUsername && arg == ftpPassword) {
            loggedIn = true
            sendResponse("230 User logged in, proceed")
        } else {
            sendResponse("530 Login incorrect")
        }
    }

    private fun requireAuth(): Boolean {
        if (!loggedIn) {
            sendResponse("530 Not logged in")
            return false
        }
        return true
    }

    // ── Type ──

    private fun handleType(arg: String) {
        when (arg.uppercase().firstOrNull()) {
            'A', 'I', 'L' -> sendResponse("200 TYPE set to ${arg.uppercase()}")
            else -> sendResponse("504 TYPE not implemented: $arg")
        }
    }

    private fun handlePort(arg: String) {
        try { pasvServer?.close() } catch (_: Exception) {}
        pasvServer = null
        val parts = arg.split(",")
        if (parts.size != 6) {
            sendResponse("501 Invalid PORT format")
            return
        }
        try {
            dataHost = parts.take(4).joinToString(".")
            val p1 = parts[4].toInt()
            val p2 = parts[5].toInt()
            dataPort = (p1 shl 8) or p2
            if (dataPort !in 1..65535) {
                sendResponse("501 Invalid PORT: port out of range")
                dataHost = null
                return
            }
            sendResponse("200 PORT command successful")
        } catch (e: NumberFormatException) {
            sendResponse("501 Invalid PORT format")
        }
    }

    private fun openDataConnection(): Socket? {
        // ── PASV mode: accept the client's incoming data connection ──
        if (pasvServer != null) {
            return try {
                pasvServer!!.soTimeout = 15000
                val s = pasvServer!!.accept()
                try { pasvServer!!.close() } catch (_: Exception) {}
                pasvServer = null
                s
            } catch (e: Exception) {
                Log.e(TAG, "PASV data accept failed", e)
                try { pasvServer?.close() } catch (_: Exception) {}
                pasvServer = null
                sendResponse("425 Cannot open data connection: ${e.message}")
                null
            }
        }
        // ── PORT mode: connect back to the client-provided address ──
        val host = dataHost ?: run {
            sendResponse("425 Use PORT first")
            return null
        }
        val port = dataPort
        return try {
            val dataSocket = Socket()
            dataSocket.connect(InetSocketAddress(host, port), 15000)
            dataSocket
        } catch (e: Exception) {
            Log.e(TAG, "Data connection failed to $host:$port", e)
            sendResponse("425 Cannot open data connection: ${e.message}")
            null
        }
    }

    private fun handlePasv() {
        try { pasvServer?.close() } catch (_: Exception) {}
        try {
            pasvServer = ServerSocket(0)
            val port = pasvServer!!.localPort
            val ip = localIpV4()
            val parts = ip.split(".")
            if (parts.size != 4) {
                sendResponse("502 PASV not available")
                try { pasvServer!!.close() } catch (_: Exception) {}
                pasvServer = null
                return
            }
            val p1 = port ushr 8
            val p2 = port and 0xFF
            sendResponse("227 Entering Passive Mode (${parts[0]},${parts[1]},${parts[2]},${parts[3]},$p1,$p2)")
        } catch (e: Exception) {
            Log.e(TAG, "PASV error", e)
            try { pasvServer?.close() } catch (_: Exception) {}
            pasvServer = null
            sendResponse("502 PASV not available: ${e.message}")
        }
    }

    private fun handleEpsv() {
        try { pasvServer?.close() } catch (_: Exception) {}
        try {
            pasvServer = ServerSocket(0)
            val port = pasvServer!!.localPort
            sendResponse("229 Entering Extended Passive Mode (|||${port}|)")
        } catch (e: Exception) {
            Log.e(TAG, "EPSV error", e)
            try { pasvServer?.close() } catch (_: Exception) {}
            pasvServer = null
            sendResponse("502 EPSV not available: ${e.message}")
        }
    }

    /** Best-effort local IPv4 address the client should connect to for PASV. */
    private fun localIpV4(): String {
        val la = socket.localAddress
        if (la is InetSocketAddress) {
            val addr = la.address
            if (addr is Inet4Address && !addr.isLoopbackAddress) {
                return addr.hostAddress.substringBefore('%')
            }
        }
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val it = intf.inetAddresses
                while (it.hasMoreElements()) {
                    val a = it.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        return a.hostAddress.substringBefore('%')
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    private fun handleList(arg: String) {
        if (!requireAuth()) return
        val targetDir = resolvePath(arg)

        if (targetDir == null || !targetDir.isDirectory) {
            sendResponse("550 Directory not found")
            return
        }

        sendResponse("150 Opening data connection for directory listing")
        val dataSocket = openDataConnection() ?: return

        try {
            dataSocket.use { ds ->
                // 列表文件名用与控制连接一致的编码回写（GBK 客户端收到 GBK 名字才不乱码）
                val dataOut = BufferedWriter(OutputStreamWriter(ds.getOutputStream(), ctrlCharset))
                val entries = targetDir.listFiles()?.filter { !it.name.startsWith(".") }?.sortedBy { it.name.lowercase() } ?: emptyList()
                val df = SimpleDateFormat("MMM dd HH:mm", Locale.US)

                for (entry in entries) {
                    val perms = if (entry.isDirectory) "drwxr-xr-x" else "-rw-r--r--"
                    val size = entry.length()
                    val date = df.format(Date(entry.lastModified()))
                    val name = entry.name + (if (entry.isDirectory) "/" else "")
                    dataOut.write("$perms 1 owner group $size $date $name\r\n")
                }
                dataOut.flush()
            }
            sendResponse("226 Directory send OK")
        } catch (e: Exception) {
            Log.e(TAG, "LIST data transfer error", e)
            sendResponse("426 Connection closed; transfer aborted")
        }
    }

    private fun handleRetr(arg: String) {
        if (!requireAuth()) return
        if (arg.isBlank()) {
            sendResponse("501 Syntax error: filename required")
            return
        }

        val file = resolvePath(arg)
        if (file == null || !file.isFile) {
            sendResponse("550 File not found: $arg")
            return
        }

        sendResponse("150 Opening data connection for $arg (${file.length()} bytes)")
        val dataSocket = openDataConnection() ?: return

        try {
            dataSocket.use { ds ->
                val dataOut = ds.getOutputStream()
                FileInputStream(file).use { fis -> fis.copyTo(dataOut, 8192) }
                dataOut.flush()
            }
            sendResponse("226 Transfer complete")
        } catch (e: Exception) {
            Log.e(TAG, "RETR data transfer error", e)
            sendResponse("426 Connection closed; transfer aborted")
        }
    }

    private fun handleStor(arg: String, append: Boolean) {
        if (!requireAuth()) return
        if (arg.isBlank()) {
            sendResponse("501 Syntax error: filename required")
            return
        }
        val fileName = arg.trim()
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            sendResponse("550 Filename not allowed")
            return
        }

        val base = rootDir
        val targetFile = File(base, cwd.removePrefix("/") + "/" + fileName).normalize()

        if (!targetFile.absolutePath.startsWith(base.normalize().absolutePath)) {
            sendResponse("550 Path traversal denied")
            return
        }

        if (targetFile.parentFile != null && !targetFile.parentFile.exists()) {
            targetFile.parentFile.mkdirs()
        }

        sendResponse("150 Opening data connection for upload")

        val dataSocket = openDataConnection() ?: return

        try {
            dataSocket.use { ds ->
                val dataIn = ds.getInputStream()
                val fileOut = FileOutputStream(targetFile, append)
                fileOut.use { fos ->
                    val buf = ByteArray(32768)
                    while (true) {
                        val n = dataIn.read(buf)
                        if (n < 0) break
                        fos.write(buf, 0, n)
                    }
                }
            }
            sendResponse("226 Transfer complete")
        } catch (e: Exception) {
            Log.e(TAG, "STOR data transfer error", e)
            if (targetFile.exists() && !append) targetFile.delete()
            sendResponse("426 Connection closed; transfer aborted")
        }
    }

    private fun handleCwd(arg: String) {
        if (!requireAuth()) return
        val newDir = resolvePath(arg) ?: run {
            sendResponse("550 Directory not found: $arg")
            return
        }
        if (!newDir.isDirectory) {
            sendResponse("550 Not a directory: $arg")
            return
        }
        cwd = getRelativePath(newDir)
        sendResponse("250 CWD successful. \"$cwd\" is current directory")
    }

    private fun handleCdup() {
        if (!requireAuth()) return
        handleCwd("..")
    }

    private fun handleSize(arg: String) {
        if (!requireAuth()) return
        val file = resolvePath(arg)
        if (file != null && file.isFile) {
            sendResponse("213 ${file.length()}")
        } else {
            sendResponse("550 File not found")
        }
    }

    private fun handleMdtm(arg: String) {
        if (!requireAuth()) return
        val file = resolvePath(arg)
        if (file != null && file.isFile) {
            val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
            sendResponse("213 ${sdf.format(Date(file.lastModified()))}")
        } else {
            sendResponse("550 File not found")
        }
    }

    private fun handleFeat() {
        sendResponse("211-Features:")
        sendResponse(" SIZE")
        sendResponse(" MDTM")
        sendResponse(" PASV")
        sendResponse(" EPSV")
        sendResponse(" UTF8")
        sendResponse("211 End")
    }

    private fun handleOpts(arg: String) {
        val parts = arg.split(" ", limit = 2)
        val opt = parts[0].uppercase()
        if (opt == "UTF8") {
            val v = parts.getOrNull(1)?.uppercase() ?: "ON"
            utf8Enabled = (v != "OFF")
            // 客户端显式协商 UTF8 时控制编码回到 UTF-8（此前可能是 GBK 自动探测）
            ctrlCharset = if (utf8Enabled) Charsets.UTF_8 else ctrlCharset
            sendResponse("200 UTF8 encoding is now ${if (utf8Enabled) "enabled" else "disabled"}")
            return
        }
        sendResponse("200 OPTS ${arg.ifBlank { "" }} ok")
    }

    private fun sendResponse(msg: String) {
        try {
            val out = socket.getOutputStream()
            out.write((msg + "\r\n").toByteArray(ctrlCharset))
            out.flush()
        } catch (e: IOException) {
            throw e
        }
    }

    private fun resolvePath(ftpPath: String): File? {
        val base = rootDir.normalize().absoluteFile
        val absoluteCwd = File(base, cwd.removePrefix("/")).normalize()

        val target = when {
            ftpPath.isBlank() -> absoluteCwd
            ftpPath.startsWith("/") -> File(base, ftpPath.removePrefix("/"))
            else -> File(absoluteCwd, ftpPath)
        }.normalize()

        return if (target.absolutePath.startsWith(base.absolutePath)) target else null
    }

    private fun getRelativePath(dir: File = File(rootDir, cwd.removePrefix("/"))): String {
        val base = rootDir.normalize().absolutePath
        val path = dir.normalize().absolutePath
        return if (path == base) "/"
        else "/" + path.removePrefix(base).removePrefix("/").replace("\\", "/")
    }
}
