package cn.appia.im.feature.login.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.i18n.t
import cn.appia.im.feature.login.LoginAreaCodeOption
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 区号页 UI 走查：进入加载态 → 列表渲染、点选回调、失败回落 +86（fetch 注入 fake 不触网）。
 */
// Robolectric 仅支持 JUnit4 runner；SDK 36 沙箱要 Java 21，钉在 SDK 34（同 EnterpriseCodeScreenTest）
@RunWith(RobolectricTestRunner::class)
// plain Application：AppiaApplication.onCreate 会初始化 MMKV，JVM 下不可加载
@Config(sdk = [34], application = Application::class)
class AreaCodeScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `shows loading then rows and reports selection`() {
        val gate = CompletableDeferred<List<LoginAreaCodeOption>>()
        var selected: LoginAreaCodeOption? = null
        rule.setContent {
            AreaCodeScreen(
                server = "https://s1",
                onSelect = { selected = it },
                fetch = { _, _, _ -> gate.await() },
            )
        }

        rule.onNodeWithTag("area_code_loading").assertIsDisplayed()

        gate.complete(
            listOf(LoginAreaCodeOption("Japan", "+81", "JP"), LoginAreaCodeOption("United States", "+1", "US")),
        )
        rule.waitForIdle()
        rule.onNodeWithText(context.t("login_area_china")).assertDoesNotExist() // 有数据不再回落
        rule.onNodeWithText("+81").assertExists()
        rule.onNodeWithText("Japan").performClick()
        assertEquals("+81", selected?.areaCode)
    }

    @Test
    fun `fallback shows plus86 china row with i18n label`() {
        rule.setContent {
            AreaCodeScreen(
                server = "https://s1",
                onSelect = {},
                fetch = { _, _, fallbackLabel -> listOf(LoginAreaCodeOption(fallbackLabel, "+86", "CN")) },
            )
        }

        rule.onNodeWithText(context.t("login_area_china")).assertExists()
        rule.onNodeWithText("+86").assertExists()
    }
}
