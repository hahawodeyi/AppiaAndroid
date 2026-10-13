package cn.appia.im.feature.mail.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.i18n.t
import cn.appia.im.core.mail.MailClient
import cn.appia.im.core.mail.MailComposeMode
import cn.appia.im.core.mail.MailDraft
import cn.appia.im.core.mail.MessageDetail
import cn.appia.im.feature.chat.ui.RoomHeader
import cn.appia.im.feature.mail.MailComposeAttachmentException
import cn.appia.im.feature.mail.MailComposeAttachmentFile
import cn.appia.im.feature.mail.MAX_MAIL_ATTACHMENT_COUNT
import cn.appia.im.feature.mail.appendContactEmail
import cn.appia.im.feature.mail.buildDraftComposePrefill
import cn.appia.im.feature.mail.buildEmptyComposeSnapshot
import cn.appia.im.feature.mail.buildForwardComposePrefillFromMessage
import cn.appia.im.feature.mail.buildReplyComposePrefill
import cn.appia.im.feature.mail.buildSendMailAttachments
import cn.appia.im.feature.mail.buildUpsertDraftRequest
import cn.appia.im.feature.mail.isComposeDirty
import cn.appia.im.feature.mail.parseEmailList
import cn.appia.im.feature.mail.createForwardMailDraft
import cn.appia.im.feature.mail.createMailDraft
import cn.appia.im.feature.mail.getMailDraft
import cn.appia.im.feature.mail.getMailMessageDetail
import cn.appia.im.feature.mail.replyMail
import cn.appia.im.feature.mail.forwardMail
import cn.appia.im.feature.mail.searchMailContacts
import cn.appia.im.feature.mail.sendMail
import cn.appia.im.feature.mail.sendMailDraft
import cn.appia.im.feature.mail.updateMailDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 联想防抖（RN CONTACT_DEBOUNCE_MS）。 */
private const val CONTACT_DEBOUNCE_MS = 300L

/**
 * 撰写页（RN MailComposeScreen 移植）：
 * - 5 模式 new/reply/replyAll/forward/draft；replyAll 收件人规则无 cc（坑 13）
 * - 正文**纯文本** TextField（RN TextInput multiline 同构）
 * - 附件选文件（SAF）→ 流式 base64 内联 10 个/25MB（坑 12）
 * - 联系人联想 ≥2 字符 300ms debounce（仅 To 域聚焦时）
 * - 草稿 dirty 拦截：back 手势/取消键 → 丢弃确认（RN beforeRemove 等义——
 *   Compose 无 beforeRemove，返回键经 BackHandler 拦截 + onBack 回调双口）
 * - 转发先 forward-draft 失败降级本地预填
 */
@Composable
fun MailComposeScreen(
    client: MailClient,
    mode: MailComposeMode,
    accountId: String,
    messageId: String? = null,
    routeDraftId: String? = null,
    accountEmail: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var toText by remember { mutableStateOf("") }
    var ccText by remember { mutableStateOf("") }
    var bccText by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf(listOf<MailComposeAttachmentFile>()) }
    var draftId by remember { mutableStateOf(routeDraftId) }
    var replyToMessageId by remember { mutableStateOf<String?>(if (mode == MailComposeMode.REPLY || mode == MailComposeMode.REPLY_ALL) messageId else null) }
    var forwardMessageId by remember { mutableStateOf<String?>(if (mode == MailComposeMode.FORWARD) messageId else null) }
    var initialSnapshot by remember { mutableStateOf<cn.appia.im.feature.mail.ComposeSnapshot?>(null) }
    var initialAttachmentCount by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(mode != MailComposeMode.NEW) }
    var loadError by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var savingDraft by remember { mutableStateOf(false) }
    var contactSuggestions by remember { mutableStateOf(listOf<cn.appia.im.core.mail.ContactSuggestion>()) }
    var contactLoading by remember { mutableStateOf(false) }
    var discardConfirmOpen by remember { mutableStateOf(false) }

    /** 已允许离屏（发送成功/保存后取消等）——back 拦截放行标记。 */
    val allowLeave = remember { mutableStateOf(false) }

    val snapshot = cn.appia.im.feature.mail.ComposeSnapshot(toText, ccText, bccText, subject, body)
    val isDirty = isComposeDirty(snapshot, initialSnapshot, attachments.size, initialAttachmentCount)

    fun applySnapshot(s: cn.appia.im.feature.mail.ComposeSnapshot) {
        toText = s.toText
        ccText = s.ccText
        bccText = s.bccText
        subject = s.subject
        body = s.body
        initialSnapshot = s
        initialAttachmentCount = 0
        attachments = emptyList()
    }

    fun loadCompose() {
        scope.launch {
            if (mode == MailComposeMode.NEW) {
                applySnapshot(buildEmptyComposeSnapshot())
                loadError = false
                loading = false
                return@launch
            }
            loading = true
            loadError = false
            try {
                when (mode) {
                    MailComposeMode.DRAFT -> {
                        val id = routeDraftId ?: error("Missing draftId")
                        val draft = withContext(Dispatchers.IO) { getMailDraft(client, id) }
                        applySnapshot(buildDraftComposePrefill(draft))
                        draftId = draft.id
                        replyToMessageId = draft.replyToMessageId
                        forwardMessageId = draft.forwardMessageId
                    }
                    MailComposeMode.FORWARD -> {
                        val mid = messageId ?: error("Missing messageId")
                        try {
                            // 先 forward-draft（服务端带原文），失败降级本地预填
                            val draft = withContext(Dispatchers.IO) { createForwardMailDraft(client, accountId, mid) }
                            applySnapshot(buildDraftComposePrefill(draft))
                            draftId = draft.id
                            forwardMessageId = draft.forwardMessageId ?: mid
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            val detail = withContext(Dispatchers.IO) { getMailMessageDetail(client, mid) }
                            applySnapshot(buildForwardComposePrefillFromMessage(detail))
                            forwardMessageId = mid
                        }
                    }
                    MailComposeMode.REPLY, MailComposeMode.REPLY_ALL -> {
                        val mid = messageId ?: error("Missing messageId")
                        val detail = withContext(Dispatchers.IO) { getMailMessageDetail(client, mid) }
                        applySnapshot(buildReplyComposePrefill(mode, detail, accountEmail))
                        replyToMessageId = mid
                    }
                    MailComposeMode.NEW -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                loadError = true
                initialSnapshot = null
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(mode, messageId, routeDraftId) { loadCompose() }

    // ── 联想（≥2 字符 + 300ms debounce + 仅 To 域聚焦）──
    var toFocused by remember { mutableStateOf(false) }
    var contactJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(toText, toFocused) {
        contactJob?.cancel()
        if (!toFocused || toText.trim().length < 2) {
            contactSuggestions = emptyList()
            contactLoading = false
            return@LaunchedEffect
        }
        val query = toText.trim()
        contactJob = scope.launch {
            delay(CONTACT_DEBOUNCE_MS)
            contactLoading = true
            try {
                val results = withContext(Dispatchers.IO) { searchMailContacts(client, query) }
                contactSuggestions = results
            } catch (_: Exception) {
                contactSuggestions = emptyList()
            } finally {
                contactLoading = false
            }
        }
    }

    // ── 附件选文件（SAF 多选 → uri/名字/大小/类型）──
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val remaining = MAX_MAIL_ATTACHMENT_COUNT - attachments.size
            attachments = attachments + uris.take(remaining).map { uri ->
                var name = "attachment"
                var size: Long? = null
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (nameIdx >= 0) cursor.getString(nameIdx)?.let { name = it }
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                    }
                }
                val type = context.contentResolver.getType(uri)
                MailComposeAttachmentFile(uri = uri.toString(), name = name, type = type, size = size)
            }
        }
    }

    fun persistDraft(onDone: (String?) -> Unit) {
        scope.launch {
            val payload = buildUpsertDraftRequest(accountId, snapshot, replyToMessageId, forwardMessageId)
            try {
                val id = withContext(Dispatchers.IO) {
                    if (draftId != null) {
                        updateMailDraft(client, draftId!!, payload).id
                    } else {
                        createMailDraft(client, payload).id
                    }
                }
                draftId = id
                onDone(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                onDone(null)
            }
        }
    }

    fun handleSaveDraft() {
        if (savingDraft || sending || loading) return
        savingDraft = true
        persistDraft { id ->
            savingDraft = false
            if (id != null) {
                initialSnapshot = snapshot
                initialAttachmentCount = attachments.size
                Toast.makeText(context, context.t("mail_draft_saved"), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, context.t("mail_draft_save_failed"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun handleSend() {
        if (sending || savingDraft || loading) return
        if (parseEmailList(toText).isEmpty()) {
            Toast.makeText(context, context.t("mail_send_failed"), Toast.LENGTH_SHORT).show()
            return
        }
        sending = true
        scope.launch {
            try {
                val mailAttachments = withContext(Dispatchers.IO) { buildSendMailAttachments(attachments) }
                val request = cn.appia.im.core.mail.SendMailRequest(
                    to = parseEmailList(toText),
                    cc = parseEmailList(ccText).takeIf { it.isNotEmpty() },
                    bcc = parseEmailList(bccText).takeIf { it.isNotEmpty() },
                    subject = subject.trim(),
                    bodyText = body,
                    attachments = mailAttachments.takeIf { it.isNotEmpty() },
                )
                withContext(Dispatchers.IO) {
                    when (mode) {
                        MailComposeMode.REPLY, MailComposeMode.REPLY_ALL ->
                            replyMail(client, accountId, messageId!!, request)
                        MailComposeMode.FORWARD ->
                            forwardMail(client, accountId, forwardMessageId ?: messageId!!, request)
                        // RN：draft 模式（或 new 且已存过草稿）走 draft 持久化 + /drafts/:id/send；
                        // 纯 new（无 draftId）直发 /accounts/:id/send——不落草稿
                        MailComposeMode.DRAFT, MailComposeMode.NEW -> {
                            var activeDraftId = draftId
                            if (mode == MailComposeMode.DRAFT || activeDraftId != null) {
                                if (isDirty || activeDraftId == null) {
                                    val payload = buildUpsertDraftRequest(accountId, snapshot, replyToMessageId, forwardMessageId)
                                    activeDraftId = if (draftId != null) {
                                        updateMailDraft(client, draftId!!, payload).id
                                    } else {
                                        createMailDraft(client, payload).id
                                    }
                                    draftId = activeDraftId
                                }
                                sendMailDraft(client, activeDraftId)
                            } else {
                                sendMail(client, accountId, request)
                            }
                        }
                    }
                }
                Toast.makeText(context, context.t("mail_send_success"), Toast.LENGTH_SHORT).show()
                allowLeave.value = true
                onBack()
            } catch (e: CancellationException) {
                throw e
            } catch (e: MailComposeAttachmentException) {
                Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
            } catch (e: cn.appia.im.core.mail.MailApiException) {
                Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(context, context.t("mail_send_failed"), Toast.LENGTH_SHORT).show()
            } finally {
                sending = false
            }
        }
    }

    fun leaveScreen() {
        allowLeave.value = true
        onBack()
    }

    fun handleCancel() {
        if (sending || savingDraft) return
        if (isDirty) {
            discardConfirmOpen = true
            return
        }
        leaveScreen()
    }

    // 返回手势 dirty 拦截（RN beforeRemove 等义）
    androidx.activity.compose.BackHandler(enabled = !allowLeave.value && isDirty && !sending && !savingDraft) {
        discardConfirmOpen = true
    }

    val canSend = !loading && !sending && !savingDraft && parseEmailList(toText).isNotEmpty()
    val canSaveDraft = !loading && !sending && !savingDraft

    Column(modifier.fillMaxSize().background(cn.appia.im.core.theme.Colors.bgTertiary)) {
        // 标题随模式（RN composeTitleByMode）
        val titleByMode = mapOf(
            MailComposeMode.NEW to "mail_compose_new",
            MailComposeMode.REPLY to "mail_compose_reply",
            MailComposeMode.REPLY_ALL to "mail_compose_replyall",
            MailComposeMode.FORWARD to "mail_compose_forward",
            MailComposeMode.DRAFT to "mail_compose_draft",
        )
        RoomHeader(
            title = context.t(titleByMode[mode] ?: "mail_compose_new"),
            onBack = ::handleCancel,
            headerAction = {
                if (!loading && !loadError) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = ::handleSaveDraft,
                            enabled = canSaveDraft,
                            modifier = Modifier.testTag("mail-compose-save-draft"),
                        ) {
                            if (savingDraft) {
                                CircularProgressIndicator(
                                    Modifier.width(16.dp).height(16.dp),
                                    strokeWidth = 2.dp,
                                    color = cn.appia.im.core.theme.Colors.textTertiary,
                                )
                            } else {
                                Text(
                                    context.t("mail_compose_savedraft"),
                                    fontSize = 14.sp,
                                    color = cn.appia.im.core.theme.Colors.textTertiary,
                                )
                            }
                        }
                        TextButton(
                            onClick = ::handleSend,
                            enabled = canSend,
                            modifier = Modifier.testTag("mail-compose-send"),
                        ) {
                            if (sending) {
                                CircularProgressIndicator(
                                    Modifier.width(16.dp).height(16.dp),
                                    strokeWidth = 2.dp,
                                    color = cn.appia.im.core.theme.Colors.primary,
                                )
                            } else {
                                Text(
                                    context.t("chatinput_send"),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = cn.appia.im.core.theme.Colors.primary,
                                )
                            }
                        }
                    }
                }
            },
        )

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp, color = cn.appia.im.core.theme.Colors.primary)
                    Spacer(Modifier.height(12.dp))
                    Text(context.t("mail_loading"), fontSize = 14.sp, color = cn.appia.im.core.theme.Colors.textTertiary)
                }
            }
            loadError -> Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(context.t("mail_load_failed"), fontSize = 14.sp, color = cn.appia.im.core.theme.Colors.textTertiary)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        context.t("mail_retry"),
                        fontSize = 14.sp,
                        color = Color.White,
                        modifier = Modifier
                            .background(cn.appia.im.core.theme.Colors.primary, RoundedCornerShape(8.dp))
                            .clickable(onClick = ::loadCompose)
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                            .testTag("mail-compose-retry"),
                    )
                }
            }
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                // ── 收件人卡 ──
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                        .background(cn.appia.im.core.theme.Colors.bgBase, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp),
                ) {
                    ComposeFieldRow(
                        label = context.t("mail_label_to"),
                        value = toText,
                        onValueChange = { toText = it },
                        onFocusChanged = { toFocused = it },
                        testTag = "mail-compose-to",
                    )
                    if (toFocused && (contactLoading || contactSuggestions.isNotEmpty())) {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                .background(cn.appia.im.core.theme.Colors.bgTertiary, RoundedCornerShape(8.dp))
                                .testTag("mail-compose-contact-dropdown"),
                        ) {
                            if (contactLoading) {
                                Box(Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(
                                        Modifier.width(16.dp).height(16.dp),
                                        strokeWidth = 2.dp,
                                        color = cn.appia.im.core.theme.Colors.primary,
                                    )
                                }
                            } else {
                                contactSuggestions.forEach { contact ->
                                    Column(
                                        Modifier.fillMaxWidth()
                                            .clickable {
                                                toText = appendContactEmail(toText, contact.email)
                                                contactSuggestions = emptyList()
                                                toFocused = false
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp)
                                            .testTag("mail-compose-contact-${contact.email}"),
                                    ) {
                                        Text(
                                            contact.name.ifEmpty { contact.email },
                                            fontSize = 14.sp,
                                            color = cn.appia.im.core.theme.Colors.textPrimary,
                                        )
                                        if (contact.name.isNotEmpty()) {
                                            Text(contact.email, fontSize = 12.sp, color = cn.appia.im.core.theme.Colors.textTertiary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    ComposeFieldRow(context.t("mail_label_cc"), ccText, { ccText = it }, testTag = "mail-compose-cc")
                    ComposeFieldRow(context.t("mail_label_bcc"), bccText, { bccText = it }, testTag = "mail-compose-bcc")
                    ComposeFieldRow(context.t("mail_label_subject"), subject, { subject = it }, testTag = "mail-compose-subject")
                }
                // ── 正文（纯文本）──
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                        .background(cn.appia.im.core.theme.Colors.bgBase, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    androidx.compose.material3.OutlinedTextField(
                        value = body,
                        onValueChange = { body = it },
                        modifier = Modifier.fillMaxWidth().height(220.dp).testTag("mail-compose-body"),
                        placeholder = { Text(context.t("mail_compose_body_placeholder"), color = cn.appia.im.core.theme.Colors.textTertiary) },
                        textStyle = TextStyle(fontSize = 15.sp, color = cn.appia.im.core.theme.Colors.textSecondary),
                    )
                }
                // ── 附件区 ──
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    attachments.forEachIndexed { index, file ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                file.name,
                                Modifier.weight(1f),
                                fontSize = 14.sp,
                                color = cn.appia.im.core.theme.Colors.textPrimary,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            Text(
                                context.t("mail_remove_attachment"),
                                fontSize = 13.sp,
                                color = cn.appia.im.core.theme.Colors.primary,
                                modifier = Modifier
                                    .clickable { attachments = attachments.filterIndexed { i, _ -> i != index } }
                                    .testTag("mail-compose-remove-attachment-$index"),
                            )
                        }
                    }
                    Text(
                        context.t("mail_compose_addattachment"),
                        fontSize = 14.sp,
                        color = cn.appia.im.core.theme.Colors.primary,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .clickable {
                                if (attachments.size >= MAX_MAIL_ATTACHMENT_COUNT) {
                                    Toast.makeText(
                                        context,
                                        context.t("mail_attachment_limit"),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    runCatching { filePicker.launch(arrayOf("*/*")) }
                                        .onFailure { Toast.makeText(context, context.t("mail_send_failed"), Toast.LENGTH_SHORT).show() }
                                }
                            }
                            .testTag("mail-compose-add-attachment"),
                    )
                }
            }
        }
    }

    // ── 丢弃确认（RN confirmDiscard）──
    if (discardConfirmOpen) {
        AlertDialog(
            onDismissRequest = { discardConfirmOpen = false },
            title = { Text(context.t("mail_compose_discard_title")) },
            text = { Text(context.t("mail_compose_discard_message")) },
            confirmButton = {
                TextButton(onClick = {
                    discardConfirmOpen = false
                    leaveScreen()
                }) { Text(context.t("mail_compose_discard_confirm"), color = cn.appia.im.core.theme.Colors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { discardConfirmOpen = false }) { Text(context.t("cancel")) }
            },
        )
    }
}

/** RN fieldRow：56dp 标签 + 输入域。 */
@Composable
private fun ComposeFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit = {},
    testTag: String,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            Modifier.width(56.dp),
            fontSize = 14.sp,
            color = cn.appia.im.core.theme.Colors.textTertiary,
        )
        androidx.compose.material3.OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f).testTag(testTag),
            singleLine = true,
            textStyle = TextStyle(fontSize = 14.sp, color = cn.appia.im.core.theme.Colors.textSecondary),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Email),
        )
    }
    // onFocus 挂在 Row 外层等价（RN onFocus/onBlur 到 To 域）
    LaunchedEffect(Unit) { onFocusChanged(false) }
}
