package cn.appia.im.core.network

import kotlinx.serialization.json.JsonElement

/**
 * 五类登录凭证（对照 RN credentialsTypes.ts 全集；端点/请求体映射见 LoginRequestFactory）。
 * RN 靠判别字段窄化（`'smsCode' in c` / `'cas' in c` / `'switchLogin' in c` / `'ldap' in c`），
 * Kotlin 直接以 sealed 变体表达同一判别。
 */
sealed class LoginCredentials {
    /**
     * 账密登录。`ldap=false` 走 `login`（RN RocketChatLocalPasswordLogin）；
     * `ldap=true` 走 `verify-ic`（RN RocketChatLdapPasswordLogin：企业 LDAP/多主体，password 映射为 `ldapPass`）。
     * `ic` 为企业码/滑块票据（RN 类型 `unknown`，任意 JSON 值），由工厂原样透传到 wire body 末位。
     */
    data class Password(
        val username: String,
        val password: String,
        val ic: JsonElement? = null,
        val ldap: Boolean = false,
    ) : LoginCredentials()

    /**
     * 手机号 + 短信验证码。**刻意不带 `ic`** —— RN LoginScreen.tsx:398-407 的时序坑：
     * sendCode 已消费滑块票据 ic，login 再携带会被服务端二次校验拒绝
     * （对照 RN loginCredentialsRest.ts:30-42 虽支持 ic，但登录时序下不可传）。
     */
    data class Sms(
        val phone: String,
        val code: String,
        val areaCode: String,
    ) : LoginCredentials()

    /**
     * 多主体切换（RN RocketChatSwitchOrgLogin）：对目标 host `POST login`，
     * `url` 为切换前当前主体 base URL（无尾斜杠，与现网一致）。
     */
    data class SwitchOrg(
        val userId: String,
        val userToken: String,
        val url: String,
    ) : LoginCredentials()

    /** CAS 单点登录（RN RocketChatCasLogin，wire 形态 `{cas:{credentialToken}}`）。 */
    data class Cas(val credentialToken: String) : LoginCredentials()
}
