package cn.appia.im.feature.login.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.i18n.t
import cn.appia.im.core.network.LoginCredentials
import cn.appia.im.core.network.LoginResult
import cn.appia.im.feature.login.AuthApi
import cn.appia.im.feature.login.CasApi
import cn.appia.im.feature.login.CompanyServer
import cn.appia.im.feature.login.LoginAreaCodeOption
import cn.appia.im.feature.login.SendCodeResult
import cn.appia.im.feature.login.SmsCaptchaBottomSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

/**
 * 是否启用账号密码登录（RN LoginScreen:27-29 逐字移植）。false 时隐藏密码登录 Tab 与相关入口，
 * 默认短信登录；代码路径完整实现。恢复时将此常量改为 true 即可（无需其他改动）。
 */
internal const val ENABLE_PASSWORD_LOGIN = false

/** 与旧版 DebugTouchable 一致（RN:31-33）：500ms 内连点 >10 次触发企业手输模式切换。 */
internal const val LOGIN_ENTERPRISE_LABEL_DEBUG_TAPS = 10
internal const val LOGIN_ENTERPRISE_LABEL_DEBUG_GAP_MS = 500L

private const val FALLBACK_SERVER_URL = "https://appia.cn"

enum class LoginMode { PASSWORD, SMS }

/** 打开 Auth 内嵌 Web 页（忘记密码 / CAS SSO）的请求；导航层负责映射成 AuthWebRoute。 */
data class AuthWebRequest(
    val url: String,
    val title: String,
    val authType: String? = null,
    val ssoToken: String? = null,
    val server: String = "",
)

/** RN pickInitialServer:35-39：`selected ?: first`，url 空/无列表回落 `https://appia.cn`（name 由下拉行自行推导）。 */
internal fun pickInitialServerUrl(servers: List<CompanyServer>): String {
    val sel = servers.firstOrNull { it.selected == true } ?: servers.firstOrNull()
    return sel?.url?.trim()?.takeIf { it.isNotEmpty() } ?: FALLBACK_SERVER_URL
}

/** RN normalizeServerBase:41-43。 */
internal fun normalizeServerBase(url: String): String = url.trim().trimEnd('/')

private fun encodeQuery(value: String): String = URLEncoder.encode(value, "UTF-8")

/** RN passwordCaptchaUri:112-119：账密 Tab 沿用 `/verification/sms`，`t`=reloadKey 强制重载。 */
internal fun buildPasswordCaptchaUri(base: String, locale: String, reloadKey: Int): String =
    "${normalizeServerBase(base)}/verification/sms?locale=${encodeQuery(locale)}&t=$reloadKey"

/** RN sheetCaptchaUri:121-130：短信弹层 `/verification/sms-login`，每次打开换新 `t`。 */
internal fun buildSmsCaptchaUri(base: String, locale: String, t: Long): String =
    "${normalizeServerBase(base)}/verification/sms-login?locale=${encodeQuery(locale)}&t=$t"

/** RN resolveAppiaForgotPasswordWebUrl（constants/appiaPasswordReset.ts，非 Rocket `/reset-password`）。 */
internal fun resolveAppiaForgotPasswordWebUrl(enterpriseUrl: String): String {
    val e = enterpriseUrl.trim().lowercase()
    return if (e.contains("ark.appia.cn")) "https://pwdreset-ark.appia.cn/" else "https://pwdresetall.appia.vip/?from=appia"
}

/** RN isLoginNetworkTimeoutError（lib/network/loginNetworkError.ts，对齐 RocketChat 错误文案）。 */
internal fun isLoginNetworkTimeoutError(error: Throwable?): Boolean {
    val message = error?.message?.lowercase() ?: return false
    return message.contains("timeout") ||
        message.contains("connection failed") ||
        message.contains("closed before connect") ||
        message.contains("network request failed") ||
        message.contains("aborted")
}

/** RN `phone.replace(/\D/g,'')`：JS `\d` 仅 [0-9]，不用 Character.isDigit（会放过非 ASCII 数字）。 */
internal fun filterDigits(phone: String): String = phone.filter { it in '0'..'9' }

/**
 * RN `JSON.parse(raw)` 等价（LoginScreen:293-301 / SmsCaptchaBottomSheet:44-55）：
 * 解析失败/裸字符串字面量（H5 心跳 "ping"，JS 会抛错）→ null 忽略；
 * kotlinx 的 parseToJsonElement 对裸串宽松返回 JsonPrimitive，需按 JS 语义回绝。
 * 良性偏差（不影响 ic 语义）："null" 放行为 JsonNull（JS 亦返回 null）；
 * "NaN"/"Infinity" 此处被回绝，JS 同样抛错——仅裸非数字字面量的归属两者一致。
 */
internal fun parseJsJson(raw: String, json: Json = Json): JsonElement? {
    if (raw.isEmpty()) return null
    val element = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return null
    // kotlinx 对裸字面量宽松（"ping" → isString=false 的 JsonPrimitive）；JS 只认数字/true/false/null
    if (element is kotlinx.serialization.json.JsonPrimitive && !element.isString) {
        val t = raw.trim()
        val looksLikeJsLiteral = t.toDoubleOrNull() != null || t == "true" || t == "false" || t == "null"
        if (!looksLikeJsLiteral) return null
    }
    return element
}

/** 生产/测试依赖注入面（RN 里都是模块级 import；此处参数化以做不触网 UI 测试）。 */
class LoginDeps(
    /** i18n 文案查找（生产 `Context.t`；测试注入 key 恒等便于断言）。 */
    val strings: (String) -> String,
    val sendCode: suspend (String, String, String, JsonElement?) -> SendCodeResult,
    val login: suspend (String, LoginCredentials) -> LoginResult,
    val fetchCasUrl: suspend (String) -> String?,
    val generateSsoToken: () -> String,
    val onLoginSuccess: (LoginResult, String) -> Unit,
    val nowMillis: () -> Long = System::currentTimeMillis,
)

/**
 * 登录页状态机（RN LoginScreen.tsx:45-408 逐行为移植）。
 * 输入/模式/验证码等状态跨导航往返保留（rememberSaveable Saver）；
 * `submitting`/弹层/告警等瞬时态不恢复。请求边界之上：sendCode/login/fetchCasUrl 均注入。
 */
class LoginState internal constructor(
    val servers: List<CompanyServer>,
    internal val deps: LoginDeps,
) {
    var mode by mutableStateOf(LoginMode.SMS)
    var selectedUrl by mutableStateOf(pickInitialServerUrl(servers))
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var passwordVisible by mutableStateOf(false)
    var areaCode by mutableStateOf("+86")
    var phone by mutableStateOf("")
    var smsCode by mutableStateOf("")
    var smsCooldown by mutableStateOf(0)
    var submitting by mutableStateOf(false)

    /** 与旧版 LoginView WebView `onMessage` 一致：密码登录 `ic` / 短信 `login.sendCode` 票据。 */
    var captchaIc by mutableStateOf<JsonElement?>(null)
    var captchaReloadKey by mutableStateOf(1)
    var smsSheetVisible by mutableStateOf(false)

    /** 每次点「获取验证码」递增，强制 sms-login URL 换新 `t`（RN:78）。 */
    var smsSheetUriNonce by mutableStateOf(0)
    var enterpriseUrlFromPicker by mutableStateOf(true)
    var casEnabled by mutableStateOf(false)
    var casLoginUrl by mutableStateOf("")

    /** 本页校验/请求失败告警（title, message）；CAS 失败走导航级 externalAlert。 */
    var alert by mutableStateOf<Pair<String, String>?>(null)

    /** RN smsCodeSendConsumedRef:79：防滑块回调重复发码。 */
    var smsCodeSendConsumed = false
        private set

    private var labelTapLastMs = 0L
    private var labelTapCount = 0

    private fun alertKeys(titleKey: String, messageKey: String) {
        alert = deps.strings(titleKey) to deps.strings(messageKey)
    }

    private fun alertMessage(titleKey: String, message: String) {
        alert = deps.strings(titleKey) to message
    }

    /** RN:61/132-135：切模式复位账密可见性、关弹层，并按 [mode,selectedUrl,locale] 效果清 ic + reload。 */
    fun switchMode(value: LoginMode) {
        if (mode == value) return
        mode = value
        if (value != LoginMode.PASSWORD) passwordVisible = false // RN:137-139
        if (value != LoginMode.SMS) smsSheetVisible = false // RN:141-143
        captchaIc = null
        captchaReloadKey += 1
    }

    /** RN setSelectedUrl：任何变更（下拉/手输）触发 [mode,selectedUrl] 效果的 ic 清空 + 滑块重载。 */
    fun selectServerUrl(url: String) {
        if (selectedUrl == url) return
        selectedUrl = url
        captchaIc = null
        captchaReloadKey += 1
    }

    /** RN:157-166 toggleEnterpriseUrlEntryMode：切回下拉时重置为首台服务器。 */
    fun toggleEnterpriseUrlEntryMode() {
        enterpriseUrlFromPicker = !enterpriseUrlFromPicker
        if (enterpriseUrlFromPicker) {
            val raw = servers.firstOrNull()?.url?.trim()
            if (!raw.isNullOrEmpty()) selectedUrl = normalizeServerBase(raw)
        }
    }

    /** RN:168-181 onEnterpriseLabelPress：>500ms 重置计数，连点 >10 次切换手输。 */
    fun onEnterpriseLabelPress() {
        val now = deps.nowMillis()
        labelTapCount = if (now - labelTapLastMs > LOGIN_ENTERPRISE_LABEL_DEBUG_GAP_MS) 1 else labelTapCount + 1
        labelTapLastMs = now
        if (labelTapCount > LOGIN_ENTERPRISE_LABEL_DEBUG_TAPS) {
            labelTapCount = 0
            toggleEnterpriseUrlEntryMode()
        }
    }

    /** RN:91-108 的落点：settings.oauth 探测结果（null=隐藏按钮）。 */
    fun setCasAvailability(url: String?) {
        casEnabled = url != null
        casLoginUrl = url.orEmpty()
    }

    /** RN:349-363 onSendSms：校验手机号/服务器 → 清 ic、复位防重、换 nonce、开弹层。 */
    fun onSendSms() {
        if (filterDigits(phone).isEmpty()) {
            alertKeys("login_alertmissingtitle", "login_alert_missing_phone")
            return
        }
        if (selectedUrl.trim().isEmpty()) {
            alertKeys("login_alertmissingtitle", "login_alertmissingmessage")
            return
        }
        captchaIc = null
        smsCodeSendConsumed = false
        smsSheetUriNonce += 1
        smsSheetVisible = true
    }

    /** RN:303-339 sendSmsAfterCaptcha：防重标记 → 发码 → 成功进冷却 / 失败清 ic 重载滑块。 */
    suspend fun sendSmsAfterCaptcha(ic: JsonElement?) {
        if (smsCodeSendConsumed) return
        smsCodeSendConsumed = true
        val digits = filterDigits(phone)
        val server = selectedUrl.trim()
        if (digits.isEmpty() || server.isEmpty()) {
            smsCodeSendConsumed = false
            return
        }
        captchaIc = ic
        try {
            val res = deps.sendCode(server, digits, areaCode, ic)
            if (!res.success) {
                smsCodeSendConsumed = false
                captchaIc = null
                captchaReloadKey += 1
                alertMessage("login_alertfailedtitle", res.message ?: deps.strings("login_sms_send_failed"))
                return
            }
            smsCooldown = 60 // RN:327
        } catch (e: CancellationException) {
            throw e // 取消是协程信号，不得当发码失败吞掉
        } catch (e: Exception) {
            smsCodeSendConsumed = false
            captchaIc = null
            captchaReloadKey += 1
            alertMessage("login_alertfailedtitle", e.message ?: deps.strings("login_sms_send_failed"))
        }
    }

    /** RN:341-347 handleSmsSheetIc：关弹层并发码。 */
    suspend fun handleSmsSheetIc(ic: JsonElement?) {
        smsSheetVisible = false
        sendSmsAfterCaptcha(ic)
    }

    /** RN:293-301 onCaptchaMessage：JSON 解析失败（心跳）保持原值。 */
    fun onCaptchaMessage(raw: String) {
        parseJsJson(raw)?.let { captchaIc = it }
    }

    /** RN:235-281 runLogin：成功上抛给 onLoginSuccess（T7 接 AuthRepository）；
     *  失败按模式区分——账密失败清 ic 重载滑块，短信 ic 已被 sendCode 消费故保留（RN:260-267 注释）。 */
    suspend fun runLogin(serverUrl: String, credentials: LoginCredentials) {
        submitting = true
        try {
            val result = deps.login(serverUrl, credentials)
            deps.onLoginSuccess(result, serverUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (credentials !is LoginCredentials.Sms) {
                captchaIc = null
                captchaReloadKey += 1
            }
            alertMessage(
                "login_alertfailedtitle",
                when {
                    isLoginNetworkTimeoutError(e) -> deps.strings("login_network_timeout")
                    e.message != null -> e.message!!
                    else -> deps.strings("login_alertfailedunknown")
                },
            )
        } finally {
            submitting = false
        }
    }

    /** RN:365-408 onSubmit：校验 → SMS 不带 ic（已消费）/ 账密带 ldap+ic。 */
    suspend fun onSubmit() {
        if (submitting) return
        val server = selectedUrl.trim()
        if (server.isEmpty()) {
            alertKeys("login_alertmissingtitle", "login_alertmissingmessage")
            return
        }
        if (mode == LoginMode.PASSWORD) {
            val user = username.trim()
            if (user.isEmpty() || password.isEmpty()) {
                alertKeys("login_alertmissingtitle", "login_alert_missing_password_login")
                return
            }
            if (captchaIc == null) {
                alertKeys("login_alertmissingtitle", "login_captcha_required")
                return
            }
            runLogin(server, LoginCredentials.Password(username = user, password = password, ic = captchaIc, ldap = true))
            return
        }
        val digits = filterDigits(phone)
        val code = smsCode.trim()
        if (digits.isEmpty() || code.isEmpty()) {
            alertKeys("login_alertmissingtitle", "login_alert_missing_sms_login")
            return
        }
        if (captchaIc == null) {
            alertKeys("login_alertmissingtitle", "login_captcha_required")
            return
        }
        // ic 已在 login.sendCode 消费；POST login 短信分支不带 ic（部分现网会拒绝）
        runLogin(server, LoginCredentials.Sms(phone = digits, code = code, areaCode = areaCode))
    }

    /** RN:198-211 onForgotPassword：忘记密码网页（非 Rocket reset-password）。 */
    fun onForgotPassword(): AuthWebRequest? {
        if (submitting) return null
        val enterprise = selectedUrl.trim()
        if (enterprise.isEmpty()) {
            alertKeys("login_alertmissingtitle", "login_alertmissingmessage")
            return null
        }
        return AuthWebRequest(
            url = resolveAppiaForgotPasswordWebUrl(enterprise),
            title = deps.strings("login_forgot_password"),
            server = enterprise,
        )
    }

    /** RN:213-233 onPressCas：17 位随机 ssoToken + `{casUrl}?service={server}/_cas/{token}`。 */
    fun onPressCas(): AuthWebRequest? {
        if (submitting) return null
        val server = selectedUrl.trim()
        val casUrl = casLoginUrl.trim()
        if (server.isEmpty() || casUrl.isEmpty()) {
            alertKeys("login_alertmissingtitle", "login_alertmissingmessage")
            return null
        }
        val ssoToken = deps.generateSsoToken()
        return AuthWebRequest(
            url = CasApi.buildCasUrl(casUrl, server, ssoToken),
            title = "SSO",
            authType = "cas",
            ssoToken = ssoToken,
            server = server,
        )
    }
}

/** 导航往返保留输入态（RN react-navigation 屏幕实例常驻的等价物）；瞬时态不恢复。 */
private fun loginStateSaver(deps: LoginDeps, servers: List<CompanyServer>): Saver<LoginState, Any> = mapSaver(
    save = { s ->
        mapOf(
            "mode" to s.mode.name,
            "url" to s.selectedUrl,
            "user" to s.username,
            "pass" to s.password,
            "pv" to s.passwordVisible,
            "area" to s.areaCode,
            "phone" to s.phone,
            "code" to s.smsCode,
            "cd" to s.smsCooldown,
            "ic" to s.captchaIc?.toString(),
            "rk" to s.captchaReloadKey,
            "nonce" to s.smsSheetUriNonce,
            "picker" to s.enterpriseUrlFromPicker,
            "casOn" to s.casEnabled,
            "casUrl" to s.casLoginUrl,
        )
    },
    restore = { m ->
        LoginState(servers, deps).apply {
            mode = LoginMode.valueOf(m["mode"] as String)
            selectedUrl = m["url"] as String
            username = m["user"] as String
            password = m["pass"] as String
            passwordVisible = m["pv"] as Boolean
            areaCode = m["area"] as String
            phone = m["phone"] as String
            smsCode = m["code"] as String
            smsCooldown = m["cd"] as Int
            captchaIc = (m["ic"] as String?)?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
            captchaReloadKey = m["rk"] as Int
            smsSheetUriNonce = m["nonce"] as Int
            enterpriseUrlFromPicker = m["picker"] as Boolean
            casEnabled = m["casOn"] as Boolean
            casLoginUrl = m["casUrl"] as String
        }
    },
)

@Composable
internal fun rememberLoginState(
    servers: List<CompanyServer>,
    onLoginSuccess: (LoginResult, String) -> Unit,
): LoginState {
    val context = LocalContext.current
    val deps = remember {
        LoginDeps(
            strings = { context.t(it) },
            sendCode = AuthApi::loginSendCode,
            login = { host, creds -> AuthApi.login(host, creds, AuthApi.LOGIN_TIMEOUT_MS) },
            fetchCasUrl = CasApi::fetchCasLoginUrl,
            generateSsoToken = CasApi::generateSsoToken,
            onLoginSuccess = onLoginSuccess,
        )
    }
    return rememberSaveable(saver = loginStateSaver(deps, servers)) { LoginState(servers, deps) }
}

/**
 * 登录页（对照 RN LoginScreen/index.tsx 全文）：短信主模式 + 滑块验证码 + `ENABLE_PASSWORD_LOGIN` 门控的密码模式。
 * 导航回调参数化（区号页/AuthWeb/成功/空 servers），Compose 测试注入 fake 不触网。
 */
@Composable
fun LoginScreen(
    servers: List<CompanyServer>,
    onLoginSuccess: (LoginResult, String) -> Unit,
    onMissingServers: () -> Unit = {},
    onOpenAreaCode: (server: String, onSelect: (LoginAreaCodeOption) -> Unit) -> Unit = { _, _ -> },
    onOpenAuthWeb: (AuthWebRequest) -> Unit = {},
    externalAlert: Pair<String, String>? = null,
    onConsumeExternalAlert: () -> Unit = {},
    enablePasswordLogin: Boolean = ENABLE_PASSWORD_LOGIN,
    state: LoginState = rememberLoginState(servers, onLoginSuccess),
) {
    // RN:146-149/:410-412 servers 丢失/为空直接回企业码页（稳态列表 + replace）
    if (servers.isEmpty()) {
        LaunchedEffect(Unit) { onMissingServers() }
        return
    }

    val scope = rememberCoroutineScope()
    val t = state.deps.strings
    val locale = LocalConfiguration.current.locales[0].toLanguageTag()

    // RN:183-187 冷却倒计时（秒级 setTimeout 链 → 秒级 ticker）
    LaunchedEffect(state.smsCooldown) {
        if (state.smsCooldown > 0) {
            delay(1_000)
            state.smsCooldown = (state.smsCooldown - 1).coerceAtLeast(0)
        }
    }
    // RN:91-108 CAS 服务端探测（登录前无会话数据，走公开 settings.oauth；异常保持隐藏）
    LaunchedEffect(state.selectedUrl) {
        val server = state.selectedUrl.trim()
        if (server.isNotEmpty()) state.setCasAvailability(state.deps.fetchCasUrl(server))
    }

    AuthBrandedLayout(title = t("enterprise_welcome")) {
        // 登录方式头（RN:421-454）：门控开显示双 Tab，关则居中「短信登录」标题
        if (enablePasswordLogin) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .background(LoginTheme.screenBackground, RoundedCornerShape(8.dp))
                    .border(1.dp, LoginTheme.separator, RoundedCornerShape(8.dp)),
            ) {
                LoginModeTab(t("login_mode_password"), state.mode == LoginMode.PASSWORD, !state.submitting) {
                    state.switchMode(LoginMode.PASSWORD)
                }
                LoginModeTab(t("login_mode_sms"), state.mode == LoginMode.SMS, !state.submitting) {
                    state.switchMode(LoginMode.SMS)
                }
            }
        } else {
            Text(
                t("login_mode_sms"),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = LoginTheme.titleText,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 20.dp),
            )
        }

        // 企业 label：连点 10 次/500ms 切手输 URL 后门（RN:156-181）
        Text(
            t("login_enterprise"),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = LoginTheme.titleText,
            modifier = Modifier
                .padding(bottom = 4.dp)
                .pointerInput(Unit) { detectTapGestures { state.onEnterpriseLabelPress() } },
        )
        if (state.enterpriseUrlFromPicker) {
            EnterpriseServerDropdown(
                servers = servers,
                selectedUrl = state.selectedUrl,
                enabled = !state.submitting,
                onSelectUrl = state::selectServerUrl,
            )
        } else {
            UnderlineField(
                value = state.selectedUrl,
                onValueChange = state::selectServerUrl,
                placeholder = t("login_enterprise_custom_url_placeholder"),
                enabled = !state.submitting,
                keyboardType = KeyboardType.Uri,
                testTag = "login_enterprise_url_input",
            )
        }

        if (state.mode == LoginMode.PASSWORD) {
            Text(t("login_username"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = LoginTheme.titleText, modifier = Modifier.padding(bottom = 4.dp))
            UnderlineField(
                value = state.username,
                onValueChange = { state.username = it },
                placeholder = t("login_placeholderusername"),
                enabled = !state.submitting,
                testTag = "login_username_input",
            )
            Text(t("login_password"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = LoginTheme.titleText, modifier = Modifier.padding(bottom = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                UnderlineField(
                    value = state.password,
                    onValueChange = { state.password = it },
                    enabled = !state.submitting,
                    visualTransformation = if (state.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.weight(1f),
                    testTag = "login_password_input",
                )
                TextButton(
                    onClick = { state.passwordVisible = !state.passwordVisible },
                    enabled = !state.submitting,
                    modifier = Modifier.testTag("login_password_visibility"),
                ) {
                    Text(
                        if (state.passwordVisible) t("login_hide_password_a11y") else t("login_show_password_a11y"),
                        fontSize = 12.sp,
                        color = LoginTheme.auxiliaryText,
                    )
                }
            }
        } else {
            Text(t("login_phone_label"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = LoginTheme.titleText, modifier = Modifier.padding(bottom = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 14.dp)) {
                // 区号 chip → 区号选择页（RN:189-196 openAreaCodePicker）
                Text(
                    "${state.areaCode} ▼",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = LoginTheme.titleText,
                    modifier = Modifier
                        .testTag("login_area_code_chip")
                        .clickable(enabled = !state.submitting) {
                            val server = state.selectedUrl.trim()
                            if (server.isNotEmpty()) onOpenAreaCode(server) { state.areaCode = it.areaCode }
                        }
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                )
                UnderlineField(
                    value = state.phone,
                    onValueChange = { state.phone = it },
                    placeholder = t("login_phone_placeholder"),
                    enabled = !state.submitting,
                    keyboardType = KeyboardType.Phone,
                    modifier = Modifier.weight(1f),
                    testTag = "login_phone_input",
                )
            }
            Text(t("login_sms_code_label"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = LoginTheme.titleText, modifier = Modifier.padding(bottom = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 14.dp)) {
                UnderlineField(
                    value = state.smsCode,
                    onValueChange = { state.smsCode = it },
                    placeholder = t("login_sms_code_placeholder"),
                    enabled = !state.submitting,
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f),
                    testTag = "login_sms_code_input",
                )
                TextButton(
                    onClick = { state.onSendSms() },
                    enabled = !state.submitting && state.smsCooldown <= 0,
                    modifier = Modifier.testTag("login_send_sms"),
                ) {
                    Text(
                        if (state.smsCooldown > 0) {
                            t("login_resend_sms_seconds").replace("{{seconds}}", state.smsCooldown.toString())
                        } else {
                            t("login_send_sms_code")
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (state.submitting || state.smsCooldown > 0) LoginTheme.auxiliaryText else LoginTheme.actionTint,
                        maxLines = 1,
                    )
                }
            }
        }

        // 密码模式内嵌滑块（RN:595-611）；key(uri) 换 reloadKey 强制重载
        if (state.mode == LoginMode.PASSWORD) {
            Text(
                t("login_captcha_title"),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = LoginTheme.bodyText,
                modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
            )
            val passwordUri = buildPasswordCaptchaUri(state.selectedUrl, locale, state.captchaReloadKey)
            key(passwordUri) {
                CaptchaWebView(
                    uri = passwordUri,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("login_captcha_webview"),
                    onMessage = state::onCaptchaMessage,
                )
            }
            Spacer(Modifier.height(14.dp))
        }

        // 短信滑块弹层（RN:613-620）：每次点发码换 nonce → 换新 t。
        // uri 必须按 [selectedUrl, locale, nonce] memo（RN useMemo:121-130）：否则任意重组（如
        // CAS 探测完成写状态）都会换 t → key(uri) 重建 WebView，拖滑块进度丢失；nowMillis 只随 key 变化取一次
        val sheetUri = remember(state.selectedUrl, locale, state.smsSheetUriNonce) {
            buildSmsCaptchaUri(state.selectedUrl, locale, state.deps.nowMillis() + state.smsSheetUriNonce)
        }
        SmsCaptchaBottomSheet(
            visible = state.smsSheetVisible,
            uri = sheetUri,
            title = t("login_sms_captcha_sheet_title"),
            closeLabel = t("login_sms_captcha_sheet_close"),
            onIc = { ic -> scope.launch { state.handleSmsSheetIc(ic) } },
            onRequestClose = { state.smsSheetVisible = false },
        )

        // RN:414-417 loginButtonDisabled：submitting / 短信缺验证码或 ic / 账密缺 ic
        val loginDisabled = state.submitting ||
            state.captchaIc == null ||
            (state.mode == LoginMode.SMS && state.smsCode.isBlank())
        Button(
            onClick = { scope.launch { state.onSubmit() } },
            enabled = !loginDisabled,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = LoginTheme.actionTint, contentColor = LoginTheme.buttonTextOnPrimary),
            modifier = Modifier.fillMaxWidth().padding(top = 22.dp).height(48.dp).testTag("login_submit"),
        ) {
            if (state.submitting) {
                CircularProgressIndicator(
                    Modifier.size(20.dp),
                    color = LoginTheme.buttonTextOnPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(t("login_signin"), fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }

        if (state.mode == LoginMode.PASSWORD) {
            SecondaryLink(t("login_forgot_password"), enabled = !state.submitting, tag = "login_forgot_password") {
                state.onForgotPassword()?.let(onOpenAuthWeb)
            }
        }
        if (state.casEnabled) {
            SecondaryLink(t("login_cas_sso"), enabled = !state.submitting, tag = "login_cas_sso") {
                state.onPressCas()?.let(onOpenAuthWeb)
            }
        }
    }

    // 本页告警 + 导航级 CAS 失败告警（RN Alert.alert 单按钮）。
    // 导航级告警一次性消费：投递即清源状态、本地暂存展示，导航往返后不重显示
    var externalAlertShown by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(externalAlert) {
        if (externalAlert != null) {
            externalAlertShown = externalAlert
            onConsumeExternalAlert()
        }
    }
    val currentAlert = state.alert ?: externalAlertShown
    if (currentAlert != null) {
        val (title, message) = currentAlert
        val dismiss = {
            if (state.alert != null) state.alert = null else externalAlertShown = null
        }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = dismiss) { Text(t("common_close")) } },
        )
    }
}

@Composable
private fun RowScope.LoginModeTab(label: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .background(if (active) Color(0x1A1D74F5) else LoginTheme.screenBackground)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 15.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            color = if (active) LoginTheme.actionTint else LoginTheme.bodyText,
        )
    }
}

@Composable
private fun SecondaryLink(label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Text(label, fontSize = 15.sp, color = LoginTheme.actionTint)
    }
}

/** RN LoginEnterpriseServerDropdown：label = name ?? ename ?? url；value = url。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnterpriseServerDropdown(
    servers: List<CompanyServer>,
    selectedUrl: String,
    enabled: Boolean,
    onSelectUrl: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    fun rowLabel(s: CompanyServer): String = (s.name ?: s.ename ?: s.url).trim().ifEmpty { s.url }
    val currentLabel = servers.firstOrNull { it.url == selectedUrl }?.let(::rowLabel)
        ?: servers.firstOrNull()?.let(::rowLabel).orEmpty()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                currentLabel,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = LoginTheme.titleText,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text("▾", color = LoginTheme.auxiliaryText)
        }
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            servers.forEach { server ->
                DropdownMenuItem(
                    text = { Text(rowLabel(server), fontSize = 16.sp, color = LoginTheme.bodyText) },
                    onClick = {
                        expanded = false
                        onSelectUrl(server.url)
                    },
                )
            }
        }
    }
}

/** RN inputUnderline：48dp 行高 + 下划线输入。 */
@Composable
private fun UnderlineField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    testTag: String? = null,
) {
    Column(modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            enabled = enabled,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = visualTransformation,
            textStyle = TextStyle(fontSize = 16.sp, color = LoginTheme.titleText),
            cursorBrush = SolidColor(LoginTheme.actionTint),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, fontSize = 16.sp, color = LoginTheme.auxiliaryText)
                    }
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth().then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        )
        HorizontalDivider(thickness = 1.dp, color = LoginTheme.separator)
    }
}
