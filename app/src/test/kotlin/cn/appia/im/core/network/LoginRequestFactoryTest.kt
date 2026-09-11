package cn.appia.im.core.network

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * 逐字段对照 RN loginCredentialsRest.ts:20-75 的五类 wire body：
 * `toString()` 断言整串 JSON = 字段集合 + 字段顺序（kotlinx buildJsonObject 保插入序）同时钉死。
 */
class LoginRequestFactoryTest {
    private fun call(credentials: LoginCredentials) = LoginRequestFactory.create(credentials)

    // ---- Password 非 LDAP：`login` {username, password, ic?}（RN :67-74）----

    @Test
    fun `password without ic posts username password only`() {
        val call = call(LoginCredentials.Password("bob", "secret"))

        assertEquals("login", call.endpoint)
        assertEquals("""{"username":"bob","password":"secret"}""", call.body.toString())
    }

    @Test
    fun `password appends ic last when present`() {
        val call = call(LoginCredentials.Password("bob", "secret", ic = JsonPrimitive("ic-1")))

        assertEquals("login", call.endpoint)
        assertEquals("""{"username":"bob","password":"secret","ic":"ic-1"}""", call.body.toString())
    }

    // ---- Password LDAP：`verify-ic` {username, ldapPass, ldap:true, ldapOptions:{}, ic?}（RN :54-65）----

    @Test
    fun `ldap password posts to verify-ic with ldapPass and empty ldapOptions`() {
        val call = call(
            LoginCredentials.Password("bob", "secret", ic = JsonPrimitive("ic-2"), ldap = true),
        )

        assertEquals("verify-ic", call.endpoint)
        assertEquals(
            """{"username":"bob","ldapPass":"secret","ldap":true,"ldapOptions":{},"ic":"ic-2"}""",
            call.body.toString(),
        )
    }

    @Test
    fun `ldap password without ic omits key`() {
        val call = call(LoginCredentials.Password("bob", "secret", ldap = true))

        assertEquals("verify-ic", call.endpoint)
        assertEquals(
            """{"username":"bob","ldapPass":"secret","ldap":true,"ldapOptions":{}}""",
            call.body.toString(),
        )
        assertFalse("ic" in call.body)
        assertFalse("password" in call.body)
    }

    // ---- Sms：`login` {smsCode:true, phone, code, areaCode}，不带 ic（RN :30-42；ic 由变体刻意去除）----

    @Test
    fun `sms posts smsCode body without ic`() {
        val call = call(LoginCredentials.Sms(phone = "13800000000", code = "1234", areaCode = "+86"))

        assertEquals("login", call.endpoint)
        assertEquals(
            """{"smsCode":true,"phone":"13800000000","code":"1234","areaCode":"+86"}""",
            call.body.toString(),
        )
        assertFalse("ic" in call.body)
    }

    // ---- SwitchOrg：`login` {userId, userToken, url}（RN :44-50）----

    @Test
    fun `switch org posts three fields`() {
        val call = call(LoginCredentials.SwitchOrg(userId = "u-1", userToken = "t-1", url = "https://a.cn"))

        assertEquals("login", call.endpoint)
        assertEquals(
            """{"userId":"u-1","userToken":"t-1","url":"https://a.cn"}""",
            call.body.toString(),
        )
    }

    // ---- Cas：`login` {cas:{credentialToken}}（RN :23-28）----

    @Test
    fun `cas nests credentialToken`() {
        val call = call(LoginCredentials.Cas("cas-ticket"))

        assertEquals("login", call.endpoint)
        assertEquals("""{"cas":{"credentialToken":"cas-ticket"}}""", call.body.toString())
    }
}
