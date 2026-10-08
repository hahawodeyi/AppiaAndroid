package cn.appia.im.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import cn.appia.im.core.datastore.AuthSessionStore
import cn.appia.im.core.i18n.t
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.api.UserPreferencesApi
import cn.appia.im.core.network.api.parseBoolUserPref
import cn.appia.im.core.theme.LocalAppiaColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * 消息设置页（M5-T9，RN screens/MessageSettingScreen/index.tsx 逐行对照）：
 * 两开关 showImageSummary / showDocumentSummary——users.setPreferences 乐观
 * （先翻转 → POST → 成功 mergeUserPreferences / 失败回滚，RN updatePref :30-38 同序）。
 * 初值 preferences 两键 parseBoolUserPref 缺省 true（RN parseBoolUserPref(prefs?.x)）。
 */
@Composable
fun MessageSettingScreen(
    sdk: RocketSdk?,
    store: AuthSessionStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalAppiaColors.current
    val scope = rememberCoroutineScope()
    val session = remember { store.load() }
    val userId = session?.user?.id.orEmpty()
    val prefs = session?.user?.preferences

    var showImageSummary by remember {
        mutableStateOf(parseBoolUserPref(prefs?.get("showImageSummary")))
    }
    var showDocSummary by remember {
        mutableStateOf(parseBoolUserPref(prefs?.get("showDocumentSummary")))
    }

    fun updatePref(key: String, next: Boolean, revert: () -> Unit) {
        if (userId.isEmpty() || sdk == null) {
            revert()
            return
        }
        scope.launch {
            try {
                UserPreferencesApi.setUserPreferences(sdk, userId, mapOf(key to JsonPrimitive(next)))
                store.mergeUserPreferences(userId, mapOf(key to JsonPrimitive(next)))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                revert()
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.backgroundColor)
            .testTag("qa-message-setting-screen"),
    ) {
        SettingsHeader(
            title = context.t("settings_row_message"),
            onBack = onBack,
            tag = "qa-message-setting-header",
        )
        SettingsCard {
            SettingsToggleRow(
                title = context.t("settings_msg_imagesummary"),
                value = showImageSummary,
                onValueChange = { next ->
                    val prev = showImageSummary
                    showImageSummary = next
                    updatePref("showImageSummary", next) { showImageSummary = prev }
                },
                tag = "qa-message-setting-image-summary",
            )
            SettingsSep()
            SettingsToggleRow(
                title = context.t("settings_msg_docsummary"),
                value = showDocSummary,
                onValueChange = { next ->
                    val prev = showDocSummary
                    showDocSummary = next
                    updatePref("showDocumentSummary", next) { showDocSummary = prev }
                },
                tag = "qa-message-setting-doc-summary",
            )
        }
    }
}
