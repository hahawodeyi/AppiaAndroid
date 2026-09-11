package cn.appia.im.feature.login.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.i18n.t
import cn.appia.im.core.network.LoginCredentials
import cn.appia.im.core.network.LoginResult
import cn.appia.im.feature.login.CompanyServer
import cn.appia.im.feature.login.SendCodeResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 登录页 UI 走查（Robolectric + Compose，请求边界之上）：模式切换、校验 Alert、冷却禁用态、
 * 按钮可用态、连点切手输、发码弹层。sendCode/login/fetchCasUrl 注入 fake 不触网。
 */
// Robolectric 仅支持 JUnit4 runner；SDK 36 沙箱要 Java 21，钉在 SDK 34（同 EnterpriseCodeScreenTest）
@RunWith(RobolectricTestRunner::class)
// plain Application：AppiaApplication.onCreate 会初始化 MMKV，JVM 下不可加载
@Config(sdk = [34], application = Application::class)
class LoginScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val servers = listOf(CompanyServer(url = "https://s1", name = "S1"), CompanyServer(url = "https://s2", name = "S2"))
    private val ic = JsonObject(emptyMap())

    private fun state(
        sendCode: suspend (String, String, String, JsonElement?) -> SendCodeResult = { _, _, _, _ -> SendCodeResult(true) },
        login: suspend (String, LoginCredentials) -> LoginResult = { _, _ -> LoginResult("tok", "uid") },
    ): LoginState = LoginState(
        servers,
        LoginDeps(
            strings = { context.t(it) },
            sendCode = sendCode,
            login = login,
            fetchCasUrl = { null },
            generateSsoToken = { "abc123xyz09876zy" },
            onLoginSuccess = { _, _ -> },
            // 固定时钟：连点后门等时间相关行为在 UI 测试里确定化
            nowMillis = { 1_000L },
        ),
    )

    private fun setContent(state: LoginState, enablePasswordLogin: Boolean = ENABLE_PASSWORD_LOGIN) {
        rule.setContent {
            LoginScreen(
                servers = servers,
                onLoginSuccess = { _, _ -> },
                state = state,
                enablePasswordLogin = enablePasswordLogin,
            )
        }
    }

    /** 品牌布局可滚动（AuthBrandedLayout verticalScroll）：触点前先滚到目标，防默认视口裁剪。 */
    private fun scrollTo(tag: String) {
        rule.onNodeWithTag("auth_branded_scroll").performScrollToNode(hasTestTag(tag))
    }

    private fun input(tag: String, text: String) {
        scrollTo(tag)
        rule.onNodeWithTag(tag).performTextInput(text)
    }

    private fun tap(tag: String) {
        scrollTo(tag)
        rule.onNodeWithTag(tag).performClick()
    }

    @Test
    fun `renders sms mode with dropdown and disabled submit`() {
        setContent(state())

        rule.onNodeWithText(context.t("enterprise_welcome")).assertExists()
        rule.onNodeWithText(context.t("login_mode_sms")).assertExists()
        rule.onNodeWithText("S1").assertExists() // 下拉当前值 = 首台 label（RN currentLabel）
        rule.onNodeWithTag("login_submit").assertIsNotEnabled() // 无验证码 → 禁用（RN loginButtonDisabled）
    }

    @Test
    fun `empty phone send shows alert then dismisses`() {
        setContent(state())

        tap("login_send_sms")
        rule.onNodeWithText(context.t("login_alert_missing_phone")).assertExists()
        rule.onNodeWithText(context.t("common_close")).performClick()
        rule.onNodeWithText(context.t("login_alert_missing_phone")).assertDoesNotExist()
    }

    @Test
    fun `valid phone opens captcha sheet`() {
        val st = state()
        setContent(st)
        input("login_phone_input", "13812345678")

        tap("login_send_sms")

        rule.waitUntil(5_000) {
            rule.onAllNodesWithText(context.t("login_sms_captcha_sheet_title")).fetchSemanticsNodes().isNotEmpty()
        }
        // 关闭走 onRequestClose
        rule.onNodeWithText(context.t("login_sms_captcha_sheet_close")).performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText(context.t("login_sms_captcha_sheet_title")).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun `cooldown shows countdown and disables send button`() {
        val st = state()
        st.smsCooldown = 60
        setContent(st)

        rule.onNodeWithText("60", substring = true).assertExists() // {{seconds}} 插值（RN:586-588）
        rule.onNodeWithTag("login_send_sms").assertIsNotEnabled()
    }

    @Test
    fun `filled sms fields with captcha ic enable submit`() {
        val st = state()
        st.phone = "13812345678"
        st.smsCode = "1234"
        st.captchaIc = ic
        setContent(st)

        rule.onNodeWithTag("login_submit").assertIsEnabled()
    }

    @Test
    fun `mode tabs switch form fields`() {
        val st = state()
        setContent(st, enablePasswordLogin = true) // 门控参数化：测试注入 true（生产常量 false）

        rule.onNodeWithText(context.t("login_mode_password")).performClick()
        scrollTo("login_username_input")
        rule.onNodeWithTag("login_username_input").assertExists()
        rule.onNodeWithTag("login_password_input").assertExists()
        rule.onNodeWithTag("login_captcha_webview").assertExists() // 密码模式内嵌滑块（RN:595-611）
        rule.onNodeWithText(context.t("login_forgot_password")).assertExists()

        rule.onNodeWithText(context.t("login_mode_sms")).performClick()
        rule.onNodeWithTag("login_phone_input").assertExists()
        rule.onNodeWithTag("login_captcha_webview").assertDoesNotExist()
        rule.onNodeWithText(context.t("login_forgot_password")).assertDoesNotExist()
    }

    @Test
    fun `password fields without ic keep submit disabled`() {
        val st = state()
        setContent(st, enablePasswordLogin = true)
        rule.onNodeWithText(context.t("login_mode_password")).performClick()
        input("login_username_input", "user")
        input("login_password_input", "pass")

        // RN:414-417 loginButtonDisabled：无滑块 ic 按钮禁用（captcha_required 校验双保险在状态机层覆盖）
        rule.onNodeWithTag("login_submit").assertIsNotEnabled()
        rule.onNodeWithText(context.t("login_captcha_required")).assertDoesNotExist()
    }

    @Test
    fun `sms submit with ic calls onLoginSuccess with server`() {
        var successServer: String? = null
        val st = LoginState(
            servers,
            LoginDeps(
                strings = { context.t(it) },
                sendCode = { _, _, _, _ -> SendCodeResult(true) },
                login = { host, _ ->
                    successServer = host
                    LoginResult("tok", "uid")
                },
                fetchCasUrl = { null },
                generateSsoToken = { "x" },
                onLoginSuccess = { _, server -> successServer = server },
            ),
        ).apply {
            phone = "13812345678"
            smsCode = "1234"
            captchaIc = ic
        }
        setContent(st)

        tap("login_submit")

        rule.waitUntil(5_000) { successServer != null }
        assertEquals("https://s1", successServer) // pickInitialServerUrl：无 selected → 首台
    }

    @Test
    fun `ten rapid label taps switch to manual url input`() {
        val st = state()
        setContent(st)

        val label = rule.onNodeWithText(context.t("login_enterprise"))
        repeat(LoginScreenTestDefaults.TOGGLE_TAPS) { label.performClick() } // 10 次：不切换
        rule.onNodeWithTag("login_enterprise_url_input").assertDoesNotExist()
        label.performClick() // 第 11 次：切换（RN:177-180 count > 10）

        rule.onNodeWithTag("login_enterprise_url_input").assertExists()
    }

    @Test
    fun `empty servers routes back to enterprise code`() {
        var missing = false
        rule.setContent {
            LoginScreen(servers = emptyList(), onLoginSuccess = { _, _ -> }, onMissingServers = { missing = true })
        }
        rule.waitUntil(5_000) { missing }
        assertTrue(missing)
    }
}

/** 避免测试文件直接引用 internal 常量做边界外的别名流转。 */
private object LoginScreenTestDefaults {
    const val TOGGLE_TAPS = 10
}
