package cn.appia.im.feature.settings.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.i18n.t
import cn.appia.im.core.theme.AppiaTheme
import cn.appia.im.core.update.AppReleaseRow
import cn.appia.im.feature.search.interpolate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 弹窗 Compose 冒烟（M6-T7）：双按钮/强制无「暂不」/下载中只留进度条/空白说明兜底。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppUpdateModalSmokeTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun row(force: Boolean = false, notes: String = "Fix several issues") = AppReleaseRow(
        platform = "android",
        version = "1.2.4",
        url = "https://static.appia.cn/Appia-1.2.4.apk",
        is_force_update = force,
        file_hash = null,
        notes = notes,
    )

    private fun setContent(release: AppReleaseRow?, downloading: Boolean = false, progress: Float = 0f) {
        rule.setContent {
            AppiaTheme(isDark = false) {
                AppUpdateModal(release, downloading = downloading, progress = progress, onLater = {}, onUpgrade = {})
            }
        }
    }

    private fun tagAbsent(tag: String) =
        assertTrue(rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty())

    @Test
    fun optionalUpdateShowsBothButtons() {
        setContent(row())
        rule.onNodeWithTag("app-update-upgrade").assertExists()
        rule.onNodeWithTag("app-update-later").assertExists()
        rule.onNodeWithText(context.t("appupdate_upgrade")).assertExists()
        rule.onNodeWithText(context.t("appupdate_later")).assertExists()
    }

    @Test
    fun forceUpdateHidesLaterButton() {
        setContent(row(force = true))
        rule.onNodeWithTag("app-update-upgrade").assertExists()
        tagAbsent("app-update-later") // 强制更新无「暂不升级」（RN :134）
    }

    @Test
    fun downloadingHidesButtonsAndShowsProgressOnly() {
        setContent(row(), downloading = true, progress = 0.5f)
        tagAbsent("app-update-upgrade")
        tagAbsent("app-update-later") // 下载中两按钮隐藏（RN :108-118）
        rule.onNodeWithTag("app-update-progress").assertExists()
        rule.onNodeWithText(
            interpolate(context.t("appupdate_downloading"), mapOf("percent" to "50")),
        ).assertExists()
    }

    @Test
    fun emptyNotesFallsBackToPlaceholder() {
        setContent(row(notes = "  "))
        rule.onNodeWithText(context.t("appupdate_notesempty")).assertExists()
    }

    @Test
    fun nullReleaseRendersNothing() {
        setContent(null)
        tagAbsent("app-update-upgrade")
    }
}
