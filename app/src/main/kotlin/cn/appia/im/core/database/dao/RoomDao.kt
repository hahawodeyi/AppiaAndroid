package cn.appia.im.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import cn.appia.im.core.database.entity.RoomEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RoomDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RoomEntity)

    @Query("SELECT * FROM rooms WHERE _id = :id")
    suspend fun getById(id: String): RoomEntity?

    @Query("SELECT * FROM rooms")
    suspend fun getAll(): List<RoomEntity>

    @Update
    suspend fun update(entity: RoomEntity)

    @Delete
    suspend fun delete(entity: RoomEntity)

    @Query("SELECT * FROM rooms")
    fun observe(): Flow<List<RoomEntity>>
}
