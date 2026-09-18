package cn.appia.im.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import cn.appia.im.core.theme.LocalAppiaColors

/**
 * 视频/音频播放页（binding 裁定 3：Media3 ExoPlayer + PlayerView，最简 props 对照 RN
 * react-native-video controls/resizeMode/paused）：进页即播（paused=false）、控制器系统样式
 * （controls=true）、resizeMode fit（PlayerView 默认）。音频复用同一引擎（PlayerView 无画面，
 * 仅控制器条）。URL 已由 AttachmentUrlFormatter 补 rc_uid/rc_token。
 */
@Composable
fun VideoPlayerScreen(
    url: String,
    title: String?,
    isAudio: Boolean,
    onBack: () -> Unit,
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true // RN paused=false：进页即播
        }
    }
    DisposableEffect(url) {
        onDispose { player.release() }
    }

    Box(Modifier.fillMaxSize().background(colors.previewBackground)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true // RN controls=true
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "←",
                color = colors.previewTintColor,
                fontSize = 22.sp,
                modifier = Modifier
                    .size(40.dp)
                    .clickable(onClick = onBack)
                    .testTag("qa-video-player-close"),
            )
            Text(
                title.orEmpty(),
                color = colors.previewTintColor,
                fontSize = 16.sp,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
