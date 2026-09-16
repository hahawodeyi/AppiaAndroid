package cn.appia.im.core.util

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 消息时间格式（对照 appiaMobile/src/lib/chat/messageUserDisplay.ts 的
 * formatRoomMessageHeaderTime + forwardMergeMessage.ts 的同日/日期标签）：
 * - 消息头时间：同年 `MM/DD HH:mm`、跨年 `YYYY/MM/DD HH:mm`，后缀 ` (UTC±x)`（本地时区偏移，
 *   半小时偏移按 RN 数字直转渲染 `+5.5`）；
 * - 同日判定：本地日历日相同（RN toDateString 对比）；
 * - 日期分隔标签：RN 固定 `Intl.DateTimeFormat('zh-CN', long)` → `yyyy年M月d日`。
 * zone 缺省系统时区；测试显式注入。
 */
fun formatRoomMessageHeaderTime(
    tsMs: Long,
    nowMs: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val given = ZonedDateTime.ofInstant(Instant.ofEpochMilli(tsMs), zone)
    val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)

    // RN getTimezoneOffset 为西经正（UTC+8 → -480，再取负）；Java offset 为东经正，直接除即可
    val tzHours = now.offset.totalSeconds / 3600.0
    val tzString = if (tzHours >= 0) "(UTC+${trimDecimal(tzHours)})" else "(UTC${trimDecimal(tzHours)})"

    val clock = "%02d:%02d".format(given.hour, given.minute)
    val formatted = if (now.year == given.year) {
        "%02d/%02d %s".format(given.monthValue, given.dayOfMonth, clock)
    } else {
        "%04d/%02d/%02d %s".format(given.year, given.monthValue, given.dayOfMonth, clock)
    }
    return "$formatted $tzString"
}

private fun trimDecimal(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

/** RN isSameCalendarDay：两时刻在同一本地日历日。 */
fun isSameCalendarDay(aMs: Long, bMs: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
    val a = ZonedDateTime.ofInstant(Instant.ofEpochMilli(aMs), zone)
    val b = ZonedDateTime.ofInstant(Instant.ofEpochMilli(bMs), zone)
    return a.year == b.year && a.dayOfYear == b.dayOfYear
}

/** RN formatForwardMessageDateLabel（固定 zh-CN long：`yyyy"年"M"月"d"日"`）：日期分隔条文案。 */
fun formatMessageDateLabel(tsMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val d = java.time.LocalDateTime.ofInstant(Instant.ofEpochMilli(tsMs), zone)
    return java.time.format.DateTimeFormatter
        .ofLocalizedDate(java.time.format.FormatStyle.LONG)
        .withLocale(java.util.Locale.CHINESE)
        .format(d)
}
