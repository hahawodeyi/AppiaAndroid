package cn.appia.im.core.messaging

import cn.appia.im.core.database.entity.MessageEntity

/**
 * RN isMessageEdited.ts:4：`Boolean(message.editedBy)`（空串 false）。
 * "(edited)" 标记渲染（有 md 行内尾随 / 无 md 独立行）在 MessageRow.buildMessageBody
 * ——RN 的 appendEditedTagToMd AST 注入路径在本仓库由 span 直追等价实现（评审 Minor-6 清 YAGNI）。
 */
fun isMessageEdited(message: MessageEntity): Boolean = !message.edited_by.isNullOrEmpty()
