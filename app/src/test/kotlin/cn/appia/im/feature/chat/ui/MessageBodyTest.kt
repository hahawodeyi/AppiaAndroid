package cn.appia.im.feature.chat.ui

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.messaging.BigEmoji
import cn.appia.im.core.messaging.Code
import cn.appia.im.core.messaging.CodeLine
import cn.appia.im.core.messaging.Emoji
import cn.appia.im.core.messaging.Heading
import cn.appia.im.core.messaging.HorizontalRule
import cn.appia.im.core.messaging.InlineKaTeX
import cn.appia.im.core.messaging.KaTeX
import cn.appia.im.core.messaging.Link
import cn.appia.im.core.messaging.LinkValue
import cn.appia.im.core.messaging.ListItem
import cn.appia.im.core.messaging.MdNode
import cn.appia.im.core.messaging.OrderedList
import cn.appia.im.core.messaging.Paragraph
import cn.appia.im.core.messaging.PlainText
import cn.appia.im.core.messaging.Quote
import cn.appia.im.core.messaging.Root
import cn.appia.im.core.messaging.TableCell
import cn.appia.im.core.messaging.TableRow
import cn.appia.im.core.messaging.UnorderedList
import cn.appia.im.core.messaging.resolveMdFromMsgFields
import cn.appia.im.core.messaging.resolveMessageMd
import cn.appia.im.core.theme.AppiaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 块级节点渲染 Compose 冒烟（对照 renderMarkdownBlock.tsx:37-73 分发表）：
 * 各块型可见性、AI 代码块语言标签+复制、表格预览 300dp 入口+点击回调、KaTeX 降级、
 * 空引用 permalink 段落剔除、resolveMessageMd stale 集成。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MessageBodyTest {

    @get:Rule
    val rule = createComposeRule()

    private fun setContent(root: Root?, aiCodeBlock: Boolean = false, onTableOpen: ((List<MdNode>) -> Unit)? = null, onKatexClick: ((String) -> Unit)? = null) {
        rule.setContent {
            AppiaTheme(isDark = false) {
                MessageBody(root, aiCodeBlock = aiCodeBlock, onTableOpen = onTableOpen, onKatexClick = onKatexClick)
            }
        }
    }

    @Test
    fun paragraphVisible() {
        setContent(resolveMdFromMsgFields(md = null, msg = "hello world"))
        rule.onNodeWithText("hello world").assertIsDisplayed()
    }

    @Test
    fun headingQuoteAndCodeVisible() {
        setContent(
            Root(
                listOf(
                    Heading(level = 2, value = listOf(PlainText("Title"))),
                    Quote(value = listOf(Paragraph(value = listOf(PlainText("quoted"))))),
                    Code(
                        language = "js",
                        value = listOf(CodeLine(PlainText("var x = 1;"))),
                    ),
                ),
            ),
        )
        rule.onNodeWithText("Title").assertIsDisplayed()
        rule.onNodeWithText("quoted").assertIsDisplayed()
        rule.onNodeWithText("var x = 1;").assertIsDisplayed()
    }

    @Test
    fun aiCodeBlockShowsLanguageAndCopy() {
        setContent(
            Root(
                listOf(
                    Code(language = "python", value = listOf(CodeLine(PlainText("print(1)")))),
                ),
            ),
            aiCodeBlock = true,
        )
        rule.onNodeWithText("python").assertIsDisplayed()
        // Robolectric 默认 en 资源
        rule.onNodeWithTag("qa-ai-code-copy").performClick()
        rule.onNodeWithText("Copied").assertIsDisplayed()
    }

    @Test
    fun listMarkersAndItemsVisible() {
        setContent(
            Root(
                listOf(
                    UnorderedList(
                        level = 0,
                        value = listOf(ListItem(value = listOf(PlainText("apple")))),
                    ),
                    OrderedList(
                        level = 0,
                        value = listOf(
                            ListItem(number = 1, value = listOf(PlainText("one"))),
                            ListItem(number = 2, value = listOf(PlainText("two"))),
                        ),
                    ),
                ),
            ),
        )
        rule.onNodeWithText("• ").assertIsDisplayed()
        rule.onNodeWithText("apple").assertIsDisplayed()
        rule.onNodeWithText("1) ").assertIsDisplayed()
        rule.onNodeWithText("2) ").assertIsDisplayed()
        rule.onNodeWithText("two").assertIsDisplayed()
    }

    @Test
    fun orderedMarkerCyclesByLevel() {
        // RN orderedMarker：level 0 → '1) '、level 1 → 'a) '、level 2 → 'I) '
        assertEquals("1) ", orderedMarker(0, 1))
        assertEquals("a) ", orderedMarker(1, 1))
        assertEquals("I) ", orderedMarker(2, 1))
        assertEquals("iv) ", orderedMarker(3, 4))
        // number 偏移：start=3 → '3) '
        assertEquals("3) ", orderedMarker(0, 3))
    }

    @Test
    fun bigEmojiAndHorizontalRuleVisible() {
        setContent(
            Root(
                listOf(
                    BigEmoji(value = listOf(Emoji(unicode = "😀"))),
                    HorizontalRule,
                    HorizontalRule,
                ),
            ),
        )
        rule.onNodeWithText("😀").assertIsDisplayed()
        rule.onAllNodesWithTag("markdown-horizontal-rule").assertCountEquals(2)
    }

    @Test
    fun tablePreviewWithOpenCallback() {
        val rows = listOf(
            TableRow(
                value = listOf(
                    TableCell(isHeader = true, value = listOf(Paragraph(value = listOf(PlainText("A"))))),
                    TableCell(isHeader = true, value = listOf(Paragraph(value = listOf(PlainText("B"))))),
                ),
            ),
        )
        var opened = false
        setContent(Root(listOf(Paragraph(value = listOf(PlainText("| A | B |")), subType = "TABLE", data = rows))), onTableOpen = { opened = true })
        rule.onNodeWithTag("markdown-table-preview").assertIsDisplayed()
        rule.onNodeWithText("A").assertIsDisplayed()
        rule.onNodeWithText("View full table").assertIsDisplayed()
        rule.onNodeWithTag("markdown-table-preview").performClick()
        assertTrue(opened)
    }

    @Test
    fun raggedTableRowsDoNotCrash() {
        // GFM 行 cells 数不齐（binding：不补齐）：后续行多于首行列数不崩（评审 Critical-2）
        val rows = listOf(
            TableRow(
                value = listOf(
                    TableCell(isHeader = true, value = listOf(Paragraph(value = listOf(PlainText("A"))))),
                ),
            ),
            TableRow(
                value = listOf(
                    TableCell(value = listOf(Paragraph(value = listOf(PlainText("a"))))),
                    TableCell(value = listOf(Paragraph(value = listOf(PlainText("b"))))),
                    TableCell(value = listOf(Paragraph(value = listOf(PlainText("c"))))),
                ),
            ),
        )
        setContent(Root(listOf(Paragraph(value = listOf(PlainText("| A |")), subType = "TABLE", data = rows))), onTableOpen = {})
        rule.onNodeWithText("A").assertIsDisplayed()
        rule.onNodeWithText("c").assertIsDisplayed()
    }

    @Test
    fun permalinkWithTrailingContentStillRenders() {
        // Paragraph.tsx:24-30 length===2 条件：[permalink, " ", 正文] 渲染正文（评审 Important-4）
        setContent(
            Root(
                listOf(
                    Paragraph(
                        value = listOf(
                            Link(
                                value = LinkValue(
                                    src = PlainText("https://x.com/msg=1"),
                                    label = emptyList(),
                                ),
                            ),
                            PlainText(" "),
                            PlainText("visible"),
                        ),
                    ),
                ),
            ),
        )
        rule.onNodeWithText("visible").assertIsDisplayed()
    }

    @Test
    fun katexDegradeRendersFormulaAndClick() {
        var clicked: String? = null
        setContent(
            Root(
                listOf(
                    KaTeX(value = " E=mc^2 "),
                    Paragraph(value = listOf(PlainText("text "), InlineKaTeX(value = "a+b"), PlainText(" end"))),
                ),
            ),
            onKatexClick = { clicked = it },
        )
        // 原式等宽降级（trim 后）
        rule.onNodeWithText("E=mc^2").assertIsDisplayed()
        rule.onNodeWithText("a+b").performClick()
        assertEquals("a+b", clicked)
    }

    @Test
    fun emptyPermalinkParagraphNotRendered() {
        // Paragraph.tsx:18-33：空 label LINK 单独成段（或仅跟空白）→ 整段不渲染
        val permalink = Link(
            value = LinkValue(
                src = PlainText("https://x.com/msg=1"),
                label = emptyList(),
            ),
        )
        setContent(
            Root(
                listOf(
                    Paragraph(value = listOf(permalink)),
                    Paragraph(value = listOf(PlainText("visible"))),
                ),
            ),
        )
        rule.onNodeWithText("visible").assertIsDisplayed()
    }

    @Test
    fun resolvesStaleMdFromMessageEntity() {
        // 集成：MessageEntity.md 过期（msg 为延长）→ 回退 parseMsgToMd 渲染编辑后文本
        val message = MessageEntity(
            _id = "m1", rid = "r1", ts = 1.0, u = "{}", alias = "", parse_urls = "[]",
            _updated_at = 1.0,
            msg = "hello edited",
            md = """[{"type":"PARAGRAPH","value":[{"type":"PLAIN_TEXT","value":"hello"}]}]""",
        )
        rule.setContent {
            AppiaTheme(isDark = false) { MessageBody(resolveMessageMd(message)) }
        }
        rule.onNodeWithText("hello edited").assertIsDisplayed()
    }

    // ── (edited) 标记两态（评审 Critical-3：有 md 只行内、无 md 只独立，不双渲染）──

    private fun editedEntity(md: String?) = MessageEntity(
        _id = "m1", rid = "r1", ts = 1.0, u = """{"_id":"u2","username":"bob"}""", alias = "",
        parse_urls = "[]", _updated_at = 1.0, msg = "body", md = md,
        edited_by = """{"_id":"u2"}""",
    )

    private fun setRow(message: MessageEntity) {
        rule.setContent {
            AppiaTheme(isDark = false) {
                MessageRow(
                    message = message,
                    currentUserId = "me",
                    currentUsername = "me",
                    serverUrl = "https://s1",
                    token = "tok",
                    onResend = {},
                )
            }
        }
    }

    @Test
    fun `edited with md renders inline tag only`() {
        setRow(
            editedEntity(
                md = """{"blocks":[{"type":"PARAGRAPH","value":[{"type":"PLAIN_TEXT","value":"body"}]}]}""",
            ),
        )
        // 行内尾随（正文 AnnotatedString 内）且无独立标记
        rule.onNodeWithText("body (Edited)").assertIsDisplayed()
        rule.onAllNodesWithTag("qa-message-edited-tag").assertCountEquals(0)
    }

    @Test
    fun `edited without md renders standalone tag only`() {
        setRow(editedEntity(md = null))
        // 独立标记存在；正文不含行内尾随
        rule.onAllNodesWithTag("qa-message-edited-tag").assertCountEquals(1)
        rule.onNodeWithText("body").assertIsDisplayed()
        rule.onNodeWithText("body (Edited)").assertDoesNotExist()
    }

    @Test
    fun `not edited renders no tag in either form`() {
        setRow(editedEntity(md = null).copy(edited_by = null))
        rule.onAllNodesWithTag("qa-message-edited-tag").assertCountEquals(0)
        rule.onNodeWithText("body (Edited)").assertDoesNotExist()
    }
}
