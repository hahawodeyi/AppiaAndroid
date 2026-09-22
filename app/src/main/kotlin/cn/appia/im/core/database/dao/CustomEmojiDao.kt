package cn.appia.im.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import cn.appia.im.core.database.entity.CustomEmojiEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomEmojiDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CustomEmojiEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<CustomEmojiEntity>)

    @Query("SELECT * FROM custom_emojis WHERE name = :id")
    suspend fun getById(id: String): CustomEmojiEntity?

    @Query("SELECT * FROM custom_emojis")
    suspend fun getAll(): List<CustomEmojiEntity>

    @Update
    suspend fun update(entity: CustomEmojiEntity)

    @Delete
    suspend fun delete(entity: CustomEmojiEntity)

    @Query("SELECT * FROM custom_emojis")
    fun observe(): Flow<List<CustomEmojiEntity>>

    @Query("DELETE FROM custom_emojis")
    suspend fun deleteAll()

    /**
     * 整表替换（总纲 §4.4-2；RN customEmojisStore.setCustomEmojis 语义）：
     * 单事务内 deleteAll+insertAll——服务端已删的表情不永驻。
     * 并发安全：Room invalidation 事务提交后单次触发，observe() Flow 只见最终态
     * （进行中的查询读 SQLite 快照，不闪断空表）。
     */
    @Transaction
    suspend fun replaceAll(entities: List<CustomEmojiEntity>) {
        deleteAll()
        insertAll(entities)
    }
}
