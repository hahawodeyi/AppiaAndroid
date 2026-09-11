package cn.appia.im.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 对照 appiaMobile `src/database/schema.ts`（WatermelonDB v6）`custom_emojis` 表逐列转录（4 列）。
 * string→String、number→Double、boolean→Boolean；isOptional→可空；JSON 列存原文 String。
 * 主键为自然键 `name`（RN 中行 id 即该值）。
 */
@Entity(tableName = "custom_emojis"
)
data class CustomEmojiEntity(
    @PrimaryKey
    val name: String,
    val aliases: String? = null,
    val extension: String,
    val _updated_at: Double,
)
