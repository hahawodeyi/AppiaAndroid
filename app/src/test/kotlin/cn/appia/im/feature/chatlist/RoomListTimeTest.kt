package cn.appia.im.feature.chatlist

import java.util.Calendar
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * RN `formatRoomListTime.ts` 五档档位 × zh/en：刚刚 / N 分钟前 / N 小时前 / N 天前 / 超 7 天「M月d日」。
 * 档位为 `diff < X` 严格小于语义；now/locale 注入（RN 用 Date.now()/i18n.language）。
 * 文案本体在 strings.xml（roomItem_time 系列与 meeting_dateM 系列，M0 已对照 RN json），此处断言 key/参数选择。
 */
class RoomListTimeTest {

    private val zh = Locale.SIMPLIFIED_CHINESE
    private val en = Locale.US
    private val now = localMillis(2026, 9, 15)

    private fun localMillis(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, 0, 0)
        }.timeInMillis

    private val minute = 60_000L
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun key(ts: Long, locale: Locale = zh): RoomListTimeKey? = roomListTimeKey(ts, now, locale)

    @Test
    fun `zero ts selects nothing like RN bang-ms`() {
        assertNull(key(0))
    }

    @Test
    fun `under a minute is justNow`() {
        assertEquals("roomItem_timeJustNow", key(now - 30_000)!!.key)
        assertEquals(emptyMap<String, String>(), key(now - 30_000, en)!!.args)
    }

    @Test
    fun `under an hour is minutes ago`() {
        assertEquals(RoomListTimeKey("roomItem_timeMinutesAgo", mapOf("count" to "1")), key(now - 90_000))
        assertEquals("59", key(now - 59 * minute, en)!!.args["count"])
    }

    @Test
    fun `under a day is hours ago`() {
        assertEquals(RoomListTimeKey("roomItem_timeHoursAgo", mapOf("count" to "2")), key(now - 2 * hour))
    }

    @Test
    fun `under a week is days ago`() {
        assertEquals(RoomListTimeKey("roomItem_timeDaysAgo", mapOf("count" to "3")), key(now - 3 * day))
    }

    @Test
    fun `over a week picks zh month-day key with numeric month and day`() {
        val k = key(localMillis(2026, 9, 7))!!
        assertEquals("meeting_dateMdZh", k.key)
        assertEquals("9", k.args["month"])
        assertEquals("7", k.args["day"])
    }

    @Test
    fun `over a week picks en key with US short month`() {
        val k = key(localMillis(2026, 9, 7), en)!!
        assertEquals("meeting_dateMdEn", k.key)
        assertEquals("Sep", k.args["month"])
        assertEquals("7", k.args["day"])
    }

    @Test
    fun `boundaries fall to the next tier at exact unit`() {
        // RN 全部为 diff < X 严格判断：恰好等于单位时长落下一档；恰好 7 天落日期档
        assertEquals("roomItem_timeMinutesAgo", key(now - minute)!!.key)
        assertEquals("roomItem_timeHoursAgo", key(now - hour)!!.key)
        assertEquals("roomItem_timeDaysAgo", key(now - day)!!.key)
        assertEquals("meeting_dateMdZh", key(localMillis(2026, 9, 8))!!.key)
    }

    @Test
    fun `formatRoomListTime resolves via injected resolver and empty on zero`() {
        assertEquals("roomItem_timeJustNow:[]", formatRoomListTime(now - 30_000, now, zh) { k, a ->
            "$k:${a.values}"
        })
        assertEquals("", formatRoomListTime(0, now, zh) { k, _ -> k })
    }

    @Test
    fun `all tier keys exist in migrated i18n keys`() {
        val used = listOf("roomItem_timeJustNow", "roomItem_timeMinutesAgo", "roomItem_timeHoursAgo", "roomItem_timeDaysAgo", "meeting_dateMdZh", "meeting_dateMdEn")
        used.forEach { key ->
            assertTrue(
                cn.appia.im.core.i18n.I18nKeys.ALL.any { it.lowercase() == key.lowercase() },
                "missing i18n key: $key",
            )
        }
    }
}
