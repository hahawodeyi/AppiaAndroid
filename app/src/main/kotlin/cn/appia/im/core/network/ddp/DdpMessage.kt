package cn.appia.im.core.network.ddp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** 全局共享 JSON 实例：DDP 消息字段开放，忽略未知字段。 */
internal val ddpJson: Json = Json { ignoreUnknownKeys = true }

internal val DDP_EMPTY_OBJECT: JsonObject = JsonObject(emptyMap())

data class DdpOptions(
    val host: String, // e.g. https://example.com
    /** 为空时按 host 协议推导（https→wss），对齐 TS DdpClientOptions.useSsl。 */
    val useSsl: Boolean? = null,
    /** 重连间隔（TS reopen）。 */
    val reopenMs: Long = 5_000,
    /** 心跳间隔（TS ping）。 */
    val pingMs: Long = 20_000,
    /** WebSocket 建连 + DDP `connect` 握手超时（毫秒）。 */
    val connectTimeoutMs: Long = 15_000,
    /** 等待单条 DDP 响应超时（毫秒）。 */
    val responseTimeoutMs: Long = 15_000,
)

/** TS DdpLoginResult { id, token, createCipher: { $date } }（ddpClient.ts:36-40）。 */
data class DdpLoginResult(
    val id: String,
    val token: String,
    val createCipherDate: Long,
)

/** TS DdpSubscription（ddpClient.ts:42-45）：unsubscribe 走 unsub 流程。 */
class DdpSubscription internal constructor(
    val id: String,
    private val client: DdpClient,
) {
    suspend fun unsubscribe(): JsonElement? = client.unsubscribeRaw(id)
}

/** TS onStreamData 返回的 { stop } 句柄（ddpClient.ts:99-102）。 */
fun interface Disposable {
    fun stop()
}

/** DDP 协议/传输错误（消息文案与 TS 版一致，前缀 `[ddp]`）。 */
open class DdpException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** 响应/`nosub` 带 `error` 字段时抛出（TS: reject(result.error)）。 */
class DdpMethodError(val error: JsonElement) :
    DdpException("[ddp] method error: $error")

/** 连接关闭事件（TS onclose 的 CloseEvent 等价物）。 */
data class DdpCloseEvent(
    val code: Int,
    val reason: String,
    val cause: Throwable? = null,
)

/**
 * WS URL 推导（TS hostToWs ddpClient.ts:62-69）：
 * 剥尾斜杠、https→wss / http→ws（useSsl 显式覆盖）、拼 `/websocket`。
 */
internal fun hostToWs(host: String, useSsl: Boolean?): String {
    val trimmed = host.replace(Regex("/$"), "")
    val isHttps = Regex("^https://", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
    val ssl = useSsl ?: isHttps
    val protocol = if (ssl) "wss://" else "ws://"
    val withoutProtocol = trimmed.replace(Regex("^https?://", RegexOption.IGNORE_CASE), "")
    return "$protocol$withoutProtocol/websocket"
}

// ==== 消息构造（TS sendRaw/subscribe 各调用点）====

/** TS ddpClient.ts:183-186 —— {msg:"connect", version:"1", support:[...]}，不带 id。 */
internal fun ddpConnectMessage(): JsonObject = buildJsonObject {
    put("msg", "connect")
    put("version", "1")
    putJsonArray("support") { add("1"); add("pre2"); add("pre1") }
}

/** TS ddpClient.ts:257 —— {msg:"ping"}，不带 id。 */
internal fun ddpPingMessage(): JsonObject = buildJsonObject { put("msg", "ping") }

/** TS ddpClient.ts:288 —— {msg:"pong"}，不带 id。 */
internal fun ddpPongMessage(): JsonObject = buildJsonObject { put("msg", "pong") }

/** TS ddpClient.ts:360 —— {msg:"method", method, params}；id 由 sendRaw 统一补挂。 */
internal fun ddpMethodMessage(method: String, params: List<JsonElement>): JsonObject = buildJsonObject {
    put("msg", "method")
    put("method", method)
    putJsonArray("params") { params.forEach { add(it) } }
}

/** TS ddpClient.ts:425 —— {msg:"sub", id, name: topic, params}。 */
internal fun ddpSubMessage(id: String, topic: String, params: JsonArray): JsonObject = buildJsonObject {
    put("msg", "sub")
    put("id", id)
    put("name", topic)
    put("params", params)
}

/** TS ddpClient.ts:527 —— {msg:"unsub", id}。 */
internal fun ddpUnsubMessage(id: String): JsonObject = buildJsonObject {
    put("msg", "unsub")
    put("id", id)
}

/**
 * 三路分发键提取（TS handleMessage ddpClient.ts:293-295）：
 * `msg`、`collection`、`id` 各发一路，同名可同时命中多路。
 * JS truthy 检查在 DDP 场景下等价于「非空字符串」。
 */
internal fun eventKeys(data: JsonObject): List<String> = buildList {
    (data["msg"] as? JsonPrimitive)?.takeIf { it.content.isNotEmpty() }?.let { add(it.content) }
    (data["collection"] as? JsonPrimitive)?.takeIf { it.content.isNotEmpty() }?.let { add(it.content) }
    (data["id"] as? JsonPrimitive)?.takeIf { it.content.isNotEmpty() }?.let { add(it.content) }
}
