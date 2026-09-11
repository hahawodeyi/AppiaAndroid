package cn.appia.im.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import cn.appia.im.core.database.entity.UploadEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: UploadEntity)

    @Query("SELECT * FROM uploads WHERE _id = :id")
    suspend fun getById(id: String): UploadEntity?

    @Query("SELECT * FROM uploads")
    suspend fun getAll(): List<UploadEntity>

    @Update
    suspend fun update(entity: UploadEntity)

    @Delete
    suspend fun delete(entity: UploadEntity)

    @Query("SELECT * FROM uploads")
    fun observe(): Flow<List<UploadEntity>>
}
