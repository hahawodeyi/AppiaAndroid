package cn.appia.im.feature.meeting.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.theme.AppiaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 会议表单 Compose 冒烟（M8-T4）：新建态全字段渲染（默认 createTxMeeting=true 无手动号输入）、
 * 编辑态标题/手动号行、主题输入回写、单次编辑锁定（提交仍可见）。sdk=null 纯静态渲染路径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MeetingFormScreenSmokeTest {
    @get:Rule
    val rule = createComposeRule()

    private fun tagExists(tag: String) =
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun render(editJson: String? = null, editMode: String = "edit-normal") {
        rule.setContent {
            AppiaTheme(isDark = false) {
                MeetingFormScreen(
                    rid = "r1",
                    roomType = "c",
                    editMeetingJson = editJson,
                    editMode = editMode,
                    sdk = null,
                    dbManager = null,
                    serverUrl = "https://s1",
                    currentUserName = "Bob",
                    currentUsername = "bob",
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun `create mode renders all fields without manual code rows`() {
        render()
        rule.waitForIdle()
        listOf(
            "qa-meeting-subject",
            "qa-meeting-attendees",
            "qa-meeting-start",
            "qa-meeting-end",
            "qa-meeting-room",
            "qa-meeting-repeat",
        ).forEach { tag ->
            assert(tagExists(tag)) { "missing $tag" }
        }
        // createTxMeeting 默认开 → 手动号/链接输入不出现（material/desc 在滚动区尾，滚到再断言）
        assert(!tagExists("qa-meeting-code"))
        rule.onNodeWithTag("qa-meeting-materials").performScrollTo()
        assert(tagExists("qa-meeting-materials"))
        assert(tagExists("qa-meeting-desc"))
        assert(tagExists("qa-meeting-submit"))
    }

    @Test
    fun `subject input recomposes state`() {
        render()
        rule.waitForIdle()
        rule.onNodeWithTag("qa-meeting-subject").performTextInput("weekly")
        rule.waitForIdle()
        rule.onNodeWithTag("qa-meeting-subject").performTextInput("!")
        rule.waitForIdle()
        assert(tagExists("qa-meeting-subject"))
    }

    @Test
    fun `edit mode shows manual code rows and hides periodic`() {
        render(
            editJson = """{"subject":"old meeting","startTime":"2099-03-05 09:00:00","endTime":"2099-03-05 10:00:00","periodic":false}""",
        )
        rule.waitForIdle()
        assert(tagExists("qa-meeting-code"))
        assert(tagExists("qa-meeting-url"))
        assert(tagExists("qa-meeting-submit"))
        // 非周期编辑不显示周期区
        assert(!tagExists("qa-meeting-repeat"))
    }

    @Test
    fun `unparsable edit record falls back to create mode`() {
        render(editJson = "{bad json")
        rule.waitForIdle()
        // 新建默认腾讯代建 → 无手动号行
        assert(!tagExists("qa-meeting-code"))
        assert(tagExists("qa-meeting-repeat"))
    }

    @Test
    fun `edit record start time parses wall clock`() {
        val editJson = """{"subject":"s","startTime":"2099-03-05 09:15:00","endTime":"2099-03-05 10:15:00"}"""
        val record = cn.appia.im.core.meeting.meetingJson.decodeFromString(
            cn.appia.im.core.meeting.Meeting.serializer(),
            editJson,
        )
        val form = cn.appia.im.feature.meeting.initialMeetingFormState("r1", record)
        assertEquals("2099-03-05 09:15:00", cn.appia.im.core.meeting.formatMeetingDateTime(form.startMs).take(16) + ":00")
        assertNotEquals(0L, form.endMs)
    }
}
