package com.lunashare.app.model

/**
 * Service type options for file sharing.
 */
enum class ShareServiceType(val displayName: String, val defaultPort: Int) {
    HTTP("HTTP/WebDAV", 8080),
    FTP("FTP", 8021)
}
