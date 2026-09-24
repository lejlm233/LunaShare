package com.lunashare.app.frpc

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * OAuth 2.0 login helper for OpenFrp via Natayark ID.
 *
 * Flow:
 *   1. User logs in via WebView at the authorize URL
 *   2. Server redirects to callback URL with a `code` parameter
 *   3. We exchange the code for a session ID + Authorization header
 */
class OpenFrpOAuthLogin {

    companion object {
        private const val TAG = "OpenFrpOAuthLogin"

        /** OAuth authorize URL — opens Natayark ID login page */
        const val AUTHORIZE_URL =
            "https://account.naids.com/api/api/oauth2/authorize?response_type=code" +
            "&redirect_uri=https://api.openfrp.net/oauth_callback&client_id=openfrp"

        /** Callback URL prefix to intercept in WebView */
        const val CALLBACK_PREFIX = "https://api.openfrp.net/oauth_callback"

        /** Exchange code for session + Authorization */
        private const val TOKEN_EXCHANGE_URL = "https://api.openfrp.net/oauth2/callback"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private val JSON = Json { ignoreUnknownKeys = true }

    data class TokenResult(
        val authorization: String,
        val session: String
    )

    /**
     * Exchange the OAuth code for an Authorization token.
     * POST to https://api.openfrp.net/oauth2/callback?code=XXX
     *
     * Response body: {"data":"<sessionId>","flag":true,"msg":"登录成功！"}
     * Response header: Authorization: OPENFRPxxx
     */
    suspend fun exchangeCode(code: String): Result<TokenResult> {
        return try {
            val url = "$TOKEN_EXCHANGE_URL?code=$code"
            Log.d(TAG, "Exchanging code at $url")

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "LunaShare-OpenFrp/1.0")
                .post(okhttp3.RequestBody.create(null, byteArrayOf()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()
            Log.d(TAG, "Token exchange response: code=${response.code} body=$responseBody")

            // Extract Authorization from response header
            val authHeader = response.header("Authorization")
                ?: response.header("authorization")
                ?: ""

            if (authHeader.isBlank()) {
                Log.e(TAG, "No Authorization header in response. Headers: ${response.headers}")
                return Result.failure(Exception("未获取到 Authorization，请重试"))
            }

            // Parse session from body
            val session = try {
                val json = JSON.parseToJsonElement(responseBody).jsonObject
                json["data"]?.toString()?.trim('"') ?: ""
            } catch (e: Exception) {
                ""
            }

            Log.d(TAG, "Got Authorization: ${authHeader.take(20)}... session=$session")
            Result.success(TokenResult(authHeader, session))
        } catch (e: Exception) {
            Log.e(TAG, "Token exchange failed", e)
            Result.failure(e)
        }
    }
}
