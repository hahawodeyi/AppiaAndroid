package cn.appia.im.feature.labor.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import cn.appia.im.core.i18n.t
import cn.appia.im.core.network.api.WorktableItem
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.feature.chat.ui.RoomHeader
import cn.appia.im.feature.labor.LaborAction
import cn.appia.im.feature.labor.LaborDmResult
import cn.appia.im.feature.labor.LaborRepository
import cn.appia.im.feature.labor.LaborAction.Web
import cn.appia.im.feature.labor.filterWorktableBySearch
import cn.appia.im.feature.labor.openLaborDirectMessage
import cn.appia.im.feature.labor.resolveLaborAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** RN LaborScreen styles 硬编码色（不走主题色板，styles.ts 同值）。 */
private val CardBg = Color(0xFFFFFFFF)
private val ScreenBg = Color(0xFFF2F3F5)
private val IconPlaceholderBg = Color(0xFFF2F3F5)
private val Muted = Color(0xFF86909C)
private val ItemLabel = Color(0xFF4E5969)

/**
 * 工作台（M7-T4 / RN LaborScreen/index.tsx）：宫格分组（组内 4 列换行，RN styles.cell
 * width 25% 等价——group.row 布局语义 RN 亦未消费）+ 搜索过滤 + 下拉刷新 + 三支路由
 * （LaborRouting：type3 DM / 消息待办 → TodoList / 其余 InAppWeb）；
 * 考勤打卡/企业滴滴先询精确定位（拒绝 Alert 不打开，RN ensureAndroidFineLocation 同门槛）。
 * 权限申请由屏侧 launcher 承担（compose 无 PermissionsAndroid 等价同步 API），
 * 已授予则直开——RN check() 短路同语义。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaborScreen(
    repo: LaborRepository,
    serverUrl: String,
    username: String,
    onBack: () -> Unit,
    onOpenRoom: (rid: String, title: String, roomType: String) -> Unit,
    onOpenTodoList: () -> Unit,
    onOpenInAppWeb: (LaborAction.Web) -> Unit,
    /** M4 openDirectMessage 链注入（resolveDirectChatRid：knownRid → 本地 chats → im.create）。 */
    resolveDm: suspend (username: String) -> String?,
) {
    val context = LocalContext.current
    val colors = LocalAppiaColors.current
    val scope = rememberCoroutineScope()
    val isGuest = username.contains("appia.guest")

    val state by repo.state.collectAsState()
    var search by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }
    var alert by remember { mutableStateOf<Pair<String, String>?>(null) }

    // 进屏装载（RN useQuery queryFn：serverUrl 为空不拉，enabled 同语义）
    LaunchedEffect(serverUrl, isGuest) {
        if (serverUrl.isNotEmpty()) repo.fetch(serverUrl, isGuest)
    }

    // 定位权限门槛（RN openLaborItem.ts:76-81 在 openWeb 内 await ensureAndroidFineLocation）：
    // 未授予 → 存 pending + 弹系统授权（fine+coarse 双申请，RN requestMultiple 同）；
    // 结果回调全授予才打开，拒绝 Alert 不打开。
    var pendingLocationWeb by remember { mutableStateOf<LaborAction.Web?>(null) }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val action = pendingLocationWeb
        pendingLocationWeb = null
        if (action != null) {
            if (grants.values.all { it }) onOpenInAppWeb(action)
            else alert = "" to context.t("labor_locationdenied")
        }
    }

    fun openWeb(action: LaborAction.Web) {
        val fineGranted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarseGranted = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!action.needsLocation || (fineGranted && coarseGranted)) {
            onOpenInAppWeb(action) // RN check() 双已授短路
            return
        }
        pendingLocationWeb = action
        locationLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }

    fun onPressItem(item: WorktableItem) {
        when (val action = resolveLaborAction(item, serverUrl)) {
            is LaborAction.DirectMessage -> scope.launch {
                when (val r = openLaborDirectMessage(item) { resolveDm(it) }) {
                    is LaborDmResult.Room -> onOpenRoom(r.rid, item.name, "d") // RN t coerce 回退 'd'
                    LaborDmResult.MissingUsername ->
                        alert = context.t("labor_dmfailed") to context.t("labor_dmmissingusername")
                    LaborDmResult.Failed ->
                        alert = context.t("labor_dmfailed") to context.t("labor_dmfailed")
                }
            }
            is LaborAction.TodoList -> onOpenTodoList()
            is LaborAction.Web -> openWeb(action)
        }
    }

    if (serverUrl.isEmpty()) {
        // RN :66-74 无主体兜底
        Column(
            Modifier.fillMaxSize().background(colors.backgroundColor).testTag("qa-labor-screen"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(context.t("labor_noserver"), color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
        return
    }

    val groups = filterWorktableBySearch(state.groups, search)
    // RN showEmpty :76-81：非加载/非错误 && 过滤后为空 && (原数据空 || 有搜索词)
    val showEmpty = !state.isLoading && !state.isError &&
        groups.isEmpty() && (state.groups.isEmpty() || search.isNotBlank())
    val emptyLabel = if (search.isNotBlank()) "labor_emptyaftersearch" else "labor_emptyconfig"

    Column(Modifier.fillMaxSize().background(colors.backgroundColor).testTag("qa-labor-screen")) {
        RoomHeader(title = context.t("labor_title"), onBack = onBack)
        // 搜索（RN searchWrap/searchInner：灰底圆角 + ⌕ 前缀）
        TextField(
            value = search,
            onValueChange = { search = it },
            placeholder = { Text(context.t("labor_searchplaceholder"), color = Muted, fontSize = 15.sp) },
            leadingIcon = { Text("⌕", color = Muted, fontSize = 16.sp) },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = ScreenBg,
                unfocusedContainerColor = ScreenBg,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("qa-labor-search"),
        )

        when {
            state.isLoading -> Box(
                Modifier.fillMaxWidth().padding(vertical = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(Modifier.testTag("qa-labor-loading"))
            }
            state.isError -> Box(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    context.t("labor_loaderror"),
                    color = Muted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("qa-labor-error"),
                )
            }
            showEmpty -> Box(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(context.t(emptyLabel), color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
            else -> PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    scope.launch {
                        refreshing = true
                        try {
                            repo.fetch(serverUrl, isGuest) // RN refetch：保留旧数据
                        } catch (e: CancellationException) {
                            throw e
                        } finally {
                            refreshing = false
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .background(ScreenBg)
                        .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
                ) {
                    for (group in groups) {
                        // 分组卡片（RN card：白底圆角；组内 4 列换行 = RN width 25% wrap）
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(CardBg),
                        ) {
                            Text(
                                group.name,
                                color = ItemLabel,
                                fontSize = 15.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                            group.items.chunked(4).forEach { rowItems ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                                    for (name in rowItems) {
                                        LaborCell(
                                            item = name,
                                            modifier = Modifier.weight(1f),
                                            onPress = ::onPressItem,
                                        )
                                    }
                                    // 尾行补位保持 4 列等宽（RN width 25% 视觉等价）
                                    repeat(4 - rowItems.size) { Box(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    alert?.let { (title, message) ->
        AlertDialog(
            onDismissRequest = { alert = null },
            title = { if (title.isNotEmpty()) Text(title) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { alert = null }) { Text(context.t("common_close")) } },
        )
    }
}

/** 宫格单元（RN cell/iconWrap/itemLabel）：48dp 圆角图标 + 两行名称。 */
@Composable
private fun LaborCell(item: WorktableItem, modifier: Modifier = Modifier, onPress: (WorktableItem) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clickable { onPress(item) }
            .padding(horizontal = 4.dp, vertical = 10.dp)
            .testTag("qa-labor-item-${item.name}"),
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(IconPlaceholderBg),
            contentAlignment = Alignment.Center,
        ) {
            if (item.icon.isNotEmpty()) {
                AsyncImage(
                    model = item.icon,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
        Text(
            item.name,
            color = ItemLabel,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp).height(32.dp),
        )
    }
}
