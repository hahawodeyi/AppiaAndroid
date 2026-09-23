package cn.appia.im.feature.roominfo.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.i18n.t
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.permissions.PermissionsStore
import cn.appia.im.core.theme.AppiaTheme
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 改名屏 wire（RN RoomChannelNameEditScreen handleSave :35-51）：
 * saveRoomSettings {roomName} 成功 → onBack；method.call 错误信封
 * reason 含 error-invalid-room-name → i18n error_invalid_room_name 插值 Alert；
 * 其余错误原文 Alert。传输三形态（params 编码）已在 RoomSettingsApiTest 钉死。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RoomChannelNameEditActionsTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
        PermissionsStore.reset()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        PermissionsStore.reset()
    }

    private fun newSdk(): RocketSdk =
        RocketSdk(client = OkHttpClient()).also { it.hydrateRestSession(server.url("/").toString(), "tok", "uid") }

    /** method.call 错误信封：message = DDP 帧 JSON 串，error.reason 进 ApiException message。 */
    private fun errorEnvelope(reason: String): String =
        """{"message":"{\"error\":{\"reason\":\"$reason\",\"message\":\"$reason\"}}"}"""

    private fun chatRow(): ChatEntity = ChatEntity(
        _id = "r1", f = false, t = "c", ts = 0.0, ls = 0.0, name = "dev-room", fname = "Dev Room",
        rid = "r1", open = true, alert = false, unread = 0.0, user_mentions = 0.0,
        group_mentions = 0.0, room_updated_at = 0.0, ro = false, archived = false,
        auto_translate_language = "en", team_id = "",
    )

    private var backCount = 0

    private fun setContent(sdk: RocketSdk?) {
        rule.setContent {
            AppiaTheme(isDark = false) {
                RoomChannelNameEditScreen(
                    rid = "r1",
                    roomType = "c",
                    chat = chatRow(),
                    sdk = sdk,
                    onBack = { backCount++ },
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `save success calls back`() = runBlocking {
        setContent(newSdk())
        server.enqueue(
            MockResponse().setBody("""{"message":"{\"result\":true,\"rid\":\"r1\"}"}"""),
        )
        rule.onNodeWithTag("qa-channel-name-input").performTextClearance()
        rule.onNodeWithTag("qa-channel-name-input").performTextInput("New Name")
        rule.onNodeWithTag("qa-channel-name-save").performClick()
        rule.waitForIdle()

        assertEquals("/api/v1/method.call/saveRoomSettings", server.takeRequest().path)
        rule.waitUntil(5_000) { backCount == 1 }
    }

    @Test
    fun `invalid room name error maps to i18n friendly alert`() {
        setContent(newSdk())
        server.enqueue(MockResponse().setBody(errorEnvelope("error-invalid-room-name")))
        rule.onNodeWithTag("qa-channel-name-input").performTextClearance()
        rule.onNodeWithTag("qa-channel-name-input").performTextInput("bad name")
        rule.onNodeWithTag("qa-channel-name-save").performClick()
        val expected = context.t("error_invalid_room_name").replace("{{room_name}}", "bad name")
        rule.waitUntil(5_000) { rule.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(context.t("error_title")).assertExists()

        assertEquals(0, backCount) // 失败不返回
    }

    @Test
    fun `other errors show raw message alert`() {
        setContent(newSdk())
        server.enqueue(MockResponse().setBody(errorEnvelope("boom")))
        rule.onNodeWithTag("qa-channel-name-input").performTextClearance()
        rule.onNodeWithTag("qa-channel-name-input").performTextInput("New Name")
        rule.onNodeWithTag("qa-channel-name-save").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("boom").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, backCount)
    }
}
