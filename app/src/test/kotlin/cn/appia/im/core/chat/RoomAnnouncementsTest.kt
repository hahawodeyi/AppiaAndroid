package cn.appia.im.core.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 公告解析纯函数（RN src/lib/chat/roomAnnouncements.test.ts 全量移植）：
 * 嵌入文件拆分 / 双重编码 / 合并去重 / meeting|summary 分类 / 预览文本（HTML 剥离+文件名回退）。
 */
class RoomAnnouncementsTest {

    private val sep = ANNOUNCEMENT_FILE_SEPARATOR

    // ---- parseAnnouncementEmbeddedFiles（RN :15-30）----

    @Test
    fun `splits text and file pairs`() {
        val msg = "Hello${sep}a.pdf${sep}https://x/a.pdf"
        val (message, files) = parseAnnouncementEmbeddedFiles(msg)
        assertEquals("Hello", message)
        assertEquals(1, files.size)
        assertEquals("a.pdf", files[0].fileName)
        assertEquals("https://x/a.pdf", files[0].fileUrl)
        assertEquals("pdf", files[0].fileType)
    }

    @Test
    fun `returns plain message when no separator`() {
        val (message, files) = parseAnnouncementEmbeddedFiles("plain")
        assertEquals("plain", message)
        assertTrue(files.isEmpty())
    }

    // ---- parseRoomAnnouncements（RN :38-131）----

    @Test
    fun `merges announcement into announcements when id missing`() {
        val ann = """{"_id":"a1","message":"only","announcementType":0}"""
        val list = """[{"_id":"b1","message":"other"}]"""
        val result = parseRoomAnnouncements(ann, list)
        assertEquals(listOf("b1", "a1"), result.map { it.id })
    }

    @Test
    fun `does not duplicate announcement when id already in list`() {
        val ann = """{"_id":"b1","message":"dup"}"""
        val list = """[{"_id":"b1","message":"other"}]"""
        assertEquals(1, parseRoomAnnouncements(ann, list).size)
    }

    @Test
    fun `includes meeting announcements in list`() {
        val list = """[
            {"_id":"m1","announcementType":1,"meetingRoomBookRecord":{"subject":"M"}},
            {"_id":"n1","message":"normal","announcementType":0}
        ]"""
        val result = parseRoomAnnouncements(null, list)
        assertEquals(2, result.size)
        assertTrue(result.any { it.id == "m1" })
    }

    @Test
    fun `parses stringified meetingRoomBookRecord on announcements`() {
        val list = """[{
            "_id":"m2",
            "announcementType":1,
            "meetingRoomBookRecord":"{\"subject\":\"Parsed\",\"startTime\":\"2026-06-24 14:00:00\",\"endTime\":\"2026-06-24 15:00:00\"}"
        }]"""
        val result = parseRoomAnnouncements(null, list)
        val record = result[0].meetingRoomBookRecord
        assertEquals("Parsed", record?.get("subject")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        val start = record?.get("startTime")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
        assertTrue(start.orEmpty().contains("2026-06-24"))
    }

    @Test
    fun `returns empty for invalid announcements json`() {
        assertTrue(parseRoomAnnouncements(null, "also-bad").isEmpty())
    }

    @Test
    fun `parses plain text announcement field`() {
        val result = parseRoomAnnouncements("Hello from plain text", null)
        assertEquals(1, result.size)
        assertEquals("Hello from plain text", result[0].message)
    }

    @Test
    fun `parses double-encoded announcement json`() {
        val inner = """{"_id":"x1","message":"nested"}"""
        val wrapped = "\"" + inner.replace("\"", "\\\"") + "\""
        val result = parseRoomAnnouncements(wrapped, null)
        assertEquals(1, result.size)
        assertEquals("nested", result[0].message)
    }

    @Test
    fun `includes announcement with files only`() {
        val ann = """{"_id":"f1","message":"","files":[{"fileName":"a.pdf","fileUrl":"https://x/a.pdf"}]}"""
        assertEquals(1, parseRoomAnnouncements(ann, null).size)
    }

    // ---- 预览（RN :106-131）----

    @Test
    fun `getAnnouncementPreviewText reads plain and json message`() {
        assertEquals("plain", getAnnouncementPreviewText("plain", null))
        assertEquals("json", getAnnouncementPreviewText("""{"message":" json "}""", null))
    }

    @Test
    fun `getAnnouncementPreviewText reads announcements list when announcement empty`() {
        val list = """[{"_id":"a1","message":"from list"}]"""
        assertEquals("from list", getAnnouncementPreviewText(null, list))
    }

    @Test
    fun `getAnnouncementPreviewText strips html and falls back to file name`() {
        assertEquals(
            "Hello world",
            getAnnouncementPreviewText("""{"message":"<p>Hello<br/>world</p>"}""", null),
        )
        assertEquals(
            "report.pdf",
            getAnnouncementPreviewText(
                """{"files":[{"fileName":"report.pdf","fileUrl":"https://x/r.pdf"}]}""",
                null,
            ),
        )
    }

    @Test
    fun `getAnnouncementPreviewText takes latest item`() {
        val list = """[{"_id":"a1","message":"first"},{"_id":"a2","message":"second"}]"""
        assertEquals("second", getAnnouncementPreviewText(null, list))
    }

    // ---- meeting vs announcement 分类（RN :134-182）----

    private val booking = """
        {"_id":"b1","announcementType":1,
         "meetingRoomBookRecord":{"subject":"Weekly","startTime":"2026-06-25 10:00:00","endTime":"2026-06-25 11:00:00"}}
    """.trimIndent()

    private val minutes = """{"_id":"s1","announcementType":2,"meeting":{"topic":"Summary A"}}"""

    private val normal = """{"_id":"n1","message":"notice","announcementType":0}"""

    private fun listJson(vararg items: String): String = items.joinToString(",", "[", "]")

    @Test
    fun `parseMainAnnouncements excludes meeting panel items`() {
        val list = listJson(booking, minutes, normal)
        assertEquals(listOf("n1"), parseMainAnnouncements(null, list).map { it.id })
    }

    @Test
    fun `parseMeetingPanelAnnouncements includes booking and summary`() {
        val list = listJson(booking, minutes, normal)
        assertEquals(listOf("b1", "s1"), parseMeetingPanelAnnouncements(null, list).map { it.id })
    }

    @Test
    fun `isMeetingBooking and isMeetingMinutes classify correctly`() {
        val bookingItem = parseRoomAnnouncements(null, listJson(booking))[0]
        val minutesItem = parseRoomAnnouncements(null, listJson(minutes))[0]
        val normalItem = parseRoomAnnouncements(null, listJson(normal))[0]
        assertTrue(isMeetingBooking(bookingItem))
        assertFalse(isMeetingMinutes(bookingItem))
        assertTrue(isMeetingMinutes(minutesItem))
        assertTrue(isMainAnnouncement(normalItem))
    }

    @Test
    fun `getAnnouncementPreviewText ignores meetings`() {
        val list = listJson(booking, minutes, normal)
        assertEquals("notice", getAnnouncementPreviewText(null, list))
    }

    @Test
    fun `getMeetingPreviewText prefers latest booking subject`() {
        val list = listJson(booking, minutes, normal)
        assertEquals("Weekly", getMeetingPreviewText(null, list))
    }

    @Test
    fun `getMeetingPreviewText falls back to minutes topic`() {
        assertEquals("Summary A", getMeetingPreviewText(null, listJson(minutes)))
    }

    // ---- display 格式化（RN :32-36）----

    @Test
    fun `formatAnnouncementMessageForDisplay converts newlines to br tags`() {
        assertEquals("a<br>b", formatAnnouncementMessageForDisplay("a\nb"))
        assertEquals("a<br>b", formatAnnouncementMessageForDisplay("a\r\nb"))
    }

    // ---- 嵌入文件拆分进 normalize（RN :122-138：无 files 字段时 message 内拆分）----

    @Test
    fun `normalizes embedded files from message when files field absent`() {
        val ann = "Hi${sep}doc.pdf${sep}https://x/doc.pdf"
        val result = parseRoomAnnouncements(ann, null)
        assertEquals("Hi", result[0].message)
        assertEquals("doc.pdf", result[0].files[0].fileName)
    }

    @Test
    fun `empty items are filtered out`() {
        val list = """[{"_id":"e1","message":"  "},{"_id":"e2","message":"ok"}]"""
        val result = parseRoomAnnouncements(null, list)
        assertEquals(listOf("e2"), result.map { it.id })
    }
}
