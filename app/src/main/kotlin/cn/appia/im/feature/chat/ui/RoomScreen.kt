package cn.appia.im.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onGloballyPositioned
import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.i18n.t
import cn.appia.im.core.messaging.EmojiResolver
import cn.appia.im.core.messaging.buildEditContent
import cn.appia.im.core.messaging.convertTipTapJsonToMessageParserRoot
import cn.appia.im.core.messaging.isMessageEdited
import cn.appia.im.core.messaging.rootToJsonElement
import cn.appia.im.feature.chat.DraftController
import cn.appia.im.feature.chat.MessageAction
import cn.appia.im.feature.chat.MessageActionContext
import cn.appia.im.feature.chat.MessageMultiSelectStore
import cn.appia.im.feature.chat.MentionCandidate
import cn.appia.im.feature.chat.PendingAttachment
import cn.appia.im.feature.chat.PendingAttachments
import cn.appia.im.feature.chat.PrepareStatus
import cn.appia.im.feature.chat.RoomMessagesUiState
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.core.util.formatMessageDateLabel
import cn.appia.im.core.util.isSameCalendarDay
import cn.appia.im.feature.chat.RoomAttachmentButton
import cn.appia.im.feature.chat.SelectedAttachmentList
import cn.appia.im.feature.chat.applyDisplayMessageTransforms
import cn.appia.im.feature.chat.buildBatchRecallTip
import cn.appia.im.feature.chat.buildOrderedFileIds
import cn.appia.im.feature.chat.canRecallMessage
import cn.appia.im.feature.chat.composeQuotedMessageText
import cn.appia.im.feature.chat.copyableText
import cn.appia.im.feature.chat.deserializeOriginalContent
import cn.appia.im.feature.chat.editor.ChatInputBarController
import cn.appia.im.feature.chat.editor.EditorWebView
import cn.appia.im.feature.chat.editor.rememberChatInputBarController
import cn.appia.im.feature.chat.editor.shouldPrimeAndroidIme
import cn.appia.im.feature.chat.filterVisibleDisplayMessages
import cn.appia.im.feature.chat.getOptions
import cn.appia.im.feature.chat.isReeditableRollback
import cn.appia.im.feature.chat.isRoomReadOnly
import cn.appia.im.feature.chat.serverMessageToEditableFiles
import cn.appia.im.feature.chat.summarizeSenders
import android.content.ClipData
import android.content.ClipboardManager
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** RN messageTypeLoad：load_chunk 渲染 1px 空行。 */
private val LOAD_CHUNK_TYPES = setOf("load-more-before", "load-more-after")

/** Log tag（T11 撤回失败等，与 MainActivity NAV_TAG 同前缀）。 */
private const val ROOM_TAG = "roomRoute"

/** 键盘弹起时 ProseMirror 底部 padding（RN RichText.tsx TOOLBAR_HEIGHT = 44）。 */
private const val IME_DOC_PADDING_PX = 44

/** 列表项两型：消息（含系统/1px chunk）与日期分隔（contentType 三型在此派生）。 */
internal sealed class RoomListItem {
    abstract val key: String

    data class Message(val message: MessageEntity) : RoomListItem() {
        override val key: String get() = message._id
    }

    data class DateSeparator(val tsMs: Long, val anchorId: String) : RoomListItem() {
        override val key: String get() = "sep-$anchorId"
    }
}

internal fun RoomListItem.contentType(): String = when (this) {
    is RoomListItem.Message ->
        if (isSystemMessageRow(message)) "system" else "msg"
    is RoomListItem.DateSeparator -> "separator"
}

/**
 * RN RoomMessageList renderItem 派生：与上一条（inverted index+1，即列表后一位的更旧消息）
 * 非同一天插日期分隔（含列表末条——无更旧消息必插）；分隔标签取当前消息 ts（RN 同）。
 * load_chunk 行（RN renderItem :250-252 chunk 早退）只落 1px 行、**不推分隔**（总纲 §4.3-3）；
 * 分隔的「更旧一条」仍取原始相邻位（RN derivedMessages[index+1] 不过滤 chunk）。
 */
internal fun buildRoomListItems(messages: List<MessageEntity>): List<RoomListItem> {
    val out = mutableListOf<RoomListItem>()
    for ((i, m) in messages.withIndex()) {
        out += RoomListItem.Message(m)
        if (m.t in LOAD_CHUNK_TYPES) continue
        val older = messages.getOrNull(i + 1)
        if (older == null || !isSameCalendarDay(older.ts.toLong(), m.ts.toLong())) {
            out += RoomListItem.DateSeparator(tsMs = m.ts.toLong(), anchorId = m._id)
        }
    }
    return out
}

/**
 * RoomScreen（RN screens/RoomScreen 的 M2 版：消息列表/输入/头部）。
 * 有 t 且非 announcement 的消息走 SystemMessageText；普通行（含 announcement 三型，=RN）走 MessageRow。
 * 空态：初始加载菊花 / `room_no_messages`；滚到底按钮在不在列表顶（最新处）时显示（简化：
 * 不区分"新消息到达"与"回看历史"，见任务报告）。
 * 草稿：IME 组合态不写（onValueChange 过滤 composition != null），commit 后 debounce 1s，
 * blur 即写，离开本屏 flush，发送成功清四列。
 *
 * **T11 锚点（已在 MainActivity RoomRoute 装配处接线）**：进房
 * `RoomStreamManager.subscribeRoom(rid)`（实时消息流；T6 重连重订已挂 manager 内）+
 * `RoomReadMarker`（onEnter/onLeave 挂 DisposableEffect(rid)、onMessagePersisted 接
 * incomingMessages 收集）。本屏只消费 DB 窗口流。
 */
@Composable
fun RoomScreen(
    rid: String,
    title: String,
    state: RoomMessagesUiState,
    currentUserId: String?,
    currentUsername: String?,
    serverUrl: String,
    token: String?,
    draftController: DraftController?,
    onSend: suspend (String, kotlinx.serialization.json.JsonElement?) -> Unit,
    /**
     * 文件消息（T6）：ready 附件 + 输入文案 + md → SendOrchestrator.enqueueFileMessage
     * （缺省装配前禁用）。md 对齐 RN ChatInputBar sendReadyAttachments：`plainText.trim() && jsonContent`
     * 才发——纯附件无文案不发 md。
     */
    onSendFiles: suspend (List<cn.appia.im.core.media.LocalFileInput>, String, kotlinx.serialization.json.JsonElement?) -> Unit = { _, _, _ -> },
    onResend: (MessageEntity) -> Unit,
    /** 附件点击路由（T7：图片/视频/音频/文档 → MainActivity 导航装配）。 */
    onAttachmentNav: (AttachmentNav) -> Unit = {},
    /** 表情回应 toggle（T8）：(message, emoji=shortname) → 装配处乐观翻转 + chat.react。 */
    onToggleReaction: suspend (MessageEntity, String) -> Unit = { _, _ -> },
    /** 合并转发卡片点击（T9）：(msgData 原文, 标题) → ForwardDetail 路由。 */
    onOpenForwardMerge: (String, String) -> Unit = { _, _ -> },
    /** 已读回执明细路由（T10）：自己的消息 unread 可点图标 → ReadReceiptScreen。 */
    onOpenReadReceipt: (MessageEntity) -> Unit = {},
    /** 未读横幅数据源（T10 / RN useRoomUnreadBanner）：GET room.firsUnread；失败/关闭回 null。 */
    loadFirstUnread: suspend (String) -> cn.appia.im.core.network.api.FirstUnread? = { null },
    /** 房间只读（T11 / RN isRoomReadOnly = archived||ro）：只读房拦长按菜单。 */
    isRoomReadOnly: Boolean = false,
    /** 撤回（T11 / RN onRecall doRecall：先快照后 POST，装配处 = RecallActions.recall）。 */
    onRecall: suspend (MessageEntity) -> Unit = {},
    /** 批量撤回（T11 多选条 / RN handleBatchRecall：POST message.batch.recall，装配处实现）。 */
    onBatchRecall: suspend (List<String>) -> Unit = {},
    /**
     * 编辑提交（T12 / RN onSendFiles editing 分支装配）：(message, msg, md, 附件条行)。
     * **绕过 Orchestrator**：items 空 → 直 DDP updateMessage；非空 → 逐文件多附件上传 +
     * `multiAttachments.replace`（装配处 MessageEditController/UploadReplaceApi）。
     */
    onEditSubmit: suspend (MessageEntity, String, kotlinx.serialization.json.JsonElement?, List<PendingAttachment>?) -> Unit = { _, _, _, _ -> },
    /** @提及选人页路由（T12 / RN navigation.navigate('MentionSuggestion', {initialQuery})）。 */
    onOpenMentionSuggestion: (String) -> Unit = {},
    /**
     * @提及选中结果流（T12 评审 Critical-1 修：savedStateHandle 观察流）。装配处 =
     * `previousBackStackEntry.savedStateHandle.getStateFlow(MENTION_SELECTED_KEY, emptyList())`
     * ——Navigation Compose 跨屏结果惯例；缺省空流（无选人页时无事件）。
     */
    mentionSelections: kotlinx.coroutines.flow.StateFlow<List<MentionCandidate>> =
        kotlinx.coroutines.flow.MutableStateFlow(emptyList()),
    /**
     * 选中结果消费后回调（fix round 2 Important-2）：装配处 remove savedStateHandle 键——
     * getStateFlow 粘性，不清键则再次进选人页取消返回/离房回房时旧值重放 → 二次 insertMention。
     */
    onMentionSelectionConsumed: () -> Unit = {},
    /** 编辑器控制器（T12；缺省本组合建——UI 测试注入自有实例驱动内容态）。 */
    editorController: ChatInputBarController = rememberChatInputBarController(),
    /** 转发路由（T11 / RN ForwardSelect isMerged）：(messageIds, 合并?)。单条与多选共用。 */
    onForward: (List<String>, Boolean) -> Unit = { _, _ -> },
    /** 自定义表情解析（T13 / RN getCustomEmoji）：shortname → resolver 命中；null 走查表文本。 */
    getCustomEmoji: ((String) -> cn.appia.im.core.messaging.ResolvedEmoji?)? = null,
    /** 本地附件失败重试（T13 / RN retryFile）：(messageId, attachmentId) → SendOrchestrator.retryFile。 */
    onRetryAttachment: suspend (String, String) -> Unit = { _, _ -> },
    /** 房间信息页入口（M4-T4 / RN openRoomInfo：标题点击 + 更多钮）——装配处 navigate(RoomInfoRoute)。 */
    onOpenRoomInfo: (() -> Unit)? = null,
    /** 真名显示（M5-T4 / RN usePublicSettingBoolean('UI_Use_Real_Name', true)）：装配处 observeById 表读，缺行 true。 */
    useRealName: Boolean = true,
    onBack: () -> Unit,
    onLoadEarlier: () -> Unit,
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // rollback 分组（T11 / RN applyDisplayMessageTransforms + filterVisible）：连续同 rollbacker
    // 归组后过滤 hidden 条，再走日期分隔（RN visibleMessages 同序）
    val display = remember(state.messages) { applyDisplayMessageTransforms(state.messages) }
    val visible = remember(display) { filterVisibleDisplayMessages(display) }
    val rollbackGroups = remember(display) {
        display.mapNotNull { d -> d.rollbackGroup?.let { d.message._id to it } }.toMap()
    }
    val items = remember(visible) { buildRoomListItems(visible.map { it.message }) }
    val latestState by rememberUpdatedState(state)
    val latestOnLoadEarlier by rememberUpdatedState(onLoadEarlier)

    // RN onEndReached（inverted 列表末端 = 最旧）：接近列表顶端触发 loadEarlier
    LaunchedEffect(listState, items) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: 0 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (items.isNotEmpty() && lastVisible >= items.size - 3 &&
                    latestState.hasMoreEarlier && !latestState.isLoadingEarlier
                ) {
                    latestOnLoadEarlier()
                }
            }
    }

    // 未读横幅（T10 / RN useRoomUnreadBanner :16-45 + RoomScreen:783-799）：进房拉一次
    // room.firsUnread；count>=10 才显示；滚过 firstUnread（该消息可见且 index+1>=unreadCount）
    // 或点击跳转后消失（RN hideBanner 单向，不复现）。
    var firstUnread by remember(rid) { mutableStateOf<cn.appia.im.core.network.api.FirstUnread?>(null) }
    var bannerDismissed by remember(rid) { mutableStateOf(false) }
    LaunchedEffect(rid) {
        firstUnread = try {
            loadFirstUnread(rid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }?.takeIf { it.success }
    }
    val bannerMsgId = firstUnread?.messageId
    val bannerCount = firstUnread?.unread ?: 0
    LaunchedEffect(listState, bannerMsgId, bannerCount) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.toList() }
            .collect { infos ->
                if (bannerMsgId != null && infos.any { it.key == bannerMsgId && it.index + 1 >= bannerCount }) {
                    bannerDismissed = true
                }
            }
    }
    val bannerVisible = !bannerDismissed && unreadBannerVisible(bannerMsgId, bannerCount)

    // ── 编辑器与草稿（T12 / RN ChatInputBar + useDraft 时序）──
    // 草稿注入两道门（RN ChatInputBar.tsx:465-494 的 editorRef 教训）：controller 是稳定引用，
    // LaunchedEffect 只以 (rid, draftController) 为 key——**禁以 editor/controller 实例为依赖**
    // （RN 根因：editor 每渲染换引用 → 注入 effect 重跑 → 旧 draft 覆盖输入）。
    val controller = editorController
    LaunchedEffect(rid, draftController, controller) {
        controller.onContentSettled = { json, plain ->
            // 内容落定 → 草稿 debounce（draft_message=TipTap JSON / draft_message_plain=纯文本）
            draftController?.onTextChanged(rid, json?.toString().orEmpty(), plain)
        }
        val saved = draftController?.loadDraftJson(rid)
        if (!saved.isNullOrEmpty()) {
            runCatching { Json.parseToJsonElement(saved) as? JsonObject }
                .getOrNull()?.let { controller.setContentWhenReady(it) }
        }
    }
    // 失焦即写（RN saveDraftImmediate）：先同步拉最新内容（异步 RPC 滞后保护）再 onBlur
    LaunchedEffect(controller) {
        var prevFocused = false
        snapshotFlow { controller.isFocused }.distinctUntilChanged().collect { focused ->
            if (prevFocused && !focused) {
                runCatching { controller.fetchContentNow() }
                draftController?.onBlur(rid, controller.jsonContent?.toString().orEmpty(), controller.plainText)
            }
            prevFocused = focused
        }
    }
    DisposableEffect(rid) {
        onDispose { draftController?.flushOnDispose(rid) }
    }

    // 附件（T6）：rid 维度实例（RN usePendingAttachments per-rid 清空同义）+ 附件条状态
    val pendingAttachments = remember(rid) {
        PendingAttachments(
            uploadsDir = File(context.cacheDir, "uploads"),
            resolver = context.contentResolver,
            scope = scope,
        )
    }
    val attachments by pendingAttachments.items.collectAsState()

    // ── T11：长按菜单 / 回复态 / 多选态（RN handleMessageLongPress + ReplyContext + multiSelectStore）──
    val messageContext = remember(currentUserId) {
        MessageActionContext(currentUserId = currentUserId.orEmpty()) // 权限硬编码对照 RN RoomScreen:423-427
    }
    var sheetMessage by remember(rid) { mutableStateOf<MessageEntity?>(null) }
    var replyTo by remember(rid) { mutableStateOf<MessageEntity?>(null) }
    var batchRecallConfirm by remember(rid) { mutableStateOf(false) }
    val multiSelect = remember(rid) { MessageMultiSelectStore() }
    val multiState by multiSelect.state.collectAsState()

    // ── T13：表格全屏 overlay / KaTeX 公式查看 overlay（RN MarkdownTableScreen 路由的懒 overlay 等价）──
    var tableOverlayRows by remember(rid) { mutableStateOf<List<cn.appia.im.core.messaging.MdNode>?>(null) }
    var katexOverlayFormula by remember(rid) { mutableStateOf<String?>(null) }

    // ── 编辑模式（T12 / RN EditContext editingMessage + stashedEditingDraftRef）──
    /** 编辑态 stash（RN stashedEditingDraftRef：pending 附件 + 当前编辑器 JSON，退出恢复）。 */
    data class EditStash(val items: List<PendingAttachment>, val json: JsonObject?)
    var editingMessage by remember(rid) { mutableStateOf<MessageEntity?>(null) }
    var editStash by remember(rid) { mutableStateOf<EditStash?>(null) }

    // Aa 工具栏（T13 / RN AicState L2 toolbar：hidden|visible|colorSubPanel）
    var showToolbar by remember(rid) { mutableStateOf(false) }
    var showColorPicker by remember(rid) { mutableStateOf(false) }

    // T13：buildEditContent 用 resolver（EmojiResolver 接口适配 getCustomEmoji lambda）
    val emojiResolver = getCustomEmoji?.let { f -> EmojiResolver { code -> f(code) } }

    /** buildEditContent 产物分流（TipTap JSON / 无 md 的 HTML 串）。 */
    fun applyEditContent(content: kotlinx.serialization.json.JsonElement) {
        when (content) {
            is JsonObject -> controller.setContentWhenReady(content)
            is JsonPrimitive -> controller.setContentHtmlWhenReady(content.content)
            else -> Unit
        }
    }

    /** 进入编辑（RN setEditing :1415-1422）：stash 当前 → setContent(buildEditContent) → focus。 */
    fun enterEdit(m: MessageEntity) {
        if (editingMessage == null) {
            editStash = EditStash(pendingAttachments.items.value, controller.jsonContent)
            // 编辑态附件回填（RN serverMessageToEditableAttachments：files 列水合，带 fileId 不重传）
            val serverFiles = serverMessageToEditableFiles(m)
            if (serverFiles.isNotEmpty()) pendingAttachments.hydrate(serverFiles)
        }
        // T13：自定义表情 resolver（:colon: 名字回填编辑器需查表，RN getCustomEmoji 同义）
        applyEditContent(buildEditContent(m, emojiResolver, serverUrl.takeIf { it.isNotBlank() }))
        controller.requestFocus("end")
        editingMessage = m
        replyTo = null // RN setEditing :1421 setReplyingMessage(null)
    }

    /** 退出编辑（RN EditPreview ✕ → clearEditing :1423-1425 → effect 恢复 stash :549-559）。 */
    fun exitEdit() {
        editingMessage = null
        val stash = editStash ?: return
        editStash = null
        pendingAttachments.hydrate(stash.items)
        controller.setContentWhenReady(stash.json)
    }

    /** 长按入口（RN handleMessageLongPress :806-812 早退守卫：多选态/只读房不开菜单）。 */
    val handleLongPress: (MessageEntity) -> Unit = { m ->
        if (!multiState.active && !isRoomReadOnly) sheetMessage = m
    }
    /** 行点击（RN handleMessagePress :838-842：多选态下点击即切换选中）。 */
    val handleRowClick: (MessageEntity) -> Unit = { m ->
        if (multiState.active) multiSelect.toggle(m)
    }

    /** 菜单动作分发（RN handlers :815-873 逐条）。 */
    fun dispatchAction(action: MessageAction, m: MessageEntity) {
        when (action) {
            MessageAction.REPLY -> replyTo = m
            MessageAction.EDIT -> enterEdit(m) // T12：接编辑器（RN onEdit → setEditing）
            MessageAction.COPY -> {
                val text = copyableText(m)
                if (text.isNotEmpty()) {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                        ClipData.newPlainText("message", text),
                    )
                    Toast.makeText(context, context.t("copied_to_clipboard"), Toast.LENGTH_SHORT).show()
                }
            }
            MessageAction.FORWARD -> onForward(listOf(m._id), false)
            MessageAction.MULTI_SELECT -> multiSelect.enter(rid, m)
            MessageAction.RECALL -> scope.launch {
                try {
                    onRecall(m)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(ROOM_TAG, "recall message failed id=${m._id}", e) // RN console.warn :879
                }
            }
            MessageAction.RESEND -> onResend(m)
        }
    }

    /**
     * 重新编辑（RN handleReedit :805-812：快照反序列化 → setContent 回填，不进编辑模式）。
     * T11 rider：**tmid 回复态恢复/清空一并处理**（RN ChatInputBar setContent :1426-1441）。
     */
    val handleReedit: (MessageEntity) -> Unit = { m ->
        deserializeOriginalContent(m)?.let { restored ->
            applyEditContent(
                buildEditContent(
                    m.copy(
                        msg = restored.msg ?: m.msg,
                        md = restored.md,
                        mentions = restored.mentions,
                        tmid = restored.tmid,
                    ),
                    emojiResolver, // T13：自定义表情回填（同 enterEdit）
                    serverUrl.takeIf { it.isNotBlank() },
                ),
            )
            replyTo = if (!restored.tmid.isNullOrEmpty()) m else null
        }
    }

    /** 批量撤回确认文案（RN :981-985 buildBatchRecallTip；弹窗打开时才组装）。 */
    val batchRecallTipText = if (batchRecallConfirm) {
        val tip = buildBatchRecallTip(
            summarizeSenders(multiState.selectedMap.values.toList(), currentUsername),
        )
        systemMessageT(context)(tip.key, tip.params)
    } else {
        ""
    }

    /**
     * 发送（RN ChatInputBar handleSubmit :846-954）：发送前同步拉编辑器最新内容
     * （RN blur→getJSON 语义）；编辑态 → [onEditSubmit]（绕 Orchestrator），普通态 →
     * 附件优先（enqueueFileMessage）否则文本（enqueueTextMessage 带 md）。
     */
    suspend fun handleSend() {
        runCatching { controller.fetchContentNow() }
        val editing = editingMessage
        val json = controller.jsonContent
        val plain = controller.plainText
        val md = json?.let {
            runCatching { rootToJsonElement(convertTipTapJsonToMessageParserRoot(it)) }.getOrNull()
        }
        if (editing != null) {
            val items = pendingAttachments.items.value
            // RN :857-861：编辑态附件未 ready → toast 拦（不提交）
            if (items.any { it.prepareStatus != PrepareStatus.READY }) {
                Toast.makeText(context, context.t("edit_message_failed"), Toast.LENGTH_SHORT).show()
                return
            }
            // null（updateMessage，服务端不动附件）仅限纯文本消息；带文件的消息一律走
            // replace 整包覆盖（可为空表）——否则编辑态删空附件条时服务端旧附件残留
            val hadServerFiles = !editing.files.isNullOrEmpty()
            val submitItems: List<PendingAttachment>? = if (items.isEmpty() && !hadServerFiles) null else items
            try {
                onEditSubmit(editing, plain, md, submitItems)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(ROOM_TAG, "edit message failed id=${editing._id}", e) // RN :660-662 console.warn + toast
                Toast.makeText(context, context.t("edit_message_failed"), Toast.LENGTH_SHORT).show()
                return
            }
            // 提交成功：stash 丢弃（RN discardStashedEditingDraftRef :897）+ 四态清
            editingMessage = null
            editStash = null
            pendingAttachments.clear()
            controller.clearEditor()
            draftController?.clearAfterSend(rid)
            return
        }
        val files = pendingAttachments.readyFiles
        if (files.isNotEmpty()) {
            // RN sendReadyAttachments :807-810：`plainText.trim() && jsonContent` 才发 md——纯附件无文案不发
            val fileMd = plain.trim().takeIf { it.isNotEmpty() }?.let { md }
            val finalMsg = composeQuotedMessageText(
                plainText = plain,
                replyingMessage = replyTo,
                serverUrl = serverUrl,
                rid = rid,
                roomType = state.roomType.ifEmpty { null },
                authUserId = currentUserId,
            )
            onSendFiles(files, finalMsg, fileMd)
            pendingAttachments.clear()
            controller.clearEditor()
            draftController?.clearAfterSend(rid)
            replyTo = null
            return
        }
        if (plain.isBlank()) return
        val finalMsg = composeQuotedMessageText(
            plainText = plain,
            replyingMessage = replyTo,
            serverUrl = serverUrl,
            rid = rid,
            roomType = state.roomType.ifEmpty { null },
            authUserId = currentUserId,
        )
        onSend(finalMsg, md)
        controller.clearEditor()
        draftController?.clearAfterSend(rid)
        replyTo = null // RN :821/:926 发送后清回复态
    }

    Column(Modifier.fillMaxSize().background(colors.backgroundColor)) {
        RoomHeader(title = title, onBack = onBack, onTitleClick = onOpenRoomInfo)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.messages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (state.isInitialLoading) {
                        CircularProgressIndicator(Modifier.testTag("qa-room-message-list-loading"))
                    } else {
                        Text(
                            context.t("room_no_messages"),
                            color = colors.auxiliaryText,
                            fontSize = 14.sp,
                            modifier = Modifier.testTag("qa-room-message-list-empty"),
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    reverseLayout = true, // RN inverted：index 0 = 最新 = 底部
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("qa-room-message-list"),
                ) {
                    items(items, key = { it.key }, contentType = { it.contentType() }) { item ->
                        when (item) {
                            is RoomListItem.Message -> when {
                                item.message.t in LOAD_CHUNK_TYPES ->
                                    Box(Modifier.fillMaxWidth().height(1.dp)) // RN load_chunk 1px
                                isSystemMessageRow(item.message) && rollbackGroups.containsKey(item.message._id) ->
                                    // rollback 分组头（T11 / RN SystemMessage → RollbackMessageGroup，≥2 条成组）
                                    RollbackMessageGroup(
                                        groupMessage = item.message,
                                        groupMessages = rollbackGroups[item.message._id].orEmpty(),
                                        currentUserId = currentUserId,
                                    )
                                isSystemMessageRow(item.message) ->
                                    SystemMessageText(
                                        item.message,
                                        // 重新编辑（RN SystemMessage isReeditableRollback :25-33）
                                        onReedit = if (isReeditableRollback(item.message, currentUserId)) {
                                            { handleReedit(item.message) }
                                        } else {
                                            null
                                        },
                                    )
                                else ->
                                    MessageRow(
                                        message = item.message,
                                        currentUserId = currentUserId,
                                        currentUsername = currentUsername,
                                        serverUrl = serverUrl,
                                        token = token,
                                        useRealName = useRealName,
                                        onResend = onResend,
                                        onAttachmentNav = onAttachmentNav,
                                        // 表情回应（T8）：行内反应条点击 → 乐观翻转 + chat.react（装配处实现）
                                        onToggleReaction = { emoji ->
                                            scope.launch { onToggleReaction(item.message, emoji) }
                                        },
                                        // 合并转发卡片（T9）：点击进 ForwardDetail（装配处导航）
                                        onOpenForwardMerge = onOpenForwardMerge,
                                        // 已读回执（T10）：unread 可点图标进明细（DM 例外在行内判定）
                                        roomType = state.roomType,
                                        onOpenReadReceipt = onOpenReadReceipt,
                                        // 长按菜单 + 多选点选（T11）
                                        onLongPress = handleLongPress,
                                        onClick = handleRowClick,
                                        selected = if (multiState.active) {
                                            item.message._id in multiState.selectedIds
                                        } else {
                                            null
                                        },
                                        // T13：自定义表情进 InlineEnv / 表格·公式 overlay 入口 / 附件重试
                                        getCustomEmoji = getCustomEmoji,
                                        onTableOpen = { rows -> tableOverlayRows = rows },
                                        onKatexClick = { formula -> katexOverlayFormula = formula },
                                        onRetryAttachment = { messageId, attachmentId ->
                                            scope.launch {
                                                try {
                                                    onRetryAttachment(messageId, attachmentId)
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (e: Exception) {
                                                    Log.w(ROOM_TAG, "retry attachment failed id=$messageId", e)
                                                    Toast.makeText(context, context.t("send_file_failed"), Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                    )
                            }
                            is RoomListItem.DateSeparator -> DateSeparator(item.tsMs)
                        }
                    }
                }

                // 未读横幅（T10）：右上角 pill，点击滚到 firstUnread 并消失
                if (bannerVisible) {
                    UnreadBanner(
                        unreadCount = bannerCount,
                        onPress = {
                            val idx = items.indexOfFirst { it.key == bannerMsgId }
                            if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) }
                            bannerDismissed = true // RN handleUnreadBannerPress :794-799
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 30.dp),
                    )
                }

                // 滚到底：不在最新处即显示（简化实现）
                if (listState.firstVisibleItemIndex > 0) {
                    Text(
                        "↓",
                        color = colors.tintColor,
                        fontSize = 20.sp,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                            .size(40.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(colors.chatComponentBackground)
                            .wrapContentSize(Alignment.Center)
                            .clickable { scope.launch { listState.animateScrollToItem(0) } }
                            .testTag("qa-room-scroll-to-bottom"),
                    )
                }
            }
        }

        // 附件条：输入区上方（RN SelectedAttachmentList 挂位；T11 组装完整形态）
        if (attachments.isNotEmpty() && !multiState.active) {
            SelectedAttachmentList(
                items = attachments,
                onRemove = { pendingAttachments.remove(it) },
            )
        }

        // 回复预览（T11 / RN ChatInputBar ReplyPreview：发送者名 + 原文一行 + ✕ 关闭）
        replyTo?.let { reply ->
            val replyName = parseMessageUser(reply.u).name ?: parseMessageUser(reply.u).username.orEmpty()
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.messageboxBackground)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .testTag("qa-reply-preview"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        replyName,
                        color = colors.primary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        reply.msg.orEmpty(),
                        color = colors.auxiliaryText,
                        fontSize = 13.sp,
                        maxLines = 1,
                    )
                }
                Text(
                    "✕",
                    color = colors.auxiliaryText,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .clickable { replyTo = null }
                        .padding(8.dp)
                        .testTag("qa-reply-close"),
                )
            }
        }

        // 底部区（RN RoomFooter 优先级：多选态操作栏替换输入框；只读横幅 M2 现状不变）
        if (multiState.active) {
            MultiSelectActionBar(
                selectedCount = multiState.selectedIds.size,
                canRecall = multiState.selectedMap.values.all { canRecallMessage(it, messageContext) },
                onForwardOneByOne = { onForward(multiState.selectedIds, false) },
                onForwardCombine = { onForward(multiState.selectedIds, true) },
                onBatchRecall = { batchRecallConfirm = true },
                onCancel = { multiSelect.exit() },
            )
        } else {
            // 编辑横幅（T12 / RN EditPreview：编辑中标题 + ✕ 退出恢复 stash）
            if (editingMessage != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.messageboxBackground)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .testTag("qa-edit-banner"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        context.t("chatinput_editmessage"),
                        color = colors.primary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "✕",
                        color = colors.auxiliaryText,
                        fontSize = 16.sp,
                        modifier = Modifier
                            .clickable { exitEdit() }
                            .padding(8.dp)
                            .testTag("qa-edit-close"),
                    )
                }
            }

            // Android IME 预热（RN androidImePrime：从未交互的 WebView requestFocus 拉不起 IME，
            // 先用隐藏原生输入框绑一次 IME，再让 WebView 抢回焦点；仅一次）
            var imePrimed by remember(rid) { mutableStateOf(false) }
            val imeAnchor = remember { FocusRequester() }
            LaunchedEffect(controller.isReady, controller.isFocused) {
                if (shouldPrimeAndroidIme("android", controller.isReady, controller.isFocused, imePrimed)) {
                    imePrimed = true
                    runCatching { imeAnchor.requestFocus() }
                    controller.requestFocus("end")
                }
            }
            @Suppress("ComposeModifierMissing")
            BasicTextField(
                value = "",
                onValueChange = {},
                modifier = Modifier
                    .size(1.dp)
                    .alpha(0f)
                    .focusRequester(imeAnchor),
            )

            // 键盘联动（RN RichText.tsx:91-101 Android 路径：键盘弹起 → ProseMirror 底部
            // padding + 滚动 margin = 工具条高度 44，收起归零）
            val imeVisible = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(
                androidx.compose.ui.platform.LocalDensity.current,
            ) > 0
            LaunchedEffect(imeVisible) {
                val padding = if (imeVisible) IME_DOC_PADDING_PX else 0
                controller.rpcBridge?.setDocBottomPadding(padding)
                controller.rpcBridge?.updateScrollThresholdAndMargin(padding)
            }

            // mention-trigger → 选人页（RN ChatInputBar :1249-1273；DM 房不触发，RN :1256 同守卫）
            controller.onMentionNavigate = { query, cursorPos ->
                if (rid.isNotEmpty() && state.roomType != "d") {
                    onOpenMentionSuggestion(query)
                } else {
                    controller.mentionRange = null
                    cursorPos.hashCode() // no-op（统一 lambda 签名）
                }
            }
            // 选人回插（RN DeviceEventEmitter MENTION_SELECTED_EVENT :763-785）+ 回房重新拉起键盘。
            // 结果经 savedStateHandle 观察流投递（装配处接 previousBackStackEntry；共享 Flow 在
            // 选人页打开期间 RoomScreen collector 已取消、tryEmit 即丢，不可用——评审 Critical-1）。
            // 消费即清键（Important-2：防粘性重放二次插入）；回插本体有 controller 就绪门控兜底
            LaunchedEffect(rid, controller) {
                mentionSelections.collect { members ->
                    if (members.isEmpty()) return@collect
                    controller.applyMentionSelection(members)
                    controller.requestFocus("end")
                    onMentionSelectionConsumed()
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.messageboxBackground)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
            RoomAttachmentButton(pending = pendingAttachments)
            Box(
                Modifier
                    .weight(1f)
                    .widthIn(max = 320.dp)
                    // contentHeight 联动（RN :1225 compact 行 min 38 max 150）
                    .height(cn.appia.im.feature.chat.editor.ChatInputBarController.clampHeight(controller.contentHeightDp).dp)
                    .background(colors.backgroundColor, RoundedCornerShape(18.dp))
                    .testTag("qa-room-editor"),
            ) {
                EditorWebView(
                    modifier = Modifier.fillMaxSize(),
                    onMessage = { raw -> controller.onRawMessage(raw) },
                    onWebViewReady = { _, bridge ->
                        controller.bridge = bridge
                        controller.rpcBridge = bridge
                    },
                    // WebView 离组合（导航选人页/多选态替换）：就绪态重置 + 桥置空（fix round 2）；
                    // 控制器（entry VM 宿主）存活，返回重建后 ready 门控重注内容/补发挂起提及
                    onWebViewReleased = { controller.onWebViewDestroyed() },
                )
            }
            // 工具栏 @ 直跳（RN :1029-1043：非 DM 房显示；range 置空 = 光标处插入）
            if (state.roomType != "d") {
                Text(
                    "@",
                    color = colors.auxiliaryText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .size(36.dp)
                        .wrapContentSize(Alignment.Center)
                        .clickable {
                            controller.mentionRange = null
                            onOpenMentionSuggestion("")
                        }
                        .testTag("qa-room-mention"),
                )
            }
            Text(
                "↑",
                color = if (controller.plainText.isNotBlank() || pendingAttachments.readyFiles.isNotEmpty()) {
                    colors.tintColor
                } else {
                    colors.auxiliaryText
                },
                fontSize = 22.sp,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(40.dp)
                    .wrapContentSize(Alignment.Center)
                    .clickable(
                        enabled = controller.plainText.isNotBlank() ||
                            pendingAttachments.readyFiles.isNotEmpty() ||
                            pendingAttachments.isPreparing ||
                            attachments.isNotEmpty(),
                    ) {
                        scope.launch { handleSend() }
                    }
                    .testTag("qa-room-send"),
            )
            }

            // ── Aa 格式工具栏（T13 / RN ChatInputBar :1020-1146）──
            // Aa 切换（RN handleToggleToolbar；compact 态显示/隐藏工具栏）
            Text(
                "Aa",
                color = if (showToolbar) colors.tintColor else colors.auxiliaryText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(36.dp)
                    .wrapContentSize(Alignment.Center)
                    .clickable { showToolbar = !showToolbar }
                    .testTag("qa-room-toolbar-toggle"),
            )
        }

        // 工具栏行（RN toolbarJSX：@/高亮/字色/加粗/斜体/删除线/清除格式/有序/无序列表）
        if (showToolbar) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.messageboxBackground)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .testTag("qa-room-toolbar"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // @ 门控（RN :1031 `chatType !== 'd' || fromAgent`——DM 无提及；fromAgent 域 M4/M5）
                if (state.roomType != "d") {
                    ToolbarTextButton("@", active = false, tag = "qa-toolbar-mention") {
                        controller.mentionRange = null
                        onOpenMentionSuggestion("")
                    }
                }
                ToolbarTextButton(
                    context.t("chatinput_highlight"),
                    active = controller.isHighlightActive,
                    tag = "qa-toolbar-highlight",
                ) { controller.toggleHighlight() }
                ToolbarTextButton(
                    context.t("chatinput_fontcolor"),
                    active = showColorPicker,
                    tag = "qa-toolbar-fontcolor",
                ) {
                    showColorPicker = !showColorPicker
                    controller.requestFocus("end")
                }
                ToolbarTextButton(
                    context.t("chatinput_bold"),
                    active = controller.isBoldActive,
                    tag = "qa-toolbar-bold",
                ) { controller.toggleBold() }
                ToolbarTextButton(
                    context.t("chatinput_italic"),
                    active = controller.isItalicActive,
                    tag = "qa-toolbar-italic",
                ) { controller.toggleItalic() }
                ToolbarTextButton(
                    context.t("chatinput_strike"),
                    active = controller.isStrikeActive,
                    tag = "qa-toolbar-strike",
                ) { controller.toggleStrike() }
                ToolbarTextButton(
                    context.t("chatinput_clearformat"),
                    active = false,
                    tag = "qa-toolbar-clearformat",
                ) { controller.clearFormat() }
                ToolbarTextButton(
                    context.t("chatinput_orderedlist"),
                    active = controller.isOrderedListActive,
                    tag = "qa-toolbar-orderedlist",
                ) { controller.toggleOrderedList() }
                ToolbarTextButton(
                    context.t("chatinput_bulletlist"),
                    active = controller.isBulletListActive,
                    tag = "qa-toolbar-bulletlist",
                ) { controller.toggleBulletList() }
            }
        }

        // 颜色行（RN colorRowJSX / PRESET_COLORS :152-164：null=默认色 + 10 预设）
        if (showToolbar && showColorPicker) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.messageboxBackground)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("qa-room-color-row"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PRESET_COLORS.forEach { (labelKey, value) ->
                    val isActive = if (value == null) controller.activeColor == null else controller.activeColor == value
                    Box(
                        Modifier
                            .padding(end = 10.dp)
                            .size(26.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(
                                when {
                                    value != null -> parseColorOrNull(value) ?: colors.tintColor
                                    else -> colors.backgroundColor
                                },
                            )
                            .border(
                                width = if (isActive) 2.dp else 1.dp,
                                color = if (isActive) colors.tintColor else colors.borderColor,
                            )
                            .clickable {
                                controller.setColor(value)
                                showColorPicker = false
                            }
                            .testTag("qa-color-${labelKey.substringAfter('_')}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (value == null) {
                            Text("A", color = colors.bodyText, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
        }

        // 长按菜单（T11）：getOptions 判定 → Sheet 分发
        sheetMessage?.let { m ->
            MessageActionsSheet(
                actions = getOptions(m, messageContext),
                onAction = { action ->
                    sheetMessage = null
                    dispatchAction(action, m)
                },
                onDismiss = { sheetMessage = null },
            )
        }

        // 批量撤回确认（T11 / RN handleBatchRecall Alert：确定 → POST；**成功才退多选**——
        // T12 rider 修正：T11 先退后调使失败后需重进多选重选，RN :996-1005 为 await 成功才退）
        if (batchRecallConfirm) {
            BatchRecallConfirmDialog(
                message = batchRecallTipText,
                onConfirm = {
                    batchRecallConfirm = false
                    val ids = multiState.selectedIds
                    scope.launch {
                        try {
                            onBatchRecall(ids)
                            multiSelect.exit()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // RN :996-999 catch → toast multiSelect_batchRecallFailed（留在多选态）
                            Toast.makeText(context, context.t("multiselect_batchrecallfailed"), Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onDismiss = { batchRecallConfirm = false },
            )
        }

        // T13 overlay：表格全屏 / KaTeX 公式查看（消息列表之上、整屏 scrim）
        tableOverlayRows?.let { rows ->
            MarkdownTableOverlay(rows = rows, onClose = { tableOverlayRows = null })
        }
        katexOverlayFormula?.let { formula ->
            KatexFormulaOverlay(math = formula, onClose = { katexOverlayFormula = null })
        }
    }

/** RN MessageDateSeparator：线 + `yyyy年M月d日` + 线。（T9 起与 ForwardDetailScreen 共用） */
@Composable
internal fun DateSeparator(tsMs: Long) {
    val colors = LocalAppiaColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("qa-message-date-separator"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(colors.borderColor))
        Text(
            formatMessageDateLabel(tsMs),
            color = colors.auxiliaryText,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Box(Modifier.weight(1f).height(1.dp).background(colors.borderColor))
    }
}

// ── Aa 工具栏（T13 / RN ChatInputBar PRESET_COLORS :152-164 + ToolBtn）──

/** RN PRESET_COLORS：label i18n 键 + 色值（null = 默认色）。 */
internal val PRESET_COLORS: List<Pair<String, String?>> = listOf(
    "chatinput_colordefault" to null,
    "chatinput_colorblack" to "#1A1A1A",
    "chatinput_colordarkgray" to "#6B7280",
    "chatinput_colorred" to "#EF4444",
    "chatinput_colororange" to "#F97316",
    "chatinput_coloryellow" to "#EAB308",
    "chatinput_colorgreen" to "#22C55E",
    "chatinput_colorcyan" to "#06B6D4",
    "chatinput_colorblue" to "#3B82F6",
    "chatinput_colorpurple" to "#A855F7",
    "chatinput_colorpink" to "#EC4899",
)

/** #RRGGBB 解析（坏值 null；UI 兜底 tintColor）。 */
internal fun parseColorOrNull(hex: String): Color? = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()

/** 工具栏文本按钮（RN ToolBtn：active 高亮色 + 同一回调形态）。 */
@Composable
private fun ToolbarTextButton(
    label: String,
    active: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val colors = LocalAppiaColors.current
    Text(
        label,
        color = if (active) colors.tintColor else colors.auxiliaryText,
        fontSize = 13.sp,
        fontWeight = if (active) FontWeight.SemiBold else null,
        maxLines = 1,
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) colors.chatComponentBackground else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp)
            .testTag(tag),
    )
}

/**
 * 路由装配依赖束原 `RoomScreenDeps` 已于 T11 更名 [cn.appia.im.RouteDeps] 并移入 MainActivity.kt
 * （同时服务 Main/Room 两路由）；进房接线（subscribeRoom/已读标记/草稿 flush 的生命周期编排）见
 * MainActivity RoomRoute 装配处与 RoomScreen KDoc 的 T11 锚点说明。
 */
