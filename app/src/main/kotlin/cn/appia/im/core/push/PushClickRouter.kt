package cn.appia.im.core.push

import android.util.Log
import cn.appia.im.core.datastore.AuthSessionStore
import cn.appia.im.core.datastore.MmkvKvStore
import com.tencent.mmkv.MMKV
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val TAG = "push:payload"

private fun normalizeServerHost(value: String): String =
    value.trim().trimEnd('/').lowercase()

/** RN hostnameOf :156-163：补协议后取 URL hostname，失败回落规范化字符串。 */
private fun hostnameOf(value: String): String = runCatching {
    val withScheme = if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
        value
    } else {
        "https://$value"
    }
    java.net.URI(withScheme).host?.lowercase() ?: normalizeServerHost(value)
}.getOrDefault(normalizeServerHost(value))

/**
 * 推送 host 与当前登录服务器是否一致（RN pushNavigation.ts:166-176：比 hostname，
 * 忽略协议/尾斜杠/子域后缀）。host 或 server 缺失视为匹配（无法校验 = 放行）。
 * 多组织防护（坑 6）：他服推送点击不得进房。
 */
fun isPushHostMatchingCurrentServer(host: String?, currentServer: String?): Boolean {
    if (host.isNullOrBlank() || currentServer.isNullOrBlank()) {
        return true
    }
    val a = hostnameOf(host)
    val b = hostnameOf(currentServer)
    return a == b || a.endsWith(".$b") || b.endsWith(".$a")
}

/**
 * 推送点击路由（RN pushNavigation.ts handleNotificationOpen :241-277 移植）。
 *
 * 流程：raw payload → PushPayloadParser → host 校验（不匹配静默跳过）→
 * oncall 语音意图桩（识别即拦截，语音分流 M10 接管；isCallEnd 落到房间）→
 * 入 PendingPushNavigation（导航就绪与否由 T5 drain 门控，本路由不导航）。
 */
class PushClickRouter(
    private val queue: PendingPushNavigation,
    private val currentServerProvider: () -> String?,
) {

    /**
     * Android 点击事件入口。`extra` 为阿里云 extraMap JSON 字符串（即 RN 事件的 `extra` 字段，
     * 形如 `{"ejson":"{...}"}`）；组装为 RN 事件同形 raw 后解析。
     */
    fun onNotificationOpened(title: String, summary: String, extra: String?) {
        val raw: JsonObject = buildJsonObject {
            put("title", JsonPrimitive(title))
            put("summary", JsonPrimitive(summary))
            if (extra != null) put("extra", JsonPrimitive(extra))
        }

        val params = runCatching { PushPayloadParser.parsePushPayload(raw) }.getOrNull()
        if (params == null) {
            Log.w(TAG, "opened but parsePushPayload returned null title=$title summary=$summary hasExtra=${extra != null}")
            return
        }

        val currentServer = runCatching { currentServerProvider() }.getOrNull()
        if (!isPushHostMatchingCurrentServer(params.host, currentServer)) {
            Log.w(TAG, "opened host mismatch, skip room jump pushHost=${params.host} currentServer=$currentServer rid=${params.rid}")
            return
        }

        // oncall 桩：通话中语音意图不进房（RN :262-265 走 routeToVoiceSession），isCallEnd 落到房间
        val voice = params.voiceOncall
        if (voice != null && !voice.isCallEnd) {
            Log.i(TAG, "oncall intent deferred to M10 voice routing rid=${params.rid} recordId=${voice.recordId}")
            return
        }

        Log.i(TAG, "open → pending room navigation rid=${params.rid} t=${params.t} messageId=${params.messageId} pending=${queue.pendingCount}")
        queue.enqueue(rid = params.rid, t = params.t, title = params.title, messageId = params.messageId)
    }

    companion object {
        /**
         * 进程级共享实例：receiver 由阿里云 SDK 反射实例化（无 Hilt 注入点）。
         * currentServer 走 AuthSessionStore（与 Hilt 侧同一 default MMKV 实例，读回登录态 serverUrl）。
         */
        val shared: PushClickRouter by lazy {
            PushClickRouter(
                queue = PendingPushNavigation.shared,
                currentServerProvider = {
                    AuthSessionStore(MmkvKvStore(MMKV.defaultMMKV())).load()?.serverUrl
                },
            )
        }
    }
}
