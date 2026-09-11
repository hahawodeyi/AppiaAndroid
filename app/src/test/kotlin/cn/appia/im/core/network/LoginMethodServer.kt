package cn.appia.im.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

internal val json = Json { ignoreUnknownKeys = true }

internal fun JsonObject?.str(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

internal fun parse(raw: String): JsonObject? =
    runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()

/**
 * 脚本化 DDP WS 服务端：connect→connected、ping→pong、method:login→result（登录结果形状），
 * 记录全部客户端帧（resume 断言用）。connected 必须在收到客户端 connect 帧后回（与 DdpClientTest
 * 的 DdpServer 同口径）——在 onOpen 抢发会与 DdpClient 的监听注册竞态，握手随机挂死。
 * RocketSdkTest / AuthApiTest 共用（AuthApi.login 编排同样要过 DDP connect + login{resume}）。
 */
internal class LoginMethodServer : WebSocketListener() {
    val frames = CopyOnWriteArrayList<String>()
    private val wsRef = AtomicReference<WebSocket?>(null)

    override fun onOpen(webSocket: WebSocket, response: Response) {
        wsRef.set(webSocket)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        frames.add(text)
        val obj = parse(text) ?: return
        when (obj.str("msg")) {
            "connect" -> webSocket.send("""{"msg":"connected","session":"s"}""")
            "ping" -> webSocket.send("""{"msg":"pong"}""")
            "method" -> if (obj.str("method") == "login") {
                webSocket.send(
                    """{"msg":"result","id":"${obj.str("id")}",""" +
                        """"result":{"id":"ddp-user-1","token":"ddp-token","createCipher":{"${'$'}date":1690000000000}}}""",
                )
            }
        }
    }

    /** 轮询等待满足条件的客户端帧（真实时间，超时失败并打印已收帧）。 */
    fun awaitFrame(desc: String, timeoutMs: Long = 5_000, predicate: (JsonObject) -> Boolean): JsonObject {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            for (raw in frames) {
                parse(raw)?.let { if (predicate(it)) return it }
            }
            Thread.sleep(10)
        }
        throw AssertionError("timeout waiting for frame [$desc], received=$frames")
    }
}
