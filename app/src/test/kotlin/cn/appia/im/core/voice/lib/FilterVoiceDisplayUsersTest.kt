package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceUser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * filterVoiceDisplayUsers + resolveVoiceParticipantUiState
 * （RN filterVoiceDisplayUsers.test.ts + resolveVoiceParticipantState.test.ts 全用例）。
 */
class FilterVoiceDisplayUsersTest {

    private val users = listOf(
        VoiceUser(username = "alice", name = "Alice", status = "in"),
        VoiceUser(username = "bob", name = "Bob", status = "in"),
    )

    // ---- filterVoiceDisplayUsers ----

    @Test
    fun `removes local user in direct room`() {
        // RN 'removes local user in direct room'
        assertEquals(
            listOf(VoiceUser(username = "bob", name = "Bob", status = "in")),
            filterVoiceDisplayUsers(users, "alice", "d"),
        )
    }

    @Test
    fun `keeps all users in group room`() {
        // RN 'keeps all users in group room'
        assertEquals(users, filterVoiceDisplayUsers(users, "alice", "p"))
    }

    @Test
    fun `removes hangup and reject users from group list`() {
        // RN 'removes hangup and reject users from group list'
        val groupUsers = listOf(
            VoiceUser(username = "alice", name = "Alice", status = "in"),
            VoiceUser(username = "bob", name = "Bob", status = "out", operation = "hangup"),
            VoiceUser(username = "carol", name = "Carol", status = "out", operation = "waiting"),
            VoiceUser(username = "dave", name = "Dave", status = "out", operation = "reject"),
        )
        assertEquals(
            listOf(
                VoiceUser(username = "alice", name = "Alice", status = "in"),
                VoiceUser(username = "carol", name = "Carol", status = "out", operation = "waiting"),
            ),
            filterVoiceDisplayUsers(groupUsers, "alice", "p"),
        )
    }

    @Test
    fun `keeps all users when localUsername blank`() {
        // filterVoiceDisplayUsers.ts:13-15：localUsername 空 → 不做本端过滤
        assertEquals(users, filterVoiceDisplayUsers(users, "", "d"))
        assertEquals(users, filterVoiceDisplayUsers(users, null, "d"))
    }

    // ---- resolveVoiceParticipantUiState ----

    private fun u(status: String? = null, operation: String? = null) =
        VoiceUser(username = "bob", name = "Bob", status = status, operation = operation)

    @Test
    fun `marks status in as connected`() {
        // RN 'marks status in as connected'
        val state = resolveVoiceParticipantUiState(u(status = "in"))
        assertEquals(VoiceParticipantUiKind.Connected, state.kind)
        assertFalseK(state.showWaitingOverlay)
        assertEquals("voice_participant_connected", state.labelKey)
    }

    @Test
    fun `marks waiting operation as ringing`() {
        // RN 'marks waiting operation as ringing'
        val state = resolveVoiceParticipantUiState(u(status = "out", operation = "waiting"))
        assertEquals(VoiceParticipantUiKind.Ringing, state.kind)
        assertEquals("voice_participant_ringing", state.labelKey)
        assertTrueK(state.showWaitingOverlay)
    }

    @Test
    fun `marks out without operation as dialing`() {
        // RN 'marks out without operation as dialing'
        val state = resolveVoiceParticipantUiState(u(status = "out"))
        assertEquals(VoiceParticipantUiKind.Dialing, state.kind)
        assertEquals("voice_participant_dialing", state.labelKey)
        assertTrueK(state.showWaitingOverlay)
    }

    @Test
    fun `marks reject operation`() {
        // RN 'marks reject operation'
        val state = resolveVoiceParticipantUiState(u(operation = "reject", status = "out"))
        assertEquals(VoiceParticipantUiKind.Rejected, state.kind)
        assertFalseK(state.showWaitingOverlay)
    }

    @Test
    fun `marks hangup while not in as ended`() {
        // RN 'marks hangup while not in as ended'
        val state = resolveVoiceParticipantUiState(u(operation = "hangup", status = "out"))
        assertEquals(VoiceParticipantUiKind.Ended, state.kind)
    }

    @Test
    fun `hangup while still in stays connected`() {
        // resolveVoiceParticipantState.ts:31-37：operation=hangup 且 status=in → connected（顺序在 hangup 判定后）
        val state = resolveVoiceParticipantUiState(u(operation = "hangup", status = "in"))
        assertEquals(VoiceParticipantUiKind.Connected, state.kind)
    }

    // ---- compareVoiceParticipantsByState ----

    @Test
    fun `sorts connected before ringing before dialing`() {
        // resolveVoiceParticipantState.ts:59-72 KIND_SORT_ORDER
        val dialing = u(status = "out")
        val ringing = u(status = "out", operation = "waiting")
        val connected = u(status = "in")
        assertTrueK(compareVoiceParticipantsByState(ringing, dialing) < 0)
        assertTrueK(compareVoiceParticipantsByState(connected, ringing) < 0)
        assertEquals(0, compareVoiceParticipantsByState(connected, u(status = "in")))
    }

    private fun assertTrueK(v: Boolean) = org.junit.Assert.assertTrue(v)
    private fun assertFalseK(v: Boolean) = org.junit.Assert.assertFalse(v)
}
