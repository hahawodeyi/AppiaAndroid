package cn.appia.im.core.util

import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 消息时间格式（对照 appiaMobile/src/lib/chat/messageUserDisplay.ts 的
 * formatRoomMessageHeaderTime + forwardMergeMessage.ts 的同日/日期标签）：
 * - 消息头时间：同年 `MM/DD HH:mm`、跨年 `YYYY/MM/DD HH:mm`，后缀 ` (UTC±x)`（本地时区偏移，
 *   半小时偏移按 RN 数字直转渲染 `+5.5`）；
 * - 同日判定：本地日历日相同（RN toDateString 对比）；
 * - 日期分隔标签：RN 固定 `Intl.DateTimeFormat('zh-CN', long)` → `yyyy"年"M"月"d"日"`。
 * zone 缺省系统时区；测试显式注入。minSdk 24 无 java.time，走 Calendar/SimpleDateFormat
 * （ChatMerger.kt:149 同裁定）。
 */
fun formatRoomMessageHeaderTime(
    tsMs: Long,
    nowMs: Long = System.currentTimeMillis(),
    zone: TimeZone = TimeZone.getDefault(),
): String {
    val given = Calendar.getInstance(zone).apply { timeInMillis = tsMs }
    val now = Calendar.getInstance(zone).apply { timeInMillis = nowMs }
    // RN getTimezoneOffset 为西经正（UTC+8 → -480，再取负）；Java offset 为东经正，直接除即可
    val tzHours = zone.getOffset(nowMs) / 3_600_000.0
    val tzString = if (tzHours >= 0) "(UTC+${trimDecimal(tzHours)})" else "(UTC${trimDecimal(tzHours)})"

    val clock = "%02d:%02d".format(given.get(Calendar.HOUR_OF_DAY), given.get(Calendar.MINUTE))
    val formatted = if (now.get(Calendar.YEAR) == given.get(Calendar.YEAR)) {
        "%02d/%02d %s".format(given.get(Calendar.MONTH) + 1, given.get(Calendar.DAY_OF_MONTH), clock)
    } else {
        "%04d/%02d/%02d %s".format(
            given.get(Calendar.YEAR),
            given.get(Calendar.MONTH) + 1,
            given.get(Calendar.DAY_OF_MONTH),
            clock,
        )
    }
    return "$formatted $tzString"
}

private fun trimDecimal(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

/** RN isSameCalendarDay：两时刻在同一本地日历日。 */
fun isSameCalendarDay(aMs: Long, bMs: Long, zone: TimeZone = TimeZone.getDefault()): Boolean {
    val a = Calendar.getInstance(zone).apply { timeInMillis = aMs }
    val b = Calendar.getInstance(zone).apply { timeInMillis = bMs }
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}

/** RN formatForwardMessageDateLabel（固定 zh-CN long）：日期分隔条文案。每次 new（DateFormat 非线程安全）。 */
fun formatMessageDateLabel(tsMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
    val df = DateFormat.getDateInstance(DateFormat.LONG, Locale.CHINESE)
    df.timeZone = zone
    return df.format(Date(tsMs))
}
