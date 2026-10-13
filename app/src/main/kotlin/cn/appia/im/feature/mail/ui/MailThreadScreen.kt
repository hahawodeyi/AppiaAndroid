package cn.appia.im.feature.mail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.i18n.t
import cn.appia.im.core.mail.MailClient
import cn.appia.im.core.mail.MailThreadMessage
import cn.appia.im.core.theme.Colors
import cn.appia.im.feature.chat.ui.RoomHeader
import cn.appia.im.feature.mail.MailThreadLoadResult
import cn.appia.im.feature.mail.loadReplyThreadMessages
import cn.appia.im.feature.mail.resolveThreadMessagePreview
import cn.appia.im.feature.mail.resolveThreadSubject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 邮件线程（RN MailThreadScreen 移植）：
 * - loadReplyThreadMessages 三级降级（thread 端点 → threadId/conversationId 列表查询 → 仅锚点）
 * - 标题：result.subject || 路由 subject || mail_thread_title
 * - 气泡：锚点加蓝色边框；sender+time 头；preview 6 行截断；点按 → 详情
 * - 底栏：回复按钮 → 撰写 reply 模式（T5 接线）
 */
@Composable
fun MailThreadScreen(
    client: MailClient,
    accountId: String,
    messageId: String,
    routeSubject: String?,
    onBack: () -> Unit,
    onOpenDetail: (messageId: String) -> Unit,
    onReply: (messageId: String, accountId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var result by remember(messageId) { mutableStateOf<MailThreadLoadResult?>(null) }
    var loading by remember(messageId) { mutableStateOf(true) }
    var loadError by remember(messageId) { mutableStateOf(false) }

    fun load() {
        scope.launch {
            loading = true
            loadError = false
            try {
                result = withContext(Dispatchers.IO) {
                    loadReplyThreadMessages(client, accountId, messageId, routeSubject, context.t("mail_no_subject"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                result = null
                loadError = true
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(messageId) { load() }

    val title = result?.subject?.takeIf { it.isNotBlank() }
        ?: routeSubject?.takeIf { it.isNotBlank() }
        ?: context.t("mail_thread_title")

    Column(modifier.fillMaxSize().background(Colors.bgTertiary)) {
        RoomHeader(title = title, onBack = onBack)
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Colors.primary)
            }
            loadError || result == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(context.t("mail_load_failed"), fontSize = 14.sp, color = Colors.textTertiary)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        context.t("mail_retry"),
                        fontSize = 14.sp,
                        color = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier
                            .background(Colors.primary, RoundedCornerShape(8.dp))
                            .clickable(onClick = ::load)
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            else -> {
                val r = result!!
                LazyColumn(Modifier.fillMaxSize()) {
                    items(r.messages, key = { it.id }) { message ->
                        ThreadBubble(
                            message = message,
                            isAnchor = message.id == r.anchorMessageId,
                            onClick = { onOpenDetail(message.id) },
                        )
                    }
                }
                // 底栏回复按钮（RN replyBtn）
                Text(
                    context.t("mail_btn_reply"),
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .background(Colors.primary, RoundedCornerShape(8.dp))
                        .clickable { onReply(r.anchorMessageId, r.accountId) }
                        .padding(vertical = 12.dp)
                        .testTag("mail-thread-reply"),
                )
            }
        }
    }
}

/** RN thread bubble：锚点蓝边框（rgba(40,120,255,0.35)），sender+time 头，preview ≤6 行。 */
@Composable
private fun ThreadBubble(
    message: MailThreadMessage,
    isAnchor: Boolean,
    onClick: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Box(
        Modifier.fillMaxWidth()
            .background(Colors.bgBase)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Colors.bgBase, RoundedCornerShape(12.dp))
                .then(
                    if (isAnchor) Modifier.padding(8.dp).background(
                        Colors.bgBase,
                        RoundedCornerShape(12.dp),
                    ).border(1.dp, Colors.primary.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    else Modifier.padding(8.dp)
                )
                .clickable(onClick = onClick)
                .padding(12.dp)
                .testTag("mail-thread-bubble-${message.id}"),
        ) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    message.fromName ?: message.from,
                    Modifier.weight(1f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Colors.textPrimary,
                )
                Text(
                    formatMailDateTime(message.receivedAt),
                    fontSize = 12.sp,
                    color = Colors.textTertiary,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                resolveThreadMessagePreview(message),
                fontSize = 14.sp,
                color = Colors.textSecondary,
                maxLines = 6,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}
