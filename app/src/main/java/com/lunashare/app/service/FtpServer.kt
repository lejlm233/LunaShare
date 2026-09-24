package com.lunashare.app.service

import android.util.Log
import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Standalone FTP server that wraps [FtpHandler] for each connection.
 *
 * Listens on [port] and delegates each incoming connection to a new
 * [FtpHandler] instance, supporting concurrent clients.
 */
class FtpServer(
    private val port: Int,
    private val rootDir: File,
    private val username: String = "",
    private val password: String = ""
) {
    companion object {
        private const val TAG = "FtpServer"
        private const val BACKLOG = 5
    }

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var acceptThread: Thread? = null

    val isActive: Boolean get() = isRunning

    fun start(): Result<Unit> {
        return try {
            if (!rootDir.exists()) {
                if (!rootDir.mkdirs()) {
                    return Result.failure(IllegalArgumentException("无法创建目录: $rootDir"))
                }
            }
            if (!rootDir.isDirectory) {
                return Result.failure(IllegalArgumentException("无效的目录: $rootDir"))
            }
            serverSocket = ServerSocket(port, BACKLOG)
            isRunning = true
            Log.i(TAG, "FTP Server started on port $port, root=$rootDir")
            acceptThread = thread(name = "ftp-server-accept", isDaemon = true) {
                try {
                    while (isRunning && !serverSocket!!.isClosed) {
                        val client = serverSocket!!.accept()
                        thread(name = "ftp-server-worker", isDaemon = true) {
                            handleClient(client)
                        }
                    }
                } catch (e: Exception) {
                    if (isRunning) Log.e(TAG, "Accept error", e)
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start FTP server", e)
            Result.failure(e)
        }
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        Log.i(TAG, "FTP Server stopped")
    }

    private fun handleClient(client: java.net.Socket) {
        val remoteAddr = client.remoteSocketAddress?.toString() ?: "unknown"
        Log.d(TAG, "FTP connection from $remoteAddr")
        try {
            client.soTimeout = 60000
            FtpHandler(
                socket = client,
                rawInput = null,
                firstCmd = null,
                rootDir = rootDir,
                ftpUsername = username,
                ftpPassword = password
            ).handle()
        } catch (e: Exception) {
            Log.e(TAG, "FTP handler error for $remoteAddr: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }
}
