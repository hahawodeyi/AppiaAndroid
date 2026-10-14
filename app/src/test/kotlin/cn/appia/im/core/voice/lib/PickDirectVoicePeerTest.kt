package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoiceChatHoster
import cn.appia.im.core.voice.VoiceUser
import cn.appia.im.core.voice.voiceJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * resolveIncomingCallerLabel / getIncomingCallRecordId（RN resolveIncomingCallerLabel.test.ts 全用例）
 * + pickDirectVoicePeer（RN pickDirectVoicePeer.test.ts 全用例）
 * + isDirectVoicePeerInCall（RN isDirectVoicePeerInCall.test.ts 全用例）。
 */
class PickDirectVoicePeerTest {

    private fun recordMsg(initiator: String? = null, initiatorName: String? = null, recordId: String? = null): VoiceCallMsg {
        val record = buildJsonObject {
            if (initiator != null) put("initiator", initiator)
            if (initiatorName != null) put("initiatorName", initiatorName)
            if (recordId != null) put("recordId", recordId)
        }
        return VoiceCallMsg(recordData = record)
    }

    // ---- resolveIncomingCallerIdentity / resolveIncomingCallerLabel ----

    @Test
    fun `prefers initiatorName over initiator username`() {
        // RN 'prefers initiatorName over initiator username'
        val msg = recordMsg(initiator = "alice", initiatorName = "Alice")
        assertEquals("Alice", resolveIncomingCallerIdentity(msg).displayName)
    }

    @Test
    fun `does not fall through to initiator when only username fields exist`() {
        // RN 'does not fall through to initiator when only username fields exist'
        assertEquals("alice", resolveIncomingCallerLabel(recordMsg(initiator = "alice")))
    }

    @Test
    fun `keeps display name when sync briefly drops initiatorName`() {
        // RN 'keeps display name when sync briefly drops initiatorName'
        val withName = recordMsg(initiator = "alice", initiatorName = "Alice")
        val first = resolveIncomingCallerLabel(withName)
        val second = resolveIncomingCallerLabel(recordMsg(initiator = "alice"), first)
        assertEquals("Alice", first)
        assertEquals("Alice", second)
    }

    @Test
    fun `upgrades from username to display name when name arrives`() {
        // RN 'upgrades from username to display name when name arrives'
        val first = resolveIncomingCallerLabel(recordMsg(initiator = "alice"))
        val second = resolveIncomingCallerLabel(recordMsg(initiator = "alice", initiatorName = "Alice"), first)
        assertEquals("Alice", second)
    }

    // ---- getIncomingCallRecordId ----

    @Test
    fun `prefers top-level recordId then falls back to recordData`() {
        // resolveIncomingCallerLabel.ts:67-72
        assertEquals("top", getIncomingCallRecordId(VoiceCallMsg(recordId = "top")))
        assertEquals("rec", getIncomingCallRecordId(recordMsg(recordId = "rec")))
        assertEquals("", getIncomingCallRecordId(VoiceCallMsg()))
        assertEquals("", getIncomingCallRecordId(null))
    }

    // ---- pickDirectVoicePeer ----

    @Test
    fun `prefers waiting callee when host is dialing`() {
        // RN 'prefers waiting callee when host is dialing'
        assertEquals(
            VoiceDisplayPeer("bob", "Bob"),
            pickDirectVoicePeer(
                users = emptyList(),
                localUsername = "alice",
                waitingCallee = VoiceDisplayPeer("bob", "Bob"),
                callMsg = null,
                hoster = null,
            ),
        )
    }

    @Test
    fun `returns remote user when userStatus includes both parties`() {
        // RN 'returns remote user when userStatus includes both parties'
        val users = listOf(
            VoiceUser(username = "alice", name = "Alice", status = "in"),
            VoiceUser(username = "bob", name = "Bob", status = "in"),
        )
        assertEquals(
            VoiceDisplayPeer("bob", "Bob"),
            pickDirectVoicePeer(
                users = users,
                localUsername = "alice",
                waitingCallee = null,
                callMsg = VoiceCallMsg(roomType = "d"),
                hoster = null,
            ),
        )
    }

    @Test
    fun `falls back to initiator for callee when users list is empty`() {
        // RN 'falls back to initiator for callee when users list is empty'
        assertEquals(
            VoiceDisplayPeer("alice", "Alice"),
            pickDirectVoicePeer(
                users = emptyList(),
                localUsername = "bob",
                waitingCallee = null,
                callMsg = VoiceCallMsg(
                    roomType = "d",
                    recordData = buildJsonObject {
                        put("initiator", "alice")
                        put("initiatorName", "Alice")
                    },
                ),
                hoster = null,
            ),
        )
    }

    @Test
    fun `returns null when nothing matches`() {
        // pickDirectVoicePeer.ts:72 兜底
        assertNull(
            pickDirectVoicePeer(
                users = emptyList(), localUsername = "alice", waitingCallee = null, callMsg = null, hoster = null,
            ),
        )
    }

    @Test
    fun `falls back to hoster receiver when callMsg has none`() {
        // pickDirectVoicePeer.ts:65-70 hoster.receivers 兜底
        assertEquals(
            VoiceDisplayPeer("bob", "Bob"),
            pickDirectVoicePeer(
                users = emptyList(),
                localUsername = "alice",
                waitingCallee = null,
                callMsg = null,
                hoster = VoiceChatHoster(roomId = "room-1", roomType = "d", receivers = listOf(cn.appia.im.core.voice.VoiceChatReceiver(receiver = "bob", name = "Bob"))),
            ),
        )
    }

    // ---- isDirectVoiceRoom ----

    @Test
    fun `isDirectVoiceRoom only matches d`() {
        // pickDirectVoicePeer.ts:75-76
        assertTrue(isDirectVoiceRoom("d"))
        assertFalse(isDirectVoiceRoom("p"))
        assertFalse(isDirectVoiceRoom(null))
    }

    // ---- isDirectVoicePeerInCall ----

    private val hoster = VoiceChatHoster(
        roomId = "room-1", roomType = "d",
        receivers = listOf(cn.appia.im.core.voice.VoiceChatReceiver(receiver = "bob", name = "Bob")),
    )

    @Test
    fun `peer in call is true when peer userStatus is in`() {
        // RN 'is true when peer userStatus is in'
        val msg = VoiceCallMsg(
            userStatus = listOf(
                cn.appia.im.core.voice.VoiceUserStatus(username = "alice", status = "in"),
                cn.appia.im.core.voice.VoiceUserStatus(username = "bob", status = "in"),
            ),
        )
        assertTrue(isDirectVoicePeerInCall(msg, "alice", hoster))
    }

    @Test
    fun `peer in call is false when peer userStatus is out`() {
        // RN 'is false when peer userStatus is out'
        val msg = VoiceCallMsg(
            userStatus = listOf(
                cn.appia.im.core.voice.VoiceUserStatus(username = "alice", status = "in"),
                cn.appia.im.core.voice.VoiceUserStatus(username = "bob", status = "out", operation = "waiting"),
            ),
        )
        assertFalse(isDirectVoicePeerInCall(msg, "alice", hoster))
    }

    @Test
    fun `peer in call is false when no peer resolvable`() {
        // isDirectVoicePeerInCall.ts:16-18
        assertFalse(isDirectVoicePeerInCall(null, "alice", null))
    }
}
