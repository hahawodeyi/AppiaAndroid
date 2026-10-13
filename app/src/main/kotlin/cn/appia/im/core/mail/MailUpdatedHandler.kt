package cn.appia.im.core.mail

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * DDP `stream-notify-user` `<uid>/mail.updated` 推送消费（mailUpdatedHandler.ts 逐段移植）。
 * RN 不 subscribe mail.updated——只被动解析 stream-notify-user 全量帧（session.ts:255-258
 * 单 listener 同时喂持久化与 mail 两 handler），Android 侧同构：由装配层在 NOTIFY_USER
 * 分发里并列调用。
 */

/** mailUpdatedHandler.ts:3-8 MailUpdatedEventPayload。 */
@Serializable
data class MailUpdatedEventPayload(
    val accountId: String,
    val folder: String,
    val newCount: Int = 0,
    val at: Long? = null,
)

/**
 * 坑 7：IMAP 实现可能返回 `Inbox`/`\\Inbox` 等变体——剥 `\\` 前缀 + trim + 大写后比对 INBOX。
 */
fun isInboxFolder(folder: String?): Boolean {
    if (folder == null) return false
    val normalized = folder.trim().removePrefix("\\").trim().uppercase()
    return normalized == "INBOX"
}

/** mailUpdatedHandler.ts:22-45 parseMailUpdatedPayload：帧解析（added/异事件/缺字段 → null）。 */
fun parseMailUpdatedPayload(ddpMessage: JsonElement): MailUpdatedEventPayload? {
    val m = ddpMessage as? JsonObject ?: return null
    if ((m["msg"] as? JsonPrimitive)?.contentOrNull == "added") return null

    val fields = m["fields"] as? JsonObject ?: return null
    val eventName = (fields["eventName"] as? JsonPrimitive)?.contentOrNull ?: return null
    if (!eventName.contains("/")) return null

    // RN split('/').slice(1).join('/')：`uid/mail.updated` → `mail.updated`（uid 含 '/' 容错）
    val ev = eventName.split("/").drop(1).joinToString("/")
    if (ev != "mail.updated") return null

    val payload = (fields["args"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
    val accountId = (payload["accountId"] as? JsonPrimitive)?.contentOrNull
    val folder = (payload["folder"] as? JsonPrimitive)?.contentOrNull
    if (accountId.isNullOrEmpty() || folder.isNullOrEmpty()) return null

    return MailUpdatedEventPayload(
        accountId = accountId,
        folder = folder,
        newCount = (payload["newCount"] as? JsonPrimitive)?.intOrNull ?: 0,
        at = (payload["at"] as? JsonPrimitive)?.let {
            (it as? JsonPrimitive)?.longOrNull ?: it.contentOrNull?.toLongOrNull()
        },
    )
}

/**
 * mailUpdatedHandler.ts:47-61 handleMailUpdatedEvent：仅 INBOX 消费；newCount>0 增量角标；
 * 预览刷新恒触发（fire-and-forget——注入的 lambda 自行起协程，等价 RN `void fn()`）。
 * 返回 false = 非 INBOX 帧未消费。
 */
fun handleMailUpdatedEvent(
    payload: MailUpdatedEventPayload,
    incrementUnread: (Int) -> Unit,
    fetchLatestMailPreview: (() -> Unit)?,
): Boolean {
    if (!isInboxFolder(payload.folder)) return false
    if (payload.newCount > 0) {
        incrementUnread(payload.newCount)
    }
    fetchLatestMailPreview?.invoke()
    return true
}

private val JsonPrimitive.contentOrNull: String?
    get() = if (isString) content else content.takeIf { it.isNotEmpty() }

private val JsonPrimitive.longOrNull: Long?
    get() = content.toLongOrNull()
