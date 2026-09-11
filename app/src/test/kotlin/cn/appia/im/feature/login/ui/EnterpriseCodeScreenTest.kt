package cn.appia.im.feature.login.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.AppiaNavHost
import cn.appia.im.core.i18n.t
import cn.appia.im.feature.login.CompanyServer
import cn.appia.im.feature.login.VerifyEnterpriseResponse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 企业码页 UI 走查（绑定裁定#6）：空输入 Alert、长按切后门、成功导航带参数。
 * Robolectric + Compose test rule；verify 注入 fake 不触网。
 */
// Robolectric 仅支持 JUnit4 runner；SDK 36 沙箱要 Java 21，钉在 SDK 34（同 I18nTest）
@RunWith(RobolectricTestRunner::class)
// plain Application：AppiaApplication.onCreate 会初始化 MMKV，JVM 下不可加载
@Config(sdk = [34], application = Application::class)
class EnterpriseCodeScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val fakeSuccess: suspend (String, String) -> VerifyEnterpriseResponse =
        { _, _ ->
            VerifyEnterpriseResponse(
                success = true,
                servers = listOf(CompanyServer(url = "https://s1", name = "S1"), CompanyServer(url = "https://s2")),
            )
        }

    @Test
    fun `empty code submit shows missing code alert`() {
        rule.setContent { EnterpriseCodeScreen(onVerified = {}) }

        rule.onNodeWithText(context.t("enterprise_next")).performClick()

        // Alert 标题与正文均走 i18n（RN:57-60 login_alertMissingTitle + enterprise_missingCode）
        rule.onNodeWithText(context.t("login_alertMissingTitle")).assertExists()
        rule.onNodeWithText(context.t("enterprise_missingCode")).assertExists()
    }

    @Test
    fun `long press code label toggles debug env host field`() {
        rule.setContent { EnterpriseCodeScreen(onVerified = {}) }

        // debug 变体 BuildConfig.DEBUG=true，后门可见
        rule.onNodeWithTag("enterprise_env_host_input").assertDoesNotExist()
        rule.onNodeWithText(context.t("enterprise_codeLabel")).performTouchInput { longClick() }
        rule.onNodeWithTag("enterprise_env_host_input").assertExists()
        rule.onNodeWithText(context.t("enterprise_envHostLabel")).assertExists()

        // 再长按收起
        rule.onNodeWithText(context.t("enterprise_codeLabel")).performTouchInput { longClick() }
        rule.onNodeWithTag("enterprise_env_host_input").assertDoesNotExist()
    }

    @Test
    fun `typed code survives backdoor toggle`() {
        rule.setContent { EnterpriseCodeScreen(onVerified = {}) }

        rule.onNodeWithTag("enterprise_code_input").performTextInput("ab12")
        rule.onNodeWithText(context.t("enterprise_codeLabel")).performTouchInput { longClick() }

        rule.onNodeWithTag("enterprise_code_input").assertTextContains("ab12")
    }

    @Test
    fun `successful verify navigates to login with servers`() {
        rule.setContent { AppiaNavHost(verify = fakeSuccess) }

        rule.onNodeWithTag("enterprise_code_input").performTextInput("ab12")
        rule.onNodeWithText(context.t("enterprise_next")).performClick()

        // Login 占位展示收到的 servers（type-safe nav 参数化）：2 台、首台 url
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("servers=2 https://s1", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}
