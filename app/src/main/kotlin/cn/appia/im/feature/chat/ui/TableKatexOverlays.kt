package cn.appia.im.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import cn.appia.im.core.i18n.t
import cn.appia.im.core.messaging.MdNode
import cn.appia.im.core.messaging.TableCell
import cn.appia.im.core.messaging.TableRow
import cn.appia.im.core.theme.LocalAppiaColors

/**
 * 表格全屏 overlay（RN MarkdownTableScreen 等价——Coordinator 分组 2 裁定：List&lt;MdNode&gt;
 * 非 Serializable 不可路由传参，取懒 overlay 形态）。Android 版 RN 布局：竖滚内嵌横滚，
 * cell 边框右/下 1px #00000026（末列/末行省略）、表头底 #2F343D0F + fontWeight 600。
 */
@Composable
internal fun MarkdownTableOverlay(
    rows: List<MdNode>,
    onClose: () -> Unit,
) {
    val colors = LocalAppiaColors.current
    FullScreenOverlayScrim(onClose = onClose) {
        Column(
            Modifier
                .fillMaxSize()
                .background(colors.backgroundColor)
                .testTag("qa-markdown-table-overlay"),
        ) {
            // 标题行（RN native-stack header 等价简化：标题 + 关闭）
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    LocalContext.current.t("markdown_viewfulltable"),
                    color = colors.titleText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "✕",
                    color = colors.auxiliaryText,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .clickable(onClick = onClose)
                        .padding(8.dp)
                        .testTag("qa-markdown-table-overlay-close"),
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Box(Modifier.horizontalScroll(rememberScrollState())) {
                    TableGridFull(rows)
                }
            }
        }
    }
}

/** 全屏 TableGrid（RN TableGrid/TableRow/TableCell：cell 独立边框，右/下 1px、末位省略）。 */
@Composable
private fun TableGridFull(rows: List<MdNode>) {
    val tableRows = rows.filterIsInstance<TableRow>()
    Column {
        tableRows.forEachIndexed { rowIndex, row ->
            val cells = row.value.filterIsInstance<TableCell>()
            val isLastRow = rowIndex == tableRows.lastIndex
            Row(Modifier.drawBehindBorders(isLastRow)) {
                cells.forEachIndexed { cellIndex, cell ->
                    val isLastCell = cellIndex == cells.lastIndex
                    val header = cell.isHeader == true
                    Box(
                        Modifier
                            .drawRightBorder(unless = isLastCell)
                            .background(if (header) MarkdownStyle.tableHeaderBg else Color.Transparent)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text(
                            cell.value.joinToString("\n") { plainTextFromTableBlock(it) },
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                            fontWeight = if (header) FontWeight.SemiBold else null,
                            color = LocalAppiaColors.current.bodyText,
                            modifier = Modifier.widthIn(max = 240.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 全屏 scrim：点击空白关闭（键盘/系统栏避让）。 */
@Composable
internal fun FullScreenOverlayScrim(
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(onClick = onClose)
            .padding(WindowInsets.safeDrawing.asPaddingValues()),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(enabled = false) {}, // 内容区不透传关闭
        ) {
            content()
        }
    }
}

/** 行底边框（末行省略——RN cell borderBottomWidth 语义）。 */
private fun Modifier.drawBehindBorders(isLastRow: Boolean): Modifier =
    if (isLastRow) this else this.drawBehind {
        val stroke = 1.dp.toPx()
        drawLine(MarkdownStyle.tableBorder, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), strokeWidth = stroke)
    }

/** cell 右边框（末列省略）。 */
private fun Modifier.drawRightBorder(unless: Boolean): Modifier =
    if (unless) this else this.drawBehind {
        val stroke = 1.dp.toPx()
        drawLine(MarkdownStyle.tableBorder, Offset(size.width - stroke / 2, 0f), Offset(size.width - stroke / 2, size.height), strokeWidth = stroke)
    }

// ── KaTeX 公式查看 overlay（RN MathView = react-native-math-view WebView；T13 接线）──

/**
 * 公式渲染 overlay：assets/mathview（react-native-math-view 的 Android web 构建产物拷贝——
 * MathJax 2.7 typeset-to-SVG bundle，与 RN 端同一渲染器）。协议（App.web.tsx）：
 * window message `{data: math}` → typeset → `ReactNativeWebView.postMessage(JSON.stringify({svg,...}))`。
 * RN MathView 渲染 SVG 本体；此处同源取 svg 注入（tint 正文色）。
 */
@Composable
internal fun KatexFormulaOverlay(
    math: String,
    onClose: () -> Unit,
) {
    val colors = LocalAppiaColors.current
    FullScreenOverlayScrim(onClose = onClose) {
        Column(
            Modifier
                .fillMaxSize()
                .background(colors.backgroundColor)
                .testTag("qa-katex-overlay"),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    math,
                    color = colors.titleText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "✕",
                    color = colors.auxiliaryText,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .clickable(onClick = onClose)
                        .padding(8.dp)
                        .testTag("qa-katex-overlay-close"),
                )
            }
            KatexMathWebView(
                math = math,
                textColor = colors.bodyText,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp),
            )
        }
    }
}

/** mathview WebView 宿主（Robolectric 占位 View——渲染断言归真机走查）。 */
@Composable
private fun KatexMathWebView(
    math: String,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            if ("robolectric".equals(android.os.Build.FINGERPRINT, ignoreCase = true)) {
                android.view.View(context)
            } else {
                android.webkit.WebView(context).apply {
                    settings.javaScriptEnabled = true
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    val tint = Integer.toHexString(textColor.toArgb()).takeLast(6)
                    // data URI 免 file 访问（allowFileAccess=false 红线）：bundle 以绝对路径互引，
                    // base64 内联 dist/bundle.js 后整页 loadData
                    val html = context.assets.open("mathview/index.html").bufferedReader().use { it.readText() }
                        .replace("./dist/bundle.js", "data:text/javascript;base64,${mathviewBundleB64(context)}")
                    val styled = html.replace(
                        "<div id=\"root\"></div>",
                        "<div id=\"root\"></div><style>svg{color:#$tint}</style>",
                    )
                    loadDataWithBaseURL("https://appia.local/", styled, "text/html", "utf-8", null)
                    webViewClient = object : android.webkit.WebViewClient() {
                        override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                            // math-view App.web.tsx 消息协议：{data: math} → MathJax typeset
                            val payload = org.json.JSONObject().put("data", math).toString()
                            view?.evaluateJavascript(
                                "window.dispatchEvent(new MessageEvent('message',{data:${kotlinx.serialization.json.JsonPrimitive(payload)}}));",
                                null,
                            )
                        }
                    }
                }
            }
        },
    )
}

private var cachedMathviewBundle: String? = null

private fun mathviewBundleB64(context: android.content.Context): String =
    cachedMathviewBundle ?: android.util.Base64.encodeToString(
        context.assets.open("mathview/dist/bundle.js").use { it.readBytes() },
        android.util.Base64.NO_WRAP,
    ).also { cachedMathviewBundle = it }
