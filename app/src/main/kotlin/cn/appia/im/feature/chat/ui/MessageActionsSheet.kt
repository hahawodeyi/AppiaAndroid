package cn.appia.im.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.i18n.t
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.feature.chat.MessageAction

/** 撤回/重发 danger 色（RN messageActions.tsx ICON_COLOR #F5455C 硬编码）。 */
private val DangerRed = Color(0xFFF5455C)

/**
 * 消息长按菜单（RN ActionSheet + getOptions 的 Compose 形态）：行 = 标题，撤回/重发红字，
 * testID `qa-action-*` 对照 RN action-*。RN 菜单图标不转录（代码库同款极简 glyph 风格）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MessageActionsSheet(
    actions: List<MessageAction>,
    onAction: (MessageAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalAppiaColors.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 12.dp)) {
            actions.forEach { action ->
                Text(
                    text = context.t(action.titleKey),
                    color = if (action.danger) DangerRed else colors.titleText,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAction(action) }
                        .padding(horizontal = 24.dp, vertical = 14.dp)
                        .testTag("qa-action-${action.testId}"),
                )
            }
        }
    }
}

/**
 * 多选态操作条（RN MultiSelectActionBar :33-117 的文字按钮形态）：计数行（多选取消在右）+
 * 逐条/合并转发、批量撤回（全选中可撤回才可用）；「设为公告」归 M7 不移植。
 */
@Composable
fun MultiSelectActionBar(
    selectedCount: Int,
    canRecall: Boolean,
    onForwardOneByOne: () -> Unit,
    onForwardCombine: () -> Unit,
    onBatchRecall: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalAppiaColors.current
    val hasSelection = selectedCount > 0
    Column(Modifier.fillMaxWidth().background(colors.messageboxBackground)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = context.t("multiselect_selectedcount").replace("{{count}}", selectedCount.toString()),
                color = colors.auxiliaryText,
                fontSize = 13.sp,
                modifier = Modifier.testTag("qa-multiselect-count"),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = context.t("multiselect_cancel"),
                color = colors.primary,
                fontSize = 14.sp,
                modifier = Modifier
                    .clickable(onClick = onCancel)
                    .padding(8.dp)
                    .testTag("qa-multiselect-cancel"),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            MultiSelectBarButton(
                label = context.t("multiselect_forwardonebyone"),
                enabled = hasSelection,
                tag = "qa-multiselect-forward-one",
                onClick = onForwardOneByOne,
            )
            MultiSelectBarButton(
                label = context.t("multiselect_forwardcombine"),
                enabled = hasSelection,
                tag = "qa-multiselect-forward-combine",
                onClick = onForwardCombine,
            )
            MultiSelectBarButton(
                label = context.t("multiselect_batchrecall"),
                enabled = hasSelection && canRecall,
                tag = "qa-multiselect-batch-recall",
                onClick = onBatchRecall,
            )
        }
    }
}

@Composable
private fun MultiSelectBarButton(label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val colors = LocalAppiaColors.current
    Text(
        text = label,
        color = if (enabled) colors.primary else colors.auxiliaryText.copy(alpha = 0.4f),
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag(tag),
    )
}

/**
 * 批量撤回确认弹窗（RN handleBatchRecall :981-1005 Alert.alert）：文案 = buildBatchRecallTip
 * 按 sender 摘要选 key，确定后回调；取消关闭。
 */
@Composable
fun BatchRecallConfirmDialog(
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Text(message, fontSize = 15.sp, textAlign = TextAlign.Center)
        },
        confirmButton = {
            Text(
                context.t("todo_confirm"),
                color = LocalAppiaColors.current.primary,
                fontSize = 15.sp,
                modifier = Modifier
                    .clickable(onClick = onConfirm)
                    .padding(8.dp)
                    .testTag("qa-batch-recall-confirm"),
            )
        },
        dismissButton = {
            Text(
                context.t("cancel"),
                color = LocalAppiaColors.current.auxiliaryText,
                fontSize = 15.sp,
                modifier = Modifier
                    .clickable(onClick = onDismiss)
                    .padding(8.dp)
                    .testTag("qa-batch-recall-cancel"),
            )
        },
    )
}
