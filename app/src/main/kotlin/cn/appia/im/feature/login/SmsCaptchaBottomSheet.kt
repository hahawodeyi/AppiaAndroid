package cn.appia.im.feature.login

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.feature.login.ui.CaptchaWebView
import cn.appia.im.feature.login.ui.parseJsJson
import kotlinx.serialization.json.JsonElement
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val sheetJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

private val dangerColor = Color(0xFFF53F3F)

/** RN onMessage:44-55：JSON 解析失败（H5 非 JSON 心跳）不回调；裸字符串心跳按 JS JSON.parse 语义一并忽略。 */
private fun parseIc(raw: String): JsonElement? = parseJsJson(raw, sheetJson)

/**
 * 短信滑块验证码底部弹层（对照 RN components/SmsCaptchaBottomSheet/index.tsx）：
 * 标题 + 关闭 + WebView（`/verification/sms-login?locale=&t=`，每次点发码换 nonce 强制换 t）。
 * H5 滑块完成经 `ReactNativeWebView.postMessage` 回传 ic → `onIc` 关弹层并发码（RN handleSmsSheetIc）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsCaptchaBottomSheet(
    visible: Boolean,
    uri: String,
    title: String,
    closeLabel: String,
    onIc: (JsonElement) -> Unit,
    onRequestClose: () -> Unit,
) {
    if (!visible) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var loadError by remember { mutableStateOf<String?>(null) }
    // RN:40-42 visible/uri 变化时清错误横幅
    LaunchedEffect(uri) { loadError = null }

    ModalBottomSheet(
        onDismissRequest = onRequestClose,
        sheetState = sheetState,
        containerColor = Color.White,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onRequestClose) { Text(closeLabel) }
            }
            loadError?.let {
                Text(it, color = dangerColor, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
            }
            // RN:34-37 滑块条在页面下半部，取 max(420, 半屏) 上限 580
            val screenH = LocalConfiguration.current.screenHeightDp
            val webviewH = min(580, max(420, (screenH * 0.52f).roundToInt()))
            Box(Modifier.fillMaxWidth().height(webviewH.dp).padding(horizontal = 16.dp)) {
                CaptchaWebView(
                    uri = uri,
                    modifier = Modifier,
                    onMessage = { raw -> parseIc(raw)?.let(onIc) },
                    onWebViewError = { loadError = it },
                )
            }
        }
    }
}
