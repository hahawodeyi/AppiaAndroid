package cn.appia.im.domain.voice

import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoiceChatHoster
import cn.appia.im.core.voice.VoiceChatReceiver
import cn.appia.im.core.voice.VoicePhase
import cn.appia.im.core.voice.VoiceUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * brief Step 1：update 原子变换并发射；idleState 全字段重置。
 * 并发 update 安全（synchronized）—— Controller 多协程写入口。
 */
class VoiceSessionStoreTest {

    private val hoster = VoiceChatHoster(roomId = "r1", roomType = "d", receivers = listOf(VoiceChatReceiver("u2")))
    private val callMsg = VoiceCallMsg(status = "waiting", roomId = "r1")
    private val user = VoiceUser(id = "u1", uid = "1001", name = "Alice")

    private fun mutateAll(state: VoiceSessionState) = state.copy(
        phase = VoicePhase.InCall,
        hoster = this@VoiceSessionStoreTest.hoster,
        callMsg = this@VoiceSessionStoreTest.callMsg,
        recordId = "rec-1",
        channelId = "ch-1",
        rid = "rid-1",
        muteLocal = true,
        speakerOn = true,
        users = listOf(user),
        hadRemoteParticipant = true,
        agoraLocalUid = 1001L,
        mediaRecordingActive = true,
        uiMinimized = true,
        incomingUiExpanded = true,
        otherIncoming = OtherIncoming(callMsg, "rid-other"),
        otherIncomingAlertType = 3,
        outgoingHint = "dialing",
        sessionGeneration = 7,
    )

    /** brief: update transforms state atomically and emits。 */
    @Test
    fun `update transforms state atomically and emits`() {
        val store = VoiceSessionStore()
        assertEquals(VoiceSessionState(), store.state.value)

        store.update { it.copy(phase = VoicePhase.OutgoingRinging, recordId = "rec-1") }

        assertEquals(VoicePhase.OutgoingRinging, store.state.value.phase)
        assertEquals("rec-1", store.state.value.recordId)

        // 同值不重发（StateFlow 语义）：identity transform 后 value 不变
        store.update { it }
        assertEquals(VoicePhase.OutgoingRinging, store.state.value.phase)
    }

    /** brief: idleState resets all fields。 */
    @Test
    fun `idleState resets all fields`() {
        val store = VoiceSessionStore()
        store.update { mutateAll(it) }
        assertEquals(VoicePhase.InCall, store.state.value.phase)

        val idle = store.idleState()

        assertEquals(VoiceSessionState(), idle)
        assertEquals(VoicePhase.Idle, idle.phase)
        assertNull(idle.hoster)
        assertNull(idle.callMsg)
        assertNull(idle.recordId)
        assertNull(idle.channelId)
        assertNull(idle.rid)
        assertFalseOrZero(idle)
    }

    /** RN :150-153 VoiceOtherIncoming 字段透传。 */
    @Test
    fun `otherIncoming carries callMsg and rid`() {
        val other = OtherIncoming(callMsg = callMsg, rid = "rid-other")
        assertEquals(callMsg, other.callMsg)
        assertEquals("rid-other", other.rid)
    }

    private fun assertFalseOrZero(idle: VoiceSessionState) {
        assertTrue(!idle.muteLocal)
        assertTrue(!idle.speakerOn)
        assertTrue(idle.users.isEmpty())
        assertTrue(!idle.hadRemoteParticipant)
        assertNull(idle.agoraLocalUid)
        assertTrue(!idle.mediaRecordingActive)
        assertTrue(!idle.uiMinimized)
        assertTrue(!idle.incomingUiExpanded)
        assertNull(idle.otherIncoming)
        assertNull(idle.otherIncomingAlertType)
        assertNull(idle.outgoingHint)
        assertEquals(0, idle.sessionGeneration)
    }
}
