package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoiceCallMsgContext
import cn.appia.im.core.voice.VoiceUserStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * groupVoiceCall（RN groupVoiceCall.test.ts 全用例 + countUsersInVoiceChannel / isGroupVoiceCallMsg /
 * resolveVoiceCallRoomType / countGroupVoiceInvitees / isGroupVoiceCallEndedForLocalUser）。
 */
class GroupVoiceCallTest {

    /** RN groupVoiceCall.test.ts groupMsg 夹具。 */
    private val groupMsg = msg(
        status = "talking", roomType = "p", exist = "false",
        userStatus = listOf(
            VoiceUserStatus(username = "alice", status = "in"),
            VoiceUserStatus(username = "bob", operation = "hangup", status = "out"),
            VoiceUserStatus(username = "carol", status = "out", operation = "waiting"),
        ),
    )

    private fun msg(
        status: String,
        roomType: String? = null,
        exist: String? = null,
        userStatus: List<VoiceUserStatus>? = null,
        receivers: List<String> = emptyList(),
        recordReceivers: List<String> = emptyList(),
        initiator: String? = null,
    ): VoiceCallMsg {
        val recordJson = buildString {
            append("{")
            if (exist != null) append("\"exist\":\"$exist\",")
            append("\"recordId\":\"r1\"")
            if (initiator != null) append(",\"initiator\":\"$initiator\"")
            if (recordReceivers.isNotEmpty()) {
                append(",\"receivers\":[")
                append(recordReceivers.joinToString(",") { "{\"receiver\":\"$it\",\"name\":\"$it\"}" })
                append("]")
            }
            append("}")
        }
        return VoiceCallMsg(
            status = status,
            roomId = "r1",
            roomType = roomType,
            org = "bitmain",
            receivers = receivers.map { cn.appia.im.core.voice.VoiceChatReceiver(receiver = it, name = it) },
            userStatus = userStatus,
            recordData = cn.appia.im.core.voice.voiceJson.parseToJsonElement(recordJson)
                as kotlinx.serialization.json.JsonObject,
        )
    }

    @Test
    fun `detects callee in channel excluding host`() {
        // RN 'detects callee in channel excluding host'
        assertFalse(groupHasCalleeInVoiceChannel(groupMsg, "alice"))
        val withBobIn = groupMsg.copy(
            userStatus = listOf(
                VoiceUserStatus(username = "alice", status = "in"),
                VoiceUserStatus(username = "bob", status = "in"),
            ),
        )
        assertTrue(groupHasCalleeInVoiceChannel(withBobIn, "alice"))
    }

    @Test
    fun `detects pending participants after one leaves`() {
        // RN 'detects pending participants after one leaves'
        assertTrue(groupCallHasActiveOrPendingParticipants(groupMsg, "alice"))
        assertTrue(isGroupVoiceCallOngoing(groupMsg, null))
    }

    @Test
    fun `does not clear local room call when leaver still has pending participants`() {
        // RN 'does not clear local room call when leaver still has pending participants'
        assertFalse(shouldClearLocalRoomCallAfterEnd(groupMsg, "bob", null))
    }

    @Test
    fun `clears local room call for host when exist ended and no callee remains in channel`() {
        // RN 'clears local room call for host when exist ended and no callee remains in channel'
        assertTrue(shouldClearLocalRoomCallAfterEnd(groupMsg, "alice", null))
    }

    // ---- resolveVoiceCallRoomType（groupVoiceCall.ts:12-19）----

    @Test
    fun `roomType resolution prefers top level then recordData then fallback`() {
        val top = msg("talking", roomType = "d")
        val record = msg("talking", roomType = null).copy(
            recordData = cn.appia.im.core.voice.voiceJson.parseToJsonElement("""{"roomType":"c","recordId":"r1"}""")
                as kotlinx.serialization.json.JsonObject,
        )
        assertEquals("d", resolveVoiceCallRoomType(top, null))
        assertEquals("c", resolveVoiceCallRoomType(record, null))
        assertEquals("p", resolveVoiceCallRoomType(msg("talking"), VoiceCallMsgContext(fallbackRoomType = "p")))
        assertEquals(null, resolveVoiceCallRoomType(msg("talking"), null))
    }

    // ---- isGroupVoiceCallMsg（groupVoiceCall.ts:40-52）----

    @Test
    fun `group detection by room type and by receiver count`() {
        // p/c 房间型 → 群组
        assertTrue(isGroupVoiceCallMsg(msg("talking", roomType = "p"), null))
        assertTrue(isGroupVoiceCallMsg(msg("talking", roomType = "c"), null))
        assertFalse(isGroupVoiceCallMsg(msg("talking", roomType = "d"), null))
        // 非 p/c + 多 receiver（不含主叫）> 1 → 群组推断
        val multi = msg(
            "talking", roomType = "d", exist = "true", initiator = "alice",
            receivers = listOf("alice", "bob", "carol"),
        )
        assertTrue(isGroupVoiceCallMsg(multi, null))
        // 单 receiver → 非群组
        val single = msg(
            "talking", roomType = "d", exist = "true", initiator = "alice",
            receivers = listOf("bob"),
        )
        assertFalse(isGroupVoiceCallMsg(single, null))
        // callMsg null → 看 fallback
        assertFalse(isGroupVoiceCallMsg(null, null))
        assertTrue(isGroupVoiceCallMsg(null, VoiceCallMsgContext(fallbackRoomType = "p")))
    }

    // ---- countUsersInVoiceChannel（resolveRoomVoiceJoinBanner.ts:13-30，Task 3 依赖，本任务文件 IsRoomCallMsgActive.kt 内提供）----

    @Test
    fun `counts users in channel with lowercase dedupe`() {
        // RN resolveRoomVoiceJoinBanner.test.ts 'counts users in channel'
        assertEquals(1, countUsersInVoiceChannel(groupMsg))
        // 'dedupes duplicate in-channel rows by username'
        val dup = groupMsg.copy(
            userStatus = listOf(
                VoiceUserStatus(username = "alice", status = "in"),
                VoiceUserStatus(username = "Alice", status = "in"),
                VoiceUserStatus(username = "carol", status = "in"),
            ),
        )
        assertEquals(2, countUsersInVoiceChannel(dup))
        assertEquals(0, countUsersInVoiceChannel(null))
    }

    // ---- countGroupVoiceInvitees（groupVoiceCall.ts:84-105）----

    @Test
    fun `invitee count spans receivers and userStatus`() {
        val m = msg(
            "talking", exist = "true", initiator = "alice",
            receivers = listOf("bob", "carol"),
            userStatus = listOf(VoiceUserStatus(username = "alice", status = "in")),
        )
        assertEquals(2, countGroupVoiceInvitees(m))
        assertEquals(0, countGroupVoiceInvitees(null))
    }

    // ---- isGroupVoiceCallEndedForLocalUser（groupVoiceCall.ts:171-200）----

    @Test
    fun `ended for local user three branches`() {
        // 分支 1：status end/reject → true
        assertTrue(isGroupVoiceCallEndedForLocalUser(msg("end", roomType = "p"), "alice"))
        assertTrue(isGroupVoiceCallEndedForLocalUser(msg("reject", roomType = "p"), "alice"))
        // 分支 2：exist 明确结束 + 频道无他人 → true
        assertTrue(isGroupVoiceCallEndedForLocalUser(
            msg("talking", roomType = "p", exist = "false",
                userStatus = listOf(VoiceUserStatus(username = "alice", status = "in"))),
            "alice",
        ))
        assertFalse(isGroupVoiceCallEndedForLocalUser(
            msg("talking", roomType = "p", exist = "false",
                userStatus = listOf(
                    VoiceUserStatus(username = "alice", status = "in"),
                    VoiceUserStatus(username = "bob", status = "in"),
                )),
            "alice",
        ))
        // 分支 3：exist 未明确结束但他人全 hangup/reject → true
        assertTrue(isGroupVoiceCallEndedForLocalUser(
            msg("talking", roomType = "p", exist = "true",
                userStatus = listOf(
                    VoiceUserStatus(username = "alice", status = "in"),
                    VoiceUserStatus(username = "bob", operation = "hangup", status = "out"),
                )),
            "alice",
        ))
        // 仍有 pending 成员（waiting）→ false：exist 未明确结束时另一视角（等待者本人视角，频道仍有他人）
        assertFalse(isGroupVoiceCallEndedForLocalUser(groupMsg, "carol"))
        // host 视角：exist 已明确结束且频道无其他 callee → true（对齐 RN 'clears local room call for host'）
        assertTrue(isGroupVoiceCallEndedForLocalUser(groupMsg, "alice"))
    }

    // ---- isVoiceUserLeftCall / isVoiceUserStillPending（groupVoiceCall.ts:55-70）----

    @Test
    fun `user left call and still pending`() {
        assertTrue(isVoiceUserLeftCall(VoiceUserStatus(operation = "hangup")))
        assertTrue(isVoiceUserLeftCall(VoiceUserStatus(operation = "reject")))
        assertFalse(isVoiceUserLeftCall(VoiceUserStatus(operation = "waiting")))
        // pending：未 left 且 status != in
        assertTrue(isVoiceUserStillPending(VoiceUserStatus(status = "out", operation = "waiting")))
        assertFalse(isVoiceUserStillPending(VoiceUserStatus(status = "in")))
        assertFalse(isVoiceUserStillPending(VoiceUserStatus(operation = "hangup", status = "out")))
    }
}
