package cn.appia.im.feature.roominfo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.i18n.t
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.api.RoomSettingsApi
import cn.appia.im.core.network.api.SaveRoomSettingsParams
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.feature.chat.ui.RoomHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// RN styles.ts 硬编码色
private val HintGray = Color(0xFF86909C)
private val InputDark = Color(0xFF1D2129)
private val InputUnderline = Color(0xFFE5E6EB)
private val SaveBlue = Color(0xFF1677FF)
private val PlaceholderGray = Color(0xFFC9CDD4)

/** RN MAX_NAME_LENGTH = 80。 */
private const val MAX_NAME_LENGTH = 80

/** RN index.tsx:21-24：fname → dname → ''（trim 后空串逐级回退）。 */
internal fun channelNameEditInitial(chat: ChatEntity?): String =
    chat?.fname?.trim()?.takeIf { it.isNotEmpty() } ?: chat?.dname?.trim().orEmpty()

/**
 * 改名屏（RN screens/RoomChannelNameEditScreen/index.tsx）：
 * fname/dname 编辑 max80 → `saveRoomSettings {roomName}` 成功返回；
 * `error-invalid-room-name` → i18n error_invalid_room_name 插 room_name 友好 Alert，
 * 其余错误原文 Alert。标题 c 房 editChannelName / 其他 editGroupName（RN 路由 options 同分支）。
 * 头部左「取消」右「保存」（RN setOptions headerLeft/headerRight）。
 */
@Composable
fun RoomChannelNameEditScreen(
    rid: String,
    roomType: String,
    chat: ChatEntity?,
    sdk: RocketSdk?,
    onBack: () -> Unit,
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val initialName = channelNameEditInitial(chat)
    var name by remember(initialName) { mutableStateOf(initialName) }
    var saving by remember { mutableStateOf(false) }
    var alert by remember { mutableStateOf<String?>(null) }

    val trimmed = name.trim()
    val canSave = trimmed.isNotEmpty() && trimmed != initialName && !saving

    val title = if (roomType == "c") context.t("roominfo_editchannelname") else context.t("roominfo_editgroupname")

    // RN handleSave :35-51：saveRoomSettings {roomName} → 成功 goBack；
    // error-invalid-room-name 消息（ApiException message 含 reason）→ i18n 友好文案
    fun handleSave() {
        if (!canSave || sdk == null) return
        saving = true
        scope.launch {
            try {
                RoomSettingsApi.postSaveRoomSettings(
                    sdk, rid, SaveRoomSettingsParams(roomName = trimmed),
                )
                onBack()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: e.toString()
                alert = if (msg.contains("error-invalid-room-name")) {
                    context.t("error_invalid_room_name").replace("{{room_name}}", trimmed)
                } else {
                    msg
                }
            } finally {
                saving = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(colors.backgroundColor)) {
        RoomHeader(
            title = title,
            onBack = onBack,
            headerAction = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (saving) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp))
                    Text(
                        context.t("save"),
                        color = SaveBlue.copy(alpha = if (canSave) 1f else 0.4f), // RN opacity 0.4 禁用态
                        fontSize = 16.sp,
                        modifier = Modifier
                            .padding(end = 16.dp)
                            .clickable(enabled = canSave, onClick = { handleSave() })
                            .testTag("qa-channel-name-save"),
                    )
                }
            },
        )
        Text(
            context.t("roominfo_namechangehint"),
            Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
            color = HintGray,
            fontSize = 14.sp,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp),
        ) {
            BasicTextField(
                value = name,
                // RN TextInput maxLength={80}：超长截断（粘贴整段取前 80）而非拒收
                onValueChange = { name = it.take(MAX_NAME_LENGTH) },
                textStyle = TextStyle(color = InputDark, fontSize = 16.sp),
                cursorBrush = SolidColor(colors.tintColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .testTag("qa-channel-name-input"),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        inner()
                        if (name.isEmpty()) {
                            Text(
                                context.t("roominfo_channelnameplaceholder"),
                                color = PlaceholderGray,
                                fontSize = 16.sp,
                            )
                        }
                    }
                },
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .height(0.5.dp)
                    .background(InputUnderline), // RN hairlineWidth
            )
        }
    }

    alert?.let { msg ->
        AlertDialog(
            onDismissRequest = { alert = null },
            title = { Text(context.t("error_title")) },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { alert = null }) { Text(context.t("common_close")) }
            },
        )
    }
}
