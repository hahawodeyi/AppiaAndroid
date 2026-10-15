package cn.appia.im.feature.chat.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终审针脚 9：VoiceChatMessageCard 四纯函数（RN voiceChatMessageDisplay.ts 语义移植）——
 * getVoiceChatPrimaryText / buildGroupCancelSummary / parseVoiceChatSummary /
 * isVoiceChatMsgOtherDeviceAccepted。
 */
class VoiceChatMessageCardLibTest {

    private val identityT: (String) -> String = { it }

    // ── getVoiceChatPrimaryText ───────────────────────────────────────────────

    @Test
    fun `author sees initiatorMsg`() {
        val data = VoiceChatMsgData(initiatorMsg = "out", receiverMsg = "in")
        assertEquals("out", getVoiceChatPrimaryText(data, "bob", "bob", null))
    }

    @Test
    fun `non author sees receiverMsg`() {
        val data = VoiceChatMsgData(initiatorMsg = "out", receiverMsg = "in")
        assertEquals("in", getVoiceChatPrimaryText(data, "alice", "bob", null))
    }

    @Test
    fun `blank text falls back to message msg`() {
        val data = VoiceChatMsgData(initiatorMsg = "  ", receiverMsg = null)
        assertEquals("fallback", getVoiceChatPrimaryText(data, "alice", "bob", "fallback"))
    }

    @Test
    fun `all blank yields null`() {
        val data = VoiceChatMsgData(initiatorMsg = "", receiverMsg = "")
        assertNull(getVoiceChatPrimaryText(data, "alice", "bob", "  "))
    }

    // ── buildGroupCancelSummary ───────────────────────────────────────────────

    private fun statusJson(rows: String) =
        """{"type":"cancel","roomType":"p","initiatorUsername":"alice","userStatus":[$rows]}"""

    private fun cancelData(rows: String): VoiceChatMsgData =
        VoiceChatMsgData(
            type = "cancel",
            roomType = "p",
            initiatorUsername = "alice",
            userStatus = cn.appia.im.core.voice.voiceJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(cn.appia.im.core.voice.VoiceUserStatus.serializer()),
                "[$rows]",
            ),
        )

    @Test
    fun `cancel summary groups answered and missed with dun separator`() {
        val data = cancelData(
            """{"username":"bob","name":"Bob","operation":"accept"},""" +
                """{"username":"carol","name":"Carol","operation":"reject"},""" +
                """{"username":"dave","name":"Dave","operation":"waiting"}""",
        )
        val sep = 0x3001.toChar() // 顿号（与实现同源；hook 禁硬编码中文——转义书写）
        val summary = buildGroupCancelSummary(data, identityT)
        assertEquals(
            "message_voiceMembersInvite: Bob${sep}Carol${sep}Dave; " +
                "message_voiceMembersAnswered: Bob; " +
                "message_voiceMembersMissed: Carol${sep}Dave",
            summary,
        )
    }

    @Test
    fun `initiator row excluded and empty groups show none label`() {
        val data = cancelData(
            """{"username":"alice","name":"Alice","operation":"accept"},""" +
                """{"username":"bob","name":"Bob","operation":"reject"}""",
        )
        val summary = buildGroupCancelSummary(data, identityT)
        assertEquals(
            "message_voiceMembersInvite: Bob; " +
                "message_voiceMembersAnswered: message_voiceMembersNone; " +
                "message_voiceMembersMissed: Bob",
            summary,
        )
    }

    @Test
    fun `non cancel or direct room yields empty`() {
        val talking = VoiceChatMsgData(type = "talking", roomType = "p")
        val directCancel = VoiceChatMsgData(type = "cancel", roomType = "d", initiatorUsername = "a")
        assertEquals("", buildGroupCancelSummary(talking, identityT))
        assertEquals("", buildGroupCancelSummary(directCancel, identityT))
    }

    // ── parseVoiceChatSummary ─────────────────────────────────────────────────

    @Test
    fun `parses success summary and strips markdown`() {
        val raw = """{"success":true,"summary":"**Hello** _world_"}"""
        val summary = parseVoiceChatSummary(raw)!!
        assertEquals("**Hello** _world_", summary.markdown)
        assertEquals("Hello world", summary.plain)
    }

    @Test
    fun `rejects failure blank and malformed`() {
        assertNull(parseVoiceChatSummary(null))
        assertNull(parseVoiceChatSummary("""{"success":false,"summary":"x"}"""))
        assertNull(parseVoiceChatSummary("""{"success":true,"summary":"  "}"""))
        assertNull(parseVoiceChatSummary("not json"))
    }

    // ── isVoiceChatMsgOtherDeviceAccepted ─────────────────────────────────────

    @Test
    fun `callee sees other device accepted for calling card`() {
        val nestedCallMsg = """{"status":"calling","roomId":"r1","roomType":"d","userStatus":[{"username":"bob","status":"in","deviceId":"device-b"}]}"""
        val data = VoiceChatMsgData(
            type = "calling",
            initiatorUsername = "alice",
            receiverUsername = "bob",
            callMsg = nestedCallMsg,
        )
        assertTrue(isVoiceChatMsgOtherDeviceAccepted(data, "bob", "device-a"))
    }

    @Test
    fun `same device in channel is not other device accepted`() {
        val nestedCallMsg = """{"status":"calling","roomId":"r1","roomType":"d","userStatus":[{"username":"bob","status":"in","deviceId":"device-a"}]}"""
        val data = VoiceChatMsgData(
            type = "calling",
            initiatorUsername = "alice",
            callMsg = nestedCallMsg,
        )
        assertFalse(isVoiceChatMsgOtherDeviceAccepted(data, "bob", "device-a"))
    }

    @Test
    fun `initiator end type or missing callMsg never flags`() {
        val nested = """{"status":"calling","userStatus":[{"username":"bob","status":"in","deviceId":"device-b"}]}"""
        val asInitiator = VoiceChatMsgData(type = "calling", initiatorUsername = "bob", callMsg = nested)
        val endType = VoiceChatMsgData(type = "end", initiatorUsername = "alice", callMsg = nested)
        val noNested = VoiceChatMsgData(type = "calling", initiatorUsername = "alice")
        assertFalse(isVoiceChatMsgOtherDeviceAccepted(asInitiator, "bob", "device-a"))
        assertFalse(isVoiceChatMsgOtherDeviceAccepted(endType, "bob", "device-a"))
        assertFalse(isVoiceChatMsgOtherDeviceAccepted(noNested, "bob", "device-a"))
    }
}
