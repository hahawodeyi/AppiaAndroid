package cn.appia.im.feature.mail

import cn.appia.im.core.mail.MailComposeMode
import cn.appia.im.core.mail.MailDraft
import cn.appia.im.core.mail.MessageDetail
import cn.appia.im.core.mail.UpsertDraftRequest

/**
 * 撰写表单纯逻辑（mailComposeForm.ts + replyRecipients.ts 移植）：
 * - replyAll 全进 to 去重去自己**无 cc**（坑 13：RN cc 恒空，不拆 to/cc）
 * - Re:/Fwd: 前缀去重
 * - snapshot ⇄ 文本域解析（逗号/分号分隔）
 */

/** mailComposeForm.ts ComposeSnapshot。 */
data class ComposeSnapshot(
    val toText: String,
    val ccText: String,
    val bccText: String,
    val subject: String,
    val body: String,
)

/** replyRecipients.ts ReplyRecipients。 */
data class ReplyRecipients(val to: List<String>, val cc: List<String>)

private fun normalizeEmailKey(value: String?): String = value?.trim()?.lowercase().orEmpty()

/** replyRecipients.ts parseEmailList：[,;] 分隔 + trim + 去空。 */
fun parseEmailList(text: String): List<String> =
    text.split(Regex("[,;]+")).map { it.trim() }.filter { it.isNotEmpty() }

/** mailComposeForm.ts formatEmailListText：逗号拼接（空元素过滤）。 */
fun formatEmailListText(emails: List<String>?): String =
    emails.orEmpty().filter { it.isNotBlank() }.joinToString(", ")

/** replyRecipients.ts buildReplySubject：空 → "Re: "；已有 Re: 前缀（大小写不敏感）不重复加。 */
fun buildReplySubject(subject: String): String {
    val trimmed = subject.trim()
    if (trimmed.isEmpty()) return "Re: "
    if (Regex("^re\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) return subject
    return "Re: $subject"
}

/** mailComposeForm.ts buildForwardSubject：空 → "Fwd: "；已有 Fwd: 前缀不重复加。 */
fun buildForwardSubject(subject: String): String {
    val trimmed = subject.trim()
    if (trimmed.isEmpty()) return "Fwd: "
    if (Regex("^fwd\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) return subject
    return "Fwd: $subject"
}

/** replyRecipients.ts buildReplyRecipients：to = [from]，cc 空。 */
fun buildReplyRecipients(detail: MessageDetail): ReplyRecipients =
    ReplyRecipients(to = if (detail.from.isNotBlank()) listOf(detail.from) else emptyList(), cc = emptyList())

/**
 * replyRecipients.ts buildReplyAllRecipients（坑 13）：from + to + cc 全部进 to，
 * trim 后 lowercase 去重、排除自己（accountEmail）；**cc 恒空**——RN 不拆收件组。
 */
fun buildReplyAllRecipients(detail: MessageDetail, accountEmail: String?): ReplyRecipients {
    val exclude = normalizeEmailKey(accountEmail)
    val seen = mutableSetOf<String>()
    val to = mutableListOf<String>()
    for (raw in listOf(detail.from) + detail.to + detail.cc) {
        if (raw.isBlank()) continue
        val trimmed = raw.trim()
        val key = normalizeEmailKey(trimmed)
        if (key.isEmpty() || key == exclude || !seen.add(key)) continue
        to += trimmed
    }
    return ReplyRecipients(to = to, cc = emptyList())
}

/** mailComposeForm.ts buildEmptyComposeSnapshot。 */
fun buildEmptyComposeSnapshot() = ComposeSnapshot("", "", "", "", "")

/** mailComposeForm.ts buildReplyComposePrefill（reply/replyAll 共口）。 */
fun buildReplyComposePrefill(mode: MailComposeMode, detail: MessageDetail, accountEmail: String?): ComposeSnapshot {
    val recipients = if (mode == MailComposeMode.REPLY_ALL) {
        buildReplyAllRecipients(detail, accountEmail)
    } else {
        buildReplyRecipients(detail)
    }
    return ComposeSnapshot(
        toText = formatEmailListText(recipients.to),
        ccText = formatEmailListText(recipients.cc),
        bccText = "",
        subject = buildReplySubject(detail.subject),
        body = "",
    )
}

/** mailComposeForm.ts buildDraftComposePrefill。 */
fun buildDraftComposePrefill(draft: MailDraft): ComposeSnapshot = ComposeSnapshot(
    toText = formatEmailListText(draft.to),
    ccText = formatEmailListText(draft.cc),
    bccText = formatEmailListText(draft.bcc),
    subject = draft.subject,
    body = draft.bodyText.orEmpty(),
)

/** mailComposeForm.ts buildForwardComposePrefillFromMessage（forward-draft 失败的本地兜底）。 */
fun buildForwardComposePrefillFromMessage(detail: MessageDetail): ComposeSnapshot = ComposeSnapshot(
    toText = "",
    ccText = "",
    bccText = "",
    subject = buildForwardSubject(detail.subject),
    body = detail.bodyText.orEmpty(),
)

/** mailComposeForm.ts buildUpsertDraftRequest：文本域解析回结构化 + 回复/转发标记。 */
fun buildUpsertDraftRequest(
    accountId: String,
    snapshot: ComposeSnapshot,
    replyToMessageId: String? = null,
    forwardMessageId: String? = null,
): UpsertDraftRequest = UpsertDraftRequest(
    accountId = accountId,
    to = parseEmailList(snapshot.toText),
    cc = parseEmailList(snapshot.ccText),
    bcc = parseEmailList(snapshot.bccText),
    subject = snapshot.subject.trim(),
    bodyText = snapshot.body,
    replyToMessageId = replyToMessageId,
    forwardMessageId = forwardMessageId,
)

/** mailComposeForm.ts appendContactEmail：联想点击追加（已有分隔尾缀直接接）。 */
fun appendContactEmail(currentText: String, email: String): String {
    val trimmed = currentText.trim()
    if (trimmed.isEmpty()) return email
    if (Regex("[,;]\\s*$").containsMatchIn(trimmed)) return "$trimmed $email"
    return "$trimmed, $email"
}

/** 表单脏检查（RN isDirty）：五文本域 + 附件数对初始快照。 */
fun isComposeDirty(
    current: ComposeSnapshot,
    initial: ComposeSnapshot?,
    attachmentCount: Int,
    initialAttachmentCount: Int,
): Boolean {
    if (initial == null) return false
    return current.toText != initial.toText ||
        current.ccText != initial.ccText ||
        current.bccText != initial.bccText ||
        current.subject != initial.subject ||
        current.body != initial.body ||
        attachmentCount != initialAttachmentCount
}
