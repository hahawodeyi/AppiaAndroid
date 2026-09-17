package cn.appia.im.core.messaging

import cn.appia.im.core.database.entity.MessageEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * md 解析链（对照 appiaMobile `src/lib/message/resolveMessageMd.ts` + `filterVisuallyEmptyMarkdown.ts`
 * + `src/lib/markdown/gfmTable.ts` + `augmentMdWithGfmTables.ts`，渲染红线：md 列 JSON 直读，勿重 parse msg）：
 *
 * 1. `md` JSON 直读（服务端裸数组 / M3-T3 `{"blocks":[...]}` 包裹两种形态）
 * 2. `plainTextFromMd(md)` 与 `msg` 不一致且 msg 为其延长（isStaleMd）才回退 `parseMsgToMd(msg)`
 * 3. `* * *` 行（RN 精确正则）自造 HORIZONTAL_RULE
 * 4. finalize：filterVisuallyEmptyMarkdown → augmentMdWithGfmTables（管道表格 → `PARAGRAPH{subType:'TABLE', data}`）
 *
 * parseMsgToMd 的 RN 版委托 @rocket.chat/message-parser（nearley 全量语法）；本文件为 Kotlin
 * 降级实现：块级结构（标题 1-4/引用/围栏代码/扁平列表/BIG_EMOJI）+ 行内子集（**粗体**（含单星，
 * RN 实测同出 BOLD）/__粗体__/_斜体_/~~删除~~/`行内代码`/[label](url)/裸 URL（复用 editorJson
 * URL_REGEX）/@mention）。已核实与 RN 的已知偏差：:shortname: 表情与行内 unicode 表情不拆
 * EMOJI 节点（shortname 表 4635 行，与 T3 EmojiResolver 同缝，T5 接表后补）；空行 LINE_BREAK
 * 记账按"每空行一个"（RN 对标题前后有 +1 怪癖，但 LINE_BREAK 块在 filter 层全被剔除，不可见）。
 */

private val mdJsonFormat = Json { ignoreUnknownKeys = true; isLenient = true }

/** `md` 列 JSON → [Root]；坏 JSON/非对象数组返回 null。未知块型丢块不弃整条（RN 渲染层同降级）。 */
fun parseMdJson(raw: String?): Root? {
    if (raw.isNullOrEmpty()) return null
    val el = runCatching { mdJsonFormat.parseToJsonElement(raw) }.getOrNull() ?: return null
    return decodeRoot(el) ?: decodeRoot(normalizeBlockTypes(el))
}

private fun decodeRoot(el: JsonElement): Root? {
    val blocks = when (el) {
        is JsonArray -> el
        is JsonObject -> el["blocks"] as? JsonArray ?: return null
        else -> return null
    }
    return runCatching {
        Root(
            blocks.mapNotNull { block ->
                runCatching { mdJsonFormat.decodeFromJsonElement(MdBlock.serializer(), block) }.getOrNull()
            },
        )
    }.getOrNull()
}

/** RN isHorizontalRuleBlock 兼容小写 `horizontal_rule`：反序列化前归一化判别符再重试。 */
private fun normalizeBlockTypes(el: JsonElement): JsonElement = when (el) {
    is JsonObject -> JsonObject(
        el.mapValues { (key, value) ->
            if (key == "type" && value is JsonPrimitive && value.isString && value.content == "horizontal_rule") {
                JsonPrimitive("HORIZONTAL_RULE")
            } else {
                normalizeBlockTypes(value)
            }
        },
    )
    is JsonArray -> JsonArray(el.map { normalizeBlockTypes(it) })
    else -> el
}

// ── 纯文本提取（stale 判定用，resolveMessageMd.ts:15-47 精确移植）──

/** 仅 PLAIN_TEXT / EMOJI{unicode} 与带数组 value 的容器参与拼接（RN 同款：LINK/MENTION 贡献空串）。 */
internal fun plainInlineText(node: MdNode): String = when (node) {
    is PlainText -> node.value
    is Emoji -> node.unicode.orEmpty()
    is Bold -> node.value.joinToString("") { plainInlineText(it) }
    is Italic -> node.value.joinToString("") { plainInlineText(it) }
    is Strike -> node.value.joinToString("") { plainInlineText(it) }
    is ListItem -> node.value.joinToString("") { plainInlineText(it) }
    is CodeLine -> plainInlineText(node.value)
    else -> ""
}

fun plainTextFromMd(md: Root): String = md.blocks.joinToString("\n") { block ->
    when (block) {
        is Paragraph -> block.value.joinToString("") { plainInlineText(it) }
        is Heading -> block.value.joinToString("") { plainInlineText(it) }
        is BigEmoji -> block.value.joinToString("") { plainInlineText(it) }
        is UnorderedList -> block.value.joinToString("\n") { plainInlineText(it) }
        is OrderedList -> block.value.joinToString("\n") { plainInlineText(it) }
        else -> ""
    }
}.trim()

// ── 回退解析：parseMsgToMd（`* * *` 造块 + 块级/行内降级解析）──

/** RN resolveMessageMd.ts:13 精确正则：`^\s*\*\s+\*\s+\*\s*$`（'***' 不算）。 */
private val HORIZONTAL_RULE_LINE = Regex("""^\s*\*\s+\*\s+\*\s*$""")

fun parseMsgToMd(msg: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val buffer = mutableListOf<String>()
    fun flush() {
        if (buffer.isEmpty()) return
        val text = buffer.joinToString("\n").trim()
        buffer.clear()
        if (text.isNotEmpty()) blocks += parseTextChunk(text)
    }
    for (line in msg.split('\n')) {
        if (HORIZONTAL_RULE_LINE.matches(line)) {
            flush()
            blocks += HorizontalRule
        } else {
            buffer += line
        }
    }
    flush()
    return blocks
}

private val FENCE_OPEN = Regex("""^```\s*(\S*)\s*$""")
private val FENCE_CLOSE = Regex("""^```\s*$""")

/** RN 实测：标题仅 1-4 级（'##### H5' 为普通段落）；引用 '>' 可无空格；'1)' 不构成有序列表。 */
private val HEADING_LINE = Regex("""^(#{1,4})\s+(.+)$""")
private val QUOTE_LINE = Regex("""^>\s?(.*)$""")
private val UNORDERED_ITEM = Regex("""^[-*+]\s+(.+)$""")
private val ORDERED_ITEM = Regex("""^(\d+)\.\s+(.+)$""")

/** 单个文本块（已 trim）→ 块级 AST；每非空行一个段落（RN message-parser 实测同款）。 */
private fun parseTextChunk(text: String): List<MdBlock> {
    val lines = text.split('\n')
    val blocks = mutableListOf<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.trim().isEmpty() -> {
                blocks += LineBreak
                i += 1
            }

            FENCE_OPEN.matches(line.trim()) -> {
                val language = FENCE_OPEN.find(line.trim())!!.groupValues[1].ifEmpty { "none" }
                val codeLines = mutableListOf<MdNode>()
                i += 1
                while (i < lines.size && !FENCE_CLOSE.matches(lines[i].trim())) {
                    codeLines += CodeLine(PlainText(lines[i]))
                    i += 1
                }
                if (i < lines.size) i += 1 // 跳过闭合围栏
                blocks += Code(language = language, value = codeLines)
            }

            else -> {
                val heading = HEADING_LINE.find(line)
                when {
                    heading != null -> {
                        blocks += Heading(
                            level = heading.groupValues[1].length,
                            value = parseInlines(heading.groupValues[2].trim()),
                        )
                        i += 1
                    }

                    QUOTE_LINE.matches(line) -> {
                        val paras = mutableListOf<MdBlock>()
                        while (i < lines.size) {
                            val quote = QUOTE_LINE.find(lines[i]) ?: break
                            paras += Paragraph(value = parseInlines(quote.groupValues[1].trim()))
                            i += 1
                        }
                        blocks += Quote(value = paras)
                    }

                    UNORDERED_ITEM.matches(line) -> {
                        val items = mutableListOf<MdNode>()
                        while (i < lines.size) {
                            val item = UNORDERED_ITEM.find(lines[i]) ?: break
                            items += ListItem(value = parseInlines(item.groupValues[1].trim()))
                            i += 1
                        }
                        blocks += UnorderedList(value = items)
                    }

                    ORDERED_ITEM.matches(line) -> {
                        val items = mutableListOf<MdNode>()
                        while (i < lines.size) {
                            val item = ORDERED_ITEM.find(lines[i]) ?: break
                            items += ListItem(value = parseInlines(item.groupValues[2].trim()), number = item.groupValues[1].toInt())
                            i += 1
                        }
                        blocks += OrderedList(value = items)
                    }

                    else -> {
                        val clusters = emojiClusterCount(line.trim())
                        // RN：全 emoji 且 1-3 个 → BIG_EMOJI，其余走段落
                        if (clusters != null && clusters in 1..3) {
                            blocks += BigEmoji(value = listOf(Emoji(unicode = line.trim())))
                        } else {
                            blocks += Paragraph(value = parseInlines(line))
                        }
                        i += 1
                    }
                }
            }
        }
    }
    return blocks
}

// ponytail: unicode 表情簇按码点范围近似（RN 用完整 emoji 正则表）；T5 接表情表后如需精确再换
private val EMOJI_MODIFIER_CPS = setOf(0xFE0F, 0x200D) + (0x1F3FB..0x1F3FF)
private val EMOJI_BASE_RANGES = listOf(
    0x1F000..0x1FAFF, 0x1F1E6..0x1F1FF, 0x2600..0x27BF, 0x2B00..0x2BFF, 0x2190..0x21FF, 0x2300..0x23FF,
)

/** 行内容全为表情码点时返回簇数（修饰符不计），否则 null。 */
private fun emojiClusterCount(s: String): Int? {
    if (s.isEmpty()) return null
    var count = 0
    var index = 0
    while (index < s.length) {
        val cp = s.codePointAt(index)
        index += Character.charCount(cp)
        if (cp in EMOJI_MODIFIER_CPS) continue
        if (EMOJI_BASE_RANGES.any { cp in it }) count += 1 else return null
    }
    return count
}

// ── 回退行内子集（RN message-parser 实测对齐：单星 *x* 亦出 BOLD）──

private val INLINE_CODE_TOKEN = Regex("`([^`\\n]+)`")
private val LINK_TOKEN = Regex("\\[([^]\\n]*)]\\(([^)\\s]+)\\)")
private val STRIKE_TOKEN = Regex("~~(.+?)~~")
private val BOLD_DOUBLE_ASTERISK = Regex("\\*\\*(.+?)\\*\\*")
private val BOLD_DOUBLE_UNDERSCORE = Regex("__(.+?)__")
private val BOLD_SINGLE_ASTERISK = Regex("\\*(.+?)\\*")
private val ITALIC_UNDERSCORE = Regex("_(.+?)_")
private val MENTION_TOKEN = Regex("(?<![\\w@])@([\\w-]+)")

/** 按 RN 实测优先级取最早命中：行内代码 > 链接 > 删除 > 粗体（双/单）> 斜体 > mention > 裸 URL。 */
private val INLINE_PATTERNS: List<Pair<Regex, (MatchResult, Int) -> MdInline>> = listOf(
    INLINE_CODE_TOKEN to { m, _ -> InlineCode(PlainText(m.groupValues[1])) },
    LINK_TOKEN to { m, depth ->
        Link(
            LinkValue(
                src = PlainText(m.groupValues[2]),
                label = parseInlinesDepth(m.groupValues[1], depth + 1),
            ),
        )
    },
    STRIKE_TOKEN to { m, depth -> Strike(parseInlinesDepth(m.groupValues[1], depth + 1)) },
    BOLD_DOUBLE_ASTERISK to { m, depth -> Bold(parseInlinesDepth(m.groupValues[1], depth + 1)) },
    BOLD_DOUBLE_UNDERSCORE to { m, depth -> Bold(parseInlinesDepth(m.groupValues[1], depth + 1)) },
    BOLD_SINGLE_ASTERISK to { m, depth -> Bold(parseInlinesDepth(m.groupValues[1], depth + 1)) },
    ITALIC_UNDERSCORE to { m, depth -> Italic(parseInlinesDepth(m.groupValues[1], depth + 1)) },
    MENTION_TOKEN to { m, _ -> MentionUser(PlainText(m.groupValues[1])) },
    URL_REGEX to { m, _ -> Link(LinkValue(src = PlainText(m.value), label = listOf(PlainText(m.value)))) },
)

internal fun parseInlines(text: String): List<MdInline> = parseInlinesDepth(text, 0)

private fun parseInlinesDepth(text: String, depth: Int): List<MdInline> {
    if (text.isEmpty()) return emptyList()
    if (depth > 2) return listOf(PlainText(text))
    var best: MatchResult? = null
    var bestFactory: ((MatchResult, Int) -> MdInline)? = null
    for ((regex, factory) in INLINE_PATTERNS) {
        val match = regex.find(text) ?: continue
        if (best == null || match.range.first < best.range.first) {
            best = match
            bestFactory = factory
        }
    }
    best ?: return listOf(PlainText(text))
    val out = mutableListOf<MdInline>()
    out += parseInlinesDepth(text.substring(0, best.range.first), depth)
    out += bestFactory!!(best, depth)
    out += parseInlinesDepth(text.substring(best.range.last + 1), depth)
    return out
}

// ── filterVisuallyEmptyMarkdown（filterVisuallyEmptyMarkdown.ts 精确移植）──

/** linkUtils.getLinkLabelText：label AST 纯文本拼接（空判定语义与 RN 一致）。 */
fun getLinkLabelText(label: List<MdInline>): String = label.joinToString("") { item ->
    when (item) {
        is PlainText -> item.value
        is Link -> getLinkLabelText(item.value.label)
        is Bold -> getLinkLabelText(item.value)
        is Italic -> getLinkLabelText(item.value)
        is Strike -> getLinkLabelText(item.value)
        is Emoji -> item.unicode.orEmpty()
        else -> ""
    }
}

/** 引用消息前的 `[ ](permalink)` 空链接标记（Paragraph.tsx:18-33 同款判定）。 */
fun isEmptyQuoteMarkerLink(node: MdInline): Boolean = node is Link && getLinkLabelText(node.value.label).isBlank()

fun isParagraphVisuallyEmpty(value: List<MdInline>): Boolean {
    if (value.isEmpty()) return true
    if (isEmptyQuoteMarkerLink(value[0])) {
        if (value.size == 1) return true
        val second = value[1]
        if (second is PlainText && second.value.trim().isEmpty()) return true
    }
    val visibleText = value
        .filterIndexed { index, block ->
            when {
                index == 0 && isEmptyQuoteMarkerLink(block) -> false
                index == 1 && block is PlainText && block.value.trimStart().isEmpty() -> false
                block is PlainText && block.value.trim().isEmpty() -> false
                block is Link && isEmptyQuoteMarkerLink(block) -> false
                else -> true
            }
        }
        .joinToString("") { block ->
            when (block) {
                is PlainText -> block.value
                is MentionUser -> (block.value as? PlainText)?.value.orEmpty()
                else -> "x"
            }
        }
        .trim()
    return visibleText.isEmpty()
}

fun filterVisuallyEmptyMarkdown(md: List<MdBlock>?): List<MdBlock>? {
    if (md.isNullOrEmpty()) return null
    val filtered = md.filter { block ->
        when (block) {
            is Paragraph -> !isParagraphVisuallyEmpty(block.value)
            is LineBreak -> false
            else -> true
        }
    }
    return filtered.ifEmpty { null }
}

// ── GFM 管道表格（gfmTable.ts 精确移植）──

private val TABLE_ROW_LINE = Regex("""^\s*\|(.+\|.+)\s*$""")
private val TABLE_SEPARATOR_CELL = Regex("""^:?-{3,}:?$""")

fun parseGfmTableRowLine(line: String): List<String>? {
    val trimmed = line.trim()
    if (!TABLE_ROW_LINE.matches(trimmed)) return null
    val parts = trimmed.split('|').map { it.trim() }.toMutableList()
    if (parts.first().isEmpty()) parts.removeAt(0)
    if (parts.last().isEmpty()) parts.removeAt(parts.size - 1)
    return parts.takeIf { it.size >= 2 }
}

fun isGfmTableSeparatorLine(line: String): Boolean {
    val cells = parseGfmTableRowLine(line) ?: return false
    return cells.isNotEmpty() && cells.all { TABLE_SEPARATOR_CELL.matches(it.replace("\\s".toRegex(), "")) }
}

fun isGfmTableRowLine(line: String): Boolean = parseGfmTableRowLine(line) != null

private fun paragraphFromPlainText(text: String): Paragraph =
    Paragraph(value = if (text.isNotEmpty()) listOf(PlainText(text)) else emptyList())

private fun buildTableBlockFromRowLines(rowLines: List<String>): Paragraph? {
    if (rowLines.size < 2) return null
    val headerCells = parseGfmTableRowLine(rowLines[0]) ?: return null
    var bodyStartIndex = 1
    var hasHeader = false
    if (isGfmTableSeparatorLine(rowLines[1])) {
        hasHeader = true
        bodyStartIndex = 2
    }
    val rows = mutableListOf<MdNode>()
    if (hasHeader) {
        rows += TableRow(
            value = headerCells.map { cell ->
                TableCell(isHeader = true, value = listOf(paragraphFromPlainText(cell)))
            },
        )
    }
    for (i in bodyStartIndex until rowLines.size) {
        val line = rowLines[i]
        if (isGfmTableSeparatorLine(line)) continue
        val cells = parseGfmTableRowLine(line) ?: break
        rows += TableRow(
            value = cells.map { cell ->
                TableCell(isHeader = !hasHeader && i == bodyStartIndex, value = listOf(paragraphFromPlainText(cell)))
            },
        )
    }
    if (rows.isEmpty()) return null
    return Paragraph(
        value = listOf(PlainText(rowLines.joinToString("\n"))),
        subType = "TABLE",
        data = rows,
    )
}

internal fun createTableParagraph(markdown: String): Paragraph {
    val rowLines = markdown.split('\n').map { it.trimEnd() }.filter { it.isNotBlank() }
    return buildTableBlockFromRowLines(rowLines)
        ?: Paragraph(value = emptyList(), subType = "TABLE", data = emptyList())
}

fun messageTextHasGfmTable(text: String): Boolean {
    val lines = text.split('\n')
    for (i in 0 until lines.size - 1) {
        if (isGfmTableRowLine(lines[i].trim()) && isGfmTableSeparatorLine(lines[i + 1].trim())) return true
    }
    return false
}

internal sealed class GfmSegment {
    data class Text(val content: String) : GfmSegment()
    data class Table(val markdown: String) : GfmSegment()
}

internal fun splitTextByGfmTables(text: String): List<GfmSegment> {
    val lines = text.split('\n')
    val segments = mutableListOf<GfmSegment>()
    val buffer = mutableListOf<String>()
    var index = 0
    fun flushText() {
        if (buffer.isNotEmpty()) {
            segments += GfmSegment.Text(buffer.joinToString("\n"))
            buffer.clear()
        }
    }
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()
        val nextLine = lines.getOrNull(index + 1)?.trim().orEmpty()
        if (isGfmTableRowLine(trimmed) && isGfmTableSeparatorLine(nextLine)) {
            flushText()
            val tableLines = mutableListOf(trimmed, nextLine)
            index += 2
            while (index < lines.size) {
                val candidate = lines[index].trim()
                if (candidate.isEmpty()) {
                    index += 1
                    break
                }
                if (!isGfmTableRowLine(candidate) && !isGfmTableSeparatorLine(candidate)) break
                tableLines += candidate
                index += 1
            }
            segments += GfmSegment.Table(tableLines.joinToString("\n"))
            continue
        }
        buffer += line
        index += 1
    }
    flushText()
    return segments
}

private fun paragraphBlockToPlainText(block: MdBlock): String =
    (block as? Paragraph)?.value?.mapNotNull { inline -> (inline as? PlainText)?.value }?.joinToString("").orEmpty()

internal fun mergeParagraphBlocksIntoTables(blocks: List<MdBlock>): List<MdBlock> {
    val result = mutableListOf<MdBlock>()
    var index = 0
    while (index < blocks.size) {
        val block = blocks[index]
        if (block is LineBreak) {
            index += 1
            continue
        }
        if (block !is Paragraph) {
            result += block
            index += 1
            continue
        }
        if (block.subType == "TABLE") {
            result += block
            index += 1
            continue
        }
        val firstLine = paragraphBlockToPlainText(block)
        if (!isGfmTableRowLine(firstLine) && !isGfmTableSeparatorLine(firstLine)) {
            result += block
            index += 1
            continue
        }
        val rowLines = mutableListOf<String>()
        while (index < blocks.size) {
            val candidate = blocks[index]
            if (candidate is LineBreak) {
                index += 1
                break
            }
            if (candidate !is Paragraph) break
            val rowText = paragraphBlockToPlainText(candidate)
            if (!isGfmTableRowLine(rowText) && !isGfmTableSeparatorLine(rowText)) break
            rowLines += rowText
            index += 1
        }
        val tableBlock = buildTableBlockFromRowLines(rowLines)
        if (tableBlock != null) {
            result += tableBlock
        } else {
            rowLines.forEach { rowText -> result += paragraphFromPlainText(rowText) }
        }
    }
    return result
}

/** 已含 TABLE（含嵌套 data 判定 Array.isArray）则原样返回——augmentMdWithGfmTables.ts:9-11。 */
private fun mdContainsTable(md: Root): Boolean =
    md.blocks.any { it is Paragraph && it.subType == "TABLE" && it.data != null }

fun augmentMdWithGfmTables(msgText: String, md: Root): Root {
    if (mdContainsTable(md)) return md
    val merged = Root(mergeParagraphBlocksIntoTables(md.blocks))
    if (mdContainsTable(merged)) return merged
    if (!messageTextHasGfmTable(msgText)) return merged
    val segments = splitTextByGfmTables(msgText)
    if (segments.all { it is GfmSegment.Text }) return merged
    val rebuilt = mutableListOf<MdBlock>()
    segments.forEach { segment ->
        when (segment) {
            is GfmSegment.Table -> rebuilt += createTableParagraph(segment.markdown)
            is GfmSegment.Text -> {
                val content = segment.content.trim()
                if (content.isNotEmpty()) rebuilt += parseMsgToMd(content)
            }
        }
    }
    return if (rebuilt.isNotEmpty()) Root(rebuilt) else merged
}

// ── finalize 与 resolve 主链 ──

/** resolveMessageMd.ts:86-91：过滤空段落/孤立换行 → GFM 表格 augment。 */
internal fun finalizeMd(msg: String?, md: List<MdBlock>?): Root? {
    if (md == null) return null
    val filtered = filterVisuallyEmptyMarkdown(md) ?: return null
    return augmentMdWithGfmTables(msg?.trim().orEmpty(), Root(filtered))
}

/** resolveMessageMd.ts:75-79：不一致但 msg 是 mdPlain 的延长才算过期（msg 更短→保留 md）。 */
fun isStaleMd(mdPlain: String, msg: String): Boolean =
    mdPlain != msg && mdPlain.isNotEmpty() && msg.startsWith(mdPlain) && msg.length > mdPlain.length

/**
 * resolveMdFromMsgFields（resolveMessageMd.ts:100-130 精确移植）：
 * - 有效 `md` 与 `msg` 一致时用 `md`
 * - `md` 过期（msg 为其延长）时解析 `msg`
 * - `md` 存在但无法解析时不回退 `msg`
 * - 仅有 `msg` 时解析 `msg`
 */
fun resolveMdFromMsgFields(md: String?, msg: String?): Root? {
    val trimmedMsg = msg?.trim()
    val hasMdField = !md.isNullOrEmpty()
    val storedMd = if (hasMdField) parseMdJson(md) else null
    if (hasMdField && storedMd == null) return null

    if (storedMd != null && storedMd.blocks.isNotEmpty()) {
        val mdPlain = plainTextFromMd(storedMd)
        if (mdPlain.trim().isNotEmpty()) {
            if (trimmedMsg != null && isStaleMd(mdPlain, trimmedMsg)) {
                return finalizeMd(trimmedMsg, parseMsgToMd(trimmedMsg))
            }
            return finalizeMd(trimmedMsg, storedMd.blocks)
        }
        // 空段落 md（编辑器默认结构）— 忽略，回退到 msg 或 null
    }

    if (trimmedMsg != null) return finalizeMd(trimmedMsg, parseMsgToMd(trimmedMsg))
    return null
}

/** 决定 MessageBody 应渲染的 Markdown AST（md/msg 列直读入口）。 */
fun resolveMessageMd(message: MessageEntity): Root? = resolveMdFromMsgFields(md = message.md, msg = message.msg)
