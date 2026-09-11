package cn.appia.im.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import cn.appia.im.core.database.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MessageEntity)

    @Query("SELECT * FROM messages WHERE _id = :id")
    suspend fun getById(id: String): MessageEntity?

    @Query("SELECT * FROM messages")
    suspend fun getAll(): List<MessageEntity>

    @Update
    suspend fun update(entity: MessageEntity)

    @Delete
    suspend fun delete(entity: MessageEntity)

    @Query("SELECT * FROM messages")
    fun observe(): Flow<List<MessageEntity>>
}
