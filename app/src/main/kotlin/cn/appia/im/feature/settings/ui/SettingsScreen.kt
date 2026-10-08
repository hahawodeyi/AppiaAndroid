package cn.appia.im.feature.settings.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import cn.appia.im.BuildConfig
import cn.appia.im.core.datastore.AuthSessionStore
import cn.appia.im.core.datastore.KvStore
import cn.appia.im.core.i18n.t
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.api.AppReleaseApi
import cn.appia.im.core.network.api.ServerInfoApi
import cn.appia.im.core.network.api.UserPreferencesApi
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.feature.settings.BatteryOptimization
import cn.appia.im.feature.settings.ClearLocalCache
import cn.appia.im.feature.settings.SettingsConstants
import cn.appia.im.feature.settings.isGuestUser
import cn.appia.im.feature.settings.isServerVersionNewer
import cn.appia.im.feature.settings.readableAppVersion
import cn.appia.im.feature.settings.serverHostLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/** 确认弹窗载荷（本地化文案 + 动作标记）。 */
private data class ConfirmPayload(
    val title: String,
    val message: String,
    val confirmText: String,
    val action: ConfirmAction,
)

private enum class ConfirmAction { CLEAR_CACHE, DELETE_ACCOUNT, LOGOUT, LOGOUT_OTHER }

/**
 * 设置页（M5-T9，RN screens/SettingsScreen/index.tsx 五分区逐行对照）：
 *
 * 通用：语言三段【T11 占位——onLanguageChange 参数化回调，T9 不落 LocaleController】/
 *   字体两段（MMKV 持久化，KV 先行）/ 浏览器两段（Android 无 iOS canOpenURL 探测恒 base 两段）/
 *   头像样式 photo|letter（users.setPreferences 乐观回滚 + mergeUserPreferences）/
 *   消息设置 / 快捷回复（CustomQuickReply M6+ 域：行渲染、导航参数化）。
 * 通知：电池优化状态行（PowerManager.isIgnoringBatteryOptimizations + 机型门；ON_RESUME 刷新
 *   ——RN AppState 'active' 等价）+ hint。null（探测未定）不渲染分区（RN :350 同）。
 * 关于：应用版本（点按 ACTION_SEND 分享）/ 服务器版本（GET /api/info 免登录 + host 两行）/
 *   检查更新（GET /provider/api/v1/version 免登录；M6 前无自更新弹窗：展示结果 + 跳浏览器下载页）。
 * 法律：服务条款 / 隐私政策（LEGAL_URLS 外链 ACTION_VIEW）。
 * 账户：guest 删号判定（username 含 appia.guest → 「删除账号」，确认走登出链）/ 清除本地缓存
 *   （ClearLocalCache：teardown→删库→重 bootstrap）/ 退出登录 / 注销其它设备登录
 *   （users.removeOtherTokens 确认弹窗）。
 */
@Composable
fun SettingsScreen(
    sdk: RocketSdk?,
    store: AuthSessionStore,
    kv: KvStore,
    clearCache: ClearLocalCache?,
    /** 清除缓存协程宿主（评审 I-1）：应用级 scope（RouteDeps.scope 装配缝）——中途 popBackStack
     *  不得取消 teardown→删库→重引导链（RN run() 闭包脱离屏幕存活同义）。null 回退屏幕 scope（测试）。 */
    clearCacheScope: kotlinx.coroutines.CoroutineScope? = null,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    onOpenMessageSetting: () -> Unit = {},
    // T11 接线点（pre-flight 裁定：T9 语言段参数化回调占位，LocaleController 归 T11）
    onLanguageChange: (value: String) -> Unit = {},
    // CustomQuickReply 屏 M6+ 域：行渲染、导航参数化
    onOpenQuickReply: () -> Unit = {},
    // T10：浏览器 pref inApp 时法律/检查更新链接走应用内 WebView（RN openLink → navigate InAppWeb）
    onOpenInAppWeb: (url: String) -> Unit = {},
) {
    val context = LocalContext.current
    val colors = LocalAppiaColors.current
    val scope = rememberCoroutineScope()
    val session = remember { store.load() }
    val userId = session?.user?.id.orEmpty()
    val username = session?.user?.username?.trim().orEmpty()
    val serverUrl = session?.serverUrl.orEmpty()

    var languageValue by remember {
        mutableStateOf(kv.getString(SettingsConstants.LANGUAGE_KEY, "").takeIf { it.isNotEmpty() } ?: "system")
    }
    var fontValue by remember {
        mutableStateOf(kv.getString(SettingsConstants.DEFAULT_FONT_KEY, SettingsConstants.DEFAULT_FONT))
    }
    var browserValue by remember {
        mutableStateOf(kv.getString(SettingsConstants.DEFAULT_BROWSER_KEY, SettingsConstants.DEFAULT_BROWSER))
    }
    val prefAvatar = session?.user?.preferences?.get("appiaAvatarType")
        ?.let { (it as? JsonPrimitive)?.content }
    var letterAvatar by remember { mutableStateOf(prefAvatar == "letter") }
    var serverVersion by remember { mutableStateOf<String?>(null) }
    var batteryOptimized by remember { mutableStateOf<Boolean?>(null) } // null = 探测未定（RN 同）
    var clearing by remember { mutableStateOf(false) }
    var checkUpdating by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ConfirmPayload?>(null) }
    var info by remember { mutableStateOf<Pair<String, String>?>(null) }

    val appVersion = remember { readableAppVersion(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong()) }
    val host = remember(serverUrl) { serverHostLabel(serverUrl) }
    val isGuest = remember(username) { isGuestUser(username) }

    // 服务器版本（RN :85-103 fetchServerInfo 免登录；失败 → null 展示占位）
    LaunchedEffect(serverUrl) {
        serverVersion = if (serverUrl.isBlank()) null else ServerInfoApi.fetchServerVersion(serverUrl)
    }

    // 电池优化状态 + 回前台刷新（RN :109-128 AppState 'active' → ON_RESUME 等价）
    fun refreshBattery() {
        batteryOptimized = !BatteryOptimization.isIgnoringBatteryOptimizations(context)
    }
    LaunchedEffect(Unit) { refreshBattery() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshBattery()
        })
    }

    // T10：openLink 等价消费（RN SettingsScreen :138-142 onPressOpenUrl → lib/openLink/openLink.ts
    // ——浏览器 pref inApp → 应用内 WebView；systemDefault → ACTION_VIEW；needVPN 白名单域强制应用内）
    fun openUrl(url: String): Boolean =
        cn.appia.im.feature.web.openLink(context, kv, url, onOpenInAppWeb)

    fun shareText(text: String) {
        runCatching {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    },
                    text,
                ),
            )
        }
    }

    fun onPressBatteryOptimization() {
        // RN :130-136：先系统弹窗申请豁免，发起失败 → 打开系统电池优化列表页
        if (!BatteryOptimization.requestIgnore(context)) {
            BatteryOptimization.openSettings(context)
        }
    }

    fun onPressCheckUpdate() {
        if (checkUpdating) return
        checkUpdating = true
        scope.launch {
            try {
                val row = AppReleaseApi.fetchLatestAppRelease(localVersion = BuildConfig.VERSION_NAME)
                if (row == null) {
                    info = context.t("settings_check_update") to context.t("appupdate_checkfailed")
                } else if (!isServerVersionNewer(row.version, BuildConfig.VERSION_NAME)) {
                    info = context.t("settings_check_update") to context.t("appupdate_latest")
                } else {
                    // M6 前无自更新弹窗/下载基建（plan 边界）：报新版并跳浏览器下载页
                    // ——RN useAppUpdateUiStore.show(row) 的「先展示」等价（报告偏差注明）
                    openUrl(
                        AppReleaseApi.resolveAppReleaseOpenUrl(row.url).ifEmpty {
                            AppReleaseApi.APP_DOWNLOAD_URL_ANDROID
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                info = context.t("settings_check_update") to context.t("appupdate_checkfailed")
            } finally {
                checkUpdating = false
            }
        }
    }

    // RN handleAvatarTypeChange :206-220：乐观翻转 → setPreferences → 合并；失败回滚
    fun handleAvatarTypeChange(next: String) {
        val prev = letterAvatar
        letterAvatar = next == "letter"
        if (userId.isEmpty() || sdk == null) return
        scope.launch {
            try {
                UserPreferencesApi.setUserPreferences(
                    sdk, userId, mapOf("appiaAvatarType" to JsonPrimitive(next)),
                )
                store.mergeUserPreferences(userId, mapOf("appiaAvatarType" to JsonPrimitive(next)))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                letterAvatar = prev // RN :216-217 失败回滚
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.backgroundColor)
            .testTag("qa-settings-screen"),
    ) {
        SettingsHeader(title = context.t("settings_title"), onBack = onBack, tag = "qa-settings-back")
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            // ── 通用 ──
            SectionTitle(context.t("settings_section_general"), first = true, tag = "qa-settings-section-general")
            SettingsCard {
                SettingsStackedSegmentedRow(
                    title = context.t("profile_language"),
                    value = languageValue,
                    options = listOf(
                        "system" to context.t("profile_followsystem"),
                        "en" to context.t("profile_english"),
                        "zh" to context.t("profile_chinese"),
                    ),
                    onChange = { next ->
                        languageValue = next
                        // T11 接线：LocaleController 落地后接管（值透传回调，T9 不越界）
                        onLanguageChange(next)
                    },
                    tag = "settings-row-language",
                )
                SettingsSep()
                SettingsSegmentedRow(
                    title = context.t("settings_row_default_font"),
                    value = fontValue,
                    options = listOf(
                        "Default_Font_Standard" to context.t("settings_font_standard"),
                        "Follow_System_Setting" to context.t("settings_font_followsystem"),
                    ),
                    onChange = { next ->
                        fontValue = next
                        kv.putString(SettingsConstants.DEFAULT_FONT_KEY, next)
                    },
                    tag = "settings-row-font",
                )
                SettingsSep()
                SettingsSegmentedRow(
                    title = context.t("settings_row_default_browser"),
                    value = browserValue,
                    options = listOf(
                        "inApp" to context.t("settings_browser_inapp"),
                        "systemDefault:" to context.t("settings_browser_system"),
                    ),
                    onChange = { next ->
                        browserValue = next
                        kv.putString(SettingsConstants.DEFAULT_BROWSER_KEY, next)
                    },
                    tag = "settings-row-browser",
                )
                SettingsSep()
                SettingsSegmentedRow(
                    title = context.t("settings_row_avatar"),
                    value = if (letterAvatar) "letter" else "normal",
                    options = listOf(
                        "normal" to context.t("settings_avatar_photo"),
                        "letter" to context.t("settings_avatar_letter"),
                    ),
                    onChange = ::handleAvatarTypeChange,
                    tag = "settings-row-avatar",
                )
                SettingsSep()
                SettingsNavRow(
                    context.t("settings_row_message"),
                    onClick = onOpenMessageSetting,
                    tag = "settings-row-message",
                )
                SettingsSep()
                SettingsNavRow(
                    context.t("settings_row_quick_reply"),
                    onClick = onOpenQuickReply,
                    tag = "settings-row-quick-reply",
                )
            }

            // ── 通知（电池状态未定不渲染——RN batteryOptimized === null 分支）──
            if (batteryOptimized != null) {
                SectionTitle(context.t("settings_section_notification"), tag = "qa-settings-section-notification")
                SettingsCard {
                    SettingsValueRow(
                        title = context.t("settings_row_pushbackground"),
                        value = if (batteryOptimized == true) {
                            context.t("pushbattery_status_restricted")
                        } else {
                            context.t("pushbattery_status_ok")
                        },
                        onClick = ::onPressBatteryOptimization,
                        tag = "settings-row-push-background",
                    )
                    Text(
                        context.t("pushbattery_settings_hint"),
                        color = colors.auxiliaryText,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }

            // ── 关于 ──
            SectionTitle(context.t("settings_section_about"), tag = "qa-settings-section-about")
            SettingsCard {
                SettingsValueRow(
                    title = context.t("settings_version_app"),
                    value = appVersion,
                    onClick = { shareText(appVersion) },
                    tag = "settings-row-app-version",
                )
                SettingsSep()
                SettingsValueRow(
                    title = context.t("settings_version_server"),
                    value = if (serverUrl.isNotEmpty()) {
                        "${serverVersion ?: context.t("settings_server_version_placeholder")}\n$host"
                    } else {
                        context.t("settings_server_host_unknown")
                    },
                    onClick = {
                        if (serverUrl.isEmpty()) return@SettingsValueRow
                        shareText(if (serverVersion != null) "$serverVersion\n$host" else serverUrl)
                    },
                    tag = "settings-row-server-host",
                )
                SettingsSep()
                SettingsNavRow(
                    context.t("settings_check_update"),
                    onClick = ::onPressCheckUpdate,
                    tag = "settings-row-check-update",
                )
                if (checkUpdating) {
                    CircularProgressIndicator(
                        Modifier.padding(16.dp).testTag("qa-settings-check-updating"),
                        strokeWidth = 2.dp,
                    )
                }
            }

            // ── 法律 ──
            SectionTitle(context.t("settings_section_legal"), tag = "qa-settings-section-legal")
            SettingsCard {
                SettingsNavRow(
                    context.t("settings_privacy_policy"),
                    onClick = { openUrl(SettingsConstants.LEGAL_PRIVACY_POLICY) },
                    tag = "settings-row-privacy",
                )
                SettingsSep()
                SettingsNavRow(
                    context.t("settings_terms_of_service"),
                    onClick = { openUrl(SettingsConstants.LEGAL_TERMS_OF_SERVICE) },
                    tag = "settings-row-terms",
                )
            }

            // ── 账户 ──
            SectionTitle(context.t("settings_section_account"), tag = "qa-settings-section-account")
            SettingsCard {
                if (isGuest) {
                    SettingsDangerRow(
                        context.t("settings_delete_account"),
                        onClick = {
                            confirm = ConfirmPayload(
                                context.t("settings_delete_account"),
                                context.t("settings_delete_account_message"),
                                context.t("settings_delete_account_confirm"),
                                ConfirmAction.DELETE_ACCOUNT,
                            )
                        },
                        tag = "settings-row-delete-account",
                    )
                    SettingsSep()
                }
                SettingsDangerRow(
                    if (clearing) context.t("settings_clear_cache_running") else context.t("settings_clear_cache"),
                    onClick = {
                        if (!clearing) {
                            confirm = ConfirmPayload(
                                context.t("settings_clear_cache"),
                                context.t("settings_clear_cache_message"),
                                context.t("settings_clear_cache_confirm"),
                                ConfirmAction.CLEAR_CACHE,
                            )
                        }
                    },
                    tag = "settings-row-clear-cache",
                    loading = clearing,
                )
                SettingsSep()
                SettingsDangerRow(
                    context.t("profile_logout"),
                    onClick = {
                        confirm = ConfirmPayload(
                            context.t("settings_logout_confirm_title"),
                            context.t("settings_logout_confirm_message"),
                            context.t("profile_logout"),
                            ConfirmAction.LOGOUT,
                        )
                    },
                    tag = "settings-logout",
                )
                SettingsSep()
                SettingsDangerRow(
                    context.t("settings_logout_other"),
                    onClick = {
                        confirm = ConfirmPayload(
                            context.t("settings_logout_other"),
                            context.t("settings_logout_other_message"),
                            context.t("settings_logout_other_confirm"),
                            ConfirmAction.LOGOUT_OTHER,
                        )
                    },
                    tag = "settings-row-logout-other",
                )
            }
        }
    }

    // 确认弹窗（RN Alert.alert destructive 形态；动作标记分发，不以本地化文案做键）
    confirm?.let { payload ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(payload.title) },
            text = { Text(payload.message) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    when (payload.action) {
                        ConfirmAction.CLEAR_CACHE -> {
                            if (clearCache == null) return@TextButton
                            clearing = true
                            // 评审 I-1：应用级 scope——屏幕销毁不取消链路（teardown 后必须重引导）；
                            // clearing/info 状态写在屏幕已销毁时是 no-op 写快照，迟到完成零 UI 副作用
                            (clearCacheScope ?: scope).launch {
                                try {
                                    clearCache.clear()
                                    info = context.t("settings_clear_cache_done_title") to
                                        context.t("settings_clear_cache_done_body")
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    info = context.t("settings_clear_cache_fail_title") to
                                        (e.message ?: e.toString())
                                } finally {
                                    clearing = false
                                }
                            }
                        }
                        // guest 删号与登出同走登出链（RN SettingsScreen:201 authActions.logout 同）
                        ConfirmAction.DELETE_ACCOUNT, ConfirmAction.LOGOUT -> onLogout()
                        ConfirmAction.LOGOUT_OTHER -> {
                            if (sdk == null || userId.isEmpty()) return@TextButton
                            scope.launch {
                                try {
                                    UserPreferencesApi.removeOtherTokens(sdk, userId)
                                    info = context.t("settings_logout_other_success_title") to
                                        context.t("settings_logout_other_success_body")
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    info = context.t("settings_logout_other_fail_title") to
                                        (e.message ?: e.toString())
                                }
                            }
                        }
                    }
                }) { Text(payload.confirmText) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text(context.t("settings_action_cancel")) }
            },
        )
    }

    // 单键信息弹窗（完成/失败提示——RN Alert.alert(title, message) 同）
    info?.let { (title, body) ->
        AlertDialog(
            onDismissRequest = { info = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = { info = null }) { Text(context.t("common_close")) }
            },
        )
    }
}
