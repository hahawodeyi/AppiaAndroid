package cn.appia.im.core.messaging

import androidx.room.withTransaction
import cn.appia.im.core.database.AppiaDatabase
import cn.appia.im.core.database.DatabaseManager
import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.datastore.AuthSessionStore
import cn.appia.im.core.messaging.MessageStatus.ERROR
import cn.appia.im.core.messaging.MessageStatus.QUEUED
import cn.appia.im.core.messaging.MessageStatus.SENDING
import cn.appia.im.core.messaging.MessageStatus.SENT
import cn.appia.im.core.network.RocketSdk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * RN `src/utils/randomId.ts` randomMessageId：17 位字母数字（对齐旧版 iOS `random(17)`），
 * 作本地消息主键 + chat.sendMessage 的 `message._id` 幂等键。非 UUID（RN 原样转录）。
 */
internal fun randomMessageId(length: Int = 17): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    return buildString { repeat(length) { append(alphabet[Random.nextInt(alphabet.length)]) } }
}

/** RN SendOrchestrator.ts:54-58 CurrentUser（u 列 JSON 源）。 */
data class CurrentUser(val _id: String, val username: String, val name: String? = null)

/** 队列作业（text 面；文件消息走后续任务）。 */
private data class SendJob(val id: String, val rid: String, val msg: String)

/**
 * 发送状态机（逐行为移植 appiaMobile/src/services/messages/SendOrchestrator.ts 的 text 面 +
 * retryPolicy.ts）：
 * - [enqueueTextMessage]：randomMessageId tempId → messages 表 create QUEUED 行 → per-rid 串行
 *   队列 → dequeue 发送（行先落库再入队：SENDING 必在行可见之后）；
 * - sendOne：QUEUED→SENDING→`POST chat.sendMessage`（md 省略）→ serverId 迁移 → SENT；
 * - 失败：`success:false` / 4xx 直败 ERROR；IOException/超时/5xx 退避 1s/2s/4s 重试
 *   （RoomHistoryRepository.isRetryableError = RN retryPolicy.isRetryableError 同语义，
 *   401 会话失效不重试），MAX_ATTEMPTS 次后 ERROR；
 * - [resend]：ERROR 行点击重发，同 id 复用原 msg 作为新 job 推队；
 * - status 只许本类写（MessageUpsert 沿用 prev.status，T6 已钉）。
 */
class SendOrchestrator(
    private val db: AppiaDatabase,
    private val sdk: RocketSdk,
    private val currentUser: CurrentUser,
    /** 队列消费 scope（生产注入 @BackgroundScope；RN setTimeout fire-and-forget 的等价通道）。 */
    private val scope: CoroutineScope,
    /** 退避睡眠缝（测试注入免真实等待；生产 = delay）。 */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    /** Date.now() 缝（测试注入固定时钟，ts / `_updated_at` 可精确断言）。 */
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /** per-rid 串行队列：每 rid 一个 UNLIMITED Channel + 单消费协程（RN pendingByRid+inflight 等价）。 */
    private val queues = ConcurrentHashMap<String, Channel<SendJob>>()
    private val consumers = ConcurrentHashMap<String, Job>()

    /**
     * RN enqueueTextMessage :90-126：tempId → messages 表 create QUEUED 行
     * （rid/msg/ts=now/u=当前用户 JSON/mentions '[]'/alias ''/parseUrls '[]'，其余可空列落 null
     * ——Watermelon 对 isOptional 列 '' 归一 null 的等价）→ per-rid 队列 → dequeue。
     * @return tempId（= 行 `_id` = wire `message._id` 幂等键）
     */
    suspend fun enqueueTextMessage(rid: String, msg: String): String {
        val tempId = randomMessageId()
        val now = nowMs()
        db.messageDao().insert(
            MessageEntity(
                _id = tempId,
                msg = msg,
                rid = rid,
                ts = now.toDouble(),
                u = currentUserJson(),
                alias = "",
                parse_urls = "[]",
                _updated_at = now.toDouble(),
                status = QUEUED.toDouble(),
                mentions = "[]",
            ),
        )
        dispatch(SendJob(id = tempId, rid = rid, msg = msg))
        return tempId
    }

    /**
     * RN resend :189-206：ERROR 行点击重发——同 id 复用原 msg 作为新 job 推队
     * （sendOne 重走 SENDING 状态机；不读库，rid/msg 由调用方从行内取，同 RN snapshot 参数）。
     */
    fun resend(id: String, rid: String, msg: String) {
        dispatch(SendJob(id = id, rid = rid, msg = msg))
    }

    /** 入队 + 确保该 rid 的消费协程在跑（Channel 缓冲保证先入先发，单消费者保证串行）。 */
    private fun dispatch(job: SendJob) {
        val ch = queues.getOrPut(job.rid) { Channel(Channel.UNLIMITED) }
        ch.trySend(job)
        consumers.getOrPut(job.rid) {
            scope.launch { for (next in ch) sendOne(next) }
        }
    }

    /** RN sendOneText :276-315：标 SENDING → POST → serverId 迁移/SENT；拒绝与不可重试直败。 */
    private suspend fun sendOne(job: SendJob) {
        markStatus(job.id, SENDING)
        var attempt = 0
        while (true) {
            try {
                val obj = MessagesApi.sendTextMessage(sdk, job.rid, job.msg, job.id) as? JsonObject
                // RN :288-292 success:false → 业务拒绝直败
                if ((obj?.get("success") as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == false) {
                    markStatus(job.id, ERROR)
                    return
                }
                // RN :294-303：部分服务端忽略客户端 _id 返回自己的 _id → 迁移防 DDP echo 双条
                val serverId = (obj?.get("message") as? JsonObject)
                    ?.get("_id")?.let { it as? JsonPrimitive }
                    ?.takeIf { it.isString }?.content
                if (serverId != null && serverId != job.id) {
                    migrateToServerId(job.id, serverId)
                    markStatus(serverId, SENT)
                } else {
                    markStatus(job.id, SENT)
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!RoomHistoryRepository.isRetryableError(e) || attempt >= MAX_ATTEMPTS) {
                    markStatus(job.id, ERROR)
                    return
                }
                sleep((1L shl attempt) * 1_000) // RN getBackoffMs：attempt 0→1s, 1→2s, 2→4s
                attempt += 1
            }
        }
    }

    /**
     * RN migrateToServerId :509-544：tempId 行迁到服务端 `_id`，防 DDP echo 双条。单事务两步：
     * - serverId 行已存在（DDP echo 先到，T6 upsert 建、不触 status）：复制 status 过去，删 temp 行
     *   ——迁移负责把状态带到 serverId 行；
     * - 否则：temp 行全字段迁建 serverId 行（`_updated_at` 刷新），删 temp 行。
     */
    private suspend fun migrateToServerId(tempId: String, serverId: String) {
        db.withTransaction {
            val dao = db.messageDao()
            val tempRow = dao.getById(tempId) ?: return@withTransaction
            val existing = dao.getById(serverId)
            if (existing != null) {
                dao.update(existing.copy(status = tempRow.status))
            } else {
                dao.insert(tempRow.copy(_id = serverId, _updated_at = nowMs().toDouble()))
            }
            dao.delete(tempRow)
        }
    }

    /** RN markStatus :546-553。messages.status 仅本类可写。 */
    private suspend fun markStatus(id: String, status: Int) {
        val dao = db.messageDao()
        val row = dao.getById(id) ?: return
        dao.update(row.copy(status = status.toDouble()))
    }

    /** RN `rec.u = JSON.stringify(this.currentUser)`：name 缺省时 JSON.stringify 丢弃 undefined 键。 */
    private fun currentUserJson(): String = buildJsonObject {
        put("_id", currentUser._id)
        put("username", currentUser.username)
        currentUser.name?.let { put("name", it) }
    }.toString()

    /**
     * 用户/组织切换重建的前半步（RN 单例整体丢弃的等价清理）：关全部 rid 队列——消费协程随
     * channel 关闭自然退出，在途 sendOne 跑完为止。由 [resetSendOrchestrator] 调用（挂点 T11）。
     */
    fun shutdown() {
        queues.values.forEach { it.close() }
        queues.clear()
        consumers.clear()
    }

    companion object {
        /** RN messages/retryPolicy.ts MAX_ATTEMPTS：首次 + 3 次重试，退避 1s/2s/4s。 */
        const val MAX_ATTEMPTS = 3
    }
}

// ---- RN SendOrchestrator.ts:556-575 单例持有 ----

@Volatile
private var singleton: SendOrchestrator? = null
private val singletonLock = Any()

/**
 * RN getSendOrchestrator :562-571：取全局单例。db 取 manager.active（getActiveDatabase 等价），
 * 用户取持久化会话（authStore 等价），未登录兜底 anonymous。装配挂点 T11。
 */
fun getSendOrchestrator(
    dbManager: DatabaseManager,
    sdk: RocketSdk,
    store: AuthSessionStore,
    scope: CoroutineScope,
): SendOrchestrator =
    singleton ?: synchronized(singletonLock) {
        singleton ?: run {
            val currentUser = store.load()?.user
                ?.let { CurrentUser(_id = it.id, username = it.username, name = it.name) }
                ?: CurrentUser(_id = "anonymous", username = "anonymous")
            SendOrchestrator(dbManager.active, sdk, currentUser, scope).also { singleton = it }
        }
    }

/** RN resetSendOrchestrator :573-575：用户/组织切换重建（M1 resetSendOrchestrator 等价；挂点 T11）。 */
fun resetSendOrchestrator() {
    synchronized(singletonLock) {
        singleton?.shutdown()
        singleton = null
    }
}
