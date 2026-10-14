package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoiceChatHoster
import cn.appia.im.core.voice.VoiceChatReceiver
import cn.appia.im.core.voice.voiceJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * isExpectedDirectVoiceRemoteUid / isExpectedOutgoingRemoteUid
 * （RN isExpectedDirectVoiceRemoteUid.test.ts + isExpectedOutgoingRemoteUid.test.ts 全用例）。
 */
class ExpectedRemoteUidTest {

    private val hoster = VoiceChatHoster(
        roomId = "room-1", roomType = "d",
        receivers = listOf(VoiceChatReceiver(receiver = "bob", name = "Bob", receiverUid = 42L)),
    )

    private fun msg(roomType: String? = null, receiverUid: Long? = null, initiatorUid: String? = null): VoiceCallMsg {
        val record = buildJsonObject {
            if (receiverUid != null) {
                put(
                    "receivers",
                    kotlinx.serialization.json.buildJsonArray {
                        add(
                            buildJsonObject {
                                put("receiver", "bob"); put("name", "Bob"); put("receiverUid", receiverUid)
                            },
                        )
                    },
                )
            }
            if (initiatorUid != null) put("initiatorUid", initiatorUid)
        }
        return VoiceCallMsg(roomType = roomType, recordData = record)
    }

    // ---- isExpectedDirectVoiceRemoteUid ----

    @Test
    fun `matches DM host peer receiverUid`() {
        // RN 'matches DM host peer receiverUid'
        assertTrue(isExpectedDirectVoiceRemoteUid(42L, msg(receiverUid = 42L), hoster))
    }

    @Test
    fun `rejects unexpected uid for DM host`() {
        // RN 'rejects unexpected uid for DM host'
        assertFalse(isExpectedDirectVoiceRemoteUid(99L, msg(), hoster))
    }

    @Test
    fun `matches DM callee initiatorUid`() {
        // RN 'matches DM callee initiatorUid'
        assertTrue(
            isExpectedDirectVoiceRemoteUid(
                VOICE_HOSTER_AGORA_UID,
                msg(roomType = "d", initiatorUid = VOICE_HOSTER_AGORA_UID.toString()),
                null,
            ),
        )
    }

    @Test
    fun `rejects unexpected uid for DM callee`() {
        // RN 'rejects unexpected uid for DM callee'
        assertFalse(
            isExpectedDirectVoiceRemoteUid(
                99L,
                msg(roomType = "d", initiatorUid = VOICE_HOSTER_AGORA_UID.toString()),
                null,
            ),
        )
    }

    @Test
    fun `allows any uid in group room`() {
        // isExpectedDirectVoiceRemoteUid.ts:24-26 非单聊 → true
        assertTrue(isExpectedDirectVoiceRemoteUid(99L, msg(roomType = "p"), hoster))
    }

    @Test
    fun `callee falls back to hoster uid when initiatorUid missing`() {
        // isExpectedDirectVoiceRemoteUid.ts:30-32 parseUid ?? VOICE_HOSTER_AGORA_UID
        assertTrue(isExpectedDirectVoiceRemoteUid(VOICE_HOSTER_AGORA_UID, msg(roomType = "d"), null))
        assertFalse(isExpectedDirectVoiceRemoteUid(99L, msg(roomType = "d"), null))
    }

    // ---- isExpectedOutgoingRemoteUid ----

    @Test
    fun `outgoing matches DM peer receiverUid`() {
        // RN 'matches DM peer receiverUid'
        assertTrue(isExpectedOutgoingRemoteUid(42L, msg(receiverUid = 42L), hoster))
    }

    @Test
    fun `outgoing rejects unexpected uid in DM`() {
        // RN 'rejects unexpected uid in DM'
        assertFalse(isExpectedOutgoingRemoteUid(99L, msg(), hoster))
    }

    @Test
    fun `outgoing allows any uid for group host`() {
        // RN 'allows any uid for group host'
        assertTrue(
            isExpectedOutgoingRemoteUid(
                99L,
                msg(roomType = "p"),
                VoiceChatHoster(roomId = "g", roomType = "p", receivers = emptyList()),
            ),
        )
    }

    @Test
    fun `outgoing allows any uid without hoster`() {
        // isExpectedOutgoingRemoteUid.ts:14 !hoster → true
        assertTrue(isExpectedOutgoingRemoteUid(99L, msg(receiverUid = 42L), null))
    }

    @Test
    fun `outgoing allows any uid when receivers count not one`() {
        // isExpectedOutgoingRemoteUid.ts:18-21 receivers.length !== 1 → true
        // （callMsg.recordData.receivers 为 null 才回退 hoster.receivers）
        val multi = VoiceChatHoster(
            roomId = "room-1", roomType = "d",
            receivers = listOf(
                VoiceChatReceiver(receiver = "bob", name = "Bob", receiverUid = 42L),
                VoiceChatReceiver(receiver = "carol", name = "Carol", receiverUid = 43L),
            ),
        )
        assertTrue(isExpectedOutgoingRemoteUid(99L, msg(), multi))
    }
}
