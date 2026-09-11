package cn.appia.im.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 对照 appiaMobile `src/database/schema.ts`（WatermelonDB v6）`rooms` 表逐列转录（20 列）。
 * string→String、number→Double、boolean→Boolean；isOptional→可空；JSON 列存原文 String。
 */
@Entity(tableName = "rooms"
)
data class RoomEntity(
    @PrimaryKey
    val _id: String,
    val custom_fields: String,
    val broadcast: Boolean,
    val encrypted: Boolean,
    val ro: Boolean,
    val v: String? = null,
    val department_id: String? = null,
    val served_by: String? = null,
    val livechat_data: String? = null,
    val tags: String? = null,
    val e2e_key_id: String? = null,
    val avatar_etag: String? = null,
    val federated: Boolean? = null,
    val rt: String? = null,
    val rooms: String? = null,
    val onCallStatus: Boolean? = null,
    val callMsg: String? = null,
    val bot: Boolean? = null,
    val showAppiaTag: Double? = null,
    val appiaUsage: String? = null,
)
