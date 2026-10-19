package cn.appia.im.feature.privacy.ui

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
 * 首启隐私同意屏 Compose 冒烟（M11-T5 Slot3，惯例参照 SettingsScreensSmokeTest）：
 * title/message/两链接/两按钮文案渲染 + 同意/不同意回调触发。
 * MainActivity 门禁分支为 Activity 级接线（gateState remember 依赖注入 kv），此处直测屏本体；
 * 门禁首启可见性由真机/Maestro 冒烟覆盖（清数据后冷启动见同意屏）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PrivacyConsentScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun setContent(onAgree: () -> Unit = {}, onDisagree: () -> Unit = {}) {
        rule.setContent {
            AppiaTheme(isDark = false) {
                PrivacyConsentScreen(onAgree = onAgree, onDisagree = onDisagree)
            }
        }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("privacy_consent_screen").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `renders title message links and buttons`() {
        setContent()

        rule.onNodeWithText(context.t("privacy_consent_title")).assertExists()
        rule.onNodeWithText(context.t("privacy_consent_message")).assertExists()
        rule.onNodeWithText(context.t("privacy_consent_terms_link")).assertExists()
        rule.onNodeWithText(context.t("privacy_consent_privacy_link")).assertExists()
        rule.onNodeWithText(context.t("privacy_consent_agree")).assertExists()
        rule.onNodeWithText(context.t("privacy_consent_disagree")).assertExists()
    }

    @Test
    fun `agree button fires callback`() {
        var agreeCalls = 0
        setContent(onAgree = { agreeCalls++ })
        rule.onNodeWithTag("privacy_consent_agree").performClick()
        rule.waitForIdle()
        assertEquals(1, agreeCalls)
    }

    @Test
    fun `disagree button fires callback`() {
        var disagreeCalls = 0
        setContent(onDisagree = { disagreeCalls++ })
        rule.onNodeWithTag("privacy_consent_disagree").performClick()
        rule.waitForIdle()
        assertEquals(1, disagreeCalls)
    }
}
