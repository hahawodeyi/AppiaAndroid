package cn.appia.im.core.network.sse

import cn.appia.im.core.network.RocketHttp
import cn.appia.im.core.network.ServerUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 非 2xx / 连接失败（RN aiStream.ts error 事件 → 'network'）。 */
private const val NETWORK_ERROR = "network"

/** type ERROR 且无 error 字段时的兜底（RN aiStream.ts `data.error ?? 'AI error'`）。 */
private const val AI_ERROR = "AI error"

/**
 * SSE chunk（RN lib/ai/streamData.ts StreamData）。[raw] 仅非 JSON 兜底时填充（非 JSON 整体作
 * `{raw}`）；JSON 解析成功但非对象时字段全空（RN 走 undefined 兜底，不产文本）。
 */
internal data class StreamData(
    val type: String? = null,
    val text: String? = null,
    val content: String? = null,
    val message: String? = null,
    val error: String? = null,
    val raw: String? = null,
)

/** RN streamData.ts:11-21 parseStreamData：JSON 解析失败 → 整体作 raw；空串 → null。 */
internal fun parseStreamData(raw: String): StreamData? {
    if (raw.isEmpty()) return null
    val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
        ?: return StreamData(raw = raw)
    if (element !is JsonObject) {
        // 裸词（kotlinx 宽松解析为字面量）：JS JSON.parse 语义下是失败 → raw；
        // 其余合法 JSON 非对象（数组/数字/null/字符串）：字段全空，不产文本
        val bareWord = element is JsonPrimitive && !element.isString && isJsonBareWord(raw)
        return if (bareWord) StreamData(raw = raw) else StreamData()
    }
    fun s(key: String): String? =
        (element[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    return StreamData(
        type = s("type"),
        text = s("text"),
        content = s("content"),
        message = s("message"),
        error = s("error"),
    )
}

/** JSON 字面量仅接受数字/true/false/null；其余裸词 JS JSON.parse 会抛错。 */
private fun isJsonBareWord(raw: String): Boolean =
    raw.toLongOrNull() == null && raw.toDoubleOrNull() == null &&
        raw != "true" && raw != "false" && raw != "null"

/** 流事件（T7 消费面）：Text 文本增量、Finished 正常收尾、Error 终止。 */
sealed interface AiStreamEvent {
    data class Text(val text: String) : AiStreamEvent
    data object Finished : AiStreamEvent
    data class Error(val message: String) : AiStreamEvent
}

/** 请求参数（RN aiStream.ts AiStreamParams 的 wire 子集；token/userId 由调用方注入）。 */
data class AiStreamRequest(
    val server: String,
    val token: String,
    val userId: String,
    val endpoint: String,
    val rid: String,
    val prompt: String,
    val agentUserId: String? = null,
)

/**
 * AI SSE 客户端（RN lib/ai/aiStream.ts openAiStream 移植）：POST `{server}{endpoint}` 流式 body，
 * 逐行按 SSE 规范取 `data:` 载荷（多行 data 以 \n 连接，注释/其它字段忽略），经 [parseStreamData]
 * 分发。无 okhttp-sse 依赖，OkHttp 手写。
 *
 * 终止纪律（坑 4，RN :41-51 注释）：`data.error` / `type=ERROR` → Error；`type=FINISH|DONE` →
 * Finished——**立即停读并关闭**，不等服务器关流（RN 靠 terminate 单次化防 _pollAgain 重连，
 * OkHttp 天然无重连坑，停读纪律保留）。读到 EOF（服务器关流）同样 → Finished（RN close 事件）。
 *
 * 取消语义（结构化并发）：阻塞读跑在 IO 读协程上，收集协程取消时 finally `call.cancel()` 立刻
 * 中断读并关闭底层连接——事件面表现为正常取消（不发 Error）。
 *
 * T7 用法：`aiStreamClient.stream(request).collect { event -> ... }`，在串行驱动协程内顺序消费。
 */
class AiStreamClient(private val client: OkHttpClient = RocketHttp.client) {

    /** SSE chunk 间隔无固定节奏：派生 client 清零读超时（连接池/调度器仍与共享单例共享）。 */
    private val sseClient: OkHttpClient =
        if (client.readTimeoutMillis == 0) client
        else client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    fun stream(request: AiStreamRequest): Flow<AiStreamEvent> = channelFlow {
        val call = sseClient.newCall(buildRequest(request))
        val reader = launch(Dispatchers.IO) { readLoop(call) { send(it) } }
        try {
            reader.join()
        } finally {
            // 取消/异常收尾：立刻中断阻塞读并关闭连接
            call.cancel()
        }
    }

    private suspend fun readLoop(call: Call, emit: suspend (AiStreamEvent) -> Unit) {
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    emit(AiStreamEvent.Error(NETWORK_ERROR))
                    return
                }
                val source = resp.body.source()
                var dataLines: MutableList<String>? = null
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    when {
                        line.isEmpty() -> {
                            val pending = dataLines
                            dataLines = null
                            if (pending != null && !dispatchChunk(pending.joinToString("\n"), emit)) return
                        }
                        // 注释行（:heartbeat 等）与 event:/id:/retry: 字段忽略
                        line.startsWith(":") -> Unit
                        line.startsWith("data:") ->
                            (dataLines ?: mutableListOf<String>().also { dataLines = it })
                                .add(line.substring("data:".length).removePrefix(" "))
                        else -> Unit
                    }
                }
                val pending = dataLines
                if (pending != null && !dispatchChunk(pending.joinToString("\n"), emit)) return
                emit(AiStreamEvent.Finished)
            }
        } catch (e: IOException) {
            // 取消路径（finally call.cancel() 打断阻塞读）：静默退出，不误报 network
            if (call.isCanceled()) return
            emit(AiStreamEvent.Error(NETWORK_ERROR))
        }
    }

    /** RN :62-77 事件分发；返回 false 表示流已终止（调用方停读）。 */
    private suspend fun dispatchChunk(
        payload: String,
        emit: suspend (AiStreamEvent) -> Unit,
    ): Boolean {
        val data = parseStreamData(payload) ?: return true
        if (!data.error.isNullOrEmpty()) {
            emit(AiStreamEvent.Error(data.error))
            return false
        }
        when (data.type) {
            "ERROR" -> {
                emit(AiStreamEvent.Error(data.error ?: AI_ERROR))
                return false
            }
            "FINISH", "DONE" -> {
                emit(AiStreamEvent.Finished)
                return false
            }
        }
        val text = data.text ?: data.content ?: data.raw
        if (!text.isNullOrEmpty()) emit(AiStreamEvent.Text(text))
        return true
    }

    private fun buildRequest(request: AiStreamRequest): Request {
        val body = buildJsonObject {
            put("prompt", request.prompt)
            put("stream", true)
            put("rid", request.rid)
            request.agentUserId?.takeIf { it.isNotEmpty() }?.let { put("agentUserId", it) }
        }.toString()
        return Request.Builder()
            .url(ServerUrl.normalizeServer(request.server) + request.endpoint)
            .header("x-auth-token", request.token)
            .header("x-user-id", request.userId)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
    }
}
