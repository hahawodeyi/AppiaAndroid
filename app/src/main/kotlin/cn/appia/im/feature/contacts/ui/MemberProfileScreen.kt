package cn.appia.im.feature.contacts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import cn.appia.im.core.network.api.MemberProfileData
import cn.appia.im.core.network.api.buildResumeWebUrl
import cn.appia.im.core.network.api.fetchMemberProfile
import cn.appia.im.core.network.api.parseOtkrCanQuery
import cn.appia.im.core.network.api.parseOtkrDate
import cn.appia.im.core.network.api.parseOtkrQuery
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.feature.chat.ui.RoomHeader
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 成员名片页（RN screens/MemberProfileScreen/index.tsx + useMemberProfile + OtkrSection 逐结构）：
 * - 数据：users.info（userId 优先——三入口透传 _id；缺省（如 DM 头像行本地无 _id）走
 *   username 回退）
 * - 头像：getTeamUserAvatarUri 鉴权 URL（teamAvatarUrl 同构）
 * - 操作：发消息（openDirectMessage 链——装配处接线）/语音通话（**占位禁用 M10 接**）
 * - 个人信息行：email / supervisor（leaderNames[0]）/ 简历链接（canViewResume 门 → InAppWeb）
 * - POTA/OKR 节：otkr.canQuery 无权限不显示（占位入册——数据解析已备，渲染简化为只读层级）
 */
@Composable
fun MemberProfileScreen(
    username: String,
    userId: String?,
    sdk: RocketSdk?,
    serverUrl: String,
    currentUserId: String?,
    token: String?,
    currentUsername: String?,
    onBack: () -> Unit,
    onSendMessage: (username: String, displayName: String) -> Unit = { _, _ -> },
    onOpenWeb: (url: String, title: String) -> Unit = { _, _ -> },
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var profile by remember { mutableStateOf<MemberProfileData?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }

    // RN useMemberProfile effect：fetchMemberProfile(userId ?? username)
    LaunchedEffect(username, userId, sdk) {
        if (sdk == null) {
            loaded = true
            return@LaunchedEffect
        }
        try {
            val fetched = fetchMemberProfile(sdk, userId?.takeIf { it.isNotBlank() } ?: username)
            profile = fetched
            // M5-T3：users.info 已拿到 _id → 回写解析缓存（RN cachePresenceRcUserId :90）
            fetched?._id?.let { cn.appia.im.domain.presence.UsernameIdResolver.cacheRcUserId(username, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            profile = null // RN catch → setProfile(null)
        } finally {
            loaded = true
        }
    }

    val displayName = profile?.fname?.takeIf { it.isNotBlank() }
        ?: profile?.name?.takeIf { it.isNotBlank() }
        ?: profile?.username?.takeIf { it.isNotBlank() }
        ?: username
    val position = profile?.jobName.orEmpty()
    val department = profile?.primaryOrgName.orEmpty()
    val statusText = profile?.statusText.orEmpty()

    // RN personalFields：email / supervisor（leaderNames[0]）
    val personalFields = remember(profile) {
        buildList {
            profile?.emails?.firstOrNull()?.let { add("memberprofile_email" to it) }
            profile?.leaderNames?.firstOrNull()?.let { add("memberprofile_supervisor" to it) }
        }
    }
    val canViewResume = profile?.canViewResume == true
    val hasPersonalInfo = personalFields.isNotEmpty() || canViewResume

    Column(Modifier.fillMaxSize().background(colors.backgroundColor).testTag("qa-member-profile")) {
        RoomHeader(title = context.t("memberprofile_navtitle"), onBack = onBack)

        if (!loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.tintColor)
            }
            return@Column
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            // ── 头像卡片（RN heroCard：头像+姓名同行 / 岗位·部门 / 个性签名）──
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.focusedBackground)
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // M5-T3 presence 双路（RN useMemberProfile：直传/override id 优先 isRocketChatUserId
                    // 守卫，否则 profile._id 回写缓存经 resolver 命中；fallback=通讯录 status 三级原始串）
                    val presenceUserId = cn.appia.im.domain.presence.pickEffectivePresenceUserId(
                        userId?.takeIf { it.isNotBlank() },
                        username,
                        null,
                    ) ?: cn.appia.im.domain.presence.pickEffectivePresenceUserId(
                        profile?._id, username, null,
                    )
                    val fallbackStatus = cn.appia.im.domain.presence.mapContactStatusToTUserStatus(
                        cn.appia.im.domain.presence.pickContactPresenceRaw(
                            profile?.statusConnection, profile?.onlineStatus, profile?.status,
                        ),
                    )
                    val presence = cn.appia.im.feature.chat.ui.presenceBadge(
                        userId = presenceUserId,
                        username = username,
                        fallbackStatus = fallbackStatus,
                        avatarSize = 60.dp,
                    )
                    Box(
                        Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(30.dp))
                            .background(Color(0xFFE0E0E0)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            displayName.take(1).uppercase().ifEmpty { "?" },
                            color = colors.auxiliaryText,
                            fontSize = 22.sp,
                        )
                        AsyncImage(
                            model = teamAvatarUrl(
                                serverUrl, username, currentUserId, token,
                                with(density) { 80.dp.roundToPx() }, // RN size 80
                            ),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                        )
                        presence()
                    }
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(
                            displayName,
                            color = colors.titleText,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (position.isNotEmpty() || department.isNotEmpty()) {
                            Text(
                                listOf(position, department).filter { it.isNotEmpty() }
                                    .joinToString(" · "),
                                color = colors.auxiliaryText,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
                if (statusText.isNotEmpty()) {
                    Text(
                        "“$statusText”",
                        color = colors.auxiliaryText,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }

            // ── 操作按钮（RN actions：发消息 + 语音通话）──
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                ActionButton(
                    label = context.t("memberprofile_sendmessage"),
                    enabled = !sending,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("qa-member-profile-send"),
                    onClick = {
                        if (sending) return@ActionButton
                        sending = true
                        scope.launch {
                            try {
                                onSendMessage(username, displayName)
                            } finally {
                                sending = false
                            }
                        }
                    },
                )
                Spacer(Modifier.size(12.dp))
                // 语音通话：占位禁用（M10 语音域接线——brief 明示）
                ActionButton(
                    label = context.t("memberprofile_voicecall"),
                    enabled = false,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("qa-member-profile-voice"),
                    onClick = {},
                )
            }

            // ── 个人信息（RN personalFields + 简历行 canViewResume 门）──
            if (hasPersonalInfo) {
                SectionTitle(context.t("memberprofile_personalinfo"))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.focusedBackground),
                ) {
                    personalFields.forEachIndexed { i, (labelKey, value) ->
                        InfoRow(
                            label = context.t(labelKey),
                            value = value,
                            showSeparator = i < personalFields.size - 1 || canViewResume,
                        )
                    }
                    if (canViewResume) {
                        InfoRow(
                            label = context.t("memberprofile_resume"),
                            value = "",
                            showSeparator = false,
                        ) {
                            Row(Modifier.padding(top = 4.dp)) {
                                profile?.resumeDownloadUrl?.let { raw ->
                                    buildResumeWebUrl(raw)?.let { url ->
                                        LinkText(
                                            context.t("memberprofile_viewresume"),
                                            Modifier
                                                .testTag("qa-member-profile-resume")
                                                .clickable { onOpenWeb(url, context.t("memberprofile_viewresume")) },
                                        )
                                    }
                                }
                                profile?.profileUrl?.let { raw ->
                                    buildResumeWebUrl(raw)?.let { url ->
                                        if (profile?.resumeDownloadUrl != null) {
                                            Spacer(Modifier.size(16.dp))
                                        }
                                        LinkText(
                                            context.t("memberprofile_profile"),
                                            Modifier
                                                .testTag("qa-member-profile-profile")
                                                .clickable { onOpenWeb(url, context.t("memberprofile_profile")) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── POTA/OKR 节（RN OtkrSection：无权限不显示——占位入册）──
            if (sdk != null) {
                OtkrSection(
                    sdk = sdk,
                    viewer = currentUsername.orEmpty(),
                    owner = username,
                )
            }
        }
    }
}

/** RN styles actionBtn：tint 底白字圆角按钮；禁用降透明度。 */
@Composable
private fun ActionButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalAppiaColors.current
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) colors.tintColor else colors.tintDisabled)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = LocalAppiaColors.current.titleText,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** RN infoRow：label 左 value 右；showSeparator 控制底分隔线（RN hairline 逻辑）。 */
@Composable
private fun InfoRow(
    label: String,
    value: String,
    showSeparator: Boolean,
    content: (@Composable () -> Unit)? = null,
) {
    val colors = LocalAppiaColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                label,
                color = colors.auxiliaryText,
                fontSize = 14.sp,
                modifier = Modifier.size(width = 90.dp, height = 20.dp),
            )
            Column(Modifier.weight(1f)) {
                if (value.isNotEmpty()) {
                    Text(
                        value,
                        color = colors.titleText,
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                content?.invoke()
            }
        }
        if (showSeparator) {
            Box(
                Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(colors.borderColor),
            )
        }
    }
}

@Composable
private fun LinkText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = LocalAppiaColors.current.tintColor,
        fontSize = 14.sp,
        modifier = modifier,
    )
}

/**
 * POTA/OKR 节（RN OtkrSection.tsx 逐段）：canQuery 门（success && data 才放行，默认无权限
 * **不渲染任何节**——brief「无权限不显示」）→ otkr.date 月份 tab → otkr.query 层级渲染
 * （KO/KT/KR 徽标行）。占位入册：数据链全真，渲染为只读层级（无 tab 滚动动画/KO hidden
 * 行为——RN hidden 语义后续渲染迭代时对照补）。
 */
@Composable
private fun OtkrSection(
    sdk: cn.appia.im.core.network.RocketSdk,
    viewer: String,
    owner: String,
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    var visible by remember { mutableStateOf(false) }
    var tabs by remember { mutableStateOf<List<cn.appia.im.core.network.api.OtkrTab>>(emptyList()) }
    var activeKey by remember { mutableStateOf("") }
    var data by remember { mutableStateOf<List<cn.appia.im.core.network.api.OtkrOItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }

    // 权限检查（RN :41-56：仅 success && data 放行）
    LaunchedEffect(viewer, owner) {
        try {
            val raw = sdk.get("otkr.canQuery", mapOf("owner" to owner, "viewer" to viewer))
            visible = parseOtkrCanQuery(raw)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            visible = false
        }
    }

    // 月份 tab（RN :58-77）
    LaunchedEffect(visible, owner) {
        if (!visible) return@LaunchedEffect
        try {
            tabs = parseOtkrDate(sdk.get("otkr.date", mapOf("username" to owner)))
            activeKey = tabs.firstOrNull()?.key.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = true
        }
    }

    // 详情（RN :80-108：activeKey 变化重拉）
    LaunchedEffect(activeKey, owner) {
        if (!visible || activeKey.isEmpty()) return@LaunchedEffect
        loading = true
        error = false
        try {
            data = parseOtkrQuery(
                sdk.get("otkr.query", mapOf("username" to owner, "time" to activeKey)),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            data = emptyList()
            error = true
        } finally {
            loading = false
        }
    }

    if (!visible) return // 无权限不显示（brief：占位入册——节整体缺席）

    Column(Modifier.fillMaxWidth().testTag("qa-member-profile-otkr")) {
        SectionTitle(context.t("memberprofile_potatitle"))
        if (tabs.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScrollPadded(),
            ) {
                tabs.forEach { tab ->
                    val active = tab.key == activeKey
                    Text(
                        tab.label,
                        color = if (active) colors.tintColor else colors.auxiliaryText,
                        fontSize = 14.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .clickable { activeKey = tab.key }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.focusedBackground)
                .padding(16.dp),
        ) {
            when {
                loading && data.isEmpty() -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = colors.tintColor) }

                error && data.isEmpty() -> EmptyText(context.t("memberprofile_potanodata"))

                data.isEmpty() -> EmptyText(context.t("memberprofile_potanodata"))

                else -> data.forEach { o ->
                    OtkrItem(o)
                }
            }
        }
    }
}

@Composable
private fun EmptyText(text: String) {
    Text(
        text,
        color = LocalAppiaColors.current.auxiliaryText,
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
    )
}

/** KO/KT/KR 徽标行（RN renderKo/renderKt/renderKr：蓝/橙/蓝标签 + 缩进层级）。 */
@Composable
private fun OtkrItem(o: cn.appia.im.core.network.api.OtkrOItem) {
    val colors = LocalAppiaColors.current
    Column(Modifier.fillMaxWidth()) {
        OtkrBadgeRow(badge = "KO", text = o.data, badgeBg = Color(0xFFE8F0FE), badgeFg = Color(0xFF1967D2))
        o.items.forEach { t ->
            Column(Modifier.padding(start = 10.dp)) {
                OtkrBadgeRow(badge = "KT", text = t.data, badgeBg = Color(0xFFFCE8E4), badgeFg = Color(0xFFD93025))
                t.items.forEach { k ->
                    Column(Modifier.padding(start = 10.dp)) {
                        OtkrBadgeRow(badge = "KR", text = k.data, badgeBg = Color(0xFFE8F0FE), badgeFg = Color(0xFF1967D2), fontSize = 13)
                    }
                }
            }
        }
    }
}

@Composable
private fun OtkrBadgeRow(
    badge: String,
    text: String,
    badgeBg: Color,
    badgeFg: Color,
    fontSize: Int = 14,
) {
    val colors = LocalAppiaColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            badge,
            color = badgeFg,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(badgeBg)
                .padding(horizontal = 7.dp, vertical = 2.dp),
        )
        Text(
            text,
            color = colors.titleText,
            fontSize = fontSize.sp,
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
        )
    }
}

/** tab 横滚（RN horizontal ScrollView；无动画依赖的最小等价）。 */
@Composable
private fun Modifier.horizontalScrollPadded(): Modifier =
    this.horizontalScroll(rememberScrollState())
