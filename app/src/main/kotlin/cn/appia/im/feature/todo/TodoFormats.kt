package cn.appia.im.feature.todo

import cn.appia.im.core.datastore.KvStore
import cn.appia.im.core.network.api.TodoAttachment
import cn.appia.im.domain.chat.ChatMerger
import cn.appia.im.feature.chat.ui.ParsedAttachment

/**
 * 待办纯格式化/判定（RN lib/todo + utils 逐行）：
 * - [stripMarkdownLite]：globalSearchFormat.ts:4-5 同正则（`**`/`__`/`~~`/`` ` `` 剥除 + 换行折空格）；
 * - [formatUtcYmdHm]：formatUTCYMDHM.ts——本地时区 `YYYY<sep>MM<sep>DD HH:mm (UTC±n)`，坏 ISO 原样返回；
 * - [coerceRoomType]：coerceSubscriptionType——已知 t 值白名单，未知回退 'c'（TodoList 跳转）；
 * - [todoRoomDisplayName]：getTodoRoomDisplayName——myAgents 自聊房（MMKV 缓存 rid 命中）显示
 *   agentLabel（t('Agent')），否则 item.name。
 */

/** RN stripMarkdownLite（globalSearchFormat.ts:4-5 逐字符）。 */
fun stripMarkdownLite(s: String): String =
    s.replace(Regex("\\*\\*|__|~~|`"), "").replace("\n", " ")

/**
 * RN formatUTCYMDHM（utils/formatUTCYMDHM.ts）：本地时区 `YYYY<sep>MM<sep>DD HH:mm (UTC±n)`，
 * 坏 ISO 原样返回。minSdk 24 无 java.time，走 Calendar（TimeFormats.kt:17 同裁定）；
 * 偏移取整点/半点双形态（JS 数字 toString：8 → "8"、5.5 → "5.5"）。
 */
fun formatUtcYmdHm(
    iso: String,
    sep: String,
    zone: java.util.TimeZone = java.util.TimeZone.getDefault(),
): String {
    val ms = ChatMerger.parseIsoMillis(iso) ?: return iso
    val cal = java.util.Calendar.getInstance(zone).apply { timeInMillis = ms.toLong() }
    // java offset 东经正 = RN getTimezoneOffset 取负后的口径，直接除（TimeFormats.kt:29 同注）
    val tzHours = zone.getOffset(ms.toLong()) / 3_600_000.0
    val tzText = if (tzHours % 1.0 == 0.0) tzHours.toLong().toString() else tzHours.toString()
    val tzString = if (tzHours >= 0) "(UTC+$tzText)" else "(UTC$tzText)"
    val pad = { n: Int -> n.toString().padStart(2, '0') }
    return "${cal.get(java.util.Calendar.YEAR)}${sep}" +
        "${pad(cal.get(java.util.Calendar.MONTH) + 1)}${sep}${pad(cal.get(java.util.Calendar.DAY_OF_MONTH))} " +
        "${pad(cal.get(java.util.Calendar.HOUR_OF_DAY))}:${pad(cal.get(java.util.Calendar.MINUTE))} $tzString"
}

/** RN date.toISOString()：恒 UTC `yyyy-MM-ddTHH:mm:ss.SSSZ`（提醒时间 POST wire 形态）。 */
fun todoReminderIso(millis: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }.format(java.util.Date(millis))

private val KNOWN_ROOM_TYPES = setOf("p", "d", "c", "l", "e2e", "thread", "b")

/** RN coerceSubscriptionType（types/subscriptionType.ts:31-33）：未知 t 回退 fallback。 */
fun coerceRoomType(raw: String?, fallback: String = "c"): String =
    if (raw != null && raw in KNOWN_ROOM_TYPES) raw else fallback

/**
 * RN getMyLocalAgentRid（lib/chat/getMyLocalAgentRid.ts:21-29）：只读 MMKV 键
 * `AGEMNT_ROOM_ID_KEY_<server 去尾斜杠><username>`，不建房。username/server 任一空 → null。
 */
fun getMyLocalAgentRid(username: String?, serverUrl: String?, kv: KvStore): String? {
    if (username.isNullOrEmpty() || serverUrl.isNullOrEmpty()) return null
    val key = "AGEMNT_ROOM_ID_KEY_${serverUrl.trimEnd('/')}$username"
    return kv.getString(key, "").takeIf { it.isNotEmpty() }
}

/**
 * RN getTodoRoomDisplayName（lib/todo/getTodoRoomDisplayName.ts）：myAgents 自聊房显示
 * agentLabel（t('Agent')），否则 name（可空——Compose 侧空串兜底）。
 */
fun todoRoomDisplayName(
    rid: String?,
    name: String?,
    username: String,
    serverUrl: String,
    agentLabel: String,
    kv: KvStore,
): String {
    if (!rid.isNullOrEmpty() && rid == getMyLocalAgentRid(username, serverUrl, kv)) return agentLabel
    return name.orEmpty()
}

/**
 * RN isTodoImageAttachment（lib/todo/mapTodoAttachmentToIAttachment + mediaPreview.ts:20-21）：
 * 映射后有 image_url 即图（IAttachment 映射不含 image_type，等价判定为 image_url 非空）。
 */
fun todoAttachmentImage(att: TodoAttachment): ParsedAttachment? =
    if (att.imageUrl.isNullOrEmpty()) null else ParsedAttachment(
        title = att.title,
        type = att.type,
        titleLink = att.titleLink,
        imageUrl = att.imageUrl,
    )

/** 会话列表头像/房间头待办角标文本（RoomItemTodoBadge/index.tsx:20-22）：>99 显 `99+`。 */
fun todoBadgeText(todoCount: Int): String? =
    when {
        todoCount <= 0 -> null
        todoCount > 99 -> "99+"
        else -> todoCount.toString()
    }
