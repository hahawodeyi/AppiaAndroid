package cn.appia.im.feature.chat.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastForEach
import coil3.compose.AsyncImage
import cn.appia.im.core.i18n.t
import cn.appia.im.core.media.AttachmentDownloader
import cn.appia.im.core.theme.LocalAppiaColors

/**
 * 图片缩放预览（RN ImageViewer + MediaPreviewScreen 的 M3 版，binding 裁定 2）：
 * - 自研 pinch 1-4x + 双击 2x 开关 + 缩放态拖移（平移夹边界防跑飞）；
 * - HorizontalPager 相册左右滑（本消息图片；RN 全房间相册 useRoomMediaGallery 归后续任务）；
 * - Coil 加载（AttachmentUrlFormatter URL；大图由 Coil 磁盘缓存承担，RN 200MB trim 不做对等）；
 * - 计数 `1 / N` + 保存（DownloadManager → Download/appia，RN saveImageToCameraRoll 语义）。
 */

/**
 * 单页缩放容器：pinch 1-4x、双击 2x 开关。
 * 手势接管判定：仅双指（捏合）或已放大（scale>1 拖移）时消费事件——单指未放大时不消费，
 * HorizontalPager 左右滑不受阻（RN ScrollView paging + Image zoom 同语义）。
 * @param onScaleChanged 缩放观测缝（UI 测试断言用；生产不传）。
 */
@Composable
fun ZoomableImage(
    modifier: Modifier = Modifier,
    onScaleChanged: (Float) -> Unit = {},
    content: @Composable () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    fun clampOffset() {
        if (scale <= 1f) {
            offset = Offset.Zero
            return
        }
        val maxX = size.width * (scale - 1f) / 2f
        val maxY = size.height * (scale - 1f) / 2f
        offset = Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier
            .testTag("qa-zoomable")
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var claimed = false
                    while (true) {
                        val event = awaitPointerEvent()
                        if (!claimed && (event.changes.size > 1 || scale > 1f)) {
                            claimed = true // 双指捏合或已放大：本页接管，Pager 让位
                        }
                        if (claimed) {
                            val next = (scale * event.calculateZoom()).coerceIn(1f, 4f)
                            offset += event.calculatePan()
                            if (next != scale) {
                                scale = next
                                onScaleChanged(next)
                            }
                            clampOffset()
                            event.changes.fastForEach { if (it.positionChanged()) it.consume() }
                        }
                        if (event.changes.fastAll { !it.pressed }) break
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2f
                    }
                    onScaleChanged(scale)
                })
            }
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    ) {
        content()
    }
}

/**
 * 图片预览页（RN MediaPreviewScreen 布局：黑底、顶部返回 + 计数 + 保存、Pager 页）。
 * @param images 已格式化图片列表（[buildViewerImages] 产出）；@param initialIndex 起始页。
 */
@Composable
fun AttachmentViewerScreen(
    images: List<ViewerImage>,
    initialIndex: Int,
    onBack: () -> Unit,
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (images.size - 1).coerceAtLeast(0)),
        pageCount = { images.size },
    )

    Box(Modifier.fillMaxSize().background(colors.previewBackground)) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val item = images.getOrNull(page)
            ZoomableImage {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AsyncImage(
                        model = item?.url,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // 顶栏：返回 + 计数 + 保存（RN headerTitle counter / headerRight save）
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
                    .testTag("qa-media-preview-close"),
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (images.isNotEmpty()) {
                    Text(
                        "${pagerState.currentPage + 1} / ${images.size}",
                        color = colors.previewTintColor,
                        fontSize = 16.sp,
                        modifier = Modifier.testTag("qa-media-preview-counter"),
                    )
                }
            }
            Text(
                "↓",
                color = colors.previewTintColor,
                fontSize = 22.sp,
                modifier = Modifier
                    .size(40.dp)
                    .clickable {
                        images.getOrNull(pagerState.currentPage)?.let { item ->
                            val name = item.url.substringBefore("?").substringAfterLast('/')
                                .ifEmpty { "image" }
                            runCatching { AttachmentDownloader.enqueue(context, item.url, name) }
                                .onSuccess {
                                    Toast.makeText(context, context.t("docpreview_downloading"), Toast.LENGTH_SHORT).show()
                                }
                                .onFailure {
                                    Toast.makeText(context, context.t("docpreview_downloadfailed"), Toast.LENGTH_SHORT).show()
                                }
                        }
                    }
                    .testTag("qa-media-preview-save"),
            )
        }
    }
}
