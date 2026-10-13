package cn.appia.im.feature.mail

import androidx.lifecycle.ViewModel
import cn.appia.im.core.mail.MailAccount
import cn.appia.im.core.mail.MailClient
import cn.appia.im.core.mail.MailClientAuth
import cn.appia.im.core.mail.MailDraft
import cn.appia.im.core.mail.MailFolder
import cn.appia.im.core.mail.MailMessage
import cn.appia.im.core.mail.MessageListResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 收件箱状态（MailChannelScreen/index.tsx:143-153 useState 组转录）。
 * initialScrollIndex 冻结语义（mailListScrollAnchor.ts:26-46）：首载/切夹/刷新重算并冻结，
 * loadMore 增长沿用旧值防跳底——Compose LazyColumn 无 initialScrollIndex，屏侧以
 * 「锚定请求号 anchorRequest」驱动 scrollToItem(index = itemCount-1) 等义承载。
 */
data class MailChannelUiState(
    val account: MailAccount? = null,
    val folders: List<MailFolder> = emptyList(),
    val activeFolder: String = MAIL_DEFAULT_FOLDER,
    val messages: List<MailMessage> = emptyList(),
    val drafts: List<MailDraft> = emptyList(),
    val page: Int = 1,
    val total: Int = 0,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val syncing: Boolean = false,
    val loadError: Boolean = false,
    val searchVisible: Boolean = false,
    val searchQuery: String = "",
    val unreadOnly: Boolean = false,
    /** 会话引导结论：无账户 → 跳绑定页（RN navigation.replace('MailAccountSetup')）。 */
    val setupRequired: Boolean = false,
    /** 新锚定请求号（loadSession/reloadList/refresh 后 +1）；0 = 无请求。 */
    val anchorRequest: Int = 0,
    /** 邮箱预取在途（RN useMailUserEmail.loading——引导链门控）。 */
    val emailLoading: Boolean = true,
)

/**
 * 收件箱 VM（RN MailChannelScreen/index.tsx:112-707 逐段移植，内存列表无 Room——RN 同构）：
 * - 会话引导：bootstrapMailSession（401→exchange→重试 / 403 静默空 / setup-required → []）
 *   → 无账户置 setupRequired；有账户列文件夹 + 草稿 + 首页
 * - 列表**升序**（旧→新，坑 9）：sortMessagesAscending + mergeMessages 按 id 去重合并
 * - 分页：onStartReached 语义 loadMore（page+1 append）；hasMore = messages.size < total
 * - 下拉刷新：非草稿夹先 triggerMailSync 再 reloadList（syncing 横幅）；草稿夹只重拉草稿
 * - 搜索：非空 query 走 searchMailMessages（该口不透传 unreadOnly——坑）；空 query 走列表
 * - 竞态守卫：epoch 计数（RN requestIdRef 同义）+ 布尔在飞门控（RN 同构）
 */
class MailChannelViewModel(
    private val client: MailClient,
    private val user: MailExchangeUser,
    private val authProvider: () -> cn.appia.im.core.mail.MailClientAuth?,
    /** 邮箱预取（RN useMailUserEmail：stored → fetchMemberProfile 兜底 → null）。 */
    private val loadUserEmail: suspend () -> String?,
    private val scope: CoroutineScope,
) : ViewModel() {

    private val _state = MutableStateFlow(MailChannelUiState())
    val state: StateFlow<MailChannelUiState> = _state.asStateFlow()

    /** 生命周期收口（GlobalSearchViewModel 同裁定）：子 SupervisorJob，onCleared 取消在飞。 */
    private val vmScope = CoroutineScope(scope.coroutineContext + SupervisorJob())

    /** RN sessionLoadedRef/sessionLoadingRef：会话只引导一次（成功后不再重跑）。 */
    private var sessionLoaded = false
    private var sessionLoading = false
    /** RN initialAnchorDoneRef：loadMore 在首锚完成前不触发（防锚定中途翻页）。 */
    private var initialAnchorDone = false
    private var loadEpoch = 0

    init {
        vmScope.launch { loadSession() }
    }

    internal fun dispose() {
        vmScope.cancel()
    }

    override fun onCleared() = dispose()

    /** RN loadSession（:325-373）：引导 → 文件夹 → 草稿 → 首页；失败置 loadError。 */
    private suspend fun loadSession() {
        if (sessionLoaded || sessionLoading) return
        val email = loadUserEmail()
        if (email == null) {
            // 邮箱预取失败：RN useMailUserEmail.error → mailClient null → 屏面停在 loading；
            // 此处置 loadError 让用户可感知（AA 无 navigation 级重试，下拉刷新即重试入口）
            _state.update { it.copy(loading = false, loadError = true, emailLoading = false) }
            return
        }
        sessionLoading = true
        _state.update { it.copy(loading = true, loadError = false, emailLoading = false) }
        try {
            val accounts = withContext(Dispatchers.IO) {
                bootstrapMailSession(client, user.copy(email = email), authProvider)
            }
            if (accounts.isEmpty()) {
                _state.update { it.copy(loading = false, setupRequired = true) }
                return
            }
            val primary = accounts[0]
            val folders = withContext(Dispatchers.IO) { listMailFolders(client, primary.id) }
            val drafts = withContext(Dispatchers.IO) { fetchDrafts(primary.id) }
            _state.update {
                it.copy(
                    account = primary,
                    folders = folders,
                    drafts = drafts,
                    loading = false,
                )
            }
            reloadList(primary.id, _state.value.activeFolder, scrollToLatest = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _state.update { it.copy(loading = false, loadError = true) }
        } finally {
            sessionLoading = false
        }
    }

    /** RN fetchDrafts（:283-297）：全量 /drafts 按 accountId 过滤 + updatedAt 升序。 */
    private suspend fun fetchDrafts(accountId: String): List<MailDraft> =
        withContext(Dispatchers.IO) {
            listMailDrafts(client)
                .filter { it.accountId == accountId }
                .sortedBy { mailIsoMillis(it.updatedAt) }
        }

    /**
     * RN fetchMessages（:299-323）：query 非空走 search 口，否则列表口；
     * 结果升序；append=mergeMessages 去重合并，否则整替 + 冻结锚定索引。
     */
    private suspend fun fetchMessages(accountId: String, folder: String, pageNum: Int, append: Boolean) {
        val s = _state.value
        // 两口响应形制相同（messages/total）；取字段投影避免跨类型公共超类解析
        val (resultMessages, resultTotal) = withContext(Dispatchers.IO) {
            val trimmedQuery = s.searchQuery.trim()
            if (trimmedQuery.isNotEmpty()) {
                val r = searchMailMessages(
                    client, accountId,
                    cn.appia.im.core.mail.SearchMessagesParams(
                        q = trimmedQuery,
                        folder = folder,
                        page = pageNum,
                        pageSize = MAIL_PAGE_SIZE,
                    ),
                )
                r.messages to r.total
            } else {
                val r = listMailMessages(
                    client, accountId,
                    cn.appia.im.core.mail.ListMessagesParams(
                        folder = folder,
                        page = pageNum,
                        pageSize = MAIL_PAGE_SIZE,
                        unreadOnly = s.unreadOnly.takeIf { it },
                    ),
                )
                r.messages to r.total
            }
        }
        val sorted: List<MailMessage> = resultMessages.sortedBy { mailIsoMillis(it.receivedAt) }
        _state.update {
            it.copy(
                total = resultTotal,
                page = pageNum,
                messages = if (append) mergeMessages(it.messages, sorted) else sorted,
            )
        }
    }

    /** RN reloadList（:325-333）：草稿夹重拉草稿；消息夹拉首页；scrollToLatest 重置锚定。 */
    suspend fun reloadList(accountId: String, folder: String, scrollToLatest: Boolean) {
        _state.update { it.copy(loadError = false) }
        if (isDraftsFolderPath(folder)) {
            val drafts = fetchDrafts(accountId)
            _state.update { it.copy(drafts = drafts, total = drafts.size, page = 1) }
        } else {
            fetchMessages(accountId, folder, 1, append = false)
        }
        if (scrollToLatest) {
            initialAnchorDone = false
            _state.update { it.copy(anchorRequest = it.anchorRequest + 1) }
        }
    }

    /** RN onRefresh（:396-418）：草稿夹只重拉；消息夹 sync + reload；失败置 loadError。 */
    fun onRefresh() {
        val s = _state.value
        val accountId = s.account?.id ?: return
        vmScope.launch {
            val epoch = ++loadEpoch
            _state.update { it.copy(refreshing = true) }
            try {
                    if (isDraftsFolderPath(s.activeFolder)) {
                        reloadList(accountId, s.activeFolder, scrollToLatest = true)
                    } else {
                        _state.update { it.copy(syncing = true) }
                        withContext(Dispatchers.IO) { triggerMailSync(client, accountId, s.activeFolder) }
                        reloadList(accountId, s.activeFolder, scrollToLatest = true)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(loadError = true) }
            } finally {
                if (epoch == loadEpoch) {
                    _state.update { it.copy(refreshing = false, syncing = false) }
                }
            }
        }
    }

    /**
     * RN onLoadMore（:420-451）：向上翻旧页（page+1 append）。
     * 门控：首锚完成前/在飞 loading/refreshing/无更多/草稿夹 → 不动（RN 同条件组）。
     */
    fun onLoadMore() {
        val s = _state.value
        val accountId = s.account?.id ?: return
        if (!initialAnchorDone || s.loadingMore || s.loading || s.refreshing || isDraftsFolderPath(s.activeFolder)) return
        if (s.messages.size >= s.total) return
        vmScope.launch {
            val epoch = ++loadEpoch
            _state.update { it.copy(loadingMore = true) }
            try {
                    fetchMessages(accountId, _state.value.activeFolder, _state.value.page + 1, append = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(loadError = true) }
            } finally {
                if (epoch == loadEpoch) {
                    _state.update { it.copy(loadingMore = false) }
                }
            }
        }
    }

    /** 首锚完成回调（屏侧 scrollToItem 后调用——RN markInitialAnchorDone 等义）。 */
    fun onInitialAnchored() {
        initialAnchorDone = true
    }

    /** RN onSelectFolder（:453-469）：切夹重载 + 草稿夹清搜索。 */
    fun onSelectFolder(folderPath: String) {
        val accountId = _state.value.account?.id ?: return
        _state.update {
            it.copy(
                activeFolder = folderPath,
                searchVisible = if (isDraftsFolderPath(folderPath)) false else it.searchVisible,
                searchQuery = if (isDraftsFolderPath(folderPath)) "" else it.searchQuery,
            )
        }
        vmScope.launch {
            try {
                reloadList(accountId, folderPath, scrollToLatest = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(loadError = true) }
            }
        }
    }

    /** RN toggleSearch（:471-478）：草稿夹不可开；关闭时清词。 */
    fun toggleSearch() {
        _state.update { s ->
            if (isDraftsFolderPath(s.activeFolder)) return@update s
            if (s.searchVisible) s.copy(searchVisible = false, searchQuery = "") else s.copy(searchVisible = true)
        }
    }

    /**
     * 搜索词/未读开关变更：RN onChangeText 直存 state，**不自动重查**——
     * 重查入口是下拉刷新/切夹（RN 同构：fetchMessages 闭包读最新 state）。
     */
    fun onSearchQueryChanged(text: String) {
        _state.update { it.copy(searchQuery = text) }
    }

    fun onUnreadOnlyChanged(value: Boolean) {
        _state.update { it.copy(unreadOnly = value) }
    }

    /**
     * RN handleDraftDeleted（:487-494）：删后重拉草稿；删的是最后一封且当前在草稿夹 → 回 INBOX。
     */
    fun onDraftDeleted() {
        val s = _state.value
        val accountId = s.account?.id ?: return
        val wasLastDraft = s.drafts.size <= 1
        vmScope.launch {
            try {
                // RN handleDraftDeleted：先重拉草稿（total/page 一并归位），再按情况切夹
                val drafts = fetchDrafts(accountId)
                _state.update { it.copy(drafts = drafts, total = drafts.size, page = 1) }
                if (wasLastDraft && isDraftsFolderPath(s.activeFolder)) {
                    _state.update { it.copy(activeFolder = MAIL_DEFAULT_FOLDER, searchVisible = false, searchQuery = "") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(loadError = true) }
            }
        }
    }

    /** 删除草稿（RN useMailCardActions.deleteDraft：DELETE /drafts/:id → onDraftDeleted）。 */
    fun deleteDraft(draftId: String) {
        vmScope.launch {
            try {
                withContext(Dispatchers.IO) { deleteMailDraft(client, draftId) }
                onDraftDeleted()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(loadError = true) }
            }
        }
    }

    companion object {
        /** MailChannelScreen:100-110 sortMessagesAscending/mergeMessages。 */
        internal fun mergeMessages(existing: List<MailMessage>, incoming: List<MailMessage>): List<MailMessage> {
            val map = LinkedHashMap<String, MailMessage>()
            (existing + incoming).forEach { map[it.id] = it }
            return map.values.sortedBy { mailIsoMillis(it.receivedAt) }
        }

        /**
         * receivedAt/updatedAt ISO → millis（解析失败 0 → 排序稳定置顶；
         * mailPreviewReceivedAtMillis 同口径五形态，此处泛用列表排序）。
         */
        internal fun mailIsoMillis(raw: String): Long {
            for (pattern in listOf(
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSS",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd",
            )) {
                try {
                    return SimpleDateFormat(pattern, Locale.ROOT)
                        .apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
                        .parse(raw)!!.time
                } catch (_: ParseException) {
                    // 换下一形态
                }
            }
            return 0L
        }
    }
}
