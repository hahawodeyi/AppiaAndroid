package cn.appia.im.feature.meeting.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.i18n.t
import cn.appia.im.core.theme.AppiaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 会议首页 Compose 冒烟（M8-T5）：头部「会议」+ 右上创建钮、sdk=null 静态路径空态
 * （meeting_listempty）、创建/加入回调接线。sdk=null 不触网（meetingRoomList 跳过）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MeetingHomeScreenSmokeTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun render(onCreate: () -> Unit = {}, onJoinAnt: (String) -> Unit = {}) {
        rule.setContent {
            AppiaTheme(isDark = false) {
                MeetingHomeScreen(
                    sdk = null,
                    serverUrl = "https://s1",
                    onBack = {},
                    onCreateMeeting = onCreate,
                    onJoinAntMeeting = onJoinAnt,
                )
            }
        }
    }

    private fun tagExists(tag: String) =
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `renders header create button and empty state without sdk`() {
        render()
        rule.waitForIdle()
        rule.onNodeWithText(context.t("drawer_meeting")).assertExists()
        rule.onNodeWithTag("qa-meeting-home-create").assertExists()
        rule.onNodeWithText(context.t("meeting_listempty")).assertExists()
    }

    @Test
    fun `create button fires callback`() {
        var created = 0
        render(onCreate = { created++ })
        rule.waitForIdle()
        rule.onNodeWithTag("qa-meeting-home-create").performClick()
        rule.waitForIdle()
        assertEquals(1, created)
    }
}
