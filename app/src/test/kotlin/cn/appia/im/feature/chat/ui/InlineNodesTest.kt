package cn.appia.im.feature.chat.ui

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.messaging.Bold
import cn.appia.im.core.messaging.Emoji
import cn.appia.im.core.messaging.InlineCode
import cn.appia.im.core.messaging.InlineKaTeX
import cn.appia.im.core.messaging.Italic
import cn.appia.im.core.messaging.Link
import cn.appia.im.core.messaging.LinkValue
import cn.appia.im.core.messaging.MentionChannel
import cn.appia.im.core.messaging.MentionUser as MdMentionUser
import cn.appia.im.core.messaging.PlainText
import cn.appia.im.core.messaging.ResolvedEmoji
import cn.appia.im.core.messaging.Strike
import cn.appia.im.core.messaging.shortnameToUnicode
import cn.appia.im.core.theme.AppiaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 行内节点渲染（对照 Inline.tsx:41-89 + Bold/Italic/Strike/Link/InlineCode/Emoji/AtMention/Hashtag）：
 * 样式 span 组合、mention 三色（复用 resolveMentionDisplay）、表情表命中/miss/custom、
 * Bold 内子节点递归（RN 不递归缺口处断言 Android 正确渲染）、链接点击、深嵌套防炸。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class InlineNodesTest {

    @get:Rule
    val rule = createComposeRule()

    /** 色值取 AppiaColors 亮色板同值，断言与色板解耦。 */
    private val colors = InlineColors(
        base = Color(0xFF444444),
        link = Color(0xFF1D74F5),
        mentionMe = Color(0xFFF5455C),
        mentionGroup = Color(0xFFF38C39),
        mentionOther = Color(0xFFF3BE08),
    )

    private val mentions = listOf(
        MentionUser(_id = "u1", username = "bob", name = "Bob Jones"),
        MentionUser(_id = "u2", username = "carol", name = "  "),
    )

    private fun build(
        value: List<cn.appia.im.core.messaging.MdInline>,
        env: InlineEnv = InlineEnv(mentions = mentions, currentUsername = "me"),
        forceTrim: Boolean = false,
    ): InlineResult = buildInlineAnnotated(value, colors, env, forceTrim)

    /** 目标子串处叠加合并后的 SpanStyle（无 span 覆盖返回空样式）。 */
    private fun styleFor(result: InlineResult, target: String): SpanStyle {
        val start = result.annotated.text.indexOf(target)
        assertTrue("text missing: $target in ${result.annotated.text}", start >= 0)
        val end = start + target.length
        var style = SpanStyle()
        result.annotated.spanStyles
            .filter { it.start < end && it.end > start }
            .forEach { style = style.merge(it.item) }
        return style
    }

    // ── 基础样式（对照 styles.ts）──

    @Test
    fun `plain text concatenates without spans`() {
        val result = build(listOf(PlainText("hello"), PlainText(" world")))
        assertEquals("hello world", result.annotated.text)
        assertTrue(result.annotated.spanStyles.isEmpty())
    }

    @Test
    fun `bold applies font weight`() {
        val result = build(listOf(PlainText("hey "), Bold(value = listOf(PlainText("world")))))
        assertEquals(FontWeight.Bold, styleFor(result, "world").fontWeight)
        assertNull(styleFor(result, "hey ").fontWeight)
    }

    @Test
    fun `bold with color is font color not bold`() {
        // Bold.tsx isMarkdownBoldNode：带 color 的 BOLD 只套色不加粗
        val result = build(listOf(Bold(value = listOf(PlainText("red")), color = "#FF0000")))
        assertNull(styleFor(result, "red").fontWeight)
        assertEquals(Color(0xFFFF0000), styleFor(result, "red").color)
    }

    @Test
    fun `bold with size scales font size by plus two`() {
        val result = build(listOf(Bold(value = listOf(PlainText("big")), size = 20)))
        assertNull(styleFor(result, "big").fontWeight)
        assertEquals(22.sp, styleFor(result, "big").fontSize)
    }

    @Test
    fun `italic and strike apply their styles`() {
        val result = build(
            listOf(Italic(listOf(PlainText("i"))), Strike(listOf(PlainText("s")))),
        )
        assertEquals(FontStyle.Italic, styleFor(result, "i").fontStyle)
        assertEquals(TextDecoration.LineThrough, styleFor(result, "s").textDecoration)
    }

    @Test
    fun `nested bold italic accumulates styles`() {
        val result = build(listOf(Bold(value = listOf(Italic(listOf(PlainText("bi")))))))
        val style = styleFor(result, "bi")
        assertEquals(FontWeight.Bold, style.fontWeight)
        assertEquals(FontStyle.Italic, style.fontStyle)
    }

    @Test
    fun `link inside bold keeps bold weight`() {
        // RN 嵌套 Text 样式继承：Bold 内 Link 仍继承粗体，链接色只覆盖 color/underline
        val result = build(
            listOf(
                Bold(
                    value = listOf(
                        Link(LinkValue(src = PlainText("https://x.com"), label = listOf(PlainText("site")))),
                    ),
                ),
            ),
        )
        assertEquals("site", result.annotated.text)
        assertEquals(FontWeight.Bold, styleFor(result, "site").fontWeight)
    }

    @Test
    fun `inline code gets monospace background style`() {
        val result = build(listOf(PlainText("a "), InlineCode(PlainText("x=1"))))
        val style = styleFor(result, "x=1")
        assertEquals(FontFamily.Monospace, style.fontFamily)
        assertEquals(13.sp, style.fontSize)
        assertEquals(Color(0xFFF0F0F0), style.background)
    }

    @Test
    fun `inline katex degrades to monospace span`() {
        val result = build(listOf(PlainText("f="), InlineKaTeX(" a+b ")))
        assertEquals("f=a+b", result.annotated.text)
        assertEquals(FontFamily.Monospace, styleFor(result, "a+b").fontFamily)
    }

    // ── EMOJI（Emoji.tsx：unicode 直出；shortCode 查表 miss 回退 ':code:'；custom 图片）──

    @Test
    fun `emoji unicode rendered as text`() {
        val result = build(listOf(PlainText("hi "), Emoji(unicode = "😀")))
        assertEquals("hi 😀", result.annotated.text)
    }

    @Test
    fun `emoji shortname resolves via table`() {
        val result = build(listOf(Emoji(shortCode = "grinning", value = PlainText(":grinning:"))))
        assertEquals("😀", result.annotated.text)
    }

    @Test
    fun `emoji shortname miss keeps code token`() {
        val result = build(listOf(Emoji(shortCode = "nope", value = PlainText(":nope:"))))
        assertEquals(":nope:", result.annotated.text)
    }

    @Test
    fun `custom emoji resolver records inline content`() {
        val env = InlineEnv(
            getCustomEmoji = { if (it == "parrot") ResolvedEmoji.Custom("parrot", "png") else null },
            baseUrl = "https://x.com",
        )
        val result = build(listOf(Emoji(shortCode = "parrot", value = PlainText(":parrot:"))), env)
        assertEquals("parrot", result.customEmojis["parrot"]?.name)
    }

    @Test
    fun `custom emoji without base url falls back to table text`() {
        val env = InlineEnv(
            getCustomEmoji = { ResolvedEmoji.Custom("grinning", "png") },
            baseUrl = null,
        )
        val result = build(listOf(Emoji(shortCode = "grinning", value = PlainText(":grinning:"))), env)
        assertTrue(result.customEmojis.isEmpty())
        assertEquals("😀", result.annotated.text)
    }

    @Test
    fun `editor value-only emoji treated as short code`() {
        // RN Emoji.tsx 对编辑器 custom 形态（只带 value）直接丢弃；Android 按 shortCode 同口径（T3 缝一致）
        val env = InlineEnv(
            getCustomEmoji = { ResolvedEmoji.Custom("party-parrot", "gif") },
            baseUrl = "https://x.com",
        )
        val result = build(listOf(Emoji(value = PlainText("party-parrot"))), env)
        assertEquals("party-parrot", result.customEmojis.keys.single())
    }

    @Test
    fun `emoji with neither unicode nor code renders nothing`() {
        val result = build(listOf(PlainText("a"), Emoji(), PlainText("b")))
        assertEquals("ab", result.annotated.text)
    }

    // ── MENTION（AtMention.tsx + resolveMentionDisplay 共享 helper）──

    @Test
    fun `mention hit shows name without at in other color`() {
        val result = build(listOf(MdMentionUser(PlainText("bob"))))
        assertEquals("Bob Jones", result.annotated.text)
        val style = styleFor(result, "Bob Jones")
        assertEquals(colors.mentionOther, style.color)
        assertEquals(FontWeight.SemiBold, style.fontWeight)
    }

    @Test
    fun `mention self uses me color`() {
        val result = build(
            listOf(MdMentionUser(PlainText("bob"))),
            InlineEnv(mentions = mentions, currentUsername = "bob"),
        )
        assertEquals(colors.mentionMe, styleFor(result, "Bob Jones").color)
    }

    @Test
    fun `mention name blank falls back to username`() {
        val result = build(listOf(MdMentionUser(PlainText("carol"))))
        assertEquals("carol", result.annotated.text)
    }

    @Test
    fun `mention all and here keep group color raw label`() {
        val result = build(listOf(PlainText("@"), MdMentionUser(PlainText("all"))))
        assertEquals("@all", result.annotated.text)
        assertEquals(colors.mentionGroup, styleFor(result, "all").color)
    }

    @Test
    fun `unresolved mention shows at-username plain`() {
        val result = build(listOf(MdMentionUser(PlainText("zed"))))
        assertEquals("@zed", result.annotated.text)
        assertEquals(Color.Unspecified, styleFor(result, "@zed").color) // 无 mention 色 span
        assertNull(styleFor(result, "@zed").fontWeight)
    }

    @Test
    fun `mention channel renders hashtag with link color`() {
        val result = build(listOf(MentionChannel(PlainText("general"))))
        assertEquals("#general", result.annotated.text)
        assertEquals(colors.link, styleFor(result, "#general").color)
    }

    // ── Bold 内子节点递归（RN Bold.tsx 缺口修复，M2 裁定偏离入册）──

    @Test
    fun `bold containing mention renders with mention style`() {
        // RN Bold.tsx 只认 PLAIN_TEXT/LINK/STRIKE/ITALIC/BOLD：mention 被丢弃；Android 递归渲染。
        // RN 嵌套 Text 语义：mention span fontWeight 600 覆盖父级 700
        val result = build(listOf(Bold(value = listOf(MdMentionUser(PlainText("bob"))))))
        assertEquals("Bob Jones", result.annotated.text)
        val style = styleFor(result, "Bob Jones")
        assertEquals(colors.mentionOther, style.color)
        assertEquals(FontWeight.SemiBold, style.fontWeight)
    }

    @Test
    fun `bold containing emoji renders unicode`() {
        val result = build(listOf(Bold(value = listOf(Emoji(shortCode = "grinning")))))
        assertEquals("😀", result.annotated.text)
        assertEquals(FontWeight.Bold, styleFor(result, "😀").fontWeight)
    }

    // ── forceTrim（Inline.tsx:31-39 permalink 剔除）──

    @Test
    fun `force trim drops permalink and trims next plain`() {
        // Inline.tsx:31-39：首位 permalink 剔除；次位 PLAIN_TEXT trimStart（" " 整个消失）
        val permalink = Link(LinkValue(src = PlainText("https://x.com/msg=1"), label = emptyList()))
        val result = build(
            listOf(permalink, PlainText(" "), PlainText("visible")),
            forceTrim = true,
        )
        assertEquals("visible", result.annotated.text)
    }

    // ── 深嵌套防护 ──

    @Test
    fun `deep nesting does not crash and flattens beyond cap`() {
        var node: cn.appia.im.core.messaging.MdInline = PlainText("core")
        repeat(40) { node = Bold(value = listOf(node)) }
        val result = build(listOf(node))
        assertEquals("core", result.annotated.text)
    }

    // ── 表数据与 Compose 冒烟 ──

    @Test
    fun `shortname table lookup matches rn entries`() {
        assertEquals("👍", shortnameToUnicode(":+1:"))
        assertEquals(":definitely_missing:", shortnameToUnicode(":definitely_missing:"))
    }

    @Test
    fun `link click invokes callback`() {
        var clicked: String? = null
        rule.setContent {
            AppiaTheme(isDark = false) {
                InlineNodes(
                    listOf(Link(LinkValue(src = PlainText("https://x.com"), label = listOf(PlainText("site"))))),
                    InlineEnv(onLinkPress = { clicked = it }),
                )
            }
        }
        rule.onNodeWithText("site").performClick()
        assertEquals("https://x.com", clicked)
    }

    @Test
    fun `mixed paragraph renders mention emoji and plain`() {
        rule.setContent {
            AppiaTheme(isDark = false) {
                InlineNodes(
                    listOf(
                        PlainText("hey "),
                        MdMentionUser(PlainText("bob")),
                        PlainText(" "),
                        Emoji(unicode = "😀"),
                    ),
                    InlineEnv(mentions = mentions, currentUsername = "me"),
                )
            }
        }
        rule.onNodeWithText("hey Bob Jones 😀").assertExists()
    }

    @Test
    fun `custom emoji inline content renders image tag`() {
        rule.setContent {
            AppiaTheme(isDark = false) {
                InlineNodes(
                    listOf(Emoji(shortCode = "parrot", value = PlainText(":parrot:"))),
                    InlineEnv(
                        getCustomEmoji = { ResolvedEmoji.Custom("parrot", "png") },
                        baseUrl = "https://x.com",
                    ),
                )
            }
        }
        rule.onNodeWithTag("custom-emoji").assertExists()
    }

    @Test
    fun `message body paragraph renders via inline pipeline`() {
        // 组装冒烟：MessageBody 块级分发 → Paragraph → InlineNodes（BOLD 加粗文本仍在同串内）
        rule.setContent {
            AppiaTheme(isDark = false) {
                MessageBody(
                    cn.appia.im.core.messaging.Root(
                        listOf(
                            cn.appia.im.core.messaging.Paragraph(
                                value = listOf(Bold(value = listOf(PlainText("bold"))), PlainText(" tail")),
                            ),
                        ),
                    ),
                )
            }
        }
        rule.onNodeWithText("bold tail").assertExists()
    }
}
