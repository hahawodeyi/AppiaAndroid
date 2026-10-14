package cn.appia.im.core.voice.lib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * parseCallMsg（RN lib/voice/parseCallMsg.test.ts 全用例转写）。
 */
class ParseCallMsgTest {

    @Test
    fun `null and blank return null`() {
        // RN 'returns null for empty input'：undefined/'' → null
        assertNull(parseCallMsg(null))
        assertNull(parseCallMsg(""))
        // RN 'returns null for invalid JSON'：'{bad' → null
        assertNull(parseCallMsg("{bad"))
        assertNull(parseCallMsg("not-json"))
    }

    @Test
    fun `parses flat callMsg json`() {
        // RN 'parses flat callMsg JSON'
        val raw = """{"status":"calling","roomId":"r1","org":"bitmain"}"""
        val result = parseCallMsg(raw)!!
        assertEquals("calling", result.status)
        assertEquals("r1", result.roomId)
    }

    @Test
    fun `recordData string is double-decoded`() {
        // RN 'parses nested recordData string'
        val raw = """{"status":"talking","recordData":"{\"recordId\":\"rec-1\",\"channelId\":\"ch-1\"}"}"""
        val result = parseCallMsg(raw)!!
        assertEquals("rec-1", result.recordDataObj?.recordId)
        assertEquals("ch-1", result.recordDataObj?.channelId)
    }

    @Test
    fun `recordData object passes through`() {
        val raw = """{"status":"talking","recordData":{"recordId":"rec-1","channelId":"ch-1"}}"""
        assertEquals("rec-1", parseCallMsg(raw)?.recordDataObj?.recordId)
    }

    @Test
    fun `u string decodes and backfills initiatorName`() {
        // RN 'parses nested u string and sets initiatorName on recordData'
        val raw = """{"status":"calling","u":"{\"name\":\"Alice\",\"username\":\"alice\"}","recordData":{"recordId":"rec-2"}}"""
        val result = parseCallMsg(raw)!!
        assertEquals("Alice", result.uObj?.name)
        assertEquals("rec-2", result.recordDataObj?.recordId)
        // parseCallMsg.ts:30-33：u.name 回填 recordData.initiatorName
        assertEquals("Alice", result.recordDataObj?.initiatorName)
    }

    @Test
    fun `recordData string decode failure returns null`() {
        // RN 'returns null when recordData string is invalid JSON'
        val raw = """{"recordData":"{invalid"}"""
        assertNull(parseCallMsg(raw))
    }

    @Test
    fun `u string decode failure is tolerated`() {
        // parseCallMsg.ts:34-36：legacy 容忍 u 解析失败
        val raw = """{"status":"calling","receivers":[],"u":"not-json"}"""
        val parsed = parseCallMsg(raw)!!
        assertEquals("calling", parsed.status)
        assertNull(parsed.uObj)
    }
}
