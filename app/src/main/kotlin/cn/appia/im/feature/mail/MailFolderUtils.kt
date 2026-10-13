package cn.appia.im.feature.mail

import cn.appia.im.core.mail.MailFolder

/**
 * 文件夹映射/可见性（mailFolderUtils.ts 逐行为移植——坑 11 钉死）：
 * specialUse `\Inbox/\Sent/\Drafts/\Trash/\Junk` 精确匹配，path 正则兜底
 * （sent/draft/trash|deleted/junk|spam，大小写不敏感），否则 name/path 回退；
 * IMAP Drafts 系文件夹隐藏（草稿走虚拟 `__drafts__` 行——服务端 /drafts 端点为准），
 * Virus Items 永久过滤。
 */

/** mailFolderUtils.ts:5-6 isDraftsFolderPath。 */
fun isDraftsFolderPath(folderPath: String): Boolean = folderPath == MAIL_DRAFTS_FOLDER

/** mailFolderUtils.ts:8-12 createDraftsVirtualFolder：path/name 同 `__drafts__`，flags 空。 */
fun createDraftsVirtualFolder(): MailFolder =
    MailFolder(path = MAIL_DRAFTS_FOLDER, name = MAIL_DRAFTS_FOLDER, specialUse = null, flags = emptyList())

/**
 * mailFolderUtils.ts:14-27 resolveMailFolderLabel：folder 对象或 path 字符串双形态；
 * t 即 Context.t 的函数化注入（测试可换 identity）。
 */
fun resolveMailFolderLabel(folder: MailFolder, t: (key: String) -> String): String {
    val path = folder.path
    return when {
        isDraftsFolderPath(path) -> t("mail_folder_drafts")
        folder.specialUse == "\\Inbox" || path.equals("INBOX", ignoreCase = true) -> t("mail_folder_inbox")
        folder.specialUse == "\\Sent" || Regex("sent", RegexOption.IGNORE_CASE).containsMatchIn(path) ->
            t("mail_folder_sent")
        folder.specialUse == "\\Drafts" || Regex("draft", RegexOption.IGNORE_CASE).containsMatchIn(path) ->
            t("mail_folder_drafts")
        folder.specialUse == "\\Trash" || Regex("trash|deleted", RegexOption.IGNORE_CASE).containsMatchIn(path) ->
            t("mail_folder_trash")
        folder.specialUse == "\\Junk" || Regex("junk|spam", RegexOption.IGNORE_CASE).containsMatchIn(path) ->
            t("mail_folder_junk")
        else -> folder.name.ifBlank { path }
    }
}

/** path 字符串形态（RN resolveMailFolderLabel(folder: string) 重载分支）。 */
fun resolveMailFolderLabel(path: String, t: (key: String) -> String): String =
    resolveMailFolderLabel(MailFolder(path = path, name = path), t)

/**
 * mailFolderUtils.ts:29-35 isMailTrashFolder：specialUse `\Trash` 或
 * path+name 含 trash/deleted/已删除/垃圾桶（已删除/垃圾箱——服务端
 * 文件夹名匹配的功能性数据，非 UI 文案；删除二次确认的「永久删除」判定，T4 用）。
 */
fun isMailTrashFolder(folderPath: String, folders: List<MailFolder>): Boolean {
    val folder = folders.firstOrNull { it.path == folderPath }
    if (folder?.specialUse == "\\Trash") return true
    val text = "$folderPath ${folder?.name.orEmpty()}".lowercase()
    return Regex("trash|deleted|\u5df2\u5220\u9664|\u5783\u573e\u7bb1").containsMatchIn(text)
}

/**
 * mailFolderUtils.ts:37-43 shouldShowFolder：虚拟 drafts 恒可见；
 * specialUse `\Drafts` 或 path+name 含 draft → 隐藏（IMAP 草稿目录不进列表）；
 * Virus Items 隐藏；其余可见。
 */
fun shouldShowFolder(folder: MailFolder): Boolean {
    if (isDraftsFolderPath(folder.path)) return true
    val path = folder.path.lowercase()
    val name = folder.name.lowercase()
    if (folder.specialUse == "\\Drafts" || Regex("draft").containsMatchIn("$path $name")) return false
    return !Regex("virus items").containsMatchIn("$path $name")
}
