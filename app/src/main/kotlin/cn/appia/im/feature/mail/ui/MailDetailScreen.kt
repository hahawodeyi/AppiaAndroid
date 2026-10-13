package cn.appia.im.feature.mail.ui

import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import cn.appia.im.core.i18n.t
import cn.appia.im.core.mail.AttachmentMeta
import cn.appia.im.core.mail.MailClient
import cn.appia.im.core.theme.Colors
import cn.appia.im.feature.chat.ui.RoomHeader
import cn.appia.im.feature.chat.ui.formatFileSize
import cn.appia.im.feature.mail.getMailAttachmentKind
import cn.appia.im.feature.mail.isMailTrashFolder
import cn.appia.im.feature.mail.openMailAttachment
import cn.appia.im.feature.mail.resolveMailAttachmentSizePolicy
import cn.appia.im.feature.mail.shouldShowMailThreadLink
import cn.appia.im.feature.mail.downloadMailAttachment
import cn.appia.im.feature.mail.saveMailAttachmentToDevice
import cn.appia.im.feature.mail.deleteMailMessage
import cn.appia.im.feature.mail.getMailMessageDetail
import cn.appia.im.feature.mail.MailAttachmentSizePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 邮件详情（RN MailDetailScreen 移植）：
 * - 正文：bodyHtml 非空 → **WebView JS 禁用**渲染（坑 10：settings.javaScriptEnabled=false，
 *   RN RenderHtml 无 JS 执行面，原生 WebView 等义需要显式关）；否则 bodyText 纯文本
 * - 删除：Trash 夹（isMailTrashFolder path+name 判定）→「永久删除」二次确认；其余「移入已删除」
 * - 更多菜单：全部回复/转发/删除（RN ActionSheet）
 * - 附件：行点击按大小分级（>25MB 仅下载 / >5MB 确认 / ≤5MB 直接预览）；下载按钮 → Download/appia
 * - 预览分发（openMailAttachment）：image → 系统查看器；pdf → 文档预览；其余 → ACTION_VIEW
 */

/** 附件预览/下载弹窗（RN Alert 三态分级 → Compose AlertDialog 状态机）。 */
private sealed interface AttachmentPrompt {
    data class TooLarge(val attachment: AttachmentMeta, val sizeLabel: String) : AttachmentPrompt
    data class Large(val attachment: AttachmentMeta, val sizeLabel: String) : AttachmentPrompt
}

@Composable
fun MailDetailScreen(
    client: MailClient,
    messageId: String,
    folders: List<cn.appia.im.core.mail.MailFolder>,
    onBack: () -> Unit,
    onReply: (messageId: String, accountId: String) -> Unit,
    onReplyAll: (messageId: String, accountId: String) -> Unit,
    onForward: (messageId: String, accountId: String) -> Unit,
    onViewThread: (messageId: String, accountId: String, subject: String) -> Unit,
    /** 附件预览分发（image → 图片查看器 / video → 播放器 / pdf → 文档预览——路由侧接线）。 */
    onOpenAttachment: (file: File, attachment: AttachmentMeta) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var detail by remember(messageId) { mutableStateOf<cn.appia.im.core.mail.MessageDetail?>(null) }
    var loading by remember(messageId) { mutableStateOf(true) }
    var loadError by remember(messageId) { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var attachmentBusyPart by remember { mutableStateOf<String?>(null) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var deleteConfirmOpen by remember { mutableStateOf(false) }
    var attachmentPrompt by remember { mutableStateOf<AttachmentPrompt?>(null) }

    fun loadDetail() {
        scope.launch {
            loading = true
            loadError = false
            try {
                detail = withContext(Dispatchers.IO) { getMailMessageDetail(client, messageId) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                detail = null
                loadError = true
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(messageId) { loadDetail() }

    fun handleDelete() {
        val d = detail ?: return
        if (deleting) return
        scope.launch {
            deleting = true
            try {
                withContext(Dispatchers.IO) { deleteMailMessage(client, d.id) }
                onBack()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    (e as? cn.appia.im.core.mail.MailApiException)?.message ?: context.t("mail_load_failed"),
                    Toast.LENGTH_SHORT,
                ).show()
            } finally {
                deleting = false
            }
        }
    }

    fun runPreviewAttachment(attachment: AttachmentMeta) {
        val d = detail ?: return
        scope.launch {
            attachmentBusyPart = attachment.part
            Toast.makeText(context, context.t("mail_attachment_preparing"), Toast.LENGTH_SHORT).show()
            try {
                val file = withContext(Dispatchers.IO) {
                    downloadMailAttachment(context, client, d.id, attachment)
                }
                // image/video/pdf → 路由侧应用内预览；其余 → 系统 ACTION_VIEW 兜底
                when (getMailAttachmentKind(attachment)) {
                    cn.appia.im.feature.mail.MailAttachmentKind.IMAGE,
                    cn.appia.im.feature.mail.MailAttachmentKind.VIDEO,
                    -> onOpenAttachment(file, attachment)
                    cn.appia.im.feature.mail.MailAttachmentKind.FILE ->
                        if (attachment.filename.substringAfterLast('.', "").lowercase() == "pdf") {
                            onOpenAttachment(file, attachment)
                        } else if (!openMailAttachment(context, file, attachment)) {
                            Toast.makeText(context, context.t("mail_attachment_open_failed"), Toast.LENGTH_SHORT).show()
                        }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(context, context.t("mail_load_failed"), Toast.LENGTH_SHORT).show()
            } finally {
                attachmentBusyPart = null
            }
        }
    }

    fun runDownloadAttachment(attachment: AttachmentMeta) {
        val d = detail ?: return
        scope.launch {
            attachmentBusyPart = attachment.part
            Toast.makeText(context, context.t("mail_attachment_downloading"), Toast.LENGTH_SHORT).show()
            try {
                withContext(Dispatchers.IO) { saveMailAttachmentToDevice(context, client, d.id, attachment) }
                Toast.makeText(context, context.t("mail_attachment_download_complete"), Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(context, context.t("mail_attachment_download_failed"), Toast.LENGTH_SHORT).show()
            } finally {
                attachmentBusyPart = null
            }
        }
    }

    Column(modifier.fillMaxSize().background(Colors.bgTertiary)) {
        RoomHeader(
            title = context.t("mail_detail_title"),
            onBack = onBack,
            headerAction = {
                if (detail != null && !loading && !loadError) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            context.t("mail_btn_reply"),
                            color = if (deleting) Colors.textDisabled else Colors.primary,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .padding(end = 10.dp)
                                .clickable(enabled = !deleting) { detail?.let { onReply(it.id, it.accountId) } }
                                .testTag("mail-detail-reply"),
                        )
                        Box {
                            Text(
                                "⋯",
                                color = if (deleting) Colors.textDisabled else Colors.textPrimary,
                                fontSize = 20.sp,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clickable(enabled = !deleting) { moreMenuOpen = true }
                                    .testTag("mail-detail-more"),
                            )
                            DropdownMenu(expanded = moreMenuOpen, onDismissRequest = { moreMenuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(context.t("mail_btn_replyall")) },
                                    onClick = {
                                        moreMenuOpen = false
                                        detail?.let { onReplyAll(it.id, it.accountId) }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(context.t("mail_forward")) },
                                    onClick = {
                                        moreMenuOpen = false
                                        detail?.let { onForward(it.id, it.accountId) }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(context.t("mail_delete"), color = Colors.danger) },
                                    onClick = {
                                        moreMenuOpen = false
                                        deleteConfirmOpen = true
                                    },
                                )
                            }
                        }
                    }
                }
            },
        )

        when {
            loading -> CenterState {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Colors.primary)
                Spacer(Modifier.height(12.dp))
                Text(context.t("mail_loading"), fontSize = 14.sp, color = Colors.textTertiary)
            }
            loadError || detail == null -> CenterState {
                Text(context.t("mail_load_failed"), fontSize = 14.sp, color = Colors.textTertiary)
                Spacer(Modifier.height(16.dp))
                Text(
                    context.t("mail_retry"),
                    fontSize = 14.sp,
                    color = Color.White,
                    modifier = Modifier
                        .background(Colors.primary, RoundedCornerShape(8.dp))
                        .clickable(onClick = ::loadDetail)
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .testTag("mail-detail-retry"),
                )
            }
            else -> {
                val d = detail!!
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    // ── 头卡：主题 + 线程链接 + From/To/Cc/Time ──
                    Column(
                        Modifier.fillMaxWidth().background(Colors.bgBase).padding(16.dp),
                    ) {
                        Text(
                            d.subject.trim().ifEmpty { context.t("mail_no_subject") },
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Colors.textPrimary,
                        )
                        if (shouldShowMailThreadLink(d)) {
                            Text(
                                context.t("mail_view_thread") + if ((d.replyCount ?: 0) > 0) " (${d.replyCount})" else "",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = Colors.primary,
                                modifier = Modifier
                                    .padding(top = 10.dp)
                                    .clickable { onViewThread(d.id, d.accountId, d.subject) }
                                    .testTag("mail-detail-view-thread"),
                            )
                        }
                        MetaRow(context.t("mail_label_from"), formatDetailAddress(d.from, d.fromName))
                        d.to.filter { it.isNotBlank() }.joinToString(", ").takeIf { it?.isNotEmpty() == true }?.let {
                            MetaRow(context.t("mail_label_to"), it)
                        }
                        d.cc.filter { it.isNotBlank() }.joinToString(", ").takeIf { it?.isNotEmpty() == true }?.let {
                            MetaRow(context.t("mail_label_cc"), it)
                        }
                        MetaRow(context.t("mail_label_time"), formatMailDateTime(d.receivedAt))
                    }
                    // ── 正文卡：HTML（WebView JS 禁用——坑 10）或纯文本 ──
                    Column(
                        Modifier.fillMaxWidth().padding(top = 8.dp).background(Colors.bgBase).padding(16.dp),
                    ) {
                        if (!d.bodyHtml.isNullOrBlank()) {
                            MailHtmlBody(html = d.bodyHtml)
                        } else {
                            Text(
                                d.bodyText?.trim().orEmpty(),
                                fontSize = 15.sp,
                                color = Colors.textSecondary,
                            )
                        }
                    }
                    // ── 附件卡 ──
                    if (d.attachments.isNotEmpty()) {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp)
                                .background(Colors.bgBase).padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text(
                                context.t("mail_attachments"),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Colors.textPrimary,
                            )
                            d.attachments.forEach { attachment ->
                                val isBusy = attachmentBusyPart == attachment.part
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(
                                        Modifier
                                            .weight(1f)
                                            .clickable(enabled = !isBusy) {
                                                val sizeLabel = formatFileSize(attachment.size.toDouble())
                                                when (resolveMailAttachmentSizePolicy(attachment.size)) {
                                                    MailAttachmentSizePolicy.DOWNLOAD_ONLY ->
                                                        attachmentPrompt = AttachmentPrompt.TooLarge(attachment, sizeLabel)
                                                    MailAttachmentSizePolicy.CONFIRM_PREVIEW ->
                                                        attachmentPrompt = AttachmentPrompt.Large(attachment, sizeLabel)
                                                    MailAttachmentSizePolicy.ALLOW_PREVIEW -> runPreviewAttachment(attachment)
                                                }
                                            }
                                            .testTag("mail-attachment-${attachment.part}"),
                                    ) {
                                        Text(attachment.filename, fontSize = 14.sp, color = Colors.textPrimary)
                                        Text(
                                            formatFileSize(attachment.size.toDouble()),
                                            fontSize = 12.sp,
                                            color = Colors.textTertiary,
                                        )
                                    }
                                    Box(
                                        Modifier
                                            .size(36.dp)
                                            .clickable(enabled = !isBusy) { runDownloadAttachment(attachment) }
                                            .testTag("mail-attachment-download-${attachment.part}"),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (isBusy) {
                                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Colors.primary)
                                        } else {
                                            Text("↓", fontSize = 18.sp, color = Colors.primary)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    // ── 删除二次确认（Trash → 永久）──
    if (deleteConfirmOpen && detail != null) {
        val permanent = isMailTrashFolder(detail!!.folder, folders)
        AlertDialog(
            onDismissRequest = { deleteConfirmOpen = false },
            title = { Text(context.t("mail_delete")) },
            text = {
                Text(context.t(if (permanent) "mail_delete_permanent_confirm" else "mail_delete_confirm"))
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteConfirmOpen = false
                    handleDelete()
                }) { Text(context.t("mail_delete"), color = Colors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmOpen = false }) { Text(context.t("cancel")) }
            },
        )
    }

    // ── 附件分级弹窗 ──
    when (val prompt = attachmentPrompt) {
        is AttachmentPrompt.TooLarge -> {
            AlertDialog(
                onDismissRequest = { attachmentPrompt = null },
                title = { Text(context.t("mail_attachment_too_large_title")) },
                text = {
                    Text(
                        context.t("mail_attachment_too_large_message")
                            .replace("{{size}}", prompt.sizeLabel)
                            .replace("{size}", prompt.sizeLabel)
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        attachmentPrompt = null
                        runDownloadAttachment(prompt.attachment)
                    }) { Text(context.t("mail_attachment_download_only")) }
                },
                dismissButton = {
                    TextButton(onClick = { attachmentPrompt = null }) { Text(context.t("cancel")) }
                },
            )
        }
        is AttachmentPrompt.Large -> {
            AlertDialog(
                onDismissRequest = { attachmentPrompt = null },
                title = { Text(context.t("mail_attachment_large_title")) },
                text = {
                    Text(
                        context.t("mail_attachment_large_message")
                            .replace("{{size}}", prompt.sizeLabel)
                            .replace("{size}", prompt.sizeLabel)
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        attachmentPrompt = null
                        runPreviewAttachment(prompt.attachment)
                    }) { Text(context.t("mail_attachment_preview")) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        attachmentPrompt = null
                        runDownloadAttachment(prompt.attachment)
                    }) { Text(context.t("mail_attachment_download_only")) }
                },
            )
        }
        null -> Unit
    }
}

/** From/To/Cc 地址：`name <email>` 或裸 email（RN formatMailAddress 同构）。 */
private fun formatDetailAddress(email: String, name: String?): String =
    if (name != null) "$name <$email>" else email

/** RN metaRow：56dp 标签列 + 值列。 */
@Composable
private fun MetaRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(label, Modifier.width(56.dp), fontSize = 13.sp, color = Colors.textTertiary)
        Text(value, Modifier.weight(1f), fontSize = 13.sp, color = Colors.textSecondary)
    }
}

/** 居中态（RN centerState）。 */
@Composable
private fun CenterState(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        content()
    }
}

/**
 * HTML 正文（坑 10）：WebView 渲染 bodyHtml——**JS 禁用**（邮件 HTML 是不可信输入；
 * RN RenderHtml 无脚本执行面，原生 WebView 必须显式 javaScriptEnabled=false 等义）。
 * loadDataWithBaseURL(null,...) 阻断相对资源加载；wrapContentView 高度自适应内容。
 */
@Composable
private fun MailHtmlBody(html: String) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false // 坑 10：JS 禁用
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                isVerticalScrollBarEnabled = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { webView -> webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp),
    )
}
