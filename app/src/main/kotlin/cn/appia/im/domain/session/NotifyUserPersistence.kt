package cn.appia.im.domain.session

import android.util.Log
import cn.appia.im.core.database.DatabaseManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * `stream-notify-user` 持久化（RN notifyUserPersistence.ts:151-203 的 T4 最小切片）：
 * 只处理 `subscriptions-changed` 的 `removed` 事件——物理删 `chats` 行
 * （RN removeChatByRid :65-87 destroyPermanently 语义）。
 *
 * - 守卫（RN :72-74 activeDbMatchesAuth）：active 库必须就是 auth server 对应的库；不匹配不删。
 * - 房间流退订（RN :85 unsubscribeRoomStreams）→ T6 RoomStreamManager 接。
 * - 访问丢失提示（RN :86 notifyRoomAccessLost，正在房间内则退到列表）→ T11 接。
 * - `subscriptions-changed` updates 的 500ms 合并落库（RN queueSubscriptionPatch/flush）不在本切片：
 *   现网列表数据以 REST `subscriptions.get` 同步为准（RoomsSyncRepository），接回推合并归后续任务。
 *
 * 线程契约：`handleStreamNotifyUser` 由 RealtimeSessionManager 在 DDP IO 线程调用；
 * 本类内部 fire-and-forget（RN :188 `.catch(() => undefined)` 同义），DB 写不触 UI。
 */
class NotifyUserPersistence(
    private val dbManager: DatabaseManager,
    /** 调用时现读 auth serverUrl（RN 从 authStore 现读；组织切换后 handler 不换实例）。 */
    private val serverUrlProvider: () -> String,
    private val scope: CoroutineScope,
) {

    /** RN handleStreamNotifyUserForPersistence :151-203：帧解析 + removed 分派。 */
    fun handleStreamNotifyUser(ddpMessage: JsonElement) {
        try {
            val m = ddpMessage as? JsonObject ?: return
            if (m.str("msg") == "added") return // RN :157

            val fields = m["fields"] as? JsonObject ?: return
            val args = fields["args"] as? JsonArray ?: return
            if (args.isEmpty()) return

            val eventName = fields.str("eventName") ?: return
            if (!eventName.contains('/')) return // RN :163
            val ev = eventName.split('/').drop(1).joinToString("/")
            if (ev.isEmpty()) return // RN :165

            if (!SUBSCRIPTIONS_CHANGED.containsMatchIn(ev)) return // userData/rooms 等不触删行

            if (args.size < 2) return // RN :179
            val type = (args[0] as? JsonPrimitive)?.contentOrNull ?: return
            val data = args[1] as? JsonObject ?: return

            if (type == "removed") {
                val rid = data.str("rid")
                if (!rid.isNullOrEmpty()) {
                    scope.launch {
                        try {
                            removeChatByRid(rid)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "notify-user remove failed", e)
                        }
                    }
                }
                return
            }
            // updates 分支：见类 KDoc（500ms 合并落库不在 T4 切片）
        } catch (e: Exception) {
            Log.w(TAG, "notify-user handler", e)
        }
    }

    /**
     * RN removeChatByRid :65-87：守卫 → 物理删 chats 行。
     * Room `deleteById` 行不存在时为 no-op（RN find 失败 catch 同义）。
     */
    suspend fun removeChatByRid(rid: String) {
        val serverUrl = serverUrlProvider()
        if (serverUrl.isBlank()) return
        val target = dbManager.databaseFor(dbManager.normalizeServer(serverUrl))
        if (dbManager.active !== target) return // RN :72-74 activeDbMatchesAuth
        target.chatDao().deleteById(rid)
        // T6: unsubscribeRoomStreams(rid) —— RN :85
        // T11: notifyRoomAccessLost(rid) —— RN :86
    }

    companion object {
        private const val TAG = "notifyUser"

        /** RN `/subscriptions/.test(ev)`：事件名含 subscriptions（`{uid}/subscriptions-changed`）。 */
        private val SUBSCRIPTIONS_CHANGED = Regex("subscriptions")
    }
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
