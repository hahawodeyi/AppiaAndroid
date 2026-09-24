package cn.appia.im.domain.presence

import androidx.compose.ui.unit.dp
import cn.appia.im.feature.chat.ui.presenceDotDiameter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * presence 判定链（RN lib/presence 各 .test 对齐）：
 * isRocketChatUserId 全规则 / resolveDirectPeerUserId / 绿点-文字态两判定分离 / 双层降级。
 */
class PresenceJudgmentsTest {

    // ---- isRocketChatUserId 全规则（RN isRocketChatUserId.test）----

    @Test
    fun `accepts meteor style ids`() {
        assertTrue(isRocketChatUserId("rB8xK9mN2pQ4sT6vW")) // 17 位 Meteor id
        assertTrue(isRocketChatUserId("652a1b3c4d5e6f00112233aa")) // ≥6 长串兜底
    }

    @Test
    fun `rejects username shaped values`() {
        assertTrue(looksLikeAppiaUsername("benfu.wei"))
        assertFalse(isRocketChatUserId("benfu.wei"))
        assertFalse(isRocketChatUserId("benfu.wei", username = "benfu.wei")) // 与 username 相同
    }

    @Test
    fun `rejects department keys and bots`() {
        assertFalse(isRocketChatUserId("EMT-0"))
        assertFalse(isRocketChatUserId("agent.bot"))
    }

    @Test
    fun `rejects short blank and null ids`() {
        assertFalse(isRocketChatUserId("ab12")) // <6
        assertFalse(isRocketChatUserId("  "))
        assertFalse(isRocketChatUserId(null))
        assertTrue(isRocketChatUserId("  rB8xK9mN2pQ4sT6vW  ")) // trim 后 17 位
    }

    // ---- resolveDirectPeerUserId（RN resolveDirectPeerUserId.test）----

    @Test
    fun `returns the other user in a 1-1 direct chat`() {
        assertEquals("other", resolveDirectPeerUserId("""["me","other"]""", "me"))
        assertEquals("other", resolveDirectPeerUserId("""["other","me"]""", "me"))
    }

    @Test
    fun `returns null for assistant only uids`() {
        assertNull(resolveDirectPeerUserId("""["me"]""", "me"))
    }

    @Test
    fun `returns null for empty or invalid json or missing current user`() {
        assertNull(resolveDirectPeerUserId("", "me"))
        assertNull(resolveDirectPeerUserId("not-json", "me"))
        assertNull(resolveDirectPeerUserId("""{"a":1}""", "me")) // 非数组
        assertNull(resolveDirectPeerUserId("""["a","b"]""", null))
    }

    // ---- 绿点 / 名片文字态两判定分离（binding #3）----

    @Test
    fun `online dot shows for online and away only`() {
        assertTrue(shouldShowOnlineDot(TUserStatus.ONLINE, "u1"))
        assertTrue(shouldShowOnlineDot(TUserStatus.AWAY, "u1"))
        assertFalse(shouldShowOnlineDot(TUserStatus.BUSY, "u1"))
        assertFalse(shouldShowOnlineDot(TUserStatus.OFFLINE, "u1"))
        assertFalse(shouldShowOnlineDot(TUserStatus.DISABLED, "u1"))
        assertFalse(shouldShowOnlineDot(TUserStatus.LOADING, "u1"))
        assertFalse(shouldShowOnlineDot(null, "u1"))
    }

    @Test
    fun `online dot hides for bots`() {
        assertFalse(shouldShowOnlineDot(TUserStatus.ONLINE, "agent.bot"))
        assertFalse(shouldShowOnlineDot(TUserStatus.ONLINE, "u1", "foo.bot"))
    }

    @Test
    fun `profile text state is online only even when dot shows away`() {
        // RN useMemberProfile :115 isOnline=(statusConnection ?? status)=='online'——仅 online；
        // 绿点 online/away：两判定分离（away 绿点亮、文字态 false）
        assertTrue(isOnlineTextStatus("online", null))
        assertTrue(isOnlineTextStatus("Online", "away")) // statusConnection 优先
        assertFalse(isOnlineTextStatus("away", null))
        assertFalse(isOnlineTextStatus("busy", "offline"))
        assertFalse(isOnlineTextStatus(null, "away"))
        assertFalse(isOnlineTextStatus(null, null))
        assertTrue(shouldShowOnlineDot(TUserStatus.AWAY) && !isOnlineTextStatus("away", null))
    }

    // ---- 双层降级（store ?? fallback）----

    @Test
    fun `store status wins over fallback when present`() {
        assertEquals(
            TUserStatus.BUSY,
            mergePresenceStatus("u1", TUserStatus.BUSY, TUserStatus.ONLINE),
        )
    }

    @Test
    fun `store miss falls back to contact status`() {
        assertEquals(TUserStatus.AWAY, mergePresenceStatus("u1", null, TUserStatus.AWAY))
        assertEquals(TUserStatus.OFFLINE, mergePresenceStatus("u1", null, TUserStatus.OFFLINE))
    }

    @Test
    fun `no effective id uses fallback only`() {
        assertEquals(TUserStatus.ONLINE, mergePresenceStatus(null, TUserStatus.BUSY, TUserStatus.ONLINE))
        assertNull(mergePresenceStatus(null, TUserStatus.BUSY, null))
    }

    // ---- pickEffectivePresenceUserId（DirectAvatar 守卫合成）----

    @Test
    fun `direct id wins when valid else resolved id`() {
        assertEquals("rB8xK9mN2pQ4sT6vW", pickEffectivePresenceUserId("rB8xK9mN2pQ4sT6vW", "benfu.wei", "resolved"))
        assertEquals("resolved", pickEffectivePresenceUserId("benfu.wei", "benfu.wei", "resolved"))
        assertEquals("resolved", pickEffectivePresenceUserId("agent.bot", null, "resolved"))
        assertNull(pickEffectivePresenceUserId(null, null, null))
    }

    // ---- 绿点直径（RN presenceDotDiameter）----

    @Test
    fun `dot diameter is max of 8 and avatar times zero point two eight`() {
        assertEquals(8.dp, presenceDotDiameter(20.dp))
        assertEquals(10.dp, presenceDotDiameter(36.dp)) // round(10.08)
        assertEquals(13.dp, presenceDotDiameter(48.dp)) // round(13.44)
    }
}
