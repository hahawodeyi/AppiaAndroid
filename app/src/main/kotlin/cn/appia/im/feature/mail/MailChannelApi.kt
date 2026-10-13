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
