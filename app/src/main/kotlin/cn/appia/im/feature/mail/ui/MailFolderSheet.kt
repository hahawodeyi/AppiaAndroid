package cn.appia.im.feature.mail.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.i18n.t
import cn.appia.im.core.mail.MailFolder
import cn.appia.im.core.theme.Colors
import cn.appia.im.feature.mail.shouldShowFolder

/**
 * 文件夹弹层（RN MailFolderSheet.tsx 移植）：账户邮箱 + 未读开关行 + 文件夹行
 * （selected 高亮 + 600）。入参 folders 应传 displayFolders（虚拟 `__drafts__` 已前置），
 * 层内再过一遍 shouldShowFolder（RN 双重过滤同构）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailFolderSheet(
    accountEmail: String,
    folders: List<MailFolder>,
    activeFolder: String,
    unreadOnly: Boolean,
    onUnreadOnlyChange: (Boolean) -> Unit,
    onSelectFolder: (String) -> Unit,
    onDismiss: () -> Unit,
    resolveFolderLabel: (MailFolder) -> String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Colors.bgBase,
        modifier = modifier,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .testTag("mail-folder-sheet"),
        ) {
            Text(accountEmail, fontSize = 14.sp, color = Colors.textPrimary, modifier = Modifier.padding(bottom = 12.dp))
            Row(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(context.t("mail_unread_only"), fontSize = 14.sp, color = Colors.textPrimary)
                Switch(
                    checked = unreadOnly,
                    onCheckedChange = onUnreadOnlyChange,
                    modifier = Modifier.testTag("mail-folder-unread-switch"),
                )
            }
            folders.filter(::shouldShowFolder).forEach { folder ->
                val selected = folder.path == activeFolder
                Text(
                    resolveFolderLabel(folder),
                    fontSize = 16.sp,
                    color = if (selected) Colors.primary else Colors.textPrimary,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSelectFolder(folder.path)
                            onDismiss()
                        }
                        .padding(vertical = 12.dp)
                        .testTag("mail-folder-item-${folder.path}"),
                )
            }
        }
    }
}
