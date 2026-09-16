package cn.appia.im.feature.chat

import cn.appia.im.core.database.AppiaDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 草稿读写（对照 appiaMobile/src/hooks/useDraft.ts :78-102）：
 * - [saveDraft]：`draft_message` 与 `draft_message_plain` 同值（M2 纯文本，无 TipTap JSON；
 *   RN 语义 plainText 为空时 draft_message 落 ''，此处同值直写等价）；`draft_reply_msg_id`
 *   **不写**留 M3（RN 该列写 replyMsgId ?? ''，M2 无回复上下文）；
 * - [clearDraft]：四列清空（:87-102）；
 * - chat 行不存在静默跳过（RN find 失败 catch）。
 */
class DraftRepository(private val db: AppiaDatabase) {

    /** 恢复输入框内容：plain 优先，回退 draft_message（RN initialContent 源）。 */
    suspend fun loadDraft(rid: String): String {
        val chat = db.chatDao().getById(rid) ?: return ""
        return chat.draft_message_plain?.takeIf { it.isNotEmpty() } ?: chat.draft_message.orEmpty()
    }

    suspend fun saveDraft(rid: String, plainText: String) {
        val chat = db.chatDao().getById(rid) ?: return
        db.chatDao().update(chat.copy(draft_message = plainText, draft_message_plain = plainText))
    }

    suspend fun clearDraft(rid: String) {
        val chat = db.chatDao().getById(rid) ?: return
        db.chatDao().update(
            chat.copy(
                draft_message = "",
                draft_message_plain = "",
                draft_reply_msg_id = "",
                draft_attachments = "",
            ),
        )
    }
}

/**
 * 草稿保存时机（RN useDraft hook 的 :185-233/:264-285 时序等价）：
 * - [onTextChanged]：IME **commit 后**才调（组合态在 UI 层拦截），重置 1s debounce；
 * - [onBlur]：失焦立即写（RN saveDraftImmediate）；
 * - [flushOnDispose]：卸载时仅在有未落库 pending 时补写（RN unmount flush）；
 * - [clearAfterSend]：发送成功后取消 pending 并清四列（RN clearDraft）。
 * scope 注入虚拟时钟 dispatcher 即可测 debounce 时序。
 */
class DraftController(
    private val repo: DraftRepository,
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEBOUNCE_MS,
) {

    private var pending: Job? = null
    private var latestText = ""

    fun onTextChanged(rid: String, text: String) {
        latestText = text
        pending?.cancel()
        pending = scope.launch {
            delay(debounceMs)
            repo.saveDraft(rid, latestText)
        }
    }

    fun onBlur(rid: String, text: String) {
        latestText = text
        pending?.cancel()
        pending = null
        scope.launch { repo.saveDraft(rid, text) }
    }

    fun flushOnDispose(rid: String) {
        val hadPending = pending?.isActive == true
        pending?.cancel()
        pending = null
        if (hadPending) scope.launch { repo.saveDraft(rid, latestText) }
    }

    fun clearAfterSend(rid: String) {
        pending?.cancel()
        pending = null
        latestText = ""
        scope.launch { repo.clearDraft(rid) }
    }

    suspend fun loadDraft(rid: String): String = repo.loadDraft(rid)

    companion object {
        /** RN useDraft.ts DEBOUNCE_MS。 */
        const val DEBOUNCE_MS = 1_000L
    }
}
