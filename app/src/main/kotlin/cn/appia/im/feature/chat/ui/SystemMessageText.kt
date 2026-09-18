package cn.appia.im.feature.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.i18n.t
import cn.appia.im.core.messaging.SystemMessageTexts
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.core.util.formatRoomMessageHeaderTime

/** RN SystemMessage/index.tsx styles.text：12sp #999 居中（不走主题色板，RN 硬编码）。 */
private val SystemGray = Color(0xFF999999)

/** Context 版 t：资源查 key（小写）+ `{{x}}` 占位替换（RN i18next 语义）。 */
fun systemMessageT(context: android.content.Context): (String, Map<String, String>) -> String =
    { key, params ->
        var s = context.t(key)
        for ((k, v) in params) s = s.replace("{{$k}}", v)
        s
    }

/**
 * 系统消息行（RN SystemMessage：居中 12sp 灰字；文本来自 SystemMessageTexts 逐条映射）。
 * announcement 三型（room_*_announcement）**不走本组件**——isSystemMessageRow 排除后与 RN 同走普通行。
 * rollback-message 且有快照且自己撤回（[isReeditableRollback]）→ 下方「重新编辑」按钮
 * （RN SystemMessage :48-62 + onReedit；反序列化回填输入框作新消息，不进编辑模式，T12 前填 msg）。
 */
@Composable
fun SystemMessageText(
    message: MessageEntity,
    modifier: Modifier = Modifier,
    /** 非空时渲染「重新编辑」（回调回填输入框；RoomScreen 按柄判定传入）。 */
    onReedit: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val colors = LocalAppiaColors.current
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("qa-system-message"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = SystemMessageTexts.infoText(message, systemMessageT(context)),
            color = SystemGray,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
        )
        if (onReedit != null) {
            Text(
                text = context.t("messageaction_reedit"),
                color = colors.primary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clickable(onClick = onReedit)
                    .padding(6.dp)
                    .testTag("qa-system-message-reedit"),
            )
        }
    }
}

/**
 * rollback 分组行（RN RollbackMessageGroup/index.tsx 形态）：头 = 「{{rollbacker}}撤回了{{count}}条」
 * （自己撤回走 self 文案）+ 展开/收起切换；展开后逐条系统文案 + updatedAt 时间。
 */
@Composable
fun RollbackMessageGroup(
    groupMessage: MessageEntity,
    groupMessages: List<MessageEntity>,
    currentUserId: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var expanded by remember(groupMessage._id) { mutableStateOf(false) }
    val t = systemMessageT(context)
    val headerText = run {
        val count = groupMessages.size.toString()
        val rollbacker = parseMessageUser(groupMessage.rollbacker.orEmpty())
        val author = parseMessageUser(groupMessage.u)
        val rollbackerId = rollbacker._id ?: author._id
        val rollbackerName = rollbacker.name ?: author.name
        if (rollbackerId != null && rollbackerId == currentUserId) {
            t("rollback_message_batch_self", mapOf("count" to count))
        } else {
            t("rollback_message_batch", mapOf("rollbacker" to rollbackerName.orEmpty(), "count" to count))
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("qa-rollback-message-group"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(headerText, color = SystemGray, fontSize = 12.sp, textAlign = TextAlign.Center)
            Text(
                text = context.t(if (expanded) "systemmessage_collapse" else "systemmessage_expand"),
                color = SystemGray,
                fontSize = 12.sp,
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .testTag("qa-rollback-message-group-toggle"),
            )
        }
        if (expanded) {
            groupMessages.forEach { msg ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = SystemMessageTexts.infoText(msg, t),
                        color = SystemGray,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = formatRoomMessageHeaderTime(msg._updated_at.toLong()),
                        color = SystemGray,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}
