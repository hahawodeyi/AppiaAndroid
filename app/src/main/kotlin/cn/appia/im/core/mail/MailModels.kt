package cn.appia.im.core.mail

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * 邮件 wire 模型（逐字段对照 appiaMobile/src/types/mail.ts 全量）。
 * RN 字段 optional → Kotlin 可空 + 默认 null；解析用 [mailJson]（ignoreUnknownKeys + explicitNulls=false，
 * 对齐 RN 服务端宽松 JSON）。详情归一化（坑 3 五组别名 + 坑 4 `mail-` 前缀）见文末 normalize 函数。
 */

/** 全 mail 域统一解析口径：未知键忽略（服务端加字段不炸）、显式 null 视同缺失。 */
val mailJson: Json = Json { ignoreUnknownKeys = true; explicitNulls = false }

/** types/mail.ts MailAccount。 */
@Serializable
data class MailAccount(
    val id: String,
    val email: String,
    val active: Boolean,
    val lastSyncAt: String? = null,
    val createdAt: String,
)

/** types/mail.ts MailFolder。 */
@Serializable
data class MailFolder(
    val path: String,
    val name: String,
    val specialUse: String? = null,
    val flags: List<String> = emptyList(),
)

/** mailApi.ts:132-135 内联响应壳 { folders: MailFolder[] }。 */
@Serializable
data class MailFoldersResponse(val folders: List<MailFolder> = emptyList())

/** types/mail.ts MailMessage。 */
@Serializable
data class MailMessage(
    val id: String,
    val uid: Long,
    val from: String,
    val fromName: String? = null,
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val subject: String,
    val receivedAt: String,
    val read: Boolean,
    val flagged: Boolean,
    val folder: String,
    val size: Long,
    val threadId: String? = null,
    val conversationId: String? = null,
    val replyCount: Int? = null,
)

/** types/mail.ts MailThreadMessage。 */
@Serializable
data class MailThreadMessage(
    val id: String,
    val from: String,
    val fromName: String? = null,
    val subject: String,
    val receivedAt: String,
    val bodyText: String? = null,
    val bodyHtml: String? = null,
)

/** types/mail.ts MailThreadResponse。 */
@Serializable
data class MailThreadResponse(
    val messages: List<MailThreadMessage> = emptyList(),
    val subject: String? = null,
)

/** types/mail.ts AttachmentMeta（归一化后的形态——见 [normalizeMailAttachment]。 */
@Serializable
data class AttachmentMeta(
    val part: String,
    val filename: String,
    val contentType: String,
    val size: Long,
)

/** types/mail.ts MessageDetail（MailMessage 全字段 + 正文/附件/账户）。 */
@Serializable
data class MessageDetail(
    val id: String,
    val uid: Long,
    val from: String,
    val fromName: String? = null,
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val subject: String,
    val receivedAt: String,
    val read: Boolean,
    val flagged: Boolean,
    val folder: String,
    val size: Long,
    val threadId: String? = null,
    val conversationId: String? = null,
    val replyCount: Int? = null,
    val bodyText: String? = null,
    val bodyHtml: String? = null,
    val attachments: List<AttachmentMeta> = emptyList(),
    val accountId: String = "",
)

/** types/mail.ts MessageListResponse。 */
@Serializable
data class MessageListResponse(
    val messages: List<MailMessage> = emptyList(),
    val total: Int = 0,
    val page: Int = 0,
    val pageSize: Int = 0,
)

/** types/mail.ts SearchMessagesResponse。 */
@Serializable
data class SearchMessagesResponse(
    val q: String = "",
    val messages: List<MailMessage> = emptyList(),
    val total: Int = 0,
    val page: Int = 0,
    val pageSize: Int = 0,
)

/** types/mail.ts SendMailAttachment——content 为纯 base64（无 data: 前缀）。 */
@Serializable
data class SendMailAttachment(
    val filename: String,
    val contentType: String? = null,
    val content: String,
)

/** types/mail.ts SendMailRequest（正文纯文本 only——bodyHtml RN 从不设置）。 */
@Serializable
data class SendMailRequest(
    val to: List<String>,
    val cc: List<String>? = null,
    val bcc: List<String>? = null,
    val subject: String,
    val bodyText: String? = null,
    val bodyHtml: String? = null,
    val attachments: List<SendMailAttachment>? = null,
)

/** types/mail.ts SendMailResponse。 */
@Serializable
data class SendMailResponse(
    val success: Boolean,
    val messageId: String? = null,
)

/** types/mail.ts MailDraft（bodyText/bodyHtml 服务端可回 JSON null——explicitNulls=false 容纳）。 */
@Serializable
data class MailDraft(
    val id: String,
    val accountId: String,
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String,
    val bodyText: String? = null,
    val bodyHtml: String? = null,
    val replyToMessageId: String? = null,
    val forwardMessageId: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

/** types/mail.ts UpsertDraftRequest（全 optional）。 */
@Serializable
data class UpsertDraftRequest(
    val accountId: String? = null,
    val to: List<String>? = null,
    val cc: List<String>? = null,
    val bcc: List<String>? = null,
    val subject: String? = null,
    val bodyText: String? = null,
    val bodyHtml: String? = null,
    val replyToMessageId: String? = null,
    val forwardMessageId: String? = null,
)

/** types/mail.ts ContactSuggestion。 */
@Serializable
data class ContactSuggestion(
    val name: String,
    val email: String,
    val department: String? = null,
    val phone: String? = null,
    val source: String = "user",
)

/** types/mail.ts MailComposeMode。 */
enum class MailComposeMode { NEW, REPLY, REPLY_ALL, FORWARD, DRAFT }

/** types/mail.ts LatestMailPreview（channel 行预览/角标）。 */
@Serializable
data class LatestMailPreview(
    val subject: String,
    val fromName: String? = null,
    val from: String,
    val receivedAt: String,
    val unread: Boolean,
)

/** types/mail.ts BindAccountRequest（明文形态——发线前经 RSA-OAEP 加密为 [EncryptedCredentialPayload]）。 */
@Serializable
data class BindAccountRequest(val email: String, val password: String)

/** mailCredentialTransport.ts CredentialPublicKeyResponse。 */
@Serializable
data class CredentialPublicKeyResponse(
    val algorithm: String = "RSA-OAEP",
    val hash: String = "SHA-256",
    val publicKey: String,
    val keyVersion: Int,
)

/** mailCredentialTransport.ts EncryptedCredentialPayload——email 明文随包，只加密密码。 */
@Serializable
data class EncryptedCredentialPayload(val email: String, val encryptedPassword: String)

/** types/mail.ts TestConnectionResponse。 */
@Serializable
data class TestConnectionResponse(val success: Boolean, val message: String)

/** types/mail.ts ListMessagesParams（query 组装由 MailClient.url 完成）。 */
data class ListMessagesParams(
    val folder: String? = null,
    val page: Int? = null,
    val pageSize: Int? = null,
    val unreadOnly: Boolean? = null,
    val threadId: String? = null,
    val conversationId: String? = null,
)

/** types/mail.ts SearchMessagesParams。 */
data class SearchMessagesParams(
    val q: String,
    val folder: String? = null,
    val page: Int? = null,
    val pageSize: Int? = null,
    val unreadOnly: Boolean? = null,
    val threadId: String? = null,
    val conversationId: String? = null,
)

/** types/mail.ts SyncResponse。 */
@Serializable
data class SyncResponse(
    val syncLogId: String,
    val status: String,
    val newCount: Int,
)

/** types/mail.ts DeleteMailMessageResponse。 */
@Serializable
data class DeleteMailMessageResponse(
    val permanent: Boolean,
    val trashFolder: String? = null,
)

// ---- 详情归一化（坑 3/4，normalizeMailMessageDetail.ts 移植）----

/**
 * 服务端附件原始形态：part|string|number 三别名 × filename/name × contentType/mimeType × size/bytes。
 * 字段全可空——归一化后缺失 part 或文件名的直接丢弃（normalizeMailMessageDetail.ts:3-48）。
 */
@Serializable
data class RawMailAttachment(
    val part: JsonPrimitive? = null,
    val partId: JsonPrimitive? = null,
    val partNumber: JsonPrimitive? = null,
    val filename: String? = null,
    val name: String? = null,
    val contentType: String? = null,
    val mimeType: String? = null,
    val size: Long? = null,
    val bytes: Long? = null,
)

/** 服务端详情原始形态：附件数组别名 attachments/attachmentList（可 null）。 */
@Serializable
data class RawMessageDetail(
    val id: String,
    val uid: Long = 0,
    val from: String = "",
    val fromName: String? = null,
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val subject: String = "",
    val receivedAt: String = "",
    val read: Boolean = false,
    val flagged: Boolean = false,
    val folder: String = "",
    val size: Long = 0,
    val threadId: String? = null,
    val conversationId: String? = null,
    val replyCount: Int? = null,
    val bodyText: String? = null,
    val bodyHtml: String? = null,
    val accountId: String = "",
    val attachments: List<RawMailAttachment>? = null,
    val attachmentList: List<RawMailAttachment>? = null,
)

/** normalizeMailMessageDetail.ts:20-21：剥 `mail-` 前缀只发生在 URL 构造点（detail/附件下载 path 参数）——
 *  RN reply/forward/thread URL 直拼不剥（mailApi.ts:220-243）。归一化输出 id 透传原样（TS `...raw`），
 *  前缀剥离由 T3/T4/T5 的 URL 构造点调用本函数。 */
fun normalizeMailMessageId(messageId: String): String =
    if (messageId.startsWith("mail-")) messageId.removePrefix("mail-") else messageId

/** 原始 part 别名（string|number 双形态）→ 字符串；JsonNull 视同缺失。 */
private fun JsonPrimitive?.textValue(): String? =
    if (this == null || this is JsonNull) null else content

/** normalizeMailMessageDetail.ts:26-40：part 为空/文件名空白的附件直接丢弃。 */
fun normalizeMailAttachment(raw: RawMailAttachment): AttachmentMeta? {
    val part = raw.part ?: raw.partId ?: raw.partNumber
    val partText = part.textValue()
    val filename = (raw.filename ?: raw.name)?.trim().orEmpty()
    if (partText.isNullOrEmpty() || filename.isEmpty()) return null
    return AttachmentMeta(
        part = partText,
        filename = filename,
        contentType = raw.contentType ?: raw.mimeType ?: "application/octet-stream",
        size = raw.size ?: raw.bytes ?: 0L,
    )
}

/** normalizeMailMessageDetail.ts:42-48：五组别名归一化 → [MessageDetail]。 */
fun normalizeMailMessageDetail(raw: RawMessageDetail): MessageDetail {
    val source = raw.attachments ?: raw.attachmentList
    val attachments = (source ?: emptyList()).mapNotNull(::normalizeMailAttachment)
    return MessageDetail(
        id = raw.id, // TS `...raw` 透传——剥前缀时机在 URL 构造点，不在此（评审 Action 1）
        uid = raw.uid,
        from = raw.from,
        fromName = raw.fromName,
        to = raw.to,
        cc = raw.cc,
        subject = raw.subject,
        receivedAt = raw.receivedAt,
        read = raw.read,
        flagged = raw.flagged,
        folder = raw.folder,
        size = raw.size,
        threadId = raw.threadId,
        conversationId = raw.conversationId,
        replyCount = raw.replyCount,
        bodyText = raw.bodyText,
        bodyHtml = raw.bodyHtml,
        attachments = attachments,
        accountId = raw.accountId,
    )
}
