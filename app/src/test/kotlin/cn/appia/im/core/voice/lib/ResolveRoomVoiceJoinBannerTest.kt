package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoiceUserStatus
import cn.appia.im.core.voice.voiceJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * shouldShowRoomVoiceJoinBanner / pickRicherRoomCallMsgRaw / resolveRoomVoiceOnCallStatus
 * （RN resolveRoomVoiceJoinBanner.test.ts 全用例；countUsersInVoiceChannel 用例已在 GroupVoiceCallTest 覆盖）。
 */
class ResolveRoomVoiceJoinBannerTest {

    private fun msg(
        status: String,
        roomType: String? = null,
        exist: JsonExist? = null,
        channelId: String? = null,
        userStatus: List<VoiceUserStatus>? = null,
    ): VoiceCallMsg {
        val recordJson = buildString {
            append("{")
            if (exist != null) append(exist.toJson())
            append("\"recordId\":\"r1\"")
            if (channelId != null) append(",\"channelId\":\"$channelId\"")
            append("}")
        }
        return VoiceCallMsg(
            status = status,
            roomId = "r1",
            roomType = roomType,
            org = "bitmain",
            receivers = emptyList(),
            userStatus = userStatus,
            recordData = voiceJson.parseToJsonElement(recordJson) as JsonObject,
        )
    }

    /** RN 夹具 exist 双形态（boolean / string）。 */
    private class JsonExist(private val raw: String) {
        fun toJson(): String = "\"exist\":$raw,"
    }

    private fun existBool(v: Boolean) = JsonExist(v.toString())
    private fun existStr(v: String) = JsonExist("\"$v\"")

    private val groupCall = msg(
        status = "talking", roomType = "p", exist = existBool(false),
        userStatus = listOf(
            VoiceUserStatus(username = "alice", status = "in"),
            VoiceUserStatus(username = "bob", operation = "hangup", status = "out"),
            VoiceUserStatus(username = "carol", status = "out", operation = "waiting"),
        ),
    )

    // ---- shouldShowRoomVoiceJoinBanner ----

    @Test
    fun `shows join banner when answered on another device`() {
        // RN 'shows join banner when answered on another device'
        val m = msg(
            status = "talking", roomType = "p", exist = existBool(false),
            userStatus = listOf(
                VoiceUserStatus(username = "alice", status = "in"),
                VoiceUserStatus(username = "bob", status = "in", operation = "accept", deviceId = "device-a"),
            ),
        )
        assertTrue(shouldShowRoomVoiceJoinBanner(m, "bob", "p", null, null))
    }

    @Test
    fun `shows join banner even when self is already in channel on this device`() {
        // RN 'shows join banner even when self is already in channel on this device'
        val m = msg(
            status = "talking", roomType = "p", exist = existBool(false),
            userStatus = listOf(
                VoiceUserStatus(username = "alice", status = "in"),
                VoiceUserStatus(username = "bob", status = "in", operation = "accept", deviceId = "device-b"),
            ),
        )
        assertTrue(shouldShowRoomVoiceJoinBanner(m, "bob", "p", null, null))
    }

    @Test
    fun `shows join banner when local status is end but host still in channel`() {
        // RN 'shows join banner when local status is end but host still in channel'
        val m = msg(
            status = "end", roomType = "p", exist = existBool(false),
            userStatus = listOf(
                VoiceUserStatus(username = "alice", status = "in"),
                VoiceUserStatus(username = "bob", operation = "hangup", status = "out"),
            ),
        )
        assertTrue(shouldShowRoomVoiceJoinBanner(m, "bob", "p", null, null))
    }

    @Test
    fun `shows join banner for callee who left while host still in`() {
        // RN 'shows join banner for callee who left while host still in'
        assertTrue(shouldShowRoomVoiceJoinBanner(groupCall, "bob", "p", null, null))
    }

    @Test
    fun `shows join banner for callee still ringing when not in channel`() {
        // RN 'shows join banner for callee still ringing when not in channel'
        val ringing = msg(
            status = "talking", roomType = "p", exist = existBool(true),
            userStatus = listOf(
                VoiceUserStatus(username = "alice", status = "in"),
                VoiceUserStatus(username = "bob", status = "out", operation = "waiting"),
            ),
        )
        assertTrue(shouldShowRoomVoiceJoinBanner(ringing, "bob", "p", null, null))
    }

    @Test
    fun `shows join banner during initial group calling phase when not in channel`() {
        // RN 'shows join banner during initial group calling phase when not in channel'
        val calling = msg(
            status = "calling", roomType = "p", exist = existBool(true),
            userStatus = listOf(
                VoiceUserStatus(username = "alice", status = "in"),
                VoiceUserStatus(username = "bob", status = "out"),
            ),
        )
        assertTrue(shouldShowRoomVoiceJoinBanner(calling, "bob", "p", null, null))
    }

    @Test
    fun `shows join banner when callee left and operation field is missing`() {
        // RN 'shows join banner when callee left and operation field is missing'
        val flaky = msg(
            status = "talking", roomType = "p", exist = existBool(true),
            userStatus = listOf(
                VoiceUserStatus(username = "alice", status = "in"),
                VoiceUserStatus(username = "bob", status = "out"),
            ),
        )
        assertTrue(shouldShowRoomVoiceJoinBanner(flaky, "bob", "p", null, null))
    }

    @Test
    fun `hides join banner when nobody in channel`() {
        // RN 'hides join banner when nobody in channel'
        val ended = groupCall.copy(
            userStatus = listOf(VoiceUserStatus(username = "bob", operation = "hangup", status = "out")),
        )
        assertFalse(shouldShowRoomVoiceJoinBanner(ended, "bob", "p", null, null))
    }

    @Test
    fun `shows join banner when user is not listed in userStatus`() {
        // RN 'shows join banner when user is not listed in userStatus'
        val ongoing = msg(
            status = "talking", roomType = "p", exist = existBool(true),
            userStatus = listOf(VoiceUserStatus(username = "alice", status = "in")),
        )
        assertTrue(shouldShowRoomVoiceJoinBanner(ongoing, "bob", "p", null, null))
    }

    @Test
    fun `hides join banner when callMsg is null`() {
        // RN shouldShowRoomVoiceJoinBanner.ts:56-58 !callMsg → false
        assertFalse(shouldShowRoomVoiceJoinBanner(null, "bob", "p", null, null))
    }

    @Test
    fun `hides join banner in direct room`() {
        // RN shouldShowRoomVoiceJoinBanner.ts:64-66 非群聊房间
        assertFalse(shouldShowRoomVoiceJoinBanner(groupCall, "bob", "d", null, null))
    }

    // ---- resolveRoomVoiceOnCallStatus ----

    @Test
    fun `resolves onCallStatus true when server sent false`() {
        // RN 'resolves onCallStatus true when server sent false'
        assertTrue(
            resolveRoomVoiceOnCallStatus(voiceJson.encodeToString(groupCall), "bob", "p", false, null),
        )
    }

    @Test
    fun `resolves onCallStatus true for mid-call invite without roomType in callMsg`() {
        // RN 'resolves onCallStatus true for mid-call invite without roomType in callMsg'
        val invite = msg(
            status = "talking", exist = existBool(false), channelId = "ch-1",
            userStatus = listOf(
                VoiceUserStatus(username = "alice", status = "in"),
                VoiceUserStatus(username = "bob", status = "out", operation = "waiting"),
            ),
        )
        assertTrue(
            resolveRoomVoiceOnCallStatus(voiceJson.encodeToString(invite), "bob", "p", false, null),
        )
    }

    @Test
    fun `onCallStatus falls back to server value when raw unparseable`() {
        // resolveRoomVoiceJoinBanner.ts:139-142 parse 失败 → serverOnCallStatus ?? false
        assertTrue(resolveRoomVoiceOnCallStatus(null, "bob", "p", true, null))
        assertFalse(resolveRoomVoiceOnCallStatus(null, "bob", "p", null, null))
        assertFalse(resolveRoomVoiceOnCallStatus("not-json{", "bob", "p", false, null))
    }

    // ---- pickRicherRoomCallMsgRaw ----

    @Test
    fun `prefers talking callMsg over local end when in-channel count matches`() {
        // RN 'prefers talking callMsg over local end when in-channel count matches'
        val dbRaw = voiceJson.encodeToString(
            msg(status = "end", roomType = "p", exist = existBool(false), userStatus = listOf(inUser("alice"))),
        )
        val richer = msg(
            status = "talking", roomType = "p", exist = existBool(false), channelId = "ch-1",
            userStatus = listOf(inUser("alice")),
        )
        assertEquals(voiceJson.encodeToString(richer), pickRicherRoomCallMsgRaw(dbRaw, richer))
    }

    @Test
    fun `prefers store callMsg when richness score ties`() {
        // RN 'prefers store callMsg when richness score ties'
        val dbRaw = voiceJson.encodeToString(
            msg(
                status = "talking", roomType = "p", exist = existBool(true),
                userStatus = listOf(
                    VoiceUserStatus(username = "alice", status = "in"),
                    VoiceUserStatus(username = "bob", operation = "hangup", status = "out"),
                ),
            ),
        )
        val storeMsg = msg(
            status = "talking", roomType = "p", exist = existBool(true),
            userStatus = listOf(VoiceUserStatus(username = "alice", status = "in")),
        )
        assertEquals(voiceJson.encodeToString(storeMsg), pickRicherRoomCallMsgRaw(dbRaw, storeMsg))
    }

    @Test
    fun `prefers callMsg with channelId when merging`() {
        // RN 'prefers callMsg with channelId when merging'
        val dbRaw = voiceJson.encodeToString(
            msg(
                status = "talking", roomType = "p", exist = existBool(false),
                userStatus = listOf(
                    VoiceUserStatus(username = "alice", status = "in"),
                    VoiceUserStatus(username = "bob", operation = "hangup", status = "out"),
                ),
            ),
        )
        val richer = msg(
            status = "talking", roomType = "p", exist = existBool(false), channelId = "ch-1",
            userStatus = listOf(
                VoiceUserStatus(username = "alice", status = "in"),
                VoiceUserStatus(username = "bob", operation = "hangup", status = "out"),
            ),
        )
        assertEquals(voiceJson.encodeToString(richer), pickRicherRoomCallMsgRaw(dbRaw, richer))
    }

    @Test
    fun `pickRaw edge cases db only store only both null`() {
        // resolveRoomVoiceJoinBanner.ts:100-108：单边缺失直取该边
        val dbRaw = voiceJson.encodeToString(msg(status = "end", roomType = "p"))
        assertEquals(dbRaw, pickRicherRoomCallMsgRaw(dbRaw, null))
        assertNull(pickRicherRoomCallMsgRaw(null, null))
        val storeMsg = msg(status = "talking", roomType = "p")
        assertEquals(voiceJson.encodeToString(storeMsg), pickRicherRoomCallMsgRaw(null, storeMsg))
    }

    private fun inUser(name: String) = VoiceUserStatus(username = name, status = "in")
}
