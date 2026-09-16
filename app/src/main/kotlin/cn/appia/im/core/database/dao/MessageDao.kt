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

    @Delete
    suspend fun delete(entity: MessageEntity)

    @Query("SELECT * FROM messages")
    fun observe(): Flow<List<MessageEntity>>
}
