package cn.appia.im.feature.chat

import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.messaging.MessageUpsert
import cn.appia.im.core.network.api.SurroundingRaw
import cn.appia.im.domain.chat.JumpMessageTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicInteger

// ── chunk 占位（RN src/types/messageTypeLoad.ts 逐字；渲染复用 M2-T1/M3-T13 的 1px 通路）──

/** RN MessageTypeLoad.PREVIOUS_CHUNK。 */
const val LOAD_MORE_BEFORE = "load-more-before"

/** RN MessageTypeLoad.NEXT_CHUNK。 */
const val LOAD_MORE_AFTER = "load-more-after"

fun isLoadChunkType(t: String?): Boolean = t == LOAD_MORE_BEFORE || t == LOAD_MORE_AFTER

/** RN generateLoadMoreChunkId：`load-more-${before|after}-${anchorMessageId}`。 */
fun generateLoadMoreChunkId(anchorMessageId: String, kind: String): String =
    "load-more-$kind-$anchorMessageId"

/**
 * 跳转域 UI 状态（M5-T6 / RN useRoomMessageJump :89-96 返回面的对应字段）：
 * [jumpMessages] 为 null = 实时源（isJumpMode=false）；非 null = 跳转窗口替换消息源
 * （纯内存态、不落库——RN fetchSurroundingMessagesForJump 不调 updateMessages，
 * DDP 落库只影响实时窗口流，跳转窗口不会被流写覆盖，即「防 DDP 覆盖」的结构性来源）。
 */
data class MessageJumpUiState(
    val jumpMessages: List<MessageEntity>? = null,
    val highlightedMessageId: String? = null,
    val isJumpLoading: Boolean = false,
    val isLoadingEarlierInJump: Boolean = false,
    val moreBefore: Boolean = false,
) {
    val isJumpMode: Boolean get() = jumpMessages != null
}

/** RN src/lib/message/jumpToMessage/types.ts JumpPlan 四型。 */
sealed interface JumpPlan {
    data class Scroll(val messageId: String) : JumpPlan
    data class FetchSurrounding(val messageId: String, val rid: String) : JumpPlan
    data class NavigateRoom(val messageId: String, val rid: String) : JumpPlan
    data object NotFound : JumpPlan
}

/** RN planJumpToMessage（jumpToMessageOrchestrator.ts :12-36）纯函数：三路判定 + not-found。 */
fun planJumpToMessage(
    rid: String,
    messageId: String,
    resolved: JumpMessageTarget?,
    windowIds: Set<String>,
): JumpPlan = when {
    resolved == null -> JumpPlan.NotFound
    resolved.rid != rid -> JumpPlan.NavigateRoom(resolved.id, resolved.rid)
    messageId in windowIds -> JumpPlan.Scroll(messageId)
    else -> JumpPlan.FetchSurrounding(messageId, rid)
}

/**
 * 跳转窗口组装（RN fetchSurroundingMessagesForJump :40-88 映射段）：API 消息 →
 * [MessageUpsert.applyApiFields]（不落库的内存行）按 ts 倒序（与实时窗口同序：index 0 = 最新），
 * moreAfter → NEXT_CHUNK 占位锚定最新一条、置于头部；moreBefore → PREVIOUS_CHUNK 占位
 * 锚定最旧一条、置于尾部（ts = 锚点 ∓/± 1ms 保序）。
 */
fun buildJumpMessages(raw: SurroundingRaw, rid: String): List<MessageEntity> {
    if (raw.apiMessages.isEmpty()) return emptyList()
    val sorted = raw.apiMessages
        .map { data ->
            val messageRid = (data["rid"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: rid
            MessageUpsert.applyApiFields(prev = null, data = data, messageRid = messageRid)
        }
        .sortedByDescending { it.ts }
        .toMutableList()
    if (raw.moreAfter) sorted.add(0, buildChunkPlaceholder(sorted.first(), before = false))
    if (raw.moreBefore) sorted.add(buildChunkPlaceholder(sorted.last(), before = true))
    return sorted
}

/** RN buildChunkPlaceholder :15-32：锚点 ±1ms 的 chunk 占位行（渲染层 1px 短路）。 */
private fun buildChunkPlaceholder(anchor: MessageEntity, before: Boolean): MessageEntity {
    val ts = if (before) anchor.ts - 1 else anchor.ts + 1
    return MessageEntity(
        _id = generateLoadMoreChunkId(anchor._id, if (before) "before" else "after"),
        rid = anchor.rid,
        ts = ts,
        u = "{}",
        alias = "",
        parse_urls = "[]",
        _updated_at = ts,
        msg = "",
        t = if (before) LOAD_MORE_BEFORE else LOAD_MORE_AFTER,
    )
}

/** RN oldestRealMessage :72-77：倒序窗口从尾部（最旧）起第一条非 chunk 消息（loadEarlier 锚点）。 */
fun oldestRealMessage(messages: List<MessageEntity>): MessageEntity? =
    messages.lastOrNull { !isLoadChunkType(it.t) }

/**
 * RN resolveVisibleMessageId（applyDisplayMessageTransforms.ts :56-71）：跳转目标落在
 * rollback 隐藏组内时映射到可见组首条 id；不在列表 → null（调用方回退原 id）。
 */
fun resolveVisibleJumpMessageId(messageId: String, messages: List<MessageEntity>): String? {
    val display = applyDisplayMessageTransforms(messages)
    val index = display.indexOfFirst { it.message._id == messageId }
    if (index < 0) return null
    if (!display[index].hiddenInRollbackGroup) return messageId
    for (i in index - 1 downTo 0) {
        if (display[i].rollbackGroup?.any { it._id == messageId } == true) {
            return display[i].message._id
        }
    }
    return null
}

/**
 * 消息跳转控制器（M5-T6 / RN useRoomMessageJump.ts :79-321 逐行为移植）：
 * - [jumpTo]：跨房早退 → [onCrossRoomJump]（装配处换路由参数重导航）；同房走
 *   resolve（本地 find → chat.getMessage）→ [planJumpToMessage] → 三路：
 *   not-found（toast + 退回实时源）/ navigate-room（跨房）/ scroll / fetch-surrounding
 *   （loadSurroundingMessages 50 → 窗口替换源 + chunk 占位 → 滚动 + 高亮）；
 * - 15s 超时（resolve 与 fetch 各一段，RN :21 JUMP_TIMEOUT_MS）；300ms loading 防抖
 *   （RN :22 LOADING_DEBOUNCE_MS——快路径不闪加载浮层）；runId 代际守卫 + job 取消
 *   （RN runIdRef/abortRef 等价——迟到回调不回写新代状态）；
 * - [loadEarlierInJumpMode]：锚最旧真实消息整窗替换（RN :268-289）；[exitJumpMode] 回实时源
 *   （高亮保留，RN 同）；[cancelJump] 加载浮层点击取消（RN :134-142）；
 * - 防死循环：本类对路由参数无订阅——jumpTo 仅由装配处 `LaunchedEffect(路由参数)` 调起
 *   （RN useEffect [jumpToMessageId] :291-296 的 Compose 键控等价），内部状态写入不会重触发。
 */
class MessageJumpController(
    private val scope: CoroutineScope,
    private val rid: String,
    private val roomType: String,
    private val resolve: suspend (messageId: String) -> JumpMessageTarget?,
    private val fetchSurrounding: suspend (messageId: String, rid: String) -> SurroundingRaw,
    /** 当前实时窗口消息（RN displayMessagesRef 读取缝：scroll 分支判定 + 可见 id 解析）。 */
    private val currentMessages: () -> List<MessageEntity>,
    private val timeoutMs: Long = JUMP_TIMEOUT_MS,
    private val loadingDebounceMs: Long = LOADING_DEBOUNCE_MS,
) {

    private val _state = MutableStateFlow(MessageJumpUiState())
    val state: StateFlow<MessageJumpUiState> = _state

    /** 屏幕注册的滚动缝（RN listRef.scrollToMessageId 等价；返回是否滚动成功）。 */
    var scrollToMessage: suspend (String) -> Boolean = { false }

    /** 跨房跳转（RN onCrossRoomJump → navigateToRoom：装配处以路由参数重导航，M6 深链同径）。 */
    var onCrossRoomJump: (targetRid: String, roomType: String, messageId: String) -> Unit = { _, _, _ -> }

    /** i18n key 出口（jumptomessage_notfound / jumptomessage_failed / jumptomessage_timeout）。 */
    var onToast: (String) -> Unit = {}

    private val runId = AtomicInteger(0)
    private var runJob: Job? = null
    private var debounceJob: Job? = null
    private var earlierJob: Job? = null

    /** RN runJump :162-259 入口（handleJumpToMessage 参数形态：目标 rid / 消息 id / 目标房型兜底）。 */
    fun jumpTo(targetRid: String, messageId: String, targetRoomType: String? = null) {
        if (messageId.isEmpty()) return
        if (targetRid != rid) { // RN :166-173 目标房 ≠ 当前房 → 直接跨房重导航
            onCrossRoomJump(targetRid, targetRoomType ?: roomType, messageId)
            return
        }
        val current = runId.incrementAndGet()
        runJob?.cancel()
        debounceJob?.cancel()
        debounceJob = scope.launch { // RN :180-183 300ms 防抖后才亮加载位
            delay(loadingDebounceMs)
            updateIf(current) { it.copy(isJumpLoading = true) }
        }
        runJob = scope.launch { runJump(current, messageId) }
    }

    /** RN cancelJump :134-142：作废在途 + 清两加载位 + 退回实时源（高亮保留，RN 同）。 */
    fun cancelJump() {
        runId.incrementAndGet()
        runJob?.cancel(); runJob = null
        earlierJob?.cancel(); earlierJob = null
        debounceJob?.cancel(); debounceJob = null
        _state.update { it.copy(isJumpLoading = false, isLoadingEarlierInJump = false) }
        exitJumpMode()
    }

    /** RN exitJumpMode :124-127：回实时源；jumpMoreBefore 复位；高亮不动。 */
    fun exitJumpMode() {
        _state.update { it.copy(jumpMessages = null, moreBefore = false) }
    }

    /** 退房/换房收口（RN unmount cleanup :298-306 的调用方等价；作废全部在途）。 */
    fun dispose() {
        runId.incrementAndGet()
        runJob?.cancel(); runJob = null
        earlierJob?.cancel(); earlierJob = null
        debounceJob?.cancel(); debounceJob = null
    }

    /** RN loadEarlierInJumpMode :268-289：非跳转态 / 无 moreBefore / 加载中 → 静默；锚最旧真实消息整窗替换。 */
    fun loadEarlierInJumpMode() {
        val s = _state.value
        if (s.jumpMessages == null || !s.moreBefore || s.isLoadingEarlierInJump) return
        val anchor = oldestRealMessage(s.jumpMessages) ?: return
        _state.update { it.copy(isLoadingEarlierInJump = true) }
        earlierJob = scope.launch {
            try {
                val raw = withTimeout(timeoutMs) { fetchSurrounding(anchor._id, rid) }
                _state.update {
                    it.copy(
                        moreBefore = raw.moreBefore, // RN :280 无条件更新（空窗也更新标记）
                        jumpMessages = if (raw.apiMessages.isEmpty()) {
                            it.jumpMessages
                        } else {
                            buildJumpMessages(raw, rid)
                        },
                    )
                }
            } catch (e: TimeoutCancellationException) {
                onToast(TOAST_FAILED) // RN :284-287 loadEarlier 超时与其余失败一律 failed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onToast(TOAST_FAILED)
            } finally {
                _state.update { it.copy(isLoadingEarlierInJump = false) }
            }
        }
    }

    private suspend fun runJump(current: Int, messageId: String) {
        try {
            val resolved = withTimeout(timeoutMs) { resolve(messageId) }
            if (isStale(current)) return
            val plan = planJumpToMessage(rid, messageId, resolved, currentMessages().map { it._id }.toSet())
            when (plan) {
                JumpPlan.NotFound -> {
                    onToast(TOAST_NOT_FOUND)
                    exitJumpMode()
                    return
                }
                is JumpPlan.NavigateRoom -> {
                    onCrossRoomJump(plan.rid, roomType, plan.messageId)
                    return
                }
                is JumpPlan.Scroll -> Unit // 落到统一滚动
                is JumpPlan.FetchSurrounding -> {
                    val raw = withTimeout(timeoutMs) { fetchSurrounding(plan.messageId, plan.rid) }
                    if (isStale(current)) return
                    if (raw.apiMessages.isEmpty()) { // RN :221-225 服务端确凿无此消息
                        onToast(TOAST_NOT_FOUND)
                        exitJumpMode()
                        return
                    }
                    if (isStale(current)) return
                    _state.update {
                        it.copy(jumpMessages = buildJumpMessages(raw, rid), moreBefore = raw.moreBefore)
                    }
                    delay(SETTLE_DELAY_MS) // RN :229 等 100ms 让新窗口先进布局
                    if (isStale(current)) return
                }
            }
            scrollAndHighlight(current, messageId)
        } catch (e: TimeoutCancellationException) { // RN :236-240 超时与其余错误分文案
            if (isStale(current)) return
            onToast(TOAST_TIMEOUT)
            exitJumpMode()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isStale(current)) return
            onToast(TOAST_FAILED)
            exitJumpMode()
        } finally {
            if (!isStale(current)) { // RN :241-247 仅本代清防抖与加载位
                debounceJob?.cancel()
                debounceJob = null
                _state.update { it.copy(isJumpLoading = false) }
            }
        }
    }

    /** RN scrollAndHighlight :144-160：可见 id 解析（rollback 隐藏组 → 组首）+ 5×50ms 重试 + 高亮置位。 */
    private suspend fun scrollAndHighlight(current: Int, messageId: String) {
        val active = _state.value.jumpMessages ?: currentMessages()
        val targetId = resolveVisibleJumpMessageId(messageId, active) ?: messageId
        repeat(SCROLL_ATTEMPTS) {
            if (isStale(current)) return
            if (scrollToMessage(targetId)) {
                updateIf(current) { it.copy(highlightedMessageId = targetId) }
                return
            }
            delay(SCROLL_RETRY_DELAY_MS)
        }
        if (!isStale(current)) onToast(TOAST_FAILED) // RN :233-235 滚动失败不退 jump 模式
    }

    private fun isStale(current: Int): Boolean = current != runId.get()

    private inline fun updateIf(current: Int, transform: (MessageJumpUiState) -> MessageJumpUiState) {
        if (!isStale(current)) _state.update(transform)
    }

    companion object {
        /** RN JUMP_TIMEOUT_MS :21。 */
        const val JUMP_TIMEOUT_MS = 15_000L

        /** RN LOADING_DEBOUNCE_MS :22。 */
        const val LOADING_DEBOUNCE_MS = 300L

        /** RN scrollAndHighlight :148 5 次尝试。 */
        const val SCROLL_ATTEMPTS = 5

        /** RN scrollAndHighlight :155 重试间隔 50ms。 */
        const val SCROLL_RETRY_DELAY_MS = 50L

        /** RN :229 fetch 后等 100ms 再滚动。 */
        const val SETTLE_DELAY_MS = 100L

        const val TOAST_NOT_FOUND = "jumptomessage_notfound"
        const val TOAST_FAILED = "jumptomessage_failed"
        const val TOAST_TIMEOUT = "jumptomessage_timeout"
    }
}
