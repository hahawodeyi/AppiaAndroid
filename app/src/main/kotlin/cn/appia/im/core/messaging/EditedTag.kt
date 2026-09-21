package cn.appia.im.core.messaging

import cn.appia.im.core.database.entity.MessageEntity

/** RN appendEditedTagToMd.ts：标签颜色 #9ca2a8、内层字号 = 16(body) - 2 - 2 = 12（messageFontSizes.ts）。 */
const val EDITED_TAG_COLOR = "#9ca2a8"
const val EDITED_TAG_MD_INNER_SIZE = 12

/** RN isMessageEdited.ts:4：`Boolean(message.editedBy)`（空串 false）。 */
fun isMessageEdited(message: MessageEntity): Boolean = !message.edited_by.isNullOrEmpty()

/** RN buildEditedTagToken :30-41：BOLD(color)+BOLD(size) 嵌套包 PLAIN_TEXT（__editedTag 标记见 [isEditedTagToken]）。 */
fun buildEditedTagToken(label: String): Bold = Bold(
    value = listOf(Bold(value = listOf(PlainText(label)), size = EDITED_TAG_MD_INNER_SIZE)),
    color = EDITED_TAG_COLOR,
)

/**
 * 编辑标记指纹（RN nodeHasEditedTag 的 __editedTag 自定义属性在 Kotlin 类型化 AST 的等价物）：
 * BOLD(color=#9ca2a8) 包 BOLD(size=12) 组合即 [buildEditedTagToken] 产物——渲染层 InlineNodes
 * 的 color+size 分支对该组合输出小号灰字，与 RN EditedTag 双层渲染一致。
 */
fun isEditedTagToken(node: MdNode): Boolean =
    node is Bold && node.color == EDITED_TAG_COLOR &&
        node.value.any { it is Bold && it.size == EDITED_TAG_MD_INNER_SIZE }

/** RN hasEditedTagInMd :67-70：任一块含标记（嵌套 label/value 递归同查）。 */
fun hasEditedTagInMd(root: Root?): Boolean {
    if (root == null) return false
    return root.blocks.any { blockHasEditedTag(it) }
}

private fun blockHasEditedTag(block: MdNode, depth: Int = 0): Boolean {
    if (depth > MD_INLINE_MAX_DEPTH) return false
    if (isEditedTagToken(block)) return true
    return when (block) {
        is Paragraph -> block.value.any { blockHasEditedTag(it, depth + 1) }
        is Heading -> block.value.any { blockHasEditedTag(it, depth + 1) }
        is BigEmoji -> block.value.any { blockHasEditedTag(it, depth + 1) }
        is Quote -> block.value.any { blockHasEditedTag(it, depth + 1) }
        is OrderedList -> block.value.any { blockHasEditedTag(it, depth + 1) }
        is UnorderedList -> block.value.any { blockHasEditedTag(it, depth + 1) }
        is ListItem -> block.value.any { blockHasEditedTag(it, depth + 1) }
        is Bold -> block.value.any { blockHasEditedTag(it, depth + 1) }
        is Italic -> block.value.any { blockHasEditedTag(it, depth + 1) }
        is Strike -> block.value.any { blockHasEditedTag(it, depth + 1) }
        is Link -> block.value.label.any { blockHasEditedTag(it, depth + 1) }
        else -> false
    }
}

/**
 * RN appendEditedTagToMd :157-173：已含标记原样返回；否则找「最后一个可追加 inline 的 value
 * 数组」（文档序深度优先，见 [findLastInlineList]）追加；无目标块则追加独立 PARAGRAPH。
 * RN cloneMd 后原地 push；Kotlin AST 列不可变 → 路径重建（copy 链）等价产出新树。
 */
fun appendEditedTagToMd(root: Root, label: String): Root {
    if (hasEditedTagInMd(root)) return root
    val token = buildEditedTagToken(label)
    val target = findLastInlineTarget(root.blocks)
        ?: return Root(root.blocks + Paragraph(value = listOf(token)))
    return Root(root.blocks.map { rebuildBlock(it, target, token) })
}

/** 按目标路径重建树（copy 链；目标外的分支原引用共享）。 */
private fun rebuildBlock(block: MdBlock, target: AppendTarget, token: Bold): MdBlock = when (block) {
    is Paragraph ->
        if (target == AppendTarget.Block(block)) block.copy(value = block.value + token)
        else block.copy(value = block.value.map { rebuildInline(it, target, token) })

    is Heading ->
        if (target == AppendTarget.Block(block)) block.copy(value = block.value + token)
        else block.copy(value = block.value.map { rebuildInline(it, target, token) })

    is BigEmoji ->
        if (target == AppendTarget.Block(block)) block.copy(value = block.value + token)
        else block.copy(value = block.value.map { rebuildInline(it, target, token) })

    is Quote -> block.copy(value = block.value.map { rebuildBlock(it, target, token) })

    is OrderedList -> block.copy(value = block.value.map { rebuildNode(it, target, token) })
    is UnorderedList -> block.copy(value = block.value.map { rebuildNode(it, target, token) })

    else -> block
}

private fun rebuildNode(node: MdNode, target: AppendTarget, token: Bold): MdNode = when (node) {
    is MdBlock -> rebuildBlock(node, target, token)
    is ListItem -> if (target == AppendTarget.ListItemParagraph(node)) {
        node.copy(
            value = node.value.map { child ->
                if (child is Paragraph) child.copy(value = child.value + token) else child
            },
        )
    } else {
        node.copy(value = node.value.map { rebuildNode(it, target, token) })
    }

    else -> (node as? MdInline)?.let { rebuildInline(it, target, token) } ?: node
}

private fun rebuildInline(node: MdInline, target: AppendTarget, token: Bold): MdInline = when (node) {
    is Bold ->
        if (target == AppendTarget.Container(node)) node.copy(value = node.value + token)
        else node.copy(value = node.value.map { rebuildInline(it, target, token) })

    is Italic ->
        if (target == AppendTarget.Container(node)) node.copy(value = node.value + token)
        else node.copy(value = node.value.map { rebuildInline(it, target, token) })

    is Strike ->
        if (target == AppendTarget.Container(node)) node.copy(value = node.value + token)
        else node.copy(value = node.value.map { rebuildInline(it, target, token) })

    else -> node
}

/** 追加目标：文档序最后一个可追加 inline 的 value 数组所在路径（null = 无目标块）。 */
internal sealed interface AppendTarget {
    /** 块级 value 数组（PARAGRAPH/HEADING/BIG_EMOJI）。 */
    data class Block(val block: MdBlock) : AppendTarget

    /** Bold/Italic/Strike 容器 value 数组。 */
    data class Container(val node: MdInline) : AppendTarget

    /** ListItem 内首个 Paragraph。 */
    data class ListItemParagraph(val item: ListItem) : AppendTarget
}

/** 文档序深度优先记录最后一个目标（RN findLastInlineArray :78-155 逐分支）。 */
internal fun findLastInlineTarget(blocks: List<MdBlock>): AppendTarget? {
    var last: AppendTarget? = null

    fun visitInlines(inlines: List<MdInline>) {
        if (inlines.isEmpty()) return
        for (node in inlines) {
            when (node) {
                // RN NESTED_INLINE_TYPES = BOLD/ITALIC/STRIKE：非叶子才递归；FontColor/FontSize
                // 形态 BOLD（带 color/size）是叶子，注入点停在其父级数组
                is Bold -> if (node.color == null && node.size == null) {
                    last = AppendTarget.Container(node)
                    visitInlines(node.value)
                }

                is Italic -> {
                    last = AppendTarget.Container(node)
                    visitInlines(node.value)
                }

                is Strike -> {
                    last = AppendTarget.Container(node)
                    visitInlines(node.value)
                }

                else -> Unit // 叶子（LINK/INLINE_CODE/MENTION/EMOJI/INLINE_KATEX）停在父级
            }
        }
    }

    fun visitListItems(items: List<MdNode>) {
        for (item in items) {
            if (item !is ListItem) continue
            val first = item.value.firstOrNull()
            if (first is OrderedList) {
                visitListItems(first.value)
                continue
            }
            if (first is UnorderedList) {
                visitListItems(first.value)
                continue
            }
            // ListItem.value 首元素恒 paragraph（mdToTipTap/editorJson 两向构造保证）
            val paragraph = item.value.filterIsInstance<Paragraph>().firstOrNull()
            if (paragraph != null) {
                last = AppendTarget.ListItemParagraph(item)
                visitInlines(paragraph.value)
            }
        }
    }

    for (block in blocks) {
        when (block) {
            is Paragraph -> {
                last = AppendTarget.Block(block)
                visitInlines(block.value)
            }

            is Heading -> {
                last = AppendTarget.Block(block)
                visitInlines(block.value)
            }

            is BigEmoji -> {
                last = AppendTarget.Block(block)
                visitInlines(block.value)
            }

            is Quote -> findLastInlineTarget(block.value)?.let { last = it }

            is OrderedList -> visitListItems(block.value)
            is UnorderedList -> visitListItems(block.value)
            else -> Unit
        }
    }
    return last
}
