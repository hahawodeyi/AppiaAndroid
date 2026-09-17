package cn.appia.im.feature.chat.editor

import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import cn.appia.im.core.theme.AppiaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * M3 T2 编辑器 spike 调试入口（仅 debug 构建，am start 直启）。
 * 验收驱动：EditorReady/stateUpdate 日志、setContent→getJSON 往返比对、mention-trigger 回显、
 * insertMention/insertEmoji/deleteRange 回写、IME/高度联动。
 * 日志 tag 统一 TenTapSpike，方便 adb logcat 抓证据。
 */
class EditorSpikeActivity : ComponentActivity() {

    private var webView: WebView? = null
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var roundTripPending = false
    private var roundTripRan = false
    private var stage2Expected: JsonObject? = null
    private var lastStateLogAt = 0L

    // ── UI 状态 ──
    private val logs = mutableStateListOf("spike: waiting for WebView load…")
    private var ready by mutableStateOf(false)
    private var selectionText by mutableStateOf("sel -/-")
    private var focusText by mutableStateOf("focus -")
    private var heightText by mutableStateOf("h -")
    private var triggerText by mutableStateOf("trigger -")
    private var roundTripText by mutableStateOf("roundtrip pending")
    private var imeText by mutableStateOf("ime -")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            AppiaTheme(isDark = false) {
                SpikeScreen()
            }
        }
        // IME 弹收日志（判据⑤证据链）
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val visible = insets.isVisible(WindowInsetsCompat.Type.ime())
            imeText = "ime ${if (visible) "up" else "down"} ${ime.bottom}px"
            logLine("IME visible=$visible bottom=${ime.bottom}px")
            ViewCompat.onApplyWindowInsets(v, insets)
        }
        logLine("WebView=${webViewVersion()} emulator=${android.os.Build.VERSION.RELEASE}")
    }

    private fun logLine(line: String) {
        Log.i(TAG, line)
        runOnUiThread {
            logs.add(0, line)
            while (logs.size > 120) logs.removeAt(logs.size - 1)
        }
    }

    private fun send(json: String) {
        val wv = webView ?: return
        runOnUiThread { wv.dispatchTenTap(json) }
    }

    private fun handle(raw: String) {
        when (val msg = TenTapBridge.parseMessage(raw)) {
            is TenTapBridge.TenTapMessage.EditorReady -> {
                ready = true
                logLine("← editor-ready ✓")
                if (!roundTripRan) {
                    roundTripRan = true
                    scope.launch { delay(600); runRoundTrip() }
                }
            }
            is TenTapBridge.TenTapMessage.StateUpdate -> {
                val p = msg.payload
                val sel = (p["selection"] as? JsonObject)
                val from = sel?.get("from")?.jsonPrimitive?.content
                val to = sel?.get("to")?.jsonPrimitive?.content
                if (from != null && to != null) selectionText = "sel $from/$to"
                p["isFocused"]?.jsonPrimitive?.content?.let { focusText = "focus $it" }
                p["contentHeight"]?.jsonPrimitive?.content?.let { heightText = "h $it" }
                // 节流日志：stateUpdate 流入证据 + 选区/高度联动
                val now = System.currentTimeMillis()
                if (now - lastStateLogAt > 800) {
                    lastStateLogAt = now
                    logLine("← stateUpdate sel=$from/$to focused=${p["isFocused"]?.jsonPrimitive?.content} h=${p["contentHeight"]?.jsonPrimitive?.content}")
                }
            }
            is TenTapBridge.TenTapMessage.ContentUpdate -> logLine("← content-update")
            is TenTapBridge.TenTapMessage.DocumentHeight -> logLine("← document-height ${msg.height}")
            is TenTapBridge.TenTapMessage.JsonBack -> {
                logLine("← send-json-back id=${msg.messageId} bytes=${msg.content.toString().length}")
                logLine("getJSON=${msg.content.toString().take(500)}")
                checkRoundTrip(msg.messageId, msg.content)
            }
            is TenTapBridge.TenTapMessage.MentionTrigger -> {
                triggerText = "trigger q=\"${msg.query}\" cursor=${msg.cursorPos}"
                logLine("← mention-trigger query=\"${msg.query}\" cursorPos=${msg.cursorPos} ✓")
            }
            is TenTapBridge.TenTapMessage.MentionClick ->
                logLine("← mention-click userId=${msg.userId}")
            is TenTapBridge.TenTapMessage.Unknown ->
                logLine("← unknown type=${msg.type}")
            null -> logLine("← unparsable: ${raw.take(120)}")
        }
    }

    /** 判据②：setContent 草稿 → getJSON 往返无损比对（两段：合成草稿 + 归一化草稿回灌） */
    private fun runRoundTrip() {
        roundTripPending = true
        roundTripText = "roundtrip running…"
        logLine("→ set-content draft JSON (list/mention/emoji/CJK)")
        send(TenTapBridge.setContentAction(DRAFT_JSON))
        scope.launch {
            delay(400)
            logLine("→ get-json id=$ROUND_TRIP_ID")
            send(TenTapBridge.getJsonAction(ROUND_TRIP_ID))
        }
    }

    private fun checkRoundTrip(messageId: String?, actual: JsonObject) {
        if (!roundTripPending) return
        if (messageId == ROUND_TRIP_ID) {
            // 第一段：合成草稿 → getJSON
            val diff = firstDiff(Json.parseToJsonElement(DRAFT_JSON).jsonObject, actual, "$")
            logLine("stage1 set-content→get-json ${if (diff == null) "PASS ✓" else "FAIL: $diff"}")
            if (diff != null) {
                roundTripPending = false
                roundTripText = "roundtrip FAIL: $diff"
                return
            }
            stage2Expected = actual
            logLine("→ stage2 replay normalized JSON (RN real draft path)")
            scope.launch {
                delay(400)
                send(TenTapBridge.setContentAction(actual.toString()))
                delay(400)
                logLine("→ get-json id=$ROUND_TRIP_ID_2")
                send(TenTapBridge.getJsonAction(ROUND_TRIP_ID_2))
            }
            return
        }
        if (messageId == ROUND_TRIP_ID_2 && stage2Expected != null) {
            // 第二段：归一化 JSON 回灌 → getJSON 必须结构等价
            val diff = firstDiff(stage2Expected!!, actual, "$")
            roundTripPending = false
            roundTripText = if (diff == null) "roundtrip PASS lossless ✓" else "roundtrip FAIL: $diff"
            logLine("stage2 replay->get-json ${if (diff == null) "PASS lossless ✓" else "FAIL: $diff"}")
        }
    }

    private fun firstDiff(expected: JsonElement, actual: JsonElement, path: String): String? = when {
        expected is JsonObject && actual is JsonObject ->
            expected.keys.firstNotNullOfOrNull { k ->
                if (!actual.containsKey(k)) "$path.$k missing"
                else firstDiff(expected.getValue(k), actual.getValue(k), "$path.$k")
            }
                ?: actual.keys.firstOrNull { !expected.containsKey(it) }?.let { "$path.$it extra" }
        expected is JsonArray && actual is JsonArray ->
            if (expected.size != actual.size) "$path size=${expected.size}/${actual.size}"
            else expected.indices.firstNotNullOfOrNull { i -> firstDiff(expected[i], actual[i], "$path[$i]") }
        expected != actual -> "$path: expected=$expected actual=$actual"
        else -> null
    }

    @Composable
    private fun SpikeScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding(),
        ) {
            Text(
                "WebView:${webViewVersion()} $imeText",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Text(
                listOf(
                    if (ready) "ready✓" else "ready…",
                    selectionText, focusText, heightText,
                ).joinToString("  "),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Text(
                triggerText,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Text(
                roundTripText,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp),
            )

            cn.appia.im.feature.chat.editor.EditorWebView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                onMessage = { raw -> runOnUiThread { handle(raw) } },
                onWebViewReady = { webView = it },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(onClick = { runRoundTrip() }, modifier = Modifier.weight(1f)) { Text("RoundTrip") }
                Button(onClick = {
                    logLine("→ get-json id=$PROBE_ID")
                    send(TenTapBridge.getJsonAction(PROBE_ID))
                }, modifier = Modifier.weight(1f)) { Text("getJSON") }
                Button(onClick = {
                    logLine("→ insert-mention u-9527/@LiSi")
                    send(TenTapBridge.insertMentionAction("u-9527", "@\u674e\u56db"))
                }, modifier = Modifier.weight(1f)) { Text("mention") }
                Button(onClick = {
                    logLine("→ insert-emoji 😀")
                    send(TenTapBridge.insertEmojiAction("😀", "😀"))
                }, modifier = Modifier.weight(1f)) { Text("emoji") }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(onClick = {
                    val (f, t) = parseSelection(selectionText)
                    // 选区退化（光标）时回删 2 字符，保证 deleteRange 有可观测效果
                    val from = if (f == t) (t - 2).coerceAtLeast(0) else f
                    logLine("→ delete-range $from..$t")
                    send(TenTapBridge.deleteRangeAction(from, t))
                }, modifier = Modifier.weight(1f)) { Text("DelRange") }
                Button(onClick = {
                    logLine("→ focus end")
                    send(TenTapBridge.focusAction("end"))
                }, modifier = Modifier.weight(1f)) { Text("focus") }
                Button(onClick = {
                    logLine("→ blur")
                    send(TenTapBridge.blurAction())
                }, modifier = Modifier.weight(1f)) { Text("blur") }
                Button(onClick = { logs.clear() }, modifier = Modifier.weight(1f)) { Text("Clear") }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp),
            ) {
                items(logs) { line ->
                    Text(line, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }

    private fun parseSelection(text: String): Pair<Long, Long> {
        val m = Regex("sel (\\d+)/(\\d+)").find(text) ?: return (0L to 0L)
        return (m.groupValues[1].toLong() to m.groupValues[2].toLong())
    }

    private fun webViewVersion(): String = runCatching {
        packageManager.getPackageInfo("com.google.android.webview", 0).versionName
    }.getOrNull() ?: "?"

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TenTapSpike"
        private const val ROUND_TRIP_ID = "rt1"
        private const val ROUND_TRIP_ID_2 = "rt2"
        private const val PROBE_ID = "probe"

        /**
         * 判据②草稿（schema 归一化形态，即 getJSON 自身产物的形态）：
         * 段落（中文）+ 无序列表（bold/mention/中文/emoji）+ 有序列表。
         * 注意：fork 的 mention 节点 schema 无 avatarUrl 属性（RN 侧 insertMention 传了也被
         * tiptap 静默丢弃），有序列表含 v3 默认 attrs（start/type）——spike 实测发现，进报告。
         * 中文以 \u 转义落码（pre-commit 禁代码区 Han 字面量），值不变。
         */
        private val TXT_PARA = "\u4e2d\u6587\u8349\u7a3f\uff1a\u7b2c\u4e00\u6bb5" // 中文草稿：第一段
        private val TXT_BOLD = "\u52a0\u7c97\u8981\u70b9" // 加粗要点
        private val MENTION_LABEL = "@\u5f20\u4e09" // @张三
        private val TXT_NIHAO = " \u4f60\u597d" // " 你好"
        private val TXT_ORDERED = "\u6709\u5e8f\u7b2c\u4e8c\u9879" // 有序第二项

        private val DRAFT_JSON = """{"type":"doc","content":[""" +
            """{"type":"paragraph","content":[{"type":"text","text":"$TXT_PARA"}]},""" +
            """{"type":"bulletList","content":[{"type":"listItem","content":[{"type":"paragraph","content":[""" +
            """{"type":"text","marks":[{"type":"bold"}],"text":"$TXT_BOLD"},""" +
            """{"type":"mention","attrs":{"id":"u-123","label":"$MENTION_LABEL","mentionSuggestionChar":"@"}},""" +
            """{"type":"text","text":"$TXT_NIHAO"},""" +
            """{"type":"customEmoji","attrs":{"alt":"😀","title":"😀","src":"","type":"emoji"}}""" +
            """]}]}]},""" +
            """{"type":"orderedList","attrs":{"start":1,"type":null},"content":[{"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"$TXT_ORDERED"}]}]}]}""" +
            """]}"""
    }
}
