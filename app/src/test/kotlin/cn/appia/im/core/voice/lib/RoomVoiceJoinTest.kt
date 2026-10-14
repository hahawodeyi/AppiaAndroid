package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoiceUserStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * prepareCallMsgForRoomVoiceJoin（RN prepareCallMsgForRoomVoiceJoin.test.ts 全用例）+
 * buildVoiceReceivers（RN buildVoiceReceivers.test.ts 全用例）+
 * isVoluntaryGroupRejoinEligible（RN isVoluntaryGroupRejoinEligible.test.ts 全用例）+
 * isVoiceMemberLockedForInvite（RN isVoiceMemberLockedForInvite.test.ts 全用例）。
 */
class RoomVoiceJoinTest {

    private fun msg(
        status: String,
        roomType: String? = null,
        userStatus: List<VoiceUserStatus>? = null,
    ): VoiceCallMsg = VoiceCallMsg(
        status = status,
        roomId = "r1",
        roomType = roomType,
        userStatus = userStatus,
    )

    // ---- prepareCallMsgForRoomVoiceJoin ----

    @Test
    fun `marks self as accept in before rejoin`() {
        // RN 'marks self as accept/in before rejoin'
        val callMsg = msg(
            status = "talking",
            userStatus = listOf(
                VoiceUserStatus(username = "alice", status = "in"),
                VoiceUserStatus(username = "bob", operation = "hangup", status = "out", uid = 2002L),
            ),
        )

        val next = prepareCallMsgForRoomVoiceJoin(callMsg, "bob")
        val row = next.userStatus!![1]
        assertEquals("bob", row.username)
        assertEquals("accept", row.operation)
        assertEquals("in", row.status)
        assertEquals(2002L, row.uid)
        // 未匹配行保持原样
        assertEquals("in", next.userStatus!![0].status)
        assertEquals(null, next.userStatus!![0].operation)
    }

    @Test
    fun `returns callMsg unchanged when username blank or userStatus empty`() {
        // prepareCallMsgForRoomVoiceJoin.ts:8-11
        val callMsg = msg(status = "talking", userStatus = listOf(VoiceUserStatus(username = "bob", status = "out")))
        assertEquals(callMsg, prepareCallMsgForRoomVoiceJoin(callMsg, "  "))
        val noStatus = msg(status = "talking")
        assertEquals(noStatus, prepareCallMsgForRoomVoiceJoin(noStatus, "bob"))
    }

    @Test
    fun `username match is case-insensitive`() {
        // prepareCallMsgForRoomVoiceJoin.ts:15-17 lower-case 比较
        val callMsg = msg(
            status = "talking",
            userStatus = listOf(VoiceUserStatus(username = "Bob", status = "out")),
        )
        assertEquals("in", prepareCallMsgForRoomVoiceJoin(callMsg, "bob").userStatus!![0].status)
    }

    // ---- buildVoiceReceivers ----

    @Test
    fun `excludes current user and maps receiver fields`() {
        // RN 'excludes current user and maps receiver fields'（RN 两组嵌套 → 扁平列表，顺序保持）
        val receivers = buildVoiceReceivers(
            listOf(
                RoomMemberLike(username = "me", id = "id-me", name = "Me"),
                RoomMemberLike(username = "bob", id = "id-bob", name = "Bob"),
                RoomMemberLike(username = "carol", id = "id-carol", name = "Carol"),
            ),
            "me",
        )

        assertEquals(
            listOf(
                cn.appia.im.core.voice.VoiceChatReceiver(receiver = "bob", receiverAppiaId = "id-bob", name = "Bob"),
                cn.appia.im.core.voice.VoiceChatReceiver(receiver = "carol", receiverAppiaId = "id-carol", name = "Carol"),
            ),
            receivers,
        )
    }

    @Test
    fun `uses username as name fallback`() {
        // RN 'uses username as name fallback'
        val receivers = buildVoiceReceivers(listOf(RoomMemberLike(username = "x", id = "id-x", name = null)), "other")
        assertEquals("x", receivers[0].name)
    }

    @Test
    fun `skips members without username`() {
        // RN 'skips members without username'
        val receivers = buildVoiceReceivers(listOf(RoomMemberLike(username = null, id = "id-only", name = null)), "me")
        assertEquals(emptyList<cn.appia.im.core.voice.VoiceChatReceiver>(), receivers)
    }

    // ---- isVoluntaryGroupRejoinEligible ----

    private fun baseRejoinMsg() = msg(
        status = "talking", roomType = "p",
        userStatus = listOf(
            VoiceUserStatus(username = "alice", status = "in"),
            VoiceUserStatus(username = "bob", status = "out", operation = "hangup"),
        ),
    )

    @Test
    fun `returns true for hangup member in group call`() {
        // RN 'returns true for hangup member in group call'
        assertTrue(isVoluntaryGroupRejoinEligible(baseRejoinMsg(), "bob", "p"))
    }

    @Test
    fun `returns true for out member without waiting`() {
        // RN 'returns true for out member without waiting'
        val m = baseRejoinMsg().copy(
            userStatus = listOf(
                VoiceUserStatus(username = "alice", status = "in"),
                VoiceUserStatus(username = "bob", status = "out"),
            ),
        )
        assertTrue(isVoluntaryGroupRejoinEligible(m, "bob", "p"))
    }

    @Test
    fun `returns false for waiting reinvite`() {
        // RN 'returns false for waiting reinvite'
        val m = baseRejoinMsg().copy(
            userStatus = listOf(
                VoiceUserStatus(username = "alice", status = "in"),
                VoiceUserStatus(username = "bob", status = "out", operation = "waiting"),
            ),
        )
        assertFalse(isVoluntaryGroupRejoinEligible(m, "bob", "p"))
    }

    @Test
    fun `returns false when already in channel`() {
        // RN 'returns false when already in channel'
        val m = baseRejoinMsg().copy(
            userStatus = listOf(
                VoiceUserStatus(username = "alice", status = "in"),
                VoiceUserStatus(username = "bob", status = "in"),
            ),
        )
        assertFalse(isVoluntaryGroupRejoinEligible(m, "bob", "p"))
    }

    @Test
    fun `returns false for direct message`() {
        // RN 'returns false for direct message'
        val dm = msg(
            status = "talking", roomType = "d",
            userStatus = listOf(
                VoiceUserStatus(username = "alice", status = "in"),
                VoiceUserStatus(username = "bob", status = "out", operation = "hangup"),
            ),
        )
        assertFalse(isVoluntaryGroupRejoinEligible(dm, "bob", "d"))
    }

    @Test
    fun `returns false when call ended`() {
        // RN 'returns false when call ended'
        assertFalse(isVoluntaryGroupRejoinEligible(baseRejoinMsg().copy(status = "end"), "bob", "p"))
    }

    // ---- isGroupInitiatorReinviteEligible（RN isGroupInitiatorReinviteEligible.test.ts 全用例；函数已在 Task 2 IsGroupInitiatorReinviteEligible.kt 落地）----

    private fun groupReinviteBase(): VoiceCallMsg {
        val recordJson = """
            {"exist":false,"recordId":"r1","channelId":"ch-1","initiator":"alice","initiatorUid":"8888"}
        """.trimIndent()
        return VoiceCallMsg(
            status = "talking",
            roomId = "r1",
            roomType = "p",
            userStatus = listOf(
                VoiceUserStatus(username = "bob", status = "in"),
                VoiceUserStatus(username = "alice", status = "out", operation = "waiting"),
            ),
            recordData = cn.appia.im.core.voice.voiceJson.parseToJsonElement(recordJson)
                as kotlinx.serialization.json.JsonObject,
        )
    }

    @Test
    fun `reinvite true when initiator is waiting and others in channel`() {
        // RN 'returns true when initiator is waiting and others in channel'
        assertTrue(isGroupInitiatorReinviteEligible(groupReinviteBase(), "alice"))
    }

    @Test
    fun `reinvite true for out pending without waiting operation`() {
        // RN 'returns true for out pending without waiting operation'
        val msg = groupReinviteBase().copy(
            userStatus = listOf(
                VoiceUserStatus(username = "bob", status = "in"),
                VoiceUserStatus(username = "alice", status = "out"),
            ),
        )
        assertTrue(isGroupInitiatorReinviteEligible(msg, "alice"))
    }

    @Test
    fun `reinvite false for non-initiator`() {
        // RN 'returns false for non-initiator'
        assertFalse(isGroupInitiatorReinviteEligible(groupReinviteBase(), "bob"))
    }

    @Test
    fun `reinvite false for direct message`() {
        // RN 'returns false for direct message'
        val recordJson = """
            {"exist":true,"recordId":"r1","initiator":"alice","initiatorUid":"8888",
             "receivers":[{"receiver":"bob","status":"out"}]}
        """.trimIndent()
        val dm = VoiceCallMsg(
            status = "talking",
            roomId = "r1",
            roomType = "d",
            userStatus = listOf(
                VoiceUserStatus(username = "bob", status = "in"),
                VoiceUserStatus(username = "alice", status = "out", operation = "waiting"),
            ),
            recordData = cn.appia.im.core.voice.voiceJson.parseToJsonElement(recordJson)
                as kotlinx.serialization.json.JsonObject,
        )
        assertFalse(isGroupInitiatorReinviteEligible(dm, "alice"))
    }

    @Test
    fun `reinvite false when nobody in channel`() {
        // RN 'returns false when nobody in channel'
        val msg = groupReinviteBase().copy(
            userStatus = listOf(
                VoiceUserStatus(username = "bob", status = "out", operation = "hangup"),
                VoiceUserStatus(username = "alice", status = "out", operation = "waiting"),
            ),
        )
        assertFalse(isGroupInitiatorReinviteEligible(msg, "alice"))
    }

    @Test
    fun `reinvite false when initiator already in channel`() {
        // RN 'returns false when initiator already in channel'
        val msg = groupReinviteBase().copy(
            userStatus = listOf(
                VoiceUserStatus(username = "alice", status = "in"),
                VoiceUserStatus(username = "bob", status = "in"),
            ),
        )
        assertFalse(isGroupInitiatorReinviteEligible(msg, "alice"))
    }

    @Test
    fun `reinvite false when initiator hung up`() {
        // RN 'returns false when initiator hung up'
        val msg = groupReinviteBase().copy(
            userStatus = listOf(
                VoiceUserStatus(username = "bob", status = "in"),
                VoiceUserStatus(username = "alice", status = "out", operation = "hangup"),
            ),
        )
        assertFalse(isGroupInitiatorReinviteEligible(msg, "alice"))
    }

    @Test
    fun `reinvite false when call ended`() {
        // RN 'returns false when call ended'
        assertFalse(isGroupInitiatorReinviteEligible(groupReinviteBase().copy(status = "end"), "alice"))
    }

    // ---- isVoiceMemberLockedForInvite ----

    private val lockCallMsg = msg(
        status = "talking",
        userStatus = listOf(
            VoiceUserStatus(username = "alice", status = "in"),
            VoiceUserStatus(username = "bob", status = "out", operation = "waiting"),
            VoiceUserStatus(username = "carol", operation = "hangup", status = "out"),
            VoiceUserStatus(username = "dave", operation = "reject", status = "out"),
        ),
    )

    @Test
    fun `locks members in channel`() {
        // RN 'locks members in channel'
        assertTrue(isVoiceMemberLockedForInvite(lockCallMsg, "alice"))
    }

    @Test
    fun `locks members still waiting`() {
        // RN 'locks members still waiting'
        assertTrue(isVoiceMemberLockedForInvite(lockCallMsg, "bob"))
    }

    @Test
    fun `does not lock members who left`() {
        // RN 'does not lock members who left'
        assertFalse(isVoiceMemberLockedForInvite(lockCallMsg, "carol"))
        assertFalse(isVoiceMemberLockedForInvite(lockCallMsg, "dave"))
    }

    @Test
    fun `does not lock members not in userStatus`() {
        // RN 'does not lock members not in userStatus'
        assertFalse(isVoiceMemberLockedForInvite(lockCallMsg, "eve"))
    }
}
