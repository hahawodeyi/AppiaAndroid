package cn.appia.im.core.messaging

import cn.appia.im.core.database.entity.MessageEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * TipTap JSON ↔ MessageParser AST 双向转换器。
 *
 * 正向逐行移植 appiaMobile `src/components/ChatInputBar/editorJson.ts`（RN 冻结基线，
 * 与 web 端 convertTipTapJsonToMessageParserRoot 对齐）；反向移植同目录 `mdToTipTap.ts`
 * （编辑回填）。输入输出均为 kotlinx JSON（TipTap doc / `{"blocks":[...]}` AST），
 * AST 侧产出/消费 [MessageParserTypes] 密封类型。
 */

// ── 常量（editorJson.ts:72-76）────────────────────────────────

private const val DEFAULT_FONT_COLOR = "#1D2129"
private const val DEFAULT_FONT_SIZE = "14px"

/** editorJson.ts:75-76 URL_REGEX 逐字符转录（JS g 标志 → findAll）；internal 供 MessageMdResolver 回退解析复用。 */
internal val URL_REGEX = Regex(
    "(https?)://([-;:&=\\+\\$,\\w]+@{1})?([-A-Za-z0-9.]+)+:?(\\d+)?" +
        "((/[-\\+=!:~%/.@,\\w]*)?\\??([-\\+=&!:;%@/.,\\w]+)?(?:#([^\\s)]+))?)?",
)

// ── JSON 取值小工具（对齐 RN getStringAttr/getNumberAttr 语义）────

/** 仅接受 JSON string（= RN `typeof value === 'string'`）。 */
private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.num(key: String): Int? =
    (this[key] as? JsonPrimitive)?.doubleOrNull?.toInt()

private val JsonObject.attrs: JsonObject
    get() = this["attrs"] as? JsonObject ?: JsonObject(emptyMap())

private fun JsonObject.contentNodes(): List<JsonObject> =
    (this["content"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()

private fun JsonObject.marks(): List<JsonObject> =
    (this["marks"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()

private fun JsonObject.valueNodes(): List<JsonObject> =
    (this["value"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()

/** AST `value: {type:'PLAIN_TEXT', value:string}` 的内层字符串。 */
private fun JsonObject.plainTextField(): String? =
    ((this["value"] as? JsonObject)?.get("value") as? JsonPrimitive)?.takeIf { it.isString }?.content

/** JS `||` 链：跳过 undefined 与空串。 */
private fun firstNonEmpty(vararg values: String?): String? =
    values.firstOrNull { !it.isNullOrEmpty() }

/** JS parseInt 前缀语义（'16px' → 16；RN Number.parseInt 同款）。 */
private fun jsParseInt(s: String): Int? =
    Regex("\\s*[+-]?\\d+").find(s)?.value?.trim()?.toIntOrNull()

private fun getMark(marks: List<JsonObject>, type: String): JsonObject? =
    marks.firstOrNull { it.str("type") == type }

// ── 正向：TipTap JSON → AST（editorJson.ts）──────────────────

/** editorJson.ts:104-138：纯文本中 URL 自动拆分为 Plain + Link 序列。 */
private fun parseTextToInlines(text: String): List<MdInline> {
    if (text.isEmpty()) return emptyList()
    val result = mutableListOf<MdInline>()
    var lastIndex = 0
    for (match in URL_REGEX.findAll(text)) {
        val url = match.value
        val index = match.range.first
        if (index > lastIndex) result += PlainText(text.substring(lastIndex, index))
        result += Link(LinkValue(src = PlainText(url), label = listOf(PlainText(url))))
        lastIndex = index + url.length
    }
    if (lastIndex < text.length) result += PlainText(text.substring(lastIndex))
    return result
}

/** editorJson.ts:141-173：bold → italic → strike → textStyle color/size 逐层包裹。 */
private fun wrapInlineMarks(value: List<MdInline>, marks: List<JsonObject>): List<MdInline> {
    var result = value
    fun wrap(node: (List<MdInline>) -> MdInline) {
        result = listOf(node(result))
    }
    if (getMark(marks, "bold") != null) wrap { Bold(it) }
    if (getMark(marks, "italic") != null) wrap { Italic(it) }
    if (getMark(marks, "strike") != null) wrap { Strike(it) }

    val textStyle = getMark(marks, "textStyle")
    val fontSize = textStyle?.attrs?.str("fontSize")
    val fontColor = textStyle?.attrs?.str("color")
    if (fontSize != null && fontSize != DEFAULT_FONT_SIZE) {
        jsParseInt(fontSize)?.let { size -> wrap { Bold(value = it, size = size) } }
    }
    if (fontColor != null && fontColor.trim().uppercase(Locale.ROOT) != DEFAULT_FONT_COLOR) {
        wrap { Bold(value = it, color = fontColor) }
    }
    return result
}

private fun convertTextNode(node: JsonObject): List<MdInline> {
    val text = node.str("text") ?: ""
    if (text.isEmpty()) return emptyList()
    val marks = node.marks()
    val linkHref = getMark(marks, "link")?.attrs?.str("href")
    if (linkHref != null) {
        val label = wrapInlineMarks(
            listOf(PlainText(text)),
            marks.filter { it.str("type") != "link" },
        )
        return listOf(Link(LinkValue(src = PlainText(linkHref), label = label)))
    }
    return wrapInlineMarks(parseTextToInlines(text), marks)
}

/** editorJson.ts:203-219：mention 节点后恒补一个空格 PLAIN_TEXT。 */
private fun convertMentionNode(node: JsonObject): List<MdInline> {
    val attrs = node.attrs
    val entityType = attrs.str("entityType")
    val mentionValue = firstNonEmpty(
        attrs.str(if (entityType == "page") "entityId" else "id"),
        attrs.str("id"),
        attrs.str("label"),
    ) ?: return emptyList()
    return listOf(
        if (entityType == "page") MentionChannel(PlainText(mentionValue)) else MentionUser(PlainText(mentionValue)),
        PlainText(" "),
    )
}

/** editorJson.ts:225-239：custom → EMOJI{value}；其余 → EMOJI{unicode}；无 alt → 空。 */
private fun convertCustomEmojiNode(node: JsonObject): List<MdInline> {
    val emojiType = node.attrs.str("type")
    val alt = node.attrs.str("alt") ?: ""
    return when {
        emojiType == "custom" && alt.isNotEmpty() -> listOf(Emoji(value = PlainText(alt)))
        alt.isNotEmpty() -> listOf(Emoji(unicode = alt))
        else -> emptyList()
    }
}

private fun convertCustomEmojiToPlainText(node: JsonObject): String {
    val type = node.attrs.str("type")
    val alt = node.attrs.str("alt")
    return when {
        type == "custom" && !alt.isNullOrEmpty() -> " :$alt: "
        type == "emoji" && !alt.isNullOrEmpty() -> alt
        else -> ""
    }
}

private fun convertInlineNode(node: JsonObject): List<MdInline> = when (node.str("type")) {
    "text" -> convertTextNode(node)
    "mention" -> convertMentionNode(node)
    "hardBreak" -> listOf(PlainText("\n"))
    "customEmoji" -> convertCustomEmojiNode(node)
    else -> emptyList()
}

private fun convertParagraphValue(node: JsonObject): List<MdInline> =
    node.contentNodes().flatMap { convertInlineNode(it) }

private fun convertParagraphNode(node: JsonObject): MdBlock {
    val value = convertParagraphValue(node)
    return if (value.isEmpty()) LineBreak else Paragraph(value)
}

// ── 列表（editorJson.ts:287-394）─────────────────────────────

private fun convertListItemNode(node: JsonObject, level: Int, number: Int?): List<MdNode> {
    val children = node.contentNodes()

    // 唯一子节点是列表时整条让位（editorJson.ts:306-313）
    if (children.size == 1) {
        convertListNode(children.first(), level)?.let { return it }
    }

    val result = mutableListOf<MdNode>()
    var value = mutableListOf<MdInline>()
    val pushListItem: () -> Unit = {
        if (value.isNotEmpty()) {
            result += ListItem(value.toList(), number)
            value = mutableListOf()
        }
    }
    for (child in children) {
        when (child.str("type")) {
            "paragraph" -> value += convertParagraphValue(child)
            else -> {
                val nestedList = convertListNode(child, level)
                if (nestedList != null) {
                    pushListItem()
                    result += nestedList
                } else {
                    value += convertInlineNode(child)
                }
            }
        }
    }
    if (value.isNotEmpty() || result.isEmpty()) {
        result += ListItem(
            value = if (value.isNotEmpty()) value else listOf(PlainText(" ")),
            number = number,
        )
    }
    return result
}

/** 嵌套列表以兄弟片段出现 → 返回多个 list 块；非列表输入返回 null。 */
private fun convertListNode(node: JsonObject, level: Int): List<MdBlock>? {
    val listType = when (node.str("type")) {
        "orderedList" -> "ORDERED_LIST"
        "bulletList" -> "UNORDERED_LIST"
        else -> return null
    }
    // RN `getNumberAttr(attrs,'start') || 1`：0/缺失 → 1
    val start = node.attrs.num("start")?.takeIf { it != 0 } ?: 1
    val fragments = node.contentNodes()
        .filter { it.str("type") == "listItem" }
        .flatMapIndexed { index, child ->
            convertListItemNode(
                child,
                level + 1,
                if (listType == "ORDERED_LIST") start + index else null,
            )
        }
    if (fragments.isEmpty()) return null

    val result = mutableListOf<MdBlock>()
    val items = mutableListOf<ListItem>()
    fun flush() {
        if (items.isNotEmpty()) {
            result += if (listType == "ORDERED_LIST") {
                OrderedList(level = level, value = items.toList())
            } else {
                UnorderedList(level = level, value = items.toList())
            }
            items.clear()
        }
    }
    for (item in fragments) {
        if (item is ListItem) {
            items += item
            continue
        }
        flush()
        result += item as MdBlock
    }
    flush()
    return result
}

// ── block 节点（editorJson.ts:396-451）───────────────────────

private fun convertBlockquoteNode(node: JsonObject): List<MdBlock> {
    val value = node.contentNodes()
        .mapNotNull { if (it.str("type") == "paragraph") convertParagraphNode(it) else null }
        .filterIsInstance<Paragraph>()
    return if (value.isNotEmpty()) listOf(Quote(value)) else emptyList()
}

/** editorJson.ts:411-428：heading 压纯文本（仅保留 PLAIN/mention 文本），level 夹取 1-4。 */
private fun convertHeadingNode(node: JsonObject): List<MdBlock> {
    val level = (node.attrs.num("level")?.takeIf { it != 0 } ?: 1).coerceIn(1, 4)
    val text = convertParagraphValue(node).joinToString("") { inline ->
        when (inline) {
            is PlainText -> inline.value
            is MentionUser -> (inline.value as? PlainText)?.value ?: ""
            is MentionChannel -> (inline.value as? PlainText)?.value ?: ""
            else -> ""
        }
    }
    return if (text.isNotEmpty()) listOf(Heading(level = level, value = listOf(PlainText(text)))) else emptyList()
}

private fun convertBlockNode(node: JsonObject, level: Int = 0): List<MdBlock> =
    when (node.str("type")) {
        "paragraph" -> listOf(convertParagraphNode(node))
        "bulletList", "orderedList" -> convertListNode(node, level) ?: emptyList()
        "blockquote" -> convertBlockquoteNode(node)
        "heading" -> convertHeadingNode(node)
        else -> emptyList()
    }

/** editorJson.ts:454-471：唯一块且全 EMOJI、1≤n≤3 → BIG_EMOJI。 */
private fun getBigEmojiBlock(blocks: List<MdBlock>): BigEmoji? {
    if (blocks.size != 1) return null
    val first = blocks.first() as? Paragraph ?: return null
    val emojis = first.value.filterIsInstance<Emoji>()
    if (emojis.size != first.value.size || emojis.isEmpty() || emojis.size > 3) return null
    return BigEmoji(emojis)
}

/**
 * TipTap JSON 文档 → MessageParser AST（editorJson.ts:480-489 逐行对齐）。
 * 入参为 TipTap doc JsonObject（缺失 content → 空 Root）。
 */
fun convertTipTapJsonToMessageParserRoot(tipTap: JsonObject): Root {
    val blocks = tipTap.contentNodes().flatMap { convertBlockNode(it) }
    val bigEmojiBlock = getBigEmojiBlock(blocks)
    return MarkdownRoot(if (bigEmojiBlock != null) listOf(bigEmojiBlock) else blocks)
}

/** editorJson.ts:494-516：`msg` fallback 纯文本（mention 为 `${char}${id} `，整体 trim）。 */
fun extractPlainTextFromTipTapJson(tipTap: JsonObject): String {
    fun extract(nodes: List<JsonObject>): String = nodes.joinToString("") { node ->
        val type = node.str("type")
        when {
            type == "text" -> node.str("text") ?: ""
            node["content"] is JsonArray -> extract(node.contentNodes())
            type == "hardBreak" -> "\n"
            type == "mention" -> {
                val char = node.attrs.str("mentionSuggestionChar") ?: "@"
                val id = firstNonEmpty(node.attrs.str("id"), node.attrs.str("label")) ?: ""
                "$char$id "
            }
            type == "customEmoji" -> convertCustomEmojiToPlainText(node)
            else -> ""
        }
    }
    return extract(tipTap.contentNodes()).trim()
}

// ── 反向：AST JSON → TipTap JSON（mdToTipTap.ts）──────────────

/** 表情 shortCode 解析缝：自定义表情 store / 标准 shortname→unicode 表由接入方注入。 */
fun interface EmojiResolver {
    fun resolve(shortCode: String): ResolvedEmoji?
}

sealed interface ResolvedEmoji {
    data class Custom(val name: String, val extension: String) : ResolvedEmoji
    data class Unicode(val unicode: String) : ResolvedEmoji
}

private data class MentionRef(val username: String?, val name: String?)

private fun textNode(text: String, marks: List<JsonObject>): JsonObject = buildJsonObject {
    put("type", "text")
    put("text", text)
    if (marks.isNotEmpty()) put("marks", JsonArray(marks))
}

private fun markNode(type: String, attrs: JsonObject? = null): JsonObject = buildJsonObject {
    put("type", type)
    if (attrs != null) put("attrs", attrs)
}

private fun hardBreakNode(): JsonObject = buildJsonObject { put("type", "hardBreak") }

private fun paragraphNode(content: List<JsonObject>): JsonObject = buildJsonObject {
    put("type", "paragraph")
    put("content", JsonArray(content))
}

private fun customEmojiNode(type: String, alt: String, title: String, src: String): JsonObject =
    buildJsonObject {
        put("type", "customEmoji")
        put("attrs", buildJsonObject {
            put("type", type)
            put("alt", alt)
            put("title", title)
            put("src", src)
        })
    }

/** mdToTipTap.ts:104-139：EMOJI → customEmoji 节点。 */
private fun emojiToTipTap(node: JsonObject, resolver: EmojiResolver?, baseUrl: String?): JsonObject? {
    val unicode = node.str("unicode")
    if (unicode != null) return customEmojiNode("emoji", unicode, unicode, "")
    // RN：shortCode ?? value.value ?? ''，空 → 丢弃
    val shortCode = firstNonEmpty(node.str("shortCode"), node.plainTextField()) ?: return null
    // ponytail: 标准 shortname→unicode 表（RN shortnameToUnicode，4635 行）未移植，
    // 由 resolver 注入；resolver 未命中/未注入时按 RN miss 路径回退 ':code:'，接入 store 后消除
    return when (val resolved = resolver?.resolve(shortCode)) {
        is ResolvedEmoji.Custom -> {
            val src = baseUrl?.let { "$it/emoji-custom/${java.net.URLEncoder.encode(resolved.name, "UTF-8")}.${resolved.extension}" } ?: ""
            customEmojiNode("custom", shortCode, shortCode, src)
        }
        is ResolvedEmoji.Unicode -> customEmojiNode("emoji", resolved.unicode, resolved.unicode, "")
        null -> customEmojiNode("emoji", ":$shortCode:", ":$shortCode:", "")
    }
}

/** mdToTipTap.ts:39-153：递归转换内联节点，收集外层 marks。 */
private fun convertMdInline(
    node: JsonObject,
    inheritedMarks: List<JsonObject>,
    mentions: List<MentionRef>,
    resolver: EmojiResolver?,
    baseUrl: String?,
): List<JsonObject> = when (node.str("type")) {
    "PLAIN_TEXT" -> listOf(textNode(node.str("value") ?: "", inheritedMarks))
    "BOLD" -> node.valueNodes().flatMap { convertMdInline(it, inheritedMarks + markNode("bold"), mentions, resolver, baseUrl) }
    "ITALIC" -> node.valueNodes().flatMap { convertMdInline(it, inheritedMarks + markNode("italic"), mentions, resolver, baseUrl) }
    "STRIKE" -> node.valueNodes().flatMap { convertMdInline(it, inheritedMarks + markNode("strike"), mentions, resolver, baseUrl) }
    "MENTION_USER" -> {
        val username = node.plainTextField() ?: ""
        val user = mentions.firstOrNull { it.username == username || it.name == username }
        if (user != null) {
            listOf(buildJsonObject {
                put("type", "mention")
                put("attrs", buildJsonObject {
                    put("id", username)
                    put("label", firstNonEmpty(user.name, user.username) ?: username)
                })
            })
        } else {
            listOf(textNode("@$username", inheritedMarks))
        }
    }
    "MENTION_CHANNEL" -> {
        val name = node.plainTextField() ?: ""
        listOf(buildJsonObject {
            put("type", "mention")
            put("attrs", buildJsonObject {
                put("id", name)
                put("label", name)
                put("entityType", "page")
            })
        })
    }
    "LINK" -> {
        val value = node["value"] as? JsonObject
        val href = (value?.get("src") as? JsonObject)?.str("value") ?: ""
        val label = ((value?.get("label") as? JsonArray))
            ?.filterIsInstance<JsonObject>()
            ?.joinToString("") { if (it.str("type") == "PLAIN_TEXT") it.str("value") ?: "" else "" }
            ?: ""
        listOf(textNode(label, inheritedMarks + markNode("link", buildJsonObject {
            put("href", href)
            put("target", "_blank")
        })))
    }
    "INLINE_CODE" -> listOf(textNode(node.plainTextField() ?: "", inheritedMarks + markNode("code")))
    "LINE_BREAK" -> listOf(hardBreakNode())
    "EMOJI" -> listOfNotNull(emojiToTipTap(node, resolver, baseUrl))
    else -> when {
        // mdToTipTap.ts:141-152：未知节点，string value 提取文本，数组递归
        (node["value"] as? JsonPrimitive)?.isString == true ->
            listOf(textNode(node.str("value") ?: "", inheritedMarks))
        node["value"] is JsonArray ->
            node.valueNodes().flatMap { convertMdInline(it, inheritedMarks, mentions, resolver, baseUrl) }
        else -> emptyList()
    }
}

/** mdToTipTap.ts:262-277：任意 AST 节点递归提取纯文本（CODE 不识别 → 空串，块被丢弃）。 */
private fun extractTextFromAst(node: JsonElement?): String = when (node) {
    null -> ""
    is JsonPrimitive -> if (node.isString) node.content else ""
    is JsonArray -> node.joinToString("") { extractTextFromAst(it) }
    is JsonObject -> when (node.str("type")) {
        "PLAIN_TEXT" -> node.str("value") ?: ""
        "LINE_BREAK" -> "\n"
        "MENTION_USER" -> "@${node.plainTextField() ?: ""}"
        "MENTION_CHANNEL" -> "#${node.plainTextField() ?: ""}"
        else -> (node["value"] as? JsonArray)?.joinToString("") { extractTextFromAst(it) } ?: ""
    }
}

/** mdToTipTap.ts:216-232：列表块 → orderedList/bulletList（start 取首个 item.number）。 */
private fun convertMdList(
    node: JsonObject,
    mentions: List<MentionRef>,
    resolver: EmojiResolver?,
    baseUrl: String?,
): JsonObject? {
    val listType = when (node.str("type")) {
        "ORDERED_LIST" -> "orderedList"
        "UNORDERED_LIST" -> "bulletList"
        else -> return null
    }
    val items = node.valueNodes().filter {
        val t = it.str("type")
        (t == "LIST_ITEM" || t == null) && it["value"] is JsonArray
    }
    if (items.isEmpty()) return null
    val start = items.first().num("number") ?: 1
    val content = items.map { convertMdListItem(it, mentions, resolver, baseUrl) }
    return buildJsonObject {
        put("type", listType)
        put("attrs", buildJsonObject { put("start", start) })
        put("content", JsonArray(content))
    }
}

/** mdToTipTap.ts:234-260：item 内联内容 + 兄弟嵌套列表（TipTap 侧嵌套放 listItem.content 尾部）。 */
private fun convertMdListItem(
    item: JsonObject,
    mentions: List<MentionRef>,
    resolver: EmojiResolver?,
    baseUrl: String?,
): JsonObject {
    val inlineContent = mutableListOf<JsonObject>()
    val nestedLists = mutableListOf<JsonObject>()
    for (child in item.valueNodes()) {
        when (child.str("type")) {
            "ORDERED_LIST", "UNORDERED_LIST" ->
                convertMdList(child, mentions, resolver, baseUrl)?.let { nestedLists += it }
            else -> inlineContent += convertMdInline(child, emptyList(), mentions, resolver, baseUrl)
        }
    }
    if (inlineContent.isEmpty()) inlineContent += textNode("", emptyList())
    return buildJsonObject {
        put("type", "listItem")
        put("content", JsonArray(listOf(paragraphNode(inlineContent)) + nestedLists))
    }
}

private fun convertMdBlock(
    node: JsonObject,
    mentions: List<MentionRef>,
    resolver: EmojiResolver?,
    baseUrl: String?,
): List<JsonObject> = when (node.str("type")) {
    "PARAGRAPH" -> {
        val content = node.valueNodes()
            .flatMap { convertMdInline(it, emptyList(), mentions, resolver, baseUrl) }
            .toMutableList()
        if (content.isEmpty()) content += textNode("", emptyList())
        listOf(paragraphNode(content))
    }
    "LINE_BREAK" -> listOf(paragraphNode(listOf(hardBreakNode())))
    "BIG_EMOJI" -> {
        val content = node.valueNodes().flatMap { convertMdInline(it, emptyList(), mentions, resolver, baseUrl) }
        if (content.isEmpty()) emptyList() else listOf(paragraphNode(content))
    }
    "ORDERED_LIST", "UNORDERED_LIST" ->
        convertMdList(node, mentions, resolver, baseUrl)?.let { listOf(it) } ?: emptyList()
    // mdToTipTap.ts:192-204：CODE/HEADING/QUOTE/TASKS 首版降级纯文本段落
    "CODE", "HEADING", "QUOTE", "BLOCKQUOTE", "TASKS" -> {
        val text = extractTextFromAst(node)
        if (text.isNotEmpty()) listOf(paragraphNode(listOf(textNode(text, emptyList())))) else emptyList()
    }
    else -> emptyList()
}

/**
 * MessageParser AST（`{"blocks":[...]}`）+ mentions → TipTap doc（mdToTipTap.ts:284-293）。
 * [mentions] 为服务端 mentions 数组（元素含 username/name）；[resolver] 见 [EmojiResolver]。
 */
fun mdToTipTap(
    root: JsonObject,
    mentions: JsonArray? = null,
    emojiResolver: EmojiResolver? = null,
    baseUrl: String? = null,
): JsonObject {
    val mentionRefs = (mentions ?: JsonArray(emptyList()))
        .filterIsInstance<JsonObject>()
        .map { MentionRef(it.str("username"), it.str("name")) }
    val blocks = ((root["blocks"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList())
        .flatMap { convertMdBlock(it, mentionRefs, emojiResolver, baseUrl) }
    return buildJsonObject {
        put("type", "doc")
        put("content", JsonArray(blocks))
    }
}

// ── 编辑回填（mdToTipTap.ts:299-307 buildEditContent）──────────

private val editContentHtmlEscape = arrayOf(
    "&" to "&amp;",
    "<" to "&lt;",
    ">" to "&gt;",
    "\"" to "&quot;",
    "'" to "&#39;",
)

private fun escapeHtml(s: String): String {
    var out = s
    for ((raw, escaped) in editContentHtmlEscape) out = out.replace(raw, escaped)
    return out
}

/**
 * 从消息构建可回填编辑器的 Content（mdToTipTap.ts:299-307 buildEditContent）：
 * 有 md → mdToTipTap（mentions 还原 mention 节点）；无 md/坏 md → `<p>{escapeHtml(msg)}</p>`
 * HTML 串（tiptap setContent(string) 直吃）。[emojiResolver]/[baseUrl] 语义同 [mdToTipTap]
 * （自定义表情还原）。
 */
fun buildEditContent(
    message: MessageEntity,
    emojiResolver: EmojiResolver? = null,
    baseUrl: String? = null,
): JsonElement {
    val mdEl = message.md?.let { raw ->
        runCatching { Json { ignoreUnknownKeys = true; isLenient = true }.parseToJsonElement(raw) }.getOrNull()
    }
    val blocks = when (mdEl) {
        is JsonArray -> mdEl
        is JsonObject -> mdEl["blocks"] as? JsonArray
        else -> null
    }
    if (blocks != null) {
        val mentions = runCatching {
            Json.parseToJsonElement(message.mentions.orEmpty()) as? JsonArray
        }.getOrNull()
        return mdToTipTap(buildJsonObject { put("blocks", blocks) }, mentions, emojiResolver, baseUrl)
    }
    return JsonPrimitive("<p>${escapeHtml(message.msg.orEmpty())}</p>")
}

/** 编辑器产物 md（AST Root）→ wire JSON（`{"blocks":[...]}`；SendOrchestrator `md?.toString()` 同形态）。 */
fun rootToJsonElement(root: Root): JsonElement =
    kotlinx.serialization.json.Json.encodeToJsonElement(MarkdownRoot.serializer(), root)
