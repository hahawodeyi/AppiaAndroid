package cn.appia.im.feature.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * M8-T5 AntMeeting 移交测试（RN InAppWebScreen:135-141 + openLink.ts:54-55 对照）：
 * 移交判定（AntMeeting 形状 vs 其他 URL）≥6 + openLink 去向门控 ≥4。
 * 纯函数无 Android 依赖（openLink 本体仅 dispatch [openLinkRoute]，Context 触达零）。
 */
class MeetingHandoffTest {

    private val server = "https://ssc.antagent.space"

    // ── resolveAntMeetingHandoff（移交判定 ≥6）──

    @Test
    fun `handoff home url with trailing slash passes through`() {
        assertEquals("https://ssc.antagent.space/AntMeeting/", resolveAntMeetingHandoff("https://ssc.antagent.space/AntMeeting/", server))
    }

    @Test
    fun `handoff meeting number path passes through`() {
        val u = "https://ssc.antagent.space/AntMeeting/123456"
        assertEquals(u, resolveAntMeetingHandoff(u, server))
    }

    @Test
    fun `handoff relative path joins serverUrl`() {
        assertEquals("$server/AntMeeting/x", resolveAntMeetingHandoff("/AntMeeting/x", server))
    }

    @Test
    fun `handoff rejects non meeting urls`() {
        assertNull(resolveAntMeetingHandoff("https://example.com/AntMeetingPage/x", server))
        assertNull(resolveAntMeetingHandoff("https://meeting.tencent.com/dm/r/abc", server))
        assertNull(resolveAntMeetingHandoff("wemeet://page/inmeeting?meeting_code=1", server))
    }

    @Test
    fun `handoff rejects empty and blank`() {
        assertNull(resolveAntMeetingHandoff(null, server))
        assertNull(resolveAntMeetingHandoff("", server))
        assertNull(resolveAntMeetingHandoff("   ", server))
    }

    @Test
    fun `handoff path match is case insensitive`() {
        val u = "https://ssc.antagent.space/antmeeting/9"
        assertEquals(u, resolveAntMeetingHandoff(u, server))
    }

    @Test
    fun `handoff absolute url from another host still matches path shape`() {
        // RN isAntMeetingHttpUrl 只看 path 形状（跨域 AntMeeting 同移交，resolveAntMeetingUrl 通过）
        val u = "https://other.host/AntMeeting/7"
        assertEquals(u, resolveAntMeetingHandoff(u, server))
    }

    @Test
    fun `handoff boundary antmeeting prefix without slash is not matched`() {
        assertNull(resolveAntMeetingHandoff("https://ssc.antagent.space/AntMeetingX/1", server))
    }

    // ── openLinkRoute（inApp 分支门控 ≥4）──

    @Test
    fun `openLink inApp plus ant meeting hands off to native`() {
        assertEquals(OpenLinkTarget.ANT_MEETING_HANDOFF, openLinkRoute("inApp", "$server/AntMeeting/1"))
    }

    @Test
    fun `openLink inApp plain url goes in app web`() {
        assertEquals(OpenLinkTarget.IN_APP_WEB, openLinkRoute("inApp", "https://example.com/x"))
    }

    @Test
    fun `openLink system pref never hands off even for ant meeting`() {
        // RN 门控仅 browser==='inApp'：系统浏览器 pref 走 ACTION_VIEW
        assertEquals(OpenLinkTarget.SYSTEM, openLinkRoute("systemDefault", "$server/AntMeeting/1"))
    }

    @Test
    fun `openLink needVPN whitelist forces in app regardless of pref`() {
        assertEquals(OpenLinkTarget.IN_APP_WEB, openLinkRoute("systemDefault", "https://lexiang.appia.cn/x"))
    }

    @Test
    fun `openLink system plain url goes system`() {
        assertEquals(OpenLinkTarget.SYSTEM, openLinkRoute("systemDefault", "https://example.com/x"))
    }
}
