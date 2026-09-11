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

    @Query("SELECT * FROM chats WHERE _id = :id")
    suspend fun getById(id: String): ChatEntity?

    @Query("SELECT * FROM chats")
    suspend fun getAll(): List<ChatEntity>

    @Update
    suspend fun update(entity: ChatEntity)

    @Delete
    suspend fun delete(entity: ChatEntity)

    @Query("SELECT * FROM chats")
    fun observe(): Flow<List<ChatEntity>>
}
