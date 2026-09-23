package cn.appia.im.feature.roominfo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import cn.appia.im.core.chat.getAnnouncementPreviewText
import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.i18n.t
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.api.RoomSettingsApi
import cn.appia.im.core.network.api.RoomsApi
import cn.appia.im.core.network.api.SaveRoomSettingsParams
import cn.appia.im.core.permissions.PermissionsStore
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.feature.chat.RoomMemberRow
import cn.appia.im.feature.chat.parseAppiaRoomMembersV2
import cn.appia.im.feature.chat.ui.RoomHeader
import cn.appia.im.feature.roominfo.ROOM_USAGE_OPTIONS
import cn.appia.im.feature.roominfo.RoomInfoActions
import cn.appia.im.feature.roominfo.canEditRoomSettings
import cn.appia.im.feature.roominfo.canEditRoomUsage
import cn.appia.im.feature.roominfo.canRemoveRoomMember
import cn.appia.im.feature.roominfo.clearPendingSelfLeaveForRid
import cn.appia.im.feature.roominfo.markPendingSelfLeave
import cn.appia.im.feature.roominfo.memberDisplaySlots
import cn.appia.im.feature.roominfo.resolveAppiaUsageKeys
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// RN RoomInfoScreen/styles.ts 硬编码色（iOS 分组样式，不走主题色板）
private val SectionGray = Color(0xFF6D6D72)
private val CardWhite = Color.White
private val SepGray = Color(0xFFC6C6C8)
private val MemberNameDark = Color(0xFF333333)
private val AddAvatarBg = Color(0xFFF0F0F0)
private val AddAvatarBorder = Color(0xFFDDDDDD)
private val AddAvatarText = Color(0xFF999999)
private val MoreMembersText = Color(0xFF8E8E93)
private val LeaveText = Color(0xFF111111)

/** RN DirectAvatar 头像 URL（getTeamUserAvatarUri → avatar/{name} 鉴权参数，ChatRow.chatAvatarUrl 同构）。 */
private fun memberAvatarUrl(serverUrl: String, username: String, userId: String?, token: String?, sizePx: Int): String? {
    if (serverUrl.isBlank() || username.isBlank()) return null
    return buildString {
        append(serverUrl.trimEnd('/')).append("/avatar/").append(username)
        append("?version=1&format=png&size=").append(sizePx)
        if (!userId.isNullOrEmpty() && !token.isNullOrEmpty()) {
            append("&rc_token=").append(token).append("&rc_uid=").append(userId)
        }
    }
}

/** RN resolveDirectPeerUserId：uids JSON 数组取非本人 id；单人自聊/坏 JSON → null。 */
internal fun resolveDirectPeerUserId(uidsRaw: String?, currentUserId: String?): String? {
    if (uidsRaw.isNullOrEmpty() || currentUserId.isNullOrEmpty()) return null
    val arr = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(uidsRaw) as? JsonArray
    }.getOrNull() ?: return null
    if (arr.isEmpty()) return null
    if (arr.size == 1 && (arr[0] as? JsonPrimitive)?.contentOrNull == currentUserId) return null
    return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.firstOrNull { it != currentUserId }
}

/** RN isGroupChat（chatFields.ts:46-51）：uids 或 usernames 解析后 >2 人。 */
private fun isGroupChat(chat: ChatEntity): Boolean =
    parseIdsLen(chat.uids) > 2 || parseIdsLen(chat.usernames) > 2

private fun parseIdsLen(raw: String?): Int {
    if (raw.isNullOrEmpty()) return 0
    return (runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) }.getOrNull()
        as? JsonArray)?.size ?: 0
}

/** RN roomTitleFromChat（agentLabel 分支不进本屏——信息页标题用列表同款解析）。 */
internal fun roomInfoTitle(chat: ChatEntity): String =
    chat.fname.trim().ifEmpty { chat.dname?.trim().orEmpty().ifEmpty { chat.name.trim().ifEmpty { chat._id } } }

/**
 * 房间信息页（RN screens/RoomInfoScreen/index.tsx 全结构）：
 * - 非直聊：成员网格（≤20 槽含加减槽，`appia/room/members/v2` 复用 M3 parseAppiaRoomMembersV2）→
 *   加人（T8 选人器 addToRoom）/移除（T5 remove 模式）/更多成员（T5 list 模式）；
 *   信息卡（房名行→T7 改名【canEditRoom 门】/公告预览行→T6/会议行占位 M8）；设置卡（分类行仅 c|p
 *   【canEditRoomUsage 门】/静音/置顶 toggle）；退出按钮（markPendingSelfLeave → postLeaveRoom，失败清标记）。
 * - 直聊：对方头像卡（→ T6' MemberProfile）/+ 按钮（选人器 peer 预选）/会议行占位。
 * - mute/pin 乐观：pendingMute/pendingPin 覆盖 toggle 显示；mute 本地先写失败回滚（[RoomInfoActions]），
 *   pin 服务端成功才写 chats.f（RN roomInfoSettingsActions 同序）。
 *
 * T5/T6/T7/T8 路由未建——导航回调由装配处接线，缺省 no-op。全局角色经 [globalRoles] 注入
 * （RN authStore user.roles；装配处读 AuthSessionStore）。
 */
@Composable
fun RoomInfoScreen(
    rid: String,
    roomType: String,
    chat: ChatEntity?,
    currentUserId: String?,
    globalRoles: List<String>,
    serverUrl: String,
    token: String?,
    actions: RoomInfoActions?,
    onBack: () -> Unit,
    onAddMembers: (existingUsernames: List<String>) -> Unit = {},
    onRemoveMembers: () -> Unit = {},
    onMoreMembers: () -> Unit = {},
    onEditChannelName: () -> Unit = {},
    onOpenAnnouncement: () -> Unit = {},
    onOpenMemberProfile: (username: String) -> Unit = {},
    onDirectAddChannel: (peerUsername: String?) -> Unit = {},
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val isDirect = chat?.t == "d"
    val isPrivateChat = isDirect

    // 权限（T2 组合）：store map 实时收集（bootstrap permissions.listAll 已同步 + 流式更新）
    val permissions by PermissionsStore.permissions.collectAsState()
    val canEditRoom = remember(chat, permissions, globalRoles) {
        canEditRoomSettings(chat, globalRoles, permissions)
    }
    var canRemove by remember(rid, roomType) { mutableStateOf(false) }
    LaunchedEffect(rid, roomType, chat?.roles, permissions, isPrivateChat) {
        canRemove = if (actions == null || isPrivateChat) false
        else canRemoveRoomMember(actions.sdk, rid, roomType, currentUserId, globalRoles, permissions)
    }

    // 乐观 toggle 显示覆盖（RN pendingMute/pendingPin :80-81）
    var pendingMute by remember { mutableStateOf<Boolean?>(null) }
    var pendingPin by remember { mutableStateOf<Boolean?>(null) }
    var alert by remember { mutableStateOf<String?>(null) }
    var leaveConfirm by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var usageSaving by remember { mutableStateOf(false) }

    val muted = pendingMute ?: (chat?.hide_unread_status == true || chat?.disable_notifications == true)
    val pinned = pendingPin ?: (chat?.f == true)

    // 成员（RN fetchMembers：非直聊拉 appia/room/members/v2，失败保旧态）
    var members by remember { mutableStateOf<List<RoomMemberRow>>(emptyList()) }
    var memberSize by remember { mutableStateOf(0) }
    var membersLoading by remember { mutableStateOf(false) }
    LaunchedEffect(rid, isPrivateChat, actions) {
        if (isPrivateChat || actions == null) return@LaunchedEffect
        membersLoading = true
        try {
            val parsed = parseAppiaRoomMembersV2(RoomsApi.getAppiaRoomMembersV2(actions.sdk, rid))
            members = parsed
            memberSize = parsed.size
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // keep previous state（RN :107-109）
        } finally {
            membersLoading = false
        }
    }

    // RN showAdd/showRemove :94-96：非一对一私聊（直聊多人会话仍可加）
    val showAdd = !isPrivateChat && (!isDirect || (chat != null && isGroupChat(chat)))
    val showRemove = showAdd && canRemove
    val slots = memberDisplaySlots(showAdd, showRemove)
    val displayMembers = remember(members, slots) { members.take(slots) }
    val memberCount = if (memberSize > 0) memberSize else members.size

    // usage 分类（RN :398-399）
    val showCategoryRow = canEditRoomUsage(roomType)
    val usageKeys = remember(chat?.appiaUsage) { resolveAppiaUsageKeys(chat?.appiaUsage) }
    // RN formatRoomUsageDisplay join('、')——顿号 U+3001（i18n 钩子禁 Han 字面量，unicode 转义书写）
    val categoryValue = usageKeys.joinToString("\u3001") { context.t(it) }
    var usageDrawerVisible by remember { mutableStateOf(false) }

    val displayName = chat?.let { roomInfoTitle(it) }.orEmpty()
    // T7：公告预览解析（RN getAnnouncementPreviewText——文件分隔拆分/多公告取最新/HTML 剥离）
    val announcementPreview = remember(chat?.announcement, chat?.announcements) {
        getAnnouncementPreviewText(chat?.announcement, chat?.announcements)
    }

    Column(Modifier.fillMaxSize().background(colors.backgroundColor)) {
        RoomHeader(title = context.t("roominfo_title"), onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            if (!isPrivateChat) {
                SectionTitle(context.t("roominfo_members").replace("{{count}}", memberCount.toString()))
                MembersCard(
                    members = displayMembers,
                    loading = membersLoading,
                    showAdd = showAdd,
                    showRemove = showRemove,
                    serverUrl = serverUrl,
                    currentUserId = currentUserId,
                    token = token,
                    avatarSizePx = with(density) { 50.dp.roundToPx() },
                    onAdd = { onAddMembers(members.map { it.username }) },
                    onRemove = onRemoveMembers,
                    onMore = onMoreMembers,
                    onMember = onOpenMemberProfile,
                )
            }
            if (isDirect) {
                DirectAvatarCard(
                    displayName = displayName,
                    otherUsername = chat?.name.orEmpty(),
                    avatarUrl = memberAvatarUrl(
                        serverUrl, chat?.name.orEmpty(), currentUserId, token,
                        with(density) { 50.dp.roundToPx() },
                    ),
                    onOpenProfile = { chat?.name?.takeIf { it.isNotEmpty() }?.let(onOpenMemberProfile) },
                    onAdd = { onDirectAddChannel(chat?.name) },
                )
                Card(Modifier.padding(top = 16.dp)) {
                    MeetingRow(onOpen = {})
                }
            }
            if (!isDirect) {
                SectionTitle(context.t("roominfo_channelname"))
                Card {
                    DetailRow(
                        title = context.t("roominfo_channelname"),
                        value = displayName.ifEmpty { "—" },
                        showChevron = canEditRoom,
                        onClick = if (canEditRoom) onEditChannelName else null,
                        tag = "qa-roominfo-channelname",
                    )
                    Sep()
                    DetailRow(
                        title = context.t("roominfo_announcement"),
                        value = announcementPreview.ifEmpty { context.t("roominfo_announcementempty") },
                        placeholder = announcementPreview.isEmpty(), // RN valuePlaceholder：空值灰显
                        showChevron = true,
                        onClick = onOpenAnnouncement,
                        tag = "qa-roominfo-announcement",
                    )
                    Sep()
                    MeetingRow(onOpen = {})
                }
            }
            SectionTitle(context.t("roominfo_settings"))
            Card {
                if (showCategoryRow) {
                    DetailRow(
                        title = context.t("roominfo_category"),
                        value = categoryValue,
                        showChevron = canEditRoom,
                        onClick = if (canEditRoom) ({ usageDrawerVisible = true }) else null,
                        tag = "qa-roominfo-category",
                    )
                    Sep()
                }
                ToggleRow(
                    title = context.t("roominfo_mute"),
                    value = muted,
                    onValueChange = { value ->
                        pendingMute = value
                        scope.launch {
                            try {
                                actions?.setRoomMuted(rid, value)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                alert = e.message ?: e.toString()
                            } finally {
                                pendingMute = null
                            }
                        }
                    },
                )
                Sep()
                ToggleRow(
                    title = context.t("roominfo_pin"),
                    value = pinned,
                    onValueChange = { value ->
                        pendingPin = value
                        scope.launch {
                            try {
                                actions?.setRoomPinned(rid, value)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                alert = e.message ?: e.toString()
                            } finally {
                                pendingPin = null
                            }
                        }
                    },
                )
            }
            if (!isDirect) {
                Box(
                    Modifier
                        .padding(start = 16.dp, end = 16.dp, top = 24.dp)
                        .fillMaxWidth()
                        .background(CardWhite)
                        .clickable(enabled = !leaving && actions != null) { leaveConfirm = true }
                        .padding(vertical = 12.dp)
                        .testTag("qa-roominfo-leave"),
                    contentAlignment = Alignment.Center,
                ) {
                    if (leaving) CircularProgressIndicator(Modifier.size(20.dp))
                    else Text(context.t("roominfo_leavechannel"), color = LeaveText, fontSize = 16.sp)
                }
            }
        }
    }

    // 退出确认（RN Alert.alert 两钮 :161-180）
    if (leaveConfirm) {
        AlertDialog(
            onDismissRequest = { leaveConfirm = false },
            title = { Text(context.t("roominfo_leaveconfirm")) },
            text = { Text(context.t("roominfo_leaveconfirmmessage")) },
            confirmButton = {
                TextButton(onClick = {
                    leaveConfirm = false
                    leaving = true
                    scope.launch {
                        try {
                            markPendingSelfLeave(rid)
                            actions?.leaveRoom(rid, roomType)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            clearPendingSelfLeaveForRid(rid)
                            alert = e.message ?: e.toString()
                        } finally {
                            leaving = false
                        }
                    }
                }) { Text(context.t("roominfo_leavechannel")) }
            },
            dismissButton = {
                TextButton(onClick = { leaveConfirm = false }) { Text(context.t("settings_action_cancel")) }
            },
        )
    }

    alert?.let { msg ->
        AlertDialog(
            onDismissRequest = { alert = null },
            title = { Text(context.t("roominfo_leaveconfirm")) },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { alert = null }) { Text(context.t("common_close")) } },
        )
    }

    // 分类抽屉（RN RoomUsageSettingDrawer：单选互斥 toggle，确认 saveRoomSettings {appiaUsage}）
    if (showCategoryRow && canEditRoom && usageDrawerVisible) {
        RoomUsageDrawer(
            initialUsage = usageKeys,
            saving = usageSaving,
            onConfirm = { selected ->
                usageSaving = true
                scope.launch {
                    try {
                        actions?.setRoomUsage(rid, selected)
                        usageDrawerVisible = false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        alert = context.t("roomusage_savefailed")
                    } finally {
                        usageSaving = false
                    }
                }
            },
            onClose = { usageDrawerVisible = false },
        )
    }
}

// ── 行组件（RN SettingsRow/index.tsx 的 Compose 落法）──

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
        color = SectionGray,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(CardWhite),
    ) { content() }
}

@Composable
private fun Sep() {
    HorizontalDivider(thickness = 0.5.dp, color = SepGray, modifier = Modifier.padding(start = 16.dp))
}

/** RN SettingsDetailRow：标题 + 右值 + chevron（showChevron ?? onPress 存在）。 */
@Composable
private fun DetailRow(
    title: String,
    value: String,
    showChevron: Boolean,
    onClick: (() -> Unit)?,
    tag: String? = null,
    placeholder: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f, fill = false), color = MemberNameDark, fontSize = 15.sp)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            // RN rowValuePlaceholder：占位值灰显（实际值正常色）
            color = if (placeholder) MoreMembersText else SectionGray,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 4.dp),
        )
        if (showChevron) {
            Text("›", color = MoreMembersText, fontSize = 18.sp)
        }
    }
}

/** RN SettingsToggleRow：标题 + Switch。 */
@Composable
private fun ToggleRow(title: String, value: Boolean, onValueChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), color = MemberNameDark, fontSize = 15.sp)
        Switch(checked = value, onCheckedChange = onValueChange)
    }
}

/** RN renderMeetingRow（SettingsNavRow）：会议行占位（M8 接 RoomMeeting）。 */
@Composable
private fun MeetingRow(onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(contextTag("roominfo_meeting"), Modifier.weight(1f), color = MemberNameDark, fontSize = 15.sp)
        Text("›", color = MoreMembersText, fontSize = 18.sp)
    }
}

@Composable
private fun contextTag(key: String): String = LocalContext.current.t(key)

/** RN renderMembersGrid：FlowRow 20% 槽 + 加/减虚线槽 + 更多成员按钮。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MembersCard(
    members: List<RoomMemberRow>,
    loading: Boolean,
    showAdd: Boolean,
    showRemove: Boolean,
    serverUrl: String,
    currentUserId: String?,
    token: String?,
    avatarSizePx: Int,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onMore: () -> Unit,
    onMember: (String) -> Unit,
) {
    val context = LocalContext.current
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(CardWhite),
    ) {
        FlowRow(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.padding(16.dp).testTag("qa-roominfo-members-loading"))
            } else {
                members.forEach { member ->
                    Column(
                        Modifier
                            .fillMaxWidth(0.2f)
                            .clickable {
                                member.username.trim().takeIf { it.isNotEmpty() }?.let(onMember)
                            }
                            .testTag("qa-roominfo-member-${member._id}"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        MemberAvatar(
                            avatarUrl = memberAvatarUrl(serverUrl, member.username, currentUserId, token, avatarSizePx),
                            fallbackLabel = member.name ?: member.username,
                        )
                        Text(
                            member.name ?: member.username,
                            Modifier.padding(top = 4.dp),
                            color = MemberNameDark,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (showAdd) SlotAction(context.t("roominfo_addmember"), "+", onAdd, "qa-roominfo-add-member")
                if (showRemove) SlotAction(context.t("roominfo_removemember"), "−", onRemove, "qa-roominfo-remove-member")
            }
        }
        // 更多成员（RN moreMembersButton：顶部分隔线 + 居中文字）
        Box(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onMore)
                .testTag("qa-roominfo-more-members"),
            contentAlignment = Alignment.Center,
        ) {
            HorizontalDivider(thickness = 0.5.dp, color = SepGray, modifier = Modifier.matchParentSize())
            Text(
                context.t("roominfo_moremembers"),
                Modifier.padding(vertical = 12.dp),
                color = MoreMembersText,
                fontSize = 14.sp,
            )
        }
    }
}

/** RN renderSlotAction：虚线圆槽 + 符号 + 标签。 */
@Composable
private fun SlotAction(label: String, symbol: String, onPress: () -> Unit, tag: String) {
    Column(
        Modifier
            .fillMaxWidth(0.2f)
            .clickable(onClick = onPress)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(25.dp))
                .background(AddAvatarBg)
                .border(1.dp, AddAvatarBorder, RoundedCornerShape(25.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(symbol, color = AddAvatarText, fontSize = 24.sp)
        }
        Text(
            label,
            Modifier.padding(top = 4.dp),
            color = MemberNameDark,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 成员头像：initial 垫底 + AsyncImage（RN DirectAvatar fallbackLabel 同义）。 */
@Composable
private fun MemberAvatar(avatarUrl: String?, fallbackLabel: String) {
    Box(
        Modifier
            .size(50.dp)
            .clip(RoundedCornerShape(25.dp))
            .background(Color(0xFFE0E0E0)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            fallbackLabel.trim().take(1).uppercase().ifEmpty { "?" },
            color = SectionGray,
            fontSize = 18.sp,
        )
        AsyncImage(model = avatarUrl, contentDescription = null, modifier = Modifier.matchParentSize())
    }
}

/** RN renderDirectAvatar：对方头像+名（→ MemberProfile）+ 右侧 + 钮。 */
@Composable
private fun DirectAvatarCard(
    displayName: String,
    otherUsername: String,
    avatarUrl: String?,
    onOpenProfile: () -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        Modifier
            .padding(top = 16.dp)
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(CardWhite)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(
            Modifier
                .clickable(enabled = otherUsername.isNotEmpty(), onClick = onOpenProfile)
                .testTag("qa-roominfo-direct-peer"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MemberAvatar(avatarUrl = avatarUrl, fallbackLabel = displayName.ifEmpty { otherUsername })
            Text(
                displayName.ifEmpty { otherUsername },
                Modifier.padding(top = 4.dp),
                color = MemberNameDark,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(25.dp))
                .background(AddAvatarBg)
                .border(1.dp, AddAvatarBorder, RoundedCornerShape(25.dp))
                .clickable(onClick = onAdd)
                .testTag("qa-roominfo-direct-add"),
            contentAlignment = Alignment.Center,
        ) {
            Text("+", color = AddAvatarText, fontSize = 24.sp)
        }
    }
}

/** RN RoomUsageSettingDrawer：四选项单选互斥（点已选项清空）+ 取消/确认。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomUsageDrawer(
    initialUsage: List<String>,
    saving: Boolean,
    onConfirm: (List<String>) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(initialUsage.toList()) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = Color.White,
    ) {
        Column(Modifier.fillMaxWidth().testTag("qa-roominfo-usage-drawer")) {
            ROOM_USAGE_OPTIONS.forEach { item ->
                val checked = item in selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (checked) Color(0xFFF0F8FF) else Color(0xFFF3F3F3))
                        .clickable {
                            // RN handleToggle :38-40：includes → []（清空），否则 [item]（单选互斥）
                            selected = if (checked) emptyList() else listOf(item)
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag("qa-roominfo-usage-$item"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(context.t(item), Modifier.weight(1f), fontSize = 15.sp)
                    if (checked) Text("✓", color = Color(0xFF2878FF), fontSize = 16.sp)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    context.t("settings_action_cancel"),
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFF3F3F3))
                        .clickable(enabled = !saving, onClick = onClose)
                        .padding(vertical = 12.dp),
                    color = MemberNameDark,
                    fontSize = 15.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Text(
                    context.t("roomusage_confirm"),
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF2878FF))
                        .clickable(enabled = !saving) { onConfirm(selected) }
                        .padding(vertical = 12.dp)
                        .testTag("qa-roominfo-usage-confirm"),
                    color = Color.White,
                    fontSize = 15.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}
