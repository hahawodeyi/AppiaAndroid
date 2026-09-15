package cn.appia.im.feature.chatlist

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private const val MINUTE = 60_000L
private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR

/** RN formatRoomListTime.ts 五档的「i18n key + 参数」形态（文案在 strings.xml，代码零硬编码中文）。 */
data class RoomListTimeKey(val key: String, val args: Map<String, String> = emptyMap())

/**
 * 五档档位（RN formatRoomListTime.ts:6-37 同序同边界）：刚刚 / N 分钟前 / N 小时前 / N 天前 /
 * 超 7 天「M月d日」。`diff < X` 严格小于：恰好等于单位时长落下一档；`ts==0` 返回 null（RN `!ms`）。
 *
 * 日期档双语沿用 RN formatMeetingDisplayTime.ts:36-47 策略：
 * zh 用 meeting_dateMdZh（{{month}}月{{day}}日），其余语言用 meeting_dateMdEn（{{month}} {{day}}，
 * month 为 en-US 短月名）——与 RN toLocaleDateString(zh-CN/en-US, {month:'short',day:'numeric'}) 输出一致。
 *
 * now/locale 注入便于测试（RN 用 Date.now()/i18n.language）；渲染侧经 [formatRoomListTime] 注入 t()。
 */
internal fun roomListTimeKey(tsMillis: Long, nowMillis: Long, locale: Locale): RoomListTimeKey? {
    if (tsMillis == 0L) return null
    val diff = nowMillis - tsMillis
    return when {
        diff < MINUTE -> RoomListTimeKey("roomItem_timeJustNow")
        diff < HOUR -> RoomListTimeKey("roomItem_timeMinutesAgo", mapOf("count" to (diff / MINUTE).toString()))
        diff < DAY -> RoomListTimeKey("roomItem_timeHoursAgo", mapOf("count" to (diff / HOUR).toString()))
        diff < 7 * DAY -> RoomListTimeKey("roomItem_timeDaysAgo", mapOf("count" to (diff / DAY).toString()))
        else -> {
            val cal = Calendar.getInstance().apply { timeInMillis = tsMillis }
            val day = cal.get(Calendar.DAY_OF_MONTH).toString()
            if (locale.language.startsWith("zh")) {
                RoomListTimeKey("meeting_dateMdZh", mapOf("month" to (cal.get(Calendar.MONTH) + 1).toString(), "day" to day))
            } else {
                RoomListTimeKey(
                    "meeting_dateMdEn",
                    mapOf("month" to SimpleDateFormat("MMM", Locale.US).format(tsMillis), "day" to day),
                )
            }
        }
    }
}

/**
 * 列表右侧时间字符串：[roomListTimeKey] 选档 + resolve 注入渲染
 * （UI 侧用 `context.t(key)` + `{{x}}` 替换，文案与 RN roomItem_time 系列与 meeting_dateM 系列同源）。
 */
fun formatRoomListTime(
    tsMillis: Long,
    nowMillis: Long = System.currentTimeMillis(),
    locale: Locale = Locale.getDefault(),
    resolve: (key: String, args: Map<String, String>) -> String,
): String = roomListTimeKey(tsMillis, nowMillis, locale)?.let { resolve(it.key, it.args) }.orEmpty()
