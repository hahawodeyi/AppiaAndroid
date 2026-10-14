package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoiceUserStatus
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * shouldPresentIncoming（RN shouldPresentIncoming.test.ts 全用例转写）。
 */
class ShouldPresentIncomingTest {

    private val dismissed = VoiceCallDismissedStore()

    /** RN baseCallMsg 夹具。 */
    private fun baseMsg(): VoiceCallMsg = msg(
        status = "calling",
        exist = JsonPrimitive("true"),
        recordReceivers = listOf(Triple("bob", "Bob", "out")),
        userStatus = listOf(VoiceUserStatus(username = "bob", status = "out")),
    )

    /** RN 测试内联 recordData 夹具（initiator alice / rec-1 / ch-1）。 */
    private fun msg(
        status: String,
        roomType: String? = null,
        exist: JsonElement? = null,
        recordRoomType: String? = null,
        recordReceivers: List<Triple<String, String, String?>> = emptyList(),
        userStatus: List<VoiceUserStatus>? = null,
        recordId: String = "rec-1",
        initiatorUid: String? = null,
    ): VoiceCallMsg = VoiceCallMsg(
        status = status,
        roomId = "room-1",
        roomType = roomType,
        userStatus = userStatus,
        recordData = buildJsonObject {
            if (exist != null) put("exist", exist)
            put("recordId", recordId)
            put("channelId", "ch-1")
            if (recordRoomType != null) put("roomType", recordRoomType)
            put("initiator", "alice")
            if (initiatorUid != null) put("initiatorUid", initiatorUid)
            if (recordReceivers.isNotEmpty()) {
                put(
                    "receivers",
                    kotlinx.serialization.json.buildJsonArray {
                        recordReceivers.forEach { (receiver, name, st) ->
                            add(
                                buildJsonObject {
                                    put("receiver", receiver)
                                    put("name", name)
                                    if (st != null) put("status", st)
                                },
                            )
                        }
                    },
                )
            }
        },
    )

    @Test
    fun `returns true for callee with out status`() {
        assertTrue(shouldPresentIncomingFromCallMsg(baseMsg(), "bob", null))
    }

    @Test
    fun `returns false for initiator on initial direct call`() {
        assertFalse(shouldPresentIncomingFromCallMsg(baseMsg(), "alice", null))
    }

    @Test
    fun `rings group initiator when re-invited after leaving`() {
        // RN 'rings group initiator when re-invited after leaving'
        val msg = msg(
            status = "talking", roomType = "p", exist = JsonPrimitive(false), initiatorUid = "8888",
            userStatus = listOf(
                VoiceUserStatus(username = "bob", status = "in"),
                VoiceUserStatus(username = "carol", status = "in"),
                VoiceUserStatus(username = "alice", status = "out", operation = "waiting"),
            ),
        )
        assertTrue(shouldPresentIncomingFromCallMsg(msg, "alice", false))
    }

    @Test
    fun `does not ring group initiator still in channel`() {
        // RN 'does not ring group initiator still in channel'
        val msg = msg(
            status = "talking", roomType = "p", exist = JsonPrimitive(false), initiatorUid = "8888",
            userStatus = listOf(
                VoiceUserStatus(username = "alice", status = "in"),
                VoiceUserStatus(username = "bob", status = "in"),
            ),
        )
        assertFalse(shouldPresentIncomingFromCallMsg(msg, "alice", false))
    }

    @Test
    fun `does not ring group initiator after hangup before reinvite`() {
        // RN 'does not ring group initiator after hangup before reinvite'
        val msg = msg(
            status = "talking", roomType = "p", exist = JsonPrimitive(false), initiatorUid = "8888",
            userStatus = listOf(
                VoiceUserStatus(username = "bob", status = "in"),
                VoiceUserStatus(username = "alice", status = "out", operation = "hangup"),
            ),
        )
        assertFalse(shouldPresentIncomingFromCallMsg(msg, "alice", false))
    }

    @Test
    fun `returns false when onCallStatus is false`() {
        assertFalse(shouldPresentIncomingFromCallMsg(baseMsg(), "bob", false))
    }

    @Test
    fun `still rings group callee when exist cleared but host still in channel`() {
        // RN 'still rings group callee when exist cleared but host still in channel'
        val msg = msg(
            status = "calling", roomType = "p", exist = JsonPrimitive(false),
            recordReceivers = listOf(Triple("bob", "Bob", "out")),
            userStatus = listOf(
                VoiceUserStatus(username = "alice", status = "in"),
                VoiceUserStatus(username = "bob", status = "out", operation = "waiting"),
            ),
        )
        assertTrue(shouldPresentIncomingFromCallMsg(msg, "bob", false))
    }

    @Test
    fun `returns false when user already in call`() {
        // RN 'returns false when user already in call'
        val msg = baseMsg().copy(
            userStatus = listOf(VoiceUserStatus(username = "bob", status = "in")),
        )
        assertFalse(shouldPresentIncomingFromCallMsg(msg, "bob", null))
    }

    @Test
    fun `returns false when record ended and nobody left in channel`() {
        // RN 'returns false when record ended and nobody left in channel'
        val msg = msg(
            status = "talking", roomType = "p", exist = JsonPrimitive(false),
            userStatus = listOf(
                VoiceUserStatus(username = "alice", operation = "hangup", status = "out"),
                VoiceUserStatus(username = "bob", status = "out", operation = "waiting"),
            ),
        )
        assertFalse(shouldPresentIncomingFromCallMsg(msg, "bob", false))
    }

    @Test
    fun `rings mid-call invitee when payload lacks roomType but others are in channel`() {
        // RN 'rings mid-call invitee when payload lacks roomType but others are in channel'
        val msg = msg(
            status = "talking", exist = JsonPrimitive(false),
            userStatus = listOf(
                VoiceUserStatus(username = "alice", status = "in"),
                VoiceUserStatus(username = "bob", status = "out", operation = "waiting"),
            ),
        )
        assertTrue(shouldPresentIncomingFromCallMsg(msg, "bob", false))
    }

    @Test
    fun `rings mid-call invitee when roomType only on recordData`() {
        // RN 'rings mid-call invitee when roomType only on recordData'
        val msg = msg(
            status = "talking", exist = JsonPrimitive(false), recordRoomType = "p",
            userStatus = listOf(
                VoiceUserStatus(username = "alice", status = "in"),
                VoiceUserStatus(username = "bob", status = "out", operation = "waiting"),
            ),
        )
        assertTrue(shouldPresentIncomingFromCallMsg(msg, "bob", false))
    }

    @Test
    fun `returns false when group call ended even if callee still pending`() {
        // RN 'returns false when group call ended even if callee still pending'
        val msg = msg(
            status = "end", roomType = "p", exist = JsonPrimitive(false),
            userStatus = listOf(VoiceUserStatus(username = "bob", status = "out", operation = "waiting")),
        )
        assertFalse(shouldPresentIncomingFromCallMsg(msg, "bob", false))
    }

    // ---- shouldPresentIncomingForUser ----

    @Test
    fun `returns false when recordId was dismissed locally`() {
        // RN 'returns false when recordId was dismissed locally'
        dismissed.markVoiceCallDismissed("rec-1", baseMsg(), "bob")
        assertFalse(shouldPresentIncomingForUser(baseMsg(), "bob", null, dismissed))
    }

    @Test
    fun `rings again after dismiss when invite snapshot changed mid-call reinvite`() {
        // RN 'rings again after dismiss when invite snapshot changed (mid-call reinvite)'
        dismissed.markVoiceCallDismissed("rec-1", baseMsg(), "bob")
        val msg = msg(
            status = "talking", roomType = "p", exist = JsonPrimitive(false),
            userStatus = listOf(
                VoiceUserStatus(username = "alice", status = "in"),
                VoiceUserStatus(username = "bob", status = "out", operation = "waiting"),
            ),
        )
        assertTrue(shouldPresentIncomingForUser(msg, "bob", false, dismissed))
    }
}
