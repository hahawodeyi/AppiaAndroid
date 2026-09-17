package cn.appia.im.core.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * md 解析链用例（对照 appiaMobile resolveMessageMd.test.ts + gfmTable.test.ts + augmentMdWithGfmTables.test.ts）：
 * md JSON 直读 / plainTextFromMd / `* * *` 造 HORIZONTAL_RULE / isStaleMd 精确语义 /
 * filterVisuallyEmptyMarkdown / GFM 表格 augment（merge 与 rebuild 两条路径）/ 回退解析行内子集。
 */
class MessageMdResolverTest {

    // ── parseMdJson：md 列 JSON 直读（服务端裸数组 + M3-T3 包裹两种形态）──

    @Test
    fun parsesBareArrayMdJson() {
        val root = parseMdJson("""[{"type":"PARAGRAPH","value":[{"type":"PLAIN_TEXT","value":"hello"}]}]""")
        assertEquals(1, root!!.blocks.size)
        assertEquals(listOf<MdInline>(PlainText("hello")), (root.blocks[0] as Paragraph).value)
    }

    @Test
    fun parsesWrappedBlocksMdJson() {
        val root = parseMdJson("""{"blocks":[{"type":"HORIZONTAL_RULE"}]}""")
        assertEquals(listOf<MdBlock>(HorizontalRule), root!!.blocks)
    }

    @Test
    fun badJsonNullAndUnknownBlockDropped() {
        assertNull(parseMdJson("not-json"))
        assertNull(parseMdJson(""))
        val root = parseMdJson(
            """[{"type":"PARAGRAPH","value":[{"type":"PLAIN_TEXT","value":"ok"}]},
               {"type":"TOTALLY_UNKNOWN"}]""",
        )
        assertEquals(1, root!!.blocks.size)
    }

    // ── plainTextFromMd ──

    @Test
    fun plainTextFromMdExtractsParagraphAndList() {
        val md = Root(
            listOf(
                Paragraph(value = listOf(PlainText("hello"))),
                UnorderedList(
                    value = listOf(
                        ListItem(value = listOf(PlainText("a"))),
                        ListItem(value = listOf(PlainText("b"))),
                    ),
                ),
                HorizontalRule,
            ),
        )
        // RN：块间 '\n' join，列表项间 '\n'，HORIZONTAL_RULE 贡献空串，整体 trim
        assertEquals("hello\na\nb", plainTextFromMd(md))
    }

    // ── parseMsgToMd：`* * *` 行造块 ──

    @Test
    fun starLineBecomesHorizontalRule() {
        val blocks = parseMsgToMd("* * *\n\nafter")
        assertTrue(blocks[0] is HorizontalRule)
        assertTrue(blocks.any { it is Paragraph })
    }

    @Test
    fun nonStandardStarLineStaysParagraph() {
        // RN 正则 ^\s*\*\s+\*\s+\*\s*$：'***' 无空格不匹配；'a * * * b' 在行内
        assertTrue(parseMsgToMd("***").none { it is HorizontalRule })
        assertTrue(parseMsgToMd("a * * * b").none { it is HorizontalRule })
    }

    // ── resolveMdFromMsgFields：stale 判定精确语义（isStaleMd）──

    @Test
    fun keepsStoredMdWhenPlainMatchesMsg() {
        val md = Root(listOf(Paragraph(value = listOf(PlainText("hello")))))
        val result = resolveMdFromMsgFields(
            md = """[{"type":"PARAGRAPH","value":[{"type":"PLAIN_TEXT","value":"hello"}]}]""",
            msg = "hello",
        )
        assertEquals(md, result)
    }

    @Test
    fun reparsesMsgWhenStale() {
        val result = resolveMdFromMsgFields(
            md = """[{"type":"PARAGRAPH","value":[{"type":"PLAIN_TEXT","value":"hello"}]}]""",
            msg = "hello edited",
        )
        assertEquals(Root(parseMsgToMd("hello edited")), result)
    }

    @Test
    fun keepsStoredMdWhenMsgShorter() {
        val result = resolveMdFromMsgFields(
            md = """[{"type":"PARAGRAPH","value":[{"type":"PLAIN_TEXT","value":"hello world"}]}]""",
            msg = "hello",
        )
        // 不一致但非前缀 → 不算 stale → 保留 md
        assertTrue(result!!.blocks[0] is Paragraph)
        assertEquals(listOf<MdInline>(PlainText("hello world")), (result.blocks[0] as Paragraph).value)
    }

    @Test
    fun fallsBackToMsgWhenMdHasNoPlainText() {
        val result = resolveMdFromMsgFields(
            md = """[{"type":"LINE_BREAK"}]""",
            msg = "fallback text",
        )
        assertEquals(Root(parseMsgToMd("fallback text")), result)
    }

    @Test
    fun noFallbackWhenMdUnparseable() {
        assertNull(resolveMdFromMsgFields(md = "not-json", msg = "fallback text"))
    }

    @Test
    fun parsesMsgWithoutMdAndNullWhenEmpty() {
        assertEquals(Root(parseMsgToMd("plain text")), resolveMdFromMsgFields(md = null, msg = "plain text"))
        assertNull(resolveMdFromMsgFields(md = null, msg = null))
    }

    @Test
    fun nullWhenEmptyParagraphMdAndNoMsg() {
        assertNull(
            resolveMdFromMsgFields(
                md = """[{"type":"PARAGRAPH","value":[{"type":"PLAIN_TEXT","value":""}]}]""",
                msg = "",
            ),
        )
    }

    // ── filterVisuallyEmptyMarkdown（经 finalize 体现）──

    @Test
    fun emptyPermalinkParagraphFilteredToNull() {
        // Paragraph.tsx:18-33 的空 label LINK 在过滤层同样剔除
        val result = resolveMdFromMsgFields(
            md = """[{"type":"PARAGRAPH","value":[{"type":"LINK","value":{"src":{"type":"PLAIN_TEXT","value":"https://x/msg=1"},"label":[]}}]}]""",
            msg = null,
        )
        assertNull(result)
    }

    // ── GFM 表格 augment ──

    @Test
    fun keepsServerTableMd() {
        val tableJson = """
            [{"type":"PARAGRAPH","subType":"TABLE",
              "value":[{"type":"PLAIN_TEXT","value":"| A | B |"}],
              "data":[{"type":"TABLE_ROW","value":[{"type":"TABLE_CELL","isHeader":true,
                "value":[{"type":"PARAGRAPH","value":[{"type":"PLAIN_TEXT","value":"A"}]}]}]}]}]
        """.trimIndent()
        val result = resolveMdFromMsgFields(md = tableJson, msg = "| A | B |")
        val paragraph = result!!.blocks[0] as Paragraph
        assertEquals("TABLE", paragraph.subType)
    }

    @Test
    fun rebuildsPipeTableTextIntoTableBlock() {
        val result = resolveMdFromMsgFields(
            md = null,
            msg = "| A | B |\n| --- | --- |\n| 1 | 2 |",
        )
        // 三行管道段落 merge 成一个 TABLE 段落
        assertEquals(1, result!!.blocks.size)
        val table = result.blocks[0] as Paragraph
        assertEquals("TABLE", table.subType)
        val rows = table.data!!
        assertEquals(2, rows.size)
        val header = rows[0] as TableRow
        val body = rows[1] as TableRow
        assertEquals(2, header.value.size)
        assertTrue((header.value[0] as TableCell).isHeader == true)
        // 有表头分隔行时正文行 isHeader=false
        assertEquals(false, (body.value[0] as TableCell).isHeader)
        val bodyCell = (body.value[0] as TableCell).value[0] as Paragraph
        assertEquals(listOf<MdInline>(PlainText("1")), bodyCell.value)
    }

    @Test
    fun keepsTextAroundTableAfterRebuild() {
        val result = resolveMdFromMsgFields(
            md = null,
            msg = "before\n| A | B |\n| --- | --- |\n| 1 | 2 |\nafter",
        )
        val blocks = result!!.blocks
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is Paragraph && (blocks[0] as Paragraph).subType == null)
        assertEquals("TABLE", (blocks[1] as Paragraph).subType)
        assertTrue(blocks[2] is Paragraph)
    }

    @Test
    fun mergesPipeParagraphsWithoutSeparator() {
        // message-parser 把每行管道文本解析为独立段落 → merge 路径。
        // RN buildTableBlockFromRowLines 无分隔行时：首行仅作 headerCells 校验、不入行集（RN 精确行为），
        // 首个数据行（i===bodyStartIndex）isHeader=true。
        val result = augmentMdWithGfmTables(
            msgText = "| A | B |\n| 1 | 2 |",
            md = parseMsgToMd("| A | B |\n| 1 | 2 |").let { Root(it) },
        )
        assertEquals(1, result.blocks.size)
        val table = result.blocks[0] as Paragraph
        assertEquals("TABLE", table.subType)
        val rows = table.data!!
        assertEquals(1, rows.size)
        val row = rows[0] as TableRow
        assertEquals(listOf("1", "2"), row.value.map { ((it as TableCell).value[0] as Paragraph).value.single().let { v -> (v as PlainText).value } })
        assertEquals(true, (row.value[0] as TableCell).isHeader)
    }

    @Test
    fun gfmRowLineBoundaries() {
        assertEquals(listOf("A", "B"), parseGfmTableRowLine("| A | B |"))
        assertNull(parseGfmTableRowLine("hello"))
        assertTrue(isGfmTableSeparatorLine("| --- | --- |"))
        assertFalse(isGfmTableSeparatorLine("| A | B |"))
        assertTrue(messageTextHasGfmTable("| A | B |\n| --- | --- |\n| 1 | 2 |"))
        assertFalse(messageTextHasGfmTable("just | pipes | here"))
    }

    // ── 回退解析行内子集（降级路径的块结构）──

    @Test
    fun fallbackParsesBlocksStructure() {
        val blocks = parseMsgToMd("# Title\n\n> quote\n```\ncode line\n```\n- item1\n- item2\n1. one\n2. two")
        assertTrue(blocks.any { it is Heading && it.level == 1 })
        assertTrue(blocks.any { it is Quote })
        val code = blocks.filterIsInstance<Code>().first()
        assertEquals("none", code.language)
        assertEquals(1, code.value.size)
        assertTrue(blocks.any { it is UnorderedList && it.value.size == 2 })
        val ordered = blocks.filterIsInstance<OrderedList>().first()
        assertEquals(2, ordered.value.size)
        assertEquals(1, (ordered.value[0] as ListItem).number)
    }

    @Test
    fun fallbackParsesBoldAndBareUrl() {
        val inline = (parseMsgToMd("bold **x** end").single() as Paragraph).value
        assertTrue(inline[1] is Bold)
        assertEquals(listOf<MdInline>(PlainText("x")), (inline[1] as Bold).value)

        val withUrl = (parseMsgToMd("see https://x.com/a now").single() as Paragraph).value
        val link = withUrl.filterIsInstance<Link>().single()
        assertEquals("https://x.com/a", (link.value.src as PlainText).value)

        // emoji-only 行 → BIG_EMOJI（1-3 个）
        assertTrue(parseMsgToMd("😀😀").single() is BigEmoji)
        // 4 个不构成 BIG_EMOJI
        assertTrue(parseMsgToMd("😀😀😀😀").single() is Paragraph)
    }
}
