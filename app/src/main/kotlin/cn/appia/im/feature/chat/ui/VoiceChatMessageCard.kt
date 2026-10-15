package cn.appia.im.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.i18n.t
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.core.voice.lib.isVoiceAnsweredOnOtherDevice
import cn.appia.im.core.voice.lib.parseCallMsg
import cn.appia.im.core.voice.voiceJson
import androidx.compose.ui.platform.LocalContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private fun JsonObject.stringField(name: String): String? =
    get(name)?.jsonPrimitive?.contentOrNull

/**
 * oncall 消息卡片（M10-T12 / RN AppiaMessage `oncall → VoiceChatMessage` +
 * lib/appiaMessage/voiceChatMessageDisplay.ts）：
 * calling/talking/end/cancel 通话消息 → 卡片（主文案 + 群聊取消摘要 + 通话小结），
 * 点击 → startFromMessage（guard：!data / phase 非 idle / otherDeviceAccepted）。
 *
 * 录音查看/保存按钮（RN :133-146）按 local_record_path 渲染——录音缓 backlog-m10
 * （progress 裁定②），列恒 null → hasRecord 恒 false，结构保留待接线。
 */

/** RN VoiceChatSummary（voiceChatMessageDisplay.ts:5-8）。 */
internal data class VoiceChatSummary(val markdown: String, val plain: String)

/**
 * oncall 消息的 msgData（RN types/appiaMessage.ts:91-111 IVoiceChatMsgData）。
 * 与房间 callMsg（IVoiceCallMsg / [VoiceCallMsg]）是**不同结构**，不得复用。
 * RN optional → Kotlin 可空 + 默认 null（voiceJson 宽松口径）。
 */
@kotlinx.serialization.Serializable
data class VoiceChatMsgData(
    val initiatorMsg: String? = null,
    val receiverMsg: String? = null,
    val type: String? = null,
    val channelId: String? = null,
    val initiatorName: String? = null,
    val initiatorUsername: String? = null,
    val receiverUsername: String? = null,
    val roomType: String? = null,
    val roomId: String? = null,
    val appiaSummary: String? = null,
    val callMsg: String? = null,
    val userStatus: List<cn.appia.im.core.voice.VoiceUserStatus>? = null,
)

/** RN stripMarkdownLite（:10-11）。 */
private fun stripMarkdownLite(text: String): String =
    text.replace(Regex("[*_~`#>\\[\\]()\\r\\n]"), "").replace(Regex("\\s+"), " ").trim()

/** 消息 msgData（IVoiceChatMsgData）解码——voiceJson 宽松口径（未知键忽略）。 */
private fun parseVoiceChatMsgData(raw: String?): VoiceChatMsgData? = raw?.let {
    runCatching { voiceJson.decodeFromString(VoiceChatMsgData.serializer(), it) }.getOrNull()
}

/** RN parseMessageAuthorUsername（:22-29）：u JSON → username。 */
internal fun parseMessageAuthorUsername(uRaw: String): String? = runCatching {
    (voiceJson.parseToJsonElement(uRaw) as? JsonObject)?.stringField("username")
}.getOrNull()

/**
 * RN getVoiceChatPrimaryText（:32-46）：发送者 == 当前用户 → initiatorMsg，否则 receiverMsg；
 * 双空回退 message.msg，再空由调用方落 message_voiceFallback。
 */
internal fun getVoiceChatPrimaryText(
    data: VoiceChatMsgData,
    authorUsername: String?,
    currentUsername: String?,
    fallbackMsg: String?,
): String? {
    val isAuthor = !authorUsername.isNullOrEmpty() && authorUsername == currentUsername
    val text = if (isAuthor) data.initiatorMsg else data.receiverMsg
    if (!text.isNullOrBlank()) return text
    if (!fallbackMsg.isNullOrBlank()) return fallbackMsg
    return null
}

/**
 * RN buildGroupCancelSummary（:48-84）：type=cancel 且群/频道的取消摘要
 * 「邀请: A、B; 已接: C; 未接: D」。名字接续 RN 用『、』。
 */
internal fun buildGroupCancelSummary(data: VoiceChatMsgData, t: (String) -> String): String {
    val roomType = data.roomType
    if (data.type != "cancel" || (roomType != "p" && roomType != "c")) return ""

    val userStatus = data.userStatus.orEmpty()
    if (userStatus.isEmpty()) return ""

    var inviteUsers = ""
    var answerUsers = ""
    var lostUsers = ""
    // RN buildGroupCancelSummary 的名字接续符 U+3001 顿号（标点，非 UI 文案——不进 i18n）
    val sep = 0x3001.toChar()
    userStatus.forEach { u ->
        if (u.username == data.initiatorUsername) return@forEach
        val name = if (!u.name.isNullOrEmpty()) "${u.name}$sep" else ""
        inviteUsers += name
        val op = u.operation
        if (op.isNullOrEmpty() || op == "reject" || op == "waiting") lostUsers += name else answerUsers += name
    }

    fun trimSuffix(value: String) =
        if (value.isNotEmpty()) value.substring(0, value.length - 1) else t("message_voiceMembersNone")

    val inviteLabel = "${t("message_voiceMembersInvite")}: ${trimSuffix(inviteUsers)}"
    val answerLabel = "${t("message_voiceMembersAnswered")}: ${trimSuffix(answerUsers)}"
    val lostLabel = "${t("message_voiceMembersMissed")}: ${trimSuffix(lostUsers)}"
    return "$inviteLabel; $answerLabel; $lostLabel"
}

/** RN parseVoiceChatSummary（:86-96）：{success, summary} JSON + stripMarkdownLite。 */
internal fun parseVoiceChatSummary(appiaSummary: String?): VoiceChatSummary? {
    if (appiaSummary.isNullOrEmpty()) return null
    return runCatching {
        val parsed = voiceJson.parseToJsonElement(appiaSummary) as? JsonObject ?: return null
        if (parsed.get("success")?.jsonPrimitive?.booleanOrNull != true) return null
        val markdown = parsed.stringField("summary")?.trim().orEmpty()
        if (markdown.isEmpty()) return null
        VoiceChatSummary(markdown = markdown, plain = stripMarkdownLite(markdown))
    }.getOrNull()
}

/** RN shouldShowVoiceChatSummary（:98-101）：type=end 且小结非空。 */
internal fun shouldShowVoiceChatSummary(data: VoiceChatMsgData, summary: VoiceChatSummary?): Boolean =
    data.type == "end" && !summary?.plain.isNullOrEmpty()

/**
 * RN isVoiceChatMsgOtherDeviceAccepted（:104-120）：被叫视角 calling/talking 且
 * callMsg 内本人已在他机接听 → 专用文案 + 禁点。msgData.callMsg 为字符串嵌套 JSON。
 */
internal fun isVoiceChatMsgOtherDeviceAccepted(
    data: VoiceChatMsgData,
    currentUsername: String?,
    localDeviceId: String,
): Boolean {
    if (currentUsername.isNullOrBlank() ||
        data.initiatorUsername == currentUsername ||
        (data.type != "calling" && data.type != "talking") ||
        data.callMsg.isNullOrBlank()
    ) {
        return false
    }
    val callMsg = parseCallMsg(data.callMsg)
    return isVoiceAnsweredOnOtherDevice(callMsg, currentUsername, localDeviceId)
}

/** RN MSG_TYPE_COMPONENTS `oncall` 分发键（AppiaMessage/index.tsx:54）。 */
const val ONCALL_MSG_TYPE = "oncall"

/**
 * oncall 消息卡片（RN VoiceChatMessage.tsx）。点击 → [onCardTap]（装配处
 * `controller.startFromMessage(rid, msgData)`；guard 判定下沉装配处或此处均可——
 * 此处只做展示判定，相位 guard 由 controller.startFromMessage 首行兜底（RN :84 同））。
 */
@Composable
fun VoiceChatMessageCard(
    message: MessageEntity,
    /** 相位 guard（RN onPressCard :83-92：!data || phase!=='idle' || otherDeviceAccepted → return）。 */
    voicePhaseIdle: Boolean,
    currentUsername: String?,
    localDeviceId: String,
    onCardTap: (rid: String, msgData: VoiceChatMsgData) -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalAppiaColors.current
    val data = remember(message.msg_data) { parseVoiceChatMsgData(message.msg_data) }
    val authorUsername = remember(message.u) { parseMessageAuthorUsername(message.u) }

    if (data == null) {
        Row(
            Modifier
                .padding(vertical = 10.dp, horizontal = 12.dp)
                .testTag("qa-voice-chat-error"),
        ) {
            Text(context.t("message_formatError"), color = colors.auxiliaryText, fontSize = 14.sp)
        }
        return
    }

    val otherDeviceAccepted = remember(data, currentUsername, localDeviceId) {
        isVoiceChatMsgOtherDeviceAccepted(data, currentUsername, localDeviceId)
    }
    val primaryText = if (otherDeviceAccepted) {
        context.t("message_voiceOtherDeviceAccepted")
    } else {
        getVoiceChatPrimaryText(data, authorUsername, currentUsername, message.msg)
            ?: context.t("message_voiceFallback")
    }
    val groupSummary = remember(data) { buildGroupCancelSummary(data) { context.t(it) } }
    val voiceSummary = remember(data) { parseVoiceChatSummary(data.appiaSummary) }
    val showSummary = shouldShowVoiceChatSummary(data, voiceSummary)
    val showSeeMore = (voiceSummary?.plain?.length ?: 0) > 40

    val canPress = voicePhaseIdle && !otherDeviceAccepted
    var summaryModalVisible by remember { mutableStateOf(false) }

    Column(
        Modifier
            .clickable(enabled = canPress) {
                val rid = message.rid.trim()
                if (rid.isNotEmpty()) onCardTap(rid, data)
            }
            .testTag("qa-voice-chat-message"),
    ) {
        Row(
            Modifier
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.backgroundColor)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // RN CallIcon（Svg 电话形）：AA 无语音图标资产——✆ 文字符号代位（VoiceCallHost 同先例）
            Text("✆", color = colors.bodyText, fontSize = 16.sp)
            Text(
                primaryText,
                color = colors.bodyText,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 7.dp)
                    .testTag("qa-voice-chat-primary-text"),
            )
        }

        if (groupSummary.isNotEmpty()) {
            Text(
                groupSummary,
                color = colors.bodyText,
                fontSize = 14.sp,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.borderColor)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }

        if (showSummary && voiceSummary != null) {
            Column(
                Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.borderColor)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    voiceSummary.plain,
                    color = colors.bodyText,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showSeeMore) {
                    Text(
                        context.t("message_voiceSeeMore"),
                        color = colors.tintColor,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .padding(start = 4.dp, top = 4.dp)
                            .clickable { summaryModalVisible = true }
                            .testTag("qa-voice-chat-see-more"),
                    )
                }
            }
        }
    }

    if (summaryModalVisible && voiceSummary != null) {
        // RN Modal（:172-201）：半透明遮罩 + 卡片（标题/正文滚动/关闭）。
        androidx.compose.ui.window.Dialog(onDismissRequest = { summaryModalVisible = false }) {
            Column(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.backgroundColor)
                    .padding(16.dp),
            ) {
                Text(
                    context.t("message_voiceSummaryTitle"),
                    color = colors.titleText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Text(
                    voiceSummary.plain,
                    color = colors.bodyText,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                )
                Text(
                    context.t("common_close"),
                    color = colors.tintColor,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .align(Alignment.End)
                        .clickable { summaryModalVisible = false },
                )
            }
        }
    }
}
