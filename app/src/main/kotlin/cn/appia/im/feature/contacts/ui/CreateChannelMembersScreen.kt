package cn.appia.im.feature.contacts.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.chat.PartnerCheckState
import cn.appia.im.core.chat.PartnerGroup
import cn.appia.im.core.chat.RecentContactRow
import cn.appia.im.core.chat.canConfirmAddToRoom
import cn.appia.im.core.chat.canConfirmCreateChannel
import cn.appia.im.core.chat.filterPartnerGroups
import cn.appia.im.core.chat.mapPartnerGroups
import cn.appia.im.core.chat.mapRecentContactRows
import cn.appia.im.core.chat.partnerGroupCheckState
import cn.appia.im.core.chat.togglePartnerGroupUsers
import cn.appia.im.core.database.DatabaseManager
import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.i18n.t
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.api.ChannelsApi
import cn.appia.im.core.network.api.ClawAgentItem
import cn.appia.im.core.network.api.ClawAgentsApi
import cn.appia.im.core.network.api.ForwardApi
import cn.appia.im.core.network.api.PartnersApi
import cn.appia.im.core.network.api.RoomSettingsApi
import cn.appia.im.core.network.api.SpotlightApi
import cn.appia.im.core.network.api.SpotlightApi.SpotlightUser
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.feature.chat.forward.ForwardDeptCheckState
import cn.appia.im.feature.chat.forward.ForwardOrgRow
import cn.appia.im.feature.chat.forward.buildDeptDescendantUsernames
import cn.appia.im.feature.chat.forward.buildOrgTreeRows
import cn.appia.im.feature.chat.forward.deptCheckboxState
import cn.appia.im.feature.chat.forward.interpolate
import cn.appia.im.feature.contacts.ContactsPhase
import cn.appia.im.feature.contacts.ContactsStore
import cn.appia.im.feature.contacts.TeamDepartment
import cn.appia.im.feature.contacts.TeamUser
import cn.appia.im.feature.contacts.TEAM_ROOT_IDS
import cn.appia.im.feature.contacts.TeamRootType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flowOf
import cn.appia.im.core.chat.CreateChannelMode
import kotlinx.coroutines.launch

private const val TAG = "CreateChannelMembers"

/**
 * RN useRemoteUserSearch（hooks/useRemoteUserSearch.ts）：300ms debounce +
 * requestId 防竞态（flatMapLatest 取消旧流等价）。search(null 用户串) → 空态不发请求。
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class UserSearcher(
    private val search: suspend (String) -> List<SpotlightUser>,
    scope: CoroutineScope,
) {
    data class State(val users: List<SpotlightUser> = emptyList(), val loading: Boolean = false)

    private val query = MutableStateFlow("")

    val state: StateFlow<State> = query
        .debounce(300)
        .flatMapLatest { q ->
            flow {
                if (q.isBlank()) {
                    emit(State(emptyList(), false))
                } else {
                    emit(State(emptyList(), true))
                    val users = runCatching { search(q.trim()) }.getOrElse { emptyList() }
                    emit(State(users, false))
                }
            }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), State())

    fun onQueryChanged(text: String) {
        query.value = text
    }
}

/** RN SelectedItem（kind user|dept）。 */
internal sealed interface SelectedChip {
    val key: String
    val label: String

    data class User(override val key: String, override val label: String, val username: String) : SelectedChip
    data class Dept(override val key: String, override val label: String, val depId: String) : SelectedChip
}

internal enum class MemberSource { RECENT, PMT, L1D, PARTNERS, AGENTS }
internal enum class OrgSource { PMT, L1D }

/** RN toggleDeptSelectAllMembers（:1077-1097）：checked → 全清（me 保留）；否则（unchecked/indeterminate）→ 全补。无上限。 */
internal fun toggleDeptSelectAllMembersUncapped(
    deptId: String,
    selectedUsernames: Set<String>,
    me: String,
    departmentMap: Map<String, TeamDepartment>,
    userMap: Map<String, TeamUser>,
): Set<String> {
    val all = buildDeptDescendantUsernames(deptId, departmentMap, userMap)
    if (all.isEmpty()) return selectedUsernames
    val next = selectedUsernames.toMutableSet()
    if (all.all { it in selectedUsernames }) {
        for (u in all) if (u != me) next.remove(u)
    } else {
        next.addAll(all)
    }
    return next
}

/**
 * 选人器（RN screens/CreateChannelMembersScreen/index.tsx ~1800 行核心语义；
 * UI 允许简化、数据/判定/wire 全保真）：
 * - intent create（me 预选）/addToRoom（既有成员预检、不出底栏、确认走 addUsersToRoom）/
 *   forward（create + 建成后 forwardMessage 进新频道，转发失败仅 Alert 不阻断跳转）
 * - members 五子源（recent/PMT/L1D/partners【Appia_Show_External_Partners 门】/agents
 *   【仅选择——管理动作 M5+】）；org 模式 depIds 部门树多选
 * - 搜索：spotlightv2 users-only（300ms debounce）；agents 子源本地过滤（RN :124 remoteSearchEnabled）
 * - **tab 常驻保滚动**：六列表常驻组合树、仅切可见（RN pagesHost/pageHidden :1336+ 同构），
 *   LazyListState 全 rememberSaveable——恢复即原位
 */
@Composable
fun CreateChannelMembersScreen(
    intent: String, // 'create' | 'addToRoom' | 'forward'
    rid: String?,
    existingMemberUsernames: List<String>,
    preselectedUsernames: List<String>,
    forwardMessageIds: List<String>,
    forwardIsMerged: Boolean,
    sdk: RocketSdk?,
    dbManager: DatabaseManager?,
    serverUrl: String,
    currentUserId: String?,
    currentUsername: String?,
    onBack: () -> Unit,
    onCreated: (rid: String, title: String?) -> Unit = { _, _ -> },
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = currentUsername.orEmpty()
    val existingSet = remember(existingMemberUsernames) {
        existingMemberUsernames.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
    val isAddToRoom = intent == "addToRoom"

    // 服务端设置门（RN useServerSetting('Appia_Show_External_Partners')；settings.public 未同步 → 关）
    var showExternalPartners by remember { mutableStateOf(false) }
    LaunchedEffect(dbManager, serverUrl) {
        val m = dbManager ?: return@LaunchedEffect
        val db = m.databaseFor(m.normalizeServer(serverUrl))
        showExternalPartners = db.settingDao().getById("Appia_Show_External_Partners")?.value_as_boolean == true
    }

    // recent（RN useRecentContacts：本地 chats t='d'，非 bot/未归档/open）
    val recentRows: List<ChatEntity> by (dbManager?.let { m ->
        m.databaseFor(m.normalizeServer(serverUrl)).chatDao().observeDirects()
    } ?: flowOf(emptyList())).collectAsState(initial = emptyList())
    val recentContacts = remember(recentRows, me) { mapRecentContactRows(recentRows, me) }

    // 组织树（RN useContacts：UNLOAD 自动拉）
    LaunchedEffect(sdk) {
        if (sdk != null) runCatching { ContactsStore.refreshIfUnloaded(sdk) }
    }
    val contactsPhase by ContactsStore.phase.collectAsState()
    val contactsPayload by ContactsStore.payload.collectAsState()
    val departmentMap = contactsPayload.departmentMap
    val userMap = contactsPayload.userMap
    val rootTree = contactsPayload.rootTree

    // partners/agents 懒拉（RN usePartners/useClawAgents enabled = tab 命中才拉）
    var partnersLoading by remember { mutableStateOf(false) }
    var partnerGroups by remember { mutableStateOf<List<PartnerGroup>>(emptyList()) }
    var partnersLoaded by remember { mutableStateOf(false) }
    var agentsLoading by remember { mutableStateOf(false) }
    var agents by remember { mutableStateOf<List<ClawAgentItem>>(emptyList()) }
    var agentsError by remember { mutableStateOf(false) }
    var agentsLoaded by remember { mutableStateOf(false) }

    // UI 状态（rememberSaveable：旋屏/进程恢复）
    var mode by rememberSaveable { mutableStateOf(CreateChannelMode.MEMBERS) }
    var memberSource by rememberSaveable { mutableStateOf(MemberSource.RECENT) }
    var orgSource by rememberSaveable { mutableStateOf(OrgSource.PMT) }
    var query by rememberSaveable { mutableStateOf("") }
    var allAutoJoin by rememberSaveable { mutableStateOf(false) }
    var isShareRecord by rememberSaveable { mutableStateOf(true) }
    var submitting by remember { mutableStateOf(false) }
    var alertMsg by remember { mutableStateOf<String?>(null) }

    // 搜索器（RN :124：members+agents 子源 → 本地过滤；其余 remote users-only）
    val useAgentLocalSearch = mode == CreateChannelMode.MEMBERS && memberSource == MemberSource.AGENTS
    val searcher = remember(sdk) {
        UserSearcher({ q -> if (sdk == null) emptyList() else SpotlightApi.fetchSpotlightV2Users(sdk, q) }, scope)
    }
    LaunchedEffect(useAgentLocalSearch) {
        // 切 agents 子源时清远端搜索（RN :124 query 传 '' 同义）
        if (useAgentLocalSearch) searcher.onQueryChanged("")
    }
    val searchState by searcher.state.collectAsState()
    // RN :129-137：remote users 按 username 累积（label 解引用跨查询仍可命中）
    val remoteUsersByUsername = remember { mutableStateOf<Map<String, SpotlightUser>>(emptyMap()) }
    LaunchedEffect(searchState.users) {
        if (searchState.users.isNotEmpty()) {
            remoteUsersByUsername.value = remoteUsersByUsername.value + searchState.users.associateBy { it.username }
        }
    }

    // 六 tab 独立滚动位置（RN scrollOffsetsRef 常驻等价）
    val recentListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val pmtListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val l1dListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val partnersListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val agentsListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val orgListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    // 展开态（members pmt/l1d 与 org 三套独立——RN :563-565）
    var expandedPmt by rememberSaveable { mutableStateOf(setOf<String>()) }
    var expandedL1d by rememberSaveable { mutableStateOf(setOf<String>()) }
    var expandedOrg by rememberSaveable { mutableStateOf(setOf<String>()) }
    var expandedPartnerKeys by remember { mutableStateOf(setOf<String>()) }

    var selectedUsernames by remember { mutableStateOf(setOf<String>()) }
    var selectedDepIds by remember { mutableStateOf(setOf<String>()) }

    // RN :190-193：create/forward → me 预选；:201-208 preselectedUsernames 补入
    LaunchedEffect(isAddToRoom, me) {
        if (!isAddToRoom && me.isNotEmpty() && me !in selectedUsernames) {
            selectedUsernames = selectedUsernames + me
        }
    }
    LaunchedEffect(isAddToRoom, preselectedUsernames) {
        if (!isAddToRoom && preselectedUsernames.isNotEmpty()) {
            selectedUsernames = selectedUsernames + preselectedUsernames
        }
    }
    // RN :210-218：addToRoom 既有成员预检（不出底栏）
    LaunchedEffect(isAddToRoom, existingSet) {
        if (isAddToRoom && existingSet.isNotEmpty()) selectedUsernames = selectedUsernames + existingSet
    }
    // RN :220-228：inactive agent 自动出选
    LaunchedEffect(agents) {
        val inactive = agents.filter { !it.active }.map { it.username }
        if (inactive.isNotEmpty()) selectedUsernames = selectedUsernames - inactive.toSet()
    }
    // RN :134-138：门关闭时 partners 子源回退 recent
    LaunchedEffect(showExternalPartners) {
        if (!showExternalPartners && memberSource == MemberSource.PARTNERS) memberSource = MemberSource.RECENT
    }

    // 懒拉
    LaunchedEffect(memberSource, mode, showExternalPartners, sdk) {
        val s = sdk ?: return@LaunchedEffect
        if (mode != CreateChannelMode.MEMBERS) return@LaunchedEffect
        if (memberSource == MemberSource.PARTNERS && showExternalPartners && !partnersLoaded) {
            partnersLoading = true
            partnersLoaded = true
            runCatching { PartnersApi.fetchPartners(s) }
                .onSuccess { partnerGroups = mapPartnerGroups(it) }
                .onFailure { partnerGroups = emptyList() }
            partnersLoading = false
        }
        if (memberSource == MemberSource.AGENTS && !agentsLoaded) {
            agentsLoading = true
            agentsError = false
            agentsLoaded = true
            runCatching { ClawAgentsApi.fetchClawAgents(s) }
                .onSuccess { agents = ClawAgentsApi.orderAgents(it) }
                .onFailure { agentsError = true }
            agentsLoading = false
        }
    }

    // 判定（纯函数）
    val canConfirm = if (isAddToRoom) {
        canConfirmAddToRoom(existingSet, selectedUsernames, selectedDepIds)
    } else {
        canConfirmCreateChannel(mode, allAutoJoin, selectedUsernames, selectedDepIds)
    }
    val showShareRecord = isAddToRoom && canConfirm
    val showAutoJoin = !isAddToRoom && mode == CreateChannelMode.MEMBERS

    // RN isUserDisabled :237-246：create/forward → u==me；addToRoom → 既有成员
    fun isUserDisabled(username: String): Boolean {
        val u = username.trim()
        if (u.isEmpty()) return true
        return if (!isAddToRoom) u == me else u in existingSet
    }

    fun toggleUsername(u: String) {
        if (u.trim().isEmpty() || isUserDisabled(u)) return
        selectedUsernames = if (u in selectedUsernames) selectedUsernames - u else selectedUsernames + u
    }

    fun toggleDepId(id: String) {
        if (id.isEmpty()) return
        selectedDepIds = if (id in selectedDepIds) selectedDepIds - id else selectedDepIds + id
    }

    fun toggleDeptSelectAllMembers(deptId: String) {
        selectedUsernames = toggleDeptSelectAllMembersUncapped(
            deptId, selectedUsernames, me, departmentMap, userMap,
        )
    }

    // RN getSelectedUserLabel :810-836：agent → recent → remote → org user → partner → @username
    fun selectedUserLabel(username: String): String {
        val u = username.trim()
        if (u.isEmpty()) return ""
        agents.firstOrNull { it.username == u }?.name?.let { return it }
        recentContacts.firstOrNull { it.username == u }?.displayName?.let { return it }
        remoteUsersByUsername.value[u]?.displayName?.let { return it }
        userMap.values.firstOrNull { it.username?.trim() == u }?.let { o ->
            (o.fname ?: o.name)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        partnerGroups.firstNotNullOfOrNull { g -> g.users.firstOrNull { it.username == u }?.name }?.let { return it }
        return "@$u"
    }

    // 确认（RN handleConfirm :262-337）
    fun handleConfirm() {
        if (!canConfirm || submitting || sdk == null) return
        scope.launch {
            submitting = true
            try {
                if (isAddToRoom && rid != null) {
                    val users = selectedUsernames.map { it.trim() }
                        .filter { it.isNotEmpty() && it !in existingSet }
                    RoomSettingsApi.postAddUsersToRoom(sdk, rid, users, selectedDepIds.toList(), isShareRecord)
                    onBack()
                } else {
                    val raw = ChannelsApi.createChannelByMembers(
                        sdk,
                        users = selectedUsernames.toList(),
                        depIds = selectedDepIds.toList(),
                        all = allAutoJoin,
                    )
                    val result = ChannelsApi.parseCreateChannelResult(raw)
                    val newRid = result.rid ?: error(context.t("createchannelmembers_createfailedtitle"))
                    if (forwardMessageIds.isNotEmpty()) {
                        // RN :296-309：转发失败不阻断跳转（频道已建成），仅 Alert
                        runCatching {
                            ForwardApi.forwardMessage(
                                sdk,
                                forwardMessageIds = forwardMessageIds,
                                forwardRooms = listOf(newRid),
                                isForwardMerged = forwardIsMerged,
                            )
                        }.onFailure { alertMsg = context.t("forwardfailed") }
                    }
                    onCreated(newRid, result.name)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "confirm failed", e)
                alertMsg = if (isAddToRoom) {
                    context.t("roomaddmembers_failed")
                } else {
                    context.t("createchannelmembers_createfailedtitle")
                }
            } finally {
                submitting = false
            }
        }
    }

    // 派生数据
    val q = query.trim().lowercase()
    val inSearchMode = query.trim().isNotEmpty()
    val pmtRootId = rootTree.getOrNull(0) ?: TEAM_ROOT_IDS.getValue(TeamRootType.PMT)
    val l1dRootId = rootTree.getOrNull(1) ?: TEAM_ROOT_IDS.getValue(TeamRootType.L1D)
    val orgRootId = if (orgSource == OrgSource.PMT) pmtRootId else l1dRootId

    val memberPmtRows = remember(departmentMap, userMap, expandedPmt, pmtRootId) {
        buildOrgTreeRows(pmtRootId, expandedPmt, departmentMap, userMap)
    }
    val memberL1dRows = remember(departmentMap, userMap, expandedL1d, l1dRootId) {
        buildOrgTreeRows(l1dRootId, expandedL1d, departmentMap, userMap)
    }
    val orgRows = remember(departmentMap, userMap, expandedOrg, orgRootId) {
        buildOrgTreeRows(orgRootId, expandedOrg, departmentMap, userMap)
    }
    val visibleAgents = remember(agents, q) { ClawAgentsApi.filterAgents(agents, q) }
    val visibleRecent = remember(recentContacts, q) {
        if (q.isEmpty()) recentContacts
        else recentContacts.filter { "${it.displayName}${it.username}".lowercase().contains(q) }
    }
    val visiblePartnerGroups = remember(partnerGroups, q) { filterPartnerGroups(partnerGroups, q) }

    // RN :1308-1334：树行过滤（dept: name+_id / user: displayName+username+sub）
    fun filterTreeRows(rows: List<ForwardOrgRow>): List<ForwardOrgRow> = if (q.isEmpty()) rows else rows.filter {
        when (it) {
            is ForwardOrgRow.Dept -> "${it.dept.name}${it.dept._id}".lowercase().contains(q)
            is ForwardOrgRow.User -> "${it.displayName}${it.username}${it.sub}".lowercase().contains(q)
        }
    }

    // 底栏已选条（RN selectedItems :838-873：addToRoom 既有成员不出底栏；key 排序）
    val selectedItems = remember(selectedUsernames, selectedDepIds, departmentMap, existingSet, isAddToRoom, agents, recentContacts, remoteUsersByUsername.value, userMap, partnerGroups) {
        val items = mutableListOf<SelectedChip>()
        for (username in selectedUsernames) {
            val u = username.trim()
            if (u.isEmpty()) continue
            if (isAddToRoom && u in existingSet) continue
            items += SelectedChip.User("u:$u", selectedUserLabel(u).ifEmpty { "@$u" }, u)
        }
        for (depId in selectedDepIds) {
            val id = depId.trim()
            if (id.isEmpty()) continue
            items += SelectedChip.Dept(
                "d:$id",
                departmentMap[id]?.name ?: context.t("createchannelmembers_selecteddeptfallback"),
                id,
            )
        }
        items.sortedBy { it.key }
    }

    // ── 渲染 ──
    Column(Modifier.fillMaxSize().background(colors.backgroundColor).testTag("qa-ccm-screen")) {
        // 顶栏
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹",
                color = colors.titleText,
                fontSize = 26.sp,
                modifier = Modifier
                    .size(40.dp)
                    .wrapContentSize(Alignment.Center)
                    .clickable(onClick = onBack)
                    .testTag("qa-ccm-back"),
            )
            Text(
                context.t(if (isAddToRoom) "roomaddmembers_title" else "createchannelmembers_navtitle"),
                color = colors.titleText,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).wrapContentSize(Alignment.Center),
            )
            Spacer(Modifier.size(40.dp))
        }

        // 搜索框（agents 子源本地过滤，其余喂 searcher）
        TextField(
            value = query,
            onValueChange = {
                query = it
                if (!useAgentLocalSearch) searcher.onQueryChanged(it)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("qa-ccm-search-input"),
            placeholder = {
                Text(context.t("createchannelmembers_searchplaceholdermembers"), color = colors.auxiliaryText)
            },
            singleLine = true,
        )

        // 分享消息记录（RN :1628-1642：addToRoom 且可确认才显示；整行 Pressable）
        if (showShareRecord) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { isShareRecord = !isShareRecord }
                    .padding(horizontal = 16.dp, vertical = 2.dp)
                    .testTag("qa-ccm-share-record"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = isShareRecord, onCheckedChange = null)
                Text(context.t("roomaddmembers_sharemessagerecords"), fontSize = 14.sp)
            }
        }

        // 主/子 tab（搜索态隐藏——RN :1644/:1671）
        if (!inSearchMode) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    CreateChannelMode.MEMBERS to "createchannelmembers_tabmembers",
                    CreateChannelMode.ORG to "createchannelmembers_taborg",
                ).forEach { (tab, key) ->
                    val active = mode == tab
                    Text(
                        context.t(key),
                        color = if (active) colors.tintColor else colors.auxiliaryText,
                        fontSize = 15.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (active) colors.tintColor.copy(alpha = 0.12f) else Color.Transparent)
                            .clickable { mode = tab }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                            .testTag("qa-ccm-main-tab-${if (tab == CreateChannelMode.MEMBERS) "members" else "org"}"),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (mode == CreateChannelMode.MEMBERS) {
                    val tabs = buildList {
                        add(MemberSource.RECENT to context.t("createchannelmembers_sourcerecent"))
                        add(MemberSource.PMT to "PMT")
                        add(MemberSource.L1D to "L1D")
                        if (showExternalPartners) add(MemberSource.PARTNERS to context.t("createchannelmembers_sourcepartners"))
                        add(MemberSource.AGENTS to context.t("agents_title"))
                    }
                    tabs.forEach { (src, label) ->
                        val active = memberSource == src
                        Text(
                            label,
                            color = if (active) colors.tintColor else colors.auxiliaryText,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (active) colors.tintColor.copy(alpha = 0.12f) else Color.Transparent)
                                .clickable { memberSource = src }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                                .testTag("qa-ccm-sub-tab-${src.name.lowercase()}"),
                        )
                    }
                } else {
                    listOf(OrgSource.PMT to "PMT", OrgSource.L1D to "L1D").forEach { (src, label) ->
                        val active = orgSource == src
                        Text(
                            label,
                            color = if (active) colors.tintColor else colors.auxiliaryText,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (active) colors.tintColor.copy(alpha = 0.12f) else Color.Transparent)
                                .clickable { orgSource = src }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                                .testTag("qa-ccm-org-tab-${src.name.lowercase()}"),
                        )
                    }
                }
            }
        }

        // 内容区（tab 常驻保滚动 + 搜索态切换）
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (inSearchMode && !useAgentLocalSearch) {
                SearchResultsPane(
                    state = searchState,
                    showAutoJoin = showAutoJoin,
                    allAutoJoin = allAutoJoin,
                    onToggleAll = { allAutoJoin = !allAutoJoin },
                    checked = { it in selectedUsernames },
                    disabled = { isUserDisabled(it) },
                    onToggle = ::toggleUsername,
                )
            } else {
                // 六 pane 常驻：LazyListState rememberSaveable 承滚动、组合树保留数据态
                Pane(visible = mode == CreateChannelMode.MEMBERS && memberSource == MemberSource.RECENT) {
                    // Box 内 Column 排列（LazyColumn fillMaxSize 会盖住同 Box 的前置行）
                    Column(Modifier.fillMaxSize()) {
                        if (showAutoJoin) AutoJoinRow(allAutoJoin, { allAutoJoin = !allAutoJoin })
                        LazyColumn(state = recentListState, modifier = Modifier.fillMaxSize().testTag("qa-ccm-recent-list")) {
                            items(visibleRecent, key = { it.username }) { row ->
                                MemberRow(
                                    title = row.displayName,
                                    subtitle = null,
                                    checked = row.username in selectedUsernames,
                                    disabled = isUserDisabled(row.username),
                                    onToggle = { toggleUsername(row.username) },
                                    testTag = "qa-ccm-recent-row-${row.username}",
                                )
                            }
                        }
                    }
                }
                Pane(visible = mode == CreateChannelMode.MEMBERS && memberSource == MemberSource.PARTNERS) {
                    Column(Modifier.fillMaxSize()) {
                        if (showAutoJoin) AutoJoinRow(allAutoJoin, { allAutoJoin = !allAutoJoin })
                        when {
                            partnersLoading -> CenterHint(context.t("createchannelmembers_loadingpartners"))
                            visiblePartnerGroups.isEmpty() -> CenterHint(context.t("createchannelmembers_nopartners"))
                            else -> LazyColumn(state = partnersListState, modifier = Modifier.fillMaxSize().testTag("qa-ccm-partners-list")) {
                                items(visiblePartnerGroups, key = { it.key }) { g ->
                                    PartnerGroupBlock(
                                        group = g,
                                        checkState = partnerGroupCheckState(g, selectedUsernames),
                                        expanded = g.key in expandedPartnerKeys,
                                        onToggleGroup = { selectedUsernames = togglePartnerGroupUsers(g, selectedUsernames, me) },
                                        onToggleExpand = {
                                            expandedPartnerKeys =
                                                if (g.key in expandedPartnerKeys) expandedPartnerKeys - g.key
                                                else expandedPartnerKeys + g.key
                                        },
                                        checked = { it in selectedUsernames },
                                        disabled = { isUserDisabled(it) },
                                        onToggleUser = ::toggleUsername,
                                    )
                                }
                            }
                        }
                    }
                }
                Pane(visible = mode == CreateChannelMode.MEMBERS && memberSource == MemberSource.AGENTS) {
                    when {
                        agentsLoading -> CenterHint(context.t("agents_loading"))
                        agentsError -> CenterHint(context.t("agents_loadfailed"))
                        else -> LazyColumn(state = agentsListState, modifier = Modifier.fillMaxSize().testTag("qa-ccm-agents-list")) {
                            items(visibleAgents, key = { it.id }) { agent ->
                                MemberRow(
                                    title = agent.name,
                                    subtitle = agent.username,
                                    checked = agent.username in selectedUsernames,
                                    disabled = !agent.active || isUserDisabled(agent.username),
                                    onToggle = { toggleUsername(agent.username) },
                                    testTag = "qa-ccm-agent-row-${agent.username}",
                                )
                            }
                        }
                    }
                }
                OrgTreePane(
                    visible = mode == CreateChannelMode.MEMBERS && memberSource == MemberSource.PMT,
                    rows = filterTreeRows(memberPmtRows),
                    listState = pmtListState,
                    phase = contactsPhase,
                    showAutoJoin = showAutoJoin,
                    allAutoJoin = allAutoJoin,
                    onToggleAll = { allAutoJoin = !allAutoJoin },
                    isOrgMode = false,
                    expanded = expandedPmt,
                    onToggleExpand = { id ->
                        expandedPmt = if (id in expandedPmt) expandedPmt - id else expandedPmt + id
                    },
                    selectedUsernames = selectedUsernames,
                    selectedDepIds = selectedDepIds,
                    onToggleUser = ::toggleUsername,
                    onToggleDept = ::toggleDeptSelectAllMembers,
                    departmentMap = departmentMap,
                    userMap = userMap,
                    isUserDisabled = ::isUserDisabled,
                    listTag = "qa-ccm-pmt-list",
                )
                OrgTreePane(
                    visible = mode == CreateChannelMode.MEMBERS && memberSource == MemberSource.L1D,
                    rows = filterTreeRows(memberL1dRows),
                    listState = l1dListState,
                    phase = contactsPhase,
                    showAutoJoin = showAutoJoin,
                    allAutoJoin = allAutoJoin,
                    onToggleAll = { allAutoJoin = !allAutoJoin },
                    isOrgMode = false,
                    expanded = expandedL1d,
                    onToggleExpand = { id ->
                        expandedL1d = if (id in expandedL1d) expandedL1d - id else expandedL1d + id
                    },
                    selectedUsernames = selectedUsernames,
                    selectedDepIds = selectedDepIds,
                    onToggleUser = ::toggleUsername,
                    onToggleDept = ::toggleDeptSelectAllMembers,
                    departmentMap = departmentMap,
                    userMap = userMap,
                    isUserDisabled = ::isUserDisabled,
                    listTag = "qa-ccm-l1d-list",
                )
                OrgTreePane(
                    visible = mode == CreateChannelMode.ORG,
                    rows = filterTreeRows(orgRows),
                    listState = orgListState,
                    phase = contactsPhase,
                    showAutoJoin = false,
                    allAutoJoin = false,
                    onToggleAll = {},
                    isOrgMode = true,
                    expanded = expandedOrg,
                    onToggleExpand = { id ->
                        expandedOrg = if (id in expandedOrg) expandedOrg - id else expandedOrg + id
                    },
                    selectedUsernames = selectedUsernames,
                    selectedDepIds = selectedDepIds,
                    onToggleUser = ::toggleUsername,
                    onToggleDept = ::toggleDepId,
                    departmentMap = departmentMap,
                    userMap = userMap,
                    isUserDisabled = ::isUserDisabled,
                    listTag = "qa-ccm-org-list",
                )
            }
        }

        // 底栏（已选条 + 确认钮——RN :1728-1834）
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.messageboxBackground)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectedItems.isEmpty()) {
                Text(
                    context.t("createchannelmembers_selecthint"),
                    color = colors.auxiliaryText,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f).testTag("qa-ccm-select-hint"),
                )
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f).testTag("qa-ccm-selected-list"),
                ) {
                    items(selectedItems, key = { it.key }) { item ->
                        SelectedChipItem(
                            label = item.label,
                            onClick = {
                                when (item) {
                                    is SelectedChip.User -> toggleUsername(item.username)
                                    is SelectedChip.Dept -> toggleDepId(item.depId)
                                }
                            },
                            testTag = "qa-ccm-selected-${item.key}",
                        )
                    }
                }
            }
            Text(
                context.t(
                    when {
                        submitting -> "createchannelmembers_confirmsubmitting"
                        isAddToRoom -> "roomaddmembers_confirm"
                        else -> "createchannelmembers_confirm"
                    },
                ),
                color = if (canConfirm && !submitting) colors.tintColor else colors.auxiliaryText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable(enabled = canConfirm && !submitting) { handleConfirm() }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("qa-ccm-confirm"),
            )
        }
    }

    // 失败 Alert（RN :318-324：title + message + OK）
    alertMsg?.let { msg ->
        AlertDialog(
            onDismissRequest = { alertMsg = null },
            title = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { alertMsg = null }) { Text("OK") }
            },
        )
    }
}

// ── 内部组件 ──

/** tab 常驻容器（RN pagesHost + pageHidden：不卸载、仅切可见——滚动由 saveable LazyListState 保）。 */
@Composable
private fun Pane(visible: Boolean, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .then(if (visible) Modifier else Modifier.size(0.dp)),
    ) {
        if (visible) content()
    }
}

@Composable
private fun CenterHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = LocalAppiaColors.current.auxiliaryText, fontSize = 14.sp)
    }
}

/** RN renderAutoJoinRow（:1257-1266）：整行 Pressable；Checkbox 仅展示（行点击承接）。 */
@Composable
private fun AutoJoinRow(allAutoJoin: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .testTag("qa-ccm-toggle-all"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = allAutoJoin, onCheckedChange = null)
        Text(context.t("createchannelmembers_allautojoin"), fontSize = 14.sp)
    }
}

/** RN renderMemberRow/renderRemoteUserRow/renderTreeUserRow：checkbox + 头像 + 名 + 副。 */
@Composable
private fun MemberRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    disabled: Boolean,
    onToggle: () -> Unit,
    testTag: String,
) {
    val colors = LocalAppiaColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !disabled, onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = if (disabled) null else ({ onToggle() }), enabled = !disabled)
        Box(
            Modifier
                .padding(start = 4.dp)
                .size(38.dp)
                .clip(CircleShape)
                .background(colors.chatComponentBackground),
            contentAlignment = Alignment.Center,
        ) {
            Text(title.take(1), color = colors.auxiliaryText, fontSize = 15.sp)
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title, color = colors.titleText, fontSize = 15.sp, maxLines = 1)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, color = colors.auxiliaryText, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

/** RN renderPartnerGroup（:711-759）：组头三态 + 展开成员行。 */
@Composable
private fun PartnerGroupBlock(
    group: PartnerGroup,
    checkState: PartnerCheckState,
    expanded: Boolean,
    onToggleGroup: () -> Unit,
    onToggleExpand: () -> Unit,
    checked: (String) -> Boolean,
    disabled: (String) -> Boolean,
    onToggleUser: (String) -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalAppiaColors.current
    Column(Modifier.fillMaxWidth().testTag("qa-ccm-partner-group-${group.key}")) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TriStateCheckbox(
                state = checkState.toForwardState(),
                onClick = onToggleGroup,
                modifier = Modifier.testTag("qa-ccm-partner-check-${group.key}"),
            )
            Row(
                Modifier
                    .weight(1f)
                    .clickable(onClick = onToggleExpand),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(group.title, color = colors.titleText, fontSize = 15.sp, maxLines = 1)
                    Text(
                        interpolate(
                            context.t("createchannelmembers_partnercontactscount"),
                            mapOf(
                                "count" to group.users.size.toString(),
                                "suffix" to context.t("createchannelmembers_partnercontactssuffix"),
                            ),
                        ),
                        color = colors.auxiliaryText,
                        fontSize = 12.sp,
                    )
                }
                Text(if (expanded) "⌄" else "›", color = colors.auxiliaryText, fontSize = 16.sp)
            }
        }
        if (expanded) {
            group.users.forEach { u ->
                MemberRow(
                    title = u.name ?: u.username,
                    subtitle = "@${u.username}",
                    checked = checked(u.username),
                    disabled = disabled(u.username),
                    onToggle = { onToggleUser(u.username) },
                    testTag = "qa-ccm-partner-row-${u.username}",
                )
            }
        }
    }
}

private fun PartnerCheckState.toForwardState(): ForwardDeptCheckState = when (this) {
    PartnerCheckState.UNCHECKED -> ForwardDeptCheckState.UNCHECKED
    PartnerCheckState.CHECKED -> ForwardDeptCheckState.CHECKED
    PartnerCheckState.INDETERMINATE -> ForwardDeptCheckState.INDETERMINATE
}

/** 搜索结果 pane（RN :1278-1300：loading → FlatList → noResults，autoJoin 行常驻头）。 */
@Composable
private fun SearchResultsPane(
    state: UserSearcher.State,
    showAutoJoin: Boolean,
    allAutoJoin: Boolean,
    onToggleAll: () -> Unit,
    checked: (String) -> Boolean,
    disabled: (String) -> Boolean,
    onToggle: (String) -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        if (showAutoJoin) AutoJoinRow(allAutoJoin, onToggleAll)
        when {
            state.loading -> CenterHint(context.t("roommembers_loading"))
            state.users.isEmpty() -> CenterHint(context.t("globalsearch_noresults"))
            else -> LazyColumn(Modifier.fillMaxSize().testTag("qa-ccm-search-list")) {
                items(state.users, key = { it._id }) { user ->
                    MemberRow(
                        title = user.displayName,
                        subtitle = user.subtitle,
                        checked = checked(user.username),
                        disabled = disabled(user.username),
                        onToggle = { onToggle(user.username) },
                        testTag = "qa-ccm-search-row-${user.username}",
                    )
                }
            }
        }
    }
}

/**
 * 组织树 pane（RN :1413-1585 members pmt/l1d + org 三块同构）：
 * members 语境部门勾选 = BFS 后代三态全选；org 语境 = depIds 二态、成员行仅展示。
 */
@Composable
private fun OrgTreePane(
    visible: Boolean,
    rows: List<ForwardOrgRow>,
    listState: LazyListState,
    phase: ContactsPhase,
    showAutoJoin: Boolean,
    allAutoJoin: Boolean,
    onToggleAll: () -> Unit,
    isOrgMode: Boolean,
    expanded: Set<String>,
    onToggleExpand: (String) -> Unit,
    selectedUsernames: Set<String>,
    selectedDepIds: Set<String>,
    onToggleUser: (String) -> Unit,
    onToggleDept: (String) -> Unit,
    departmentMap: Map<String, TeamDepartment>,
    userMap: Map<String, TeamUser>,
    isUserDisabled: (String) -> Boolean,
    listTag: String,
) {
    val context = LocalContext.current
    Pane(visible = visible) {
        Column(Modifier.fillMaxSize()) {
            if (showAutoJoin) AutoJoinRow(allAutoJoin, onToggleAll)
            when (phase) {
                ContactsPhase.LOADING, ContactsPhase.UNLOAD ->
                    CenterHint(context.t("createchannelmembers_loadingorgmembers"))
                ContactsPhase.LOAD_ERROR ->
                    CenterHint(context.t("createchannelmembers_orgmembersloadfailed"))
                ContactsPhase.LOADED -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag(listTag)) {
                    items(rows, key = { it.id }) { row ->
                        when (row) {
                            is ForwardOrgRow.Dept -> {
                                val checkState = if (isOrgMode) {
                                    if (row.id in selectedDepIds) ForwardDeptCheckState.CHECKED
                                    else ForwardDeptCheckState.UNCHECKED
                                } else {
                                    deptCheckboxState(row.id, selectedUsernames, departmentMap, userMap)
                                }
                                DeptRow(
                                    row = row,
                                    checkState = checkState,
                                    expanded = row.id in expanded,
                                    onToggleCheck = { onToggleDept(row.id) },
                                    onToggleExpand = { onToggleExpand(row.id) },
                                )
                            }
                            is ForwardOrgRow.User -> {
                                if (isOrgMode) {
                                    // org 模式成员行恒 disabled（RN :1550-1557：仅展示）
                                    MemberRow(
                                        title = row.displayName,
                                        subtitle = row.sub,
                                        checked = false,
                                        disabled = true,
                                        onToggle = {},
                                        testTag = "qa-ccm-org-user-${row.username}",
                                    )
                                } else {
                                    MemberRow(
                                        title = row.displayName,
                                        subtitle = row.sub,
                                        checked = row.username in selectedUsernames,
                                        disabled = isUserDisabled(row.username),
                                        onToggle = { onToggleUser(row.username) },
                                        testTag = "qa-ccm-org-user-${row.username}",
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** RN renderTreeDeptRow（:1099-1168）：缩进 + 三态 checkbox + 部门块 + 名/计数/箭头。 */
@Composable
private fun DeptRow(
    row: ForwardOrgRow.Dept,
    checkState: ForwardDeptCheckState,
    expanded: Boolean,
    onToggleCheck: () -> Unit,
    onToggleExpand: () -> Unit,
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    val dept = row.dept
    val hasToggle = dept.children.isNotEmpty() || dept.users.isNotEmpty()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = (row.depth * 16).dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(
            state = checkState,
            onClick = onToggleCheck,
            modifier = Modifier.testTag("qa-ccm-dept-check-${row.id}"),
        )
        Row(
            Modifier
                .weight(1f)
                .clickable(enabled = hasToggle, onClick = { onToggleExpand() }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.chatComponentBackground),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    (dept.type ?: dept.name.ifEmpty { row.id }).take(1),
                    color = colors.auxiliaryText,
                    fontSize = 14.sp,
                )
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        dept.name.ifEmpty { row.id },
                        color = colors.titleText,
                        fontSize = 15.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (hasToggle) {
                        Text(
                            if (expanded) "⌄" else "›",
                            color = colors.auxiliaryText,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                dept.usersCountIncludeChildren?.let { count ->
                    Text(
                        interpolate(
                            context.t("createchannelmembers_deptpeoplecount"),
                            mapOf(
                                "count" to count.toString(),
                                "suffix" to context.t("createchannelmembers_peoplesuffix"),
                            ),
                        ),
                        color = colors.auxiliaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 三态 checkbox（同 ForwardSelectScreen 实现：indeterminate = clickable Box + disabled Checkbox + 「−」）。 */
@Composable
private fun TriStateCheckbox(state: ForwardDeptCheckState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (state == ForwardDeptCheckState.INDETERMINATE) {
        Box(
            modifier
                .size(48.dp)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Checkbox(checked = false, onCheckedChange = null, enabled = false)
            Text("−", color = LocalAppiaColors.current.tintColor, fontSize = 16.sp)
        }
    } else {
        Box(modifier, contentAlignment = Alignment.Center) {
            Checkbox(checked = state == ForwardDeptCheckState.CHECKED, onCheckedChange = { onClick() })
        }
    }
}

/** 已选条目 chip（RN renderItem :1755-1811：头像占位 + 名，点按取消选择）。 */
@Composable
private fun SelectedChipItem(label: String, onClick: () -> Unit, testTag: String) {
    val colors = LocalAppiaColors.current
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(colors.chatComponentBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(colors.auxiliaryBackground),
            contentAlignment = Alignment.Center,
        ) {
            Text(label.take(1), color = colors.auxiliaryText, fontSize = 12.sp)
        }
        Text(
            label,
            color = colors.titleText,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}
