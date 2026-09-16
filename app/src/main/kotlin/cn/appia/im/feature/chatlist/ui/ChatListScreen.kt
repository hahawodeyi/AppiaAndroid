package cn.appia.im.feature.chatlist.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.RouteDeps
import cn.appia.im.core.i18n.t
import cn.appia.im.core.realtime.RealtimeTransportPhase
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.domain.session.RoomsSyncRepository
import cn.appia.im.domain.session.SessionBootstrapOrchestrator
import cn.appia.im.feature.chatlist.ChatListViewModel
import cn.appia.im.feature.chatlist.ChatRow
import cn.appia.im.feature.chatlist.roomTitleFromChat
import cn.appia.im.feature.org.ui.OrgSwitchSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 会话列表主屏（RN RoomListScreen 的 M2 装配：占位 MainScreen 退役，登出/我的企业迁入顶栏菜单）：
 * - 顶栏：主体显示名 + search 入口占位（全局搜索 M5，RN 搜索框本就隐藏）+ ⋮ 菜单（我的企业/退出登录）
 * - 连接横幅（T5）：phase/networkOnline 由装配方收集传入（测试可直接给值），手动重连接 manager
 * - 分段列表（T2/T3/T4）：ChatListViewModel sections + ChatRow，点击 → onOpenRoom(rid, title, t)
 * - 下拉刷新：RoomsSyncRepository PULL 全量等价（RN syncRoomListOnPullRefresh；
 *   组织切换中/刷新中跳过，失败 Alert——RN RoomListScreenInner.tsx:200-220 同）
 * - 进屏即 bootstrap（RN MainNavigator:44-51）；组织切换成功重读会话 → VM 按新 server 重建
 *   （RN App.tsx:49-52 dbKey 变化丢单例的列表侧等价）。
 */
@Composable
fun ChatListScreen(
    gateway: SessionBootstrapOrchestrator,
    deps: RouteDeps,
    phase: RealtimeTransportPhase,
    networkOnline: Boolean?,
    onOpenRoom: (rid: String, title: String, roomType: String) -> Unit,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalAppiaColors.current
    // 全屏级作用域：切组织/下拉刷新的挂起点——弹层关闭/重组不得取消进行中的协程（同旧 MainScreen 裁定）
    val scope = rememberCoroutineScope()

    // RN MainNavigator:44-51 进 Main 即 bootstrap（恢复会话/登录成功共用此入口）
    LaunchedEffect(Unit) { gateway.bootstrapOnMainEntered() }

    var session by remember { mutableStateOf(gateway.restorableSession()) }
    val serverUrl = session?.serverUrl.orEmpty()
    val currentUserId = session?.user?.id?.takeIf { it.isNotEmpty() }
    // 换服重建实例（T2 裁定）：切组织成功刷新 session → serverUrl 变化 → VM 重绑新库
    val viewModel = remember(serverUrl) {
        ChatListViewModel(deps.dbManager, serverUrl, deps.store, deps.scope)
    }
    val sections by viewModel.sections.collectAsState()
    val candidates by gateway.orgCandidates.collectAsState()

    var refreshing by remember { mutableStateOf(false) }
    var orgSwitching by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showOrgSheet by remember { mutableStateOf(false) }
    var alert by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun onRefresh() {
        // RN RoomListScreenInner.tsx:201-208：组织切换中/刷新中跳过（UI 级防抖；
        // bootstrap 同步与 pull 的并发互斥在 RoomsSyncRepository 全局锁）
        if (refreshing || orgSwitching) return
        refreshing = true
        scope.launch {
            try {
                // repo 调用时现读会话 server（SessionModule syncInitial 同款；守卫在 repo 内）
                RoomsSyncRepository(
                    deps.sdk,
                    deps.dbManager,
                    deps.kv,
                    deps.store.load()?.serverUrl.orEmpty(),
                ).sync(RoomsSyncRepository.Mode.PULL)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // RN :212-217 失败 Alert（不标记 disconnected——横幅由传输层 phase 驱动，M2 简化）
                alert = context.t("roomList_syncFailedTitle") to context.t("roomList_syncFailedBody")
            } finally {
                refreshing = false
            }
        }
    }

    // 顶栏安全区（RN RoomListScreen SafeAreaView edges=['top'] 等价）：app 边缘到边缘绘制，
    // 不垫 statusBar inset 时顶栏落进系统状态栏触摸区（模拟器走查实测：状态栏窗口吞掉
    // 顶部 136px 点击，⋮ 菜单不可点）。
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .testTag("qa-room-list-screen"),
    ) {
        // 顶栏（RN RoomListProfileHeader 最小版）：显示名 + search 占位 + ⋮ 菜单（无 icons 依赖，文本字形同 RoomHeader）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("qa-room-list-header"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                session?.user?.name?.takeIf { it.isNotBlank() }
                    ?: session?.user?.username?.takeIf { it.isNotBlank() }
                    ?: context.t("roomList_profileFallbackName"),
                Modifier.weight(1f),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // search 入口占位（全局搜索 M5；RN 搜索框已隐藏，留 Maestro 定位点）
            Text(
                "⌕",
                color = colors.headerTintColor,
                fontSize = 24.sp,
                modifier = Modifier
                    .size(40.dp)
                    .wrapContentSize(Alignment.Center)
                    .testTag("qa-room-list-search"),
            )
            Box {
                Text(
                    "⋮",
                    color = colors.headerTintColor,
                    fontSize = 24.sp,
                    modifier = Modifier
                        .size(40.dp)
                        .wrapContentSize(Alignment.Center)
                        .clickable { menuOpen = true }
                        .testTag("qa-room-list-menu"),
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(context.t("drawer_myenterprise")) },
                        onClick = {
                            menuOpen = false
                            showOrgSheet = true
                        },
                        modifier = Modifier.testTag("qa-room-list-menu-my-enterprise"),
                    )
                    DropdownMenuItem(
                        text = { Text(context.t("profile_logout")) },
                        onClick = {
                            menuOpen = false
                            // 组织切换中豁免（总纲 §4.2-2，同旧 MainScreen/SessionExpired 收集器裁定）
                            if (gateway.logout()) onLogout()
                        },
                        modifier = Modifier.testTag("qa-room-list-menu-logout"),
                    )
                }
            }
        }

        // T5 连接横幅：localHasRooms = 本地已有会话（RN chats.length > 0，冷启动横幅宽限判定用）
        ConnectionBanner(
            phase = phase,
            networkOnline = networkOnline,
            localHasRooms = sections.sumOf { it.chats.size } > 0,
            onManualReconnect = gateway::requestManualReconnect,
        )

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = ::onRefresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            LazyColumn(Modifier.fillMaxSize().testTag("qa-room-list")) {
                sections.forEach { section ->
                    item(key = "header-${section.key}", contentType = "header") {
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                context.t(section.key.titleKey),
                                color = colors.auxiliaryText,
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                                    .testTag("qa-room-list-section-${section.key.name.lowercase()}"),
                            )
                        }
                    }
                    items(section.chats, key = { it._id }, contentType = { "chat" }) { chat ->
                        ChatRow(
                            chat = chat,
                            currentUserId = currentUserId,
                            serverUrl = serverUrl,
                            onPress = {
                                onOpenRoom(
                                    chat._id,
                                    roomTitleFromChat(chat, currentUserId, context.t("Agent")),
                                    chat.t,
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    if (showOrgSheet) {
        // 候选两段式（总纲 §4.2-3）：开弹层触发刷新，collect orgCandidates——段1 缓存即刻上屏，
        // 段2 后台 REST 完成自动刷新（旧 MainScreen 原样迁入）
        LaunchedEffect(Unit) { gateway.refreshOrgCandidates() }
        OrgSwitchSheet(
            candidates = candidates.orEmpty(),
            currentServerUrl = serverUrl,
            onSwitch = { candidate ->
                showOrgSheet = false
                orgSwitching = true
                // launch 挂全屏级 scope：跨弹层关闭继续执行到完成/告警（评审 Critical 修复语义保留）
                scope.launch {
                    try {
                        gateway.switchOrg(candidate.appiaUrl)
                        session = gateway.restorableSession() // 刷新主体 + VM 按新 server 重建
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        alert = context.t("roomList_orgSwitchFailedTitle") to
                            (e.message ?: context.t("roomList_orgSwitchFailedBody"))
                    } finally {
                        orgSwitching = false
                    }
                }
            },
            onDismiss = { showOrgSheet = false },
        )
    }

    alert?.let { (title, message) ->
        AlertDialog(
            onDismissRequest = { alert = null },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { alert = null }) { Text(context.t("common_close")) } },
        )
    }
}
