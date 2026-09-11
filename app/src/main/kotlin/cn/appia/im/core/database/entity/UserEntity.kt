package cn.appia.im.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 对照 appiaMobile `src/database/schema.ts`（WatermelonDB v6）`users` 表逐列转录（4 列）。
 * string→String、number→Double、boolean→Boolean；isOptional→可空；JSON 列存原文 String。
 */
@Entity(tableName = "users",
    indices = [
        Index(value = ["username"]),
    ],
)
data class UserEntity(
    @PrimaryKey
    val _id: String,
    val name: String,
    val username: String,
    val avatar_etag: String? = null,
)
