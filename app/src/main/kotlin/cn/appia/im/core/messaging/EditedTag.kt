package cn.appia.im.core.messaging

import cn.appia.im.core.database.entity.MessageEntity

/** RN appendEditedTagToMd.ts：标签颜色 #9ca2a8、内层字号 = 16(body) - 2 - 2 = 12（messageFontSizes.ts）。 */
const val EDITED_TAG_COLOR = "#9ca2a8"
const val EDITED_TAG_MD_INNER_SIZE = 12

/** RN isMessageEdited.ts:4：`Boolean(message.editedBy)`（空串 false）。 */
fun isMessageEdited(message: MessageEntity): Boolean = !message.edited_by.isNullOrEmpty()

/** RN buildEditedTagToken :19-33：BOLD(color)+BOLD(size) 嵌套包 PLAIN_TEXT。 */
fun buildEditedTagToken(label: String): Bold = Bold(
    value = listOf(PlainText(label)),
    color = EDITED_TAG_COLOR,
    size = EDITED_TAG_MD_INNER_SIZE,
)

/**
 * 编辑标记指纹（RN nodeHasEditedTag 的 __editedTag 自定义属性在 Kotlin 类型化 AST 的等价物）：
 * Bold(color=#9ca2a8, size=12) 组合即 [buildEditedTagToken] 产物——渲染层 Bold 的 color+size
 * 双 styling 分支（InlineNodes）对该组合的输出即小号灰字，行为一致。
 */
fun isEditedTagToken(node: MdInline): Boolean =
    node is Bold && node.color == EDITED_TAG_COLOR && node.size == EDITED_TAG_MD_INNER_SIZE

/** RN hasEditedTagInMd :46-53：任一块含标记（含嵌套 label/value 递归；Link 的 label 数组同查）。 */
fun hasEditedTagInMd(root: Root?): Boolean {
    if (root == null) return false
    return root.blocks.any { blockHasEditedTag(it) }
}

private fun blockHasEditedTag(block: MdBlock): Boolean = when (block) {
    is Paragraph -> block.value.any(::inlineHasEditedTag)
    is Heading -> block.value.any(::inlineHasEditedTag)
    is BigEmoji -> block.value.any(::inlineHasEditedTag)
    is Quote -> block.value.any { b -> blockHasEditedTag(b) }
    is OrderedList -> listHasEditedTag(block.value)
    is UnorderedList -> listHasEditedTag(block.value)
    else -> false
}

private fun listHasEditedTag(items: List<MdNode>): Boolean = items.any { item ->
    if (item is ListItem) {
        item.value.any { child ->
            when (child) {
                is MdBlock -> blockHasEditedTag(child)
                is MdInline -> inlineHasEditedTag(child)
                else -> false
            }
        }
    } else {
        false
    }
}

private fun inlineHasEditedTag(node: MdInline): Boolean = when (node) {
    is Bold -> isEditedTagToken(node) || node.value.any(::inlineHasEditedTag)
    is Italic -> node.value.any(::inlineHasEditedTag)
    is Strike -> node.value.any(::inlineHasEditedTag)
    else -> false
}

/**
 * RN appendEditedTagToMd :157-166：已含标记原样返回；否则找「最后一个可追加 inline 的 value
 * 数组」（文档序深度优先，见 [findLastInlineList]）追加；无目标块则追加独立 PARAGRAPH。
 */
fun appendEditedTagToMd(root: Root, label: String): Root {
    if (hasEditedTagInMd(root)) return root
    val token = buildEditedTagToken(label)
    val target = findLastInlineList(root.blocks)
    if (target != null) {
        target.add(token)
        return root
    }
    return Root(root.blocks + Paragraph(value = listOf(token)))
}

/** RN findLastInlineArray 的 Kotlin 等价（ MutableList 直改——RN 是克隆后 push，调用方持有 [Root] 副本语义由调用方保证）。 */
private fun findLastInlineList(blocks: List<MdBlock>): MutableList<MdInline>? {
    var last: MutableList<MdInline>? = null

    fun visitInlines(inlines: List<MdInline>) {
        if (inlines.isEmpty()) return
        val mutable = inlines as? MutableList<MdInline>
        if (mutable != null) last = mutable
        for (node in inlines) {
            when (node) {
                // 叶子 inline（LINK/INLINE_CODE/MENTION/EMOJI/INLINE_KATEX）：停在其父级
                is Bold -> if (node.color == null && node.size == null) visitInlines(node.value)
                is Italic -> visitInlines(node.value)
                is Strike -> visitInlines(node.value)
                else -> Unit
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
            if (paragraph != null) visitInlines(paragraph.value)
        }
    }

    for (block in blocks) {
        when (block) {
            is Paragraph -> visitInlines(block.value)
            is Heading -> visitInlines(block.value)
            is BigEmoji -> visitInlines(block.value)
            is Quote -> last = findLastInlineList(block.value) ?: last
            is OrderedList -> visitListItems(block.value)
            is UnorderedList -> visitListItems(block.value)
            else -> Unit
        }
    }
    return last
}
