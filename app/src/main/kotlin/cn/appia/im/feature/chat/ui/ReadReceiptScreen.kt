package cn.appia.im.feature.chat.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.i18n.t
import cn.appia.im.core.network.api.ReadReceipt
import cn.appia.im.core.network.api.ReadReceiptUser
import cn.appia.im.core.network.api.ReadReceiptsApi
import cn.appia.im.core.network.api.RoomMembersGroup
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.theme.LocalAppiaColors
import coil3.compose.AsyncImage

/**
 * 已读 tab 数据（RN ReadReceiptScreen :90-93）：receipts 带 user 的列表（无 user 的丢弃）。
 */
internal fun readUsersFromReceipts(receipts: List<ReadReceipt>): List<ReadReceiptUser> =
    receipts.mapNotNull { it.user }

/**
 * 未读 tab 数据（RN ReadReceiptScreen :95-106）：成员分组拉平，排除自己（userId）与已读者。
 */
internal fun unreadMembers(
    groups: List<RoomMembersGroup>,
    readIds: Set<String?>,
    currentUserId: String?,
): List<ReadReceiptUser> = groups.flatMap { it.members }
    .filter { it._id != currentUserId && it._id !in readIds }

/**
 * 成员头像 URL（RN getTeamUserAvatarUri → getAvatarUrl direct 分支逐字）：
 * `/avatar/{username}?version=1&format=png&size={px}&rc_token=..&rc_uid=..`；无 username → null。
 */
private fun receiptAvatarUrl(
    server: String,
    username: String?,
    userId: String?,
    token: String?,
    sizePx: Int,
): String? {
    if (server.isBlank() || username.isNullOrBlank()) return null
    return buildString {
        append(server.trimEnd('/')).append("/avatar/").append(username.trim())
        append("?version=1&format=png&size=").append(sizePx)
        if (!userId.isNullOrEmpty() && !token.isNullOrEmpty()) {
            append("&rc_token=").append(token).append("&rc_uid=").append(userId)
        }
    }
}

/**
 * 已读回执明细（RN screens/ReadReceiptScreen 的最小版）：
 * 双 tab（已读=receipts / 未读=房间成员减已读者与本人），列表只显头像+名字（ts 不渲染）。
 * 进房拉一次；失败保留已有列表（RN catch 静默同义）。RN 下拉刷新未移植（Android 无现成
 * pull-refresh 基建，需时再加）。
 */
@Composable
fun ReadReceiptScreen(
    messageId: String,
    rid: String,
    userId: String?,
    sdk: RocketSdk,
    serverUrl: String,
    token: String?,
    onBack: () -> Unit,
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    var readView by remember { mutableStateOf(true) }
    var readList by remember { mutableStateOf(emptyList<ReadReceiptUser>()) }
    var unreadList by remember { mutableStateOf(emptyList<ReadReceiptUser>()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(messageId, rid) {
        try {
            val nextRead = readUsersFromReceipts(ReadReceiptsApi.getMessageReadReceipts(sdk, messageId))
            readList = nextRead
            val res = ReadReceiptsApi.getFederatedRoomMembers(sdk, rid)
            val members = if (res.success) res.data else emptyList()
            unreadList = unreadMembers(members, nextRead.map { it._id }.toSet(), userId)
        } catch (_: Exception) {
            // RN：失败时保留已有列表
        } finally {
            loaded = true
        }
    }

    val activeList = if (readView) readList else unreadList
    val density = LocalDensity.current
    val avatarSizePx = with(density) { 40.dp.roundToPx() }

    Column(Modifier.fillMaxSize().background(colors.backgroundColor)) {
        RoomHeader(title = context.t("readreceipt_details"), onBack = onBack)

        // tab 行（RN renderTab）：已读/未读 + 计数徽标
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            ReceiptTab(
                label = context.t("readreceipt_readtab"),
                count = readList.size,
                selected = readView,
                tag = "qa-read-receipt-tab-read",
                onClick = { readView = true },
                modifier = Modifier.weight(1f),
            )
            ReceiptTab(
                label = context.t("readreceipt_unreadtab"),
                count = unreadList.size,
                selected = !readView,
                tag = "qa-read-receipt-tab-unread",
                onClick = { readView = false },
                modifier = Modifier.weight(1f),
            )
        }

        if (activeList.isEmpty()) {
            if (loaded) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        context.t("readreceipt_empty"),
                        color = colors.auxiliaryText,
                        fontSize = 14.sp,
                        modifier = Modifier.testTag("qa-read-receipt-empty"),
                    )
                }
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize().background(colors.chatComponentBackground)) {
            itemsIndexed(activeList, key = { i, u -> u._id ?: "idx-$i" }) { _, user ->
                ReceiptListItem(
                    user = user,
                    avatarUrl = receiptAvatarUrl(serverUrl, user.username, userId, token, avatarSizePx),
                    name = user.name?.takeUnless { it.isEmpty() } ?: user.username.orEmpty(),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 66.dp)
                        .height(1.dp)
                        .background(colors.separatorColor),
                )
            }
        }
    }
}

@Composable
private fun ReceiptTab(
    label: String,
    count: Int,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppiaColors.current
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .testTag(tag)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (selected) colors.tintColor else colors.titleText,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
        Box(
            Modifier
                .padding(start = 6.dp)
                .clip(CircleShape)
                .background(if (selected) colors.tintColor else colors.borderColor)
                .padding(horizontal = 6.dp, vertical = 1.dp),
        ) {
            Text(
                "$count",
                color = if (selected) colors.backgroundColor else colors.auxiliaryText,
                fontSize = 11.sp,
            )
        }
    }
}

/** 单行：40dp 圆头像（initial 垫底 + Coil AsyncImage 鉴权 URL）+ presence 绿点 + 名字。RN ReceiptListItem 同构。 */
@Composable
private fun ReceiptListItem(user: ReadReceiptUser, avatarUrl: String?, name: String) {
    val colors = LocalAppiaColors.current
    val presence = presenceBadge(
        userId = user._id,
        username = user.username,
        fallbackStatus = null,
        avatarSize = 40.dp,
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("qa-read-receipt-item"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(colors.chatComponentBackground),
                contentAlignment = Alignment.Center,
            ) {
                Text(name.take(1).uppercase(), color = colors.auxiliaryText, fontSize = 15.sp)
            }
            if (avatarUrl != null) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(CircleShape),
                )
            }
            presence()
        }
        Text(
            name,
            color = colors.titleText,
            fontSize = 15.sp,
            maxLines = 1,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}
