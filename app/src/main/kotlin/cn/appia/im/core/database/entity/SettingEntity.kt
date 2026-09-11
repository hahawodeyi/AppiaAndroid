package cn.appia.im.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 对照 appiaMobile `src/database/schema.ts`（WatermelonDB v6）`settings` 表逐列转录（5 列）。
 * string→String、number→Double、boolean→Boolean；isOptional→可空；JSON 列存原文 String。
 * RN schema 无 `_id` 列：Android 侧按自然键（WatermelonDB 行 id 语义）补 `_id` 作主键。
 */
@Entity(tableName = "settings"
)
data class SettingEntity(
    @PrimaryKey
    val _id: String,  // Android 侧补充的主键列（RN schema 无此列）
    val value_as_string: String? = null,
    val value_as_boolean: Boolean? = null,
    val value_as_number: Double? = null,
    val _updated_at: Double? = null,
)
