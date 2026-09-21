package cn.appia.im.feature.chat.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import cn.appia.im.feature.chat.MentionCandidate

/** RN editorFocusScheduler.ts FocusSchedulerState（generation 过期丢弃 + 同向去重）。 */
internal data class FocusSchedulerState(
    val generation: Int = 0,
    val lastDispatched: String? = null, // "focus" | "blur" | null
)

/**
 * 焦点单一调度纯函数（RN editorFocusScheduler.ts reduceFocusScheduler 逐条）：
 * blur 立即下发；旧 generation 丢弃；与上次下发同向去重（Android WebView 双 requestFocus
 * 会把键盘打不起——RN release 修复的根因）；idle 不操作由调用方表达（不触发 request 类入口）。
 */
internal fun reduceFocusScheduler(
    prev: FocusSchedulerState,
    requestGeneration: Int,
    desired: String,
): Pair<String?, FocusSchedulerState> {
    if (requestGeneration < prev.generation) return null to prev
    if (prev.lastDispatched == desired) return null to prev
    return desired to FocusSchedulerState(generation = prev.generation, lastDispatched = desired)
}

/**
 * RN ChatInputBar 的编辑器面等价（Android 唯一编辑器入口，Compose 无关可单测）：
 *
 * - **草稿 setContent isReady 门控 + editorReadyCount**（RN ChatInputBar.tsx:465-494 的两道门）：
 *   只在 web 侧 editor-ready（ProseMirror onCreate）后注入；每个就绪周期（WebView 重建，RN iOS
 *   remount 同义）重注一次当前挂起内容。**禁依赖 editor 引用**：RN 的草稿覆盖 bug 根因是 effect
 *   依赖了每次渲染都换新引用的 editor 对象（saveDraft→行变→重渲染→注入 effect 重跑→旧 draft
 *   覆盖用户输入）。此处 controller 即稳定引用；UI 层 LaunchedEffect 只响应
 *   isReady/editorReadyCount/draft 变化，绝不以 bridge/controller 实例为 key。
 * - **焦点单一调度**（RN useEditorFocusController + editorFocusScheduler）：50ms debounce +
 *   generation 过期丢弃 + 同向去重；blur 立即下发；未就绪 focus 记意图挂起，editor-ready
 *   时重派一次（blur 清挂起——后到意图胜出）。
 * - mention-trigger 链：记 range {from,to}，选中回插 deleteRange+insertMention。
 * - Android IME 预热判定（RN androidImePrime）：从未交互过的 WebView requestFocus 拉不起 IME。
 */
class ChatInputBarController(
    private val scope: CoroutineScope,
    /** 焦点 debounce（RN FOCUS_DEBOUNCE_MS=50）；测试注入 0 直接跑。 */
    private val focusDebounceMs: Long = FOCUS_DEBOUNCE_MS,
) {

    /** 编辑器桥：WebView 建成后装配（RN editorRef 等价——全类唯一编辑器引用）。 */
    var bridge: EditorBridge? = null

    /** RPC 面（TenTapEditorBridge 专有；null 时消息解析降级直接 parseMessage）。 */
    internal var rpcBridge: TenTapEditorBridge? = null

    // ── 编辑器状态（Compose 读取；RN BridgeState 消费面）──
    var isReady by mutableStateOf(false); private set
    var editorReadyCount by mutableIntStateOf(0); private set
    var isFocused by mutableStateOf(false); private set
    var contentHeightDp by mutableDoubleStateOf(DEFAULT_HEIGHT_DP); private set

    // ── 工具栏活动态（T13 / stateUpdate 的 bridge state 键；RN es?.* 同一来源）──
    var isBoldActive by mutableStateOf(false); private set
    var isItalicActive by mutableStateOf(false); private set
    var isStrikeActive by mutableStateOf(false); private set
    var isOrderedListActive by mutableStateOf(false); private set
    var isBulletListActive by mutableStateOf(false); private set
    var headingLevel by mutableStateOf(0); private set
    var activeColor by mutableStateOf<String?>(null); private set
    var activeFontSize by mutableStateOf<String?>(null); private set

    /** 高亮组合态（RN isHighlightActive：bold + color #FF0000 + fontSize 16px 三者同活）。 */
    val isHighlightActive: Boolean
        get() = isBoldActive && activeColor == HIGHLIGHT_COLOR && activeFontSize == HIGHLIGHT_FONT_SIZE

    /** 最新 TipTap JSON（draft 保存/发送 md 源）；null=编辑器空或未拉到。 */
    var jsonContent by mutableStateOf<JsonObject?>(null); private set

    /** 最新纯文本（draft_message_plain / 发送 msg 源）。 */
    var plainText by mutableStateOf(""); private set

    /** mention 提及范围（@ 起止，deleteRange 用）；null=工具栏 @ 直跳（光标处插入）。 */
    var mentionRange: Pair<Long, Long>? = null

    /** 内容落定回调（UI 层接草稿 debounce 保存；RN onContentChange 等价）。 */
    var onContentSettled: ((json: JsonObject?, plain: String) -> Unit)? = null

    // ── 挂起内容注入（isReady 门控）──
    private data class PendingContent(val json: JsonObject?, val html: String?)

    private var pendingContent: PendingContent? = null
    private var appliedContentVersion = -1
    private var appliedReadyCycle = -1
    private var contentVersion = 0

    /**
     * 就绪门控注入：编辑器未就绪则挂起，editor-ready 后自动下发；已就绪立即下发。
     * 每个新就绪周期（editorReadyCount++）重注一次当前挂起内容（RN 每个 ready 周期注入一次同义）。
     * 本地 plain/json 快照随注入立即更新（web 侧 content-update 回流后 fetchContentNow 再校正
     * 同值）——发送门与草稿管线无需等 RPC，WebView 缺席（Robolectric）时同样成立。
     */
    fun setContentWhenReady(json: JsonObject?) {
        pendingContent = PendingContent(json = json, html = null)
        contentVersion++
        jsonContent = json
        plainText = json?.let { cn.appia.im.core.messaging.extractPlainTextFromTipTapJson(it) }.orEmpty()
        applyPendingIfReady()
    }

    /** HTML 形态（buildEditContent 无 md 回退 `<p>..</p>`）。plainText 反转义（M9 rider：实体还原）。 */
    fun setContentHtmlWhenReady(html: String?) {
        pendingContent = PendingContent(json = null, html = html)
        contentVersion++
        plainText = html?.replace(Regex("<[^>]*>"), "")?.let { cn.appia.im.core.messaging.unescapeHtml(it) } ?: ""
        applyPendingIfReady()
    }

    private fun applyPendingIfReady() {
        if (!isReady) return
        val content = pendingContent
        if (content == null) {
            // 无挂起内容（空草稿）：提及可直接补发
            flushPendingMention()
            return
        }
        if (appliedContentVersion == contentVersion && appliedReadyCycle == editorReadyCount) return
        appliedContentVersion = contentVersion
        appliedReadyCycle = editorReadyCount
        content.json?.let { bridge?.setContent(it) }
        content.html?.let { bridge?.setContentHtml(it) }
        // 内容先落再补发挂起提及（deleteRange/insertMention 依赖文档就位，防打空文档）
        flushPendingMention()
    }

    /** 补发未就绪期到达的提及选中（一次性；调用点 = 内容注入后或无内容就绪时）。 */
    private fun flushPendingMention() {
        pendingMention?.let { applyMentionSelectionNow(it) }
        pendingMention = null
    }

    // ── web→native 消息入口（UI 线程；EditorWebView.onMessage 转接）──

    fun onRawMessage(raw: String) {
        val msg = rpcBridge?.onRawMessage(raw) ?: TenTapBridge.parseMessage(raw)
        when (msg) {
            TenTapBridge.TenTapMessage.EditorReady -> onEditorReady()
            is TenTapBridge.TenTapMessage.StateUpdate -> onStateUpdate(msg.payload)
            is TenTapBridge.TenTapMessage.MentionTrigger -> handleMentionTrigger(msg.query, msg.cursorPos)
            TenTapBridge.TenTapMessage.ContentUpdate -> scheduleContentFetch()
            else -> Unit
        }
    }

    private fun onEditorReady() {
        isReady = true
        editorReadyCount++
        applyPendingIfReady()
        // 未就绪期挂起的 focus 意图重派一次（RN useEditorFocusController：ready 即补发）
        heldFocus?.let { scheduleFocus(it) }
        heldFocus = null
        scheduleContentFetch()
    }

    private fun onStateUpdate(payload: JsonObject) {
        payload["isFocused"]?.jsonPrimitive?.let { isFocused = it.content == "true" }
        payload["contentHeight"]?.jsonPrimitive?.doubleOrNull?.let { contentHeightDp = it }
        // 工具栏活动态（T13）：RN useBridgeState 是整体替换（每 stateUpdate 全量重建 payload
        // 且 JS undefined 键在 JSON 化即消失）——缺席键重置默认，防清除标题/字色后假亮滞留
        isBoldActive = payload["isBoldActive"]?.jsonPrimitive?.content == "true"
        isItalicActive = payload["isItalicActive"]?.jsonPrimitive?.content == "true"
        isStrikeActive = payload["isStrikeActive"]?.jsonPrimitive?.content == "true"
        isOrderedListActive = payload["isOrderedListActive"]?.jsonPrimitive?.content == "true"
        isBulletListActive = payload["isBulletListActive"]?.jsonPrimitive?.content == "true"
        headingLevel = payload["headingLevel"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        activeColor = payload["activeColor"]?.jsonPrimitive
            ?.takeIf { it !is kotlinx.serialization.json.JsonNull && it.isString }?.content
        activeFontSize = payload["activeFontSize"]?.jsonPrimitive
            ?.takeIf { it !is kotlinx.serialization.json.JsonNull && it.isString }?.content
        // 编辑器状态更新后拉一次内容（RN useEditorContent 订阅 stateUpdate → debounce 拉取同义）
        scheduleContentFetch()
    }

    private var contentFetchJob: Job? = null

    private fun scheduleContentFetch() {
        if (contentFetchJob?.isActive == true) return
        contentFetchJob = scope.launch {
            delay(CONTENT_FETCH_DEBOUNCE_MS)
            fetchContentNow()
        }
    }

    /** 立即拉取（blur/退出/发送前同步用；RN navigation blur 直接 getJSON 同义）。 */
    suspend fun fetchContentNow() {
        val json = bridge?.getJson() ?: return
        jsonContent = json
        plainText = cn.appia.im.core.messaging.extractPlainTextFromTipTapJson(json)
        onContentSettled?.invoke(json, plainText)
    }

    /** 测试缝：模拟编辑器内容落定（Robolectric 不执行 WebView JS；生产管线走 fetchContentNow）。 */
    fun simulateContent(json: JsonObject?, plain: String) {
        jsonContent = json
        plainText = plain
        onContentSettled?.invoke(json, plain)
    }

    // ── 焦点单一调度 ──

    private var focusState = FocusSchedulerState()
    private var focusJob: Job? = null
    private var lastRequestedPos = "end"

    /** 50ms debounce 后下发 focus（连发请求合并，仅最新一代生效）。 */
    fun requestFocus(pos: String = "end") {
        lastRequestedPos = pos
        scheduleFocus("focus")
    }

    /** blur 立即下发（RN blur 不 debounce——键盘应即时收起）；同时丢弃未就绪挂起的 focus。 */
    fun requestBlur() {
        focusJob?.cancel()
        heldFocus = null
        dispatchFocus("blur")
    }

    /**
     * 就绪前挂起（RN useEditorFocusController：未就绪 focus 不 debounce、记意图，
     * editor-ready 时重派一次）；blur 清挂起（后到意图胜出）。就绪后走 50ms debounce。
     */
    private fun scheduleFocus(desired: String) {
        focusJob?.cancel()
        if (!isReady) {
            heldFocus = desired
            return
        }
        val gen = focusState.generation
        focusJob = scope.launch {
            delay(focusDebounceMs)
            dispatchFocus(desired, gen)
        }
    }

    private var heldFocus: String? = null

    /** 未就绪期到达的提及选中（ready 后补插一次）。 */
    private var pendingMention: List<MentionCandidate>? = null

    private fun dispatchFocus(desired: String, requestGeneration: Int = focusState.generation) {
        val (action, next) = reduceFocusScheduler(focusState, requestGeneration, desired)
        focusState = next
        when (action) {
            "focus" -> bridge?.focus(lastRequestedPos)
            "blur" -> bridge?.blur()
        }
    }

    // ── mention-trigger 链（RN ChatInputBar.tsx:1249-1273 同构）──

    /**
     * mention-trigger 导航回调（UI 层装配：守卫房间类型后导航选人页；RN navigation.navigate
     * 'MentionSuggestion' 等价）。不设则仅记 range 不导航。
     */
    var onMentionNavigate: ((query: String, cursorPos: Long) -> Unit)? = null

    /**
     * beforeinput mention-trigger 处理：cursorPos 是 @ 插入前的光标位；range 覆盖 @+query
     * （RN :1260-1264），再交导航回调。
     */
    private fun handleMentionTrigger(query: String, cursorPos: Long) {
        mentionRange = cursorPos to cursorPos + 1 + query.length
        onMentionNavigate?.invoke(query, cursorPos)
    }

    /** 选中成员回插：先删 @query 范围（有 range 时）再逐个 insertMention（RN :766-783）。未就绪挂起，ready 后补发（fix round 2：冷 WebView 时 dispatch 即丢）。 */
    fun applyMentionSelection(members: List<MentionCandidate>) {
        if (!isReady) {
            pendingMention = members
            return
        }
        applyMentionSelectionNow(members)
    }

    private fun applyMentionSelectionNow(members: List<MentionCandidate>) {
        val range = mentionRange
        if (range != null) bridge?.deleteRange(range.first, range.second)
        for (m in members) bridge?.insertMention(m.username, m.displayName)
        mentionRange = null
    }

    /**
     * WebView 离组合（导航选人页/进多选态/离房）时重置（fix round 2）：就绪归零、桥置空——
     * 下次 editor-ready 走新就绪周期（内容重注 + 挂起提及/focus 补发）。挂起态
     * （heldFocus/pendingMention/mentionRange/pendingContent）与内容快照**不清**：控制器若由
     * entry 级宿主持有（[RoomEditorViewModel]），跨选人页往返即恢复（RN native-stack 保留前屏
     * 挂载的等价物）。
     */
    fun onWebViewDestroyed() {
        focusJob?.cancel()
        isReady = false
        bridge = null
        rpcBridge = null
    }

    /** 宿主销毁（VM onCleared）：取消自持 scope（VM 宿主的 controller 不随组合取消）。 */
    fun release() {
        onWebViewDestroyed()
        scope.cancel()
    }

    /**
     * 清空编辑器（RN clearEditorContent.ts clearChatEditor）：空 doc + 空链接/尾段清理脚本 +
     * Android 光标回起点。
     */
    fun clearEditor() {
        bridge?.setContent(EMPTY_EDITOR_DOC)
        rpcBridge?.runPostClear()
        bridge?.focus("start")
        jsonContent = null
        plainText = ""
    }

    // ── 工具栏命令（T13 / RN editorAction：动作后 50ms 重聚焦编辑器）──

    /** 动作 + 50ms 重聚焦（RN editorAction :980-986 setTimeout(focus,50) 同构）。 */
    private fun editorAction(action: () -> Unit) {
        action()
        scope.launch {
            delay(REFOCUS_DELAY_MS)
            dispatchFocus("focus")
        }
    }

    fun toggleBold() = editorAction { bridge?.sendAction(TenTapBridge.TOGGLE_BOLD) }
    fun toggleItalic() = editorAction { bridge?.sendAction(TenTapBridge.TOGGLE_ITALIC) }
    fun toggleStrike() = editorAction { bridge?.sendAction(TenTapBridge.TOGGLE_STRIKE) }
    fun toggleOrderedList() = editorAction { bridge?.sendAction(TenTapBridge.TOGGLE_ORDERED_LIST) }
    fun toggleBulletList() = editorAction { bridge?.sendAction(TenTapBridge.TOGGLE_BULLET_LIST) }

    /** 颜色（RN handleSetColor :971-978）：非空 setColor / 空 unsetColor，50ms 重聚焦。 */
    fun setColor(color: String?) = editorAction {
        if (color != null) bridge?.sendAction(TenTapBridge.SET_COLOR, kotlinx.serialization.json.JsonPrimitive(color))
        else bridge?.sendAction(TenTapBridge.UNSET_COLOR)
    }

    /**
     * 高亮组合（RN Highlight ToolBtn :1044-1061）：on = bold+color(#FF0000)+fontSize(16px)；
     * off = bold+unsetColor+unsetFontSize。活动态见 [isHighlightActive]。
     */
    fun toggleHighlight() = editorAction {
        val b = bridge ?: return@editorAction
        if (isHighlightActive) {
            b.sendAction(TenTapBridge.TOGGLE_BOLD)
            b.sendAction(TenTapBridge.UNSET_COLOR)
            b.sendAction(TenTapBridge.UNSET_FONT_SIZE)
        } else {
            b.sendAction(TenTapBridge.TOGGLE_BOLD)
            b.sendAction(TenTapBridge.SET_COLOR, kotlinx.serialization.json.JsonPrimitive(HIGHLIGHT_COLOR))
            b.sendAction(TenTapBridge.SET_FONT_SIZE, kotlinx.serialization.json.JsonPrimitive(HIGHLIGHT_FONT_SIZE))
        }
    }

    /**
     * 清除格式（RN handleClearFormat :988-1018）：web 构建无 clear-nodes/unset-all-marks 动作
     * （已核查 bundle 的 onBridgeMessage 白名单）→ 恒走 RN 的 fallback：逐活动 mark/node 翻转 +
     * unsetColor/unsetFontSize；标题/列表按当前活动态翻转。
     */
    fun clearFormat() = editorAction {
        val b = bridge ?: return@editorAction
        if (headingLevel > 0) b.sendAction(TenTapBridge.TOGGLE_HEADING, kotlinx.serialization.json.JsonPrimitive(headingLevel))
        if (isOrderedListActive) b.sendAction(TenTapBridge.TOGGLE_ORDERED_LIST)
        if (isBulletListActive) b.sendAction(TenTapBridge.TOGGLE_BULLET_LIST)
        if (isBoldActive) b.sendAction(TenTapBridge.TOGGLE_BOLD)
        if (isItalicActive) b.sendAction(TenTapBridge.TOGGLE_ITALIC)
        if (isStrikeActive) b.sendAction(TenTapBridge.TOGGLE_STRIKE)
        b.sendAction(TenTapBridge.UNSET_COLOR)
        b.sendAction(TenTapBridge.UNSET_FONT_SIZE)
        b.sendAction(TenTapBridge.UNSET_HIGHLIGHT)
    }

    companion object {
        const val FOCUS_DEBOUNCE_MS = 50L
        const val CONTENT_FETCH_DEBOUNCE_MS = 10L
        const val DEFAULT_HEIGHT_DP = 38.0
        const val MIN_HEIGHT_DP = 38.0
        const val MAX_HEIGHT_DP = 150.0
        const val REFOCUS_DELAY_MS = 50L

        /** RN ChatInputBar :1044-1061 高亮组合常量：#FF0000 + 16px。 */
        const val HIGHLIGHT_COLOR = "#FF0000"
        const val HIGHLIGHT_FONT_SIZE = "16px"

        /** RN compact 行：Math.min(Math.max(contentHeight, 38), 150)。 */
        fun clampHeight(h: Double): Double = h.coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP)

        /** RN clearEditorContent.ts EMPTY_EDITOR_DOC。 */
        val EMPTY_EDITOR_DOC: JsonObject = Json.decodeFromString(
            JsonObject.serializer(),
            """{"type":"doc","content":[{"type":"paragraph"}]}""",
        )
    }
}

/**
 * Android IME 预热判定（RN androidImePrime.ts shouldPrimeAndroidIme 逐条）：
 * Android + 编辑器就绪 + 编辑器已获焦点 + 未预热过 → 用隐藏原生输入框绑一次 IME，
 * 再让 WebView 抢回焦点（[ChatInputBarController.requestFocus]）。
 */
fun shouldPrimeAndroidIme(platform: String, isReady: Boolean, isFocused: Boolean, primed: Boolean): Boolean =
    platform == "android" && isReady && isFocused && !primed

/** 组合工厂：RoomScreen 缺省控制器（UI 测试另建实例注入以驱动内容态）。 */
@androidx.compose.runtime.Composable
fun rememberChatInputBarController(): ChatInputBarController {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return androidx.compose.runtime.remember { ChatInputBarController(scope) }
}

/**
 * 编辑器控制器的 entry 级宿主（fix round 2 Critical-1）：Navigation Compose 非 FloatingWindow
 * 目的地压栈时下层 entry maxLifecycle=CREATED、移出 visibleEntries、内容整体离组合——
 * destination 内任何 `remember`（含装配处提升）都随组合销毁；entry 的 ViewModelStoreOwner
 * 存活到 popBackStack。控制器经此存活：选人页往返后 mentionRange/挂起 focus/挂起提及/内容
 * 快照不丢（RN native-stack 保留前屏挂载的等价物）；WebView 仍随组合销毁重建，就绪态由
 * [ChatInputBarController.onWebViewDestroyed] 重置、ready 门控补发。
 */
class RoomEditorViewModel : androidx.lifecycle.ViewModel() {
    /** scope 跟随 VM（onCleared 取消）——挂起协程不悬空于销毁的组合。 */
    val controller: ChatInputBarController = ChatInputBarController(
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate,
        ),
    )

    override fun onCleared() {
        controller.release()
    }
}
