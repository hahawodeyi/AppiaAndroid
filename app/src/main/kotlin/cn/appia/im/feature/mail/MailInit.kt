package cn.appia.im.feature.mail

import cn.appia.im.core.mail.CredentialPublicKeyResponse
import cn.appia.im.core.mail.EncryptedCredentialPayload
import cn.appia.im.core.mail.MailAccount
import cn.appia.im.core.mail.MailApiException
import cn.appia.im.core.mail.MailClient
import cn.appia.im.core.mail.MailClientAuth
import cn.appia.im.core.mail.MailCredentialKeyCache
import cn.appia.im.core.mail.MailCredentialCipher
import cn.appia.im.core.mail.TestConnectionResponse
import cn.appia.im.core.mail.mailJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement

/**
 * mail 会话引导 + 账户绑定/测试连接（mailApi.ts:41-130 逐行为移植）。
 *
 * [MailClient] 全同步 IO（OkHttp execute），路由层调用一律挂 [Dispatchers.IO]
 * （withContext 包裹，同 RoomsApi 等 REST 口径）。
 */

/** mailApi.ts:29-33 MailExchangeParams：exchange 体取值源（user 非空字段）。 */
data class MailExchangeUser(val email: String, val name: String? = null, val username: String? = null)

/** mailApi.ts:35-39 isMailThrottleError 直接复用 MailClient.isMailThrottleError（坑 5）。 */

/**
 * mailApi.ts:41-62 exchangeAppiaimMailToken：auth 三元组缺一即 401 语义错；skipRefresh 防环。
 * auth 取值源 = client 的 authProvider（RN client.getAuth()）。
 */
internal fun exchangeAppiaimMailToken(client: MailClient, user: MailExchangeUser, auth: MailClientAuth) {
    val email = user.email.trim()
    if (auth.authToken.isBlank() || auth.userId.isBlank() || email.isEmpty()) {
        throw MailApiException(401, "Incomplete user info for mail exchange")
    }
    client.request<Unit>(
        "/auth/appiaim-exchange",
        method = "POST",
        body = buildJsonObject {
            put("appiaimUserId", JsonPrimitive(auth.userId))
            put("appiaimToken", JsonPrimitive(auth.authToken))
            put("email", JsonPrimitive(email))
            put("name", JsonPrimitive(user.name ?: user.username ?: ""))
            put("username", JsonPrimitive(user.username ?: ""))
        },
        skipRefresh = true,
    )
}

/**
 * bootstrapMailSession 的 auth 解析缝：authProvider 空或缺 token/userId → 401 语义错
 * （RN exchangeAppiaimMailToken 内 getAuth 判空同义——提前到引导链入口判）。
 */
private fun requireMailAuth(auth: MailClientAuth?): MailClientAuth =
    auth?.takeIf { it.authToken.isNotBlank() && it.userId.isNotBlank() }
        ?: throw MailApiException(401, "Incomplete user info for mail exchange")

/** mailApi.ts:64-69：未绑定/未建立会话 → 引导绑定页而非报错。 */
fun isMailSetupRequiredError(error: Throwable): Boolean {
    if (error !is MailApiException) return false
    if (error.status == 401 || error.status == 403) return true
    return error.message?.contains("Incomplete user info") == true
}

/**
 * mailApi.ts:71-107 bootstrapMailSession（坑 6 钉死）：
 * 1. GET /accounts 成功 → 直接返回；
 * 2. 403（邮件未开通）→ **静默空**（不弹错）；
 * 3. 401 → user.email 空 → []；否则 exchange（setup-required 错 → []）→ 再列账户
 *    （setup-required → []，其他错照抛）。
 */
suspend fun bootstrapMailSession(
    client: MailClient,
    user: MailExchangeUser,
    authProvider: () -> MailClientAuth?,
): List<MailAccount> =
    withContext(Dispatchers.IO) {
        try {
            return@withContext listMailAccounts(client)
        } catch (error: MailApiException) {
            if (error.status != 401) {
                if (error.status == 403) return@withContext emptyList()
                throw error
            }
        }

        if (user.email.isBlank()) return@withContext emptyList()

        try {
            exchangeAppiaimMailToken(client, user, requireMailAuth(authProvider()))
        } catch (error: Throwable) {
            if (isMailSetupRequiredError(error)) return@withContext emptyList()
            throw error
        }

        try {
            listMailAccounts(client)
        } catch (error: Throwable) {
            if (isMailSetupRequiredError(error)) return@withContext emptyList()
            throw error
        }
    }

/** mailApi.ts:109-110 listMailAccounts。 */
fun listMailAccounts(client: MailClient): List<MailAccount> =
    client.request("/accounts", ListSerializer(MailAccount.serializer()))

/** mailApi.ts:112-118：加密凭证体（公钥按 keyVersion 缓存——坑 2）。 */
internal fun toEncryptedCredentialBody(cache: MailCredentialKeyCache, client: MailClient, email: String, password: String): EncryptedCredentialPayload {
    val info = cache.load { fetchMailCredentialPublicKey(client) }
    return MailCredentialCipher.buildEncryptedCredentialPayload(email, password, info.publicKeyPem)
}

/** mailApi.ts:112-113 fetchMailCredentialPublicKey。 */
internal fun fetchMailCredentialPublicKey(client: MailClient): CredentialPublicKeyResponse =
    client.request("/accounts/credential-public-key", CredentialPublicKeyResponse.serializer())

/** mailApi.ts:120-124 bindMailAccount：POST /accounts（密码加密体）。 */
fun bindMailAccount(client: MailClient, cache: MailCredentialKeyCache, email: String, password: String): MailAccount =
    client.request(
        "/accounts",
        MailAccount.serializer(),
        method = "POST",
        body = mailJson.encodeToJsonElement(toEncryptedCredentialBody(cache, client, email, password)),
    )

/** mailApi.ts:126-130 testMailConnection：POST /accounts/test-connection（密码加密体）。 */
fun testMailConnection(client: MailClient, cache: MailCredentialKeyCache, email: String, password: String): TestConnectionResponse =
    client.request(
        "/accounts/test-connection",
        TestConnectionResponse.serializer(),
        method = "POST",
        body = mailJson.encodeToJsonElement(toEncryptedCredentialBody(cache, client, email, password)),
    )
