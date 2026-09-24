package com.lunashare.app.frpc.mefrp

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 本地 HTTP CONNECT 代理网关（仅监听 127.0.0.1）。
 *
 * 根因：mefrpc 是纯 Go 静态二进制（CGO_ENABLED=0），不读取 Android 系统 DNS，
 * 只硬编码 8.8.8.8:53 做域名解析，而 8.8.8.8 在国行网络下被拦截，导致
 * `lookup api.mefrp.com ... 8.8.8.8:53: i/o timeout`。此外部分手机网络把
 * api.mefrp.com 解析成 IPv6，而手机无 IPv6 → `dial tcp [2409:...]:443: network
 * is unreachable`。两种情况下隧道都起不来，UI 却可能显示「成功」（假象）。
 *
 * 方案：在 App 进程内起一个本地 CONNECT 代理，对 CONNECT 目标用 Android 系统 DNS
 * （InetAddress.getByName，走手机当前网络 DNS）解析，且**强制只取 IPv4 A 记录**
 * （过滤掉 IPv6），再建立 TCP 隧道转发。启动 mefrpc 时注入
 * HTTPS_PROXY=http://127.0.0.1:<port>，使其所有 https 请求经此代理。这样 mefrpc
 * 发出的 Host/SNI 仍是 api.mefrp.com（代理只做 TCP 隧道、不碰 TLS），既绕过 8.8.8.8
 * 与 IPv6 unreachable，又不被 CDN 因 Host=IP 拒绝，且不改动系统 / WiFi 的 DNS 设置。
 *
 * 每次 CONNECT 都会把 host / 解析到的 IPv4 / 成败写进 files/frpc/proxy_gateway.log，
 * 便于排查 mefrpc 到底有没有走代理（若文件里没有任何 api.mefrp.com 的 CONNECT 记录，
 * 说明 mefrpc 根本不读 HTTPS_PROXY，需改用 patch 二进制 DNS 的方案）。
 */
object MefrpProxyGateway {
    private const val TAG = "MefrpProxyGateway"
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
        Log.i(TAG, "本地代理已启动 127.0.0.1:$localPort（系统 DNS 强制 IPv4，绕过 8.8.8.8/IPv6）")
        appendLog("GATEWAY START 127.0.0.1:$localPort")
        executor.execute { acceptLoop(ss) }
        return localPort
    }

    /** 停止代理（一般在 App 退出 / 全部 mefrp 隧道关闭后调用）。 */
    fun stop() = synchronized(lock) {
        try { serverSocket?.close() } catch (_: Exception) { /* ignore */ }
        serverSocket = null
        localPort = 0
        Log.i(TAG, "本地代理已停止")
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

    private fun handle(client: Socket) {
        try {
            val inp = client.getInputStream()
            val out = client.getOutputStream()

            val requestLine = readLine(inp) ?: run { client.close(); return }
            val parts = requestLine.split(" ")
            if (parts.size < 2 || !parts[0].equals("CONNECT", true)) {
                out.write("HTTP/1.1 405 Method Not Allowed\r\n\r\n".toByteArray())
                out.flush()
                client.close()
                return
            }
            // 读完剩余请求头（直到空行），避免把头文本当成 TLS 数据转发
            while (true) {
                val h = readLine(inp) ?: break
                if (h.isEmpty()) break
            }

            val hostPort = parts[1]
            val (host, p) = hostPort.split(":", limit = 2).let {
                it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 443)
            }
            // 强制 IPv4：过滤掉 IPv6（部分手机网络无 IPv6，Go 解析出 IPv6 会 network unreachable）
            val addr = resolveIpv4(host)
            if (addr == null) {
                appendLog("RESOLVE-FAIL $host (no A record via system DNS)")
                out.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                out.flush()
                client.close()
                return
            }
            appendLog("CONNECT $host -> ${addr.hostAddress}:$p")
            val remote = Socket()
            try {
                remote.connect(InetSocketAddress(addr, p), 15000)
            } catch (e: Exception) {
                appendLog("CONNECT-FAIL $host -> ${addr.hostAddress}:$p : ${e.message}")
                out.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                out.flush()
                client.close()
                return
            }
            out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            out.flush()
            appendLog("TUNNEL-UP $host -> ${addr.hostAddress}:$p")

            val r1 = pipe(remote.getInputStream(), client.getOutputStream())
            val r2 = pipe(client.getInputStream(), remote.getOutputStream())
            try { r1.join() } catch (_: Exception) { /* ignore */ }
            try { r2.join() } catch (_: Exception) { /* ignore */ }
            try { remote.close() } catch (_: Exception) { /* ignore */ }
            try { client.close() } catch (_: Exception) { /* ignore */ }
        } catch (e: Exception) {
            Log.w(TAG, "代理处理异常: ${e.message}")
            try { client.close() } catch (_: Exception) { /* ignore */ }
        }
    }

    /** 仅取 IPv4 A 记录；若系统 DNS 只返回 IPv6，回退到 getAllByName 兜底（仍可能失败，但日志可见）。 */
    private fun resolveIpv4(host: String): InetAddress? {
        return try {
            InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }
                ?: InetAddress.getByName(host)
        } catch (e: Exception) {
            appendLog("RESOLVE-EX $host : ${e.message}")
            null
        }
    }

    /** 双向转发线程：从 `in` 读，写到 out，直到对端关闭。 */
    private fun pipe(`in`: InputStream, out: OutputStream): Thread {
        val t = object : Thread() {
            override fun run() {
                val buf = ByteArray(16 * 1024)
                var n: Int
                try {
                    while (`in`.read(buf).also { n = it } > 0) {
                        out.write(buf, 0, n)
                        out.flush()
                    }
                } catch (_: Exception) { /* ignore */ }
            }
        }
        t.start()
        return t
    }

    /** 逐字节读一行（无缓冲，避免超前消费 TLS 数据），空行返回空字符串。 */
    private fun readLine(`in`: InputStream): String? {
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
