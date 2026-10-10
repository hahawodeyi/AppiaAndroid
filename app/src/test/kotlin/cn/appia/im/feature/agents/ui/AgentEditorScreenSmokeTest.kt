package cn.appia.im.feature.agents.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.i18n.t
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.theme.AppiaTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AgentEditorScreen Compose 冒烟（M7-T8 / RN AgentEditorScreen）：create 快捷添加解析回填 +
 * 保存链（MockWebServer create 命中 → createdUsername 回投）；edit 字段回显 + 空 API Key 不发密钥。
 * 判定纯逻辑在 ClawAgentEditTest/ClawAgentsApiTest。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AgentEditorScreenSmokeTest {
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

    @Test
    fun `create mode quick add parses and save posts payload`() {
        var saved: String? = null
        rule.setContent {
            AppiaTheme(isDark = false) {
                AgentEditorScreen(mode = "create", sdk = newSdk(), onBack = {}, onSaved = { saved = it })
            }
        }
        rule.onNodeWithText(context.t("agents_navTitleCreate")).assertExists()
        rule.onNodeWithTag("agent-editor-save").assertIsNotEnabled() // 凭证未就绪禁保存

        val json = """{"agentId":"AG-9","apiSecret":"sec","streamUrl":"https://a"}"""
        val b64 = android.util.Base64.encodeToString(json.toByteArray(), android.util.Base64.DEFAULT)
        rule.onNodeWithTag("agent-editor-quick-input").performTextInput(b64)
        rule.onNodeWithTag("agent-editor-quick-apply").performClick()
        rule.onNodeWithText(context.t("agents_quickReady")).assertExists()
        // 名称空 → 回填 agentId；再补显式名称
        rule.onNodeWithTag("agent-editor-name").performTextInput("My Bot")
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        rule.onNodeWithTag("agent-editor-save").performClick()

        rule.waitUntil(10_000) { saved != null }
        assertEquals("/api/v1/appia.createClawAgents", server.takeRequest().path)
        assertTrue(saved!!.startsWith("claw.") && saved!!.endsWith(".bot"))
    }

    @Test
    fun `edit mode prefills fields and blank api key saves without secret`() {
        var savedCount = 0
        rule.setContent {
            AppiaTheme(isDark = false) {
                AgentEditorScreen(
                    mode = "edit",
                    sdk = newSdk(),
                    onBack = {},
                    onSaved = { savedCount += 1 },
                    initialName = "Old",
                    initialAgentId = "AG-1",
                    initialServiceUrl = "https://old",
                    initialId = "ROW-1",
                )
            }
        }
        rule.onNodeWithText(context.t("agents_navTitleEdit")).assertExists()
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        rule.onNodeWithTag("agent-editor-save").performClick()
        rule.waitUntil(10_000) { server.requestCount > 0 }
        val req = server.takeRequest()
        assertEquals("/api/v1/appia.updateClawAgent", req.path)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("ROW-1", body["_id"]!!.jsonPrimitive.content)
        // 字段回显（RN route agent）：初始名 "Old" 原样提交
        assertEquals("Old", body["appiaOpenClawName"]!!.jsonPrimitive.content)
        assertNull(body["appiaOpenClawApiSecret"]) // apiKey 留空 → 不发密钥（沿用旧密钥）
    }

    @Test
    fun `duplicate agent id error surfaces friendly alert`() {
        var backCalls = 0
        rule.setContent {
            AppiaTheme(isDark = false) {
                AgentEditorScreen(
                    mode = "edit",
                    sdk = newSdk(),
                    onBack = { backCalls += 1 },
                    onSaved = {},
                    initialName = "N",
                    initialAgentId = "AG-1",
                    initialServiceUrl = "https://u",
                    initialId = "ROW-1",
                )
            }
        }
        server.enqueue(
            MockResponse().setBody("""{"success":false,"error":"appiaOpenClawAgentId already exists"}"""),
        )
        rule.onNodeWithTag("agent-editor-save").performClick()
        rule.waitUntil(5_000) { server.requestCount > 0 }
        rule.onNodeWithTag("agent-editor-save-error").assertExists()
        rule.onNodeWithText(context.t("agents_duplicateagentid").replace("{{id}}", "AG-1")).assertExists()
        // 保存失败不返回
        assertEquals(0, backCalls)
    }
}
