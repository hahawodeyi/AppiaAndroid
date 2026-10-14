package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoiceChatReceiver
import cn.appia.im.core.voice.VoiceUser
import cn.appia.im.core.voice.VoiceUserStatus
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * mapCallMsgToVoiceUsers（RN mapCallMsgToVoiceUsers.test.ts 全用例）+
 * applyAgoraRtcUserState（RN applyAgoraRtcUserState.test.ts 全用例）。
 */
class UsersMappingTest {

    // ---- mapCallMsgToVoiceUsers（RN mapCallMsgToVoiceUsers.test.ts）----

    @Test
    fun `maps userStatus when present`() {
        // RN 'maps userStatus when present'
        val msg = VoiceCallMsg(
            userStatus = listOf(
                VoiceUserStatus(username = "alice", name = "Alice", status = "in"),
                VoiceUserStatus(username = "bob", name = "Bob", status = "out"),
            ),
        )
        assertEquals(2, mapCallMsgToVoiceUsers(msg).size)
        assertEquals("out", mapCallMsgToVoiceUsers(msg)[1].status)
    }

    @Test
    fun `fills uid from recordData receivers when userStatus omits uid`() {
        // RN 'fills uid from recordData receivers when userStatus omits uid'
        val msg = VoiceCallMsg(
            userStatus = listOf(VoiceUserStatus(username = "bob", name = "Bob", status = "in")),
            recordData = buildJsonObject {
                put(
                    "receivers",
                    buildJsonArray {
                        add(buildJsonObject { put("receiver", "bob"); put("name", "Bob"); put("receiverUid", 12001) })
                    },
                )
            },
            receivers = emptyList(),
        )
        assertEquals("12001", mapCallMsgToVoiceUsers(msg)[0].uid)
    }

    @Test
    fun `maps operation from userStatus`() {
        // RN 'maps operation from userStatus'
        val msg = VoiceCallMsg(
            userStatus = listOf(VoiceUserStatus(username = "bob", status = "out", operation = "waiting")),
        )
        assertEquals("waiting", mapCallMsgToVoiceUsers(msg)[0].operation)
    }

    @Test
    fun `falls back to initiator and receivers`() {
        // RN 'falls back to initiator and receivers'
        val msg = VoiceCallMsg(
            recordData = buildJsonObject {
                put("initiator", "alice")
                put("initiatorName", "Alice")
                put(
                    "receivers",
                    buildJsonArray {
                        add(buildJsonObject { put("receiver", "bob"); put("name", "Bob"); put("status", "out") })
                    },
                )
            },
            receivers = emptyList(),
        )
        val users = mapCallMsgToVoiceUsers(msg)
        assertTrue(users.any { it.username == "alice" })
        assertTrue(users.any { it.username == "bob" })
    }

    @Test
    fun `returns empty for null callMsg`() {
        // RN `if (!callMsg) return []`
        assertTrue(mapCallMsgToVoiceUsers(null).isEmpty())
    }

    @Test
    fun `initiator uid attached from initiatorUid map`() {
        // buildReceiverUidByUsername：initiator+initiatorUid 入表，fallback 构造的 initiator 用户补挂 uid
        val msg = VoiceCallMsg(
            recordData = buildJsonObject {
                put("initiator", "alice")
                put("initiatorUid", "8888")
                put(
                    "receivers",
                    buildJsonArray {
                        add(buildJsonObject { put("receiver", "bob"); put("name", "Bob"); put("receiverUid", 12001) })
                    },
                )
            },
            receivers = emptyList(),
        )
        val users = mapCallMsgToVoiceUsers(msg)
        assertEquals("8888", users.first { it.username == "alice" }.uid)
        assertEquals("12001", users.first { it.username == "bob" }.uid)
    }

    // ---- applyAgoraRtcUserState（RN applyAgoraRtcUserState.test.ts）----

    @Test
    fun `marks users as speaking from volume speakers`() {
        // RN 'marks users as speaking from volume speakers'（uid=0 归本端 8888）
        val users = listOf(
            VoiceUser(username = "local", uid = "8888", isSpeaking = false),
            VoiceUser(username = "b", uid = "200", isSpeaking = false),
        )
        val next = applyAgoraVolumeToUsers(
            users,
            listOf(AgoraVolumeSpeaker(uid = 0, volume = 10), AgoraVolumeSpeaker(uid = 200, volume = 5)),
            8888L,
        )
        assertTrue(next[0].isSpeaking!!)
        assertTrue(next[1].isSpeaking!!)
    }

    @Test
    fun `clears speaking when volume is zero`() {
        // RN 'clears speaking when volume is zero'
        val users = listOf(VoiceUser(username = "a", uid = "100", isSpeaking = true))
        val next = applyAgoraVolumeToUsers(users, listOf(AgoraVolumeSpeaker(uid = 100, volume = 0)), 8888L)
        assertFalse(next[0].isSpeaking!!)
    }

    @Test
    fun `patches remote mute by agora uid`() {
        // RN 'patches remote mute by agora uid'
        val users = listOf(VoiceUser(username = "b", uid = "42", isMuted = false))
        val next = applyRemoteMuteToUsers(users, 42L, true)
        assertTrue(next[0].isMuted!!)
    }

    @Test
    fun `preserves isSpeaking when callMsg refresh replaces users`() {
        // RN 'preserves isSpeaking when callMsg refresh replaces users'
        val prev = listOf(VoiceUser(username = "bob", uid = "200", isSpeaking = true, isMuted = false))
        val next = listOf(VoiceUser(username = "bob", status = "in", uid = "200"))
        val merged = mergeVoiceUsersPreservingRtcState(next, prev)
        assertTrue(merged[0].isSpeaking!!)
    }

    @Test
    fun `merge backfills uid from prev when next omits it`() {
        // mergeVoiceUsersPreservingRtcState.ts:68 `uid: user.uid ?? old.uid`
        val prev = listOf(VoiceUser(username = "bob", uid = "200", isSpeaking = true, isMuted = true))
        val next = listOf(VoiceUser(username = "bob", status = "in"))
        val merged = mergeVoiceUsersPreservingRtcState(next, prev)
        assertEquals("200", merged[0].uid)
        assertTrue(merged[0].isMuted!!)
    }
}
