package cn.appia.im.core.database

import androidx.room.withTransaction

/**
 * 已读态双表写单点（RN readMessages.ts 的 Kotlin 落法；T4 列表滑动与 T10 房间内标读共用）。
 *
 * 字段组对照 applyLocalReadStateToRow（readMessages.ts:20-34）：
 * `open=true, alert=false, unread=0, userMentions=0, groupMentions=0, ls=now`，
 * `updateLastOpen=true` 时另写 `lastOpen=now`（T10 进房即读用）。
 *
 * 双表写的原因（RN :61）：房间列表读 `chats` 表，只更 subscription 会被 DDP 增量把状态写回假未读。
 * 行不存在静默跳过（RN markSubscriptionReadLocally/markChatReadLocally 的 catch 同义；
 * 现网 subscriptions 行主键是 sub 文档 id，与 rid 不同时自然走跳过分支）。
 * 写序：先 subscriptions 后 chats（RN :92-93）；RN 两次独立 db.write，此处并为单事务（原子性加固）。
 */
class ReadStateWriter(private val db: AppiaDatabase) {

    suspend fun applyReadState(rid: String, now: Long, updateLastOpen: Boolean = false) {
        db.withTransaction {
            db.subscriptionDao().getById(rid)?.let { sub ->
                db.subscriptionDao().update(
                    sub.copy(
                        open = true,
                        alert = false,
                        unread = 0.0,
                        user_mentions = 0.0,
                        group_mentions = 0.0,
                        ls = now.toDouble(),
                        last_open = if (updateLastOpen) now.toDouble() else sub.last_open,
                    ),
                )
            }
            db.chatDao().getById(rid)?.let { chat ->
                db.chatDao().update(
                    chat.copy(
                        open = true,
                        alert = false,
                        unread = 0.0,
                        user_mentions = 0.0,
                        group_mentions = 0.0,
                        ls = now.toDouble(),
                        last_open = if (updateLastOpen) now.toDouble() else chat.last_open,
                    ),
                )
            }
        }
    }
}
