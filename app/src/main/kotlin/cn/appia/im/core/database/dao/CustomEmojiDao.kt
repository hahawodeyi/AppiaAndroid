package cn.appia.im.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import cn.appia.im.core.database.entity.CustomEmojiEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomEmojiDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CustomEmojiEntity)

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
}
