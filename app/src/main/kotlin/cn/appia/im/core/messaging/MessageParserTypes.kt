package cn.appia.im.core.messaging

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Rocket.Chat MessageParser AST 的 Kotlin 形状（M3 渲染/编辑范围）。
 *
 * 字段名与 appiaMobile `external/message-parser/dist/definitions.d.ts` 逐一对齐；
 * 序列化采用多态判别符 `type`（值 = RN AST 的 type 字符串，如 "PARAGRAPH"），
 * 可空字段缺省时省略（对齐 RN `JSON.stringify` 丢弃 undefined 的 wire 形态）。
 *
 * 容器字段一律以密封接口为元素类型（而非 definitions 中的具体收窄类型），
 * 保证序列化后每个元素仍带 `type` 字段——KDoc 注明原 definitions 的收窄。
 */
@Serializable
sealed interface MdNode

/** 行内节点（Paragraph.value / Bold.value 等的成员）。 */
@Serializable
sealed interface MdInline : MdNode

/** 块级节点（Root.blocks 的成员）。 */
@Serializable
sealed interface MdBlock : MdNode

/** RN `Root` = blocks 数组；此处包一层对象（`{"blocks":[...]}`）便于 JSON 直传。 */
@Serializable
data class MarkdownRoot(val blocks: List<MdBlock>)

/** 任务书接口名；= [MarkdownRoot]。 */
typealias Root = MarkdownRoot

@Serializable
@SerialName("PLAIN_TEXT")
data class PlainText(val value: String) : MdInline

@Serializable
@SerialName("LINK")
data class Link(val value: LinkValue) : MdInline

/** definitions Link.value：`{ src: Plain, label: Markup | Markup[] }`（label 此处恒为数组）。 */
@Serializable
data class LinkValue(val src: PlainText, val label: List<MdInline>)

/** definitions FontSize/FontColor：同为 type 'BOLD'，size/color 可选（editorJson.ts:161-169）。 */
@Serializable
@SerialName("BOLD")
data class Bold(
    val value: List<MdInline>,
    val color: String? = null,
    val size: Int? = null,
) : MdInline

@Serializable
@SerialName("ITALIC")
data class Italic(val value: List<MdInline>) : MdInline

@Serializable
@SerialName("STRIKE")
data class Strike(val value: List<MdInline>) : MdInline

/**
 * definitions Emoji 两形态合一：unicode 形态 `{unicode}`；shortCode 形态 `{shortCode, value: Plain}`。
 * RN editorJson.ts:225-239 的 custom 输出只带 value（不带 shortCode），原样保留。
 */
@Serializable
@SerialName("EMOJI")
data class Emoji(
    val unicode: String? = null,
    val value: MdInline? = null,
    val shortCode: String? = null,
) : MdInline

/** definitions BigEmoji：value 为 1-3 个 Emoji（判定在 TipTapJsonConverter.getBigEmojiBlock）。 */
@Serializable
@SerialName("BIG_EMOJI")
data class BigEmoji(val value: List<MdInline>) : MdBlock

@Serializable
@SerialName("MENTION_USER")
data class MentionUser(val value: MdInline) : MdInline

@Serializable
@SerialName("MENTION_CHANNEL")
data class MentionChannel(val value: MdInline) : MdInline

@Serializable
@SerialName("INLINE_CODE")
data class InlineCode(val value: MdInline) : MdInline

@Serializable
@SerialName("INLINE_KATEX")
data class InlineKaTeX(val value: String) : MdInline

/** Appia 扩展：INLINE_KATEX 同款块级形态。 */
@Serializable
@SerialName("KATEX")
data class KaTeX(val value: String) : MdBlock

/** RN editorJson.ts:277-285：空段落 → LINE_BREAK；Appia 扩展 subType='TABLE' + data 行。 */
@Serializable
@SerialName("PARAGRAPH")
data class Paragraph(
    val value: List<MdInline>,
    val subType: String? = null,
    val data: List<MdNode>? = null,
) : MdBlock

/** definitions Quote.value: Paragraph[]（此处放宽为 MdBlock，序列化仍带 type）。 */
@Serializable
@SerialName("QUOTE")
data class Quote(val value: List<MdBlock>) : MdBlock

/** definitions Heading.value: Plain[]（此处放宽为 MdInline）；level 1-4。 */
@Serializable
@SerialName("HEADING")
data class Heading(val level: Int, val value: List<MdInline>) : MdBlock

/** definitions Code：language 可选 + CodeLine[]（此处放宽为 MdNode）。 */
@Serializable
@SerialName("CODE")
data class Code(val language: String? = null, val value: List<MdNode>) : MdBlock

@Serializable
@SerialName("CODE_LINE")
data class CodeLine(val value: MdInline) : MdNode

/** definitions Tasks：Task[]（status 必填）。 */
@Serializable
@SerialName("TASKS")
data class Tasks(val value: List<MdNode>) : MdBlock

@Serializable
@SerialName("TASK")
data class Task(val status: Boolean, val value: List<MdInline>) : MdNode

/** Appia TABLE 形状（definitions AppiaTableBlock/Row/Cell）。 */
@Serializable
@SerialName("TABLE")
data class TableBlock(val value: List<MdNode>) : MdBlock

@Serializable
@SerialName("TABLE_ROW")
data class TableRow(val value: List<MdNode>) : MdNode

@Serializable
@SerialName("TABLE_CELL")
data class TableCell(val isHeader: Boolean? = null, val value: List<MdNode>) : MdNode

/** RN editorJson.ts:42-44：list 玩家 value 混含 LIST_ITEM 与兄弟嵌套 list（level=父级 level）。 */
@Serializable
@SerialName("ORDERED_LIST")
data class OrderedList(val level: Int, val value: List<MdNode>) : MdBlock

@Serializable
@SerialName("UNORDERED_LIST")
data class UnorderedList(val level: Int, val value: List<MdNode>) : MdBlock

/** definitions ListItem.value: (Inlines | OrderedList | UnorderedList)[] → MdNode。 */
@Serializable
@SerialName("LIST_ITEM")
data class ListItem(val value: List<MdNode>, val number: Int? = null) : MdNode

/**
 * LINE_BREAK 在 RN 中既作块级（空段落）也作行内（paragraph.value 成员）出现，
 * 故同时实现两密封接口；序列化为 `{"type":"LINE_BREAK"}`（RN value: undefined 被丢弃）。
 */
@Serializable
@SerialName("LINE_BREAK")
object LineBreak : MdInline, MdBlock
