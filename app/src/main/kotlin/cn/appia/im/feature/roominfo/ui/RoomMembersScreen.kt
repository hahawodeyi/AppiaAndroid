package cn.appia.im.feature.roominfo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.chat.DepartmentRow
import cn.appia.im.core.chat.MemberRow
import cn.appia.im.core.chat.RoomMembersListRows
import cn.appia.im.core.chat.buildMemberActionSheetItems
import cn.appia.im.core.chat.canRemoveMemberRow
import cn.appia.im.core.chat.findRoomMemberRoles
import cn.appia.im.core.chat.mergeMemberRoles
import cn.appia.im.core.chat.parseRoomMembersListRows
import cn.appia.im.core.chat.resolveMemberRoleTag
import cn.appia.im.core.i18n.t
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.api.RoomSettingsApi
import cn.appia.im.core.network.api.RoomsApi
import cn.appia.im.core.permissions.PermissionsStore
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.feature.chat.ui.RoomHeader
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// RN styles.ts 硬编码色（成员页 iOS 分组样式，同 RoomInfoScreen 口径不走主题色板）
private val MemberNameDark = Color(0xFF333333)
private val AuxGray = Color(0xFF6D6D72)
private val CheckboxBlue = Color(0xFF2878FF)
private val CheckboxDisabled = Color(0xFFC6C6C8)
private val BadgeOwnerBg = Color(0xFFFFF7E6)
private val BadgeOwnerText = Color(0xFFFA8C16)
private val BadgeAdminBg = Color(0xFFE6F4FF)
private val BadgeAdminText = Color(0xFF1677FF)

private val MEMBER_AVATAR_SIZE = 44.dp

/** RN getTeamUserAvatarUri（成员行头像鉴权 URL，RoomInfoScreen.memberAvatarUrl 同构）。 */
private fun memberAvatarUrl(serverUrl: String, username: String, userId: String?, token: String?): String? {
    if (serverUrl.isBlank() || username.isBlank()) return null
    return buildString {
        append(serverUrl.trimEnd('/')).append("/avatar/").append(username)
        append("?version=1&format=png&size=80") // RN index.tsx:79 size 80
        if (!userId.isNullOrEmpty() && !token.isNullOrEmpty()) {
            append("&rc_token=").append(token).append("&rc_uid=").append(userId)
        }
    }
}

/**
 * 成员管理页（RN screens/RoomMembersScreen/index.tsx 全结构）：
 * - 双模式：list（默认，长按 ActionSheet）/remove（checkbox 多选 + 头部移除钮 → 批量移除后返回）。
 * - 数据：`appia/room/members/v2` org 块分组（本地块首/多块 orgHeader/部门分组行——
 *   [parseRoomMembersListRows]）+ `getRoomRoles` 角色合并（徽标 owner/SP、moderator/RP）。
 * - 成员动作全部 hasRoomPermission 门（T2）+ canRemoveMemberRow 本地排除（owner 不可移除/
 *   joinType 含 user 才可移除——与服务端权限双层独立）；部门行长按 → removeDepartment。
 * - 发消息回调参数化 [onSendMessage]（openDirectMessage 链归 T9 接线）。
 */
@Composable
fun RoomMembersScreen(
    rid: String,
    roomType: String,
    mode: String,
    sdk: RocketSdk?,
    currentUserId: String?,
    globalRoles: List<String>,
    serverUrl: String,
    token: String?,
    onBack: () -> Unit,
    onOpenMemberProfile: (username: String) -> Unit = {},
    onSendMessage: (username: String, userId: String?) -> Unit = { _, _ -> },
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isRemoveMode = mode == "remove"

    val permissions by PermissionsStore.permissions.collectAsState()

    var loading by remember { mutableStateOf(true) }
    var listRows by remember { mutableStateOf<List<RoomMembersListRows>>(emptyList()) }
    var roomRolesRaw by remember { mutableStateOf<kotlinx.serialization.json.JsonElement?>(null) }
    val selectedUserIds = remember { mutableStateOf(setOf<String>()) }
    val selectedDepIds = remember { mutableStateOf(setOf<String>()) }

    fun loadMembers() {
        if (sdk == null) {
            loading = false
            return
        }
        scope.launch {
            loading = true
            try {
                listRows = parseRoomMembersListRows(RoomsApi.getAppiaRoomMembersV2(sdk, rid))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                listRows = emptyList() // RN catch → setListRows([])
            } finally {
                loading = false
            }
        }
    }

    fun fetchRoles() {
        if (sdk == null) return
        scope.launch {
            try {
                roomRolesRaw = RoomSettingsApi.getRoomRoles(sdk, rid, roomType)
            } catch (_: Exception) {
                // ignore（RN 同）
            }
        }
    }

    // RN useFocusEffect：进页拉成员 + 角色
    LaunchedEffect(rid, roomType, sdk) {
        loadMembers()
        fetchRoles()
    }

    fun memberRolesOf(memberId: String): List<String> = findRoomMemberRoles(roomRolesRaw, memberId)
    val currentUserRoles = memberRolesOf(currentUserId.orEmpty())

    // 服务端权限判定（RN useCanRemoveRoomMember）：hasRoomPermission 纯函数 → 派生值直算，
    // roomRolesRaw 异步到达后自动重算（effect+state 会读进页时的过期空角色，评审 Bug-1）
    val canRemoveFromRoom = !currentUserId.isNullOrEmpty() && cn.appia.im.core.permissions.hasRoomPermission(
        "remove-user",
        currentUserRoles,
        globalRoles,
        permissions["remove-user"]?.takeIf { it.isNotEmpty() }?.let { mapOf("remove-user" to it) },
    )

    var actionSheet by remember { mutableStateOf<List<Pair<String, () -> Unit>>?>(null) }
    var alert by remember { mutableStateOf<String?>(null) }
    var depRemoveTarget by remember { mutableStateOf<Pair<String, String>?>(null) } // (depId, name)
    var bulkConfirm by remember { mutableStateOf(false) }

    val hasBulkSelection = selectedUserIds.value.isNotEmpty() || selectedDepIds.value.isNotEmpty()

    fun showMemberActionSheet(member: MemberRow, allowRemoveFromRoom: Boolean) {
        val memberRoles = memberRolesOf(member._id)
        val items = buildMemberActionSheetItems(
            member = member,
            memberRoles = memberRoles,
            currentUserRoles = currentUserRoles,
            globalRoles = globalRoles,
            permissions = permissions,
            allowRemoveFromRoom = allowRemoveFromRoom,
            onSendMessage = { member.username?.let { u -> onSendMessage(u, member._id) } },
            onToggleOwner = {
                scope.launch {
                    try {
                        val isOwner = "owner" in memberRolesOf(member._id)
                        RoomSettingsApi.postToggleRoomOwner(sdk!!, rid, roomType, member._id, !isOwner)
                        fetchRoles()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
            },
            onToggleModerator = {
                scope.launch {
                    try {
                        val isMod = "moderator" in memberRolesOf(member._id)
                        RoomSettingsApi.postToggleRoomModerator(sdk!!, rid, roomType, member._id, !isMod)
                        fetchRoles()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
            },
            onRemoveFromRoom = {
                scope.launch {
                    try {
                        RoomSettingsApi.postRemoveUserFromRoom(sdk!!, rid, roomType, member._id)
                        loadMembers()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
            },
        )
        actionSheet = items.map { context.t(it.labelKey) to it.onPress }
    }

    fun toggleUserSelection(member: MemberRow) {
        if (!canRemoveMemberRow(member, memberRolesOf(member._id))) return
        selectedUserIds.value = selectedUserIds.value.toMutableSet().apply {
            if (!add(member._id)) remove(member._id)
        }
    }

    fun toggleDepSelection(depId: String) {
        selectedDepIds.value = selectedDepIds.value.toMutableSet().apply {
            if (!add(depId)) remove(depId)
        }
    }

    fun handleBulkRemove() {
        if (!hasBulkSelection || sdk == null) return
        scope.launch {
            try {
                cn.appia.im.core.chat.bulkRemoveRoomMembers(
                    sdk, rid, roomType,
                    selectedUserIds.value.toList(), selectedDepIds.value.toList(),
                )
                onBack() // RN navigation.goBack()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                alert = context.t("roomMembers_actionFailed")
            }
        }
    }

    Column(Modifier.fillMaxSize().background(colors.backgroundColor)) {
        RoomHeader(
            title = if (isRemoveMode) context.t("roomMembers_removeTitle") else context.t("roomMembers_title"),
            onBack = onBack,
            // remove 模式头部右侧移除钮（RN navigation.setOptions headerRight）
            headerAction = if (isRemoveMode) {
                {
                    Text(
                        context.t("roomMembers_removeAction"),
                        color = if (hasBulkSelection) CheckboxBlue else CheckboxDisabled,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable(enabled = hasBulkSelection) { bulkConfirm = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("qa-roommembers-remove-action"),
                    )
                }
            } else {
                null
            },
        )

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(
                        context.t("roomMembers_loading"),
                        Modifier.padding(top = 8.dp),
                        color = Color(0xFF8E8E93),
                        fontSize = 14.sp,
                    )
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(listRows, key = { i, row ->
                    when (row) {
                        is RoomMembersListRows.OrgHeader -> "org:${row.org}:$i"
                        is RoomMembersListRows.User -> "user:${row.user._id}:$i"
                        is RoomMembersListRows.Department -> "dep:${row.department._id}:$i"
                    }
                }) { _, row ->
                    when (row) {
                        is RoomMembersListRows.OrgHeader -> OrgHeaderRow(row.org)
                        is RoomMembersListRows.User -> MemberItem(
                            member = row.user,
                            serverUrl = serverUrl,
                            currentUserId = currentUserId,
                            token = token,
                            roomMemberRoles = memberRolesOf(row.user._id),
                            removeMode = isRemoveMode,
                            checked = row.user._id in selectedUserIds.value,
                            showCheckbox = isRemoveMode && canRemoveMemberRow(
                                row.user, memberRolesOf(row.user._id),
                            ),
                            onCheckboxPress = { toggleUserSelection(row.user) },
                            onPress = {
                                if (isRemoveMode && canRemoveMemberRow(
                                        row.user, memberRolesOf(row.user._id),
                                    )
                                ) {
                                    toggleUserSelection(row.user)
                                } else {
                                    row.user.username?.trim()?.takeIf { it.isNotEmpty() }
                                        ?.let(onOpenMemberProfile)
                                }
                            },
                            onLongPress = {
                                if (!isRemoveMode) showMemberActionSheet(row.user, allowRemoveFromRoom = true)
                            },
                        )
                        is RoomMembersListRows.Department -> DepartmentGroup(
                            department = row.department,
                            removeMode = isRemoveMode,
                            checked = row.department._id in selectedDepIds.value,
                            onDepartmentLongPress = if (!isRemoveMode && canRemoveFromRoom) {
                                { depRemoveTarget = row.department._id to row.department.name }
                            } else {
                                null
                            },
                            onDepartmentCheckboxPress = { toggleDepSelection(row.department._id) },
                            serverUrl = serverUrl,
                            currentUserId = currentUserId,
                            token = token,
                            roomMemberRolesOf = ::memberRolesOf,
                            onMemberPress = { member ->
                                // 部门块成员无 checkbox（RN showCheckbox=false）→ 恒走资料页
                                member.username?.trim()?.takeIf { it.isNotEmpty() }?.let(onOpenMemberProfile)
                            },
                            onMemberLongPress = { member ->
                                if (!isRemoveMode) showMemberActionSheet(member, allowRemoveFromRoom = false)
                            },
                        )
                    }
                }
            }
        }
    }

    // 成员/部门 ActionSheet（RN useActionSheet → Compose AlertDialog 列表形态）
    actionSheet?.let { items ->
        AlertDialog(
            onDismissRequest = { actionSheet = null },
            containerColor = Color.White,
            text = {
                Column {
                    items.forEach { (label, action) ->
                        Text(
                            label,
                            fontSize = 16.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    actionSheet = null
                                    action()
                                }
                                .padding(vertical = 14.dp)
                                .testTag("qa-roommembers-action-$label"),
                        )
                    }
                    Text(
                        context.t("roomMembers_cancel"),
                        fontSize = 16.sp,
                        color = AuxGray,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { actionSheet = null }
                            .padding(vertical = 14.dp),
                    )
                }
            },
            confirmButton = {},
        )
    }

    // 移除部门确认（RN Alert.alert :283-308）
    depRemoveTarget?.let { (depId, depName) ->
        AlertDialog(
            onDismissRequest = { depRemoveTarget = null },
            title = { Text(context.t("roomMembers_removeDepartment")) },
            text = {
                Text(context.t("roomMembers_removeDepartmentConfirm").replace("{{name}}", depName))
            },
            confirmButton = {
                TextButton(onClick = {
                    depRemoveTarget = null
                    scope.launch {
                        try {
                            RoomSettingsApi.postRemoveDepartmentFromRoom(sdk!!, rid, listOf(depId))
                            alert = context.t("roomMembers_removeDepartmentSuccess")
                            loadMembers()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            alert = context.t("roomMembers_actionFailed")
                        }
                    }
                }) { Text(context.t("roomMembers_removeDepartment"), color = colors.dangerColor) }
            },
            dismissButton = {
                TextButton(onClick = { depRemoveTarget = null }) { Text(context.t("roomMembers_cancel")) }
            },
        )
    }

    // 批量移除确认（RN Alert.alert :346-377）
    if (bulkConfirm) {
        AlertDialog(
            onDismissRequest = { bulkConfirm = false },
            title = { Text(context.t("roomMembers_removeTitle")) },
            text = { Text(context.t("roomMembers_removeConfirm")) },
            confirmButton = {
                TextButton(onClick = {
                    bulkConfirm = false
                    handleBulkRemove()
                }) { Text(context.t("roomMembers_removeAction"), color = colors.dangerColor) }
            },
            dismissButton = {
                TextButton(onClick = { bulkConfirm = false }) { Text(context.t("roomMembers_cancel")) }
            },
        )
    }

    alert?.let { msg ->
        AlertDialog(
            onDismissRequest = { alert = null },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { alert = null }) { Text(context.t("roomMembers_cancel")) }
            },
        )
    }
}

// ── 行组件 ──

@Composable
private fun OrgHeaderRow(org: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(LocalAppiaColors.current.auxiliaryBackground)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("qa-roommembers-org"),
    ) {
        Text(
            org,
            color = AuxGray,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun RoleBadge(role: cn.appia.im.core.chat.MemberRoleTag) {
    val context = LocalContext.current
    // RN roleConfig：owner→SP（橙）/admin→RP（蓝，label 用 roleModerator 同 key）
    val (bg, fg, key) = when (role) {
        cn.appia.im.core.chat.MemberRoleTag.OWNER -> Triple(BadgeOwnerBg, BadgeOwnerText, "roomMembers_roleOwner")
        cn.appia.im.core.chat.MemberRoleTag.ADMIN -> Triple(BadgeAdminBg, BadgeAdminText, "roomMembers_roleModerator")
    }
    Text(
        context.t(key),
        color = fg,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** RN MemberListItem：checkbox 列 + 头像 + 名 + 角色徽标；长按 ActionSheet / 点击资料页。 */
@Composable
private fun MemberItem(
    member: MemberRow,
    serverUrl: String,
    currentUserId: String?,
    token: String?,
    roomMemberRoles: List<String>,
    removeMode: Boolean,
    checked: Boolean,
    showCheckbox: Boolean,
    onCheckboxPress: () -> Unit,
    onPress: () -> Unit,
    onLongPress: () -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onPress, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("qa-roommembers-member-${member._id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (removeMode && showCheckbox) {
            // RN removeCheckbox：22dp 圆角 4 + 1.5px 边框 #c6c6c8；选中蓝底白勾
            Box(
                Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (checked) CheckboxBlue else Color.Transparent)
                    .border(1.5.dp, CheckboxDisabled, RoundedCornerShape(4.dp))
                    .clickable(onClick = onCheckboxPress),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) Text("✓", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        Box(
            Modifier
                .padding(start = if (removeMode && showCheckbox) 12.dp else 0.dp)
                .size(MEMBER_AVATAR_SIZE)
                .clip(RoundedCornerShape(MEMBER_AVATAR_SIZE / 2))
                .background(Color(0xFFE0E0E0)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                (member.name?.takeIf { it.isNotBlank() } ?: member.username ?: "?").trim().take(1).uppercase().ifEmpty { "?" },
                color = AuxGray,
                fontSize = 18.sp,
            )
            AsyncImage(
                model = memberAvatarUrl(serverUrl, member.username.orEmpty(), currentUserId, token),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Row(
            Modifier
                .weight(1f)
                .padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                member.name?.takeIf { it.isNotBlank() } ?: member.username ?: "Unknown",
                color = MemberNameDark,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.weight(1f).padding(0.dp))
            resolveMemberRoleTag(mergeMemberRoles(member.roles, roomMemberRoles))?.let { RoleBadge(it) }
        }
    }
}

/** RN RoomDepartmentMemberGroup：部门头（checkbox/长按移除/展开折叠）+ 展开成员行。 */
@Composable
private fun DepartmentGroup(
    department: DepartmentRow,
    removeMode: Boolean,
    checked: Boolean,
    onDepartmentLongPress: (() -> Unit)?,
    onDepartmentCheckboxPress: () -> Unit,
    serverUrl: String,
    currentUserId: String?,
    token: String?,
    roomMemberRolesOf: (String) -> List<String>,
    onMemberPress: (MemberRow) -> Unit,
    onMemberLongPress: (MemberRow) -> Unit,
) {
    val context = LocalContext.current
    var expanded by remember(department._id) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = if (removeMode) 0.dp else 16.dp, end = 16.dp)
                .testTag("qa-roommembers-dep-${department._id}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (removeMode) {
                // RN checkboxBox：同成员行复选框（22dp/1.5px 边框/蓝底白勾）
                Box(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .size(22.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (checked) CheckboxBlue else Color.Transparent)
                        .border(1.5.dp, CheckboxDisabled, RoundedCornerShape(4.dp))
                        .clickable(onClick = onDepartmentCheckboxPress),
                    contentAlignment = Alignment.Center,
                ) {
                    if (checked) Text("✓", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
            Row(
                Modifier
                    .weight(1f)
                    .combinedClickable(onClick = { expanded = !expanded }, onLongClick = { onDepartmentLongPress?.invoke() })
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // RN deptIcon 五类 PNG：Android 无同名资源，色块 + 类型字替代（视觉降级，行为等价）
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFE8F3FF)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        (department.type ?: "ORG").take(3).uppercase(),
                        color = BadgeAdminText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(
                        department.name,
                        color = MemberNameDark,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        context.t("team_totalCount").replace("{{count}}", department.members.size.toString()),
                        color = AuxGray,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Text(
                    if (expanded) "▾" else "▸",
                    color = AuxGray,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
        if (expanded) {
            department.members.forEach { member ->
                MemberItem(
                    member = member,
                    serverUrl = serverUrl,
                    currentUserId = currentUserId,
                    token = token,
                    roomMemberRoles = roomMemberRolesOf(member._id),
                    removeMode = false, // 部门块成员无 checkbox（RN showCheckbox=false）
                    checked = false,
                    showCheckbox = false,
                    onCheckboxPress = {},
                    onPress = { onMemberPress(member) },
                    onLongPress = { onMemberLongPress(member) },
                )
            }
        }
    }
}
