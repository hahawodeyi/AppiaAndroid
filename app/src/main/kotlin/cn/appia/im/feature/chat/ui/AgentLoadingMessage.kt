package cn.appia.im.feature.chat.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.ai.resolveBotEndpoints
import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.i18n.t
import cn.appia.im.core.network.sse.AiStreamEvent
import cn.appia.im.core.network.sse.AiStreamRequest
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.domain.ai.AiRoomStateMachine
import cn.appia.im.domain.ai.runAgentLoadingTurn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * RN AgentLoadingMessage 组件依赖包（装配处构造）：流客户端 / 持久化回写 / 端点解析列表 /
 * SSE 鉴权四参。UI 测试可注入假流与假持久化。
 */
class AgentLoadingEnv(
    val machine: AiRoomStateMachine,
    val stream: suspend (AiStreamRequest) -> Flow<AiStreamEvent>,
    /** RN saveAiMessage（bot.saveAIMessage）；装配处 = AiBotApi.saveAiMessage。 */
    val saveAiMessage: suspend (
        rid: String, toUsername: String, mid: String, botName: String, content: String, inAgentRoom: Boolean,
    ) -> Unit,
    /** `Agent_Bot_List` 设置（resolveBotEndpoints 的 Claw 分支候选）。 */
    val agentBotList: List<String>,
    val serverUrl: String,
    val token: String,
    val userId: String,
    val currentUsername: String,
)

/** RN AgentLoadingDots :35-60：三点错相淡入淡出（stagger 200ms、各 500ms 往返）。 */
@Composable
private fun AgentLoadingDots(color: Color) {
    val transition = rememberInfiniteTransition(label = "agentDots")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("qa-agent-loading-dots"),
    ) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(500),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(i * 200),
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .padding(horizontal = 5.dp)
                    .size(5.dp)
                    .graphicsLayer { this.alpha = alpha }
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/**
 * AI 流式槽位组件（RN components/AppiaMessage/components/AgentLoadingMessage.tsx 的 Compose
 * 移植）：随 [cn.appia.im.domain.ai.deriveMessagesWithAgentSlot] 注入的槽位行挂载。
 *
 * - 流式文本只在组件 state（[runAgentLoadingTurn] 增量回调），不入库；
 * - 三点动画（无文本且未结束）→ 纯文本增量 → 结束后持续展示完成文本；
 * - finalize（恰一次）：有文本 → [AgentLoadingEnv.saveAiMessage] 持久化回写 + 15s 兜底强清
 *   （RN persistSelf :104-132——不立即 clear，保留 isProcessing 让槽位继续显示完成文本，直到
 *   真实消息同 id 回流触发 clear）；空文本/错误 → clear（错误也必 finalize，坑 5）；
 * - 停止（machine isStopping）：中止流 + finalize，停止后有文本仍持久化（RN stopAiProcessing）。
 */
@Composable
fun AgentLoadingMessage(message: MessageEntity, env: AgentLoadingEnv) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    val rid = message.rid
    val botUsername = remember(message.u) { parseMessageUser(message.u).username.orEmpty() }
    val prompt = message.msg?.trim().orEmpty()

    var text by remember(message._id) { mutableStateOf("") }
    var error by remember(message._id) { mutableStateOf<String?>(null) }
    var finished by remember(message._id) { mutableStateOf(false) }

    LaunchedEffect(message._id, prompt, botUsername) {
        // RN :155/:160：无 prompt 或端点不可解析不开流（槽位停留三点态，等兜底）
        if (prompt.isEmpty()) return@LaunchedEffect
        val endpoint = resolveBotEndpoints(botUsername, env.agentBotList)?.stream
            ?: return@LaunchedEffect
        runAgentLoadingTurn(
            stream = env.stream(AiStreamRequest(env.serverUrl, env.token, env.userId, endpoint, rid, prompt)),
            machine = env.machine,
            rid = rid,
            onText = { text = it },
            onError = { error = it },
            onPersist = { finalText ->
                val room = env.machine.room(rid)
                val mid = room?.currentMessageId ?: message._id
                val botName = room?.currentBotUsername ?: botUsername
                env.machine.scope.launch {
                    try {
                        env.saveAiMessage(rid, env.currentUsername, mid, botName, finalText, room?.inAgentRoom ?: false)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // RN persistSelf catch console.warn：回写失败不阻塞（15s 兜底仍会清）
                    }
                }
                env.machine.scheduleFallbackClear(rid, mid)
            },
            onClear = { env.machine.clear(rid) },
        )
        finished = true // 流自然结束（含停止分支返回）；无文本时的兜底文案数据源（RN :237-245）
    }

    when {
        error != null -> Text(
            text = (error ?: "").takeIf { it != "network" } ?: context.t("ai_error_network"),
            color = colors.auxiliaryText,
            fontSize = 16.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("qa-agent-loading-error"),
        )
        text.isEmpty() && !finished -> AgentLoadingDots(colors.tintColor)
        text.isEmpty() -> Text(
            text = context.t("message_agentloading"),
            color = colors.auxiliaryText,
            fontSize = 16.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("qa-agent-loading-fallback"),
        )
        else -> Text(
            text = text,
            color = colors.bodyText,
            fontSize = 16.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("qa-agent-loading-streaming"),
        )
    }
}
