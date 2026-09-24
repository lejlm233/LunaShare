package com.lunashare.app.service

import android.util.Log
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Embedded HTTP/WebDAV + FTP file server for Android.
 *
 * Serves files from a [rootDir] on [port].
 * Auto-detects HTTP vs FTP from the first byte of the client connection.
 * Supports:
 *   - HTTP GET, directory listing, basic MIME types
 *   - WebDAV (PROPFIND, PUT, DELETE, MKCOL, COPY, MOVE, OPTIONS, LOCK, UNLOCK)
 *   - Read-only FTP with PORT (active) mode
 *
 * NOTE: HTTP/WebDAV reads request and body on raw bytes (not via Reader)
 *       so binary PUT uploads are never corrupted by charset decoding.
 */
class FileServer(
    private val port: Int = 8080,
    private val rootDir: String = "",
    private val username: String = "",
    private val password: String = "",
    private val encPassword: String = "",
    private val shareId: String = "",
    private val onUploadComplete: (fileName: String, sizeBytes: Long) -> Unit = { _, _ -> }
) {
    companion object {
        private const val TAG = "FileServer"
        private const val BACKLOG = 10
        private const val SESSION_TTL_MS = 7L * 24 * 3600 * 1000
        private const val ENC_HEADER_LEN = 4   // v2 封装格式里 nameLen 的字节数

        private val HTTP_METHODS = setOf(
            "GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS",
            "PATCH", "CONNECT", "TRACE",
            "PROPFIND", "PROPPATCH", "MKCOL", "COPY", "MOVE", "LOCK", "UNLOCK"
        )
    }

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var acceptThread: Thread? = null
    private val sessions = ConcurrentHashMap<String, Long>()

    // 分包加密上传：按会话缓存各分包 .part，收齐后合并解密落盘
    private val encChunks = ConcurrentHashMap<String, EncChunkSession>()

    // 已完成解密落盘的分包会话（session → 完成时间戳），供脚本端断点续传查询「上次是否已传完」；
    // 与分包会话同窗口（ENC_SESSION_RETENTION_MS）后清理
    private val encFinished = ConcurrentHashMap<String, Long>()
    private val ENC_TMP_DIR_NAME = ".luna_enc_tmp"

    /** 分包会话/完成记录的保留窗口：期间脚本端可断点续传，超时清理（同时防 .part 垃圾长期占盘）。 */
    private val ENC_SESSION_RETENTION_MS = 24L * 3600 * 1000

    /** 一次分包上传会话的累积状态（线程安全由调用方 synchronized 保证）。 */
    private data class EncChunkSession(
        val session: String,
        val total: Int,
        val dir: File,
        val received: BooleanArray,
        val createdAt: Long,
        var lastActiveAt: Long,
        /** 已接收的分包字节总数（用于进度日志）。 */
        var receivedBytes: Long = 0,
        /** 上次进度日志时间戳（2 秒节流，防刷屏）。 */
        var lastProgressLogAt: Long = 0,
        /**
         * 完成权是否已移交。收齐后由「写入最后一个分包」的那次请求独占接管合并+解密+落盘，
         * 置 true 后其它并发/重放请求一律回 202 不再参与，避免读到被覆盖的 .part 拼出错位 blob。
         */
        var finalizeHandedOff: Boolean = false
    )

    val isActive: Boolean get() = isRunning

    fun start(): Result<Unit> {
        return try {
            val dir = File(rootDir)
            if (rootDir.isNotBlank() && !dir.isDirectory) {
                if (!dir.mkdirs()) {
                    return Result.failure(IllegalArgumentException("无效的目录: $rootDir"))
                }
            }
            serverSocket = ServerSocket(port, BACKLOG)
            isRunning = true
            Log.i(TAG, "Server started on port $port, root=$rootDir")
            acceptThread = thread(name = "file-server-accept", isDaemon = true) {
                try {
                    while (isRunning && !serverSocket!!.isClosed) {
                        val client = serverSocket!!.accept()
                        thread(name = "file-server-worker", isDaemon = true) {
                            handleClient(client)
                        }
                    }
                } catch (e: Exception) {
                    if (isRunning) Log.e(TAG, "Accept error", e)
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            Result.failure(e)
        }
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        // 清掉未完成的分包会话临时文件，避免残留占用空间
        try {
            for ((_, s) in encChunks) { try { s.dir.deleteRecursively() } catch (_: Exception) {} }
            encChunks.clear()
        } catch (_: Exception) {}
        Log.i(TAG, "Server stopped")
    }

    // ── Client dispatch ──

    private fun handleClient(client: Socket) {
        val remoteAddr = client.remoteSocketAddress?.toString() ?: "unknown"
        Log.i(TAG, "=== New connection from $remoteAddr ===")
        try {
            client.soTimeout = 30000
            val rawInput = client.getInputStream()
            val b = try { rawInput.read() } catch (_: SocketTimeoutException) { -1 }
            Log.i(TAG, "=== DIAG first byte from $remoteAddr: ${if (b >= 0) "0x${Integer.toHexString(b)} ('${if(b>=32&&b<127) b.toChar().toString() else "?"}') " else "TIMEOUT(-1)"} ===")
            client.soTimeout = 60000

            if (b < 0) {
                Log.d(TAG, "Timeout/EOF waiting for data from $remoteAddr")
                return
            }

            val pushback = PushbackInputStream(rawInput)
            pushback.unread(b)

            val firstLine = readLineRaw(pushback)
            client.soTimeout = 60000

            if (firstLine == null) {
                Log.d(TAG, "Timeout waiting for first line from $remoteAddr")
                return
            }

            Log.d(TAG, "First line from $remoteAddr: ${firstLine.take(100)}")
            val trimmed = firstLine.trim()
            if (looksLikeFtpCommand(trimmed)) {
                Log.d(TAG, "Routing to FTP handler for $remoteAddr")
                handleFtp(client, pushback, trimmed)
            } else {
                Log.d(TAG, "Routing to HTTP handler for $remoteAddr")
                handleHttp(client, pushback, firstLine)
            }
        } catch (e: Exception) {
            if (isRunning) Log.e(TAG, "Client handling error for $remoteAddr: ${e.message}", e)
        } finally {
            try { client.shutdownOutput() } catch (_: Exception) {}
            try {
                client.soTimeout = 500
                val drain = client.getInputStream()
                val skipBuf = ByteArray(1024)
                while (drain.read(skipBuf) > 0) { /* discard remaining data */ }
            } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    /** Read a single line (until \n) and return it without the trailing newline. */
    private fun readLineRaw(input: InputStream): String? {
        val bytes = readLineBytes(input) ?: return null
        return bytes.decodeToString().trimEnd('\r')
    }

    /** True if [line] looks like an FTP control command (e.g. USER, PASS, LIST). */
    private fun looksLikeFtpCommand(line: String): Boolean {
        val cmd = line.substringBefore(' ').uppercase()
        if (cmd.length !in 3..4) return false
        if (!cmd.all { it.isUpperCase() }) return false
        if (cmd in HTTP_METHODS) return false
        return true
    }

    // ── HTTP/WebDAV handler (raw-bytes based) ──

    private fun handleHttp(socket: Socket, rawInput: InputStream, firstLine: String? = null) {
        val output = socket.getOutputStream()
        var responseSent = false
        val remoteAddr = socket.remoteSocketAddress?.toString() ?: "unknown"
        Log.i(TAG, ">>> handleHttp START from $remoteAddr, firstLine=${firstLine?.take(50)}")

        val (requestLine, headerBytes) = readRequestLine(rawInput, firstLine) ?: run {
            Log.e(TAG, "<<< handleHttp END: readRequestLine failed")
            sendResponse(output, 400, "Bad Request", "text/plain", "Invalid request")
            responseSent = true
            return
        }

        val parts = requestLine.split(" ")
        if (parts.size < 2) {
            Log.e(TAG, "<<< handleHttp END: invalid request line: $requestLine")
            sendResponse(output, 400, "Bad Request", "text/plain", "Invalid request")
            return
        }

        val method = parts[0]
        var path = parts[1]
        if (path.contains("://")) {
            val after = path.substringAfter("://")
            path = "/" + after.substringAfter('/')
        }
        Log.i(TAG, "REQ: $method $path (from $remoteAddr)")

        // Parse headers
        val headers = headerBytes.decodeToString().lines().filter { it.isNotBlank() }

        // 从 URL query 提取 ?_s= 作为 session token 兜底（移动端浏览器不在
        // location.href 导航时发送 fetch 设置的 SameSite=Lax cookie）
        val sidParam = run {
            val qi = path.indexOf('?')
            if (qi < 0) null else parseQueryString(path.substring(qi + 1))["_s"]
        }

        // 登录接口：POST /_login 不需要预认证（供主题登录页使用）
        if (method == "POST" && path == "/_login") {
            handleLogin(output, headers, rawInput)
            return
        }

        // HTTP Basic Auth check (skip for preflight/discovery methods)
        if (username.isNotBlank() && method !in listOf("OPTIONS")) {
            val authed = checkAuth(headers) || hasValidSession(headers, sidParam)
            if (!authed) {
                val isPageRequest = method == "GET" &&
                    parseHeader(headers, "Accept")?.contains("text/html", ignoreCase = true) == true
                if (isPageRequest) {
                    sendResponse(output, 200, "OK", "text/html; charset=utf-8", WebUi.loginPageTemplate())
                    return
                }
                sendResponse(output, 401, "Unauthorized", "text/plain", "Authentication required",
                    extraHeaders = mapOf("WWW-Authenticate" to "Basic realm=\"File Server\""))
                return
            }
            // 已认证通过。若浏览器用 Basic Auth（如复制带账号密码的 WebDAV 连接）直接
            // 打开页面、但此时还没有会话 token（无有效 cookie 也无有效 ?_s），则建立
            // session 并 302 跳到带 ?_s 的页面。这样页面内所有 fetch 都走 URL token 认证，
            // 绕开两点：① fetch 用 absUrl() 不含凭据 → 不带 Basic Auth；② Lax cookie 不在
            // 顶级导航时发送。与登录表单成功后的 location.href='/?_s='+sid 同源。
            // 仅对 GET 页面请求生效；WebDAV 方法（PROPFIND 等带 Basic Auth 但不含
            // text/html）不重定向，以免破坏 WebDAV 客户端。
            val basicOk = checkAuth(headers)
            val hasSession = hasValidSession(headers, sidParam)
            if (basicOk && !hasSession &&
                method == "GET" &&
                parseHeader(headers, "Accept")?.contains("text/html", ignoreCase = true) == true) {
                val sid = UUID.randomUUID().toString()
                sessions[sid] = System.currentTimeMillis() + SESSION_TTL_MS
                sendResponse(output, 302, "Found", "text/plain", null,
                    extraHeaders = mapOf(
                        "Location" to "/?_s=$sid",
                        "Set-Cookie" to "luna_session=$sid; Path=/; HttpOnly; SameSite=Lax; Max-Age=${SESSION_TTL_MS / 1000}"
                    ))
                return
            }
        }

        // Decode URL path
        path = try { URLDecoder.decode(path, "UTF-8") } catch (_: Exception) { path }
        val rawPath = path
        val queryIdx = path.indexOf('?')
        if (queryIdx >= 0) path = path.substring(0, queryIdx)
        val isZipDownload = queryIdx >= 0 && rawPath.substring(queryIdx + 1).contains("zip=1")

        try {
            when (method) {
                "GET" -> handleGet(output, path, headers, isZipDownload)
                "HEAD" -> handleHead(output, path)
                "PUT" -> handlePut(output, path, headers, rawInput)
                "DELETE" -> handleDelete(output, path)
                "MKCOL" -> handleMkcol(output, path)
                "OPTIONS" -> handleOptions(output)
                "POST" -> when (path) {
                    "/_encupload" -> handleEncUpload(output, rawPath, headers, rawInput)
                    "/_encupload_chunk" -> handleEncUploadChunk(output, rawPath, headers, rawInput)
                    "/_encstatus" -> handleEncStatus(output, headers)
                    else -> sendResponse(output, 405, "Method Not Allowed", "text/plain", null)
                }
                "PROPFIND" -> handlePropfind(output, path, headers)
                "COPY" -> handleCopyMove(output, path, headers, copy = true)
                "MOVE" -> handleCopyMove(output, path, headers, copy = false)
                "LOCK" -> handleLock(output)
                "UNLOCK" -> handleUnlock(output)
                else -> sendResponse(output, 501, "Not Implemented", "text/plain", null)
            }
            Log.i(TAG, "<<< handleHttp END: $method $path completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling $method $path", e)
            if (!responseSent) {
                sendResponse(output, 500, "Internal Server Error", "text/plain", null)
                responseSent = true
            }
            Log.e(TAG, "<<< handleHttp END: exception for $method $path")
        }
    }

    private fun readRequestLine(input: InputStream, firstLine: String? = null): Pair<String, ByteArray>? {
        try {
            val requestLine: String = if (firstLine != null) {
                firstLine.trim()
            } else {
                val requestLineBytes = readLineBytes(input) ?: return null
                requestLineBytes.decodeToString().trim()
            }

            val headerBuf = ByteArrayOutputStream()
            while (true) {
                val line = readLineBytes(input) ?: break
                headerBuf.write(line)
                headerBuf.write('\n'.code)
                if (line.isEmpty() || (line.size == 1 && line[0] == '\r'.code.toByte())) {
                    break
                }
            }
            return Pair(requestLine, headerBuf.toByteArray())
        } catch (e: Exception) {
            Log.d(TAG, "Error reading request: ${e.message}")
            return null
        }
    }

    /** Read bytes until \n. Returns null on timeout/EOF. */
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

    // ── WebDAV method handlers ──

    private fun handleOptions(output: OutputStream) {
        sendResponse(output, 200, "OK", "text/plain", null,
            extraHeaders = mapOf(
                "DAV" to "1, 2",
                "Allow" to "GET, HEAD, PUT, DELETE, MKCOL, COPY, MOVE, PROPFIND, OPTIONS, LOCK, UNLOCK",
                "MS-Author-Via" to "DAV"))
    }

    private fun handleGet(output: OutputStream, path: String, headers: List<String>, isZipDownload: Boolean = false) {
        val file = resolvePath(path) ?: run {
            sendResponse(output, 403, "Forbidden", "text/plain", null); return
        }
        if (!file.exists()) {
            sendResponse(output, 404, "Not Found", "text/plain", null); return
        }
        if (file.isDirectory) {
            // Folder download as ZIP (?zip=1)
            if (isZipDownload) {
                serveDirectoryZip(output, file)
                return
            }
            if (!path.endsWith("/")) {
                // Location header must be ASCII — re-encode non-ASCII path segments
                val encoded = path.split("/").joinToString("/") { seg ->
                    try { URLEncoder.encode(seg, "UTF-8") } catch (_: Exception) { seg }
                }
                sendResponse(output, 301, "Moved Permanently", "text/plain", null,
                    extraHeaders = mapOf("Location" to "$encoded/"))
                return
            }
            val accept = parseHeader(headers, "Accept") ?: ""
            if (accept.contains("application/json")) {
                serveDirectoryJson(output, path, file)
                return
            }
            val indexFile = File(file, "index.html")
            if (indexFile.isFile) serveFile(output, indexFile)
            else serveDirectoryListing(output, path, file)
        } else {
            serveFile(output, file)
        }
    }

    private fun handleHead(output: OutputStream, path: String) {
        val file = resolvePath(path)
        if (file == null || !file.exists()) {
            sendResponse(output, 404, "Not Found", "text/plain", null); return
        }
        if (file.isFile) {
            sendResponse(output, 200, "OK", getMimeType(file.extension), null, contentLength = file.length())
        } else {
            sendResponse(output, 200, "OK", "httpd/unix-directory", null)
        }
    }

    private fun handlePut(output: OutputStream, path: String, headers: List<String>, rawInput: InputStream) {
        val file = resolvePath(path) ?: run {
            sendResponse(output, 403, "Forbidden", "text/plain", null); return
        }
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }

        val contentLength = parseHeader(headers, "Content-Length")?.toLongOrNull() ?: -1L

        try {
            if (contentLength >= 0) {
                FileOutputStream(file).use { fos ->
                    val buf = ByteArray(32768)
                    var remaining = contentLength
                    while (remaining > 0) {
                        val toRead = minOf(buf.size.toLong(), remaining).toInt()
                        val n = rawInput.read(buf, 0, toRead)
                        if (n < 0) {
                            throw IOException("Client disconnected prematurely after ${contentLength - remaining} bytes (expected $contentLength)")
                        }
                        fos.write(buf, 0, n)
                        remaining -= n
                    }
                }
            } else {
                FileOutputStream(file).use { fos ->
                    val buf = ByteArray(32768)
                    var total = 0L
                    while (true) {
                        val n = rawInput.read(buf)
                        if (n < 0) break
                        fos.write(buf, 0, n)
                        total += n
                    }
                    if (total == 0L) {
                        throw IOException("No data received")
                    }
                }
            }
            Log.i(TAG, "PUT saved: ${file.absolutePath} (${file.length()} bytes)")
            onUploadComplete(file.name, file.length())
            sendResponse(output, 201, "Created", "text/plain", null,
                extraHeaders = mapOf("Location" to path))
        } catch (e: Exception) {
            Log.e(TAG, "PUT error", e)
            sendResponse(output, 500, "Internal Server Error", "text/plain", null)
        }
    }

    private fun deriveEncKey(salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keySpec = PBEKeySpec(encPassword.toCharArray(), salt, 200_000, 256)
        return SecretKeySpec(factory.generateSecret(keySpec).encoded, "AES")
    }

    /**
     * 加密上传端点 POST /_encupload
     * v2 封装格式（脚本端 luna-send）：
     *   nameLen(4, 大端) || nameEnc(16 salt || 12 nonce || ct_name) || contentEnc(16 salt || 12 nonce || ct_content)
     *   文件名与内容均经 AES-256-GCM 加密，手机端解密后还原为原文件名落盘。
     * 兼容旧格式（无 nameLen 前缀）：整个 body 即 contentEnc，文件名取自明文 query/header。
     * 解密失败（口令错 / 被篡改）拒绝写入，防中间人替换。
     */
    /** 读取请求体为字节数组（按 Content-Length 精确读取；缺省读到 EOF）。 */
    private fun readBodyToBytes(contentLength: Long, rawInput: InputStream): ByteArray {
        return if (contentLength >= 0) {
            val buf = ByteArray(contentLength.toInt())
            var off = 0
            while (off < contentLength) {
                val n = rawInput.read(buf, off, (contentLength - off).toInt())
                if (n <= 0) break
                off += n
            }
            buf.copyOf(off)
        } else {
            val baos = ByteArrayOutputStream()
            val tmp = ByteArray(32768)
            while (true) {
                val n = rawInput.read(tmp)
                if (n < 0) break
                baos.write(tmp, 0, n)
            }
            baos.toByteArray()
        }
    }

    /**
     * 流式解析+解密一个 v2 封装 blob：来源为 [src]（长度 [totalLen]，仅用于 v2 判定），
     * 明文写入 [dst]。文件名(小, v2)内存解密后返回；旧格式(无 nameLen 前缀)返回 null。
     *
     * 关键：文件内容用 Cipher.update 分块喂入、doFinal 收尾校验 GCM tag，明文边解密边写盘，
     * 全程不把整段明文/密文同时驻留内存 —— 根治大文件(数十 MB+)解密时一次性 doFinal 分配整段
     * 明文缓冲导致的 OutOfMemoryError（实测 68MB 文件在 heap growth limit 402MB 下直接 OOM 闪退）。
     * 被 POST /_encupload（单包）与 POST /_encupload_chunk（分包合并后）共用。
     */
    private fun decryptEncBlobStream(
        src: InputStream,
        totalLen: Long,
        dst: OutputStream,
        onProgress: ((decryptedIn: Long, totalIn: Long) -> Unit)? = null
    ): String? {
        val head = ByteArray(ENC_HEADER_LEN + 44)
        readFully(src, head, 0, head.size)
        val nameLen = ((head[0].toInt() and 0xFF) shl 24) or
                      ((head[1].toInt() and 0xFF) shl 16) or
                      ((head[2].toInt() and 0xFF) shl 8) or
                      (head[3].toInt() and 0xFF)
        val isV2 = nameLen >= 44 && (ENC_HEADER_LEN + nameLen) <= totalLen
        val name: String?
        val contentSrc: InputStream
        var contentTotal = totalLen
        if (isV2) {
            // v2：nameLen(4) || nameEnc(16 salt || 12 nonce || ct_name) || contentEnc(...)
            val nameEnc = ByteArray(nameLen)
            System.arraycopy(head, ENC_HEADER_LEN, nameEnc, 0, 44)
            readFully(src, nameEnc, 44, nameLen - 44)
            val ns = nameEnc.copyOfRange(0, 16)
            val nnonce = nameEnc.copyOfRange(16, 28)
            val nct = nameEnc.copyOfRange(28, nameEnc.size)
            val nkey = deriveEncKey(ns)
            val ncipher = Cipher.getInstance("AES/GCM/NoPadding")
            ncipher.init(Cipher.DECRYPT_MODE, nkey, GCMParameterSpec(128, nnonce))
            name = String(ncipher.doFinal(nct), Charsets.UTF_8) // 文件名小，内存安全
            contentSrc = src // 已跳过 nameLen+nameEnc，src 现位于 contentEnc 起点
            contentTotal = totalLen - ENC_HEADER_LEN - nameLen
        } else {
            // 旧格式：整个 body 即 contentEnc（含已读的 head 前 48 字节），需把 head 回退拼接
            name = null
            contentSrc = SequenceInputStream(ByteArrayInputStream(head, 0, head.size), src)
        }
        contentSrc.use { cs ->
            val salt = ByteArray(16); readFully(cs, salt, 0, 16)
            val nonce = ByteArray(12); readFully(cs, nonce, 0, 12)
            val key = deriveEncKey(salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
            val buf = ByteArray(4 * 1024 * 1024) // 4MB 流式缓冲，内存恒定有界
            var readTotal = 0L
            while (true) {
                val n = cs.read(buf)
                if (n < 0) break
                readTotal += n
                val out = cipher.update(buf, 0, n)
                if (out != null && out.isNotEmpty()) dst.write(out)
                if (onProgress != null) onProgress(readTotal, contentTotal)
            }
            if (onProgress != null) onProgress(readTotal, contentTotal) // 收尾必报一次（100%）
            val fin = cipher.doFinal() // 校验 GCM tag，返回末块明文（仅一小块，内存有界）
            if (fin.isNotEmpty()) dst.write(fin)
        }
        return name
    }

    /** 从 [src] 精确读满 [len] 字节到 [buf] 的 [off, off+len)，不足则抛 IOException。 */
    private fun readFully(src: InputStream, buf: ByteArray, off: Int, len: Int) {
        var o = off
        val end = off + len
        while (o < end) {
            val n = src.read(buf, o, end - o)
            if (n < 0) throw IOException("数据不足(期望 $len 字节)")
            o += n
        }
    }

    /** 字节数人性化（进度日志用）：<1KB 原样，否则 KB/MB/GB 保留 1~2 位小数。 */
    private fun fmtSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }

    /**
     * 生成解密进度日志回调：2 秒节流写 [ENC] 进度日志（防大文件每 4MB 一条刷屏），
     * 读满（100%）那次必报。每条同时落 logcat（Log.d）与 UI 日志（addEncLog）。
     */
    private fun makeDecryptProgressLogger(): (Long, Long) -> Unit {
        var lastLogAt = 0L
        return { read, total ->
            val now = System.currentTimeMillis()
            if (read >= total || now - lastLogAt >= 2000) {
                lastLogAt = now
                val pct = if (total > 0) read * 100 / total else 100L
                Log.d(TAG, "Enc decrypt progress: $pct% (${fmtSize(read)}/${fmtSize(total)})")
                ShareStateHolder.addEncLog(shareId, "解密中: $pct% (${fmtSize(read)}/${fmtSize(total)})")
            }
        }
    }

    /**
     * 加密上传端点 POST /_encupload（单包兼容路径，复用 [decryptEncBlobStream]）。
     * v2 封装格式（脚本端 luna-send）：nameLen(4)||nameEnc||contentEnc，文件名与内容均加密。
     * 兼容旧格式（无 nameLen 前缀）：整个 body 即 contentEnc，文件名取自明文 query/header。
     */
    private fun handleEncUpload(output: OutputStream, rawPath: String, headers: List<String>, rawInput: InputStream) {
        val t0 = System.currentTimeMillis()
        if (encPassword.isBlank()) {
            ShareStateHolder.addEncLog(shareId, "上传被拒绝: 该共享未配置加密口令")
            sendResponse(output, 403, "Forbidden", "application/json; charset=utf-8",
                """{"ok":false,"error":"该共享未配置加密口令"}""")
            return
        }
        val bodyBytes = readBodyToBytes(parseHeader(headers, "Content-Length")?.toLongOrNull() ?: -1L, rawInput)
        ShareStateHolder.addEncLog(shareId, "传输中: 已接收密文 ${bodyBytes.size} B，正在解密...")
        try {
            // 流式解密：明文边解边写盘，避免大文件一次性 doFinal 分配整段明文导致 OOM
            val base = File(File(rootDir), ENC_TMP_DIR_NAME); base.mkdirs()
            val plainTmp = File(base, "single_${System.nanoTime()}.plain.tmp")
            val rawName = decryptEncBlobStream(
                ByteArrayInputStream(bodyBytes), bodyBytes.size.toLong(), plainTmp.outputStream(),
                makeDecryptProgressLogger()
            )
            val fallback = if (rawName.isNullOrBlank())
                parseQueryString(rawPath.substringAfter('?', ""))["name"] ?: parseHeader(headers, "X-Luna-Name")
            else rawName
            if (fallback.isNullOrBlank()) {
                plainTmp.delete()
                sendResponse(output, 400, "Bad Request", "application/json; charset=utf-8",
                    """{"ok":false,"error":"缺少文件名"}""")
                return
            }
            val safeName = fallback.replace(Regex("""[\\/]"""), "_").take(200)
            val outFile = resolvePath("/$safeName") ?: run {
                plainTmp.delete()
                sendResponse(output, 403, "Forbidden", "application/json; charset=utf-8",
                    """{"ok":false,"error":"非法路径"}""")
                return
            }
            outFile.parentFile?.mkdirs()
            if (!plainTmp.renameTo(outFile)) {
                // 跨文件系统兜底：复制后删除临时文件
                plainTmp.inputStream().buffered(1 shl 20).use { `in` ->
                    FileOutputStream(outFile).use { fos -> `in`.copyTo(fos) }
                }
                plainTmp.delete()
            }
            val finalSize = outFile.length()
            Log.i(TAG, "Encrypted upload decrypted & saved: ${outFile.absolutePath} (${finalSize} bytes)")
            onUploadComplete(safeName, finalSize)
            val cost = System.currentTimeMillis() - t0
            ShareStateHolder.addEncLog(shareId, "传输完成: 「$safeName」 明文 ${finalSize} B / 密文 ${bodyBytes.size} B (耗时 ${cost}ms)")
            sendResponse(output, 201, "Created", "application/json; charset=utf-8",
                """{"ok":true}""")
        } catch (e: Exception) {
            Log.e(TAG, "Enc upload failed", e)
            ShareStateHolder.addEncLog(shareId, "解密/写入失败: ${e.message}")
            sendResponse(output, 400, "Bad Request", "application/json; charset=utf-8",
                """{"ok":false,"error":"解密失败：${e.message}"}""")
        }
    }

    /**
     * 分包加密上传端点 POST /_encupload_chunk
     * 客户端把整文件加密后的 v2 blob 切成多包，每包独立 POST（带 X-Luna-Session / Index / Total），
     * 单包失败可独立重试，避免大文件经内网穿透隧道时单条大请求超时。
     * 服务端按会话把各包落为 <session>/<index>.part，收齐后按序合并成完整 blob，
     * 复用 [decryptEncBlobStream] 还原文件名+内容落盘（分包必须走 v2 封装，文件名在 blob 内、不随请求暴露）。
     */
    private fun handleEncUploadChunk(output: OutputStream, rawPath: String, headers: List<String>, rawInput: InputStream) {
        cleanupStaleEncChunks()
        if (encPassword.isBlank()) {
            sendResponse(output, 403, "Forbidden", "application/json; charset=utf-8",
                """{"ok":false,"error":"该共享未配置加密口令"}""")
            return
        }
        val session = parseHeader(headers, "X-Luna-Session")
        val index = parseHeader(headers, "X-Luna-Chunk-Index")?.toIntOrNull()
        val total = parseHeader(headers, "X-Luna-Chunk-Total")?.toIntOrNull()
        if (session.isNullOrBlank() || index == null || total == null || index < 0 || total <= 0 || index >= total) {
            sendResponse(output, 400, "Bad Request", "application/json; charset=utf-8",
                """{"ok":false,"error":"非法分包参数(需 X-Luna-Session/Index/Total)"}""")
            return
        }
        val chunk = readBodyToBytes(parseHeader(headers, "Content-Length")?.toLongOrNull() ?: -1L, rawInput)
        if (chunk.isEmpty()) {
            sendResponse(output, 400, "Bad Request", "application/json; charset=utf-8",
                """{"ok":false,"error":"空分包"}""")
            return
        }

        val s = encChunks.computeIfAbsent(session) {
            val base = File(File(rootDir), ENC_TMP_DIR_NAME).apply { mkdirs() }
            val sd = File(base, session).apply { mkdirs() }
            EncChunkSession(session, total, sd, BooleanArray(total), System.currentTimeMillis(), System.currentTimeMillis())
        }
        if (s.total != total) {
            sendResponse(output, 409, "Conflict", "application/json; charset=utf-8",
                """{"ok":false,"error":"分包总数($total)与会话已存在的 ${s.total} 不一致"}""")
            return
        }

        synchronized(s) {
            if (s.finalizeHandedOff) {
                // 已由"写入最后一个分包"的那次请求接管完成（合并+解密+落盘），
                // 其它并发请求一律回 202，绝不参与合并 —— 否则合并者会读到正在被覆盖的 .part，
                // 拼出错位 blob 导致 GCM 校验 BAD_DECRYPT（实测重放缓存的 form 请求即可触发）。
                val idx = s.received.withIndex().filter { it.value }.map { it.index }
                sendResponse(output, 202, "Accepted", "application/json; charset=utf-8",
                    """{"ok":true,"received":${idx.size},"total":${s.total},"pending":${s.total - idx.size},"received_idx":${idx.joinToString(",", "[", "]")}}""")
                return
            }
            try {
                File(s.dir, "$index.part").writeBytes(chunk)
            } catch (e: Exception) {
                sendResponse(output, 500, "Internal Server Error", "application/json; charset=utf-8",
                    """{"ok":false,"error":"写分包失败：${e.message}"}""")
                return
            }
            s.received[index] = true
            s.lastActiveAt = System.currentTimeMillis()
            s.receivedBytes += chunk.size
            val got = s.received.count { it }
            // 接收进度日志：2 秒节流防刷屏；收齐那包必报（接管合并前给出完整状态）
            val nowMs = System.currentTimeMillis()
            if (got >= s.total || nowMs - s.lastProgressLogAt >= 2000) {
                s.lastProgressLogAt = nowMs
                Log.d(TAG, "Enc chunk progress: $got/${s.total} chunks, ${s.receivedBytes} B received")
                if (got >= s.total) {
                    ShareStateHolder.addEncLog(shareId, "分包收齐: ${s.total}/${s.total} 包 共 ${fmtSize(s.receivedBytes)}，开始合并解密...")
                } else {
                    ShareStateHolder.addEncLog(shareId, "分包接收中: $got/${s.total} 包 (已收 ${fmtSize(s.receivedBytes)})")
                }
            }
            if (got >= s.total) {
                s.finalizeHandedOff = true
                // 合并各分包 → 完整 blob → 解密 → 落盘
                try {
                    // 1) 流式合并各分包为单个 blob 文件（落盘，不进内存）—— 避免 ByteArrayOutputStream 把整 blob 撑进堆
                    val base = File(File(rootDir), ENC_TMP_DIR_NAME)
                    base.mkdirs()
                    val blobFile = File(base, "$session.bin")
                    val blobLen: Long
                    FileOutputStream(blobFile).use { fos ->
                        val cbuf = ByteArray(1 shl 20)
                        for (i in 0 until s.total) {
                            val pf = File(s.dir, "$i.part")
                            if (!pf.exists()) throw IOException("缺失分包 #$i")
                            pf.inputStream().use { `in` ->
                                var r: Int
                                while (`in`.read(cbuf).also { r = it } >= 0) fos.write(cbuf, 0, r)
                            }
                        }
                    }
                    blobLen = blobFile.length()
                    // 2) 分包 .part 已并入 blobFile，清理会话临时目录（失败也要移除会话）
                    s.dir.deleteRecursively()
                    encChunks.remove(session)
                    // 3) 流式解密 blob：明文边解边写盘，内存恒定在数 MB（根治大文件解密 OOM）
                    val plainTmp = File(base, "$session.plain.tmp")
                    val rawName = decryptEncBlobStream(
                        blobFile.inputStream().buffered(1 shl 20), blobLen, plainTmp.outputStream(),
                        makeDecryptProgressLogger()
                    )
                    blobFile.delete()
                    if (rawName.isNullOrBlank()) {
                        plainTmp.delete()
                        throw IllegalArgumentException("分包缺少文件名(须用 v2 封装)")
                    }
                    val safeName = rawName.replace(Regex("""[\\/]"""), "_").take(200)
                    val outFile = resolvePath("/$safeName") ?: throw IllegalArgumentException("非法路径")
                    outFile.parentFile?.mkdirs()
                    if (!plainTmp.renameTo(outFile)) {
                        // 跨文件系统兜底：复制后删除临时文件
                        plainTmp.inputStream().buffered(1 shl 20).use { `in` ->
                            FileOutputStream(outFile).use { fos -> `in`.copyTo(fos) }
                        }
                        plainTmp.delete()
                    }
                    val finalSize = outFile.length()
                    onUploadComplete(safeName, finalSize)
                    encFinished[session] = System.currentTimeMillis() // 供断点续传查询「已传完」
                    ShareStateHolder.addEncLog(shareId, "分包传输完成: 「$safeName」 明文 ${finalSize} B / 密文 ${blobLen} B")
                    sendResponse(output, 201, "Created", "application/json; charset=utf-8",
                        """{"ok":true}""")
                } catch (e: Exception) {
                    Log.e(TAG, "Enc chunk finalize failed", e)
                    s.dir.deleteRecursively()
                    encChunks.remove(session)
                    ShareStateHolder.addEncLog(shareId, "分包解密失败: ${e.message}")
                    sendResponse(output, 400, "Bad Request", "application/json; charset=utf-8",
                        """{"ok":false,"error":"解密失败：${e.message}"}""")
                }
                return
            }
            val pending = s.total - got
            val idx = s.received.withIndex().filter { it.value }.map { it.index }
            sendResponse(output, 202, "Accepted", "application/json; charset=utf-8",
                """{"ok":true,"received":$got,"total":${s.total},"pending":$pending,"received_idx":${idx.joinToString(",", "[", "]")}}""")
        }
    }

    /**
     * 分包上传会话状态查询 POST /_encstatus（断点续传用，脚本端 luna-send 调用）。
     * 请求头 X-Luna-Session 指定会话；响应只含包计数与序号，不含文件名等明文信息：
     *   {"done":true}                                   —— 会话已收齐并解密落盘（24h 内可查）
     *   {"done":false,"received":n,"total":t,"received_idx":[...]} —— 会话进行中，已收到的分包序号
     *   {"unknown":true}                                —— 会话不存在（从未开始 / 已过期被清理）
     */
    private fun handleEncStatus(output: OutputStream, headers: List<String>) {
        if (encPassword.isBlank()) {
            sendResponse(output, 403, "Forbidden", "application/json; charset=utf-8",
                """{"ok":false,"error":"该共享未配置加密口令"}""")
            return
        }
        cleanupStaleEncChunks()
        val session = parseHeader(headers, "X-Luna-Session")
        if (session.isNullOrBlank()) {
            sendResponse(output, 400, "Bad Request", "application/json; charset=utf-8",
                """{"ok":false,"error":"缺少 X-Luna-Session"}""")
            return
        }
        if (encFinished.containsKey(session)) {
            sendResponse(output, 200, "OK", "application/json; charset=utf-8", """{"done":true}""")
            return
        }
        val s = encChunks[session] ?: run {
            sendResponse(output, 200, "OK", "application/json; charset=utf-8", """{"unknown":true}""")
            return
        }
        synchronized(s) {
            val idx = s.received.withIndex().filter { it.value }.map { it.index }
            sendResponse(output, 200, "OK", "application/json; charset=utf-8",
                """{"done":false,"received":${idx.size},"total":${s.total},"received_idx":${idx.joinToString(",", "[", "]")}}""")
        }
    }

    /** 清理超过保留窗口（24h）无活动的分包会话临时目录与完成记录（防客户端中断遗留垃圾；窗口内支持断点续传）。 */
    private fun cleanupStaleEncChunks() {
        val now = System.currentTimeMillis()
        val it = encChunks.entries.iterator()
        while (it.hasNext()) {
            val (sid, s) = it.next()
            if (now - s.lastActiveAt > ENC_SESSION_RETENTION_MS) {
                s.dir.deleteRecursively()
                it.remove()
            }
        }
        encFinished.entries.removeAll { now - it.value > ENC_SESSION_RETENTION_MS }
    }

    private fun handleDelete(output: OutputStream, path: String) {
        val file = resolvePath(path) ?: run {
            sendResponse(output, 403, "Forbidden", "text/plain", null); return
        }
        if (!file.exists()) {
            sendResponse(output, 404, "Not Found", "text/plain", null); return
        }
        if (file.isDirectory) file.deleteRecursively() else file.delete()
        sendResponse(output, 204, "No Content", "text/plain", null)
    }

    private fun handleMkcol(output: OutputStream, path: String) {
        val dir = resolvePath(path) ?: run {
            sendResponse(output, 403, "Forbidden", "text/plain", null); return
        }
        if (dir.exists()) {
            sendResponse(output, 405, "Method Not Allowed", "text/plain", null); return
        }
        if (dir.mkdirs()) sendResponse(output, 201, "Created", "text/plain", null)
        else sendResponse(output, 500, "Internal Server Error", "text/plain", null)
    }

    private fun handleCopyMove(output: OutputStream, path: String, headers: List<String>, copy: Boolean) {
        val src = resolvePath(path) ?: run {
            sendResponse(output, 404, "Not Found", "text/plain", null); return
        }
        if (!src.exists()) {
            sendResponse(output, 404, "Not Found", "text/plain", null); return
        }

        val destHeader = parseHeader(headers, "Destination") ?: run {
            sendResponse(output, 400, "Bad Request", "text/plain", null); return
        }
        val destPath = try {
            val uri = java.net.URI(destHeader)
            URLDecoder.decode(uri.path ?: destHeader, "UTF-8")
        } catch (_: Exception) {
            try { URLDecoder.decode(destHeader, "UTF-8") } catch (_: Exception) { destHeader }
        }
        val dest = resolvePath(destPath) ?: run {
            sendResponse(output, 403, "Forbidden", "text/plain", null); return
        }
        if (dest.exists()) {
            sendResponse(output, 412, "Precondition Failed", "text/plain", null); return
        }
        dest.parentFile?.let { if (!it.exists()) it.mkdirs() }

        try {
            if (copy) src.copyRecursively(dest, overwrite = false)
            else src.renameTo(dest)
            sendResponse(output, 201, "Created", "text/plain", null,
                extraHeaders = mapOf("Location" to destPath))
        } catch (e: Exception) {
            Log.e(TAG, "COPY/MOVE error", e)
            sendResponse(output, 500, "Internal Server Error", "text/plain", null)
        }
    }

    private fun handleLock(output: OutputStream) {
        val token = UUID.randomUUID().toString()
        val body = """<?xml version="1.0" encoding="utf-8"?>
<D:prop xmlns:D="DAV:"><D:lockdiscovery><D:activelock>
<D:locktype><D:write/></D:locktype>
<D:lockscope><D:exclusive/></D:lockscope>
<D:depth>infinity</D:depth>
<D:locktoken><D:href>urn:uuid:$token</D:href></D:locktoken>
</D:activelock></D:lockdiscovery></D:prop>"""
        sendResponse(output, 200, "OK", "application/xml; charset=utf-8", body,
            extraHeaders = mapOf("Lock-Token" to "<urn:uuid:$token>"))
    }

    private fun handleUnlock(output: OutputStream) {
        sendResponse(output, 204, "No Content", "text/plain", null)
    }

    // ── PROPFIND ──

    private fun handlePropfind(output: OutputStream, path: String, headers: List<String>) {
        val file = resolvePath(path) ?: run {
            sendResponse(output, 404, "Not Found", "text/plain", null); return
        }
        if (!file.exists()) {
            sendResponse(output, 404, "Not Found", "text/plain", null); return
        }

        val depth = parseHeader(headers, "Depth") ?: "1"
        val isDeep = depth == "infinity" || depth == "1"
        val df = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        df.timeZone = TimeZone.getTimeZone("GMT")

        val xml = buildString {
            appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
            appendLine("""<D:multistatus xmlns:D="DAV:">""")
            appendPropXml(path, file, df)
            if (file.isDirectory && isDeep) {
                val entries = file.listFiles()
                    ?.filter { !it.name.startsWith(".") }
                    ?.sortedBy { it.name.lowercase() } ?: emptyList()
                for (entry in entries) {
                    val childPath = if (path.endsWith("/")) "$path${entry.name}" else "$path/${entry.name}"
                    appendPropXml(if (entry.isDirectory) "$childPath/" else childPath, entry, df)
                }
            }
            appendLine("""</D:multistatus>""")
        }

        sendResponse(output, 207, "Multi-Status", "application/xml; charset=utf-8", xml)
    }

    private fun StringBuilder.appendPropXml(path: String, file: File, df: SimpleDateFormat) {
        val displayName = file.name.ifEmpty { rootDir.substringAfterLast("/").ifEmpty { "root" } }
        val lastMod = df.format(Date(file.lastModified()))
        val contentType = if (file.isDirectory) "httpd/unix-directory" else getMimeType(file.extension)
        val contentLen = if (file.isFile) file.length() else 0L
        val resourceType = if (file.isDirectory) "<D:collection/>" else ""

        appendLine("  <D:response>")
        appendLine("    <D:href>${xmlEscape(encodeHref(path))}</D:href>")
        appendLine("    <D:propstat>")
        appendLine("      <D:prop>")
        appendLine("        <D:displayname>${xmlEscape(displayName)}</D:displayname>")
        appendLine("        <D:getcontenttype>$contentType</D:getcontenttype>")
        appendLine("        <D:getcontentlength>$contentLen</D:getcontentlength>")
        appendLine("        <D:getlastmodified>$lastMod</D:getlastmodified>")
        appendLine("        <D:resourcetype>$resourceType</D:resourcetype>")
        appendLine("        <D:getetag>\"${file.lastModified()}-${file.length()}\"</D:getetag>")
        appendLine("      </D:prop>")
        appendLine("      <D:status>HTTP/1.1 200 OK</D:status>")
        appendLine("    </D:propstat>")
        appendLine("  </D:response>")
    }

    /** Escape a string for safe inclusion in XML element content. */
    private fun xmlEscape(s: String): String = buildString(s.length + 8) {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }

    /** Percent-encode each path segment (keep '/' separators) so non-ASCII /
     *  space filenames survive strict WebDAV clients. WinSCP / Windows WebDAV
     *  silently drop entries whose <D:href> contains raw UTF-8 or spaces. */
    private fun encodeHref(path: String): String {
        if (path.isEmpty()) return path
        return path.split("/").joinToString("/") { seg ->
            if (seg.isEmpty()) seg else java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }
    }

    // ── FTP handler ──

    private fun handleFtp(socket: Socket, rawInput: InputStream, firstCmd: String?) {
        val rootFile = if (rootDir.isBlank()) File(".") else File(rootDir)
        FtpHandler(socket, rawInput, firstCmd, rootFile, username, password).handle()
    }

    // ── Path resolution ──

    private fun resolvePath(requestPath: String): File? {
        val root = File(rootDir)
        if (rootDir.isBlank()) return root
        val cleanPath = requestPath.removePrefix("/")
        // 禁止访问分包临时目录，避免密文分包被 GET 暴露
        if (cleanPath.split("/").any { it == ENC_TMP_DIR_NAME }) return null
        val resolved = File(root, cleanPath).normalize()
        return if (resolved.normalize().absolutePath.startsWith(root.normalize().absolutePath)) resolved else null
    }

    // ── HTTP Basic Auth ──

    private fun checkAuth(headers: List<String>): Boolean {
        for (line in headers) {
            if (line.startsWith("Authorization:", ignoreCase = true)) {
                val value = line.substringAfter(":").trim()
                if (!value.startsWith("Basic ", ignoreCase = true)) return false
                val decoded = try {
                    String(Base64.getDecoder().decode(value.substringAfter("Basic ", "")), Charsets.UTF_8)
                } catch (_: Exception) { return false }
                val colonIdx = decoded.indexOf(':')
                if (colonIdx < 0) return false
                return decoded.substring(0, colonIdx) == username && decoded.substring(colonIdx + 1) == password
            }
        }
        return false
    }

    // ── Session-based auth (theme login page) ──

    private fun hasValidSession(headers: List<String>, urlSid: String? = null): Boolean {
        // 1) Cookie（桌面浏览器 / WebDAV 客户端）
        val cookie = parseHeader(headers, "Cookie")
        if (cookie != null) {
            val m = Regex("luna_session=([^;\\s]+)").find(cookie)
            if (m != null) {
                val sid = m.groupValues[1]
                val exp = sessions[sid]
                if (exp != null) {
                    if (System.currentTimeMillis() > exp) { sessions.remove(sid); return false }
                    return true
                }
            }
        }
        // 2) URL token ?_s=（移动端浏览器在 fetch 设置的 SameSite=Lax cookie
        //    不会随 location.href 导航发送，故用 URL token 兜底）
        if (!urlSid.isNullOrBlank()) {
            val exp = sessions[urlSid] ?: return false
            if (System.currentTimeMillis() > exp) { sessions.remove(urlSid); return false }
            return true
        }
        return false
    }

    private fun parseQueryString(q: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (q.isBlank()) return map
        q.split('&').forEach { pair ->
            if (pair.isBlank()) return@forEach
            val eq = pair.indexOf('=')
            if (eq > 0) map[pair.substring(0, eq)] = pair.substring(eq + 1)
        }
        return map
    }

    private fun handleLogin(output: OutputStream, headers: List<String>, rawInput: InputStream) {
        val len = parseHeader(headers, "Content-Length")?.toIntOrNull() ?: 0
        val body = if (len > 0) {
            val buf = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = rawInput.read(buf, off, len - off)
                if (n <= 0) break
                off += n
            }
            String(buf, 0, off, Charsets.UTF_8)
        } else ""
        val user = extractLoginField(body, "user")
        val pass = extractLoginField(body, "pass")
        if (user == username && pass == password) {
            val sid = UUID.randomUUID().toString()
            sessions[sid] = System.currentTimeMillis() + SESSION_TTL_MS
            sendResponse(output, 200, "OK", "application/json; charset=utf-8",
                """{"ok":true,"sid":"$sid"}""",
                extraHeaders = mapOf("Set-Cookie" to
                    "luna_session=$sid; Path=/; HttpOnly; SameSite=Lax; Max-Age=${SESSION_TTL_MS / 1000}"))
        } else {
            sendResponse(output, 401, "Unauthorized", "application/json; charset=utf-8",
                """{"ok":false,"error":"用户名或密码错误"}""")
        }
    }

    private fun extractLoginField(body: String, key: String): String {
        Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)?.let {
            return it.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
        }
        Regex("$key=([^&]*)").find(body)?.let {
            return java.net.URLDecoder.decode(it.groupValues[1], "UTF-8")
        }
        return ""
    }

    private fun parseHeader(headers: List<String>, name: String): String? {
        for (line in headers) {
            if (line.startsWith("$name:", ignoreCase = true)) {
                return line.substringAfter(":").trim()
            }
        }
        return null
    }

    // ── Response helpers ──

    private fun serveFile(output: OutputStream, file: File) {
        sendResponse(output, 200, "OK", getMimeType(file.extension), null,
            contentLength = file.length(),
            bodyWriter = { out ->
                FileInputStream(file).use { fis ->
                    val buf = ByteArray(32768)
                    while (true) {
                        val n = fis.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        out.flush()
                    }
                }
            })
    }

    /** Build a directory listing DTO list shared by HTML and JSON rendering. */
    private fun buildListing(path: String, dir: File): Pair<String?, List<WebUi.EntryDto>> {
        val entries = dir.listFiles()?.filter { !it.name.startsWith(".") } ?: emptyList()
        val parent = if (path == "/") null
            else path.removeSuffix("/").substringBeforeLast("/").ifEmpty { "/" }
        val dtos = entries.map {
            WebUi.EntryDto(
                name = it.name,
                size = if (it.isFile) it.length() else 0L,
                mtime = it.lastModified(),
                dir = it.isDirectory
            )
        }.sortedBy { !it.dir }
        return parent to dtos
    }

    /** JSON listing for the web UI (Accept: application/json). */
    private fun serveDirectoryJson(output: OutputStream, path: String, dir: File) {
        val (parent, dtos) = buildListing(path, dir)
        val body = WebUi.buildListingJson(path, parent, dtos)
        sendResponse(output, 200, "OK", "application/json; charset=utf-8", body)
    }

    /** Stream the whole folder as a ZIP archive (?zip=1). */
    private fun serveDirectoryZip(output: OutputStream, dir: File) {
        val zipName = (dir.name.ifEmpty { "share" }) + ".zip"
        Log.i(TAG, "ZIP download: ${dir.absolutePath}")
        sendResponse(
            output, 200, "OK", "application/zip",
            null, // unknown length — streamed until EOF (Connection: close)
            extraHeaders = mapOf("Content-Disposition" to "attachment; filename=\"$zipName\"")
        ) { out ->
            ZipOutputStream(BufferedOutputStream(out)).use { zos ->
                val entries = dir.walkTopDown().filter { !it.name.startsWith(".") }.toList()
                for (f in entries) {
                    val rel = f.relativeTo(dir).path.replace(File.separatorChar, '/')
                    if (rel.isEmpty()) continue // skip the root entry itself
                    if (f.isDirectory) {
                        zos.putNextEntry(ZipEntry("$rel/"))
                        zos.closeEntry()
                    } else {
                        zos.putNextEntry(ZipEntry(rel))
                        FileInputStream(f).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
        }
    }

    private fun serveDirectoryListing(output: OutputStream, path: String, dir: File) {
        val (parent, dtos) = buildListing(path, dir)
        val data = WebUi.buildListingJson(path, parent, dtos)
        val html = WebUi.pageTemplate().replace("%DATA%", data)
        sendResponse(output, 200, "OK", "text/html; charset=utf-8", html)
    }

    private fun sendResponse(
        output: OutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: String?,
        contentLength: Long? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        bodyWriter: ((OutputStream) -> Unit)? = null
    ) {
        val bodyBytes = if (body != null) body.toByteArray(Charsets.UTF_8) else null
        val streamed = bodyWriter != null && contentLength == null
        val actualLength = contentLength ?: (bodyBytes?.size?.toLong() ?: 0)

        val header = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: $contentType\r\n")
            if (!streamed) append("Content-Length: $actualLength\r\n")
            append("Connection: close\r\n")
            for ((k, v) in extraHeaders) append("$k: $v\r\n")
            append("\r\n")
        }

        val headerBytes = header.toByteArray(Charsets.US_ASCII)
        Log.d(TAG, "RESP: HTTP/1.1 $statusCode $statusText, Content-Type: $contentType")
        output.write(headerBytes)
        if (bodyBytes != null) output.write(bodyBytes)
        bodyWriter?.invoke(output)
        output.flush()
    }

    private fun getMimeType(ext: String): String = when (ext.lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js" -> "application/javascript"
        "json" -> "application/json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        "txt" -> "text/plain; charset=utf-8"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "gz" -> "application/gzip"
        "tar" -> "application/x-tar"
        "mp3" -> "audio/mpeg"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "wasm" -> "application/wasm"
        else -> "application/octet-stream"
    }
}
