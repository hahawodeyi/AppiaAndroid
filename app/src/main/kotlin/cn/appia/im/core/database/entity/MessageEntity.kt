package cn.appia.im.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 对照 appiaMobile `src/database/schema.ts`（WatermelonDB v6）`messages` 表逐列转录（51 列）。
 * string→String、number→Double、boolean→Boolean；isOptional→可空；JSON 列存原文 String。
 * RN schema 无 `_id` 列：Android 侧按自然键（WatermelonDB 行 id 语义）补 `_id` 作主键。
 */
@Entity(tableName = "messages",
    indices = [
        Index(value = ["rid"]),
    ],
)
data class MessageEntity(
    @PrimaryKey
    val _id: String,  // Android 侧补充的主键列（RN schema 无此列）
    val msg: String? = null,
    val t: String? = null,
    val rid: String,
    val ts: Double,
    val u: String,
    val roomSender: String? = null,
    val rollbacker: String? = null,
    val alias: String,
    val parse_urls: String,
    val groupable: Boolean? = null,
    val avatar: String? = null,
    val emoji: String? = null,
    val attachments: String? = null,
    val files: String? = null,
    val urls: String? = null,
    val _updated_at: Double,
    val status: Double? = null,
    val pinned: Boolean? = null,
    val starred: Boolean? = null,
    val edited_by: String? = null,
    val reactions: String? = null,
    val role: String? = null,
    val role_name: String? = null,
    val drid: String? = null,
    val dcount: Double? = null,
    val dlm: Double? = null,
    val tmid: String? = null,
    val tcount: Double? = null,
    val tlm: Double? = null,
    val replies: String? = null,
    val mentions: String? = null,
    val channels: String? = null,
    val unread: Boolean? = null,
    val auto_translate: Boolean? = null,
    val translations: String? = null,
    val tmsg: String? = null,
    val blocks: String? = null,
    val e2e: String? = null,
    val tshow: Boolean? = null,
    val md: String? = null,
    val comment: String? = null,
    val msg_type: String? = null,
    val msg_data: String? = null,
    val survey_status: Boolean? = null,
    val appia_todo: String? = null,
    val local_record_path: String? = null,
    val show_image_summary: String? = null,
    val show_document_summary: String? = null,
    val appia_quick_replies: String? = null,
    val original_content: String? = null,
)
