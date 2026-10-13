package cn.appia.im.feature.mail

import cn.appia.im.core.mail.ListMessagesParams
import cn.appia.im.core.mail.MailClient
import cn.appia.im.core.mail.MailDraft
import cn.appia.im.core.mail.MailFolder
import cn.appia.im.core.mail.MailFoldersResponse
import cn.appia.im.core.mail.MessageListResponse
import cn.appia.im.core.mail.SearchMessagesParams
import cn.appia.im.core.mail.SearchMessagesResponse
import cn.appia.im.core.mail.SyncResponse
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import cn.appia.im.core.mail.mailJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 收件箱域 API（mailApi.ts:132-254 移植）。全同步 IO（MailClient OkHttp execute），
 * 调用方一律挂 Dispatchers.IO（MailInit 同口径）。
 */

/** mailApi.ts:132-135 listMailFolders：GET /accounts/:id/folders。 */
fun listMailFolders(client: MailClient, accountId: String): List<MailFolder> =
    client.request("/accounts/$accountId/folders", MailFoldersResponse.serializer()).folders

/**
 * mailApi.ts:137-152 listMailMessages：GET /accounts/:id/messages（query null/空跳过，
 * MailClient.url 承担——unreadOnly=false 不上行同 RN `unreadOnly || undefined`）。
 */
fun listMailMessages(client: MailClient, accountId: String, params: ListMessagesParams): MessageListResponse =
    client.request(
        "/accounts/$accountId/messages",
        MessageListResponse.serializer(),
        params = mapOf(
            "folder" to params.folder,
            "page" to params.page,
            "pageSize" to params.pageSize,
            "unreadOnly" to params.unreadOnly?.takeIf { it },
            "threadId" to params.threadId,
            "conversationId" to params.conversationId,
        ),
    )

/**
 * mailApi.ts:153-169 searchMailMessages：GET /accounts/:id/messages/search
 * （RN 此口只上行 q/folder/page/pageSize——坑：不透传 unreadOnly 等）。
 */
fun searchMailMessages(client: MailClient, accountId: String, params: SearchMessagesParams): SearchMessagesResponse =
    client.request(
        "/accounts/$accountId/messages/search",
        SearchMessagesResponse.serializer(),
        params = mapOf(
            "q" to params.q,
            "folder" to params.folder,
            "page" to params.page,
            "pageSize" to params.pageSize,
        ),
    )

/** mailApi.ts:193-198 triggerMailSync：POST /accounts/:id/sync（folder 省略 → 空体）。 */
fun triggerMailSync(client: MailClient, accountId: String, folder: String? = null): SyncResponse =
    client.request(
        "/accounts/$accountId/sync",
        SyncResponse.serializer(),
        method = "POST",
        body = buildJsonObject { folder?.let { put("folder", it) } },
    )

/** mailApi.ts:245-247 listMailDrafts：GET /drafts（全账户，屏侧按 accountId 过滤）。 */
fun listMailDrafts(client: MailClient): List<MailDraft> =
    client.request("/drafts", ListSerializer(MailDraft.serializer()))

/** mailApi.ts:253-257 deleteMailDraft：DELETE /drafts/:id（204 → null）。 */
fun deleteMailDraft(client: MailClient, draftId: String) {
    client.request<Unit>("/drafts/$draftId", Unit.serializer(), method = "DELETE")
}

// ---- 详情域（mailApi.ts:171-199 / T4）----

/** mailApi.ts:171-176 getMailMessageDetail：GET /messages/{id}（剥前缀 + 五组别名归一化）。 */
fun getMailMessageDetail(client: MailClient, messageId: String): cn.appia.im.core.mail.MessageDetail {
    val normalizedId = cn.appia.im.core.mail.normalizeMailMessageId(messageId)
    val raw = client.request("/messages/$normalizedId", cn.appia.im.core.mail.RawMessageDetail.serializer())
    return cn.appia.im.core.mail.normalizeMailMessageDetail(raw)
}

/** mailApi.ts:178-186 listMailThreadMessages：GET /accounts/:id/messages/:msgId/thread（id 原样不剥——RN 直拼）。 */
fun listMailThreadMessages(
    client: MailClient,
    accountId: String,
    messageId: String,
): cn.appia.im.core.mail.MailThreadResponse =
    client.request("/accounts/$accountId/messages/$messageId/thread", cn.appia.im.core.mail.MailThreadResponse.serializer())

/** mailApi.ts:188-193 deleteMailMessage：DELETE /messages/:id（Trash→permanent 标记响应体）。 */
fun deleteMailMessage(client: MailClient, messageId: String): cn.appia.im.core.mail.DeleteMailMessageResponse =
    client.request("/messages/$messageId", cn.appia.im.core.mail.DeleteMailMessageResponse.serializer(), method = "DELETE")

// ---- 撰写域（mailApi.ts:199-276 / T5）----

/** mailApi.ts:199-208 replyMail：POST /accounts/:id/messages/:msgId/reply。 */
fun replyMail(
    client: MailClient,
    accountId: String,
    messageId: String,
    data: cn.appia.im.core.mail.SendMailRequest,
): cn.appia.im.core.mail.SendMailResponse =
    client.request(
        "/accounts/$accountId/messages/$messageId/reply",
        cn.appia.im.core.mail.SendMailResponse.serializer(),
        method = "POST",
        body = mailJson.encodeToJsonElement(cn.appia.im.core.mail.SendMailRequest.serializer(), data),
    )

/** mailApi.ts:210-214 sendMail：POST /accounts/:id/send。 */
fun sendMail(
    client: MailClient,
    accountId: String,
    data: cn.appia.im.core.mail.SendMailRequest,
): cn.appia.im.core.mail.SendMailResponse =
    client.request(
        "/accounts/$accountId/send",
        cn.appia.im.core.mail.SendMailResponse.serializer(),
        method = "POST",
        body = mailJson.encodeToJsonElement(cn.appia.im.core.mail.SendMailRequest.serializer(), data),
    )

/** mailApi.ts:216-225 forwardMail：POST /accounts/:id/messages/:msgId/forward。 */
fun forwardMail(
    client: MailClient,
    accountId: String,
    messageId: String,
    data: cn.appia.im.core.mail.SendMailRequest,
): cn.appia.im.core.mail.SendMailResponse =
    client.request(
        "/accounts/$accountId/messages/$messageId/forward",
        cn.appia.im.core.mail.SendMailResponse.serializer(),
        method = "POST",
        body = mailJson.encodeToJsonElement(cn.appia.im.core.mail.SendMailRequest.serializer(), data),
    )

/** mailApi.ts:236-243 createForwardMailDraft：POST /accounts/:id/messages/:msgId/forward-draft。 */
fun createForwardMailDraft(
    client: MailClient,
    accountId: String,
    messageId: String,
): MailDraft =
    client.request("/accounts/$accountId/messages/$messageId/forward-draft", MailDraft.serializer(), method = "POST")

/** mailApi.ts:249-251 getMailDraft：GET /drafts/:id。 */
fun getMailDraft(client: MailClient, draftId: String): MailDraft =
    client.request("/drafts/$draftId", MailDraft.serializer())

/** mailApi.ts:252-257 createMailDraft：POST /drafts。 */
fun createMailDraft(client: MailClient, data: cn.appia.im.core.mail.UpsertDraftRequest): MailDraft =
    client.request(
        "/drafts",
        MailDraft.serializer(),
        method = "POST",
        body = mailJson.encodeToJsonElement(cn.appia.im.core.mail.UpsertDraftRequest.serializer(), data),
    )

/** mailApi.ts:258-266 updateMailDraft：PUT /drafts/:id。 */
fun updateMailDraft(client: MailClient, draftId: String, data: cn.appia.im.core.mail.UpsertDraftRequest): MailDraft =
    client.request(
        "/drafts/$draftId",
        MailDraft.serializer(),
        method = "PUT",
        body = mailJson.encodeToJsonElement(cn.appia.im.core.mail.UpsertDraftRequest.serializer(), data),
    )

/** mailApi.ts:270-271 sendMailDraft：POST /drafts/:id/send。 */
fun sendMailDraft(client: MailClient, draftId: String): cn.appia.im.core.mail.SendMailResponse =
    client.request("/drafts/$draftId/send", cn.appia.im.core.mail.SendMailResponse.serializer(), method = "POST")

/** mailApi.ts:273-276 searchMailContacts：GET /contacts/search?q=。 */
fun searchMailContacts(client: MailClient, q: String): List<cn.appia.im.core.mail.ContactSuggestion> =
    client.request(
        "/contacts/search",
        ListSerializer(cn.appia.im.core.mail.ContactSuggestion.serializer()),
        params = mapOf("q" to q),
    )

// ---- 角标/预览域（mailApi.ts:278-339 / T6）----

/** mailApi.ts:278-289 buildLatestMailPreview：fromName 花名解析优先，回退 message.fromName。 */
fun buildLatestMailPreview(
    message: cn.appia.im.core.mail.MailMessage,
    resolveFromName: ((email: String) -> String?)? = null,
): cn.appia.im.core.mail.LatestMailPreview {
    val internalName = resolveFromName?.invoke(message.from)?.trim()
    return cn.appia.im.core.mail.LatestMailPreview(
        subject = message.subject,
        fromName = internalName?.takeIf { it.isNotEmpty() } ?: message.fromName,
        from = message.from,
        receivedAt = message.receivedAt,
        unread = !message.read,
    )
}

/**
 * mailApi.ts:291-312 fetchLatestMailPreview：首账户 INBOX pageSize=1 → 最新一条 →
 * receivedAt 降序取首（RN [...messages].sort 同义）。全程容错 → null（角标链静默）。
 */
suspend fun fetchLatestMailPreview(
    client: MailClient,
    resolveFromName: ((email: String) -> String?)? = null,
): cn.appia.im.core.mail.LatestMailPreview? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    runCatching {
        val account = listMailAccounts(client).firstOrNull() ?: return@runCatching null
        val result = listMailMessages(
            client,
            account.id,
            cn.appia.im.core.mail.ListMessagesParams(folder = MAIL_DEFAULT_FOLDER, page = 1, pageSize = 1),
        )
        val newest = result.messages.sortedByDescending { cn.appia.im.feature.mail.MailChannelViewModel.mailIsoMillis(it.receivedAt) }
            .firstOrNull() ?: return@runCatching null
        buildLatestMailPreview(newest, resolveFromName)
    }.getOrNull()
}

/**
 * mailApi.ts:314-339 fetchMailUnreadCount（坑 8 天花板照抄）：只扫 INBOX 首页 100 条，
 * receivedAt > since 计数——积压 >100 少算（RN 同构如实移植）。since<=0 → 0；全程容错 → 0。
 */
suspend fun fetchMailUnreadCount(
    client: MailClient,
    since: Long,
    accountId: String? = null,
): Int = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    if (since <= 0) return@withContext 0
    runCatching {
        val id = accountId ?: listMailAccounts(client).firstOrNull()?.id ?: return@runCatching 0
        val result = listMailMessages(
            client,
            id,
            cn.appia.im.core.mail.ListMessagesParams(folder = MAIL_DEFAULT_FOLDER, page = 1, pageSize = 100),
        )
        var count = 0
        for (message in result.messages) {
            val receivedAt = cn.appia.im.feature.mail.MailChannelViewModel.mailIsoMillis(message.receivedAt)
            if (receivedAt > since) count += 1
        }
        count
    }.getOrDefault(0)
}
