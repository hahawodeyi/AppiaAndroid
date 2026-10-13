package cn.appia.im.feature.mail

import cn.appia.im.core.mail.MailMessage
import cn.appia.im.core.mail.MailThreadMessage
import cn.appia.im.core.mail.MessageDetail
import cn.appia.im.feature.mail.MailChannelViewModel.Companion.mailIsoMillis

/**
 * 线程加载（mailThread.ts 移植）：三级降级
 * 1. GET /accounts/:id/messages/:msgId/thread（非空 → 升序返回）
 * 2. 列表查询 threadId/conversationId（page=1 pageSize=100）
 * 3. 仅锚点详情单条
 */

/** mailThread.ts:10-15 MailThreadLoadResult。 */
data class MailThreadLoadResult(
    val anchorMessageId: String,
    val accountId: String,
    val subject: String,
    val messages: List<MailThreadMessage>,
)

/** mailThread.ts:17-22 sortThreadMessagesChronologically。 */
fun sortThreadMessagesChronologically(messages: List<MailThreadMessage>): List<MailThreadMessage> =
    messages.sortedBy { mailIsoMillis(it.receivedAt) }

/** mailThread.ts:24-33 messageDetailToThreadMessage。 */
fun messageDetailToThreadMessage(detail: MessageDetail): MailThreadMessage =
    MailThreadMessage(
        id = detail.id,
        from = detail.from,
        fromName = detail.fromName,
        subject = detail.subject,
        receivedAt = detail.receivedAt,
        bodyText = detail.bodyText,
        bodyHtml = detail.bodyHtml,
    )

/** mailThread.ts:35-42 mailMessageToThreadMessage。 */
fun mailMessageToThreadMessage(message: MailMessage): MailThreadMessage =
    MailThreadMessage(
        id = message.id,
        from = message.from,
        fromName = message.fromName,
        subject = message.subject,
        receivedAt = message.receivedAt,
    )

/** mailThread.ts:44-48 resolveThreadSubject：锚点 → 路由 fallback → (No subject)。 */
fun resolveThreadSubject(anchorSubject: String?, fallbackSubject: String?, noSubjectLabel: String): String =
    anchorSubject?.trim()?.ifEmpty { null } ?: fallbackSubject?.trim()?.ifEmpty { null } ?: noSubjectLabel

/** mailThread.ts:50-54 shouldShowMailThreadLink（详情页「查看线程」入口判定）。 */
fun shouldShowMailThreadLink(
    detail: MessageDetail,
): Boolean = detail.threadId != null || detail.conversationId != null || (detail.replyCount ?: 0) > 0

/** mailThread.ts:56-66 stripHtmlToPreview：style/script/标签/实体剥除 + 空白折叠。 */
fun stripHtmlToPreview(html: String?): String {
    if (html.isNullOrBlank()) return ""
    return html
        .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("&nbsp;", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

/** mailThread.ts:68-71 resolveThreadMessagePreview：bodyText 优先，HTML 剥除兜底。 */
fun resolveThreadMessagePreview(message: MailThreadMessage): String {
    val text = message.bodyText?.trim()
    if (!text.isNullOrEmpty()) return text
    return stripHtmlToPreview(message.bodyHtml)
}

/**
 * mailThread.ts:73-125 loadReplyThreadMessages：三级降级主链。
 * thread 端点失败静默降级（catch fall-through）；列表查询空也降级到锚点单条。
 */
suspend fun loadReplyThreadMessages(
    client: cn.appia.im.core.mail.MailClient,
    accountId: String,
    messageId: String,
    fallbackSubject: String? = null,
    noSubjectLabel: String = "(No subject)",
): MailThreadLoadResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val anchor = getMailMessageDetail(client, messageId)

    try {
        val thread = listMailThreadMessages(client, accountId, messageId)
        if (thread.messages.isNotEmpty()) {
            return@withContext MailThreadLoadResult(
                anchorMessageId = messageId,
                accountId = accountId,
                subject = resolveThreadSubject(anchor.subject, thread.subject, noSubjectLabel),
                messages = sortThreadMessagesChronologically(thread.messages),
            )
        }
    } catch (_: Exception) {
        // Fall through to list-by-thread-id or anchor-only result.
    }

    val threadQueryId = anchor.threadId ?: anchor.conversationId
    if (threadQueryId != null) {
        val listed = listMailMessages(
            client, accountId,
            cn.appia.im.core.mail.ListMessagesParams(
                threadId = anchor.threadId,
                conversationId = anchor.conversationId,
                page = 1,
                pageSize = 100,
            ),
        )
        if (listed.messages.isNotEmpty()) {
            return@withContext MailThreadLoadResult(
                anchorMessageId = messageId,
                accountId = accountId,
                subject = resolveThreadSubject(anchor.subject, null, noSubjectLabel),
                messages = sortThreadMessagesChronologically(listed.messages.map(::mailMessageToThreadMessage)),
            )
        }
    }

    MailThreadLoadResult(
        anchorMessageId = messageId,
        accountId = accountId,
        subject = resolveThreadSubject(anchor.subject, fallbackSubject, noSubjectLabel),
        messages = listOf(messageDetailToThreadMessage(anchor)),
    )
}
