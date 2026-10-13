package cn.appia.im.feature.mail

import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.mail.LatestMailPreview
import cn.appia.im.feature.chatlist.ChatListSection
import cn.appia.im.feature.chatlist.ChatListSectionKey
import cn.appia.im.feature.chatlist.sortChatsForRoomList
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 会话列表虚拟邮件行（RN useMailChannelListRow + buildMailChannelChatRow 移植）：
 * `Appia_Mail_Channel_Enabled` 门控开时注入 assistant 段固定「邮件」行，点击 → MailChannel。
 *
 * pinned/unreadCount/preview 数据源是 MailBadgeStore（M9-T6 MMKV per-org 分桶）——
 * T2 阶段由装配层以默认值接线（未置顶/0 未读/空预览），T6 接真实 store。
 */

/** RN lib/mail/constants.ts MAIL_CHANNEL_ID。 */
const val MAIL_CHANNEL_ID = "__appia_mail_channel__"

/** RN isMailChannelChatRow：占位行判定（isMailChannel 旗标 AA 无列，id 恒等即可——同 Agent 行裁定）。 */
fun isMailChannelRow(chat: ChatEntity): Boolean = chat._id == MAIL_CHANNEL_ID

/** RN lib/mail/constants.ts MAIL_DRAFTS_FOLDER（T3 收件箱用；此处一并声明）。 */
const val MAIL_DRAFTS_FOLDER = "__drafts__"

/** RN MAIL_PAGE_SIZE = 30（T3 分页用；一并声明）。 */
const val MAIL_PAGE_SIZE = 30

/**
 * RN lib/mail/constants.ts MAIL_LIST_ESTIMATED_ITEM_SIZE = 140：升序列表底部锚定
 * 的溢出判定（坑 9）——itemCount × 140 ≤ 视口高时无需滚动锚底。
 */
const val MAIL_LIST_ESTIMATED_ITEM_SIZE = 140

/** MailChannelScreen/index.tsx:60 STICK_TO_BOTTOM_THRESHOLD = 48。 */
const val MAIL_STICK_TO_BOTTOM_THRESHOLD = 48

/** MailChannelScreen/index.tsx:59 DEFAULT_FOLDER = 'INBOX'。 */
const val MAIL_DEFAULT_FOLDER = "INBOX"

/**
 * buildMailChannelChatRow.ts:12-21 formatMailChannelPreviewText：
 * fromName?.trim() || from.trim()；两者皆空 → ''；无主题 → 发件人；否则 `{fromName}：{subject}`（全角冒号）。
 */
fun formatMailChannelPreviewText(preview: LatestMailPreview?): String {
    if (preview == null) return ""
    val fromName = preview.fromName?.trim().orEmpty().ifEmpty { preview.from.trim() }
    val subject = preview.subject.trim()
    if (fromName.isEmpty() && subject.isEmpty()) return ""
    if (subject.isEmpty()) return fromName
    return "$fromName：$subject"
}

/**
 * previewReceivedAtMillis（:23-27）：ISO → millis，解析失败 → 0。
 * minSdk 24 无 java.time，SimpleDateFormat 五形态（ChatMerger.parseIsoMillis 同口径）。
 */
internal fun mailPreviewReceivedAtMillis(preview: LatestMailPreview?): Long {
    val raw = preview?.receivedAt ?: return 0L
    for (pattern in listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd",
    )) {
        try {
            return SimpleDateFormat(pattern, Locale.ROOT)
                .apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
                .parse(raw)!!.time
        } catch (_: ParseException) {
            // 换下一形态
        }
    }
    return 0L
}

/**
 * buildMailChannelChatRow.ts:36-69 字段映射：`f`←pinned、`unread`←unreadCount、
 * `lm`←preview.receivedAt（失败 0）、activity = lm || now、`t`←CHANNEL('c')、
 * `lastMessage`←预览文案。虚拟行不落库不发服务端。
 */
fun buildMailChannelChatRow(
    pinned: Boolean,
    unreadCount: Int,
    preview: LatestMailPreview?,
): ChatEntity {
    val lm = mailPreviewReceivedAtMillis(preview)
    val now = System.currentTimeMillis().toDouble()
    val activity = if (lm > 0) lm.toDouble() else now
    return ChatEntity(
        _id = MAIL_CHANNEL_ID,
        f = pinned,
        t = "c",
        ts = now,
        ls = now,
        name = MAIL_CHANNEL_ID,
        fname = "",
        rid = MAIL_CHANNEL_ID,
        open = true,
        alert = unreadCount > 0,
        unread = maxOf(0, unreadCount).toDouble(),
        user_mentions = 0.0,
        group_mentions = 0.0,
        lm = activity,
        last_message = formatMailChannelPreviewText(preview),
        room_updated_at = activity,
        ro = false,
        archived = false,
        auto_translate_language = "",
        team_id = "",
    )
}

/**
 * useMailChannelListRow.ts:39-66 injectMailChannelIntoSections：并入 assistant 段
 * （先剔除旧行再随段排序）；无 assistant 段则新建置前。mailRow null 时原样返回
 * （门控关 = RN row 为 null 不注入）。
 */
fun injectMailChannelRow(
    sections: List<ChatListSection>,
    mailRow: ChatEntity?,
): List<ChatListSection> {
    if (mailRow == null) return sections
    val assistantIndex = sections.indexOfFirst { it.key == ChatListSectionKey.ASSISTANT }
    if (assistantIndex >= 0) {
        return sections.mapIndexed { index, section ->
            if (index != assistantIndex) {
                section
            } else {
                ChatListSection(
                    section.key,
                    sortChatsForRoomList(section.chats.filterNot(::isMailChannelRow) + mailRow),
                )
            }
        }
    }
    return listOf(ChatListSection(ChatListSectionKey.ASSISTANT, listOf(mailRow))) + sections
}
