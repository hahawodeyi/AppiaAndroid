package cn.appia.im.feature.contacts.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.i18n.t
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.theme.AppiaTheme
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
import java.util.Base64

/**
 * MyCard/MemberProfile Compose 冒烟（Robolectric，T9）：
 * - MyCard：data URI 二维码位图化上屏、企业名下缘、错误态重试可见
 * - MemberProfile：users.info mock → 邮箱行、发消息回调参数透传、语音按钮禁用占位
 * 深逻辑（7 级回退/DM 三段）在 QrcodeFallbackTest/OpenDirectMessageTest 覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MyCardMemberProfileSmokeTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun newSdk(): RocketSdk =
        RocketSdk(client = OkHttpClient())
            .also { it.hydrateRestSession(server.url("/").toString(), "tok", "uid") }

    private fun tagExists(tag: String): Boolean =
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun textExists(text: String): Boolean =
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** 1x1 RGBA PNG（最小合法位图——BitmapFactory 可解码）。 */
    private val tinyPngBase64: String by lazy {
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0, 0, 0, 1, 0, 0, 0, 1,
            0x08, 0x06, 0, 0, 0, 0x1F.toByte(), 0x15.toByte(), 0xC4.toByte(), 0x89.toByte(),
            0, 0, 0, 0x0A, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C.toByte(), 0x63, 0, 1, 0, 0, 0x05, 0, 0x01, 0x0D, 0x0A, 0x2D.toByte(), 0xB4.toByte(),
            0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )
        Base64.getEncoder().encodeToString(png)
    }

    private fun enqueueQrSuccess() {
        // 7 级回退第 1 级即命中（GET qrcode.query 无参）
        server.enqueue(
            MockResponse().setBody("""{"imgUrl":"data:image/png;base64,$tinyPngBase64","expire":99}"""),
        )
    }

    // ── MyCard ──

    @Test
    fun `mycard renders qr bitmap from data uri and enterprise name`() {
        enqueueQrSuccess()
        rule.setContent {
            AppiaTheme(isDark = false) {
                MyCardScreen(
                    sdk = newSdk(),
                    enterpriseName = "Acme Inc",
                    serverUrl = "https://s1",
                    currentUserId = "u1",
                    currentUsername = "alice",
                    displayName = "Alice Zhang",
                    token = "tok",
                    onBack = {},
                )
            }
        }
        rule.waitForIdle()
        // 位图解码完成 → 保存可用；企业名文案上屏
        rule.waitUntil(5_000) { textExists("Acme Inc") }
        rule.waitUntil(5_000) { tagExists("qa-mycard-save") }
    }

    @Test
    fun `mycard shows error retry when all levels fail`() {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(500)) }
        rule.setContent {
            AppiaTheme(isDark = false) {
                MyCardScreen(
                    sdk = newSdk(),
                    serverUrl = "https://s1",
                    currentUserId = "u1",
                    currentUsername = "alice",
                    displayName = "Alice Zhang",
                    token = "tok",
                    onBack = {},
                )
            }
        }
        rule.waitForIdle()
        rule.waitUntil(5_000) { tagExists("qa-mycard-retry") }
    }

    @Test
    fun `mycard null sdk falls to error state without network`() {
        rule.setContent {
            AppiaTheme(isDark = false) {
                MyCardScreen(
                    sdk = null,
                    serverUrl = "https://s1",
                    currentUserId = "u1",
                    currentUsername = "alice",
                    displayName = "Alice Zhang",
                    token = "tok",
                    onBack = {},
                )
            }
        }
        rule.waitForIdle()
        rule.waitUntil(5_000) { tagExists("qa-mycard-retry") }
        assertEquals(0, server.requestCount)
    }

    // ── MemberProfile ──

    private fun setMemberProfile(bodyJson: String, onSendMessage: (String, String) -> Unit) {
        server.enqueue(MockResponse().setBody(bodyJson))
        rule.setContent {
            AppiaTheme(isDark = false) {
                MemberProfileScreen(
                    username = "bob",
                    userId = null,
                    sdk = newSdk(),
                    serverUrl = "https://s1",
                    currentUserId = "u1",
                    token = "tok",
                    currentUsername = "alice",
                    onBack = {},
                    onSendMessage = onSendMessage,
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `member profile renders rows and passes send callback params`() {
        val body = """
            {"user":{"_id":"u2","username":"bob","name":"Bob Li","fname":"Bob Li",
            "emails":[{"address":"bob@x.com"}],"jobName":"Dev","primaryOrgName":"R&D",
            "leaderNames":["Carol"],"canViewResume":false}}
        """.trimIndent()
        var sent: Pair<String, String>? = null
        setMemberProfile(body) { u, n -> sent = u to n }
        rule.waitUntil(5_000) { textExists("bob@x.com") }
        rule.onNodeWithTag("qa-member-profile-send").performClick()
        rule.waitForIdle()
        // RN handleSendMessage：displayname 兜底 username；回调透传给装配层 openDirectMessage
        assertEquals("bob" to "Bob Li", sent)
    }

    @Test
    fun `member profile voice button disabled placeholder`() {
        setMemberProfile("""{"user":{"_id":"u2","username":"bob"}}""") { _, _ -> }
        rule.waitUntil(5_000) { tagExists("qa-member-profile-voice") }
        rule.onNodeWithTag("qa-member-profile-voice").assertIsNotEnabled()
    }
}
