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

    @Delete
    suspend fun deleteAll(entities: List<ChatEntity>)

    @Query("SELECT * FROM chats")
    fun observe(): Flow<List<ChatEntity>>
}
