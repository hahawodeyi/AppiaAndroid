package cn.appia.im.feature.chat

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.theme.AppiaTheme
import cn.appia.im.feature.chat.ui.AttachmentNav
import cn.appia.im.feature.chat.ui.AttachmentViewerScreen
import cn.appia.im.feature.chat.ui.DocPreviewScreen
import cn.appia.im.feature.chat.ui.MessageRow
import cn.appia.im.feature.chat.ui.ViewerImage
import cn.appia.im.feature.chat.ui.buildDocPreviewParamsFromFileLink
import cn.appia.im.feature.chat.ui.buildViewerImages
import cn.appia.im.feature.chat.ui.parseServerAttachments

/**
 * M3 T7 附件查看 spike 调试入口（仅 debug 构建，am start 直启，同 EditorSpikeActivity 先例）：
 * - 消息行样例（图片网格/视频/音频/文件卡/本地上传形状）用**生产 MessageRow** 渲染；
 * - 点击走真实 [MessageRow.onAttachmentNav] → 真实 Viewer/Player/DocPreview 全屏页；
 * - 媒体 URL 指向 host 本地 HTTP 服务（adb reverse tcp:8123）——rc_uid/rc_token 由
 *   AttachmentUrlFormatter 补齐，server 端忽略 query 即可（等同 RN file-proxy 形状）。
 * 验收：图片 pinch/双击/Pager、视频 Media3 播放、PDF 打开翻页，截图+logcat 证据。
 */
class AttachmentViewerSpikeActivity : ComponentActivity() {

    /** 全屏页状态（null = 列表页）。 */
    private var viewer by mutableStateOf<AttachmentNav?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val base = "http://127.0.0.1:8123"
        setContent {
            AppiaTheme(isDark = false) {
                val target = viewer
                when (target) {
                    is AttachmentNav.Images -> AttachmentViewerScreen(
                        images = target.images,
                        initialIndex = target.initialIndex,
                        onBack = { viewer = null },
                    )
                    is AttachmentNav.Media -> cn.appia.im.feature.chat.ui.VideoPlayerScreen(
                        url = target.url,
                        title = target.title,
                        isAudio = target.isAudio,
                        onBack = { viewer = null },
                    )
                    is AttachmentNav.Doc -> DocPreviewScreen(
                        params = target.params,
                        // spike：无真实会话；SDK 仅 office 轮询使用（本走查不含 office，PDF 直开）
                        sdk = cn.appia.im.core.network.RocketSdk(),
                        onBack = { viewer = null },
                    )
                    null -> SpikeList(base)
                }
            }
        }
    }

    private fun onNav(nav: AttachmentNav) {
        Log.i(SPIKE_TAG, "attachment nav → $nav")
        viewer = nav
    }

    @Composable
    private fun SpikeList(base: String) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        ) {
            Text("T7 attachment viewer spike (local HTTP $base)")
            Button(
                onClick = { onNav(AttachmentNav.Images(spikeImages(base), 0)) },
                modifier = Modifier.padding(vertical = 4.dp),
            ) { Text("Open viewer directly (3-image pager)") }
            MessageRow(
                message = imageMessage("$base/file-upload/u1/f1/img1.png", "$base/file-upload/u1/f1/img2.png"),
                currentUserId = "me1",
                currentUsername = "me",
                serverUrl = base,
                token = "tk",
                onResend = {},
                onAttachmentNav = ::onNav,
            )
            MessageRow(
                message = videoMessage("$base/file-upload/u1/f1/clip.mp4"),
                currentUserId = "me1",
                currentUsername = "me",
                serverUrl = base,
                token = "tk",
                onResend = {},
                onAttachmentNav = ::onNav,
            )
            MessageRow(
                message = audioMessage("$base/file-upload/u1/f1/voice.mp3"),
                currentUserId = "me1",
                currentUsername = "me",
                serverUrl = base,
                token = "tk",
                onResend = {},
                onAttachmentNav = ::onNav,
            )
            MessageRow(
                message = fileMessage("$base/file-upload/u1/f1/report.pdf"),
                currentUserId = "me1",
                currentUsername = "me",
                serverUrl = base,
                token = "tk",
                onResend = {},
                onAttachmentNav = ::onNav,
            )
            MessageRow(
                message = fileMessage("$base/file-upload/u1/f1/sheet.xlsx"),
                currentUserId = "me1",
                currentUsername = "me",
                serverUrl = base,
                token = "tk",
                onResend = {},
                onAttachmentNav = ::onNav,
            )
            MessageRow(
                message = localUploadMessage(),
                currentUserId = "me1",
                currentUsername = "me",
                serverUrl = base,
                token = "tk",
                onResend = {},
                onAttachmentNav = ::onNav,
            )
        }
    }

    private fun spikeImages(base: String): List<ViewerImage> = buildViewerImages(
        parseServerAttachments(
            """[
                {"title":"img1","title_link":"$base/file-upload/u1/f1/img1.png"},
                {"title":"img2","title_link":"$base/file-upload/u1/f1/img2.png"},
                {"title":"img3","title_link":"$base/file-upload/u1/f1/img3.png"}
            ]""",
        ),
        "me1",
        "tk",
        base,
    )

    companion object {
        private const val SPIKE_TAG = "AttachmentSpike"

        private fun row(id: String, attachments: String): MessageEntity = MessageEntity(
            _id = id,
            msg = "attachment sample $id",
            rid = "spike-room",
            ts = System.currentTimeMillis().toDouble(),
            u = """{"_id":"me1","username":"me","name":"Spike"}""",
            alias = "",
            parse_urls = "[]",
            _updated_at = System.currentTimeMillis().toDouble(),
            status = 0.0, // SENT（不渲染状态徽标）
            mentions = "[]",
            attachments = attachments,
        )

        private fun imageMessage(u1: String, u2: String): MessageEntity = row(
            "img-msg",
            """[
                {"title":"img1","title_link":"$u1","image_url":"$u1","image_dimensions":{"width":1600,"height":1200}},
                {"title":"img2","title_link":"$u2","image_url":"$u2","image_dimensions":{"width":900,"height":900}}
            ]""",
        )

        private fun videoMessage(url: String): MessageEntity = row(
            "video-msg",
            """[{"title":"demo.mp4","video_url":"$url","type":"video/mp4"}]""",
        )

        private fun audioMessage(url: String): MessageEntity = row(
            "audio-msg",
            """[{"title":"voice.m4a","audio_url":"$url","type":"audio/m4a"}]""",
        )

        private fun fileMessage(link: String): MessageEntity = row(
            "file-msg",
            """[{"title":"${link.substringAfterLast('/')}","title_link":"$link","type":"file","file_size":237405}]""",
        )

        private fun localUploadMessage(): MessageEntity = row(
            "local-msg",
            """[{"id":"la1","name":"local.png","type":"image/png","size":12345,
                 "localPath":"/nonexistent/local.png","uploadStatus":"pending"}]""",
        )
    }
}
