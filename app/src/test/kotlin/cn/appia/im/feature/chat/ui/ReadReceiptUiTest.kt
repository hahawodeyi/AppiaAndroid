package cn.appia.im.feature.chat.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.network.api.FirstUnread
import cn.appia.im.core.network.api.ReadReceipt
import cn.appia.im.core.network.api.ReadReceiptUser
import cn.appia.im.core.network.api.RoomMembersGroup
import cn.appia.im.core.theme.AppiaTheme
import cn.appia.im.feature.chat.RoomMessagesUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 已读回执 + 未读横幅（T10）：明细页数据派生（已读=receipts.user / 未读=成员差集）、
 * 横幅阈值（RN useRoomUnreadBanner：count>=10 且有 firstUnread 才显示）、
 * 行内挂点判定（仅自己消息 + unread 列非空；DM 无可点图标）、RoomScreen 横幅点击消失。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReadReceiptUiTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    @get:org.junit.Rule
    val rule = createComposeRule()

    // ── 纯函数：明细页数据派生 ──

    @Test
    fun `read users drop receipts without user`() {
        val receipts = listOf(
            ReadReceipt(_id = "rc1", userId = "u1", user = ReadReceiptUser(_id = "u1", username = "alice", name = "Alice")),
            ReadReceipt(_id = "rc2", userId = "u2", user = null),
        )
        assertEquals(listOf("u1"), readUsersFromReceipts(receipts).map { it._id })
    }

    @Test
    fun `unread members flatten groups and exclude self and readers`() {
        val groups = listOf(
            RoomMembersGroup(listOf(u("me"), u("u1"), u("u2"))),
            RoomMembersGroup(listOf(u("u3"))),
        )
        val unread = unreadMembers(groups, readIds = setOf("u1", "u2"), currentUserId = "me")
        assertEquals(listOf("u3"), unread.map { it._id })
    }

    @Test
    fun `unread members with no readers keeps everyone but self`() {
        val unread = unreadMembers(listOf(RoomMembersGroup(listOf(u("me"), u("u1")))), emptySet(), "me")
        assertEquals(listOf("u1"), unread.map { it._id })
    }

    // ── 纯函数：横幅阈值（RN useRoomUnreadBanner :24：lastUnreadMsgId && unreadCount >= 10）──

    @Test
    fun `banner visible only at ten or more with target message`() {
        assertTrue(unreadBannerVisible("m1", 10))
        assertTrue(unreadBannerVisible("m1", 12))
        assertFalse(unreadBannerVisible("m1", 9))
        assertFalse(unreadBannerVisible(null, 12))
        assertFalse(unreadBannerVisible("", 12))
    }

    // ── 行内挂点（MessageRow 状态徽标旁）──

    private fun rowEntity(
        _id: String,
        unread: Boolean?,
        u: String = """{"_id":"me","username":"me","name":"Me"}""",
    ) = MessageEntity(_id = _id, rid = "r1", ts = 1_757_900_000_000.0, u = u, parse_urls = "[]", alias = "", _updated_at = 0.0, msg = "hi", unread = unread)

    private fun setRowContent(unread: Boolean?, roomType: String?, u: String = """{"_id":"me","username":"me","name":"Me"}""") {
        rule.setContent {
            AppiaTheme(isDark = false) {
                MessageRow(
                    message = rowEntity("m1", unread, u),
                    currentUserId = "me",
                    currentUsername = "me",
                    serverUrl = "https://s1",
                    token = "tok",
                    roomType = roomType,
                    onResend = {},
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `own read message renders blue read icon`() {
        setRowContent(unread = false, roomType = "c")
        rule.onNodeWithTag("qa-read-receipt-read").assertExists()
        rule.onAllNodesWithTag("qa-read-receipt-unread").assertCountEquals(0)
    }

    @Test
    fun `own unread message in channel renders clickable icon opening detail`() {
        var opened = false
        rule.setContent {
            AppiaTheme(isDark = false) {
                MessageRow(
                    message = rowEntity("m1", unread = true),
                    currentUserId = "me",
                    currentUsername = "me",
                    serverUrl = "https://s1",
                    token = "tok",
                    roomType = "c",
                    onResend = {},
                    onOpenReadReceipt = { opened = true },
                )
            }
        }
        rule.onNodeWithTag("qa-read-receipt-unread").performClick()
        assertTrue(opened)
    }

    @Test
    fun `dm room hides clickable unread icon`() {
        setRowContent(unread = true, roomType = "d")
        rule.onAllNodesWithTag("qa-read-receipt-unread").assertCountEquals(0)
        rule.onAllNodesWithTag("qa-read-receipt-read").assertCountEquals(0)
    }

    @Test
    fun `others message renders no receipt`() {
        setRowContent(unread = false, roomType = "c", u = """{"_id":"bob","username":"bob","name":"Bob"}""")
        rule.onAllNodesWithTag("qa-read-receipt-read").assertCountEquals(0)
        rule.onAllNodesWithTag("qa-read-receipt-unread").assertCountEquals(0)
    }

    @Test
    fun `null unread renders no receipt`() {
        setRowContent(unread = null, roomType = "c")
        rule.onAllNodesWithTag("qa-read-receipt-read").assertCountEquals(0)
        rule.onAllNodesWithTag("qa-read-receipt-unread").assertCountEquals(0)
    }

    // ── 未读横幅（RoomScreen 挂点）──

    private var messages by mutableStateOf(listOf<MessageEntity>())

    @Before
    fun resetMessages() {
        messages = listOf(rowEntity("m1", unread = null, u = """{"_id":"bob","username":"bob"}"""))
    }

    private fun setRoomContent(loader: suspend (String) -> FirstUnread?) {
        rule.setContent {
            AppiaTheme(isDark = false) {
                RoomScreen(
                    rid = "r1",
                    title = "dev",
                    state = RoomMessagesUiState(rid = "r1", roomType = "c", messages = messages),
                    currentUserId = "me",
                    currentUsername = "me",
                    serverUrl = "https://s1",
                    token = "tok",
                    draftController = null,
                    onSend = {},
                    onResend = {},
                    onBack = {},
                    onLoadEarlier = {},
                    loadFirstUnread = loader,
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `banner shows at threshold and hides on tap`() {
        setRoomContent(loader = { FirstUnread(success = true, messageId = "m1", unread = 12) })
        rule.onNodeWithTag("qa-unread-messages-banner").assertExists()
        rule.onNodeWithTag("qa-unread-messages-banner").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag("qa-unread-messages-banner").assertCountEquals(0)
    }

    @Test
    fun `banner absent below threshold`() {
        setRoomContent(loader = { FirstUnread(success = true, messageId = "m1", unread = 9) })
        rule.onAllNodesWithTag("qa-unread-messages-banner").assertCountEquals(0)
    }

    @Test
    fun `banner absent when load fails`() {
        setRoomContent(loader = { null }) // 请求失败：无横幅（RN catch 静默）
        rule.onAllNodesWithTag("qa-unread-messages-banner").assertCountEquals(0)
    }
}

private fun u(_id: String) = ReadReceiptUser(_id = _id, username = _id, name = _id)
