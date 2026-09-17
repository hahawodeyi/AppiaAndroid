package cn.appia.im.domain.session

import android.util.Log
import cn.appia.im.core.database.AppiaDatabase
import cn.appia.im.core.database.DatabaseManager
import cn.appia.im.domain.chat.ChatMerger
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * `stream-notify-user` 持久化（RN notifyUserPersistence.ts 全文件语义）：
 * - `subscriptions-changed` `removed`：物理删 `chats` 行（RN removeChatByRid :65-87 destroyPermanently）
 * - `subscriptions-changed` 其余类型：入队（RN queueSubscriptionPatch :39-46）
 * - `rooms-changed` `updated`/`inserted`：入队（RN queueRoomPatch :48-63）
 * - 同 rid 的 sub/room 补丁合并，[flushMs]（RN FLUSH_MS=500 :17）窗口后经 M1 ChatMerger
 *   （mergeSubscriptionAndRoom 等价）一次 upsert 进 `chats`（RN flushPendingToDatabase :89-144）
 * - `userData` → preferences（RN applyUserDataDiffToAuthStore :168-177）归 M5 占位
 *
 * 守卫（RN :72-74/:94-99 activeDbMatchesAuth）：active 库必须就是 auth server 对应的库。
 * 房间流退订（RN :85 unsubscribeRoomStreams）经 [unsubscribeRoom] 缝接 T6 RoomStreamManager；
 * 访问丢失提示（RN :86 notifyRoomAccessLost）归 T11；callMsg 语音同步（RN :55-61/:141-143）归 M6。
 *
 * 线程契约：[handleStreamNotifyUser] 由 RealtimeSessionManager 在 DDP IO 线程调用；
 * 删行/flush 在 [scope] 内异步执行（RN `.catch(() => undefined)` 同义），DB 写不触 UI。
 */
class NotifyUserPersistence(
    private val dbManager: DatabaseManager,
    /** 调用时现读 auth serverUrl（RN 从 authStore 现读；组织切换后 handler 不换实例）。 */
    private val serverUrlProvider: () -> String,
    private val scope: CoroutineScope,
    /** 合并窗口毫秒（RN FLUSH_MS :17）；测试注入短窗口。 */
    private val flushMs: Long = FLUSH_MS,
    /** RN :85 unsubscribeRoomStreams：removed 后退订该房间流（DI 接 RoomStreamManager）。 */
    private val unsubscribeRoom: suspend (String) -> Unit = {},
) {

    /** RN Pending :19：同 rid 的 sub/room 补丁合并载体（同字段后者覆盖）。@Volatile：flush 摘除与 handler 写入并发。 */
    private class Pending {
        @Volatile
        var sub: JsonObject? = null

        @Volatile
        var room: JsonObject? = null
    }

    private val pending = ConcurrentHashMap<String, Pending>()
    private val flushLock = Any()

    @Volatile
    private var flushJob: Job? = null

    /** RN handleStreamNotifyUserForPersistence :151-203：帧解析 + removed/入队分派。 */
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

            if (USER_DATA.containsMatchIn(ev)) {
                // RN :168-177 applyUserDataDiffToAuthStore(diff, unset)——userData→preferences 归 M5
                return
            }

            if (args.size < 2) return // RN :179
            val type = (args[0] as? JsonPrimitive)?.contentOrNull
            val data = args[1]

            if (SUBSCRIPTIONS.containsMatchIn(ev)) { // RN :184-193
                if (type == "removed" && data is JsonObject) {
                    val rid = data.str("rid")
                    if (!rid.isNullOrEmpty()) {
                        // RN :66 pending.delete 在 handler 同步段（async fn 首个 await 前同步执行）；
                        // Kotlin launch 整体延迟派发，撤销必须留在本同步段——否则同 rid 先入队补丁
                        // 后 removed 时，删除协程里的撤销晚了，flush 窗口可复活该行（总纲 §4.3-4）
                        pending.remove(rid)
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
                queueSubscriptionPatch(data)
            }

            if (ROOMS.containsMatchIn(ev)) { // RN :195-199
                if (type == "updated" || type == "inserted") {
                    queueRoomPatch(data)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "notify-user handler", e)
        }
    }

    /** RN queueSubscriptionPatch :39-46：按 `rid` 入队（无 rid 丢弃），同字段后者覆盖。 */
    private fun queueSubscriptionPatch(data: JsonElement) {
        val obj = data as? JsonObject ?: return
        val rid = obj.str("rid")?.takeIf { it.isNotEmpty() } ?: return
        pending.getOrPut(rid) { Pending() }.sub = obj
        scheduleFlush()
    }

    /** RN queueRoomPatch :48-63：按 `_id` 入队；callMsg 即时同步归 M6（语音域）。 */
    private fun queueRoomPatch(data: JsonElement) {
        val obj = data as? JsonObject ?: return
        val rid = obj.str("_id")?.takeIf { it.isNotEmpty() } ?: return
        pending.getOrPut(rid) { Pending() }.room = obj
        scheduleFlush()
    }

    /** RN scheduleFlush :31-37：单一定时器——已有排程则并入同一窗口。 */
    private fun scheduleFlush() {
        if (flushJob != null) return
        synchronized(flushLock) {
            if (flushJob != null) return
            flushJob = scope.launch {
                delay(flushMs)
                flushJob = null // RN :34 先清定时器引用再落库：flush 期间新补丁排新窗口
                try {
                    flushPendingToDatabase()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // RN :35 .catch(() => {})
                }
            }
        }
    }

    /** RN clearNotifyUserPersistenceQueue :23-29：撤销排程并清队（teardown 挂点）。 */
    fun clearQueue() {
        synchronized(flushLock) {
            flushJob?.cancel()
            flushJob = null
        }
        pending.clear()
    }

    /**
     * RN flushPendingToDatabase :89-144：取批清队 → activeDbMatchesAuth 守卫 →
     * 逐 rid 合并（sub 缺省 `{rid}` 占位，RN :114）→ find/update、create 等价 upsert（单事务）。
     * 列级无变化不写（RoomsSyncRepository mergedRowNeedsUpdate 同口径）；callMsg 语音同步归 M6。
     */
    suspend fun flushPendingToDatabase() {
        // 逐 key 原子摘除（copy+clear 会吞掉摘除间隙落进来的新补丁）：
        // remove 返回 null = 已被 removed 分支撤销；摘除后新到补丁留待下一窗口
        val batch = LinkedHashMap<String, Pending>()
        for (rid in pending.keys) {
            pending.remove(rid)?.let { batch[rid] = it }
        }
        if (batch.isEmpty()) return

        val db = activeDbForAuth() ?: run {
            Log.w(TAG, "skip notify-user flush: active db != auth serverUrl") // RN :94-99
            return
        }

        val dao = db.chatDao()
        db.withTransaction {
            for ((rid, p) in batch) {
                if (p.sub == null && p.room == null) continue // RN :111
                try {
                    val merged = ChatMerger.merge(
                        p.sub ?: JsonObject(mapOf("rid" to JsonPrimitive(rid))), // RN :114 `sub ?? {rid}`
                        p.room,
                    )
                    val prev = dao.getById(merged._id)
                    val next = ChatMerger.applyMergedChatFields(prev, merged)
                    if (next == prev) continue // 列级 diff：无变化不写
                    if (prev == null) dao.insert(next) else dao.update(next)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "notify-user persist failed", e) // RN :135-137 逐行 try/catch
                }
            }
        }
    }

    /**
     * RN removeChatByRid :65-87 的异步半程（撤销补丁已前移至 [handleStreamNotifyUser] 同步段，
     * RN :66 首 await 前语义）→ 守卫 → 物理删 chats 行 → 退订房间流（:85）。
     * Room `deleteById` 行不存在时 no-op（RN find 失败 catch 同义）。
     * T11: notifyRoomAccessLost —— RN :86；其提示文案需 payload 的 `t`，届时透传整包。
     */
    suspend fun removeChatByRid(rid: String) {
        val db = activeDbForAuth() ?: return // RN :72-74 activeDbMatchesAuth
        db.chatDao().deleteById(rid)
        runCatching { unsubscribeRoom(rid) } // RN :85 .catch(() => undefined)
    }

    /** activeDbMatchesAuth（RN db.ts 同名）：auth server 对应的库且它就是 active；否则 null。 */
    private fun activeDbForAuth(): AppiaDatabase? {
        val serverUrl = serverUrlProvider()
        if (serverUrl.isBlank()) return null
        val target = dbManager.databaseFor(dbManager.normalizeServer(serverUrl))
        return if (dbManager.active === target) target else null
    }

    companion object {
        private const val TAG = "notifyUser"

        /** RN notifyUserPersistence.ts:17 WINDOW_TIME。 */
        const val FLUSH_MS = 500L

        /** RN `/subscriptions/.test(ev)` / `/rooms/` / `/userData/`。 */
        private val SUBSCRIPTIONS = Regex("subscriptions")
        private val ROOMS = Regex("rooms")
        private val USER_DATA = Regex("userData")
    }
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
