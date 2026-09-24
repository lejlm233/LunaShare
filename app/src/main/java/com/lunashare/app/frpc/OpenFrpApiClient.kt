package com.lunashare.app.frpc

import android.util.Log
import com.lunashare.app.frpc.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the OpenFrp public API.
 *
 * API base URLs:
 *   - Primary:   https://api.openfrp.net
 *   - Secondary: https://of-dev-api.bfsea.xyz
 *
 * Authentication:
 *   The "Security Login" method is used: the user pastes an Authorization
 *   token obtained from the OpenFrp dashboard → stored & sent as the
 *   `Authorization` header on every request. The optional `session` cookie
 *   is also supported.
 */
class OpenFrpApiClient {

    companion object {
        private const val TAG = "OpenFrpApi"
        const val API_BASE_PRIMARY = "https://api.openfrp.net"
        const val API_BASE_FALLBACK = "https://of-dev-api.bfsea.xyz"

        // OAuth / OpenID endpoints (for account-login flow)
        const val OPENID_LOGIN_URL = "https://openid.17a.ink/api/public/login"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // ── Credentials ───────────────────────────────────────────────

    data class Credentials(
        val authorization: String,
        val session: String = ""
    )

    // ── Public API ────────────────────────────────────────────────

    /**
     * Fetch the authenticated user's profile.
     * Endpoint: POST /frp/api/getUserInfo
     */
    suspend fun getUserInfo(creds: Credentials): Result<OpenFrpUserInfo> =
        apiPost(
            path = "/frp/api/getUserInfo",
            creds = creds,
            body = null
        ) { resp ->
            val data = resp.data
            when {
                data != null -> json.decodeFromJsonElement<OpenFrpUserInfo>(wrapData(data))
                else -> OpenFrpUserInfo()
            }
        }

    /**
     * Fetch list of available nodes (servers).
     * Endpoint: POST /frp/api/getNodeList
     */
    suspend fun getNodeList(creds: Credentials): Result<List<OpenFrpNode>> =
        apiPost(
            path = "/frp/api/getNodeList",
            creds = creds,
            body = null
        ) { resp ->
            val data = resp.data
            when (data) {
                is List<*> -> {
                    val list = data as? List<JsonElement> ?: emptyList()
                    list.mapNotNull { runCatching { json.decodeFromJsonElement<OpenFrpNode>(it) }.getOrNull() }
                }
                is JsonElement -> runCatching {
                    val arr = data.jsonObject["list"]
                        ?: data.jsonObject["proxies"]
                        ?: data.jsonObject["data"]
                        ?: data
                    when {
                        arr is JsonArray -> arr.mapNotNull {
                            runCatching { json.decodeFromJsonElement<OpenFrpNode>(it) }.getOrNull()
                        }
                        else -> listOf(
                            runCatching { json.decodeFromJsonElement<OpenFrpNode>(arr) }.getOrNull()
                        ).filterNotNull()
                    }
                }.getOrDefault(emptyList())
                else -> emptyList()
            }
        }

    /**
     * Fetch list of user's proxy (tunnel) configurations.
     * Endpoint: POST /frp/api/getUserProxies
     */
    suspend fun getUserProxies(creds: Credentials): Result<List<OpenFrpProxy>> =
        apiPost(
            path = "/frp/api/getUserProxies",
            creds = creds,
            body = null
        ) { resp ->
            val data = resp.data
            Log.d(TAG, "getUserProxies resp.data raw: $data")
            val listJson: JsonElement? = when (data) {
                is JsonElement -> {
                    // Try multiple keys commonly used by the API
                    data.jsonObject["data"]
                        ?: data.jsonObject["proxies"]
                        ?: data.jsonObject["list"]
                        ?: data
                }
                else -> null
            }
            val arr: JsonArray? = when (listJson) {
                is JsonArray -> listJson
                is JsonObject -> JsonArray(listOf(listJson))
                else -> null
            }
            arr?.mapNotNull { item ->
                runCatching { json.decodeFromJsonElement<OpenFrpProxy>(item) }.getOrNull()
            } ?: emptyList()
        }

    /**
     * Create a new proxy (tunnel).
     * Endpoint: POST /frp/api/newProxy
     */
    suspend fun newProxy(creds: Credentials, req: NewProxyRequest): Result<OpenFrpProxy> {
        // Build the request body manually instead of json.encodeToString(req) so that the
        // optional `remote_port` is OMITTED (not serialized as JSON null) when not provided.
        // OpenFrp's backend uses `isset()` to validate required fields; sending
        // `"remote_port": null` makes it treat the field as missing and return
        // "基础数据是必填的". TCP/UDP tunnels get an auto-assigned remote port when omitted.
        val bodyJson = buildJsonObject {
            put("node_id", JsonPrimitive(req.node_id))
            put("type", JsonPrimitive(req.type))
            put("local_addr", JsonPrimitive(req.local_addr))
            put("local_port", JsonPrimitive(req.local_port))
            put("name", JsonPrimitive(req.name))
            if (req.remote_port != null) put("remote_port", JsonPrimitive(req.remote_port))
            put("domain_bind", JsonPrimitive(req.domain_bind))
            put("dataEncrypt", JsonPrimitive(req.dataEncrypt))
            put("dataGzip", JsonPrimitive(req.dataGzip))
            put("autoTls", JsonPrimitive(req.autoTls))
            put("forceHttps", JsonPrimitive(req.forceHttps))
            put("proxyProtocolVersion", JsonPrimitive(req.proxyProtocolVersion))
            put("custom", JsonPrimitive(req.custom))
        }
        val bodyStr = bodyJson.toString()
        Log.d(TAG, "newProxy request body: $bodyStr")
        return apiPost(
            path = "/frp/api/newProxy",
            creds = creds,
            body = bodyStr
        ) { resp ->
            val data = resp.data
            Log.d(TAG, "newProxy resp.data raw: $data")
            when (data) {
                is JsonElement -> {
                    val obj = runCatching { data.jsonObject }.getOrNull()
                    // Primary: decode the whole object.
                    val decoded = runCatching { json.decodeFromJsonElement<OpenFrpProxy>(data) }
                        .getOrElse { OpenFrpProxy(name = req.name ?: "") }
                    // Fallback: pull id from any common key, in case decoding missed it.
                    val idFromObj = obj?.get("id")?.toString()?.toIntOrNull()
                    val proxyIdFromObj = obj?.get("proxy_id")?.toString()?.toIntOrNull()
                        ?: obj?.get("proxyId")?.toString()?.toIntOrNull()
                    val finalId = decoded.id.takeIf { it != 0 }
                        ?: idFromObj ?: proxyIdFromObj ?: decoded.proxyId ?: 0
                    Log.d(TAG, "newProxy parsed id=$finalId (decoded.id=${decoded.id}, proxyId=${decoded.proxyId})")
                    decoded.copy(
                        id = finalId,
                        proxyId = decoded.proxyId ?: proxyIdFromObj,
                        name = decoded.name.ifBlank { req.name ?: "" }
                    )
                }
                else -> OpenFrpProxy(name = req.name ?: "")
            }
        }
    }

    /**
     * Delete an existing proxy (tunnel).
     * Endpoint: POST /frp/api/removeProxy
     */
    suspend fun removeProxy(creds: Credentials, proxyId: Int): Result<Boolean> =
        apiPost(
            path = "/frp/api/removeProxy",
            creds = creds,
            body = json.encodeToString(RemoveProxyRequest(proxy_id = proxyId))
        ) { resp ->
            resp.isSuccess
        }

    // ── Internal: request engine ──────────────────────────────────

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private fun wrapData(elem: JsonElement): JsonElement {
        // Some endpoints return the raw object; some wrap it in {data:{...}}
        return elem
    }

    /**
     * Execute a POST against the OpenFrp API and parse the envelope.
     * Returns Result<T> so callers can use .fold / .onSuccess / .onFailure.
     */
    private suspend fun <T> apiPost(
        path: String,
        creds: Credentials,
        body: String?,
        parse: (OpenFrpApiResponse<JsonElement>) -> T
    ): Result<T> {
        val urls = listOf(API_BASE_PRIMARY, API_BASE_FALLBACK)
        var lastError: Throwable? = null

        for (base in urls) {
            try {
                val fullUrl = base + path
                Log.d(TAG, "POST $fullUrl")
                if (body != null) Log.d(TAG, "Request body: $body")

                val requestBuilder = Request.Builder()
                    .url(fullUrl)
                    .header("Authorization", creds.authorization)
                    .header("Accept", "application/json")
                    .header("User-Agent", "LunaShare-OpenFrp/1.0")

                if (creds.session.isNotBlank()) {
                    requestBuilder.header("Cookie", "session=${creds.session}")
                }

                if (body != null) {
                    requestBuilder.post(body.toRequestBody(JSON_MEDIA))
                } else {
                    // Empty JSON body {} — some endpoints require POST with no data
                    requestBuilder.post("{}".toRequestBody(JSON_MEDIA))
                }

                val response = client.newCall(requestBuilder.build()).execute()
                val responseBody = response.body?.string().orEmpty()

                // Check for updated Authorization token in response headers
                // (the API may rotate tokens periodically)
                val newAuth = response.header("Authorization")
                if (newAuth != null && newAuth != creds.authorization) {
                    Log.i(TAG, "Received updated Authorization token from server")
                }

                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code} from $fullUrl: $responseBody")
                    lastError = IOException("HTTP ${response.code}: ${response.message}")
                    continue
                }

                Log.v(TAG, "Response: $responseBody")

                val envelope = try {
                    json.decodeFromString<OpenFrpApiResponse<JsonElement>>(responseBody)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse API response", e)
                    lastError = e
                    continue
                }

                if (!envelope.isSuccess) {
                    val msg = envelope.displayMessage.ifBlank { "API 请求失败" }
                    Log.w(TAG, "API flag=false: $msg")
                    lastError = OpenFrpApiException(msg, envelope)
                    // Don't continue to fallback — API responded but flagged failure
                    return Result.failure(lastError)
                }

                return try {
                    Result.success(parse(envelope))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse data field", e)
                    lastError = e
                    continue
                }
            } catch (e: IOException) {
                Log.w(TAG, "Network error on $base: ${e.message}")
                lastError = e
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error", e)
                lastError = e
            }
        }

        return Result.failure(lastError ?: IOException("未知错误"))
    }
}

/**
 * Thrown when the OpenFrp API responds with flag=false / success=false.
 */
class OpenFrpApiException(
    message: String,
    val response: OpenFrpApiResponse<*>? = null
) : IOException(message)
