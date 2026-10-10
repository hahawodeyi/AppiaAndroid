package cn.appia.im.feature.chat.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.theme.LocalAppiaColors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * RN lib/appiaMessage/aiMsgType.ts resolveAiMsgKind 移植：持久化 AI 消息分型。
 * - `msgType === 'ai_response'` → AI_RESPONSE（M3 markdown 链 + 代码块复制按钮，
 *   MessageRow→MessageBody(aiCodeBlock) 承接，RN AiResponseMessage 同链）
 * - `msgType === 'fastModelMsg'` 或 `msgData.type === 'fastModelMsg'` → AI_FAST_MODEL
 * - 其余 NONE
 */
enum class AiMsgKind { AI_RESPONSE, AI_FAST_MODEL, NONE }

private val aiMsgJson = Json { ignoreUnknownKeys = true; isLenient = true }

fun resolveAiMsgKind(msgType: String?, msgData: String?): AiMsgKind {
    if (msgType == "ai_response") return AiMsgKind.AI_RESPONSE
    if (msgType == "fastModelMsg") return AiMsgKind.AI_FAST_MODEL
    if (!msgData.isNullOrEmpty()) {
        val type = runCatching { aiMsgJson.parseToJsonElement(msgData) }.getOrNull()
            ?.let { it as? JsonObject }?.get("type")
            ?.let { it as? JsonPrimitive }?.contentOrNull
        if (type == "fastModelMsg") return AiMsgKind.AI_FAST_MODEL
    }
    return AiMsgKind.NONE
}

/**
 * fastModelMsg 类型桩（RN FastModelMessage 为 staffService 引文域：citation 还原 + bot.docs
 * 原文 Modal——整体划出 M10）。最小占位：msgData.refs 正文拼接缺失，仅回退 msg 纯文本，
 * 引文/文档域 M10 接管时替换。
 */
@Composable
internal fun FastModelMessageStub(message: MessageEntity, modifier: Modifier = Modifier) {
    val text = message.msg.orEmpty()
    if (text.isBlank()) return
    Text(
        text,
        modifier = modifier.fillMaxWidth(),
        color = LocalAppiaColors.current.bodyText,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        maxLines = 200,
        overflow = TextOverflow.Ellipsis,
    )
}
