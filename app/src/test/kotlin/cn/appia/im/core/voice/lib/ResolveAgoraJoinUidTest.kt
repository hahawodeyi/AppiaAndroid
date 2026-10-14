package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoiceChatReceiver
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.buildJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * resolveAgoraJoinUid（RN resolveAgoraJoinUid.test.ts 全用例转写 + brief 补充被叫找不到抛异常）。
 */
class ResolveAgoraJoinUidTest {

    @Test
    fun `returns host uid for initiator`() {
        // RN 'returns host uid for initiator'
        val msg = VoiceCallMsg(
            recordData = buildJsonObject { put("initiatorUid", VOICE_HOSTER_AGORA_UID.toString()) },
        )
        assertEquals(VOICE_HOSTER_AGORA_UID, resolveAgoraJoinUid(msg, "alice", asHostInitiator = true))
    }

    @Test
    fun `returns receiver uid for callee`() {
        // RN 'returns receiver uid for callee'
        val msg = VoiceCallMsg(
            recordData = buildJsonObject {
                put(
                    "receivers",
                    buildJsonArray {
                        add(buildJsonObject { put("receiver", "bob"); put("receiverUid", 123456789) })
                    },
                )
            },
        )
        assertEquals(123456789L, resolveAgoraJoinUid(msg, "bob"))
    }

    @Test
    fun `returns initiatorUid when username matches initiator`() {
        // RN 'returns initiatorUid when username matches initiator'
        val msg = VoiceCallMsg(
            recordData = buildJsonObject {
                put("initiator", "alice")
                put("initiatorUid", VOICE_HOSTER_AGORA_UID.toString())
            },
        )
        assertEquals(VOICE_HOSTER_AGORA_UID, resolveAgoraJoinUid(msg, "alice"))
    }

    @Test
    fun `prefers receiver uid over initiatorUid for non-initiator`() {
        // RN 'prefers receiver uid over initiatorUid for non-initiator'
        val msg = VoiceCallMsg(
            recordData = buildJsonObject {
                put("initiator", "alice")
                put("initiatorUid", VOICE_HOSTER_AGORA_UID.toString())
                put(
                    "receivers",
                    buildJsonArray {
                        add(buildJsonObject { put("receiver", "bob"); put("receiverUid", 123456789) })
                    },
                )
            },
        )
        assertEquals(123456789L, resolveAgoraJoinUid(msg, "bob"))
    }

    @Test
    fun `falls back to top receivers when recordData receivers missing`() {
        // brief：recordData 缺失回退顶层 receivers
        val msg = VoiceCallMsg(
            receivers = listOf(VoiceChatReceiver(receiver = "bob", name = "Bob", receiverUid = 42)),
        )
        assertEquals(42L, resolveAgoraJoinUid(msg, "bob"))
    }

    @Test
    fun `throws when callee uid not found anywhere`() {
        // brief：被叫找不到 receiverUid → VoiceUidResolveException（绝不回退 8888）
        val msg = VoiceCallMsg(
            recordData = buildJsonObject {
                put("initiator", "alice")
                put("initiatorUid", VOICE_HOSTER_AGORA_UID.toString())
            },
        )
        try {
            resolveAgoraJoinUid(msg, "stranger")
            fail("expected VoiceUidResolveException")
        } catch (e: VoiceUidResolveException) {
            assertEquals("voice_agora_uid_missing", e.message)
        }
    }

    @Test
    fun `asHostInitiator falls back to 8888 when initiatorUid missing`() {
        // brief：asHostInitiator=true → recordData.initiatorUid ?? 8888
        assertEquals(VOICE_HOSTER_AGORA_UID, resolveAgoraJoinUid(VoiceCallMsg(), "alice", asHostInitiator = true))
        assertEquals(VOICE_HOSTER_AGORA_UID, resolveAgoraJoinUid(null, "alice", asHostInitiator = true))
    }
}
