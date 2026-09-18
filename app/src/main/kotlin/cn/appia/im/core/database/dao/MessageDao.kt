package cn.appia.im.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import cn.appia.im.core.database.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MessageEntity)

    /** 消息批量落库（MessageUpsert.persist 单事务内调用；全行覆盖，客户端列经 prev 预先并回）。 */
    @Upsert
    suspend fun upsertAll(entities: List<MessageEntity>)

    @Query("SELECT * FROM messages WHERE _id = :id")
    suspend fun getById(id: String): MessageEntity?

    /** 批量 upsert 前查已有行（RN Q.oneOf 等价；调用方需按 ≤500 分块防 SQLite 变量上限）。 */
    @Query("SELECT * FROM messages WHERE _id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<MessageEntity>

    /** 房间历史查询：按 ts 倒序取最新 :limit 条（RN messages 查询排序等价）。 */
    @Query("SELECT * FROM messages WHERE rid = :rid ORDER BY ts DESC LIMIT :limit")
    suspend fun getByRid(rid: String, limit: Int): List<MessageEntity>

    /** 房间历史窗口流：同查询的响应式形态，落库即重发射（RoomMessagesViewModel 窗口订阅）。 */
    @Query("SELECT * FROM messages WHERE rid = :rid ORDER BY ts DESC LIMIT :limit")
    fun observeByRid(rid: String, limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages")
    suspend fun getAll(): List<MessageEntity>

    @Update
    suspend fun update(entity: MessageEntity)

    /**
     * 单列 status 更新（SendOrchestrator.markStatus，RN `message.update { status }` 等价）：
     * 避免旧实现的 get-then-update 全行回写——与 DDP echo / history 的全行 upsert 并发时，
     * 陈旧快照会把对方刚写的列冲回去（M2 终审 Minor-6 丢 SENT 窗口）。UPDATE 只触 status 列。
     */
    @Query("UPDATE messages SET status = :status WHERE _id = :id")
    suspend fun updateStatus(id: String, status: Double)

    /**
     * attachments 单列更新（SendOrchestrator.updateAttachments 失败路径，T6）：只触 attachments 列，
     * 与 markStatus 同理不回写全行（并发 DDP echo 保护；成功路径禁用——服务端 attachments 才是渲染源）。
     */
    @Query("UPDATE messages SET attachments = :attachments WHERE _id = :id")
    suspend fun updateAttachments(id: String, attachments: String?)

    /** retryFile 两列更新（RN row.update { attachments; status } 等价；同不回写全行）。 */
    @Query("UPDATE messages SET attachments = :attachments, status = :status WHERE _id = :id")
    suspend fun updateAttachmentsAndStatus(id: String, attachments: String?, status: Double)

    /**
     * reactions 单列更新（T8 表情回应乐观翻转/失败回滚）：只触 reactions 列，
     * 与 updateStatus 同理不回写全行——乐观写与 DDP 回推的全行 upsert 并发时互不冲列
     * （回推以服务端真值覆盖 reactions，乐观写不冲它的其余字段）。
     */
    @Query("UPDATE messages SET reactions = :reactions WHERE _id = :id")
    suspend fun updateReactions(id: String, reactions: String?)

    /**
     * original_content 单列更新（T11 撤回快照，RN captureRecalledOriginalContent :82-99 的
     * prepareUpdate 等价）：撤回前把原文 8 字段快照写入，只触 original_content 列（不回写全行，
     * 与并发 DDP 回推互不冲列，同 updateStatus 裁定）。
     */
    @Query("UPDATE messages SET original_content = :json WHERE _id = :id")
    suspend fun updateOriginalContent(id: String, json: String?)

    @Delete
    suspend fun delete(entity: MessageEntity)

    @Query("SELECT * FROM messages")
    fun observe(): Flow<List<MessageEntity>>
}
