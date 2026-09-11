package cn.appia.im.feature.login.ui

import cn.appia.im.core.network.LoginCredentials
import cn.appia.im.core.network.LoginResult
import cn.appia.im.feature.login.CompanyServer
import cn.appia.im.feature.login.SendCodeResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 登录状态机逻辑（RN LoginScreen.tsx 逐条对照，请求边界之上）：
 * 发码防重（RN:303-339）、60s 冷却（RN:327）、账密失败清 ic / 短信失败保留（RN:260-267）、
 * 服务器选择（RN:35-43）、连点切手输（RN:168-181）、CAS/忘记密码 URL 构造（RN:198-233）。
 * strings 注入 key 恒等：告警断言即 i18n key 断言。
 */
class LoginStateLogicTest {

    private val servers = listOf(
        CompanyServer(url = "https://s1/", name = "S1"),
        CompanyServer(url = "https://s2", name = "S2", selected = true),
    )
    private val ic: JsonObject = JsonObject(mapOf("ic" to JsonPrimitive("ticket")))

    private class SendCodeRecorder(
        var result: SendCodeResult = SendCodeResult(true),
        var error: Exception? = null,
    ) {
        var calls = 0
        var lastArgs: List<Any?> = emptyList()

        suspend fun send(host: String, phone: String, areaCode: String, ic: JsonElement?): SendCodeResult {
            calls += 1
            lastArgs = listOf(host, phone, areaCode, ic)
            error?.let { throw it }
            return result
        }
    }

    private class LoginRecorder {
        var calls = 0
        var lastHost: String? = null
        var lastCreds: LoginCredentials? = null
        var error: Exception? = null

        suspend fun login(host: String, creds: LoginCredentials): LoginResult {
            calls += 1
            lastHost = host
            lastCreds = creds
            error?.let { throw it }
            return LoginResult(authToken = "tok", userId = "uid")
        }
    }

    private fun state(
        sendCode: SendCodeRecorder = SendCodeRecorder(),
        login: LoginRecorder = LoginRecorder(),
        onLoginSuccess: (LoginResult, String) -> Unit = { _, _ -> },
        now: () -> Long = { 1_000L },
        testServers: List<CompanyServer> = servers,
    ): LoginState = LoginState(
        testServers,
        LoginDeps(
            strings = { it },
            sendCode = sendCode::send,
            login = login::login,
            fetchCasUrl = { null },
            generateSsoToken = { "abc123xyz09876zy" }, // 17 位
            onLoginSuccess = onLoginSuccess,
            nowMillis = now,
        ),
    )

    // ---- 服务器选择（RN:35-43/:151-166）----

    @Test
    fun `initial url prefers selected then first then fallback`() {
        assertEquals("https://s2", pickInitialServerUrl(servers))
        assertEquals("https://s1/", pickInitialServerUrl(listOf(CompanyServer("https://s1/"), CompanyServer("https://s2"))))
        assertEquals("https://appia.cn", pickInitialServerUrl(listOf(CompanyServer("   "))))
    }

    @Test
    fun `switching back to picker resets to first server normalized`(): Unit = runBlocking {
        val st = state()
        st.toggleEnterpriseUrlEntryMode() // → 手输
        st.selectServerUrl("https://custom.example.com/")
        st.toggleEnterpriseUrlEntryMode() // → 下拉，重置首台（RN:157-166）
        assertEquals("https://s1", st.selectedUrl) // 去尾斜杠
        assertTrue(st.enterpriseUrlFromPicker)
    }

    // ---- 发码（RN:303-363）----

    @Test
    fun `send sms without phone alerts missing phone and keeps sheet closed`() {
        val st = state()
        st.onSendSms()
        assertEquals("login_alertmissingtitle" to "login_alert_missing_phone", st.alert)
        assertFalse(st.smsSheetVisible)
    }

    @Test
    fun `send sms opens sheet with fresh nonce and cleared ic`(): Unit = runBlocking {
        val st = state()
        st.captchaIc = ic
        st.smsSheetUriNonce = 3
        st.phone = "138 1234 5678"
        st.onSendSms()
        assertTrue(st.smsSheetVisible)
        assertEquals(4, st.smsSheetUriNonce) // 每次点发码换 nonce
        assertNull(st.captchaIc) // RN:359
        assertFalse(st.smsCodeSendConsumed) // RN:360 复位防重
    }

    @Test
    fun `sheet ic triggers send code exactly once then cooldown 60`(): Unit = runBlocking {
        val send = SendCodeRecorder()
        val st = state(sendCode = send)
        st.phone = "13812345678"
        st.handleSmsSheetIc(ic)
        assertEquals(1, send.calls)
        // digits 过滤 + areaCode + ic 原样透传（RN:307/:313-319）
        assertEquals(listOf("https://s2", "13812345678", "+86", ic), send.lastArgs)
        assertEquals(60, st.smsCooldown) // RN:327
        assertFalse(st.smsSheetVisible)
        assertEquals(ic, st.captchaIc) // 登录按钮依赖 ic 非空

        st.handleSmsSheetIc(ic) // smsCodeSendConsumed 防重（RN:305-306）
        assertEquals(1, send.calls)
    }

    @Test
    fun `send code failure resets consumed guard clears ic and bumps reload`(): Unit = runBlocking {
        val send = SendCodeRecorder(result = SendCodeResult(false, "boom"))
        val st = state(sendCode = send)
        st.phone = "13812345678"
        st.captchaReloadKey = 1
        st.handleSmsSheetIc(ic)
        assertEquals(1, send.calls)
        assertFalse(st.smsCodeSendConsumed) // 可重新发码
        assertNull(st.captchaIc) // RN:322
        assertEquals(2, st.captchaReloadKey) // RN:323 强制滑块重载
        assertEquals("login_alertfailedtitle" to "boom", st.alert) // RN:324 message 透传
    }

    @Test
    fun `send code exception surfaces raw message`() = runBlocking {
        val send = SendCodeRecorder(error = IllegalStateException("connection reset"))
        val st = state(sendCode = send)
        st.phone = "13812345678"
        st.handleSmsSheetIc(ic)
        assertEquals("login_alertfailedtitle" to "connection reset", st.alert) // RN:332-335 e.message
        assertNull(st.captchaIc)
    }

    @Test
    fun `sheet ic with empty digits resets guard without calling send code`(): Unit = runBlocking {
        val send = SendCodeRecorder()
        val st = state(sendCode = send)
        st.phone = "abc" // digits 为空（RN:309-312）
        st.handleSmsSheetIc(ic)
        assertEquals(0, send.calls)
        assertFalse(st.smsCodeSendConsumed)
    }

    // ---- 登录提交（RN:235-281/:365-408）----

    @Test
    fun `sms submit requires code and captcha ic`() = runBlocking {
        val st = state()
        st.phone = "13812345678"
        st.onSubmit()
        assertEquals("login_alertmissingtitle" to "login_alert_missing_sms_login", st.alert)

        st.smsCode = "1234"
        st.onSubmit()
        assertEquals("login_alertmissingtitle" to "login_captcha_required", st.alert) // RN:394-397
    }

    @Test
    fun `sms submit success calls login without ic and reports success with server`() = runBlocking {
        val login = LoginRecorder()
        var successArgs: Pair<LoginResult, String>? = null
        val st = state(login = login, onLoginSuccess = { r, s -> successArgs = r to s })
        st.phone = "13812345678"
        st.smsCode = " 5678 "
        st.captchaIc = ic
        st.onSubmit()
        val creds = login.lastCreds
        assertTrue(creds is LoginCredentials.Sms)
        assertEquals("13812345678", (creds as LoginCredentials.Sms).phone) // digits 过滤
        assertEquals("5678", creds.code.trim())
        assertEquals("+86", creds.areaCode)
        assertEquals("https://s2", login.lastHost)
        assertEquals("uid", successArgs?.first?.userId)
        assertEquals("https://s2", successArgs?.second)
        assertFalse(st.submitting)
    }

    @Test
    fun `sms login failure keeps ic so button stays usable`() = runBlocking {
        val login = LoginRecorder().apply { error = IllegalStateException("wrong code") }
        val st = state(login = login)
        st.phone = "13812345678"
        st.smsCode = "1234"
        st.captchaIc = ic
        st.captchaReloadKey = 1
        st.onSubmit()
        assertEquals(ic, st.captchaIc) // RN:260-263：短信 ic 已消费，保留不清
        assertEquals(1, st.captchaReloadKey)
        assertEquals("login_alertfailedtitle" to "wrong code", st.alert)
    }

    @Test
    fun `password failure clears ic and reloads slider`() = runBlocking {
        val login = LoginRecorder().apply { error = IllegalStateException("bad credentials") }
        val st = state(login = login)
        st.switchMode(LoginMode.PASSWORD) // RN:132-135 切模式本身也清 ic + reload
        assertEquals(2, st.captchaReloadKey)
        st.username = "user"
        st.password = "pass"
        st.captchaIc = ic
        st.captchaReloadKey = 5
        st.onSubmit()
        assertNull(st.captchaIc) // RN:265：账密失败清 ic
        assertEquals(6, st.captchaReloadKey) // RN:266 重载滑块
        assertEquals("login_alertfailedtitle" to "bad credentials", st.alert)
    }

    @Test
    fun `password submit validates fields and sends ldap credentials with ic`() = runBlocking {
        val login = LoginRecorder()
        val st = state(login = login)
        st.switchMode(LoginMode.PASSWORD)
        st.onSubmit()
        assertEquals("login_alertmissingtitle" to "login_alert_missing_password_login", st.alert)

        st.username = " user "
        st.password = "pass"
        st.onSubmit()
        assertEquals("login_alertmissingtitle" to "login_captcha_required", st.alert) // RN:380-383

        st.captchaIc = ic
        st.onSubmit()
        val creds = login.lastCreds
        assertTrue(creds is LoginCredentials.Password)
        creds as LoginCredentials.Password
        assertEquals("user", creds.username) // trim
        assertTrue(creds.ldap) // RN:384 ldap=true
        assertEquals(ic, creds.ic)
        assertEquals("https://s2", login.lastHost)
    }

    @Test
    fun `network timeout error maps to i18n timeout message`() = runBlocking {
        val login = LoginRecorder().apply { error = IllegalStateException("Connection failed") }
        val st = state(login = login)
        st.phone = "13812345678"
        st.smsCode = "1234"
        st.captchaIc = ic
        st.onSubmit()
        assertEquals("login_alertfailedtitle" to "login_network_timeout", st.alert)
    }

    @Test
    fun `submit without server alerts missing message`() = runBlocking {
        val st = state(testServers = listOf(CompanyServer("   ")))
        st.selectedUrl = "   "
        st.onSubmit()
        assertEquals("login_alertmissingtitle" to "login_alertmissingmessage", st.alert)
    }

    // ---- 企业 label 连点后门（RN:168-181）----

    @Test
    fun `ten rapid label taps do not toggle eleventh does`(): Unit = runBlocking {
        var now = 1_000L
        val st = state(now = { now })
        assertTrue(st.enterpriseUrlFromPicker) // 初始下拉
        repeat(LOGIN_ENTERPRISE_LABEL_DEBUG_TAPS) { st.onEnterpriseLabelPress() } // 10 次：count==10，不触发
        assertTrue(st.enterpriseUrlFromPicker)
        st.onEnterpriseLabelPress() // 第 11 次：count>10 触发
        assertFalse(st.enterpriseUrlFromPicker)
    }

    @Test
    fun `label taps beyond gap reset counter`() = runBlocking {
        var now = 1_000L
        val st = state(now = { now })
        repeat(LOGIN_ENTERPRISE_LABEL_DEBUG_TAPS + 5) {
            st.onEnterpriseLabelPress()
            now += LOGIN_ENTERPRISE_LABEL_DEBUG_GAP_MS + 1 // 每次都超 500ms → count 恒为 1
        }
        assertTrue(st.enterpriseUrlFromPicker)
    }

    // ---- CAS / 忘记密码（RN:198-233）----

    @Test
    fun `cas press builds sso request with 17 char token`() {
        val st = state()
        st.setCasAvailability("https://cas.example.com/login")
        val req = st.onPressCas()
        assertEquals("https://cas.example.com/login?service=https://s2/_cas/abc123xyz09876zy", req?.url)
        assertEquals("cas", req?.authType)
        assertEquals("abc123xyz09876zy", req?.ssoToken)
        assertEquals("https://s2", req?.server)
        assertTrue(st.casEnabled)
    }

    @Test
    fun `cas press without server url alerts missing message`() {
        val st = state()
        val req = st.onPressCas() // casLoginUrl 空
        assertNull(req)
        assertEquals("login_alertmissingtitle" to "login_alertmissingmessage", st.alert)
    }

    @Test
    fun `forgot password url resolves ark variant by enterprise host`() {
        val st = state()
        val req = st.onForgotPassword()
        assertEquals("https://pwdresetall.appia.vip/?from=appia", req?.url)
        assertEquals("login_forgot_password", req?.title)
        assertNull(req?.authType)

        st.selectServerUrl("https://console.ark.appia.cn")
        assertEquals("https://pwdreset-ark.appia.cn/", st.onForgotPassword()?.url)
    }

    // ---- 纯函数 ----

    @Test
    fun `captcha uri builders match rn endpoints and vary by t`() {
        assertEquals("https://s1/verification/sms?locale=zh-CN&t=3", buildPasswordCaptchaUri("https://s1/", "zh-CN", 3))
        assertEquals("https://s1/verification/sms-login?locale=zh-CN&t=1710000000001", buildSmsCaptchaUri("https://s1/", "zh-CN", 1710000000001L))
        assertTrue(
            buildSmsCaptchaUri("https://s1", "zh-CN", 1L) != buildSmsCaptchaUri("https://s1", "zh-CN", 2L), // nonce 变化 → 换新 t
        )
    }

    @Test
    fun `filter digits keeps ascii only`() {
        assertEquals("13812345678", filterDigits("+138-1234-5678"))
        assertEquals("", filterDigits("١٢٣")) // 非 ASCII 数字同样剔除（JS \d 语义）
    }

    @Test
    fun `timeout predicate matches rocket chat messages`() {
        assertTrue(isLoginNetworkTimeoutError(IllegalStateException("closed before connect")))
        assertTrue(isLoginNetworkTimeoutError(IllegalStateException("request aborted")))
        assertFalse(isLoginNetworkTimeoutError(IllegalStateException("bad credentials")))
        assertFalse(isLoginNetworkTimeoutError(null))
    }

    @Test
    fun `captcha message keeps previous ic on non json heartbeat`() {
        val st = state()
        st.captchaIc = ic
        st.onCaptchaMessage("{\"ic\":\"next\"}")
        assertEquals(Json.parseToJsonElement("{\"ic\":\"next\"}"), st.captchaIc)
        st.onCaptchaMessage("ping") // H5 心跳忽略（RN:293-301）
        assertEquals(Json.parseToJsonElement("{\"ic\":\"next\"}"), st.captchaIc)
        st.onCaptchaMessage("")
        assertEquals(Json.parseToJsonElement("{\"ic\":\"next\"}"), st.captchaIc)
    }

    @Test
    fun `switch mode to same value keeps state`() = runBlocking {
        val st = state()
        st.captchaIc = ic
        st.switchMode(LoginMode.SMS) // 同值不触发 RN:132-135 效果
        assertEquals(ic, st.captchaIc)
        assertEquals(1, st.captchaReloadKey)
    }
}
