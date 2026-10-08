package cn.appia.im.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.datastore.AuthSessionStore
import cn.appia.im.core.i18n.t
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.api.UserPreferencesApi
import cn.appia.im.core.theme.AppiaColors
import cn.appia.im.core.theme.Colors
import cn.appia.im.core.theme.LocalAppiaColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** RN StatusEditScreen maxLength（index.tsx:69）。 */
const val STATUS_MAX_LENGTH = 120

/**
 * 工作签名编辑（M5-T9，RN screens/StatusEditScreen/index.tsx 逐行对照）：
 * - 初值 user.statusText ?? ''；输入 maxLength 120
 * - 保存（RN handleSave :21-32）：users.setStatus({status:'online',
 * 返回；失败 Alert(error_title, message)
 * - 头部左「取消」右「保存」（RN headerLeft/headerRight 文字钮）
 * - 输入行：在线绿点 + 输入框（placeholder status_placeholder、autoFocus）
 */
@Composable
fun StatusEditScreen(
    sdk: RocketSdk?,
    store: AuthSessionStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalAppiaColors.current
    val scope = rememberCoroutineScope()
    val session = remember { store.load() }
    val statusText = session?.user?.statusText.orEmpty()

    var text by remember { mutableStateOf(statusText) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    // RN autoFocus 等价（组合后请求焦点）
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    fun handleSave() {
        if (saving) return
        if (sdk == null) {
            onBack()
            return
        }
        saving = true
        scope.launch {
            try {
                // RN setUserStatus({ status: 'online', message: text })
                UserPreferencesApi.setUserStatus(sdk, status = "online", message = text)
                store.mergeStatusText(text)
                onBack()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.toString() // RN Alert(error_title, message)
            } finally {
                saving = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.backgroundColor)
            .testTag("qa-status-edit-screen"),
    ) {
        // 头部：左「取消」右「保存」（RN headerLeft/headerRight 文字钮）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                context.t("cancel"),
                color = colors.auxiliaryText,
                fontSize = 16.sp,
                modifier = Modifier
                    .padding(8.dp)
                    .clickable(onClick = onBack)
                    .testTag("qa-status-edit-cancel"),
            )
            Box(Modifier.weight(1f))
            Text(
                context.t("save"),
                color = colors.tintColor,
                fontSize = 16.sp,
                modifier = Modifier
                    .padding(8.dp)
                    .clickable(enabled = !saving, onClick = ::handleSave)
                    .testTag("qa-status-edit-save"),
            )
        }

        // 输入容器（RN inputContainer：绿点 + 输入框）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.chatComponentBackground)
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(AppiaColors.status.online),
            )
            BasicTextField(
                value = text,
                onValueChange = { if (it.length <= STATUS_MAX_LENGTH) text = it },
                textStyle = TextStyle(color = colors.titleText, fontSize = 16.sp),
                cursorBrush = SolidColor(colors.tintColor),
                singleLine = true,
                decorationBox = { innerTextField ->
                    Box(
                        Modifier.padding(start = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (text.isEmpty()) {
                            Text(
                                context.t("status_placeholder"),
                                color = Colors.textDisabled,
                                fontSize = 16.sp,
                            )
                        }
                        innerTextField()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .testTag("qa-status-edit-input"),
            )
        }
    }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text(context.t("error_title")) },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { error = null }) { Text(context.t("common_close")) }
            },
        )
    }
}
