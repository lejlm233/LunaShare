package com.lunashare.app.service

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Alternate lightweight HTTP-only file server based on NanoHTTPD.
 * Used as a fallback option if the primary FileServer is not suitable.
 */
class NanoFileServer(
    private val port: Int,
    private val rootDir: String,
    private val username: String = "",
    private val password: String = ""
) : NanoHTTPD("0.0.0.0", port) {

    companion object {
        private const val TAG = "NanoFileServer"
    }

    private val rootFile = File(rootDir)

    init {
        Log.i(TAG, "NanoFileServer constructor called: port=$port, root=$rootDir, username=$username")
        if (!rootFile.exists() || !rootFile.isDirectory) {
            throw IllegalArgumentException("Invalid directory: $rootDir")
        }
        Log.i(TAG, "NanoFileServer initialized successfully")
    }

    @Throws(IOException::class)
    fun startServer() {
        start(30000, false)
        Log.i(TAG, "NanoFileServer started on 0.0.0.0:$port, root=$rootDir, timeout=30s")
    }

    fun stopServer() {
        stop()
        Log.i(TAG, "NanoFileServer stopped")
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val remoteAddr = session.remoteIpAddress
        Log.d(TAG, "REQ: ${session.method} $uri from $remoteAddr")

        if (username.isNotBlank() && !checkAuth(session)) {
            Log.d(TAG, "Auth failed for $uri")
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Unauthorized")
                .apply { 
                    addHeader("WWW-Authenticate", "Basic realm=\"FileServer\"")
                    addHeader("Connection", "close")
                }
        }

        val filePath = resolvePath(uri)
        val file = File(rootFile, filePath)

        if (!file.exists()) {
            Log.d(TAG, "File not found: $file")
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
                .apply { addHeader("Connection", "close") }
        }

        if (file.isDirectory) {
            return listDirectory(file, uri)
        }

        return serveFile(file)
    }

    private fun checkAuth(session: IHTTPSession): Boolean {
        val auth = session.headers.entries.firstOrNull { 
            it.key.equals("authorization", ignoreCase = true) 
        }?.value ?: return false
        if (!auth.startsWith("Basic ", ignoreCase = true)) return false

        val credentials = auth.substring(6).decodeBase64()
        val parts = credentials.split(":", limit = 2)
        if (parts.size != 2) return false

        return parts[0] == username && parts[1] == password
    }

    private fun resolvePath(uri: String): String {
        var path = uri.removePrefix("/")
        if (path.contains("..")) {
            path = path.replace("..", "")
        }
        return path
    }

    private fun listDirectory(dir: File, uri: String): Response {
        val files = dir.listFiles() ?: return newFixedLengthResponse(
            Response.Status.INTERNAL_ERROR, "text/plain", "Cannot list directory"
        )

        val html = StringBuilder()
        html.append("<!DOCTYPE html><html><head><title>Directory Listing</title></head><body>")
        html.append("<h1>Directory: $uri</h1>")
        html.append("<ul>")

        if (uri != "/") {
            val parentUri = uri.substringBeforeLast("/", "") + "/"
            html.append("<li><a href=\"$parentUri\">..</a></li>")
        }

        files.forEach { file ->
            val fileName = file.name
            val fileUri = if (uri.endsWith("/")) "$uri$fileName" else "$uri/$fileName"
            val size = if (file.isFile) " (${file.length()} bytes)" else ""
            html.append("<li><a href=\"$fileUri\">$fileName</a>$size</li>")
        }

        html.append("</ul></body></html>")

        return newFixedLengthResponse(Response.Status.OK, "text/html", html.toString())
            .apply { addHeader("Connection", "close") }
    }

    private fun serveFile(file: File): Response {
        try {
            val inputStream = FileInputStream(file)
            val mimeType = guessMimeType(file.name)
            val fileLength = file.length()
            Log.d(TAG, "Serving file: ${file.name}, size=$fileLength, mime=$mimeType")
            return newFixedLengthResponse(Response.Status.OK, mimeType, inputStream, fileLength)
                .apply { addHeader("Connection", "close") }
        } catch (e: IOException) {
            Log.e(TAG, "Error serving file: ${file.name}", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/plain", "Error reading file"
            ).apply { addHeader("Connection", "close") }
        }
    }

    private fun guessMimeType(name: String): String {
        return when {
            name.endsWith(".html", ignoreCase = true) -> "text/html"
            name.endsWith(".css", ignoreCase = true) -> "text/css"
            name.endsWith(".js", ignoreCase = true) -> "application/javascript"
            name.endsWith(".json", ignoreCase = true) -> "application/json"
            name.endsWith(".png", ignoreCase = true) -> "image/png"
            name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            name.endsWith(".gif", ignoreCase = true) -> "image/gif"
            name.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
            name.endsWith(".txt", ignoreCase = true) -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    private fun String.decodeBase64(): String {
        return String(java.util.Base64.getDecoder().decode(this))
    }
}
