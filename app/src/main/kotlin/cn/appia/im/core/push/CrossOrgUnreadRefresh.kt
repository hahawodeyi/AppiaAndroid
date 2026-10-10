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

/** 阿里云到达回调 extraMap → RN 事件同形 raw 袋（键值原样字符串化，兜底解析归 PushPayloadParser）。 */
internal fun extraMapToRaw(extraMap: Map<String, String>): JsonObject = buildJsonObject {
    for ((k, v) in extraMap) put(k, JsonPrimitive(v))
}

/**
 * 推送到达跨组织未读刷新（RN crossOrgUnreadRefresh.ts 移植；pushService.ts:122-144
 * onNotification / receivedInApp 到达回调挂点，点击路径不刷新——RN handlePushOpen :116-119 同）：
 * 到达事件解析出 host ≠ 当前登录主体 → 触发多组织未读刷新（RN tabIndicatorStore.refresh，
 * getMyUnread 全组织角标源）。
 *
 * host 比较按 RN 到达路径原样移植 [isSameOrgHost]（:7-14 规范化后互为包含；RN 对照测试
 * crossOrgUnreadRefresh.test.ts 断言 appia.cn ≠ ssc.appia.cn 刷角标）——与点击路径
 * isPushHostMatchingCurrentServer 的「子域后缀=同主体」判然有别：父/子域在到达路径按
 * RN 语义视为异主体；漏刷代价仅角标暂旧，宁宽勿漏（RN fail-open 同向）。
 *
 * AA 未读数据面（tab indicator / unread.list API）尚未建：刷新动作为可注册缝 [action]，
 * 未注册仅日志（backlog #9 链路已接，sink 归多组织角标特性落地时接线）。
 * receiver 由 SDK 反射实例化无 DI（PushClickRouter.shared 同款单例），当前主体走 default MMKV。
 */
object CrossOrgUnreadRefresh {

    /** 未读刷新动作（多组织角标 store 注册点）；null = 数据面未接入，仅日志。 */
    @Volatile
    var action: (() -> Unit)? = null

    /** receiver 到达回调入口（onNotification / onNotificationReceivedInApp 同径）。 */
    fun routeFromExtraMap(extraMap: Map<String, String>) {
        route(extraMapToRaw(extraMap)) {
            AuthSessionStore(MmkvKvStore(MMKV.defaultMMKV())).load()?.serverUrl
        }
    }

    /**
     * RN routeCrossOrgUnreadFromPushEvent :28-36 + maybeRefreshCrossOrgUnreadFromPush :16-26：
     * 解析失败静默（不抛）；host/当前主体缺失或同主体 → 跳过；否则触发 [action]（失败吞，
     * 刷新永不阻断推送回调）。
     */
    fun route(raw: JsonObject, currentServerProvider: () -> String?) {
        val params = runCatching { PushPayloadParser.parsePushPayload(raw) }.getOrNull() ?: return
        val host = params.host?.trim().orEmpty()
        val current = runCatching { currentServerProvider() }.getOrNull().orEmpty()
        if (host.isEmpty() || current.isEmpty()) return
        if (isSameOrgHost(host, current)) return
        Log.i(TAG, "cross-org push arrival → refresh unread pushHost=$host currentServer=$current")
        val sink = action
        if (sink == null) {
            Log.i(TAG, "unread refresh sink not registered, skip")
            return
        }
        runCatching { sink() }.onFailure { Log.w(TAG, "unread refresh action failed", it) }
    }

    /** RN isSameHost :7-14：去全部尾斜杠 + 小写后互为包含（协议原样参与比较，到达路径专用宽匹配）。 */
    internal fun isSameOrgHost(pushHost: String, currentServer: String): Boolean {
        val h = pushHost.trimEnd('/').lowercase()
        val s = currentServer.trimEnd('/').lowercase()
        if (h.isEmpty() || s.isEmpty()) return false
        return h.contains(s) || s.contains(h)
    }
}
