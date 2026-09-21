package cn.appia.im.core.messaging

import androidx.room.withTransaction
import cn.appia.im.core.database.AppiaDatabase
import cn.appia.im.core.database.DatabaseManager
import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.datastore.AuthSessionStore
import cn.appia.im.core.media.FileUploadProgress
import cn.appia.im.core.media.LocalFileInput
import cn.appia.im.core.media.UploadApi
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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

/** LocalAttachment.uploadStatus 三态（RN :49 字面量）。 */
internal const val UPLOAD_PENDING = "pending"
internal const val UPLOAD_UPLOADED = "uploaded"
internal const val UPLOAD_FAILED = "failed"

/** attachments 列编解码：RN JSON.stringify 丢 undefined 键 → explicitNulls=false 同形。 */
private val attachmentJson = Json { explicitNulls = false }

/**
 * RN SendOrchestrator.ts:43 LocalAttachment：messages.attachments 列的本地形状 JSON。
 * 注意：**仅入队行与失败路径写入**；上传成功后服务端 DDP 回推的 attachments（含 title_link 等）
 * 经 MessageUpsert 覆盖本列才是渲染源（isLocalAttachmentShape 判定据此回落本地图）。
 */
@Serializable
data class LocalAttachment(
    val id: String? = null,
    val name: String,
    val type: String,
    val size: Long? = null,
    val localPath: String,
    val sourceUri: String? = null,
    val uploadStatus: String, // pending | uploaded | failed
    val fileId: String? = null,
)

/** 队列作业（text + file 双面，RN SendJob :66-74）。 */
private data class SendJob(
    val id: String,
    val rid: String,
    val msg: String,
    val md: JsonElement? = null,
    /** file 消息的 LocalAttachment JSON 数组；null = text 作业。 */
    val attachments: String? = null,
    /** multiAttachments append 重试：已存在的服务端消息 _id（RN serverMessageId）。 */
    val serverMessageId: String? = null,
    /** append 重试的目标附件 id（RN retryAttachmentId）。 */
    val retryAttachmentId: String? = null,
)

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

    /** RN :82-83 进程内记忆映射：append 重试定位已建服务端消息 / 服务端 id 反查本地行（测试可注入）。 */
    internal val serverMessageIdByLocalId = ConcurrentHashMap<String, String>()
    internal val attachmentLocalIdByServerId = ConcurrentHashMap<String, String>()

    /**
     * RN enqueueTextMessage :90-126：tempId → messages 表 create QUEUED 行
     * （rid/msg/ts=now/u=当前用户 JSON/mentions '[]'/alias ''/parseUrls '[]'，其余可空列落 null
     * ——Watermelon 对 isOptional 列 '' 归一 null 的等价）→ per-rid 队列 → dequeue。
     * T12：编辑器产出 md（TipTap→AST JSON）随行携带（RN sendTextMessage 同参 md 可选）。
     * @return tempId（= 行 `_id` = wire `message._id` 幂等键）
     */
    suspend fun enqueueTextMessage(rid: String, msg: String, md: JsonElement? = null): String {
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
                md = md?.toString(),
            ),
        )
        dispatch(SendJob(id = tempId, rid = rid, msg = msg, md = md))
        return tempId
    }

    /**
     * RN enqueueFileMessage :128-182：tempId → messages 表 create QUEUED 行
     * （attachments 列存 LocalAttachment JSON，每项 id=randomMessageId、uploadStatus=pending）→
     * emitProgress 初值（totalFiles/completedFiles=0/0）→ per-rid 队列（text 同队串行）。
     * @return tempId
     */
    suspend fun enqueueFileMessage(
        rid: String,
        files: List<LocalFileInput>,
        msg: String? = null,
        md: JsonElement? = null,
    ): String {
        val tempId = randomMessageId()
        val attachmentsJson = encodeAttachments(
            files.map { f ->
                LocalAttachment(
                    id = randomMessageId(),
                    name = f.name,
                    type = f.type,
                    size = f.size,
                    localPath = f.localPath,
                    // RN 同位读 f.sourceUri，而 LocalFileInput 无该键 → undefined 落空（原样转录）
                    uploadStatus = UPLOAD_PENDING,
                )
            },
        )
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
                attachments = attachmentsJson,
                md = md?.toString(),
            ),
        )
        // 进度 keyed by tempId：徽标环形进度数据源（RN emitProgress :170-174）
        FileUploadProgress.emit(
            tempId,
            FileUploadProgress.Data(totalFiles = files.size, completedFiles = 0, currentFileProgress = 0.0),
        )
        dispatch(SendJob(id = tempId, rid = rid, msg = msg.orEmpty(), md = md, attachments = attachmentsJson))
        return tempId
    }

    /**
     * RN resend :189-206：ERROR 行点击重发——同 id 复用原 msg 作为新 job 推队
     * （sendOne 重走 SENDING 状态机；不读库，rid/msg 由调用方从行内取，同 RN snapshot 参数）。
     * 文件行（RN snapshot.attachments 非空 → kind='file'）复用 attachments JSON 列原值。
     */
    fun resend(id: String, rid: String, msg: String, attachments: String? = null, md: JsonElement? = null) {
        dispatch(SendJob(id = id, rid = rid, msg = msg, md = md, attachments = attachments))
    }

    /**
     * RN retryFile :208-245：单附件失败重试——目标项 failed→pending 回 QUEUED 重推队。
     * append 形态（此前 multiAttachments 已建过消息）由 [serverMessageIdByLocalId] 记忆驱动。
     */
    suspend fun retryFile(messageId: String, attachmentId: String) {
        val row = db.messageDao().getById(messageId) ?: return
        val attachments = parseAttachments(row.attachments)
        val key = { a: LocalAttachment -> a.id ?: a.localPath }
        val target = attachments.find { key(it) == attachmentId } ?: return
        if (target.uploadStatus != UPLOAD_FAILED) return
        val updated = attachments.map { if (key(it) == attachmentId) it.copy(uploadStatus = UPLOAD_PENDING) else it }
        val json = encodeAttachments(updated)
        db.messageDao().updateAttachmentsAndStatus(messageId, json, QUEUED.toDouble())
        dispatch(
            SendJob(
                id = messageId,
                rid = row.rid,
                msg = row.msg.orEmpty(),
                md = row.md?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() },
                attachments = json,
                serverMessageId = serverMessageIdByLocalId[messageId],
                retryAttachmentId = attachmentId,
            ),
        )
    }

    /** 入队 + 确保该 rid 的消费协程在跑（Channel 缓冲保证先入先发，单消费者保证串行）。 */
    private fun dispatch(job: SendJob) {
        val ch = queues.getOrPut(job.rid) { Channel(Channel.UNLIMITED) }
        if (!ch.trySend(job).isSuccess) {
            // T8 披露的 shutdown/enqueue 并发窗口：reset 关队后迟到的 enqueue 不再静默丢——
            // 行落 ERROR（UI 可点重发，重发走新单例）；死消费协程句柄一并摘除
            scope.launch { markStatus(job.id, ERROR) }
            consumers.remove(job.rid)
            return
        }
        consumers.getOrPut(job.rid) {
            scope.launch { for (next in ch) sendOne(next) }
        }
    }

    /** RN sendOne :266-274 双面分发：file 作业走 [sendOneFileJob]，否则 text 状态机。 */
    private suspend fun sendOne(job: SendJob) {
        if (job.attachments != null) {
            sendOneFileJob(job)
            return
        }
        sendOneText(job)
    }

    /** RN sendOneText :276-315：标 SENDING → POST → serverId 迁移/SENT；拒绝与不可重试直败。 */
    private suspend fun sendOneText(job: SendJob) {
        markStatus(job.id, SENDING)
        var attempt = 0
        while (true) {
            try {
                val obj = MessagesApi.sendTextMessage(sdk, job.rid, job.msg, job.id, job.md) as? JsonObject
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
     * RN sendOneFileJob :317-465：标 SENDING → 逐 pending 项 rooms.upload（单文件幂等键
     * messageId=tempId、多文件 isMultiAttachment 收集 fileId）→ 单文件迁移/SENT；多文件
     * multiAttachments 合成 1 条消息后迁移/SENT。
     *
     * **成功路径禁写 attachments 形状**（RN :384-391 长注释的 bug 钉）：rooms.upload 已让服务端
     * 创建消息并经 DDP 回推服务端 attachments（含 title_link/image_url，缺 uploadStatus）；若此处
     * updateAttachments 写本地形状会覆盖之，isLocalAttachmentShape 判真、渲染回落本地图
     * （"切换到本地"bug）。故成功路径零触碰 attachments 列（DDP echo 未及时到时列保持入队原值，
     * 由 echo 覆盖）；失败路径仍写（记录 failed 便于重试跳过已成功项）。
     */
    private suspend fun sendOneFileJob(job: SendJob) {
        val attachments = parseAttachments(job.attachments)

        markStatus(job.id, SENDING)

        // 找出需要上传的项（pending/failed）
        val pendingIndexes = attachments
            .withIndex()
            .filter { it.value.uploadStatus != UPLOAD_UPLOADED }

        val isSingle = attachments.size == 1 && pendingIndexes.size == 1
        var lastUploadResult: cn.appia.im.core.media.UploadResult? = null

        for ((i, att) in pendingIndexes) {
            var attempt = 0
            var success = false
            while (true) {
                try {
                    val res = UploadApi.uploadFileForOrchestrator(
                        sdk = sdk,
                        rid = job.rid,
                        file = LocalFileInput(
                            name = att.name,
                            type = att.type,
                            size = att.size,
                            localPath = att.localPath,
                        ),
                        isMultiAttachment = !isSingle,
                        messageId = if (isSingle) job.id else null,
                        msg = if (isSingle && job.msg.isNotEmpty()) job.msg else null,
                        md = if (isSingle) job.md else null,
                        onProgress = { ratio ->
                            FileUploadProgress.emit(
                                job.id,
                                FileUploadProgress.Data(
                                    totalFiles = attachments.size,
                                    completedFiles = attachments.count { it.uploadStatus == UPLOAD_UPLOADED },
                                    currentFileProgress = ratio,
                                    currentFileName = att.name,
                                ),
                            )
                        },
                    )
                    attachments[i] = att.copy(uploadStatus = UPLOAD_UPLOADED, fileId = res.fileId)
                    lastUploadResult = res
                    success = true
                    if (!isSingle) {
                        updateAttachments(job.id, attachments) // 多文件进度可见；成功整体仍不回写终态列
                    }
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!RoomHistoryRepository.isRetryableError(e) || attempt >= MAX_ATTEMPTS) {
                        attachments[i] = att.copy(uploadStatus = UPLOAD_FAILED)
                        break
                    }
                    sleep((1L shl attempt) * 1_000) // RN getBackoffMs 同 text 面
                    attempt += 1
                }
            }
            if (!success) {
                // 失败路径保留 updateAttachments：记录 uploadStatus='failed'，重发/重试跳过已成功项
                updateAttachments(job.id, attachments)
                markStatus(job.id, ERROR)
                FileUploadProgress.emit(job.id, null)
                return
            }
        }

        // （成功路径到此：不写 attachments——见上方 KDoc 红线）

        if (isSingle) {
            // 单文件：rooms.upload 已建消息；服务端生成自己的 _id（响应 message._id）→ 迁移复用 M2 语义
            val res = lastUploadResult
            val serverId = res?.messageId
            if (serverId != null && serverId != job.id) {
                migrateToServerId(job.id, serverId)
                FileUploadProgress.emit(job.id, null)
                markStatus(serverId, SENT)
            } else {
                markStatus(job.id, SENT)
                FileUploadProgress.emit(job.id, null)
            }
            return
        }

        // 多文件：multiAttachments 把 fileId 合成 1 条消息（RN :409-464）
        var multiAttempt = 0
        while (true) {
            try {
                val isAppend = job.serverMessageId != null
                val fileIds = (if (isAppend) {
                    attachments.filter { (it.id ?: it.localPath) == job.retryAttachmentId }
                } else {
                    attachments
                }).mapNotNull { it.fileId }
                val res = UploadApi.multiAttachments(
                    sdk = sdk,
                    rid = job.rid,
                    fileIds = fileIds,
                    msg = job.msg.ifEmpty { null },
                    md = job.md,
                    messageId = job.serverMessageId,
                )
                // create 分支响应含 messageId（= 服务端新消息 _id）；append 分支原行直接 SENT
                val serverId = UploadApi.multiAttachmentsServerId(res)
                    ?: throw cn.appia.im.core.network.rest.ApiException(
                        "multiAttachments response missing message id",
                        status = 502,
                    )
                if (isAppend) {
                    markStatus(job.id, SENT)
                    FileUploadProgress.emit(job.id, null)
                } else if (serverId != job.id) {
                    serverMessageIdByLocalId[job.id] = serverId
                    serverMessageIdByLocalId[serverId] = serverId
                    attachmentLocalIdByServerId[serverId] = job.id
                    migrateToServerId(job.id, serverId)
                    FileUploadProgress.emit(job.id, null)
                    markStatus(serverId, SENT)
                } else {
                    markStatus(job.id, SENT)
                    FileUploadProgress.emit(job.id, null)
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!RoomHistoryRepository.isRetryableError(e) || multiAttempt >= MAX_ATTEMPTS) {
                    markStatus(job.id, ERROR)
                    FileUploadProgress.emit(job.id, null)
                    return
                }
                sleep((1L shl multiAttempt) * 1_000)
                multiAttempt += 1
            }
        }
    }

    /** RN updateAttachments :467-476 失败路径等价：attachments 单列 UPDATE（不回写全行，见 MessageDao）。 */
    private suspend fun updateAttachments(id: String, attachments: List<LocalAttachment>) {
        db.messageDao().updateAttachments(id, encodeAttachments(attachments))
    }

    /** RN parseAttachments :478-488：坏 JSON/非数组 → 空表（不抛）。 */
    private fun parseAttachments(raw: String?): MutableList<LocalAttachment> {
        if (raw.isNullOrEmpty()) return mutableListOf()
        return runCatching {
            Json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(LocalAttachment.serializer()), raw)
        }.getOrDefault(emptyList()).toMutableList()
    }

    private fun encodeAttachments(attachments: List<LocalAttachment>): String =
        attachmentJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(LocalAttachment.serializer()),
            attachments,
        )

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

    /**
     * RN markStatus :546-553。messages.status 仅本类可写。单列 UPDATE（[MessageDao.updateStatus]）：
     * get-then-update 的全行回写会与并发全行 upsert（DDP echo/history）互相冲列（M2 终审 Minor-6）。
     */
    private suspend fun markStatus(id: String, status: Int) {
        db.messageDao().updateStatus(id, status.toDouble())
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
        cn.appia.im.core.media.FileUploadProgress.clear() // 进度单例同寿：旧 tempId 流不跨会话残留
    }
}
