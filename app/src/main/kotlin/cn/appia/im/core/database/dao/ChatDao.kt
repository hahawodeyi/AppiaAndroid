package cn.appia.im.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import cn.appia.im.core.database.entity.ChatEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ChatEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<ChatEntity>)

    @Query("SELECT * FROM chats WHERE _id = :id")
    suspend fun getById(id: String): ChatEntity?

    /** 会话同步只查本次 update+remove 涉及的 rid（RN Q.oneOf；调用方需按 ≤500 分块防 SQLite 变量上限）。 */
    @Query("SELECT * FROM chats WHERE _id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<ChatEntity>

    @Query("SELECT * FROM chats")
    suspend fun getAll(): List<ChatEntity>

    @Update
    suspend fun update(entity: ChatEntity)

    @Update
    suspend fun updateAll(entities: List<ChatEntity>)

    @Delete
    suspend fun delete(entity: ChatEntity)

    /** 物理删单行（RN destroyPermanently 语义；notify-user removed 事件用），行不存在时 no-op。 */
    @Query("DELETE FROM chats WHERE _id = :id")
    suspend fun deleteById(id: String)

    @Delete
    suspend fun deleteAll(entities: List<ChatEntity>)

    @Query("SELECT * FROM chats")
    fun observe(): Flow<List<ChatEntity>>

    /** 单房间行观察（RoomScreen 头部标题实时跟随 chats 行；行不存在发 null → 路由参数兜底）。 */
    @Query("SELECT * FROM chats WHERE rid = :rid LIMIT 1")
    fun observeByRid(rid: String): Flow<ChatEntity?>

    /**
     * 会话列表观察（RN useRoomListChats.ts:38-43）：
     * `archived = false AND open = true AND bot != true`，按 room_updated_at 倒序兜底
     * （分段排序由 buildRoomListSections 负责，此处仅同步 RN 的查询排序）。
     * bot 为可空列：Watermelon `Q.notEq(true)` 编码为 `is not`（NULL 行保留），故用 IS NOT 而非 !=。
     * Room invalidation 为表级触发，WHERE 列变化天然驱动重发射（无需 RN 的列订阅对齐）。
     */
    @Query(
        "SELECT * FROM chats WHERE archived = 0 AND open = 1 AND bot IS NOT 1 " +
            "ORDER BY room_updated_at DESC",
    )
    fun observeList(): Flow<List<ChatEntity>>

    /**
     * 最近联系成员源（RN useRecentContacts.ts:44-48）：`t='d'`、非 bot、未归档、open，
     * room_updated_at 倒序。「对方」username 提取/去重由 [cn.appia.im.core.chat.mapRecentContactRows] 负责。
     */
    @Query(
        "SELECT * FROM chats WHERE t = 'd' AND bot IS NOT 1 AND archived = 0 AND open = 1 " +
            "ORDER BY room_updated_at DESC",
    )
    fun observeDirects(): Flow<List<ChatEntity>>
}
