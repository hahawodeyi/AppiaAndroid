package cn.appia.im.core.messaging

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TipTap↔md AST 双向转换器测试。
 * 用例移植 appiaMobile `src/components/ChatInputBar/editorJson.test.ts` 与 `mdToTipTap.test.ts`，
 * 另补 URL 拆分/默认色字号边界/BIG_EMOJI 边界/extractPlainText/TipTap→AST→TipTap 同型组。
 */
class TipTapJsonConverterTest {

    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    // ── TipTap JSON 构造 ──
    private fun doc(vararg blocks: JsonObject): JsonObject = buildJsonObject {
        put("type", "doc")
        put("content", JsonArray(blocks.toList()))
    }

    private fun ttParagraph(vararg content: JsonObject): JsonObject = buildJsonObject {
        put("type", "paragraph")
        put("content", JsonArray(content.toList()))
    }

    private fun ttText(text: String, vararg marks: JsonObject): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", text)
        if (marks.isNotEmpty()) put("marks", JsonArray(marks.toList()))
    }

    private fun ttHardBreak(): JsonObject = buildJsonObject { put("type", "hardBreak") }

    private fun ttMark(type: String, attrs: JsonObject? = null): JsonObject = buildJsonObject {
        put("type", type)
        if (attrs != null) put("attrs", attrs)
    }

    private fun ttCustomEmoji(type: String, alt: String, src: String = ""): JsonObject = buildJsonObject {
        put("type", "customEmoji")
        put("attrs", buildJsonObject {
            put("type", type)
            put("alt", alt)
            put("title", alt)
            put("src", src)
        })
    }

    private fun ttMention(attrs: JsonObject): JsonObject = buildJsonObject {
        put("type", "mention")
        put("attrs", attrs)
    }

    private fun ttListItem(vararg content: JsonObject): JsonObject = buildJsonObject {
        put("type", "listItem")
        put("content", JsonArray(content.toList()))
    }

    private fun ttList(type: String, vararg items: JsonObject, start: Int? = null): JsonObject = buildJsonObject {
        put("type", type)
        put("attrs", buildJsonObject { put("start", start ?: 1) })
        put("content", JsonArray(items.toList()))
    }

    private fun strOf(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    // ── AST 构造 ──
    private fun plain(value: String) = PlainText(value)
    private fun astParagraph(vararg value: MdInline) = Paragraph(value.toList())
    private fun bold(vararg value: MdInline) = Bold(value.toList())
    private fun italic(vararg value: MdInline) = Italic(value.toList())
    private fun strike(vararg value: MdInline) = Strike(value.toList())
    private fun mentionUser(username: String) = MentionUser(plain(username))
    private fun mentionChannel(name: String) = MentionChannel(plain(name))
    private fun astLink(src: String, label: String) = Link(LinkValue(plain(src), listOf(plain(label))))
    private fun inlineCode(value: String) = InlineCode(plain(value))
    private fun listItem(value: List<MdNode>, number: Int? = null) = ListItem(value, number)
    private fun orderedList(vararg value: MdNode) = OrderedList(level = 0, value = value.toList())
    private fun unorderedList(vararg value: MdNode) = UnorderedList(level = 0, value = value.toList())
    private fun emojiUnicode(unicode: String) = Emoji(unicode = unicode)
    private fun emojiShortCode(shortCode: String) = Emoji(shortCode = shortCode, value = plain(shortCode))
    private fun bigEmoji(vararg emojis: MdInline) = BigEmoji(emojis.toList())

    private fun mdJson(vararg blocks: MdBlock): JsonObject =
        json.encodeToJsonElement(MarkdownRoot.serializer(), MarkdownRoot(blocks.toList())).jsonObject

    private fun mentionsJson(vararg pairs: Pair<String, String?>): JsonArray = JsonArray(
        pairs.map { (username, name) ->
            buildJsonObject {
                put("username", username)
                name?.let { put("name", it) }
            }
        },
    )

    private val emojiResolver = EmojiResolver { shortCode ->
        when (shortCode) {
            "grinning" -> ResolvedEmoji.Unicode("😀")
            "party_parrot" -> ResolvedEmoji.Custom("party_parrot", "gif")
            else -> null
        }
    }

    // ══ 正向：convertTipTapJsonToMessageParserRoot（editorJson.test.ts 移植）══

    @Test
    fun singleCustomEmojiTypeEmojiBecomesBigEmoji() {
        val root = convertTipTapJsonToMessageParserRoot(doc(ttParagraph(ttCustomEmoji("emoji", "😀"))))
        assertEquals(listOf<MdBlock>(bigEmoji(emojiUnicode("😀"))), root.blocks)
    }

    @Test
    fun singleCustomEmojiTypeCustomBecomesBigEmoji() {
        val root = convertTipTapJsonToMessageParserRoot(
            doc(ttParagraph(ttCustomEmoji("custom", "custom_emoji", "https://example.com/emoji-custom/custom_emoji.png"))),
        )
        assertEquals(listOf<MdBlock>(BigEmoji(listOf(Emoji(value = plain("custom_emoji"))))), root.blocks)
    }

    @Test
    fun mixedTextAndCustomEmojiStaysParagraph() {
        val root = convertTipTapJsonToMessageParserRoot(
            doc(ttParagraph(ttText("Hello "), ttCustomEmoji("emoji", "👍"), ttText(" world"))),
        )
        assertEquals(
            listOf<MdBlock>(astParagraph(plain("Hello "), emojiUnicode("👍"), plain(" world"))),
            root.blocks,
        )
    }

    @Test
    fun twoMixedEmojisBecomeBigEmoji() {
        val root = convertTipTapJsonToMessageParserRoot(
            doc(ttParagraph(ttCustomEmoji("emoji", "😀"), ttCustomEmoji("custom", "party_parrot"))),
        )
        val block = root.blocks.single()
        assertEquals(true, block is BigEmoji)
        assertEquals(2, (block as BigEmoji).value.size)
    }

    @Test
    fun customEmojiWithEmptyAttrsFallsBackToLineBreak() {
        val root = convertTipTapJsonToMessageParserRoot(
            doc(ttParagraph(buildJsonObject {
                put("type", "customEmoji")
                put("attrs", JsonObject(emptyMap()))
            })),
        )
        assertEquals(listOf<MdBlock>(LineBreak), root.blocks)
    }

    @Test
    fun fourEmojisStayParagraph() {
        val root = convertTipTapJsonToMessageParserRoot(
            doc(ttParagraph(
                ttCustomEmoji("emoji", "😀"), ttCustomEmoji("emoji", "👍"),
                ttCustomEmoji("emoji", "🎉"), ttCustomEmoji("emoji", "❤️"),
            )),
        )
        val block = root.blocks.single()
        assertEquals(true, block is Paragraph)
        assertEquals(4, (block as Paragraph).value.size)
    }

    @Test
    fun exactlyThreeEmojisBecomeBigEmoji() {
        val root = convertTipTapJsonToMessageParserRoot(
            doc(ttParagraph(ttCustomEmoji("emoji", "😀"), ttCustomEmoji("emoji", "👍"), ttCustomEmoji("emoji", "🎉"))),
        )
        assertEquals(true, root.blocks.single() is BigEmoji)
    }

    @Test
    fun boldPlusColorPlusFontSizeNestsThreeBolds() {
        val root = convertTipTapJsonToMessageParserRoot(
            doc(
                ttParagraph(
                    ttText(
                        "hello",
                        ttMark("bold"),
                        ttMark("textStyle", buildJsonObject {
                            put("color", "#FF0000")
                            put("fontSize", "16px")
                        }),
                    ),
                ),
            ),
        )
        val outer = (root.blocks.single() as Paragraph).value.single() as Bold
        assertEquals("#FF0000", outer.color)
        assertEquals(null, outer.size)
        val middle = outer.value.single() as Bold
        assertEquals(16, middle.size)
        assertEquals(null, middle.color)
        val inner = middle.value.single() as Bold
        assertEquals(null, inner.color)
        assertEquals(null, inner.size)
        assertEquals(PlainText("hello"), inner.value.single())
    }

    @Test
    fun boldAloneHasNoColorOrSize() {
        val root = convertTipTapJsonToMessageParserRoot(doc(ttParagraph(ttText("plain bold", ttMark("bold")))))
        val boldNode = (root.blocks.single() as Paragraph).value.single() as Bold
        assertEquals(null, boldNode.color)
        assertEquals(null, boldNode.size)
        assertEquals(PlainText("plain bold"), boldNode.value.single())
    }

    // ── 正向补强：URL 拆分 / 默认色字号 / mention / hardBreak / link mark ──

    @Test
    fun urlInTextSplitsIntoPlainAndLink() {
        val root = convertTipTapJsonToMessageParserRoot(doc(ttParagraph(ttText("see https://a.b/c x"))))
        assertEquals(
            listOf<MdInline>(
                plain("see "),
                astLink("https://a.b/c", "https://a.b/c"),
                plain(" x"),
            ),
            (root.blocks.single() as Paragraph).value,
        )
    }

    @Test
    fun urlWithPortAndQueryIsOneLink() {
        val root = convertTipTapJsonToMessageParserRoot(
            doc(ttParagraph(ttText("https://host.example:8443/p?q=1#frag"))),
        )
        val link = (root.blocks.single() as Paragraph).value.single() as Link
        assertEquals("https://host.example:8443/p?q=1#frag", link.value.src.value)
    }

    @Test
    fun defaultColorIsNotWrapped() {
        val root = convertTipTapJsonToMessageParserRoot(
            doc(ttParagraph(ttText("t", ttMark("textStyle", buildJsonObject { put("color", "#1d2129") })))),
        )
        assertEquals(PlainText("t"), (root.blocks.single() as Paragraph).value.single())
    }

    @Test
    fun defaultColorWithWhitespaceIsNormalizedAway() {
        val root = convertTipTapJsonToMessageParserRoot(
            doc(ttParagraph(ttText("t", ttMark("textStyle", buildJsonObject { put("color", " #1D2129 ") })))),
        )
        assertEquals(PlainText("t"), (root.blocks.single() as Paragraph).value.single())
    }

    @Test
    fun defaultFontSizeIsNotWrapped() {
        val root = convertTipTapJsonToMessageParserRoot(
            doc(ttParagraph(ttText("t", ttMark("textStyle", buildJsonObject { put("fontSize", "14px") })))),
        )
        assertEquals(PlainText("t"), (root.blocks.single() as Paragraph).value.single())
    }

    @Test
    fun nonDefaultFontSizeAloneWrapsBoldWithSize() {
        val root = convertTipTapJsonToMessageParserRoot(
            doc(ttParagraph(ttText("t", ttMark("textStyle", buildJsonObject { put("fontSize", "16px") })))),
        )
        assertEquals(Bold(value = listOf(PlainText("t")), size = 16), (root.blocks.single() as Paragraph).value.single())
    }

    @Test
    fun nonDefaultColorAloneWrapsBoldWithRawColor() {
        val root = convertTipTapJsonToMessageParserRoot(
            doc(ttParagraph(ttText("t", ttMark("textStyle", buildJsonObject { put("color", "#ff0000") })))),
        )
        assertEquals(Bold(value = listOf(PlainText("t")), color = "#ff0000"), (root.blocks.single() as Paragraph).value.single())
    }

    @Test
    fun mentionNodeAppendsTrailingSpace() {
        val root = convertTipTapJsonToMessageParserRoot(
            doc(ttParagraph(ttMention(buildJsonObject { put("id", "john"); put("label", "John Doe") }))),
        )
        assertEquals(
            listOf<MdInline>(mentionUser("john"), plain(" ")),
            (root.blocks.single() as Paragraph).value,
        )
    }

    @Test
    fun pageMentionUsesEntityIdAndBecomesChannel() {
        val root = convertTipTapJsonToMessageParserRoot(
            doc(ttParagraph(ttMention(buildJsonObject {
                put("entityType", "page")
                put("entityId", "room1")
                put("id", "ignored")
            }))),
        )
        assertEquals(
            listOf<MdInline>(mentionChannel("room1"), plain(" ")),
            (root.blocks.single() as Paragraph).value,
        )
    }

    @Test
    fun hardBreakBecomesNewlinePlainText() {
        val root = convertTipTapJsonToMessageParserRoot(
            doc(ttParagraph(ttText("a"), ttHardBreak(), ttText("b"))),
        )
        assertEquals(
            listOf<MdInline>(plain("a"), plain("\n"), plain("b")),
            (root.blocks.single() as Paragraph).value,
        )
    }

    @Test
    fun linkMarkKeepsBoldLabel() {
        val root = convertTipTapJsonToMessageParserRoot(
            doc(ttParagraph(ttText("click", ttMark("bold"), ttMark("link", buildJsonObject { put("href", "https://x.dev") })))),
        )
        assertEquals(
            listOf<MdInline>(Link(LinkValue(plain("https://x.dev"), listOf(bold(plain("click")))))),
            (root.blocks.single() as Paragraph).value,
        )
    }

    @Test
    fun emptyParagraphBecomesLineBreakBetweenBlocks() {
        val root = convertTipTapJsonToMessageParserRoot(
            doc(ttParagraph(ttText("a")), ttParagraph(), ttParagraph(ttText("b"))),
        )
        assertEquals(listOf<MdBlock>(astParagraph(plain("a")), LineBreak, astParagraph(plain("b"))), root.blocks)
    }

    @Test
    fun orderedListDerivesNumbersFromStart() {
        val root = convertTipTapJsonToMessageParserRoot(
            doc(
                ttList(
                    "orderedList",
                    ttListItem(ttParagraph(ttText("first"))),
                    ttListItem(ttParagraph(ttText("second"))),
                    start = 3,
                ),
            ),
        )
        assertEquals(
            listOf<MdBlock>(
                orderedList(listItem(listOf(plain("first")), 3), listItem(listOf(plain("second")), 4)),
            ),
            root.blocks,
        )
    }

    @Test
    fun nestedListBecomesSiblingListBlock() {
        // RN editorJson 特性：嵌套列表以兄弟块出现（convertListItemNode fragments 平铺）
        val root = convertTipTapJsonToMessageParserRoot(
            doc(
                ttList(
                    "bulletList",
                    ttListItem(ttParagraph(ttText("a")), ttList("bulletList", ttListItem(ttParagraph(ttText("b"))))),
                ),
            ),
        )
        assertEquals(
            listOf<MdBlock>(
                UnorderedList(level = 0, value = listOf(listItem(listOf(plain("a"))))),
                UnorderedList(level = 1, value = listOf(listItem(listOf(plain("b"))))),
            ),
            root.blocks,
        )
    }

    @Test
    fun headingClampsLevelAndCollapsesToPlainText() {
        val root = convertTipTapJsonToMessageParserRoot(
            doc(buildJsonObject {
                put("type", "heading")
                put("attrs", buildJsonObject { put("level", 5) })
                put("content", JsonArray(listOf(
                    ttText("Hi "),
                    ttMention(buildJsonObject { put("id", "bob") }),
                )))
            }),
        )
        // RN 同款：mention 后的补位空格一并压入 heading 纯文本
        assertEquals(listOf<MdBlock>(Heading(4, listOf(plain("Hi bob ")))), root.blocks)
    }

    @Test
    fun blockquoteDropsEmptyParagraphs() {
        val root = convertTipTapJsonToMessageParserRoot(
            doc(buildJsonObject {
                put("type", "blockquote")
                put("content", JsonArray(listOf(ttParagraph(ttText("q1")), ttParagraph(), ttParagraph(ttText("q2")))))
            }),
        )
        assertEquals(
            listOf<MdBlock>(Quote(listOf(astParagraph(plain("q1")), astParagraph(plain("q2"))))),
            root.blocks,
        )
    }

    // ══ 反向：mdToTipTap（mdToTipTap.test.ts 移植）══

    @Test
    fun mdPlainParagraphToTipTap() {
        val doc = mdToTipTap(mdJson(astParagraph(plain("hello world"))))
        assertEquals(doc(ttParagraph(ttText("hello world"))), doc)
    }

    @Test
    fun mdMultipleParagraphs() {
        val doc = mdToTipTap(mdJson(astParagraph(plain("first")), astParagraph(plain("second"))))
        val content = doc["content"] as JsonArray
        assertEquals(2, content.size)
        assertEquals(ttParagraph(ttText("first")), content[0])
        assertEquals(ttParagraph(ttText("second")), content[1])
    }

    @Test
    fun mdBoldToMark() {
        val doc = mdToTipTap(mdJson(astParagraph(bold(plain("bold text")))))
        assertEquals(doc(ttParagraph(ttText("bold text", ttMark("bold")))), doc)
    }

    @Test
    fun mdItalicToMark() {
        val doc = mdToTipTap(mdJson(astParagraph(italic(plain("italic text")))))
        assertEquals(doc(ttParagraph(ttText("italic text", ttMark("italic")))), doc)
    }

    @Test
    fun mdStrikeToMark() {
        val doc = mdToTipTap(mdJson(astParagraph(strike(plain("strike text")))))
        assertEquals(doc(ttParagraph(ttText("strike text", ttMark("strike")))), doc)
    }

    @Test
    fun mdNestedMarksMerge() {
        val doc = mdToTipTap(mdJson(astParagraph(bold(italic(plain("bold italic"))))))
        assertEquals(doc(ttParagraph(ttText("bold italic", ttMark("bold"), ttMark("italic")))), doc)
    }

    @Test
    fun mdMentionUserLookupByMentions() {
        val doc = mdToTipTap(mdJson(astParagraph(mentionUser("john"))), mentionsJson("john" to "John Doe"))
        assertEquals(
            doc(ttParagraph(ttMention(buildJsonObject { put("id", "john"); put("label", "John Doe") }))),
            doc,
        )
    }

    @Test
    fun mdMentionUserFallbackToPlainText() {
        val doc = mdToTipTap(mdJson(astParagraph(mentionUser("unknown"))), mentionsJson())
        assertEquals(doc(ttParagraph(ttText("@unknown"))), doc)
    }

    @Test
    fun mdMentionChannelGetsEntityTypePage() {
        val doc = mdToTipTap(mdJson(astParagraph(mentionChannel("general"))))
        assertEquals(
            doc(ttParagraph(ttMention(buildJsonObject {
                put("id", "general"); put("label", "general"); put("entityType", "page")
            }))),
            doc,
        )
    }

    @Test
    fun mdLinkToLinkMark() {
        val doc = mdToTipTap(mdJson(astParagraph(astLink("https://example.com", "click here"))))
        assertEquals(
            doc(ttParagraph(ttText("click here", ttMark("link", buildJsonObject {
                put("href", "https://example.com"); put("target", "_blank")
            })))),
            doc,
        )
    }

    @Test
    fun mdInlineCodeToCodeMark() {
        val doc = mdToTipTap(mdJson(astParagraph(inlineCode("const x = 1"))))
        assertEquals(doc(ttParagraph(ttText("const x = 1", ttMark("code")))), doc)
    }

    @Test
    fun mdLineBreakToHardBreak() {
        val doc = mdToTipTap(mdJson(astParagraph(plain("line1"), LineBreak, plain("line2"))))
        assertEquals(doc(ttParagraph(ttText("line1"), ttHardBreak(), ttText("line2"))), doc)
    }

    @Test
    fun mdEmojiUnicodeToCustomEmojiNode() {
        val doc = mdToTipTap(mdJson(astParagraph(emojiUnicode("😀"))))
        assertEquals(doc(ttParagraph(ttCustomEmoji("emoji", "😀"))), doc)
    }

    @Test
    fun mdEmojiStandardShortCodeResolvesToUnicode() {
        val doc = mdToTipTap(mdJson(astParagraph(emojiShortCode("grinning"))), emojiResolver = emojiResolver)
        assertEquals(doc(ttParagraph(ttCustomEmoji("emoji", "😀"))), doc)
    }

    @Test
    fun mdEmojiCustomShortCodeBuildsSrcWithBaseUrl() {
        val doc = mdToTipTap(
            mdJson(astParagraph(emojiShortCode("party_parrot"))),
            emojiResolver = emojiResolver,
            baseUrl = "https://chat.example.com",
        )
        assertEquals(
            doc(ttParagraph(ttCustomEmoji("custom", "party_parrot", "https://chat.example.com/emoji-custom/party_parrot.gif"))),
            doc,
        )
    }

    @Test
    fun mdEmojiCustomShortCodeWithoutBaseUrlHasEmptySrc() {
        val doc = mdToTipTap(mdJson(astParagraph(emojiShortCode("party_parrot"))), emojiResolver = emojiResolver)
        assertEquals(doc(ttParagraph(ttCustomEmoji("custom", "party_parrot"))), doc)
    }

    @Test
    fun mdEmojiUnresolvedShortCodeFallsBackToColonForm() {
        val doc = mdToTipTap(mdJson(astParagraph(emojiShortCode("nope"))), emojiResolver = emojiResolver)
        assertEquals(doc(ttParagraph(ttCustomEmoji("emoji", ":nope:"))), doc)
    }

    @Test
    fun mdBigEmojiBecomesParagraph() {
        val doc = mdToTipTap(mdJson(bigEmoji(emojiUnicode("😀"))))
        assertEquals(doc(ttParagraph(ttCustomEmoji("emoji", "😀"))), doc)
    }

    @Test
    fun mdBigEmojiMultipleEmojis() {
        val doc = mdToTipTap(mdJson(bigEmoji(emojiUnicode("😀"), emojiUnicode("😎"))))
        assertEquals(doc(ttParagraph(ttCustomEmoji("emoji", "😀"), ttCustomEmoji("emoji", "😎"))), doc)
    }

    @Test
    fun mdMixedTextAndEmoji() {
        val doc = mdToTipTap(
            mdJson(astParagraph(plain("Hello "), emojiUnicode("👍"), plain(" world"))),
            emojiResolver = emojiResolver,
        )
        assertEquals(
            doc(ttParagraph(ttText("Hello "), ttCustomEmoji("emoji", "👍"), ttText(" world"))),
            doc,
        )
    }

    @Test
    fun mdOrderedListPreservesOrderAndStart() {
        val doc = mdToTipTap(
            mdJson(
                orderedList(
                    listItem(listOf(plain("first item")), 1),
                    listItem(listOf(plain("second item")), 2),
                ),
            ),
        )
        assertEquals(
            doc(
                ttList(
                    "orderedList",
                    ttListItem(ttParagraph(ttText("first item"))),
                    ttListItem(ttParagraph(ttText("second item"))),
                ),
            ),
            doc,
        )
    }

    @Test
    fun mdUnorderedListToBulletList() {
        val doc = mdToTipTap(mdJson(unorderedList(listItem(listOf(plain("a"))), listItem(listOf(plain("b"))))))
        assertEquals(
            doc(ttList("bulletList", ttListItem(ttParagraph(ttText("a"))), ttListItem(ttParagraph(ttText("b"))))),
            doc,
        )
    }

    @Test
    fun mdOrderedListStartFromFirstItemNumber() {
        val doc = mdToTipTap(
            mdJson(orderedList(listItem(listOf(plain("third")), 3), listItem(listOf(plain("fourth")), 4))),
        )
        val list = (doc["content"] as JsonArray)[0] as JsonObject
        assertEquals(buildJsonObject { put("start", 3) }, list["attrs"])
    }

    @Test
    fun mdMarksInsideListItems() {
        val doc = mdToTipTap(mdJson(orderedList(listItem(listOf(bold(plain("bold item")))))))
        val listItemNode = (((doc["content"] as JsonArray)[0] as JsonObject)["content"] as JsonArray)[0] as JsonObject
        assertEquals(ttParagraph(ttText("bold item", ttMark("bold"))), (listItemNode["content"] as JsonArray)[0])
    }

    @Test
    fun mdNestedUnorderedInsideOrderedItem() {
        val doc = mdToTipTap(
            mdJson(orderedList(listItem(listOf(plain("parent"), unorderedList(listItem(listOf(plain("child")))))))),
        )
        val outerList = (doc["content"] as JsonArray)[0] as JsonObject
        assertEquals("orderedList", strOf(outerList["type"]))
        val outerItem = (outerList["content"] as JsonArray)[0] as JsonObject
        val content = outerItem["content"] as JsonArray
        assertEquals(ttParagraph(ttText("parent")), content[0])
        val nested = content[1] as JsonObject
        assertEquals("bulletList", strOf(nested["type"]))
        assertEquals(ttListItem(ttParagraph(ttText("child"))), (nested["content"] as JsonArray)[0])
    }

    @Test
    fun mdCodeHeadingQuoteTasksDegradeToPlainTextParagraph() {
        val doc = mdToTipTap(
            mdJson(
                Code(value = listOf(CodeLine(plain("const")))),
                Heading(1, listOf(plain("Title"))),
                Quote(listOf(astParagraph(plain("quoted")))),
                Tasks(listOf(Task(true, listOf(plain("todo"))))),
            ),
        )
        val content = doc["content"] as JsonArray
        assertEquals(3, content.size) // CODE 无文本 → 整块丢弃（RN extractTextFromAST 不识别 CODE_LINE）
        assertEquals(ttParagraph(ttText("Title")), content[0])
        assertEquals(ttParagraph(ttText("quoted")), content[1])
        assertEquals(ttParagraph(ttText("todo")), content[2])
    }

    // ══ round-trip 无损组（TipTap→AST→TipTap 同型）══

    @Test
    fun roundTripTipTapToAstToTipTapParagraphBoldEmojiList() {
        val original = doc(
            ttParagraph(ttText("hello "), ttText("world", ttMark("bold"))),
            ttParagraph(ttCustomEmoji("emoji", "😀")),
            ttList(
                "orderedList",
                ttListItem(ttParagraph(ttText("one"))),
                ttListItem(ttParagraph(ttText("two"))),
            ),
        )
        val root = convertTipTapJsonToMessageParserRoot(original)
        val back = mdToTipTap(json.encodeToJsonElement(MarkdownRoot.serializer(), root).jsonObject)
        assertEquals(original, back)
    }

    @Test
    fun roundTripOrderedListStaysOrdered() {
        val md = listOf<MdBlock>(
            orderedList(listItem(listOf(plain("first")), 1), listItem(listOf(plain("second")), 2)),
        )
        val back = convertTipTapJsonToMessageParserRoot(mdToTipTap(mdJson(*md.toTypedArray())))
        assertEquals(md, back.blocks)
    }

    @Test
    fun roundTripUnorderedListStaysUnordered() {
        val md = listOf<MdBlock>(unorderedList(listItem(listOf(plain("a"))), listItem(listOf(plain("b")))))
        val back = convertTipTapJsonToMessageParserRoot(mdToTipTap(mdJson(*md.toTypedArray())))
        assertEquals(md, back.blocks)
    }

    @Test
    fun roundTripMentionKeepsNodeWithKnownTrailingSpace() {
        // 已知不对称（RN 同款）：正向 mention 后补空格 PLAIN_TEXT，回填后多出一个空格 text 节点
        val root = convertTipTapJsonToMessageParserRoot(
            doc(ttParagraph(ttMention(buildJsonObject { put("id", "john"); put("label", "John") }))),
        )
        val back = mdToTipTap(
            json.encodeToJsonElement(MarkdownRoot.serializer(), root).jsonObject,
            mentionsJson("john" to "John"),
        )
        assertEquals(
            doc(ttParagraph(
                ttMention(buildJsonObject { put("id", "john"); put("label", "John") }),
                ttText(" "),
            )),
            back,
        )
    }

    // ══ extractPlainTextFromTipTapJson ══

    @Test
    fun extractPlainTextJoinsTexts() {
        assertEquals("hello world", extractPlainTextFromTipTapJson(doc(ttParagraph(ttText("hello "), ttText("world")))))
    }

    @Test
    fun extractPlainTextMentionUsesSuggestionChar() {
        val text = extractPlainTextFromTipTapJson(
            doc(ttParagraph(ttMention(buildJsonObject { put("mentionSuggestionChar", "#"); put("id", "general") }))),
        )
        assertEquals("#general", text)
    }

    @Test
    fun extractPlainTextMentionDefaultsToAt() {
        val text = extractPlainTextFromTipTapJson(
            doc(ttParagraph(ttMention(buildJsonObject { put("label", "bob") }))),
        )
        assertEquals("@bob", text)
    }

    @Test
    fun extractPlainTextCustomEmojiAndHardBreak() {
        val text = extractPlainTextFromTipTapJson(
            doc(ttParagraph(
                ttCustomEmoji("custom", "party_parrot"),
                ttHardBreak(),
                ttCustomEmoji("emoji", "😀"),
                ttText("  "),
            )),
        )
        assertEquals(":party_parrot: \n😀", text)
    }

    @Test
    fun extractPlainTextTrimsOuterWhitespace() {
        assertEquals("x", extractPlainTextFromTipTapJson(doc(ttParagraph(ttText("  x  ")))))
    }
}
