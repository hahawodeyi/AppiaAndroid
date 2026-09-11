package cn.appia.im.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 对照 appiaMobile `src/database/schema.ts`（WatermelonDB v6）`uploads` 表逐列转录（8 列）。
 * string→String、number→Double、boolean→Boolean；isOptional→可空；JSON 列存原文 String。
 * RN schema 无 `_id` 列：Android 侧按自然键（WatermelonDB 行 id 语义）补 `_id` 作主键。
 */
@Entity(tableName = "uploads",
    indices = [
        Index(value = ["rid"]),
    ],
)
data class UploadEntity(
    @PrimaryKey
    val _id: String,  // Android 侧补充的主键列（RN schema 无此列）
    val rid: String,
    val path: String,
    val name: String,
    val size: Double,
    val type: String,
    val progress: Double,
    val error: Boolean,
)
