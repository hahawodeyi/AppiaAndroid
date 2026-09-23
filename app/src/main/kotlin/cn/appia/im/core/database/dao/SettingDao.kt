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

    /**
     * 批量 upsert（M5-T1 settings.public 分批同步 / public-settings-changed 单条增量共用）。
     * REPLACE 整行覆盖 = RN batchUpsertPublicSettings 的「清三列再写目标列」语义（换列类型不残留旧值）。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<SettingEntity>)

    /** 单行 upsert（流式增量 args[1] 单条路径）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SettingEntity)

    /** 单行 Flow（usePublicSettingBoolean 等价的响应式读：行变化即重发；缺行发 null）。 */
    @Query("SELECT * FROM settings WHERE _id = :id")
    fun observeById(id: String): Flow<SettingEntity?>
}
