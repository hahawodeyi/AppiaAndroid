package cn.appia.im.feature.voice.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.R
import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoiceChatHoster
import cn.appia.im.core.voice.VoiceUser
import cn.appia.im.core.voice.lib.VoiceDisplayPeer
import cn.appia.im.core.voice.lib.compareVoiceParticipantsByState
import cn.appia.im.core.voice.lib.filterVoiceDisplayUsers
import cn.appia.im.core.voice.lib.isDirectVoiceRoom
import cn.appia.im.core.voice.lib.pickDirectVoicePeer
import cn.appia.im.core.voice.lib.resolveVoiceParticipantUiState
import kotlinx.coroutines.delay

/**
 * 通话页参与者中心（RN VoiceParticipantCenter/index.tsx 140 行 1:1）：
 * 单聊固定对方（pickDirectVoicePeer 防 userStatus 抖动闪布局）；群聊 filter+sort → 单视图/宫格
 * （≥3 列 3，2 列 2，>6 可滚动）。宫格单元：头像 + 名字 + 非接通置暗 + waiting 遮罩 + 静音/说话徽标。
 */
@Composable
internal fun VoiceParticipantCenter(
    users: List<VoiceUser>,
    roomType: String?,
    localUsername: String?,
    callMsg: VoiceCallMsg?,
    hoster: VoiceChatHoster? = null,
    waitingCallee: VoiceDisplayPeer? = null,
    t: (String) -> String,
) {
    val directPeer = if (isDirectVoiceRoom(roomType)) {
        pickDirectVoicePeer(users, localUsername, waitingCallee, callMsg, hoster)
    } else {
        null
    }
    if (directPeer != null) {
        SinglePeerView(peer = directPeer)
        return
    }

    val displayUsers = remember(users, localUsername, roomType) {
        filterVoiceDisplayUsers(users, localUsername, roomType)
            .sortedWith { a, b -> compareVoiceParticipantsByState(a, b) }
    }
    if (displayUsers.isEmpty()) {
        Box(Modifier.fillMaxSize())
        return
    }
    if (displayUsers.size == 1) {
        val u = displayUsers[0]
        SinglePeerView(peer = VoiceDisplayPeer(u.username ?: "", u.name.orEmpty().ifEmpty { u.username }.orEmpty()))
        return
    }

    val numColumns = if (displayUsers.size >= 3) 3 else 2
    // RN resolveParticipantGridLayout: avatar = (screenWidth - 20*2) / cols - 5*2（发现 7 动态宽）
    val avatarSize = ((LocalConfiguration.current.screenWidthDp - 40f) / numColumns - 10f).toInt()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(numColumns),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            userScrollEnabled = displayUsers.size > 6,
            horizontalArrangement = Arrangement.Center,
        ) {
            items(displayUsers, key = { it.username ?: "u-${it.uid}" }) { user ->
                ParticipantCell(user = user, avatarSize = avatarSize, t = t)
            }
        }
    }
}

/** RN VoiceSinglePeerView：146 头像 + 主名。显示名缺省回退 username（Task 2 minor 3 carry）。 */
@Composable
private fun SinglePeerView(peer: VoiceDisplayPeer) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PeerAvatar(username = peer.username, fallbackLabel = peer.name, size = 146)
        Text(
            peer.name,
            color = Color.White,
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/** RN VoiceParticipantAvatar（61 行）：头像 + dim + waiting 遮罩 + muted/speaking 徽标。 */
@Composable
private fun ParticipantCell(user: VoiceUser, avatarSize: Int, t: (String) -> String) {
    val uiState = remember(user) { resolveVoiceParticipantUiState(user) }
    val connected = uiState.kind == cn.appia.im.core.voice.lib.VoiceParticipantUiKind.Connected
    val showMuted = connected && user.isMuted == true
    val showSpeaking = connected && user.isMuted != true && user.isSpeaking == true

    Column(
        Modifier
            .padding(5.dp)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            PeerAvatar(
                username = user.username ?: "",
                fallbackLabel = user.name.orEmpty().ifEmpty { user.username }.orEmpty(),
                size = avatarSize,
                modifier = Modifier.alpha(if (connected) 1f else 0.45f),
            )
            if (uiState.showWaitingOverlay) {
                WaitingOverlay(size = avatarSize, label = t(uiState.labelKey))
            }
            if (showMuted) {
                // RN badgeMuted 20 白圆 + VoiceMutedBadgeIcon 15（#fa657a 麦克风斜杠，硬编码色透传 tint）
                Badge(color = Color.White, tag = "qa-voice-participant-muted") {
                    Icon(
                        painterResource(R.drawable.ic_appia_voice_muted_badge),
                        contentDescription = null,
                        tint = Color(0xFFFA657A),
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            if (showSpeaking) {
                // 说话声波 alpha 脉冲 0.4→1f 循环（RN 静态 VoiceSpeakingBadgeIcon 16 + badgeSpeaking 无底色——AA 动效 ruling 允许）
                val pulse by rememberInfiniteTransition(label = "speaking")
                    .animateFloat(
                        initialValue = 0.4f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
                        label = "pulse",
                    )
                Badge(color = Color.Transparent, tag = "qa-voice-participant-speaking", alpha = pulse) {
                    Icon(
                        painterResource(R.drawable.ic_appia_voice_speaking_badge),
                        contentDescription = null,
                        tint = Color(0xFF2DC888),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        Text(
            user.name.orEmpty().ifEmpty { user.username }.orEmpty(),
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
    }
}

/** RN Avatar direct 变体：initial 垫底（TeamScreen MemberAvatar 先例，无 presence 点）。 */
@Composable
internal fun PeerAvatar(
    username: String,
    fallbackLabel: String,
    size: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(size.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0xFF6C727A)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            fallbackLabel.trim().take(1).uppercase().ifEmpty { "?" },
            color = Color.White,
            fontSize = if (size >= 100) 34.sp else 26.sp,
        )
        // 头像 URL 需 serverUrl/token/userId——Task 12 装配缝（VoiceRoomHandle 注入前无源）。
        // ponytail: initial 垫底先行，Task 12 装 AsyncImage（teamAvatarUrl 公式已就绪）
    }
}

/** RN VoiceParticipantWaitingOverlay（49 行）：黑 55% 遮罩 + 3 点轮替 + 状态文案。 */
@Composable
private fun WaitingOverlay(size: Int, label: String) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(300)
            tick += 1
        }
    }
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0x8C000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.padding(bottom = 8.dp)) {
                repeat(3) { i ->
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .alpha(if (tick % 3 == i) 1f else 0.45f),
                    )
                }
            }
            Text(
                label,
                color = Color.White,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/** RN badgeMuted/badgeSpeaking 右下角标（BoxScope 内调用）。 */
@Composable
private fun BoxScope.Badge(color: Color, tag: String, alpha: Float = 1f, content: @Composable () -> Unit) {
    Box(
        Modifier
            .align(Alignment.BottomEnd)
            .padding(5.dp)
            .size(20.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
