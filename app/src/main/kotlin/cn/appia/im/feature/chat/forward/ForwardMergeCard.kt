package cn.appia.im.feature.chat.forward

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.i18n.t
import cn.appia.im.core.theme.LocalAppiaColors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 合并转发卡片（RN components/AppiaMessage/components/ForwardMergeMessage.tsx 同构）：
 * 标题「[xx,yy]的聊天记录」+ 前 2 条「发送者: 内容」预览 + 页脚「聊天记录」；点击进详情。
 * msgData 解析失败 → `message_formaterror`（RN qa-forward-msg-error 同 tag）。
 * 客户端只解析不构造（msgData 由服务端组装）。
 */
@Composable
fun ForwardMergeCard(
    msgData: String?,
    onOpen: (msgData: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    val data = remember(msgData) { parseForwardMsgData(msgData) }

    if (data == null) {
        Text(
            context.t("message_formaterror"),
            color = colors.auxiliaryText,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            modifier = modifier
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("qa-forward-msg-error"),
        )
        return
    }

    val title = remember(data) { buildForwardMergeTitle(data) { context.t(it) } }
    Column(
        modifier
            .background(colors.backgroundColor)
            .clickable { onOpen(msgData.orEmpty(), title) }
            .testTag("qa-forward-msg"),
    ) {
        Text(
            title,
            color = colors.titleText,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        data.messages.take(2).forEach { item -> // RN previews：前 2 条
            Text(
                formatMsgPreview(item),
                color = colors.auxiliaryText,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                maxLines = 1,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
            )
        }
        // RN footerWrapper：顶部分隔线 + padding 12/16
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .height(1.dp)
                .background(colors.borderColor),
        ) {}
        Text(
            context.t("message_chathistory"),
            color = colors.auxiliaryText,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/** RN types/navigation MSG_TYPE_COMPONENTS 键：`msgType === 'forwardMergeMessage'` 检测（AppiaMessage 同值）。 */
const val MERGE_FORWARD_MSG_TYPE = "forwardMergeMessage"

/** RN formatMsgPreview :21-26：`{u.name||u.username}: {msg}`；双方皆空 → 空串。 */
internal fun formatMsgPreview(item: ForwardMessageItem): String {
    val u = runCatching { Json.parseToJsonElement(item.u) as? JsonObject }.getOrNull()
    fun field(key: String): String? =
        (u?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim()?.ifEmpty { null }

    val senderName = field("name") ?: field("username") ?: ""
    val content = item.msg ?: ""
    if (senderName.isEmpty() && content.isEmpty()) return ""
    return if (senderName.isNotEmpty()) "$senderName: $content" else content
}
