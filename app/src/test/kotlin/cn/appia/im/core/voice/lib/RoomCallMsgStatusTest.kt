package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoiceCallMsgContext
import cn.appia.im.core.voice.VoiceChatReceiver
import cn.appia.im.core.voice.VoiceUserStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * isRoomCallMsgActive / isRoomVoiceInProgress / reconcileStaleLocalRoomCallMsg
 * （RN isRoomCallMsgActive.test.ts + reconcileStaleLocalRoomCallMsg.test.ts + resolveRoomVoiceJoinBanner.test.ts
 *  中 isRoomVoiceInProgress 相关用例——RN 无独立 isRoomVoiceInProgress.test.ts，经横幅用例覆盖）。
 */
class RoomCallMsgStatusTest {

    private fun msg(
        status: String,
        roomType: String? = null,
        exist: String? = null,
        userStatus: List<VoiceUserStatus>? = null,
    ): VoiceCallMsg {
        val recordJson = buildString {
            append("{")
            if (exist != null) append("\"exist\":\"$exist\",")
            append("\"recordId\":\"r1\"")
            append("}")
        }
        return VoiceCallMsg(
            status = status,
            roomId = "r1",
            roomType = roomType,
            org = "bitmain",
            receivers = emptyList(),
            userStatus = userStatus,
            recordData = cn.appia.im.core.voice.voiceJson.parseToJsonElement(recordJson)
                as kotlinx.serialization.json.JsonObject,
        )
    }

    private val groupCall = msg(
        status = "talking", roomType = "p", exist = "false",
        userStatus = listOf(
            VoiceUserStatus(username = "alice", status = "in"),
            VoiceUserStatus(username = "bob", operation = "hangup", status = "out"),
            VoiceUserStatus(username = "carol", status = "out", operation = "waiting"),
        ),
    )

    // ---- isRoomCallMsgActive（RN isRoomCallMsgActive.test.ts 全用例）----

    @Test
    fun `true when status calling and exist is active`() {
        // RN 'is true when status calling and exist is active'
        assertTrue(isRoomCallMsgActive(msg("calling", exist = "true"), null))
    }

    @Test
    fun `false when status calling but exist ended`() {
        // RN 'is false when status calling but exist ended'
        assertFalse(isRoomCallMsgActive(msg("calling", exist = "false"), null))
    }

    @Test
    fun `false when status is end`() {
        // RN 'is false when status is end'
        assertFalse(isRoomCallMsgActive(msg("end"), null))
    }

    @Test
    fun `keeps room call active for group while still ringing`() {
        // RN groupVoiceCall.test.ts 'keeps room call active for group while still ringing'
        assertTrue(isRoomCallMsgActive(groupCall, null))
    }

    @Test
    fun `null or inactive status is false`() {
        assertFalse(isRoomCallMsgActive(null, null))
        assertFalse(isRoomCallMsgActive(msg("timeout", exist = "true"), null))
    }

    @Test
    fun `no recordData with calling status is active`() {
        // isRoomCallMsgActive.ts:28：无 recordData → true
        val noRecord = VoiceCallMsg(
            status = "calling", roomId = "r1", roomType = "d", org = "bitmain", receivers = emptyList(),
        )
        assertTrue(isRoomCallMsgActive(noRecord, null))
    }

    // ---- reconcileStaleLocalRoomCallMsg（RN reconcileStaleLocalRoomCallMsg.test.ts 全用例）----

    @Test
    fun `promotes local end to talking when users remain in channel`() {
        // RN 'promotes local end to talking when users remain in channel'
        val m = msg(
            "end", roomType = "p", exist = "false",
            userStatus = listOf(
                VoiceUserStatus(username = "alice", status = "in"),
                VoiceUserStatus(username = "bob", operation = "hangup", status = "out"),
            ),
        )
        assertEquals("talking", reconcileStaleLocalRoomCallMsg(m)?.status)
    }

    @Test
    fun `keeps end when nobody is in channel`() {
        // RN 'keeps end when nobody is in channel'
        val m = msg(
            "end", roomType = "p",
            userStatus = listOf(VoiceUserStatus(username = "bob", operation = "hangup", status = "out")),
        )
        assertEquals("end", reconcileStaleLocalRoomCallMsg(m)?.status)
    }

    @Test
    fun `null passes through and cancel reject also reconcile`() {
        assertNull(reconcileStaleLocalRoomCallMsg(null))
        val cancel = msg(
            "cancel", roomType = "p",
            userStatus = listOf(VoiceUserStatus(username = "alice", status = "in")),
        )
        assertEquals("talking", reconcileStaleLocalRoomCallMsg(cancel)?.status)
        // 非 end/cancel/reject 状态原样返回
        val talking = msg("talking", roomType = "p")
        assertEquals("talking", reconcileStaleLocalRoomCallMsg(talking)?.status)
    }

    // ---- isRoomVoiceInProgress（isRoomVoiceInProgress.ts，无独立 RN 测试，对齐横幅用例语义）----

    @Test
    fun `calling and talking are in progress`() {
        assertTrue(isRoomVoiceInProgress(msg("calling", roomType = "d")))
        assertTrue(isRoomVoiceInProgress(msg("talking", roomType = "d")))
        assertFalse(isRoomVoiceInProgress(msg("end", roomType = "d")))
        assertFalse(isRoomVoiceInProgress(null))
    }

    @Test
    fun `reconciles stale end before judging progress`() {
        // resolveRoomVoiceJoinBanner.test.ts 'shows join banner when local status is end but host still in channel'
        // 同款语义：status=end 但频道有人 → 修为 talking → in progress
        val staleEnd = msg(
            "end", roomType = "p", exist = "false",
            userStatus = listOf(
                VoiceUserStatus(username = "alice", status = "in"),
                VoiceUserStatus(username = "bob", operation = "hangup", status = "out"),
            ),
        )
        assertTrue(isRoomVoiceInProgress(staleEnd))
    }
}
