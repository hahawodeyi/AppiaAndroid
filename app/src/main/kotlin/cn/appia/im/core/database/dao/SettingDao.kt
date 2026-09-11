package cn.appia.im.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import cn.appia.im.core.database.entity.SettingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SettingEntity)

    @Query("SELECT * FROM settings WHERE _id = :id")
    suspend fun getById(id: String): SettingEntity?

    @Query("SELECT * FROM settings")
    suspend fun getAll(): List<SettingEntity>

    @Update
    suspend fun update(entity: SettingEntity)

    @Delete
    suspend fun delete(entity: SettingEntity)

    @Query("SELECT * FROM settings")
    fun observe(): Flow<List<SettingEntity>>
}
