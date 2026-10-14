package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoiceUserStatus
import cn.appia.im.core.voice.VoicePhase
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * shouldQueueOtherIncoming（RN shouldQueueOtherIncoming.test.ts 全用例）+
 * getOtherIncomingAlertType（RN getOtherIncomingAlertType.test.ts 全用例）。
 */
class QueueOtherIncomingTest {

    /** RN otherCall 夹具。 */
    private fun otherCall(recordId: String = "rec-2", initiator: String = "carol"): VoiceCallMsg = VoiceCallMsg(
        status = "calling",
        roomId = "r2",
        recordId = recordId,
        userStatus = listOf(VoiceUserStatus(username = "bob", status = "out")),
        recordData = buildJsonObject {
            put("exist", "true")
            put("recordId", recordId)
            put("channelId", "ch-2")
            put("initiator", initiator)
            put("initiatorName", "Carol")
            put(
                "receivers",
                buildJsonArray {
                    add(buildJsonObject { put("receiver", "bob"); put("name", "Bob"); put("status", "out") })
                },
            )
        },
    )

    // ---- shouldQueueOtherIncoming（RN shouldQueueOtherIncoming.test.ts）----

    @Test
    fun `returns true when in call and different record`() {
        // RN 'returns true when in call and different record'
        assertTrue(
            shouldQueueOtherIncoming(
                phase = VoicePhase.InCall,
                currentRecordId = "rec-1",
                parsed = otherCall(),
                username = "bob",
                onCallStatus = null,
            ),
        )
    }

    @Test
    fun `returns false when idle`() {
        // RN 'returns false when idle'
        assertFalse(
            shouldQueueOtherIncoming(
                phase = VoicePhase.Idle,
                currentRecordId = "rec-1",
                parsed = otherCall(),
                username = "bob",
                onCallStatus = null,
            ),
        )
    }

    // ---- brief 补充关键分支 ----

    @Test
    fun `returns false for same recordId`() {
        // brief：同 recordId → false
        assertFalse(
            shouldQueueOtherIncoming(
                phase = VoicePhase.InCall,
                currentRecordId = "rec-2",
                parsed = otherCall(),
                username = "bob",
                onCallStatus = null,
            ),
        )
    }

    @Test
    fun `returns false when self is initiator`() {
        // brief：自己发起 → false
        assertFalse(
            shouldQueueOtherIncoming(
                phase = VoicePhase.InCall,
                currentRecordId = "rec-1",
                parsed = otherCall(initiator = "bob"),
                username = "bob",
                onCallStatus = null,
            ),
        )
    }

    @Test
    fun `returns false when recordData exist missing or false`() {
        // brief：recordData.exist 缺失 / 'false' → false
        val noExist = otherCall().copy(
            recordData = buildJsonObject {
                put("recordId", "rec-2")
                put("initiator", "carol")
                put(
                    "receivers",
                    buildJsonArray {
                        add(buildJsonObject { put("receiver", "bob"); put("name", "Bob"); put("status", "out") })
                    },
                )
            },
        )
        assertFalse(
            shouldQueueOtherIncoming(VoicePhase.InCall, "rec-1", noExist, "bob", null),
        )

        val existFalse = otherCall().copy(
            recordData = buildJsonObject {
                put("exist", "false")
                put("recordId", "rec-2")
                put("initiator", "carol")
                put(
                    "receivers",
                    buildJsonArray {
                        add(buildJsonObject { put("receiver", "bob"); put("name", "Bob"); put("status", "out") })
                    },
                )
            },
        )
        assertFalse(
            shouldQueueOtherIncoming(VoicePhase.InCall, "rec-1", existFalse, "bob", null),
        )
    }

    @Test
    fun `returns false without currentRecordId`() {
        // brief：无 currentRecordId → false
        assertFalse(
            shouldQueueOtherIncoming(VoicePhase.InCall, null, otherCall(), "bob", null),
        )
    }

    // ---- getOtherIncomingAlertType（RN getOtherIncomingAlertType.test.ts）----

    /** RN baseMsg 夹具。 */
    private fun alertBaseMsg(org: String = "bitmain"): VoiceCallMsg = VoiceCallMsg(
        status = "calling",
        roomId = "r2",
        roomType = "d",
        org = org,
        receivers = emptyList(),
        recordData = buildJsonObject {
            put("exist", "true")
            put("recordId", "rec-2")
            put("channelId", "ch-2")
        },
    )

    @Test
    fun `alert returns null when idle`() {
        // RN 'returns null when idle'
        assertNull(
            getOtherIncomingAlertType(VoicePhase.Idle, "rec-1", "bitmain", alertBaseMsg(), "accept"),
        )
    }

    @Test
    fun `alert returns 3 for cross-org while in call`() {
        // RN 'returns 3 for cross-org while in call'
        assertEquals(
            3,
            getOtherIncomingAlertType(VoicePhase.InCall, "rec-1", "bitmain", alertBaseMsg("sophgo"), "accept"),
        )
    }

    @Test
    fun `alert returns 1 for same-org accept`() {
        // RN 'returns 1 for same-org accept'
        assertEquals(
            1,
            getOtherIncomingAlertType(VoicePhase.InCall, "rec-1", "bitmain", alertBaseMsg(), "accept"),
        )
    }

    @Test
    fun `alert returns 2 for join intent`() {
        // RN 'returns 2 for join intent'
        assertEquals(
            2,
            getOtherIncomingAlertType(VoicePhase.InCall, "rec-1", "bitmain", alertBaseMsg(), "join"),
        )
    }
}
