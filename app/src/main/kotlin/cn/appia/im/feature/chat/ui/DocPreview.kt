package cn.appia.im.feature.chat.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.i18n.t
import cn.appia.im.core.media.AttachmentDownloader
import cn.appia.im.core.media.MediaCache
import cn.appia.im.core.media.PreviewFailedException
import cn.appia.im.core.media.isSupportedPreviewFormat
import cn.appia.im.core.media.pollPreviewUrl
import cn.appia.im.core.media.resolvePreviewPdfUri
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.theme.LocalAppiaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 文档预览页（RN DocPreviewScreen + useFilePreview 的 M3 版）：
 * - 状态机：loading（3s 后 slow 文案 + 下载链接）→ preview（PDF）/ error / unsupported（白名单外）；
 *   RN 'localFile' 态不移植（Android 附件无 localFilePath 参数，报告披露）；
 * - PDF：白名单 pdf 或 office 轮询转换产物 → MediaCache 落盘 → PdfRenderer 自绘页
 *   （HorizontalPager 左右翻页 + 页面缩放复用 ZoomableImage，binding 裁定 3）；
 * - office（doc/xls/ppt/txt/html...）：rooms.preview 轮询 30 次 × 2s，code 0/1 转换中、2/200 就绪；
 * - 下载：DownloadManager → Download/appia + 通知（RN useDocPreviewDownload 语义）。
 */

/** useFilePreview 预览状态（'localFile' 不移植）。 */
private sealed interface DocPreviewState {
    data object Loading : DocPreviewState

    /** 加载超 3s（RN isSlow：文案换 slow + 可下载）。 */
    data object LoadingSlow : DocPreviewState
    data class Preview(val pdfFile: java.io.File) : DocPreviewState
    data class Error(val message: String) : DocPreviewState
    data object Unsupported : DocPreviewState
}

private const val SLOW_LOADING_THRESHOLD_MS = 3_000L

@Composable
fun DocPreviewScreen(
    params: DocPreviewParams,
    sdk: RocketSdk,
    onBack: () -> Unit,
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    var state by remember(params) { mutableStateOf<DocPreviewState>(DocPreviewState.Loading) }
    var downloading by remember { mutableStateOf(false) }

    fun download() {
        if (downloading) return // RN isDownloadingRef 防重入
        downloading = true
        Toast.makeText(context, context.t("docpreview_downloading"), Toast.LENGTH_SHORT).show()
        runCatching {
            AttachmentDownloader.enqueue(context, params.downloadUrl, params.title.ifBlank { context.t("docpreview_untitled") })
        }.onSuccess {
            Toast.makeText(context, context.t("docpreview_downloadcomplete"), Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, context.t("docpreview_downloadfailed"), Toast.LENGTH_SHORT).show()
        }
        downloading = false
    }

    // useFilePreview effect：状态机推进（loading→slow 3s；预览管线；卸载取消 = 协程取消）
    LaunchedEffect(params) {
        val slowJob = launch {
            kotlinx.coroutines.delay(SLOW_LOADING_THRESHOLD_MS)
            if (state is DocPreviewState.Loading) state = DocPreviewState.LoadingSlow
        }
        val result = runCatching { resolveDocPreviewPdf(params, sdk, context.cacheDir) }
        slowJob.cancel()
        result.fold(
            onSuccess = { file -> state = DocPreviewState.Preview(file) },
            onFailure = { e ->
                state = when {
                    e is PreviewFailedException -> DocPreviewState.Error(e.message ?: context.t("docpreview_previewfailed"))
                    e is UnsupportedOperationException -> DocPreviewState.Unsupported
                    else -> DocPreviewState.Error(context.t("docpreview_previewfailed"))
                }
            },
        )
    }

    Column(Modifier.fillMaxSize().background(colors.backgroundColor)) {
        // 头部（RN header：返回 + 标题 + 下载）
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "←",
                color = colors.titleText,
                fontSize = 22.sp,
                modifier = Modifier
                    .size(40.dp)
                    .clickable(onClick = onBack)
                    .testTag("qa-doc-preview-close"),
            )
            Text(
                params.title.ifBlank { context.t("docpreview_untitled") },
                color = colors.titleText,
                fontSize = 16.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(
                "↓",
                color = colors.tintColor,
                fontSize = 20.sp,
                modifier = Modifier
                    .size(40.dp)
                    .clickable(enabled = !downloading) { download() }
                    .testTag("qa-doc-preview-download"),
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (val s = state) {
                DocPreviewState.Loading, DocPreviewState.LoadingSlow -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        context.t(if (s is DocPreviewState.LoadingSlow) "docpreview_loadingslow" else "docpreview_loading"),
                        color = colors.auxiliaryText,
                        fontSize = 14.sp,
                    )
                    if (s is DocPreviewState.LoadingSlow) {
                        Text(
                            context.t("docpreview_download"),
                            color = colors.tintColor,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .clickable(enabled = !downloading) { download() }
                                .testTag("qa-doc-preview-slow-download"),
                        )
                    }
                }

                is DocPreviewState.Preview -> PdfPager(file = s.pdfFile)
                is DocPreviewState.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        s.message,
                        color = colors.auxiliaryText,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Box(
                        Modifier
                            .padding(top = 16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.tintColor)
                            .clickable(enabled = !downloading) { download() }
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                    ) {
                        Text(context.t("docpreview_download"), color = Color.White, fontSize = 14.sp)
                    }
                }

                DocPreviewState.Unsupported -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(context.t("docpreview_unsupportedformat"), color = colors.auxiliaryText, fontSize = 14.sp)
                    Box(
                        Modifier
                            .padding(top = 16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.tintColor)
                            .clickable(enabled = !downloading) { download() }
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                    ) {
                        Text(context.t("docpreview_download"), color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

/**
 * useFilePreview 管线：白名单外 → unsupported；pdf → downloadUrl 直取；
 * office → fileId 轮询（缺 fileId → error）→ resolvePreviewPdfUri 补鉴权 → MediaCache 落盘。
 * @return PDF 本地文件（PdfRenderer 输入）。
 */
internal suspend fun resolveDocPreviewPdf(params: DocPreviewParams, sdk: RocketSdk, cacheDir: java.io.File): java.io.File {
    if (!isSupportedPreviewFormat(params.fileType)) throw UnsupportedOperationException(params.fileType)

    val pdfUrl = if (params.fileType.lowercase() == "pdf") {
        params.downloadUrl
    } else {
        if (params.fileId.isBlank()) throw PreviewFailedException()
        val raw = pollPreviewUrl(fileId = params.fileId, preview = { _, retry ->
            sdk.get("rooms.preview", mapOf("id" to params.fileId, "retry" to retry.toString()))
        })
        // rooms.preview 地址可能是相对路径/缺鉴权参数，对齐 downloadUrl 补齐（RN resolvePreviewPdfUri）
        resolvePreviewPdfUri(raw, params.downloadUrl)
    }
    if (pdfUrl.isBlank()) throw PreviewFailedException()

    return MediaCache.fetch(cacheDir = cacheDir, url = pdfUrl)
}

/** PdfRenderer 自绘翻页（页位图懒渲染缓存；左右翻页 + 双指缩放复用 ZoomableImage）。 */
@Composable
private fun PdfPager(file: java.io.File) {
    val pages = remember(file) { mutableStateOf<List<Bitmap>>(emptyList()) }
    LaunchedEffect(file) {
        pages.value = withContext(Dispatchers.IO) { renderPdfPages(file) }
    }
    val list = pages.value
    if (list.isEmpty()) {
        CircularProgressIndicator()
        return
    }
    val pagerState = rememberPagerState { list.size }
    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        ZoomableImage {
            Image(
                bitmap = list[page].asImageBitmap(),
                contentDescription = "page ${page + 1}",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 渲染全部页位图（ponytail：整本一次性渲染，几百页大 PDF 会慢——分页懒渲染见报告升级路径）。 */
private fun renderPdfPages(file: java.io.File): List<Bitmap> = runCatching {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { renderer ->
            (0 until renderer.pageCount).map { i ->
                renderer.openPage(i).use { page ->
                    val bitmap = Bitmap.createBitmap(
                        page.width * 2,
                        page.height * 2,
                        Bitmap.Config.ARGB_8888,
                    )
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }
    }
}.getOrDefault(emptyList())
