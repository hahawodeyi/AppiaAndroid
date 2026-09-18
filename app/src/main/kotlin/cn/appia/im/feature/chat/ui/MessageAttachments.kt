package cn.appia.im.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.media.AttachmentUrlFormatter
import cn.appia.im.core.messaging.LocalAttachment
import coil3.compose.AsyncImage
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 消息内附件渲染（对照 appiaMobile MessageBody → MessageAttachments + LocalAttachmentPreviewList）：
 * - 本地形状（isLocalAttachmentShape）：行内上传预览（本地图缩略/视频黑块/文件卡），无点击导航；
 * - 服务端形状：图片网格（单图 200×150 适配、多图 90 格）→ 点击进图片预览页；视频黑块+▶ → 播放页；
 *   音频胶囊行 → 播放页（binding 裁定：音频点击也进播放页）；文件卡 → 文档预览页。
 * 点击路由经 [AttachmentNav]（RoomScreen 组装处映射导航）。
 */

/** 附件点击路由（RoomScreen → MainActivity 导航装配）。 */
sealed interface AttachmentNav {
    /** 图片预览页：本消息全部图片 + 起始下标（RN navigateToMediaPreview 的 M3 简化：页内相册）。 */
    data class Images(val images: List<ViewerImage>, val initialIndex: Int) : AttachmentNav

    /** 视频/音频播放页（同一 Media3 播放器）。 */
    data class Media(val url: String, val title: String?, val isAudio: Boolean) : AttachmentNav

    /** 文档预览页（PDF 直开 / office rooms.preview 轮询）。 */
    data class Doc(val params: DocPreviewParams) : AttachmentNav
}

/** RN styles：多图 90 格、本地图缩略 80。 */
private val MultiImageSize = 90.dp
private val LocalThumbSize = 80.dp

private val attachmentsJson = Json { ignoreUnknownKeys = true }

/** SendOrchestrator LocalAttachment 列解码（渲染端；编码在 SendOrchestrator.attachmentJson）。 */
internal fun decodeLocalAttachments(raw: String?): List<LocalAttachment> =
    runCatching {
        attachmentsJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(LocalAttachment.serializer()),
            raw.orEmpty(),
        )
    }.getOrDefault(emptyList())

/**
 * 附件分流入口（MessageRow 正文后挂载；RN MessageBody 内 MessageAttachments 挂位）。
 * @param onNav 附件点击路由回调（本地形状不触发）。
 */
@Composable
fun MessageAttachmentsNode(
    message: MessageEntity,
    currentUserId: String?,
    token: String?,
    serverUrl: String,
    onNav: (AttachmentNav) -> Unit,
    modifier: Modifier = Modifier,
) {
    val raw = message.attachments
    if (raw.isNullOrBlank()) return

    if (isLocalAttachmentShape(raw)) {
        LocalAttachmentRow(decodeLocalAttachments(raw), modifier)
        return
    }

    val attachments = remember(raw) { parseServerAttachments(raw) }
    if (attachments.isEmpty()) return

    val (images, others) = attachments.withIndex()
        .partition { attachmentKind(it.value) == AttachmentKind.IMAGE }
    val userId = currentUserId.orEmpty()
    val tk = token.orEmpty()
    val viewerImages = remember(raw, userId, tk, serverUrl) {
        buildViewerImages(attachments, userId, tk, serverUrl)
    }

    Column(modifier) {
        if (images.isNotEmpty()) {
            MessageImageGrid(images, viewerImages, onNav)
        }
        others.forEach { (_, att) ->
            when (attachmentKind(att)) {
                AttachmentKind.AUDIO -> MessageAudioNode(att, userId, tk, serverUrl, onNav)
                AttachmentKind.VIDEO -> MessageVideoNode(att, userId, tk, serverUrl, onNav)
                AttachmentKind.FILE -> MessageFileNode(att, userId, tk, serverUrl, onNav)
                // QUOTE（引用块 M3 另链）/ ACTIONS / COLLAPSIBLE_QUOTE 不在附件查看范围（RN 各有组件，报告披露）
                else -> Unit
            }
        }
    }
}

/** 图片网格（RN MessageImageRow：多图 90 方格 gap 6、单图 computeImageSize 适配）。 */
@Composable
private fun MessageImageGrid(
    images: List<IndexedValue<ParsedAttachment>>,
    viewerImages: List<ViewerImage>,
    onNav: (AttachmentNav) -> Unit,
) {
    val isMulti = images.size >= 2
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        images.forEachIndexed { localIndex, (_, att) ->
            val cell = if (isMulti) {
                Modifier.size(MultiImageSize)
            } else {
                val (w, h) = computeImageSize(att.imageDimensions)
                Modifier.width(w.dp).height(h.dp)
            }
            AsyncImage(
                model = viewerImages.getOrNull(localIndex)?.url,
                contentDescription = att.description ?: att.title,
                contentScale = ContentScale.Crop,
                modifier = cell
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFE5E5E5))
                    .clickable {
                        if (viewerImages.isNotEmpty()) {
                            onNav(AttachmentNav.Images(viewerImages, localIndex))
                        }
                    },
            )
        }
    }
}

/** 视频块（RN MessageVideo：200×150 黑块 + ▶ 圆；服务端 thumb_url 回推后垫底）。 */
@Composable
private fun MessageVideoNode(
    att: ParsedAttachment,
    userId: String,
    token: String,
    serverUrl: String,
    onNav: (AttachmentNav) -> Unit,
) {
    Box(
        Modifier
            .padding(top = 4.dp)
            .size(width = 200.dp, height = 150.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF333333))
            .clickable {
                buildViewerVideo(att, userId, token, serverUrl)?.let {
                    onNav(AttachmentNav.Media(it.url, it.title, isAudio = false))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val thumb = att.thumbUrl?.takeUnless { it.isEmpty() }
            ?.let { AttachmentUrlFormatter.format(it, userId, token, serverUrl) }
        if (!thumb.isNullOrEmpty()) {
            AsyncImage(
                model = thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 200.dp, height = 150.dp),
            )
        }
        PlayCircle()
    }
}

@Composable
private fun PlayCircle() {
    Box(
        Modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(Color(0x33FFFFFF)),
        contentAlignment = Alignment.Center,
    ) {
        Text("▶", color = Color.White, fontSize = 28.sp)
    }
}

/** 音频行（RN MessageAudio 胶囊外观简化：▶ + 进度条占位 + 00:00；点击进播放页，binding 裁定）。 */
@Composable
private fun MessageAudioNode(
    att: ParsedAttachment,
    userId: String,
    token: String,
    serverUrl: String,
    onNav: (AttachmentNav) -> Unit,
) {
    Row(
        Modifier
            .padding(top = 4.dp)
            .height(56.dp)
            .width(220.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFFF9F9F9))
            .clickable {
                val url = AttachmentUrlFormatter.format(att.audioUrl.orEmpty(), userId, token, serverUrl)
                if (url.isNotEmpty()) onNav(AttachmentNav.Media(url, att.title ?: att.name, isAudio = true))
            }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("▶", color = Color(0xFF0A84FF), fontSize = 24.sp)
        Box(
            Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFFE0E0E0)),
        )
        Text("00:00", color = Color(0xFF666666), fontSize = 14.sp)
    }
}

/** 文件卡（RN MessageFile：彩色类型方块 + 名称 + 大小；点击进文档预览页）。 */
@Composable
private fun MessageFileNode(
    att: ParsedAttachment,
    userId: String,
    token: String,
    serverUrl: String,
    onNav: (AttachmentNav) -> Unit,
) {
    val fileName = att.title?.takeUnless { it.isEmpty() }
        ?: att.name?.takeUnless { it.isEmpty() } ?: "File"
    val fileInfo = getFileInfo(fileName)
    val fileSize = att.size?.takeIf { it > 0 }
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                onNav(
                    AttachmentNav.Doc(
                        buildDocPreviewParamsFromFileLink(
                            title = fileName,
                            fileLink = att.titleLink,
                            fileUrl = null,
                            userId = userId,
                            token = token,
                            server = serverUrl,
                            attachmentAppiaBaseUrl = att.appiaBaseUrl,
                        ),
                    ),
                )
            }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileIconBadge(fileInfo)
        Column(Modifier.padding(start = 10.dp)) {
            Text(
                fileName,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (fileSize != null) {
                Text(formatFileSize(fileSize), fontSize = 12.sp, color = Color(0xFF888888))
            }
        }
    }
}

/** 类型方块（RN fileCardIconWrap：40dp 圆角 8 + 大写后缀标签）。 */
@Composable
private fun FileIconBadge(fileInfo: FileInfo) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(fileIconColor(fileInfo.color)),
        contentAlignment = Alignment.Center,
    ) {
        Text(fileInfo.label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** RN Color(string hex) 等价：复用 InlineNodes.parseHexColor（色表常量可信，解析失败回退灰）。 */
private fun fileIconColor(hex: String): Color = parseHexColor(hex) ?: Color(0xFFC7DADD)

/** 本地上传预览行（RN LocalAttachmentPreviewList：图 80 缩略 / 视频黑块 / 文件卡；无导航）。 */
@Composable
private fun LocalAttachmentRow(items: List<LocalAttachment>, modifier: Modifier = Modifier) {
    Row(
        modifier
            .padding(top = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFCCE6FF)),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { item ->
            when {
                item.type.startsWith("image/") -> AsyncImage(
                    model = File(item.localPath),
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .padding(2.dp)
                        .size(LocalThumbSize)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFF0F0F0)),
                )

                item.type.startsWith("video/") -> Box(
                    Modifier
                        .padding(4.dp)
                        .size(width = 200.dp, height = 150.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF333333)),
                    contentAlignment = Alignment.Center,
                ) { PlayCircle() }

                else -> Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    FileIconBadge(getFileInfo(item.name))
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(
                            item.name,
                            fontSize = 13.sp,
                            color = Color(0xFF333333),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        item.size?.takeIf { it > 0 }?.let {
                            Text(formatFileSize(it.toDouble()), fontSize = 11.sp, color = Color(0xFF888888))
                        }
                    }
                }
            }
        }
    }
}
