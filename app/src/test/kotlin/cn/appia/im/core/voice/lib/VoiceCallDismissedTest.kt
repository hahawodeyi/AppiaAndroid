package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoiceUserStatus
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * voiceCallDismissed（RN voiceCallDismissed.test.ts 全用例转写）。
 */
class VoiceCallDismissedTest {

    private lateinit var store: VoiceCallDismissedStore

    @Before
    fun setUp() {
        store = VoiceCallDismissedStore()
    }

    /** RN baseCallMsg 夹具。 */
    private fun baseMsg(): VoiceCallMsg = VoiceCallMsg(
        status = "calling",
        recordData = buildJsonObject {
            put("exist", "true")
            put("recordId", "rec-1")
            put("channelId", "ch-1")
            put("initiator", "alice")
        },
        userStatus = listOf(VoiceUserStatus(username = "bob", status = "out", operation = "waiting")),
    )

    @Test
    fun `marks and checks dismissed recordId`() {
        // RN 'marks and checks dismissed recordId'：空白 trim 归一
        store.markVoiceCallDismissed(" rec-1 ")
        assertTrue(store.isVoiceCallDismissed("rec-1"))
        assertFalse(store.isVoiceCallDismissed("rec-2"))
    }

    @Test
    fun `clears dismissed recordId`() {
        // RN 'clears dismissed recordId'
        store.markVoiceCallDismissed("rec-1")
        store.clearVoiceCallDismissed("rec-1")
        assertFalse(store.isVoiceCallDismissed("rec-1"))
    }

    @Test
    fun `blocks only when invite snapshot matches dismiss moment`() {
        // RN 'blocks only when invite snapshot matches dismiss moment'
        val initial = baseMsg()
        store.markVoiceCallDismissed("rec-1", initial, "bob")
        val sameSnapshot = store.buildVoiceInviteSnapshot(initial, "bob")
        assertTrue(store.shouldBlockDismissedIncoming("rec-1", sameSnapshot))

        val reinvite = initial.copy(
            status = "talking",
            userStatus = listOf(
                VoiceUserStatus(username = "alice", status = "in"),
                VoiceUserStatus(username = "bob", status = "out", operation = "waiting"),
            ),
        )
        val reinviteSnapshot = store.buildVoiceInviteSnapshot(reinvite, "bob")
        assertFalse(store.shouldBlockDismissedIncoming("rec-1", reinviteSnapshot))
        // 快照不同 → 记录被删除（后续同快照再来也放行）
        assertFalse(store.isVoiceCallDismissed("rec-1"))
    }
}
