package cn.appia.im.feature.todo

import cn.appia.im.core.datastore.InMemoryKvStore
import cn.appia.im.core.network.api.TodoAttachment
import cn.appia.im.feature.todo.ui.todoCardDocPreview
import cn.appia.im.feature.todo.ui.todoCardViewerImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 附件跳转参数 + 卡片渲染纯逻辑（RN openTodoAttachment :20-45 鉴权参链 + TodoListCard 判定）：
 * 图片 → MediaViewer 条目（AttachmentUrlFormatter rc_uid/rc_token 鉴权同 RN resolveImagePreviewUri）；
 * 文件 → buildDocPreviewParamsFromFileLink；无 image_url 且无 title_link 两头落空都不跳。
 */
class TodoAttachmentJumpTest {

    private val user = "uid1"
    private val token = "tok1"
    private val server = "https://im.example.com"

    // ---- 图片附件 → MediaViewer（auth 参数对照 openTodoAttachment）----

    @Test
    fun `image attachment builds viewer with authed url`() {
        val att = TodoAttachment(imageUrl = "/file-upload/abc.jpg", title = "pic")

        val viewer = todoCardViewerImage(att, user, token, server)

        assertNotNull(viewer)
        val url = viewer!!.url
        assertTrue(url.startsWith("$server/file-upload/abc.jpg?"))
        assertTrue(url.contains("rc_uid=$user"))
        assertTrue(url.contains("rc_token=$token"))
    }

    @Test
    fun `image with external origin keeps host and appends auth`() {
        val att = TodoAttachment(imageUrl = "https://files.example.net/x.png")

        val viewer = todoCardViewerImage(att, user, token, server)

        assertTrue(viewer!!.url.startsWith("https://files.example.net/x.png?rc_uid="))
    }

    @Test
    fun `non-image attachment yields no viewer entry`() {
        val att = TodoAttachment(titleLink = "/file-upload/doc.pdf", title = "doc.pdf", type = "file")

        assertNull(todoCardViewerImage(att, user, token, server))
    }

    // ---- 文件附件 → DocPreview（buildDocPreviewParamsFromFileLink 同参）----

    @Test
    fun `file attachment builds doc preview with fileId type and download url`() {
        val att = TodoAttachment(
            titleLink = "$server/file-upload/rooms/abc/report.v2.pdf?rc_uid=x",
            title = "report",
        )

        val params = todoCardDocPreview(att, user, token, server)

        assertNotNull(params)
        params!!
        assertEquals("report", params.title)
        assertEquals("abc", params.fileId) // /file-upload/rooms/{fileId}/name 段
        assertEquals("pdf", params.fileType)
        assertTrue(params.downloadUrl.contains("/file-proxy/"))
        assertTrue(params.downloadUrl.contains("rc_uid=$user"))
        assertTrue(params.downloadUrl.contains("rc_token=$token"))
    }

    @Test
    fun `file without title_link is not openable`() {
        val att = TodoAttachment(title = "orphan.bin")

        assertNull(todoCardDocPreview(att, user, token, server))
        assertNull(todoCardViewerImage(att, user, token, server))
    }

    // ---- 卡片渲染判定 ----

    @Test
    fun `high tag is type h`() {
        assertEquals("todo_hightag", tagKeyFor(type = "h"))
        assertEquals("todo_defaulttag", tagKeyFor(type = "d"))
        assertEquals("todo_defaulttag", tagKeyFor(type = null))
    }

    @Test
    fun `stripMarkdownLite removes emphasis markers and newlines`() {
        assertEquals("hello world", stripMarkdownLite("**hello**\nworld"))
        assertEquals("a b c", stripMarkdownLite("`a` ~~b~~ __c__"))
    }

    @Test
    fun `empty title renders no title line`() {
        // RN :86-90：stripMarkdownLite 后空串不渲染标题行（Compose 侧以 title.isEmpty() 分支同源）
        assertEquals("", stripMarkdownLite("**__"))
    }

    @Test
    fun `room display name shows agent label for myAgents self room`() {
        val kv = InMemoryKvStore().apply { putString("AGEMNT_ROOM_ID_KEY_https://im.example.comme", "agent-rid") }

        assertEquals(
            "myAgents",
            todoRoomDisplayName(rid = "agent-rid", name = "me", username = "me", serverUrl = server, agentLabel = "myAgents", kv = kv),
        )
        assertEquals(
            "dev room",
            todoRoomDisplayName(rid = "r9", name = "dev room", username = "me", serverUrl = server, agentLabel = "myAgents", kv = kv),
        )
        assertEquals("", todoRoomDisplayName(rid = null, name = null, username = "me", serverUrl = server, agentLabel = "myAgents", kv = kv))
    }

    @Test
    fun `formatUtcYmdHm renders local zone with offset label and falls back on bad iso`() {
        assertEquals(
            "2026/03/01 08:30 (UTC+8)",
            formatUtcYmdHm("2026-03-01T00:30:00.000Z", "/", java.util.TimeZone.getTimeZone("Asia/Shanghai")),
        )
        assertEquals(
            "2026-03-01 00:30 (UTC+0)",
            formatUtcYmdHm("2026-03-01T00:30:00.000Z", "-", java.util.TimeZone.getTimeZone("UTC")),
        )
        // 半小时偏移（RN 数字直转 +5.5）
        assertEquals(
            "2026/03/01 06:00 (UTC+5.5)",
            formatUtcYmdHm("2026-03-01T00:30:00.000Z", "/", java.util.TimeZone.getTimeZone("Asia/Kolkata")),
        )
        assertEquals("not-a-date", formatUtcYmdHm("not-a-date", "/"))
    }

    @Test
    fun `todoReminderIso renders UTC wire form`() {
        assertEquals("2023-11-14T22:13:20.000Z", todoReminderIso(1_700_000_000_000L))
    }

    @Test
    fun `badge text caps at 99 plus and hides zero`() {
        assertNull(todoBadgeText(0))
        assertEquals("1", todoBadgeText(1))
        assertEquals("99", todoBadgeText(99))
        assertEquals("99+", todoBadgeText(100))
    }

    @Test
    fun `coerceRoomType falls back to channel on unknown`() {
        assertEquals("p", coerceRoomType("p"))
        assertEquals("c", coerceRoomType(null))
        assertEquals("c", coerceRoomType("weird"))
    }

    private fun tagKeyFor(type: String?): String = if (type == "h") "todo_hightag" else "todo_defaulttag"
}
