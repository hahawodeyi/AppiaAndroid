package cn.appia.im.domain.ai

import cn.appia.im.core.ai.AiTurnInput
import cn.appia.im.core.ai.resolveAiTurn
import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.messaging.randomMessageId
import cn.appia.im.core.network.sse.AiStreamEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicBoolean

/** RN stores/aiStore.ts AiRoomState（lib/ai/types.ts 同形）。 */
data class AiRoomState(
    val isProcessing: Boolean = false,
    val isStopping: Boolean = false,
    val currentMessageId: String? = null,
    val currentBotUsername: String? = null,
    val error: String? = null,
    /** 触发本次 AI 流的 prompt（控制态；流式文本不入库） */
    val prompt: String? = null,
    /** 触发本次 AI 流的用户消息 id */
    val relatedUserMessageId: String? = null,
    /** 当前 bot 回复序号（从 0 起） */
    val botReplyIndex: Int? = null,
    /** 是否为 myAgents 自聊房间（持久化透传 saveAiMessage） */
    val inAgentRoom: Boolean = false,
    /** 槽位发送者展示名（缺省回退 bot username） */
    val botDisplayName: String? = null,
)

/** agentLoadingMsg 槽位行的 msg_type 值（RN IMessage msgType 'agentLoadingMsg'）。 */
const val AGENT_LOADING_MSG_TYPE = "agentLoadingMsg"

/**
 * AI 房间控制态机（RN stores/aiStore.ts aiStore + lib/ai/runAiTurn.ts 串行驱动的等价合并）：
 * - 每房一条 [AiRoomState]，[rooms] 供 UI 收集（槽位注入/停止按钮）；
 * - [runAiTurn] 多 bot **串行**：`setProcessing` → await 该房被 [clear]（[waitTimeoutMs]
 *   兜底强制推进）。**反重排不变量（坑 6，RN runAiTurn:31-33 注释）**：必须先 setProcessing
 *   再 await clear——顺序反了第 0 轮 await 因房间为空立即 resolve，下一 bot 的 setProcessing
 *   会在上一 bot 完成前覆盖槽位、中止其流；
 * - 流式文本不在此处（AgentLoadingMessage 组件 state）；持久化回流（saveAiMessage + DDP
 *   同 id 替换 + 双兜底）在 [scheduleFallbackClear] 与 AgentLoading 消费侧。
 *
 * scope 注入 app 级 BackgroundScope（RN `void runAiTurn(...)` fire-and-forget 跨导航存续同义）。
 */
class AiRoomStateMachine(
    /** 兜底强清定时器 scope（生产 = app 级；RN persistSelf 的裸 setTimeout 同义）。 */
    val scope: CoroutineScope,
    /** 等待单个 bot 清理的超时（RN DEFAULT_WAIT_TIMEOUT_MS）；测试注入短值。 */
    private val waitTimeoutMs: Long = DEFAULT_WAIT_TIMEOUT_MS,
    /** persistSelf 后真实消息未到的强清兜底（RN AgentLoadingMessage :123-129 15s）。 */
    private val finalizeFallbackMs: Long = FINALIZE_FALLBACK_MS,
    /** 槽位 message id 生成缝（RN randomMessageId）。 */
    private val genMessageId: () -> String = { randomMessageId() },
) {

    private val _rooms = MutableStateFlow<Map<String, AiRoomState>>(emptyMap())
    val rooms: StateFlow<Map<String, AiRoomState>> = _rooms.asStateFlow()

    fun room(rid: String): AiRoomState? = _rooms.value[rid]

    /** RN aiStore.setProcessing：整条覆盖（isStopping 归 false、error 清空）。 */
    fun setProcessing(
        rid: String,
        bot: String,
        messageId: String,
        prompt: String? = null,
        relatedUserMessageId: String? = null,
        botReplyIndex: Int? = null,
        inAgentRoom: Boolean = false,
        botDisplayName: String? = null,
    ) {
        _rooms.update {
            it + (rid to AiRoomState(
                isProcessing = true,
                currentBotUsername = bot,
                currentMessageId = messageId,
                prompt = prompt,
                relatedUserMessageId = relatedUserMessageId,
                botReplyIndex = botReplyIndex,
                inAgentRoom = inAgentRoom,
                botDisplayName = botDisplayName,
            ))
        }
    }

    /** RN aiStore.setStopping：缺席房也建条目（EMPTY+isStopping，原样转录）。 */
    fun setStopping(rid: String) {
        _rooms.update { m -> m + (rid to (m[rid] ?: AiRoomState()).copy(isStopping = true)) }
    }

    /** RN aiStore.setError。 */
    fun setError(rid: String, error: String) {
        _rooms.update { m -> m + (rid to (m[rid] ?: AiRoomState()).copy(error = error)) }
    }

    /** RN aiStore.clear：删条目（驱动 await 与槽位注入一并释放）。 */
    fun clear(rid: String) {
        _rooms.update { it - rid }
    }

    /**
     * RN runAiTurn:35-63。bots 串行推进；等待被 [clear]（isProcessing=false 同义）后进下一个；
     * [waitTimeoutMs] 超时强制推进（组件异常卸载防永久挂起，RN :91-96）。
     */
    suspend fun runAiTurn(
        rid: String,
        bots: List<String>,
        prompt: String,
        relatedUserMessageId: String,
        inAgentRoom: Boolean = false,
        botDisplayName: String? = null,
    ) {
        if (bots.isEmpty()) return
        for ((i, bot) in bots.withIndex()) {
            // 坑 6：先 setProcessing 再 await（见类 KDoc 反重排不变量）
            setProcessing(
                rid, bot, genMessageId(), prompt,
                relatedUserMessageId = relatedUserMessageId,
                botReplyIndex = i,
                inAgentRoom = inAgentRoom,
                botDisplayName = botDisplayName,
            )
            awaitClear(rid)
        }
    }

    /** RN waitForAiClear：rooms[rid] 缺席或 isProcessing=false 即 resolve；超时强制 resolve。 */
    suspend fun awaitClear(rid: String, timeoutMs: Long = waitTimeoutMs) {
        try {
            withTimeout(timeoutMs) {
                _rooms.first { rooms -> rooms[rid]?.isProcessing != true }
            }
        } catch (_: TimeoutCancellationException) {
            // RN :91-96 超时兜底强制推进
        }
    }

    /**
     * RN AgentLoadingMessage persistSelf :123-129 兜底：持久化发起后 [finalizeFallbackMs]
     * 内真实消息未到达（未触发 clear）→ 强清。仅当仍是同一条槽位（同 id 且 isProcessing）
     * 才清——串行推进到下一 bot 后不得误清新槽位。
     */
    fun scheduleFallbackClear(rid: String, messageId: String) {
        scope.launch {
            delay(finalizeFallbackMs)
            val cur = _rooms.value[rid]
            if (cur?.isProcessing == true && cur.currentMessageId == messageId) clear(rid)
        }
    }

    companion object {
        /** RN runAiTurn.ts:19 DEFAULT_WAIT_TIMEOUT_MS。 */
        const val DEFAULT_WAIT_TIMEOUT_MS = 60_000L

        /** RN AgentLoadingMessage.tsx:129 兜底 15s。 */
        const val FINALIZE_FALLBACK_MS = 15_000L
    }
}

/**
 * RN RoomScreen maybeTriggerAiAfterSend :620-643 的装配等价：发送成功后解析触发计划，
 * 命中则 [AiRoomStateMachine.runAiTurn] 串行驱动。未命中（无 @bot / 转人工等）no-op。
 * botDisplayName 由调用方按房间上下文注入（staffService 房用房间名，M7 暂无该域传 null）。
 */
suspend fun maybeTriggerAiAfterSend(
    machine: AiRoomStateMachine,
    input: AiTurnInput,
    agentBotList: List<String>,
    siteUrl: String,
    userMessageId: String,
    botDisplayName: String? = null,
): Boolean {
    val plan = resolveAiTurn(input, agentBotList, siteUrl, userMessageId) ?: return false
    machine.runAiTurn(
        rid = input.rid,
        bots = plan.bots,
        prompt = plan.prompt,
        relatedUserMessageId = plan.relatedUserMessageId,
        inAgentRoom = plan.inAgentRoom,
        botDisplayName = botDisplayName,
    )
    return true
}

/**
 * RN RoomMessageList/index.tsx:133-153 agentSlot 派生：isProcessing 且真实消息（同
 * currentMessageId）未在列表 → 最新端注入一条 agentLoadingMsg 槽位行（u=bot、msg=prompt、
 * msgData={relatedUserMessageId,botReplyIndex}）。
 *
 * **防闪烁不变量（坑 6，RN :135）**：同 id 真实消息已在列表 → 不注入，由真实消息接替渲染。
 * [messages] 为 Room DB 倒序窗口（index 0 = 最新，reverseLayout 同序）。
 */
fun deriveMessagesWithAgentSlot(
    messages: List<MessageEntity>,
    room: AiRoomState?,
    nowMs: () -> Double = { System.currentTimeMillis().toDouble() },
): List<MessageEntity> {
    if (room?.isProcessing != true) return messages
    val messageId = room.currentMessageId ?: return messages
    if (messages.any { it._id == messageId }) return messages
    val rid = messages.firstOrNull()?.rid ?: return messages
    val bot = room.currentBotUsername.orEmpty()
    val botName = room.botDisplayName ?: bot
    val now = nowMs()
    val slot = MessageEntity(
        _id = messageId,
        rid = rid,
        ts = now,
        u = buildJsonObject {
            put("_id", bot)
            put("username", bot)
            put("name", botName)
        }.toString(),
        alias = "",
        parse_urls = "[]",
        _updated_at = now,
        msg = room.prompt.orEmpty(),
        msg_type = AGENT_LOADING_MSG_TYPE,
        msg_data = buildJsonObject {
            put("relatedUserMessageId", room.relatedUserMessageId.orEmpty())
            put("botReplyIndex", room.botReplyIndex ?: 0)
        }.toString(),
    )
    return listOf(slot) + messages
}

/**
 * RN AgentLoadingMessage 流消费 + finalize 状态机（组件 useEffect :154-199 openAiStream +
 * :201-212 停止分支 + finalize :138-152 的无 Compose 核心）。挂起直到流终态或机器
 * isStopping：
 * - Text：增量累加并回调 [onText]（组件 state，不入库）；
 * - Finished：文本非空 → [onPersist]（trimEnd；persist 侧自行挂 [AiRoomStateMachine.scheduleFallbackClear]
 *   兜底），空 → [onClear]；
 * - Error：[onError] + `machine.setError`，**必 finalize**（空文本 → [onClear]）——否则
 *   isProcessing 恒真卡死串行驱动（坑 5，RN :188-190）；
 * - 停止（isStopping 翻真）：中止流收集 + finalize（停止后有文本仍持久化，RN stopAiProcessing
 *   语义）。
 * finalize 恰一次（RN finalizedRef）。流自然结束后函数即返回；停止分支同理。
 */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun runAgentLoadingTurn(
    stream: Flow<AiStreamEvent>,
    machine: AiRoomStateMachine,
    rid: String,
    onText: (String) -> Unit,
    onPersist: (String) -> Unit,
    onClear: () -> Unit,
    onError: (String) -> Unit = {},
) {
    var text = ""
    var stopping = false
    val finalized = AtomicBoolean(false)
    fun finalize(finalText: String) {
        if (!finalized.compareAndSet(false, true)) return
        if (finalText.isNotEmpty()) onPersist(finalText) else onClear()
    }
    coroutineScope {
        val collectJob = launch {
            stream.collect { ev ->
                when (ev) {
                    is AiStreamEvent.Text ->
                        if (!stopping) {
                            text += ev.text
                            onText(text)
                        }
                    is AiStreamEvent.Finished -> finalize(text.trimEnd())
                    is AiStreamEvent.Error -> {
                        if (stopping) return@collect // 用户主动中止由停止分支 finalize
                        onError(ev.message)
                        machine.setError(rid, ev.message)
                        finalize("") // 坑 5：错误也 finalize，放行串行驱动
                    }
                }
            }
        }
        val stopJob = launch { machine.rooms.first { rooms -> rooms[rid]?.isStopping == true } }
        select<Unit> {
            collectJob.onJoin { }
            stopJob.onJoin { stopping = true }
        }
        stopJob.cancel()
        collectJob.cancel() // 停止分支：中止流（底层连接随 AiStreamClient finally 关闭）
    }
    if (stopping) finalize(text.trimEnd())
}
