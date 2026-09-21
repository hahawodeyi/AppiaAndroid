package cn.appia.im

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import cn.appia.im.core.database.DatabaseManager
import cn.appia.im.core.datastore.AuthSessionStore
import cn.appia.im.core.datastore.KvStore
import cn.appia.im.core.i18n.t
import cn.appia.im.core.messaging.RoomHistoryRepository
import cn.appia.im.core.messaging.getSendOrchestrator
import cn.appia.im.core.network.LoginCredentials
import cn.appia.im.core.network.LoginResult
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.rest.SessionExpiredBus
import cn.appia.im.BuildConfig
import cn.appia.im.core.realtime.NetworkMonitor
import cn.appia.im.core.realtime.RoomStreamManager
import cn.appia.im.core.theme.AppiaTheme
import cn.appia.im.domain.session.BackgroundScope
import cn.appia.im.domain.session.SessionBootstrapOrchestrator
import cn.appia.im.feature.chat.DraftController
import cn.appia.im.feature.chat.DraftRepository
import cn.appia.im.feature.chat.RecallActions
import cn.appia.im.feature.chat.RoomMessagesViewModel
import cn.appia.im.feature.chat.RoomReadMarker
import cn.appia.im.feature.chat.ui.AttachmentNav
import cn.appia.im.feature.chat.ui.AttachmentViewerScreen
import cn.appia.im.feature.chat.ui.DocPreviewParams
import cn.appia.im.feature.chat.ui.DocPreviewScreen
import cn.appia.im.feature.chat.ui.MentionSuggestionScreen
import cn.appia.im.feature.chat.ui.VideoPlayerScreen
import cn.appia.im.feature.chat.ui.ViewerImage
import cn.appia.im.feature.chat.ui.ReactionActions
import cn.appia.im.feature.chat.ui.ReadReceiptScreen
import cn.appia.im.feature.chat.ui.RoomScreen
import cn.appia.im.feature.chat.ui.resolveRoomHeaderTitle
import cn.appia.im.feature.chat.forward.ForwardDetailScreen
import cn.appia.im.feature.chat.forward.ForwardSearcher
import cn.appia.im.feature.chat.forward.ForwardSelectScreen
import cn.appia.im.core.network.api.ForwardApi
import cn.appia.im.core.network.api.ReadReceiptsApi
import cn.appia.im.core.network.api.RoomsApi
import cn.appia.im.core.network.api.SpotlightApi
import cn.appia.im.core.media.UploadApi
import cn.appia.im.feature.chat.MessageEditController
import cn.appia.im.feature.chat.MentionCandidate
import cn.appia.im.feature.chat.OrderedFileIdsResult
import cn.appia.im.feature.chat.agentBotsToCandidates
import cn.appia.im.feature.chat.buildOrderedFileIds
import cn.appia.im.feature.chat.editor.RoomEditorViewModel
import cn.appia.im.feature.chat.filterBotsByClawAgentVisibility
import cn.appia.im.feature.chat.parseAgentBotMentionList
import cn.appia.im.feature.chat.parseAppiaRoomMembersV2
import cn.appia.im.feature.chat.parseClawAgentVisibilityMap
import cn.appia.im.feature.chatlist.ChatRowActions
import cn.appia.im.feature.chatlist.ui.ChatListScreen
import cn.appia.im.feature.login.AuthApi
import cn.appia.im.feature.login.CompanyServer
import cn.appia.im.feature.login.LoginAreaCodeOption
import cn.appia.im.feature.login.VerifyEnterpriseResponse
import cn.appia.im.feature.login.ui.AuthWebScreen
import cn.appia.im.feature.login.ui.EnterpriseCodeScreen
import cn.appia.im.feature.login.ui.LoginScreen
import cn.appia.im.feature.login.ui.LoginState
import cn.appia.im.feature.login.ui.AreaCodeScreen
import cn.appia.im.feature.login.ui.isLoginNetworkTimeoutError
import cn.appia.im.feature.login.ui.rememberLoginState
import cn.appia.im.feature.login.verifyEnterprise
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

/** Auth 图路由（T3 建立导航骨架）：Enterprise → Login → (AreaCode | AuthWeb) → Main。 */
@Serializable
data object EnterpriseCodeRoute

/**
 * RN LoginScreen 路由参数：verify 成功返回的可用服务器列表。
 * nav 内建 NavType 不支持 List<自定义类>，按绑定裁定#4 走 kotlinx JSON 串（type-safe nav 负责转义回环）。
 */
@Serializable
data class LoginRoute(val serversJson: String) {
    fun servers(): List<CompanyServer> =
        runCatching { loginRouteJson.decodeFromString<List<CompanyServer>>(serversJson) }
            .getOrDefault(emptyList())
}

private val loginRouteJson = Json { ignoreUnknownKeys = true }

/** 区号选择页路由参数（RN:189-196 navigate(AreaCodeScreen, {server})）。 */
@Serializable
data class AreaCodeRoute(val server: String)

/** RN AuthWebScreen 路由参数：忘记密码 Web（url 直开）与 CAS SSO（authType='cas' + ssoToken）共用；
 *  `server` 为发起页选中的企业服务器（RN 闭包捕获 selectedUrl 的等价物，CAS 成功回调用）。 */
@Serializable
data class AuthWebRoute(
    val url: String,
    val title: String = "",
    val authType: String? = null,
    val ssoToken: String? = null,
    val server: String = "",
)

@Serializable
data object MainRoute

/** RN RoomScreen 路由参数（rid + 标题兜底 + 房间类型，RoomListScreen T11 串联入口）。 */
@Serializable
data class RoomRoute(val rid: String, val title: String = "", val roomType: String = "c")

/** 图片预览页路由（T7）：ViewerImage 列表 JSON 串（type-safe nav 不支持 List<自定义>，同 LoginRoute 裁定）。 */
@Serializable
data class MediaViewerRoute(val imagesJson: String, val initialIndex: Int = 0)

/** 视频/音频播放页路由（T7）。 */
@Serializable
data class MediaPlayerRoute(val url: String, val title: String = "", val isAudio: Boolean = false)

/** 文档预览页路由（T7，RN DocPreviewPage 参数同名）。 */
@Serializable
data class DocPreviewRoute(
    val title: String = "",
    val fileId: String = "",
    val downloadUrl: String = "",
    val fileType: String = "",
)

/** 转发选择页路由（T9）：被转发的消息 id 列表 + 是否合并；入口（长按菜单多选）T11 接线。 */
@Serializable
data class ForwardSelectRoute(val messageIds: List<String>, val isMerged: Boolean = false)

/** 合并转发详情路由（T9）：msgData 原文 + 卡片标题（RN navigate('ForwardMessage', {messages, originRid, title}) 的等价自包含参数）。 */
@Serializable
data class ForwardDetailRoute(val msgDataJson: String, val title: String = "")

/** 已读回执明细路由（T10，RN navigate('ReadReceipt', {messageId, rid, roomType, userId})）。 */
@Serializable
data class ReadReceiptRoute(
    val messageId: String,
    val rid: String,
    val userId: String,
    val roomType: String = "c",
)

/** @提及选人页路由（T12，RN navigate('MentionSuggestion', {chatId, t, initialQuery})；agent 房 M4 接入）。 */
@Serializable
data class MentionSuggestionRoute(
    val rid: String,
    val roomType: String = "c",
    val initialQuery: String = "",
)

/** 选人结果回投键：选人页写 previousBackStackEntry.savedStateHandle，RoomRoute 观察回插（评审 Critical-1）。 */
const val MENTION_SELECTED_KEY = "mention_selected"

/** LoginState 构造缝：仅导航流 UI 测试注入 fake deps（预设输入/ic 免触网）；生产恒 null 走默认。 */
private typealias LoginStateFactory =
    (servers: List<CompanyServer>, onLoginSuccess: (LoginResult, String) -> Unit) -> LoginState

/**
 * 路由装配依赖束（原 T9 RoomScreenDeps，T11 起同时服务 Main/Room 两路由；MainActivity 注入后
 * 传入 AppiaNavHost，UI 测试传 null 走占位）。db 绑定目标 server（换服由会话层重建，同裁定）。
 * `roomStreams` 与 bootstrap 重连重订/teardown 清理共用同一 Hilt 单例（SessionModule 裁定）。
 */
class RouteDeps(
    val dbManager: DatabaseManager,
    val sdk: RocketSdk,
    val store: AuthSessionStore,
    val scope: CoroutineScope,
    val kv: KvStore,
    val roomStreams: RoomStreamManager,
    val networkMonitor: NetworkMonitor,
)

private const val NAV_TAG = "roomRoute"

/**
 * 导航宿主：默认落 EnterpriseCode（RN AuthStack 首屏）；verify 参数化供 UI 测试注入 fake。
 * `session` 为会话编排（登录持久化/bootstrap/登出/切组织的挂接点）；`startAuthenticated`
 * 供 MainActivity 以同步恢复判定直落 Main（RN RootNavigator.tsx:66-95 首帧即定，无闪屏）。
 */
@Composable
fun AppiaNavHost(
    verify: suspend (String, String) -> VerifyEnterpriseResponse = ::verifyEnterprise,
    session: SessionBootstrapOrchestrator? = null,
    startAuthenticated: Boolean = false,
    loginState: LoginStateFactory? = null,
    deps: RouteDeps? = null,
) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // CAS SSO 登录失败要落回登录页弹窗（RN runLogin 的 Alert；导航级状态跨屏传递）。
    // 一次性：LoginScreen 投递即消费（externalAlert 投递即清 + 本地暂存展示），导航往返不重显示
    var loginAlert by remember { mutableStateOf<Pair<String, String>?>(null) }
    // 区号选择回调：type-safe nav 参数不携带 lambda，暂存导航级状态（RN 路由 params.onSelect 等价物）
    var areaCodeSelect by remember { mutableStateOf<((LoginAreaCodeOption) -> Unit)?>(null) }

    fun goMain() = nav.navigate(MainRoute) { popUpTo(0) { inclusive = true } }
    fun goAuth() = nav.navigate(EnterpriseCodeRoute) { popUpTo(0) { inclusive = true } }

    /** 登录成功统一路径（账密/SMS onLoginSuccess 与 CAS runSsoLogin 汇入）：持久化 → Main。 */
    fun onLoginSuccess(result: LoginResult, server: String) {
        session?.persistLogin(result, server) // RN authStore.login authStore.ts:98-107（store+推送）
        goMain()
    }

    // 会话失效总线（REST 401 / DDP resume 失效）→ 登出 + 回 Auth（RN toast+logout 的 M1 等价：
    // 无 toast 基建直接回落；M5 补 auth_session_expired 提示，key 已备于 rest/ApiError.kt）。
    // 注册先于 NavHost 子级 effect：Main 内 bootstrap 触发的失效事件不丢（总线无 replay）。
    // session == null（纯 Auth 栈 UI 测试路径）整体跳过——与 onLoginSuccess 的 persistLogin 同一保护。
    // logout 仅在真正执行（非组织切换豁免，评审 Important-2）时才导航：切换中总线事件不甩回企业码页。
    LaunchedEffect(session) {
        SessionExpiredBus.events.collect {
            val gateway = session
            if (gateway != null && gateway.logout()) {
                // 与 ChatListScreen 手动登出双触发是有意的幂等操作（logout/teardown 均幂等）
                goAuth()
            }
        }
    }

    /** CAS 命中 → 与账密/SMS 同走登录成功路径（RN handleCasSsoLogin → runLogin）。 */
    fun runSsoLogin(server: String, ssoToken: String) {
        scope.launch {
            try {
                val result = AuthApi.login(server, LoginCredentials.Cas(ssoToken), AuthApi.LOGIN_TIMEOUT_MS)
                onLoginSuccess(result, server) // popUpTo(0) 清空 Auth 栈（含 AuthWeb），等价 RN goBack + 切 Main
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // AuthWebScreen 的 Success 分支已自行 onBack()，此处不再 pop（避免与导航时序竞态误弹栈）；
                // 回到登录页弹告警（RN runLogin catch → Alert on LoginScreen）
                loginAlert = context.t("login_alertfailedtitle") to when {
                    isLoginNetworkTimeoutError(e) -> context.t("login_network_timeout")
                    e.message != null -> e.message!!
                    else -> context.t("login_alertfailedunknown")
                }
            }
        }
    }

    NavHost(navController = nav, startDestination = if (startAuthenticated) MainRoute else EnterpriseCodeRoute) {
        composable<EnterpriseCodeRoute> {
            EnterpriseCodeScreen(verify = verify, onVerified = { servers ->
                // RN:75 navigation.replace：Login 顶掉 EnterpriseCode
                nav.navigate(LoginRoute(loginRouteJson.encodeToString(servers))) {
                    popUpTo<EnterpriseCodeRoute> { inclusive = true }
                }
            })
        }
        composable<LoginRoute> { entry ->
            val route = entry.toRoute<LoginRoute>()
            // 测试缝命中时 remember 固定（工厂携带预设输入/ fake deps）；生产走默认 rememberSaveable
            val state = if (loginState != null) {
                remember(route.serversJson) { loginState(route.servers(), ::onLoginSuccess) }
            } else {
                rememberLoginState(route.servers(), ::onLoginSuccess)
            }
            LoginScreen(
                servers = route.servers(),
                onLoginSuccess = ::onLoginSuccess,
                onMissingServers = {
                    nav.navigate(EnterpriseCodeRoute) { popUpTo(0) { inclusive = true } } // RN:147 replace
                },
                onOpenAreaCode = { server, onSelect ->
                    areaCodeSelect = onSelect
                    nav.navigate(AreaCodeRoute(server))
                },
                onOpenAuthWeb = { request ->
                    nav.navigate(
                        AuthWebRoute(
                            url = request.url,
                            title = request.title,
                            authType = request.authType,
                            ssoToken = request.ssoToken,
                            server = request.server,
                        ),
                    )
                },
                externalAlert = loginAlert,
                onConsumeExternalAlert = { loginAlert = null },
                state = state,
            )
        }
        composable<AreaCodeRoute> { entry ->
            AreaCodeScreen(
                server = entry.toRoute<AreaCodeRoute>().server,
                onSelect = { option ->
                    areaCodeSelect?.invoke(option) // RN onSelect(areaCode) 后 goBack
                    nav.popBackStack()
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable<AuthWebRoute> { entry ->
            val route = entry.toRoute<AuthWebRoute>()
            AuthWebScreen(
                url = route.url,
                title = route.title,
                authType = route.authType,
                ssoToken = route.ssoToken,
                // T6 接线点：CAS 回调判定命中 → AuthApi.login → 同一成功路径 → Main（失败落回登录页告警）
                onSsoLogin = { runSsoLogin(route.server, it.credentialToken) },
                onBack = { nav.popBackStack() },
            )
        }
        composable<MainRoute> {
            val gateway = session
            if (gateway == null || deps == null) {
                // 无会话层注入（纯 Auth 栈 UI 测试）；不放企业 servers 明文
                Text(LocalContext.current.t("feature_not_implemented"))
            } else {
                // 横幅数据源收集在装配处（ChatListScreen 收原值，测试可直接给参）
                val phase by gateway.phase.collectAsState()
                val online by deps.networkMonitor.online.collectAsState()
                // NetworkMonitor 生命周期（RN startNetworkMonitoring 挂载语义）：进列表注册、离屏注销
                DisposableEffect(Unit) {
                    deps.networkMonitor.start()
                    onDispose { deps.networkMonitor.stop() }
                }
                ChatListScreen(
                    gateway = gateway,
                    deps = deps,
                    phase = phase,
                    networkOnline = online,
                    onOpenRoom = { rid, title, roomType -> nav.navigate(RoomRoute(rid, title, roomType)) },
                    onLogout = { goAuth() }, // 登出 → 回企业码页（RN logout 后回 Auth 首屏）
                )
            }
        }
        composable<RoomRoute> { entry ->
            val route = entry.toRoute<RoomRoute>()
            if (deps == null) {
                // 无会话层注入（纯 Auth 栈 UI 测试）；RoomScreen 自身由 RoomScreenTest 直测
                Text(LocalContext.current.t("feature_not_implemented"))
            } else {
                // 绑定目标 server 的库（ChatListViewModel 同裁定）；标题 chats 行实时跟随
                val serverUrl = remember { deps.store.load()?.serverUrl.orEmpty() }
                val db = remember(serverUrl) {
                    deps.dbManager.databaseFor(deps.dbManager.normalizeServer(serverUrl))
                }
                // androidx viewModel（挂 Activity ViewModelStore）：旋转重建后保留分页窗口态
                // （T9 遗留修复：remember(db) 的 VM 转屏即丢）；路由出栈随 back stack entry 释放
                val vm: RoomMessagesViewModel = viewModel(
                    key = "room-messages:$serverUrl:${route.rid}",
                    factory = viewModelFactory {
                        initializer {
                            RoomMessagesViewModel(RoomHistoryRepository(deps.sdk, db), db, deps.scope)
                        }
                    },
                )
                LaunchedEffect(route.rid, route.roomType) { vm.openRoom(route.rid, route.roomType) }
                val chatRow by remember(db, route.rid) { db.chatDao().observeByRid(route.rid) }
                    .collectAsState(initial = null)
                val draftController = remember(db) { DraftController(DraftRepository(db), deps.scope) }
                val auth = remember { deps.store.load() }
                // RN getSendOrchestrator 单例（M1 会话态注入；reset 挂 AuthRepository.login/logout，RN App.tsx dbKey 同义）
                val orchestrator = remember {
                    getSendOrchestrator(deps.dbManager, deps.sdk, deps.store, deps.scope)
                }
                // 已读标记（T10）：markRead 复用 ChatRowActions（REST+双表写单点）
                val actions = remember(serverUrl) { ChatRowActions(deps.sdk, deps.dbManager, serverUrl) }
                val readMarker = remember { RoomReadMarker(markRead = actions::markRoomRead, scope = deps.scope) }
                // 表情回应（T8）：乐观翻转 + chat.react + 失败回滚；username 口径（非 userId）
                val reactionActions = remember(db) { ReactionActions(deps.sdk, db) }
                // 撤回（T11）：先快照 original_content 再 POST message.recall / batch.recall
                val recallActions = remember(db) { RecallActions(deps.sdk, db) }
                // 编辑提交（T12）：updateMessage / multiAttachments.replace 双路（评审 Important-4 装配）
                val editController = remember { MessageEditController(deps.sdk) }
                // 编辑器控制器 entry 级宿主（fix round 2 Critical-1）：destination 组合导航选人页
                // 即销毁（任何 remember 都不存活），entry ViewModelStore 存活到 pop——控制器跨
                // 选人页往返保住 mentionRange/挂起 focus/挂起提及/内容快照（RN 保留前屏挂载等价）
                val editorVm: RoomEditorViewModel = viewModel()
                val editorController = editorVm.controller

                // 自定义表情（T13 / RN customEmojisStore.getCustomEmoji）：name+aliases 双键 →
                // ResolvedEmoji.Custom（同步源 = RealtimeSessionManager bootstrap extras 的
                // emoji-custom.list upsert）；RoomScreen → MessageRow → InlineEnv 注入
                val customEmojis by remember(db) { db.customEmojiDao().observe() }
                    .collectAsState(initial = emptyList())
                val emojiResolver = remember(customEmojis) {
                    val byName = buildMap(customEmojis.size * 2) {
                        customEmojis.forEach { e ->
                            put(e.name, cn.appia.im.core.messaging.ResolvedEmoji.Custom(e.name, e.extension))
                            e.aliases?.let { raw ->
                                runCatching { Json.decodeFromString<List<String>>(raw) }.getOrNull()
                                    ?.forEach { alias -> put(alias, cn.appia.im.core.messaging.ResolvedEmoji.Custom(e.name, e.extension)) }
                            }
                        }
                    }
                    val f: (String) -> cn.appia.im.core.messaging.ResolvedEmoji? = { byName[it] }
                    f
                }

                // T9/T10 锚点（进房接线）：进房即读 + 订阅房间流；新消息落库信号 → 已读防抖
                //（仅当前房间：RoomReadMarker.activeRid 守卫）。DisposableEffect 声明在 RoomScreen
                //（子级）之前：Compose onDispose 逆声明序执行 → 卸载先 flush 草稿（子级）再退订流/清
                // 防抖（本处），与 RN 卸载序一致（useDraft → useRoomSessionStreams → useRoomReadMessages）。
                DisposableEffect(route.rid) {
                    readMarker.onEnter(route.rid)
                    val subscribeJob = deps.scope.launch {
                        runCatching { deps.roomStreams.subscribeRoom(route.rid) }
                            .onFailure { Log.w(NAV_TAG, "subscribe room stream failed rid=${route.rid}", it) }
                    }
                    onDispose {
                        deps.scope.launch {
                            subscribeJob.join() // 订阅在途先等完成（RN cancelled 标志同义），退订不留半挂订阅
                            runCatching { deps.roomStreams.unsubscribeRoom(route.rid) }
                        }
                        readMarker.onLeave() // 先发退订再清防抖（RN cleanup 声明序）
                    }
                }
                LaunchedEffect(deps.roomStreams, readMarker) {
                    deps.roomStreams.incomingMessages.collect { rid -> readMarker.onMessagePersisted(rid) }
                }

                RoomScreen(
                    rid = route.rid,
                    title = resolveRoomHeaderTitle(route.title, chatRow),
                    state = vm.state.collectAsState().value,
                    currentUserId = auth?.user?.id,
                    currentUsername = auth?.user?.username,
                    serverUrl = serverUrl,
                    token = auth?.token,
                    draftController = draftController,
                    onSend = { msg, md -> orchestrator.enqueueTextMessage(route.rid, msg, md) },
                    onSendFiles = { files, msg, md -> orchestrator.enqueueFileMessage(route.rid, files, msg, md) },
                    // 文件行（attachments 非空）走 file 作业重发；md 从行列解析（RN resend snapshot 同参）
                    onResend = { m ->
                        orchestrator.resend(
                            id = m._id,
                            rid = m.rid,
                            msg = m.msg.orEmpty(),
                            attachments = m.attachments,
                            md = m.md?.let { raw ->
                                runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) }.getOrNull()
                            },
                        )
                    },
                    onBack = { nav.popBackStack() },
                    onLoadEarlier = { vm.loadEarlier() },
                    onToggleReaction = { m, emoji ->
                        try {
                            reactionActions.toggle(m._id, emoji, auth?.user?.username)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(NAV_TAG, "toggle reaction failed id=${m._id}", e)
                        }
                    },
                    // 合并转发卡片（T9）：点击进 ForwardDetail
                    onOpenForwardMerge = { msgData, title ->
                        nav.navigate(ForwardDetailRoute(msgDataJson = msgData, title = title))
                    },
                    // 已读回执（T10）：自己的消息 unread 可点图标 → ReadReceiptScreen
                    onOpenReadReceipt = { m ->
                        nav.navigate(
                            ReadReceiptRoute(
                                messageId = m._id,
                                rid = route.rid,
                                userId = auth?.user?.id.orEmpty(),
                                roomType = route.roomType,
                            ),
                        )
                    },
                    // 未读横幅数据源（T10）：GET room.firsUnread（拼写保留）
                    loadFirstUnread = { rid -> ReadReceiptsApi.getFirstUnread(deps.sdk, rid) },
                    // 只读房（T11 / RN isRoomReadOnly = archived||ro）：拦长按菜单
                    isRoomReadOnly = chatRow?.archived == true || chatRow?.ro == true,
                    // 撤回（T11 / RN onRecall doRecall：先快照 original_content 再 POST message.recall）
                    onRecall = { m -> recallActions.recall(m) },
                    // 批量撤回（T11 多选条）：POST message.batch.recall {ids}（不快照，RN 同）
                    onBatchRecall = { ids -> recallActions.batchRecall(ids) },
                    // 编辑提交（T12 / RN handleSendFiles editing 分支装配）：绕 Orchestrator——
                    // null 直 updateMessage；非空逐文件多附件上传 + multiAttachments.replace 整包覆盖
                    onEditSubmit = { m, msg, md, items ->
                        if (items == null) {
                            editController.submit(route.rid, m._id, msg, md, fileIds = null)
                        } else {
                            val ordered = buildOrderedFileIds(items) { file ->
                                UploadApi.uploadFileForOrchestrator(
                                    deps.sdk, route.rid, file, isMultiAttachment = true,
                                ).fileId
                            }
                            val fileIds = when (ordered) {
                                is OrderedFileIdsResult.Ok -> ordered.fileIds
                                is OrderedFileIdsResult.Failed ->
                                    throw IllegalStateException("edit attachment not ready: ${ordered.failedItemId}")
                            }
                            editController.submit(route.rid, m._id, msg, md, fileIds = fileIds)
                        }
                    },
                    // @提及选人页（T12 / RN MentionSuggestion）：成员 v2 / agent bot 门控装配
                    onOpenMentionSuggestion = { initialQuery ->
                        nav.navigate(
                            MentionSuggestionRoute(
                                rid = route.rid,
                                roomType = route.roomType,
                                initialQuery = initialQuery,
                            ),
                        )
                    },
                    // 选人结果（T12 评审 Critical-1）：选人页写 previousBackStackEntry（=本 RoomRoute
                    // entry）的 savedStateHandle，本处观察 StateFlow 回插——Navigation Compose 跨屏
                    // 结果惯例（共享 Flow 在选人页打开期间本屏 collector 已取消会丢事件）。
                    // 消费后写回空表（fix round 2 Important-2）：getStateFlow 粘性，不清则再次进
                    // 选人页取消返回/离房回房时旧值重放 → 二次 insertMention。用 set 空表而非
                    // remove——remove 会把 flows map 项一并清掉，下次 getStateFlow 新建实例，
                    // 旧 collector 换绑前收不到后续写入（SavedStateHandleImpl.remove:110-115）
                    mentionSelections = entry.savedStateHandle.getStateFlow(MENTION_SELECTED_KEY, emptyList<MentionCandidate>()),
                    onMentionSelectionConsumed = {
                        entry.savedStateHandle.set(MENTION_SELECTED_KEY, emptyList<MentionCandidate>())
                    },
                    editorController = editorController,
                    // 转发（T11 / RN ForwardSelect）：单条（菜单）与多选（多选条）共用路由
                    onForward = { ids, merged ->
                        nav.navigate(ForwardSelectRoute(messageIds = ids, isMerged = merged))
                    },
                    // T13：自定义表情（name+aliases 双键查表）+ 本地附件失败重试（retryFile）
                    getCustomEmoji = emojiResolver,
                    onRetryAttachment = { messageId, attachmentId ->
                        orchestrator.retryFile(messageId, attachmentId)
                    },
                    // 附件查看路由（T7）：图片网格/视频/音频/文档点击 → 预览/播放/文档页
                    onAttachmentNav = { target ->
                        when (target) {
                            is AttachmentNav.Images -> nav.navigate(
                                MediaViewerRoute(
                                    imagesJson = loginRouteJson.encodeToString(target.images),
                                    initialIndex = target.initialIndex,
                                ),
                            )
                            is AttachmentNav.Media -> nav.navigate(
                                MediaPlayerRoute(url = target.url, title = target.title.orEmpty(), isAudio = target.isAudio),
                            )
                            is AttachmentNav.Doc -> nav.navigate(
                                DocPreviewRoute(
                                    title = target.params.title,
                                    fileId = target.params.fileId,
                                    downloadUrl = target.params.downloadUrl,
                                    fileType = target.params.fileType,
                                ),
                            )
                        }
                    },
                )
            }
        }
        composable<ForwardSelectRoute> { entry ->
            val route = entry.toRoute<ForwardSelectRoute>()
            if (deps == null) {
                Text(LocalContext.current.t("feature_not_implemented"))
            } else {
                val serverUrl = remember { deps.store.load()?.serverUrl.orEmpty() }
                val db = remember(serverUrl) {
                    deps.dbManager.databaseFor(deps.dbManager.normalizeServer(serverUrl))
                }
                val auth = remember { deps.store.load() }
                val chats by remember(serverUrl) { db.chatDao().observeList() }
                    .collectAsState(initial = emptyList())
                // spotlightv2 聚合搜索（REST 包 DDP call）+ 300ms debounce
                val searcher = remember(serverUrl) {
                    ForwardSearcher({ q -> SpotlightApi.fetchForwardSelectSearch(deps.sdk, q) }, deps.scope)
                }
                ForwardSelectScreen(
                    messageIds = route.messageIds,
                    isMerged = route.isMerged,
                    chats = chats,
                    currentUserId = auth?.user?.id,
                    searcher = searcher,
                    onForward = { users, rooms ->
                        ForwardApi.forwardMessage(
                            deps.sdk,
                            forwardMessageIds = route.messageIds,
                            forwardUsers = users,
                            forwardRooms = rooms,
                            isForwardMerged = route.isMerged,
                        )
                    },
                    onBack = { nav.popBackStack() },
                )
            }
        }
        composable<ForwardDetailRoute> { entry ->
            val route = entry.toRoute<ForwardDetailRoute>()
            if (deps == null) {
                Text(LocalContext.current.t("feature_not_implemented"))
            } else {
                val serverUrl = remember { deps.store.load()?.serverUrl.orEmpty() }
                val auth = remember { deps.store.load() }
                ForwardDetailScreen(
                    msgDataJson = route.msgDataJson,
                    title = route.title,
                    currentUserId = auth?.user?.id,
                    currentUsername = auth?.user?.username,
                    serverUrl = serverUrl,
                    token = auth?.token,
                    // 附件查看路由（T7 同款）：内层消息附件可点击
                    onAttachmentNav = { target ->
                        when (target) {
                            is AttachmentNav.Images -> nav.navigate(
                                MediaViewerRoute(
                                    imagesJson = loginRouteJson.encodeToString(target.images),
                                    initialIndex = target.initialIndex,
                                ),
                            )
                            is AttachmentNav.Media -> nav.navigate(
                                MediaPlayerRoute(url = target.url, title = target.title.orEmpty(), isAudio = target.isAudio),
                            )
                            is AttachmentNav.Doc -> nav.navigate(
                                DocPreviewRoute(
                                    title = target.params.title,
                                    fileId = target.params.fileId,
                                    downloadUrl = target.params.downloadUrl,
                                    fileType = target.params.fileType,
                                ),
                            )
                        }
                    },
                    // 内层再嵌合并转发卡片：同样进详情（RN useOpenForwardMergeMessage 任意层导航同义）
                    onOpenForwardMerge = { msgData, title ->
                        nav.navigate(ForwardDetailRoute(msgDataJson = msgData, title = title))
                    },
                    onBack = { nav.popBackStack() },
                )
            }
        }
        composable<MentionSuggestionRoute> { entry ->
            val route = entry.toRoute<MentionSuggestionRoute>()
            if (deps == null) {
                Text(LocalContext.current.t("feature_not_implemented"))
            } else {
                val serverUrl = remember { deps.store.load()?.serverUrl.orEmpty() }
                val db = remember(serverUrl) {
                    deps.dbManager.databaseFor(deps.dbManager.normalizeServer(serverUrl))
                }
                MentionSuggestionScreen(
                    initialQuery = route.initialQuery,
                    isAgentRoom = false, // agent 房（myAgents）M4 域；门控数据源已备（settings 拉齐后即通）
                    loadCandidates = { isAgentRoom ->
                        if (isAgentRoom) {
                            // Agent_Bot_List × Appia_Claw_Agent_Visibility 门控（settings 表 M5 拉齐前为空集）
                            val bots = parseAgentBotMentionList(
                                db.settingDao().getById("Agent_Bot_List")?.value_as_string,
                            )
                            val visibility = parseClawAgentVisibilityMap(
                                db.settingDao().getById("Appia_Claw_Agent_Visibility")?.value_as_string,
                            )
                            agentBotsToCandidates(filterBotsByClawAgentVisibility(bots, visibility) { it.username })
                        } else {
                            parseAppiaRoomMembersV2(RoomsApi.getAppiaRoomMembersV2(deps.sdk, route.rid))
                                .map { row ->
                                    MentionCandidate(
                                        id = row._id,
                                        username = row.username,
                                        displayName = row.name ?: row.username,
                                    )
                                }
                        }
                    },
                    // 结果写 previousBackStackEntry（=RoomRoute）的 savedStateHandle（Critical-1 修）
                    onSelected = { members ->
                        nav.previousBackStackEntry?.savedStateHandle?.set(MENTION_SELECTED_KEY, members)
                    },
                    onBack = { nav.popBackStack() },
                )
            }
        }
        composable<ReadReceiptRoute> { entry ->
            val route = entry.toRoute<ReadReceiptRoute>()
            if (deps == null) {
                Text(LocalContext.current.t("feature_not_implemented"))
            } else {
                val serverUrl = remember { deps.store.load()?.serverUrl.orEmpty() }
                val auth = remember { deps.store.load() }
                ReadReceiptScreen(
                    messageId = route.messageId,
                    rid = route.rid,
                    userId = route.userId,
                    sdk = deps.sdk,
                    serverUrl = serverUrl,
                    token = auth?.token,
                    onBack = { nav.popBackStack() },
                )
            }
        }
        composable<MediaViewerRoute> { entry ->
            val route = entry.toRoute<MediaViewerRoute>()
            val images = remember(route.imagesJson) {
                runCatching { loginRouteJson.decodeFromString<List<ViewerImage>>(route.imagesJson) }
                    .getOrDefault(emptyList())
            }
            AttachmentViewerScreen(
                images = images,
                initialIndex = route.initialIndex,
                onBack = { nav.popBackStack() },
            )
        }
        composable<MediaPlayerRoute> { entry ->
            val route = entry.toRoute<MediaPlayerRoute>()
            VideoPlayerScreen(
                url = route.url,
                title = route.title.ifEmpty { null },
                isAudio = route.isAudio,
                onBack = { nav.popBackStack() },
            )
        }
        composable<DocPreviewRoute> { entry ->
            val route = entry.toRoute<DocPreviewRoute>()
            if (deps == null) {
                Text(LocalContext.current.t("feature_not_implemented"))
            } else {
                DocPreviewScreen(
                    params = DocPreviewParams(
                        title = route.title,
                        fileId = route.fileId,
                        downloadUrl = route.downloadUrl,
                        fileType = route.fileType,
                    ),
                    sdk = deps.sdk,
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var session: SessionBootstrapOrchestrator

    // 路由装配束：库/sdk/会话态/后台 scope + 房间流管理器 + 网络可达性（Main/Room 两路由共用）
    @Inject
    lateinit var dbManager: DatabaseManager

    @Inject
    lateinit var sdk: RocketSdk

    @Inject
    lateinit var authStore: AuthSessionStore

    @Inject
    lateinit var kv: KvStore

    @Inject
    lateinit var roomStreams: RoomStreamManager

    @Inject
    lateinit var networkMonitor: NetworkMonitor

    @Inject
    @BackgroundScope
    lateinit var backgroundScope: CoroutineScope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 首帧判定在 setContent 前完成（同步读 MMKV 持久化会话）：有会话直落 Main（RN
        // RootNavigator.tsx:66-95 首帧即定 Auth/Main，无闪屏）；Main 内再异步 bootstrap（RN 同构）
        val startAuthenticated = session.hasRestorableSession()
        setContent {
            AppiaTheme(isDark = isSystemInDarkTheme()) {
                // M2 前置收尾（总纲 §4.2-4）：testTag 以 resource-id 暴露给 UiAutomator/Maestro，
                // 仅 debug 包启用（Maestro appId cn.appia.im.debug）；release 语义树不携带该配置。
                val rootModifier = if (BuildConfig.DEBUG) {
                    Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }
                } else {
                    Modifier.fillMaxSize()
                }
                Surface(rootModifier) {
                    AppiaNavHost(
                        session = session,
                        startAuthenticated = startAuthenticated,
                        deps = RouteDeps(
                            dbManager,
                            sdk,
                            authStore,
                            backgroundScope,
                            kv,
                            roomStreams,
                            networkMonitor,
                        ),
                    )
                }
            }
        }
    }
}
