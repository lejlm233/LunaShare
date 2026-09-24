package com.lunashare.app.ui

import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

private const val TAG = "OpenFrpWebViewLogin"
private const val CONSOLE_URL = "https://console.openfrp.net/"

/**
 * WebView-based login for OpenFrp.
 *
 * Loads the OpenFrp console. Intercepts ALL network requests at the Android level
 * to capture the Authorization header. Also checks localStorage/sessionStorage/cookies.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenFrpWebViewLogin(
    onDismiss: () -> Unit,
    onLoginSuccess: (authorization: String, session: String) -> Unit
) {
    var statusMessage by remember { mutableStateOf("正在加载登录页面...") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var captured by remember { mutableStateOf(false) }
    var onLoginSuccessRef by remember { mutableStateOf(onLoginSuccess) }
    onLoginSuccessRef = onLoginSuccess

    // Periodically check localStorage/sessionStorage/cookies
    LaunchedEffect(webViewRef) {
        val webView = webViewRef ?: return@LaunchedEffect
        while (!captured) {
            delay(1500)
            webView.post {
                webView.evaluateJavascript(
                    """
                    (function() {
                        try {
                            var result = {};
                            // Dump all localStorage
                            result.ls = {};
                            for (var i = 0; i < localStorage.length; i++) {
                                var k = localStorage.key(i);
                                result.ls[k] = localStorage.getItem(k);
                            }
                            // Dump all sessionStorage
                            result.ss = {};
                            for (var i = 0; i < sessionStorage.length; i++) {
                                var k = sessionStorage.key(i);
                                result.ss[k] = sessionStorage.getItem(k);
                            }
                            // Check for OPENFRP token in any value
                            var allVals = Object.values(result.ls).concat(Object.values(result.ss));
                            for (var i = 0; i < allVals.length; i++) {
                                var v = allVals[i];
                                if (v && typeof v === 'string' && v.startsWith('OPENFRP')) {
                                    return JSON.stringify({token: v, source: 'storage'});
                                }
                            }
                            return JSON.stringify({storage: result, cookie: document.cookie});
                        } catch(e) { return JSON.stringify({error: e.message}); }
                    })()
                    """.trimIndent()
                ) { result ->
                    if (!captured && result != null && result != "null") {
                        val str = result.trim('"').replace("\\\"", "\"").replace("\\\\", "\\")
                        Log.d(TAG, "Storage check: $str")
                        // Check for captured auth (set by XHR hook)
                        val capturedMatch = Regex("\"__captured_auth\"\\s*:\\s*\"([^\"]+)\"").find(str)
                        if (capturedMatch != null) {
                            captured = true
                            val token = capturedMatch.groupValues[1]
                            Log.d(TAG, "Found captured auth: ${token.take(30)}...")
                            onLoginSuccessRef(token, "")
                        } else {
                            // Check cookie for authorization=
                            val cookieMatch = Regex("authorization=([^\";\\s]+)").find(str)
                            if (cookieMatch != null) {
                                captured = true
                                val token = cookieMatch.groupValues[1]
                                Log.d(TAG, "Found auth in cookie: ${token.take(30)}...")
                                onLoginSuccessRef(token, "")
                            }
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OpenFrp 登录") },
                navigationIcon = {
                    IconButton(onClick = {
                        webViewRef?.destroy()
                        onDismiss()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "关闭")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.userAgentString = "LunaShare/1.0 OpenFrpClient"

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest
                            ): Boolean {
                                return false
                            }

                            // Intercept ALL requests to capture Authorization header
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                if (!captured && request != null) {
                                    val headers = request.requestHeaders
                                    val auth = headers?.get("Authorization")
                                        ?: headers?.get("authorization")
                                        ?: headers?.get("AUTHORIZATION")

                                    if (auth != null && auth.isNotEmpty()) {
                                        Log.d(TAG, "Captured Authorization from request to ${request.url}")
                                        Log.d(TAG, "Auth: ${auth.take(30)}...")
                                        if (auth.startsWith("OPENFRP") || auth.startsWith("Bearer")) {
                                            captured = true
                                            view?.post {
                                                statusMessage = "登录成功！正在保存..."
                                                onLoginSuccessRef(auth, "")
                                            }
                                        }
                                    }
                                }
                                return null // let WebView load normally
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                Log.d(TAG, "Page finished: $url")
                                statusMessage = if (url?.contains("login") == true ||
                                    url?.contains("account") == true)
                                    "请登录您的 OpenFrp 账号"
                                else
                                    "正在检测登录状态..."

                                // Also check cookies
                                val cookies = CookieManager.getInstance().getCookie(url)
                                if (cookies != null) {
                                    Log.d(TAG, "Cookies for $url: $cookies")
                                }
                            }
                        }

                        loadUrl(CONSOLE_URL)
                        webViewRef = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
