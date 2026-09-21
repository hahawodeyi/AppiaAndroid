package cn.appia.im.feature.chat.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cn.appia.im.core.messaging.ListItem
import cn.appia.im.core.messaging.PlainText
import cn.appia.im.core.messaging.TableCell
import cn.appia.im.core.messaging.TableRow
import cn.appia.im.core.messaging.Paragraph
import cn.appia.im.core.theme.AppiaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T13 overlay UI 测试：scrim 点击关闭链（点空白关/点内容不关——Important-3 回归）、
 * KaTeX SVG 回传解析（Critical-2 纯函数面：bundle postMessage JSON → svg）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OverlayUiTest {

    @get:Rule
    val rule = createComposeRule()

    // ── KaTeX SVG 回传解析（bundle 协议 JSON.stringify({svg, width, height, ...})）──

    @Test
    fun `parse katex svg message extracts svg string`() {
        val raw = """{"svg":"<svg xmlns=\"http://www.w3.org/2000/svg\">E=mc^2</svg>","width":"10ex","height":"2ex"}"""
        assertEquals("<svg xmlns=\"http://www.w3.org/2000/svg\">E=mc^2</svg>", parseKatexSvgMessage(raw))
    }

    @Test
    fun `parse katex svg message rejects broken json empty svg or error payload`() {
        assertNull(parseKatexSvgMessage("{oops"))
        assertNull(parseKatexSvgMessage("""{"svg":""}"""))
        assertNull(parseKatexSvgMessage("""{"error":{"message":"math error"}}"""))
    }

    // ── scrim 关闭链（Important-3：内容区消费点击不透传）──

    @Test
    fun `scrim click closes katex overlay`() {
        var closed = false
        rule.setContent {
            AppiaTheme(isDark = false) { KatexFormulaOverlay(math = "E=mc^2", onClose = { closed = true }) }
        }
        // 占位 WebView（Robolectric）占内容区——点 overlay 根（内容区内任意点）不应透传
        rule.onNodeWithTag("qa-katex-overlay", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(false, closed) // 内容区消费点击：不关闭

        // 显式关闭按钮走 onClose
        rule.onNodeWithTag("qa-katex-overlay-close").performClick()
        rule.waitForIdle()
        assertEquals(true, closed)
    }

    @Test
    fun `table overlay content click does not close but close button does`() {
        var closed = false
        rule.setContent {
            AppiaTheme(isDark = false) {
                MarkdownTableOverlay(
                    rows = listOf(
                        TableRow(
                            value = listOf(
                                TableCell(isHeader = true, value = listOf(Paragraph(value = listOf(PlainText("H"))))),
                            ),
                        ),
                    ),
                    onClose = { closed = true },
                )
            }
        }
        rule.onNodeWithTag("qa-markdown-table-overlay", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(false, closed)

        rule.onNodeWithTag("qa-markdown-table-overlay-close").performClick()
        rule.waitForIdle()
        assertEquals(true, closed)
    }

    // ListItem 生产者形态渲染冒烟（Critical-3：直接内联不 Paragraph 包裹，Row 渲染不丢）
    @Test
    fun `list item with direct inline value renders row`() {
        rule.setContent {
            AppiaTheme(isDark = false) {
                MarkdownUnorderedList(
                    value = listOf(ListItem(value = listOf(PlainText("item one")))),
                    level = 0,
                )
            }
        }
        rule.onNodeWithText("item one").assertExists()
    }
}
