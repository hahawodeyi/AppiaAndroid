package cn.appia.im.feature.voice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.api.ReadReceiptsApi
import cn.appia.im.core.network.api.ReadReceiptUser
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.core.voice.VoiceChatReceiver
import cn.appia.im.core.voice.lib.isVoiceMemberLockedForInvite
import cn.appia.im.domain.voice.VoiceSessionController
import cn.appia.im.feature.chat.ui.RoomHeader

/**
 * 选通话成员页（RN screens/VoiceMemberPickerScreen/index.tsx 516 行，裁定④裁剪为 start+invite 两态）：
 * - start：群聊发起（Task 12 房间附件菜单路由进入）——确认 = controller.startOutgoing(hoster)
 * - invite：通话中加人（overlay 邀请钮路由进入）——locked 行 isVoiceMemberLockedForInvite 展示但不可选，
 *   确认 = controller.inviteUsers(receivers) + onBack；进入时 setUiMinimized(true)（RN :134-138）
 * - 成员源：ReadReceiptsApi.getFederatedRoomMembers，flattenMembers/dedupe-by-username verbatim
 * - 搜索（username/name contains）+ 全选行（搜索空时）+ invite 态已选 chips 横排
 *
 * RN navigation.setOptions headerRight 确认钮 → AA RoomHeader headerAction 槽（CreateChannelMembersScreen 先例）。
 */
@Composable
fun VoiceMemberPickerScreen(
    rid: String,
    roomType: String,
    intent: String,
    controller: VoiceSessionController?,
    sdk: RocketSdk?,
    t: (String) -> String,
    onBack: () -> Unit,
) {
    val colors = LocalAppiaColors.current
    val isInvite = intent == "invite"

    var loading by remember { mutableStateOf(true) }
    var members by remember { mutableStateOf(listOf<PickerMember>()) }
    val selected = remember { mutableStateListOf<String>() }
    var searchText by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }

    // RN :134-138 invite 进入即最小化通话 overlay
    LaunchedEffect(isInvite) {
        if (isInvite) controller?.setUiMinimized(true)
    }

    // RN :140-160 load members
    LaunchedEffect(rid) {
        loading = true
        val rows = runCatching {
            ReadReceiptsApi.getFederatedRoomMembers(sdk!!, rid).let { res -> flattenMembers(res.data) }
        }.getOrDefault(emptyList())
        members = rows
        loading = false
    }

    val isMemberLocked: (String) -> Boolean = { username ->
        isInvite && isVoiceMemberLockedForInvite(controller?.store?.state?.value?.callMsg, username)
    }
    val listMembers = remember(members) { members.filter { !it.username.isNullOrBlank() } }
    val selectableMembers = remember(listMembers, isInvite, controller?.store?.state?.value?.callMsg) {
        listMembers.filter { !isMemberLocked(it.username!!) }
    }
    val displayedMembers = remember(listMembers, searchText) {
        val q = searchText.trim().lowercase()
        if (q.isEmpty()) listMembers
        else listMembers.filter {
            it.username?.lowercase()?.contains(q) == true || (it.name ?: "").lowercase().contains(q)
        }
    }
    val selectedMembers = remember(listMembers, selected) {
        listMembers.filter { selected.contains(it.username) }
    }
    val allSelectableSelected = selectableMembers.isNotEmpty() && selectableMembers.all { selected.contains(it.username) }

    fun toggle(username: String) {
        if (isMemberLocked(username)) return
        if (selected.contains(username)) selected.remove(username) else selected.add(username)
        if (searchText.isNotBlank()) searchText = ""
    }

    val canConfirm = !submitting && selected.isNotEmpty()

    Column(Modifier.fillMaxSize().background(if (isInvite) colors.backgroundColor else cn.appia.im.core.theme.Colors.bgTertiary)) {
        RoomHeader(
            title = "",
            onBack = onBack,
            headerAction = {
                val count = selected.size
                val label = (if (isInvite) "voice_memberpicker_invite" else "voice_memberpicker_start")
                    .let { key -> interpolateCount(t(key), count) }
                Text(
                    if (submitting) t("roommembers_loading") else label,
                    color = if (canConfirm) colors.tintColor else colors.tintDisabled,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .padding(end = 16.dp)
                        .clickable(enabled = canConfirm) {
                            submitting = true
                            val picked = listMembers.filter { selected.contains(it.username) && !isMemberLocked(it.username!!) }
                            val receivers = picked.map {
                                VoiceChatReceiver(receiver = it.username!!, receiverAppiaId = it._id, name = it.name ?: it.username!!)
                            }
                            if (isInvite) {
                                controller?.inviteUsers(receivers)
                                submitting = false
                                onBack()
                            } else {
                                controller?.startOutgoing(
                                    cn.appia.im.core.voice.VoiceChatHoster(roomId = rid, roomType = roomType, receivers = receivers),
                                )
                                submitting = false
                                onBack()
                            }
                        }
                        .testTag("qa-voice-memberpicker-confirm"),
                )
            },
        )

        if (isInvite) {
            // RN inviteSearchBox：chips 横排 + 输入
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.searchboxBackground)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectedMembers.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(selectedMembers, key = { it.key }) { m ->
                            Chip(member = m) { toggle(m.username!!) }
                        }
                    }
                } else {
                    Spacer(Modifier.width(8.dp))
                }
                SearchField(searchText, { searchText = it }, t = t)
            }
        } else {
            // RN startCard 搜索行
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.backgroundColor)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchField(searchText, { searchText = it }, Modifier.weight(1f), t)
            }
        }

        if (searchText.isBlank() && selectableMembers.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (allSelectableSelected) {
                            selectableMembers.forEach { selected.remove(it.username) }
                        } else {
                            selectableMembers.forEach { if (!selected.contains(it.username)) selected.add(it.username!!) }
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = allSelectableSelected, onCheckedChange = null)
                Text(t("voice_memberpicker_selectall"), Modifier.padding(start = 8.dp), color = colors.bodyText, fontSize = 15.sp)
            }
        }

        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(displayedMembers, key = { it.key }) { m ->
                    val username = m.username ?: ""
                    val locked = isMemberLocked(username)
                    val checked = locked || selected.contains(username)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !locked) { toggle(username) }
                            .background(colors.backgroundColor)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = if (locked) null else ({ toggle(username) }), enabled = !locked)
                        PeerAvatar(
                            username = username,
                            fallbackLabel = m.name ?: username,
                            size = 40,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                        Text(
                            m.name ?: username,
                            color = if (locked) colors.auxiliaryText else colors.bodyText,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
                item { if (displayedMembers.isNotEmpty()) Spacer(Modifier.height(20.dp)) }
            }
            if (displayedMembers.isEmpty() && !loading) {
                Text(
                    t("voice_memberpicker_empty"),
                    color = colors.bodyText,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.tintColor)
                }
            }
        }
    }
}

/** RN :31-48 flattenMembers：分组展平 + username 去重。 */
private data class PickerMember(
    val key: String,
    val _id: String?,
    val username: String?,
    val name: String?,
)

private fun flattenMembers(groups: List<cn.appia.im.core.network.api.RoomMembersGroup>): List<PickerMember> {
    val seen = mutableSetOf<String>()
    val rows = mutableListOf<PickerMember>()
    groups.forEach { group ->
        group.members.forEach { member: ReadReceiptUser ->
            val username = member.username?.trim()
            if (username.isNullOrEmpty() || seen.contains(username)) return@forEach
            seen.add(username)
            rows.add(PickerMember(key = member._id ?: username, _id = member._id, username = username, name = member.name))
        }
    }
    return rows
}

/** RN renderSelectedChip inviteChip（36 头像 + 名，点击反选）。 */
@Composable
private fun Chip(member: PickerMember, onClick: () -> Unit) {
    val colors = LocalAppiaColors.current
    Row(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(colors.chatComponentBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PeerAvatar(username = member.username ?: "", fallbackLabel = member.name ?: member.username ?: "", size = 36)
        Text(
            member.name ?: member.username ?: "",
            color = colors.bodyText,
            fontSize = 13.sp,
            maxLines = 1,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, t: (String) -> String) {
    val colors = LocalAppiaColors.current
    TextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(t("roomlist_searchplaceholder"), color = colors.auxiliaryText) },
        singleLine = true,
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = modifier,
    )
}

/** {{count}} 插值（RN t(key, {count})；TeamScreen interpolateCount 同款）。 */
private fun interpolateCount(template: String, count: Int): String = template.replace("{{count}}", count.toString())
