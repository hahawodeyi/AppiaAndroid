package cn.appia.im.feature.chat

import cn.appia.im.core.database.AppiaDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 草稿读写（对照 appiaMobile/src/hooks/useDraft.ts :78-102；T12 起 TipTap JSON 口径）：
 * - [saveDraft]：`draft_message` 存 TipTap JSON（编辑器回填源），`draft_message_plain` 存纯文本；
 *   `draft_reply_msg_id` **不写**（回复态未持久化，RN 该列写 replyMsgId ?? ''——M3-T12 现状）；
 * - [clearDraft]：四列清空（:87-102）；
 * - chat 行不存在静默跳过（RN find 失败 catch）。
 */
class DraftRepository(private val db: AppiaDatabase) {

    /** 恢复编辑器内容：draft_message（TipTap JSON 串）；空/缺失返回 null。 */
    suspend fun loadDraftJson(rid: String): String? {
        val chat = db.chatDao().getById(rid) ?: return null
        return chat.draft_message?.takeIf { it.isNotEmpty() }
    }

    suspend fun saveDraft(rid: String, json: String, plain: String) {
        val chat = db.chatDao().getById(rid) ?: return
        db.chatDao().update(chat.copy(draft_message = json, draft_message_plain = plain))
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
 * - [onTextChanged]：编辑器内容落定（content-update 拉取）后调，重置 1s debounce
 *   （M2 的 IME 组合态守卫随 TextField 移除——WebView 内组合态对 native 不可见，debounce 吸收）；
 * - [onBlur]：失焦立即写（RN saveDraftImmediate）；WebView 路径 blur 前先 fetchContentNow；
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
    private var latestJson = ""
    private var latestPlain = ""

    fun onTextChanged(rid: String, json: String, plain: String) {
        latestJson = json
        latestPlain = plain
        pending?.cancel()
        pending = scope.launch {
            delay(debounceMs)
            repo.saveDraft(rid, latestJson, latestPlain)
        }
    }

    fun onBlur(rid: String, json: String, plain: String) {
        latestJson = json
        latestPlain = plain
        pending?.cancel()
        pending = null
        scope.launch { repo.saveDraft(rid, json, plain) }
    }

    fun flushOnDispose(rid: String) {
        val hadPending = pending?.isActive == true
        pending?.cancel()
        pending = null
        if (hadPending) scope.launch { repo.saveDraft(rid, latestJson, latestPlain) }
    }

    fun clearAfterSend(rid: String) {
        pending?.cancel()
        pending = null
        latestJson = ""
        latestPlain = ""
        scope.launch { repo.clearDraft(rid) }
    }

    suspend fun loadDraftJson(rid: String): String? = repo.loadDraftJson(rid)

    companion object {
        /** RN useDraft.ts DEBOUNCE_MS。 */
        const val DEBOUNCE_MS = 1_000L
    }
}
