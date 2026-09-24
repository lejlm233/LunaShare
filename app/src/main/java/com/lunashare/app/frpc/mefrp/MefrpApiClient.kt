package com.lunashare.app.frpc.mefrp

import com.lunashare.app.frpc.mefrp.MefrpCreateProxyData
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.booleanOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * mefrp（幻缘映射）公开 API 客户端。
 *
 * - 基址: https://api.mefrp.com/api
 * - 认证: HTTP `Authorization: Bearer <访问令牌>`（用户在 mefrp 网页控制台获取的「用户 Token」）
 * - 信封: {code, data, message}，code == 200 表示成功
 * - frpc 启动：GET /auth/user/frpToken 拿启动令牌（frpToken），mefrpc 用
 *   `-t <frpToken> -p <proxyId>`（即后台「生成启动配置」给出的命令，走服务端自取配置模式）；
 *   注意 mefrp 的 -t 传的是 frpToken 而非「用户访问令牌」，且 API 依赖 Host: api.mefrp.com
 *   路由（CDN），不要用 IP 当 --api-root-url 直连。
 */
class MefrpApiClient {

    companion object {
        private const val TAG = "MefrpApi"
        const val API_BASE = "https://api.mefrp.com/api"
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

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    // ── Public API ────────────────────────────────────────────────

    /** GET /auth/user/info — 校验访问令牌并获取用户信息。 */
    suspend fun getUserInfo(token: String): Result<MefrpUserInfo> =
        apiCall(method = "GET", path = "/auth/user/info", token = token, body = null) { resp ->
            decodeWrapped(resp.data, MefrpUserInfo())
        }

    /** GET /auth/user/frpToken — 获取 frpc 启动令牌。 */
    suspend fun getUserFrpToken(token: String): Result<String> =
        apiCall(method = "GET", path = "/auth/user/frpToken", token = token, body = null) { resp ->
            val data = resp.data
            if (data is JsonElement) {
                data.jsonObject["token"]?.jsonPrimitive?.contentOrNull ?: ""
            } else ""
        }

    /** GET /auth/node/list — 节点列表。 */
    suspend fun getNodeList(token: String): Result<List<MefrpNode>> =
        apiCall(method = "GET", path = "/auth/node/list", token = token, body = null) { resp ->
            val data = resp.data
            when (data) {
                is JsonArray -> data.mapNotNull { el ->
                    runCatching { json.decodeFromJsonElement<MefrpNode>(el).deriveVip() }.getOrNull()
                }
                is JsonElement -> runCatching {
                    val arr = data.jsonObject["list"]
                        ?: data.jsonObject["nodes"]
                        ?: data.jsonObject["data"]
                    if (arr is JsonArray) arr.mapNotNull { el ->
                        runCatching { json.decodeFromJsonElement<MefrpNode>(el).deriveVip() }.getOrNull()
                    } else emptyList()
                }.getOrDefault(emptyList())
                else -> emptyList()
            }
        }

    /** GET /auth/createProxyData — 创建约束（可用端口/剩余隧道数）。 */
    suspend fun getCreateProxyData(token: String): Result<MefrpCreateProxyData> =
        apiCall(method = "GET", path = "/auth/createProxyData", token = token, body = null) { resp ->
            decodeWrapped(resp.data, MefrpCreateProxyData())
        }

    /** GET /auth/node/status — 节点运行状态（负载/在线数/版本，官网节点卡片同源）。 */
    suspend fun getNodeStatus(token: String): Result<List<MefrpNodeStatus>> =
        apiCall(method = "GET", path = "/auth/node/status", token = token, body = null) { resp ->
            val data = resp.data
            when (data) {
                is JsonArray -> data.mapNotNull {
                    runCatching { json.decodeFromJsonElement<MefrpNodeStatus>(it) }.getOrNull()
                }
                is JsonElement -> runCatching {
                    val arr = data.jsonObject["list"]
                        ?: data.jsonObject["nodes"]
                        ?: data.jsonObject["data"]
                    if (arr is JsonArray) arr.mapNotNull {
                        runCatching { json.decodeFromJsonElement<MefrpNodeStatus>(it) }.getOrNull()
                    } else emptyList()
                }.getOrDefault(emptyList())
                else -> emptyList()
            }
        }

    /** GET /auth/proxy/list — 隧道列表（文档为 /auth/proxy/list 或返回嵌套对象，兼容数组/对象）。 */
    suspend fun getProxyList(token: String): Result<List<MefrpProxy>> =
        apiCall(method = "GET", path = "/auth/proxy/list", token = token, body = null) { resp ->
            val data = resp.data
            when (data) {
                is JsonArray -> data.mapNotNull {
                    runCatching { json.decodeFromJsonElement<MefrpProxy>(it) }.getOrNull()
                }
                is JsonElement -> runCatching {
                    val obj = data.jsonObject
                    val arr = obj["proxies"]
                        ?: obj["list"]
                        ?: obj["data"]
                    when (arr) {
                        is JsonArray -> arr.mapNotNull {
                            runCatching { json.decodeFromJsonElement<MefrpProxy>(it) }.getOrNull()
                        }
                        is JsonElement -> listOfNotNull(
                            runCatching { json.decodeFromJsonElement<MefrpProxy>(arr) }.getOrNull()
                        )
                        else -> emptyList()
                    }
                }.getOrDefault(emptyList())
                else -> emptyList()
            }
        }

    /**
     * GET /auth/proxy/list — 完整解析 data：{nodes, proxies}。
     * nodes 是该账号隧道涉及节点的「nodeId → hostname（真实连接地址，如
     * ip.lhdyx.top）」映射来源——node/list 的 hostname 对普通用户恒为空，
     * 公网访问地址只能从这里拿。
     */
    suspend fun getProxyListWithNodes(token: String): Result<MefrpProxyListData> =
        apiCall(method = "GET", path = "/auth/proxy/list", token = token, body = null) { resp ->
            val data = resp.data
            if (data is JsonElement) {
                runCatching { json.decodeFromJsonElement<MefrpProxyListData>(data) }
                    .getOrElse { MefrpProxyListData() }
            } else {
                MefrpProxyListData()
            }
        }

    /**
     * POST /auth/proxy/create — 创建隧道。
     * 成功时 data 通常为 null，调用方需按 proxyName 回查 proxyId（幂等自愈）。
     */
    suspend fun createProxy(token: String, req: MefrpCreateProxyRequest): Result<String> =
        apiCall(method = "POST", path = "/auth/proxy/create", token = token, body = json.encodeToString(req)) { resp ->
            resp.displayMessage
        }

    /** POST /auth/proxy/delete — 删除隧道。 */
    suspend fun deleteProxy(token: String, proxyId: Int): Result<String> =
        apiCall(
            method = "POST",
            path = "/auth/proxy/delete",
            token = token,
            body = """{"proxyId":$proxyId}"""
        ) { resp ->
            resp.displayMessage
        }

    /** POST /auth/proxy/config — 获取隧道配置（TOML 等，目前仅用于校验存在性）。 */
    suspend fun getProxyConfig(token: String, proxyId: Int): Result<String> =
        apiCall(
            method = "POST",
            path = "/auth/proxy/config",
            token = token,
            body = """{"proxyId":$proxyId,"format":"toml"}"""
        ) { resp ->
            val data = resp.data
            if (data is JsonElement) {
                data.jsonObject["config"]?.jsonPrimitive?.contentOrNull ?: ""
            } else ""
        }

    // ── Internal: request engine ──────────────────────────────────

    private inline fun <reified T> decodeWrapped(data: JsonElement?, fallback: T): T =
        if (data != null) {
            runCatching { json.decodeFromJsonElement<T>(data) }.getOrDefault(fallback)
        } else fallback

    private suspend fun <T> apiCall(
        method: String,
        path: String,
        token: String,
        body: String?,
        parse: (MefrpApiResponse<JsonElement>) -> T
    ): Result<T> {
        try {
            val fullUrl = API_BASE + path
            val builder = Request.Builder()
                .url(fullUrl)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .header("User-Agent", "LunaShare-MEFrp/1.0.7")

            if (body != null) {
                builder.post(body.toRequestBody(JSON_MEDIA))
            } else {
                builder.get()
            }

            val response = client.newCall(builder.build()).execute()
            val responseBody = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                val msg = runCatching {
                    json.decodeFromJsonElement<MefrpApiResponse<JsonElement>>(
                        Json.parseToJsonElement(responseBody)
                    ).displayMessage
                }.getOrDefault(response.message)
                return Result.failure(IOException("HTTP ${response.code}: $msg"))
            }

            val envelope = runCatching {
                json.decodeFromJsonElement<MefrpApiResponse<JsonElement>>(
                    Json.parseToJsonElement(responseBody)
                )
            }.getOrElse {
                return Result.failure(IOException("响应解析失败: ${it.message}"))
            }

            if (!envelope.isSuccess) {
                return Result.failure(IOException(envelope.displayMessage))
            }
            return Result.success(parse(envelope))
        } catch (e: IOException) {
            return Result.failure(e)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }
}