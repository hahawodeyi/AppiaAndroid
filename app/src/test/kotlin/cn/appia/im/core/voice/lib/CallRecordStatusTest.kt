package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoiceCallRecordData
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * callRecordStatus（RN callRecordStatus.test.ts 全用例 + brief 补充形态矩阵）。
 */
class CallRecordStatusTest {

    /** RN asVoiceCallMsg 夹具等价：recordData 以对象形态构造（AA 侧 raw=JsonObject，经 recordDataObj 展开）。 */
    private fun msg(status: String, exist: JsonPrimitive? = null): VoiceCallMsg {
        val recordJson = buildJsonObject {
            put("recordId", "rec-1")
            if (exist != null) put("exist", exist)
        }
        return VoiceCallMsg(
            status = status, roomId = "r1", roomType = "d", org = "bitmain", receivers = emptyList(),
            recordData = recordJson,
        )
    }

    @Test
    fun `exist truthy and falsy forms`() {
        // callRecordStatus.ts:7-13 verbatim：boolean/string 混杂形态
        assertTrue(isCallRecordActive(JsonPrimitive(true)))
        assertTrue(isCallRecordActive(JsonPrimitive("true")))
        assertTrue(isCallRecordActive(JsonPrimitive("1")))
        assertFalse(isCallRecordActive(JsonPrimitive(false)))
        assertFalse(isCallRecordActive(JsonPrimitive("false")))
        assertFalse(isCallRecordActive(JsonPrimitive("0")))
        assertFalse(isCallRecordActive(JsonPrimitive("")))
        assertFalse(isCallRecordActive(null))
    }

    @Test
    fun `missing exist with calling status is not ended`() {
        // RN 'treats missing exist with calling status as not ended'
        assertFalse(isCallRecordExplicitlyEnded(msg("calling")))
        assertFalse(isCallRecordActive(null))
    }

    @Test
    fun `exist false is ended`() {
        // RN 'treats exist false as ended'
        assertTrue(isCallRecordExplicitlyEnded(msg("calling", JsonPrimitive("false"))))
    }

    @Test
    fun `explicitly ended requires clear signal`() {
        // 无 recordData → 仅 end/cancel 视为明确结束（callRecordStatus.ts:23-24）
        assertTrue(isCallRecordExplicitlyEnded(msg("end")))
        assertTrue(isCallRecordExplicitlyEnded(msg("cancel")))
        // exist true → 不结束
        assertFalse(isCallRecordExplicitlyEnded(msg("calling", JsonPrimitive(true))))
        // exist 明确 false → 结束
        assertTrue(isCallRecordExplicitlyEnded(msg("talking", JsonPrimitive(false))))
        // exist 缺失 + calling/talking → 不结束（主叫等待期保护）
        assertFalse(isCallRecordExplicitlyEnded(msg("talking")))
        // exist 缺失 + end/cancel/reject → 结束（:39-40）
        assertTrue(isCallRecordExplicitlyEnded(msg("reject")))
        // exist 缺失 + 其他 status（timeout）→ 不结束
        assertFalse(isCallRecordExplicitlyEnded(msg("timeout")))
    }

    @Test
    fun `null callMsg is not ended`() {
        assertFalse(isCallRecordExplicitlyEnded(null))
    }

    @Test
    fun `recordData helper exposes exist raw`() {
        val data = msg("calling", JsonPrimitive("true")).recordDataObj!!
        assertTrue(isCallRecordActive(data.exist))
    }
}
