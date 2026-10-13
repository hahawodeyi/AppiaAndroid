package cn.appia.im.feature.mail

import cn.appia.im.core.mail.MailFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件夹映射（mailFolderUtils.test.ts 移植——坑 11 钉死）：
 * specialUse 精确匹配 / path 正则兜底 / name 回退 / IMAP Drafts 隐藏 / Virus Items 过滤 /
 * 虚拟 __drafts__ / Trash 判定。
 */
class MailFolderUtilsTest {

    /** i18n 注入假件：key → 固定标记词（断言不依赖 locale）。 */
    private val t: (String) -> String = { key -> "[$key]" }
    private fun folder(
        path: String,
        name: String = path,
        specialUse: String? = null,
    ) = MailFolder(path = path, name = name, specialUse = specialUse)

    // ── resolveMailFolderLabel（mailFolderUtils.ts:14-27）──

    @Test
    fun `label maps specialUse and path regex for all five folders`() {
        // specialUse 精确匹配五组（path 不参与判定）
        assertEquals("[mail_folder_inbox]", resolveMailFolderLabel(folder("x", specialUse = "\\Inbox"), t))
        assertEquals("[mail_folder_sent]", resolveMailFolderLabel(folder("x", specialUse = "\\Sent"), t))
        assertEquals("[mail_folder_drafts]", resolveMailFolderLabel(folder("x", specialUse = "\\Drafts"), t))
        assertEquals("[mail_folder_trash]", resolveMailFolderLabel(folder("x", specialUse = "\\Trash"), t))
        assertEquals("[mail_folder_junk]", resolveMailFolderLabel(folder("x", specialUse = "\\Junk"), t))
        // path 正则兜底（大小写不敏感；specialUse null）
        assertEquals("[mail_folder_inbox]", resolveMailFolderLabel(folder("INBOX"), t))
        assertEquals("[mail_folder_inbox]", resolveMailFolderLabel(folder("inbox"), t))
        assertEquals("[mail_folder_sent]", resolveMailFolderLabel(folder("My-Sent-Messages"), t))
        assertEquals("[mail_folder_trash]", resolveMailFolderLabel(folder("Deleted Items"), t))
        assertEquals("[mail_folder_junk]", resolveMailFolderLabel(folder("Spam"), t))
        // junk|spam 与 trash|deleted 双形态
        assertEquals("[mail_folder_junk]", resolveMailFolderLabel(folder("JUNK"), t))
        assertEquals("[mail_folder_trash]", resolveMailFolderLabel(folder("trash-bin"), t))
    }

    @Test
    fun `label drafts path hits drafts and virtual folder maps first`() {
        // 虚拟 __drafts__ 置于判定首位（即使含 draft 字样也先命中 drafts key）
        assertEquals("[mail_folder_drafts]", resolveMailFolderLabel(createDraftsVirtualFolder(), t))
        // 普通 draft 路径也映射草稿（但该夹会被 shouldShowFolder 隐藏）
        assertEquals("[mail_folder_drafts]", resolveMailFolderLabel(folder("Mail/Drafts"), t))
    }

    @Test
    fun `label falls back to name then path for unmatched folders`() {
        // name 优先
        assertEquals("Custom", resolveMailFolderLabel(folder("x-dir", "Custom"), t))
        // name 空白 → path
        assertEquals("x-dir", resolveMailFolderLabel(folder("x-dir", "  "), t))
    }

    @Test
    fun `label string overload wraps path`() {
        assertEquals("[mail_folder_inbox]", resolveMailFolderLabel("INBOX", t))
        assertEquals("archive", resolveMailFolderLabel("archive", t))
    }

    // ── shouldShowFolder（mailFolderUtils.ts:37-43）──

    @Test
    fun `shouldShowFolder hides IMAP drafts and virus items`() {
        // IMAP Drafts（specialUse 或 draft 字样）隐藏——坑 11
        assertFalse(shouldShowFolder(folder("Drafts", specialUse = "\\Drafts")))
        assertFalse(shouldShowFolder(folder("Mail/Drafts")))
        assertFalse(shouldShowFolder(folder("x", name = "my drafts")))
        // Virus Items 永久过滤
        assertFalse(shouldShowFolder(folder("Virus Items")))
        assertFalse(shouldShowFolder(folder("y", name = "virus items")))
        // 普通夹可见
        assertTrue(shouldShowFolder(folder("INBOX")))
        assertTrue(shouldShowFolder(folder("Sent", specialUse = "\\Sent")))
        assertTrue(shouldShowFolder(folder("Archive")))
        // 虚拟 __drafts__ 恒可见
        assertTrue(shouldShowFolder(createDraftsVirtualFolder()))
    }

    // ── isDraftsFolderPath / createDraftsVirtualFolder ──

    @Test
    fun `drafts path detection and virtual folder shape`() {
        assertTrue(isDraftsFolderPath("__drafts__"))
        assertFalse(isDraftsFolderPath("INBOX"))
        assertFalse(isDraftsFolderPath("Drafts")) // IMAP Drafts 不是虚拟草稿夹
        val v = createDraftsVirtualFolder()
        assertEquals("__drafts__", v.path)
        assertEquals("__drafts__", v.name)
        assertTrue(v.flags.isEmpty())
    }

    // ── isMailTrashFolder（mailFolderUtils.ts:29-35）──

    @Test
    fun `trash folder detection covers specialUse path name and cn aliases`() {
        val folders = listOf(
            folder("1", "Trash", specialUse = "\\Trash"),
            folder("2", "Trash Bin"),
            folder("3", "\u5df2\u5220\u9664"), // yi shan chu (deleted-CN)
            folder("4", "\u5783\u573e\u7bb1"), // la ji tong (trashcan-CN)
        )
        assertTrue(isMailTrashFolder("1", folders)) // specialUse
        assertTrue(isMailTrashFolder("2", folders)) // name 含 trash
        assertTrue(isMailTrashFolder("3", folders)) // 中文别名（经 name）
        assertTrue(isMailTrashFolder("4", folders)) // 中文别名（经 name）
        assertTrue(isMailTrashFolder("Deleted Items", emptyList())) // path 直命中
        assertFalse(isMailTrashFolder("INBOX", folders))
        assertFalse(isMailTrashFolder("5", emptyList())) // 无 name 上下文 → 不命中
    }
}
