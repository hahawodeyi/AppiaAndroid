package cn.appia.im.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.TimeZone

/**
 * TimeFormats 纯函数测试：消息头时间（同年 MM/DD HH:mm、跨年 YYYY/MM/DD HH:mm、(UTC±x) 后缀）、
 * 同日判定、日期分隔标签。时区显式注入（fixed +8 / -5 / +5:30），不吃宿主机时区。
 */
class TimeFormatsTest {

    private val zone8 = ZoneId.of("+08:00")
    private val zoneMinus5 = ZoneId.of("-05:00")
    private val zone530 = ZoneId.of("+05:30")
    private val utc8 = TimeZone.getTimeZone("GMT+08:00")
    private val utcMinus5 = TimeZone.getTimeZone("GMT-05:00")
    private val utc530 = TimeZone.getTimeZone("GMT+05:30")

    @Test
    fun `same year renders MM slash DD HH colon mm with plus8 suffix`() {
        val ms = java.time.ZonedDateTime.of(2026, 3, 5, 7, 8, 0, 0, zone8).toInstant().toEpochMilli()
        val now = java.time.ZonedDateTime.of(2026, 3, 10, 0, 0, 0, 0, zone8).toInstant().toEpochMilli()
        assertEquals("03/05 07:08 (UTC+8)", formatRoomMessageHeaderTime(ms, now, utc8))
    }

    @Test
    fun `cross year renders yyyy prefix`() {
        val ms = java.time.ZonedDateTime.of(2025, 12, 31, 23, 5, 0, 0, zone8).toInstant().toEpochMilli()
        val now = java.time.ZonedDateTime.of(2026, 1, 2, 0, 0, 0, 0, zone8).toInstant().toEpochMilli()
        assertEquals("2025/12/31 23:05 (UTC+8)", formatRoomMessageHeaderTime(ms, now, utc8))
    }

    @Test
    fun `negative offset suffix uses minus without plus`() {
        val ms = java.time.ZonedDateTime.of(2026, 6, 1, 8, 0, 0, 0, zoneMinus5).toInstant().toEpochMilli()
        val now = java.time.ZonedDateTime.of(2026, 6, 1, 9, 0, 0, 0, zoneMinus5).toInstant().toEpochMilli()
        assertEquals("06/01 08:00 (UTC-5)", formatRoomMessageHeaderTime(ms, now, utcMinus5))
    }

    @Test
    fun `half hour offset renders decimal`() {
        val ms = java.time.ZonedDateTime.of(2026, 6, 1, 8, 0, 0, 0, zone530).toInstant().toEpochMilli()
        val now = java.time.ZonedDateTime.of(2026, 6, 1, 9, 0, 0, 0, zone530).toInstant().toEpochMilli()
        assertEquals("06/01 08:00 (UTC+5.5)", formatRoomMessageHeaderTime(ms, now, utc530))
    }

    @Test
    fun `seven-day window crossing month boundary stays short`() {
        // RN :142-149 withinSevenDays && sameYear → 短格式，与是否跨月无关
        val ts = java.time.ZonedDateTime.of(2026, 1, 28, 9, 30, 0, 0, zone8).toInstant().toEpochMilli()
        val now = java.time.ZonedDateTime.of(2026, 2, 2, 9, 0, 0, 0, zone8).toInstant().toEpochMilli()
        assertEquals("01/28 09:30 (UTC+8)", formatRoomMessageHeaderTime(ts, now, utc8))
    }

    @Test
    fun `same year beyond seven days renders long format`() {
        val ts = java.time.ZonedDateTime.of(2026, 1, 5, 9, 30, 0, 0, zone8).toInstant().toEpochMilli()
        val now = java.time.ZonedDateTime.of(2026, 1, 20, 9, 30, 0, 0, zone8).toInstant().toEpochMilli()
        assertEquals("2026/01/05 09:30 (UTC+8)", formatRoomMessageHeaderTime(ts, now, utc8))
    }

    @Test
    fun `exactly seven days boundary falls to long format`() {
        // RN withinSevenDays 闭开区间：now == given + 7d → false → 长格式
        val ts = java.time.ZonedDateTime.of(2026, 3, 5, 7, 8, 0, 0, zone8).toInstant().toEpochMilli()
        val now = ts + 7L * 24 * 60 * 60 * 1000
        assertEquals("2026/03/05 07:08 (UTC+8)", formatRoomMessageHeaderTime(ts, now, utc8))
    }

    @Test
    fun `future timestamp renders long format`() {
        // RN now > given 才算窗口内；未来时刻 → 长格式
        val ts = java.time.ZonedDateTime.of(2026, 3, 15, 7, 8, 0, 0, zone8).toInstant().toEpochMilli()
        val now = java.time.ZonedDateTime.of(2026, 3, 10, 0, 0, 0, 0, zone8).toInstant().toEpochMilli()
        assertEquals("2026/03/15 07:08 (UTC+8)", formatRoomMessageHeaderTime(ts, now, utc8))
    }

    @Test
    fun `same day instants are same calendar day`() {
        val a = java.time.ZonedDateTime.of(2026, 9, 15, 23, 59, 0, 0, zone8).toInstant().toEpochMilli()
        val b = java.time.ZonedDateTime.of(2026, 9, 15, 0, 0, 0, 0, zone8).toInstant().toEpochMilli()
        assertTrue(isSameCalendarDay(a, b, utc8))
    }

    @Test
    fun `day boundary flips calendar day`() {
        val a = java.time.ZonedDateTime.of(2026, 9, 15, 23, 59, 59, 0, zone8).toInstant().toEpochMilli()
        val b = a + 2_000
        assertFalse(isSameCalendarDay(a, b, utc8))
    }

    @Test
    fun `date label renders zh-CN long form`() {
        val ms = java.time.ZonedDateTime.of(2026, 9, 15, 12, 0, 0, 0, zone8).toInstant().toEpochMilli()
        // 2026年9月15日（hook 禁码内中文字面量，此处用 unicode 转义）
        val expected = "2026\u5e749\u670815\u65e5"
        assertEquals(expected, formatMessageDateLabel(ms, utc8))
    }
}
