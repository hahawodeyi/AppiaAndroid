package cn.appia.im.core.push

import android.util.Log
import cn.appia.im.core.datastore.AuthSessionStore
import cn.appia.im.core.datastore.MmkvKvStore
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
 * oncall 语音意图分流（RN :262-265 routeToVoiceSession；isCallEnd 落到房间）→
 * 入 PendingPushNavigation（导航就绪与否由 T5 drain 门控，本路由不导航）。
 */
class PushClickRouter(
    private val queue: PendingPushNavigation,
    private val currentServerProvider: () -> String?,
    /**
     * 入队后幂等 drain 提示（终审 I-1 / RN pushNavigation.ts:241-277 nav 就绪即派发——入队只是
     * 未就绪兜底）：App 已在前台时点托盘通知，SDK 回调不引起生命周期转换，三 drain 点全不触发，
     * 意图会坐满 90s TTL。默认推 shared.onNavReady——nav 未接线时其内部门控（navReady/navigate/
     * auth）使 drain 安全 no-op，意图留队列待接线点冲刷。
     */
    private val afterEnqueue: () -> Unit = { PushNavigationDrainer.shared.onNavReady() },
    /**
     * oncall 语音意图出口（RN :262-265 `void routeToVoiceSession(params)` →
     * voicePushIngress）。构造纯净：默认 no-op（语音分流静默丢失可接受——CallSyncWatcher
     * 与轮询是主被叫同步主路径）；MainActivity 装配接线 VoicePushIngress.handle（Task 12）。
     */
    private val onVoiceOncall: suspend (rid: String, voice: PushVoiceOncallParams, host: String?) -> Unit = { _, _, _ -> },
) {

    /**
     * 语音分流的 fire-and-forget 载体（RN :263 `void routeToVoiceSession(params)`）：
     * onNotificationOpened 非 suspend,ingress 链含网络拉取,丢协程不阻塞点击回调。
     * ponytail: 进程级 scope（无生命周期宿主——receiver 反射实例化）;挂起回调自身有
     * 队列重试兜底,泄漏上限 = 单次 ingress 链。
     */
    private val voiceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

        // oncall 分流（RN :262-265）：通话中语音意图不进房，交语音分流；isCallEnd 落到房间
        val voice = params.voiceOncall
        if (voice != null && !voice.isCallEnd) {
            Log.i(TAG, "oncall intent → voice ingress rid=${params.rid} recordId=${voice.recordId}")
            voiceScope.launch {
                runCatching { onVoiceOncall(params.rid, voice, params.host) }
                    .onFailure { Log.w(TAG, "voice ingress failed rid=${params.rid}: $it") }
            }
            return
        }

        Log.i(TAG, "open → pending room navigation rid=${params.rid} t=${params.t} messageId=${params.messageId} pending=${queue.pendingCount}")
        queue.enqueue(rid = params.rid, t = params.t, title = params.title, messageId = params.messageId)
        afterEnqueue() // 前台点击即时派发（终审 I-1）；未就绪场景由内部门控兜底为留队
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
