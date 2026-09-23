package cn.appia.im.feature.contacts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.i18n.t
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.theme.AppiaColors
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.feature.contacts.BuildTeamFlatListParams
import cn.appia.im.feature.contacts.BuildTeamHomeModelParams
import cn.appia.im.feature.contacts.ContactsPhase
import cn.appia.im.feature.contacts.ContactsStore
import cn.appia.im.feature.contacts.TEAM_ROOT_IDS
import cn.appia.im.feature.contacts.TeamDeptSummary
import cn.appia.im.feature.contacts.TeamFlatListItem
import cn.appia.im.feature.contacts.TeamHomeModel
import cn.appia.im.feature.contacts.TeamMember
import cn.appia.im.feature.contacts.TeamRootType
import cn.appia.im.feature.contacts.buildTeamFlatList
import cn.appia.im.feature.contacts.buildTeamHomeModel
import cn.appia.im.feature.contacts.memberMatchesQuery
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

/**
 * 通讯录团队页（RN screens/TeamScreen/index.tsx 逐结构移植）：
 * - 根视图：搜索框 / PMT-L1D 分段 tab / 我的卡（头像+我+employeeDesc 徽标）/ 公司头
 *   （logo+Enterprise_Name+总数）/ 部门行（图标按 tagLabel）/ footer（总数+就业类型计数）
 * - 子部门视图（deptId 非空）：buildTeamFlatList 扁平（子部门+直属成员）
 * - 搜索：根视图合并 PMT+L1D 去重（seenDeptIds/seenUsernames）；子部门过滤 flat items
 * - 加载态：UNLOAD/LOADING → team_loading；LOAD_ERROR → team_loadfailed + 重试钮
 * - 成员点击 → T9 名片（onOpenMemberProfile）；部门点击 → push 子视图（onOpenDept）
 * - presence 预取跳过：Android 无 presence 基建（RealtimeSessionManager M5 占位，
 *   T5 同款披露）；绿点按通讯录 status 回退渲染（presenceFallbackStatus）
 * - 部门图标色块降级：RN 五类 PNG 无 Android drawable（T5 先例——tagLabel 首字母）
 */

private val CompanyLogoBase = "https://static.appia.cn/logo"

/** RN getCompanyLogoUrl。 */
internal fun companyLogoUrl(enterpriseLabel: String): String =
    "$CompanyLogoBase/${enterpriseLabel.lowercase()}.png"

/** RN getTeamUserAvatarUri（DirectAvatar direct 变体：/avatar/{username}?…&rc_token&rc_uid）。
 *  size 由调用方按渲染 dp×密度换算 px（chatAvatarUrl 先例）。 */
internal fun teamAvatarUrl(serverUrl: String, username: String, userId: String?, token: String?, sizePx: Int): String? {
    if (serverUrl.isBlank() || username.isBlank()) return null
    return buildString {
        append(serverUrl.trimEnd('/')).append("/avatar/").append(username)
        append("?version=1&format=png&size=").append(sizePx)
        if (!userId.isNullOrEmpty() && !token.isNullOrEmpty()) {
            append("&rc_token=").append(token).append("&rc_uid=").append(userId)
        }
    }
}

@Composable
fun TeamScreen(
    deptId: String? = null,
    sdk: RocketSdk?,
    serverUrl: String,
    currentUserId: String?,
    currentUsername: String?,
    token: String?,
    enterpriseId: String? = null,
    enterpriseName: String? = null,
    onBack: () -> Unit,
    onOpenMemberProfile: (username: String, userId: String?) -> Unit,
    onOpenDept: (deptId: String) -> Unit,
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isSubDept = deptId != null

    // RN useContacts：进屏自动拉取（仅 UNLOAD）
    LaunchedEffect(sdk) {
        if (sdk != null) scope.launch { runCatching { ContactsStore.refreshIfUnloaded(sdk) } }
    }
    val phase by ContactsStore.phase.collectAsState()
    val payload by ContactsStore.payload.collectAsState()

    var activeRootType by remember { mutableStateOf(TeamRootType.PMT) }
    var searchQuery by remember { mutableStateOf("") }

    val userMap = payload.userMap
    val departmentMap = payload.departmentMap

    val homeModel = remember(activeRootType, userMap, departmentMap, currentUserId, currentUsername) {
        buildTeamHomeModel(
            BuildTeamHomeModelParams(
                rootType = activeRootType,
                userMap = userMap,
                departmentMap = departmentMap,
                currentUserId = currentUserId,
                username = currentUsername,
                unknownMemberLabel = context.t("team_unknownmember"),
            ),
        )
    }
    val flatListModel = remember(deptId, userMap, departmentMap, currentUserId, currentUsername) {
        deptId?.let {
            buildTeamFlatList(
                BuildTeamFlatListParams(
                    deptId = it,
                    userMap = userMap,
                    departmentMap = departmentMap,
                    currentUserId = currentUserId,
                    username = currentUsername,
                    unknownMemberLabel = context.t("team_unknownmember"),
                ),
            )
        }
    }

    // 搜索（根视图）：PMT+L1D 合并去重（RN :107-141）
    val trimmedQuery = searchQuery.trim()
    val searchResult = remember(trimmedQuery, userMap, departmentMap, currentUserId, currentUsername, isSubDept) {
        if (isSubDept || trimmedQuery.isEmpty()) null
        else {
            val allDepts = mutableListOf<TeamDeptSummary>()
            val allMembers = mutableListOf<TeamMember>()
            val seenDeptIds = mutableSetOf<String>()
            val seenUsernames = mutableSetOf<String>()
            for (rt in listOf(TeamRootType.PMT, TeamRootType.L1D)) {
                val m = buildTeamHomeModel(
                    BuildTeamHomeModelParams(
                        rootType = rt,
                        userMap = userMap,
                        departmentMap = departmentMap,
                        currentUserId = currentUserId,
                        username = currentUsername,
                        unknownMemberLabel = context.t("team_unknownmember"),
                    ),
                )
                for (dept in m.departments) if (seenDeptIds.add(dept.id)) allDepts.add(dept)
                for (member in m.members) if (member.username.isNotEmpty() && seenUsernames.add(member.username)) allMembers.add(member)
            }
            val q = trimmedQuery.lowercase()
            allDepts.filter { it.name.lowercase().contains(q) } to allMembers.filter { memberMatchesQuery(it, q) }
        }
    }

    val filteredFlatItems = remember(flatListModel, trimmedQuery) {
        val items = flatListModel?.items ?: emptyList()
        if (trimmedQuery.isEmpty()) items
        else {
            val q = trimmedQuery.lowercase()
            items.filter { item ->
                when (item) {
                    is TeamFlatListItem.Dept -> item.dept.name.lowercase().contains(q)
                    is TeamFlatListItem.Member -> memberMatchesQuery(item.member, q)
                }
            }
        }
    }
    val isSearching = trimmedQuery.isNotEmpty()

    Column(Modifier.fillMaxSize().background(colors.backgroundColor)) {
        // navbar（RN 56 高：back + 居中标题）
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "←",
                color = colors.titleText,
                fontSize = 22.sp,
                modifier = Modifier.size(44.dp).wrapContentSize(Alignment.Center)
                    .clickable(onClick = onBack).testTag("qa-team-back"),
            )
            Text(
                if (isSubDept && flatListModel != null) flatListModel.name else context.t("team_title"),
                color = colors.titleText,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("qa-team-title"),
            )
            Spacer(Modifier.width(44.dp))
        }

        // 搜索框（38 高/16 边距/10 圆角）
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp)
                .height(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.searchboxBackground)
                .testTag("qa-team-search"),
        ) {
            androidx.compose.material3.TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().height(38.dp),
                placeholder = { Text(context.t("team_searchplaceholder"), color = colors.auxiliaryText, fontSize = 15.sp) },
                singleLine = true,
                colors = androidx.compose.material3.TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                textStyle = androidx.compose.ui.text.TextStyle(color = colors.titleText, fontSize = 15.sp),
            )
        }

        // body
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (phase) {
                ContactsPhase.UNLOAD, ContactsPhase.LOADING ->
                    CenterState(context.t("team_loading"), tag = "qa-team-loading")
                ContactsPhase.LOAD_ERROR ->
                    Column(
                        Modifier.fillMaxSize().wrapContentSize(Alignment.Center).padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(context.t("team_loadfailed"), color = colors.auxiliaryText, fontSize = 15.sp)
                        Text(
                            context.t("team_retry"),
                            color = colors.buttonText,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .padding(top = 14.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(colors.tintColor)
                                .clickable {
                                    if (sdk != null) scope.launch { runCatching { ContactsStore.refresh(sdk) } }
                                }
                                .padding(horizontal = 18.dp, vertical = 8.dp)
                                .testTag("qa-team-retry"),
                        )
                    }
                ContactsPhase.LOADED -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
                ) {
                    if (isSearching) {
                        SearchContent(
                            isSubDept = isSubDept,
                            flatItems = filteredFlatItems,
                            searchResult = searchResult,
                            serverUrl = serverUrl,
                            currentUserId = currentUserId,
                            token = token,
                            onOpenMemberProfile = onOpenMemberProfile,
                            onOpenDept = onOpenDept,
                        )
                    } else {
                        NormalContent(
                            isSubDept = isSubDept,
                            flatListModel = flatListModel,
                            homeModel = homeModel,
                            activeRootType = activeRootType,
                            onTabSelect = { activeRootType = it },
                            serverUrl = serverUrl,
                            currentUserId = currentUserId,
                            currentUsername = currentUsername,
                            token = token,
                            enterpriseId = enterpriseId,
                            enterpriseName = enterpriseName,
                            onOpenMemberProfile = onOpenMemberProfile,
                            onOpenDept = onOpenDept,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterState(text: String, tag: String) {
    val colors = LocalAppiaColors.current
    Box(Modifier.fillMaxSize().wrapContentSize(Alignment.Center).padding(horizontal = 24.dp).testTag(tag)) {
        Text(text, color = colors.auxiliaryText, fontSize = 15.sp)
    }
}

@Composable
private fun NormalContent(
    isSubDept: Boolean,
    flatListModel: cn.appia.im.feature.contacts.TeamFlatListModel?,
    homeModel: TeamHomeModel,
    activeRootType: TeamRootType,
    onTabSelect: (TeamRootType) -> Unit,
    serverUrl: String,
    currentUserId: String?,
    currentUsername: String?,
    token: String?,
    enterpriseId: String?,
    enterpriseName: String?,
    onOpenMemberProfile: (username: String, userId: String?) -> Unit,
    onOpenDept: (deptId: String) -> Unit,
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current

    if (isSubDept && flatListModel != null) {
        // 子部门视图：扁平 items + footer
        if (flatListModel.items.isEmpty()) {
            CenterState(context.t("team_empty"), tag = "qa-team-empty")
            return
        }
        flatListModel.items.forEach { item ->
            when (item) {
                is TeamFlatListItem.Dept -> DeptRow(item.dept, onOpenDept)
                is TeamFlatListItem.Member -> MemberRow(
                    item.member, serverUrl, currentUserId, token, onOpenMemberProfile,
                )
            }
        }
        SubDeptFooter(flatListModel)
        return
    }

    // 根视图：我的卡 → tab → 公司头 → 部门行 → footer
    homeModel.me?.let { me -> MeCard(me, serverUrl, currentUserId, token) }

    // PMT/L1D tab（34 高/11 圆角）
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(colors.chatComponentBackground)
            .padding(3.dp),
    ) {
        listOf(TeamRootType.PMT to "PMT", TeamRootType.L1D to "L1D").forEach { (type, label) ->
            val active = type == activeRootType
            Text(
                label,
                color = if (active) colors.titleText else colors.auxiliaryText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (active) colors.backgroundColor else Color.Transparent)
                    .wrapContentSize(Alignment.Center)
                    .clickable { onTabSelect(type) }
                    .testTag("qa-team-tab-${label.lowercase()}"),
            )
        }
    }

    // 公司头（36 logo + Enterprise_Name + rootTotalCount）
    val enterpriseLabel = enterpriseId?.trim()?.takeIf { it.isNotEmpty() } ?: "SSC"
    val displayName = enterpriseName?.trim()?.takeIf { it.isNotEmpty() } ?: homeModel.companyName
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.auxiliaryBackground)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            DeptIconBlock("ORG")
            AsyncImage(
                model = companyLogoUrl(enterpriseLabel),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            displayName,
            color = colors.titleText,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 10.dp),
        )
        Text(
            interpolateCount(context.t("team_totalcount"), homeModel.rootTotalCount),
            color = colors.auxiliaryText,
            fontSize = 13.sp,
        )
    }

    if (homeModel.departments.isEmpty()) {
        Text(
            context.t("team_empty"),
            color = colors.auxiliaryText,
            fontSize = 15.sp,
            modifier = Modifier.padding(16.dp).testTag("qa-team-empty"),
        )
    } else {
        homeModel.departments.forEach { DeptRow(it, onOpenDept) }
    }

    RootFooter(homeModel)
}

@Composable
private fun SearchContent(
    isSubDept: Boolean,
    flatItems: List<TeamFlatListItem>,
    searchResult: Pair<List<TeamDeptSummary>, List<TeamMember>>?,
    serverUrl: String,
    currentUserId: String?,
    token: String?,
    onOpenMemberProfile: (username: String, userId: String?) -> Unit,
    onOpenDept: (deptId: String) -> Unit,
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current

    if (isSubDept) {
        if (flatItems.isEmpty()) {
            CenterState(context.t("team_nosearchresults"), tag = "qa-team-no-results")
            return
        }
        flatItems.forEach { item ->
            when (item) {
                is TeamFlatListItem.Dept -> DeptRow(item.dept, onOpenDept)
                is TeamFlatListItem.Member -> MemberRow(item.member, serverUrl, currentUserId, token, onOpenMemberProfile)
            }
        }
        return
    }

    val (depts, members) = searchResult ?: (emptyList<TeamDeptSummary>() to emptyList<TeamMember>())
    if (depts.isEmpty() && members.isEmpty()) {
        CenterState(context.t("team_nosearchresults"), tag = "qa-team-no-results")
        return
    }
    depts.forEach { DeptRow(it, onOpenDept) }
    members.forEach { MemberRow(it, serverUrl, currentUserId, token, onOpenMemberProfile) }
}

/** 我的卡（48 头像 + 我/employeeDesc 徽标）。 */
@Composable
private fun MeCard(
    me: TeamMember,
    serverUrl: String,
    currentUserId: String?,
    token: String?,
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.backgroundColor)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MemberAvatar(me, serverUrl, currentUserId, token, size = 48)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    me.displayName,
                    color = colors.titleText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Badge(context.t("team_mebadge"))
                if (me.employeeDesc.isNotEmpty()) Badge(me.employeeDesc)
            }
            if (listOf(me.position, me.departmentName).filter { it.isNotEmpty() }.isNotEmpty()) {
                Text(
                    listOf(me.position, me.departmentName).filter { it.isNotEmpty() }.joinToString(" · "),
                    color = colors.auxiliaryText,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    val colors = LocalAppiaColors.current
    Text(
        text,
        color = colors.tintColor,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .padding(start = 8.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(colors.searchboxBackground)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** 部门行（min 58 高 + 36 图标 + name + totalCount + ›）。 */
@Composable
private fun DeptRow(dept: TeamDeptSummary, onOpenDept: (String) -> Unit) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clickable { onOpenDept(dept.id) }
            .padding(horizontal = 16.dp)
            .testTag("qa-team-dept-row-${dept.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DeptIconBlock(dept.tagLabel)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                dept.name,
                color = colors.titleText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                interpolateCount(context.t("team_totalcount"), dept.totalCount),
                color = colors.auxiliaryText,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Text("›", color = colors.auxiliaryText, fontSize = 22.sp, modifier = Modifier.padding(start = 10.dp))
    }
}

/**
 * 部门图标色块降级（T5 先例）：RN 五类 PNG（pmt/pdt/l1d/l3d/organization）无 Android
 * drawable——36dp 圆角块 + tagLabel 字母。M4 后引入图标资源时回补。
 */
@Composable
private fun DeptIconBlock(tagLabel: String) {
    val colors = LocalAppiaColors.current
    val letter = tagLabel.take(1)
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.chatComponentBackground),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, color = colors.tintColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 成员行（min 64 高 + 40 头像 + displayName + position · departmentName）。 */
@Composable
private fun MemberRow(
    member: TeamMember,
    serverUrl: String,
    currentUserId: String?,
    token: String?,
    onOpenMemberProfile: (username: String, userId: String?) -> Unit,
) {
    val colors = LocalAppiaColors.current
    val meta = listOf(member.position, member.departmentName).filter { it.isNotEmpty() }.joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable {
                if (member.username.isNotEmpty()) {
                    onOpenMemberProfile(member.username, member.id.takeIf { it.isNotEmpty() })
                }
            }
            .padding(horizontal = 16.dp)
            .testTag("qa-team-member-${member.username.ifEmpty { member.id }}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MemberAvatar(member, serverUrl, currentUserId, token, size = 40)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                member.displayName,
                color = colors.titleText,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotEmpty()) {
                Text(meta, color = colors.auxiliaryText, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

/** 头像：initial 垫底 + Coil AsyncImage + presence 绿点（status 回退——presence 基建 M5 落地前）。 */
@Composable
private fun MemberAvatar(
    member: TeamMember,
    serverUrl: String,
    currentUserId: String?,
    token: String?,
    size: Int,
) {
    val colors = LocalAppiaColors.current
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(size / 2))
            .background(colors.chatComponentBackground),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            member.displayName.take(1).uppercase().ifEmpty { "?" },
            color = colors.auxiliaryText,
            fontSize = if (size >= 48) 18.sp else 15.sp,
        )
        AsyncImage(
            // 渲染 size dp×密度（RN avatarSize 同款；chatAvatarUrl 先例）
            model = teamAvatarUrl(
                serverUrl, member.username, currentUserId, token,
                sizePx = with(LocalDensity.current) { size.dp.roundToPx() },
            ),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
        // presence 绿点（RN DirectAvatar presenceFallbackStatus；online/away → 绿）
        if (member.presenceFallbackStatus == cn.appia.im.feature.contacts.TUserStatus.ONLINE ||
            member.presenceFallbackStatus == cn.appia.im.feature.contacts.TUserStatus.AWAY
        ) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size((size / 4).dp)
                    .clip(RoundedCornerShape((size / 8).dp))
                    .background(AppiaColors.status.online)
                    .testTag("qa-team-presence-${member.username}"),
            )
        }
    }
}

@Composable
private fun RootFooter(model: TeamHomeModel) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    val labels = listOf(
        "fullTime" to context.t("team_fulltime"),
        "outsourcing" to context.t("team_outsourcing"),
        "internship" to context.t("team_internship"),
        "partTime" to context.t("team_parttime"),
        "other" to context.t("team_otheremployment"),
    )
    val visible = labels.mapNotNull { (key, label) ->
        model.rootEmploymentCounts[key]?.takeIf { it > 0 }?.let { key to (label to it) }
    }
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            interpolateCount(context.t("team_totalcount"), model.rootTotalCount),
            color = colors.auxiliaryText,
            fontSize = 13.sp,
        )
        if (visible.isNotEmpty()) {
            Text(
                visible.joinToString(" | ") { (_, labelCount) -> "${labelCount.first} ${labelCount.second}" },
                color = colors.auxiliaryText,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun SubDeptFooter(model: cn.appia.im.feature.contacts.TeamFlatListModel) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    val labels = listOf(
        "fullTime" to context.t("team_fulltime"),
        "outsourcing" to context.t("team_outsourcing"),
        "internship" to context.t("team_internship"),
        "partTime" to context.t("team_parttime"),
        "other" to context.t("team_otheremployment"),
    )
    val visible = labels.mapNotNull { (key, label) ->
        model.employmentCounts[key]?.takeIf { it > 0 }?.let { label to it }
    }
    if (visible.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            interpolateCount(context.t("team_totalcount"), model.totalCount),
            color = colors.auxiliaryText,
            fontSize = 13.sp,
        )
        Text(
            visible.joinToString(" | ") { (label, count) -> "$label $count" },
            color = colors.auxiliaryText,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** {{count}} 插值（RN team_totalCount t 调用）。 */
private fun interpolateCount(template: String, count: Int): String = template.replace("{{count}}", count.toString())
