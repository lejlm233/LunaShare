package com.lunashare.app.frpc.mefrp

import android.content.Context
import android.util.Log
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/**
 * 本地 HTTP 反向代理（仅监听 127.0.0.1），专治 mefrpc 硬编码 8.8.8.8 被墙。
 *
 * 根因：mefrpc（Go CGO_ENABLED=0）不读系统 DNS、也不读 env 代理（Go net/http 默认
 * client 不应用 HTTPS_PROXY），只读不存在的 /etc/resolv.conf，回退硬编码 8.8.8.8:53
 * → api.mefrp.com 解析超时，控制面起不来。本地 CONNECT 代理方案同样无效（mefrpc 不
 * 发 CONNECT、且不读 env proxy）。
 *
 * 方案：App 内起一个本地 HTTP 服务器；启动 mefrpc 时注入
 * `--api-root-url http://127.0.0.1:<port>`（配合 --skip-cert-verify）。mefrpc 控制面
 * 请求连 127.0.0.1（IP，无需 DNS 解析），本代理收到后用 Android 系统 DNS 解析
 * api.mefrp.com 并真实连 https://api.mefrp.com<path>（Host/SNI=api.mefrp.com，CDN 友好），
 * 把响应回传。这样绕开 8.8.8.8，又不被 CDN 因 Host=IP 拒绝。
 *
 * 节点连接（TCP/TLS，由服务端下发的 IP）不走本代理，仍由 mefrpc 直连；本代理只承载
 * 控制面 API（easyStartup / getProxyConfig 等）。每次转发都写 proxy_gateway.log，便于
 * 排查 mefrpc 是否真的经本代理（应有 `FWD-OK ... -> 200` 记录）。
 */
object MefrpProxyGateway {
    private const val TAG = "MefrpProxyGateway"
    private const val TARGET_HOST = "api.mefrp.com"
    private val executor = Executors.newCachedThreadPool()
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var localPort: Int = 0
    @Volatile private var logFile: File? = null
    private val lock = Any()

    /** 确保代理已启动，返回监听端口（幂等）。仅在 127.0.0.1 监听，外部不可达。 */
    fun ensureStarted(context: Context): Int = synchronized(lock) {
        if (serverSocket != null) return localPort
        logFile = File(context.filesDir, "frpc/proxy_gateway.log")
        val ss = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        localPort = ss.localPort
        serverSocket = ss
        Log.i(TAG, "本地 mefrp 反向代理已启动 127.0.0.1:$localPort（系统 DNS 解析 api.mefrp.com）")
        appendLog("GATEWAY START 127.0.0.1:$localPort")
        executor.execute { acceptLoop(ss) }
        return localPort
    }

    /** 停止代理（一般在 App 退出 / 全部 mefrp 隧道关闭后调用）。 */
    fun stop() = synchronized(lock) {
        try { serverSocket?.close() } catch (_: Exception) { /* ignore */ }
        serverSocket = null
        localPort = 0
        Log.i(TAG, "本地 mefrp 反向代理已停止")
    }

    private fun appendLog(line: String) {
        try {
            val f = logFile ?: return
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            f.appendText("[$ts] $line\n")
        } catch (_: Exception) { /* ignore */ }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (true) {
            val client = try { ss.accept() } catch (_: SocketException) { break }
            executor.execute { handle(client) }
        }
    }

    /**
     * 处理一个 mefrpc 控制面请求：读请求行 + 头 + body（逐字节读头，避免 BufferedReader
     * 预读 body），用系统 DNS 真实连 https://api.mefrp.com<path> 转发，回写响应。
     */
    private fun handle(client: Socket) {
        try {
            val inp = client.getInputStream()
            val out = client.getOutputStream()
            val requestLine = readLine(inp) ?: run { client.close(); return }
            val parts = requestLine.split(" ")
            if (parts.size < 2) { client.close(); return }
            val method = parts[0]
            val path = parts[1]
            // 读请求头
            val headers = mutableMapOf<String, String>()
            while (true) {
                val h = readLine(inp) ?: break
                if (h.isEmpty()) break
                val idx = h.indexOf(':')
                if (idx > 0) headers[h.substring(0, idx).trim().lowercase()] = h.substring(idx + 1).trim()
            }
            // 读 body（按 Content-Length）
            val body = headers["content-length"]?.toIntOrNull()?.let { len ->
                if (len > 0) {
                    val buf = ByteArray(len)
                    var off = 0
                    while (off < len) {
                        val n = inp.read(buf, off, len - off)
                        if (n < 0) break
                        off += n
                    }
                    buf.copyOf(off)
                } else null
            }

            appendLog("FWD $method $path")
            val url = "https://$TARGET_HOST$path"
            val conn = try {
                (URL(url).openConnection() as HttpsURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 20000
                    readTimeout = 20000
                    doInput = true
                    for ((k, v) in headers) {
                        if (k !in setOf("host", "content-length", "connection", "proxy-connection", "accept-encoding"))
                            setRequestProperty(k, v)
                    }
                    if (body != null) {
                        doOutput = true
                        outputStream.write(body)
                    }
                }
            } catch (e: Exception) {
                appendLog("FWD-FAIL $method $path : ${e.message}")
                out.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                out.flush(); client.close(); return
            }

            val code = runCatching { conn.responseCode }.getOrElse {
                appendLog("FWD-FAIL $method $path : ${it.message}")
                out.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                out.flush(); client.close(); return
            }
            val respBody = runCatching { conn.inputStream.buffered().readBytes() }.getOrElse {
                runCatching { conn.errorStream?.buffered()?.readBytes() ?: ByteArray(0) }.getOrElse { ByteArray(0) }
            }
            out.write("HTTP/1.1 $code ${statusText(code)}\r\n".toByteArray())
            out.write("Content-Length: ${respBody.size}\r\n".toByteArray())
            out.write("Connection: close\r\n\r\n".toByteArray())
            if (respBody.isNotEmpty()) out.write(respBody)
            out.flush()
            appendLog("FWD-OK $method $path -> $code (${respBody.size}B)")
            runCatching { client.close() }
        } catch (e: Exception) {
            Log.w(TAG, "代理异常: ${e.message}")
            runCatching { client.close() }
        }
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        201 -> "Created"
        202 -> "Accepted"
        204 -> "No Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        422 -> "Unprocessable Entity"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> "Status $code"
    }

    /** 逐字节读一行（无缓冲，避免超前消费 body），空行返回空字符串。 */
    private fun readLine(`in`: java.io.InputStream): String? {
        val sb = StringBuilder()
        var c: Int
        while (`in`.read().also { c = it } != -1) {
            if (c == '\r'.code) continue
            if (c == '\n'.code) break
            sb.append(c.toChar())
            if (sb.length > 8192) break
        }
        return if (sb.isEmpty() && c == -1) null else sb.toString()
    }
}
