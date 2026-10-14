package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoiceCallMsgContext
import cn.appia.im.core.voice.VoiceChatReceiver
import cn.appia.im.core.voice.VoiceUserStatus
import cn.appia.im.core.voice.VoicePhase
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * shouldEndCallFromCallMsg（RN shouldEndCallFromCallMsg.test.ts 全用例）+
 * shouldAcceptRemoteUserJoined（RN shouldAcceptRemoteUserJoined.test.ts 全用例）+
 * isStaleEndedCallSyncForHost（RN isStaleEndedCallSyncForHost.test.ts 全用例）。
 */
class EndCallDecisionsTest {

    /** RN asVoiceCallMsg 夹具等价。 */
    private fun msg(
        status: String,
        roomType: String? = null,
        recordRoomType: String? = null,
        exist: JsonElement? = null,
        userStatus: List<VoiceUserStatus> = emptyList(),
        recordReceivers: List<Pair<String, String?>> = emptyList(),
    ): VoiceCallMsg = VoiceCallMsg(
        status = status,
        roomId = "r1",
        roomType = roomType,
        userStatus = userStatus,
        recordData = buildJsonObject {
            if (exist != null) put("exist", exist)
            put("recordId", "r1")
            if (recordRoomType != null) put("roomType", recordRoomType)
            if (recordReceivers.isNotEmpty()) {
                put(
                    "receivers",
                    buildJsonArray {
                        recordReceivers.forEach { (receiver, st) ->
                            add(
                                buildJsonObject {
                                    put("receiver", receiver)
                                    put("name", receiver)
                                    if (st != null) put("status", st)
                                },
                            )
                        }
                    },
                )
            }
        },
    )

    // ---- shouldEndCallFromCallMsg（RN shouldEndCallFromCallMsg.test.ts）----

    @Test
    fun `is true when record explicitly ended`() {
        // RN 'is true when record explicitly ended'
        assertTrue(
            shouldEndCallFromCallMsg(msg("end", exist = kotlinx.serialization.json.JsonPrimitive(false)), "alice", null),
        )
    }

    @Test
    fun `does not end DM when peer has hangup while exist is still true`() {
        // RN 'does not end DM when peer has hangup while exist is still true'
        assertFalse(
            shouldEndCallFromCallMsg(
                msg(
                    "talking", roomType = "d", exist = kotlinx.serialization.json.JsonPrimitive("true"),
                    userStatus = listOf(
                        VoiceUserStatus(username = "alice", status = "in"),
                        VoiceUserStatus(username = "bob", operation = "hangup", status = "out"),
                    ),
                ),
                "alice", null,
            ),
        )
    }

    @Test
    fun `ends group call when one peer leaves but others still pending`() {
        // RN 'ends group call when one peer leaves but others still pending'
        assertTrue(
            shouldEndCallFromCallMsg(
                msg(
                    "talking", roomType = "p", exist = kotlinx.serialization.json.JsonPrimitive(false),
                    userStatus = listOf(
                        VoiceUserStatus(username = "alice", status = "in"),
                        VoiceUserStatus(username = "bob", operation = "hangup", status = "out"),
                        VoiceUserStatus(username = "carol", status = "out", operation = "waiting"),
                        VoiceUserStatus(username = "dave", status = "out"),
                    ),
                ),
                "alice", null,
            ),
        )
    }

    @Test
    fun `ends group call when pending users only exist in receivers and callee channel empty`() {
        // RN 'ends group call when pending users only exist in receivers and callee channel empty'
        assertTrue(
            shouldEndCallFromCallMsg(
                msg(
                    "talking", exist = kotlinx.serialization.json.JsonPrimitive(false), recordRoomType = "p",
                    recordReceivers = listOf("bob" to "out", "carol" to "out"),
                    userStatus = listOf(
                        VoiceUserStatus(username = "alice", status = "in"),
                        VoiceUserStatus(username = "bob", operation = "hangup", status = "out"),
                    ),
                ),
                "alice", null,
            ),
        )
    }

    @Test
    fun `ends group host when status end but others still pending`() {
        // RN 'ends group host when status end but others still pending'
        assertTrue(
            shouldEndCallFromCallMsg(
                msg(
                    "end", exist = kotlinx.serialization.json.JsonPrimitive(false),
                    userStatus = listOf(
                        VoiceUserStatus(username = "alice", status = "in"),
                        VoiceUserStatus(username = "bob", operation = "hangup", status = "out"),
                        VoiceUserStatus(username = "carol", status = "out", operation = "waiting"),
                    ),
                ),
                "alice", VoiceCallMsgContext(fallbackRoomType = "p"),
            ),
        )
    }

    @Test
    fun `does not end group call when one in callee leaves but another callee still in`() {
        // RN 'does not end group call when one in callee leaves but another callee still in'
        assertFalse(
            shouldEndCallFromCallMsg(
                msg(
                    "talking", roomType = "p", exist = kotlinx.serialization.json.JsonPrimitive("true"),
                    userStatus = listOf(
                        VoiceUserStatus(username = "alice", status = "in"),
                        VoiceUserStatus(username = "bob", operation = "hangup", status = "out"),
                        VoiceUserStatus(username = "carol", status = "in"),
                    ),
                ),
                "alice", null,
            ),
        )
    }

    @Test
    fun `ends group call when everyone left or rejected`() {
        // RN 'ends group call when everyone left or rejected'
        assertTrue(
            shouldEndCallFromCallMsg(
                msg(
                    "talking", roomType = "p", exist = kotlinx.serialization.json.JsonPrimitive(false),
                    userStatus = listOf(
                        VoiceUserStatus(username = "alice", status = "in"),
                        VoiceUserStatus(username = "bob", operation = "hangup", status = "out"),
                        VoiceUserStatus(username = "carol", operation = "reject", status = "out"),
                    ),
                ),
                "alice", null,
            ),
        )
    }

    @Test
    fun `is false for DM when peer still in call`() {
        // RN 'is false for DM when peer still in call'
        assertFalse(
            shouldEndCallFromCallMsg(
                msg(
                    "talking", roomType = "d", exist = kotlinx.serialization.json.JsonPrimitive("true"),
                    userStatus = listOf(
                        VoiceUserStatus(username = "alice", status = "in"),
                        VoiceUserStatus(username = "bob", status = "in"),
                    ),
                ),
                "alice", null,
            ),
        )
    }

    @Test
    fun `does not end DM when peer is out with waiting operation`() {
        // RN 'does not end DM when peer is out with waiting operation'
        assertFalse(
            shouldEndCallFromCallMsg(
                msg(
                    "talking", roomType = "d", exist = kotlinx.serialization.json.JsonPrimitive("true"),
                    userStatus = listOf(
                        VoiceUserStatus(username = "alice", status = "in"),
                        VoiceUserStatus(username = "bob", status = "out", operation = "waiting"),
                    ),
                ),
                "alice", null,
            ),
        )
    }

    @Test
    fun `does not end DM when peer status is out without hangup or reject`() {
        // RN 'does not end DM when peer status is out without hangup or reject'
        assertFalse(
            shouldEndCallFromCallMsg(
                msg(
                    "talking", roomType = "d", exist = kotlinx.serialization.json.JsonPrimitive("true"),
                    userStatus = listOf(
                        VoiceUserStatus(username = "alice", status = "in"),
                        VoiceUserStatus(username = "bob", status = "out"),
                    ),
                ),
                "alice", null,
            ),
        )
    }

    @Test
    fun `ends group callee when status is timeout while others still pending`() {
        // RN 'ends group callee when status is timeout while others still pending'
        assertTrue(
            shouldEndCallFromCallMsg(
                msg(
                    "timeout", roomType = "p", exist = kotlinx.serialization.json.JsonPrimitive("true"),
                    recordReceivers = listOf("bob" to "out", "carol" to "out"),
                    userStatus = listOf(
                        VoiceUserStatus(username = "alice", status = "in"),
                        VoiceUserStatus(username = "bob", status = "out", operation = "waiting"),
                        VoiceUserStatus(username = "carol", status = "out"),
                    ),
                ),
                "bob", null,
            ),
        )
    }

    @Test
    fun `ends group host when status is timeout and nobody is in channel`() {
        // RN 'ends group host when status is timeout and nobody is in channel'
        assertTrue(
            shouldEndCallFromCallMsg(
                msg(
                    "timeout", roomType = "p", exist = kotlinx.serialization.json.JsonPrimitive("true"),
                    userStatus = listOf(
                        VoiceUserStatus(username = "alice", status = "out"),
                        VoiceUserStatus(username = "bob", status = "out", operation = "waiting"),
                    ),
                ),
                "alice", null,
            ),
        )
    }

    @Test
    fun `does not end group when timeout but local user in call with others`() {
        // RN 'does not end group when timeout but local user in call with others'
        assertFalse(
            shouldEndCallFromCallMsg(
                msg(
                    "timeout", roomType = "p", exist = kotlinx.serialization.json.JsonPrimitive("true"),
                    userStatus = listOf(
                        VoiceUserStatus(username = "alice", status = "in"),
                        VoiceUserStatus(username = "bob", status = "in"),
                        VoiceUserStatus(username = "carol", status = "out", operation = "waiting"),
                    ),
                ),
                "alice", null,
            ),
        )
    }

    @Test
    fun `ends DM when status is timeout`() {
        // RN 'ends DM when status is timeout'
        assertTrue(
            shouldEndCallFromCallMsg(
                msg("timeout", roomType = "d", exist = kotlinx.serialization.json.JsonPrimitive("true")),
                "alice", null,
            ),
        )
    }

    @Test
    fun `ends DM when status is cancel while ringing`() {
        // RN 'ends DM when status is cancel while ringing'
        assertTrue(
            shouldEndCallFromCallMsg(
                msg("cancel", roomType = "d", exist = kotlinx.serialization.json.JsonPrimitive("true")),
                "bob", null,
            ),
        )
    }

    @Test
    fun `ends DM when exist is explicitly false`() {
        // RN 'ends DM when exist is explicitly false'
        assertTrue(
            shouldEndCallFromCallMsg(
                msg(
                    "talking", roomType = "d", exist = kotlinx.serialization.json.JsonPrimitive(false),
                    userStatus = listOf(
                        VoiceUserStatus(username = "alice", status = "in"),
                        VoiceUserStatus(username = "bob", status = "out"),
                    ),
                ),
                "alice", null,
            ),
        )
    }

    // ---- shouldAcceptRemoteUserJoined（RN shouldAcceptRemoteUserJoined.test.ts）----

    @Test
    fun `accepts remote - rejects self uid`() {
        // RN 'rejects self uid'
        assertFalse(shouldAcceptRemoteUserJoined(8888L, 8888L))
    }

    @Test
    fun `accepts remote - accepts DM peer with matching receiverUid`() {
        // RN 'accepts DM peer with matching receiverUid'
        assertTrue(shouldAcceptRemoteUserJoined(42L, 8888L))
    }

    @Test
    fun `accepts remote - accepts DM peer even when uid does not match assigned receiverUid`() {
        // RN 'accepts DM peer even when remote uid does not match assigned receiverUid (legacy behavior)'
        // 被叫可能因服务端未回传 receiverUid 而用回退 UID 加入。
        // 旧版 legacy VoiceChatView 对任何远端加入即接通，无 UID 精确匹配过滤。
        assertTrue(shouldAcceptRemoteUserJoined(99L, 8888L))
    }

    @Test
    fun `accepts remote - accepts any non-self uid for group call`() {
        // RN 'accepts any non-self uid for group call'
        assertTrue(shouldAcceptRemoteUserJoined(777L, 8888L))
    }

    @Test
    fun `accepts remote - accepts when local uid is undefined`() {
        // RN 'accepts when local uid is undefined'
        assertTrue(shouldAcceptRemoteUserJoined(42L, null))
    }

    // ---- isStaleEndedCallSyncForHost（RN isStaleEndedCallSyncForHost.test.ts）----

    @Test
    fun `stale sync - is true when host is outgoing without recordId and sync is ended`() {
        // RN 'is true when host is outgoing without recordId and sync is ended'
        assertTrue(
            isStaleEndedCallSyncForHost(
                VoicePhase.OutgoingRinging, true, null,
                msg("end", exist = kotlinx.serialization.json.JsonPrimitive(false)),
            ),
        )
    }

    @Test
    fun `stale sync - is true when sync ended record differs from current record`() {
        // RN 'is true when sync ended record differs from current record'
        assertTrue(
            isStaleEndedCallSyncForHost(
                VoicePhase.OutgoingRinging, true, "rec-new",
                msg("end", exist = kotlinx.serialization.json.JsonPrimitive(false)),
            ),
        )
    }

    @Test
    fun `stale sync - is false when sync ended record matches current outgoing record`() {
        // RN 'is false when sync ended record matches current outgoing record'
        assertFalse(
            isStaleEndedCallSyncForHost(
                VoicePhase.Connecting, true, "r1",
                msg("end", exist = kotlinx.serialization.json.JsonPrimitive(false)),
            ),
        )
    }

    @Test
    fun `stale sync - is false when sync is still active`() {
        // RN 'is false when sync is still active'
        assertFalse(
            isStaleEndedCallSyncForHost(
                VoicePhase.OutgoingRinging, true, "r1",
                msg("calling", exist = kotlinx.serialization.json.JsonPrimitive("true")),
            ),
        )
    }
}
