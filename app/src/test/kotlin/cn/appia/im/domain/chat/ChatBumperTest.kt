package cn.appia.im.domain.chat

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.database.DatabaseManager
import cn.appia.im.core.database.entity.ChatEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * tSearch bump（RN src/lib/chat/bumpChatFromGlobalSearch.test.ts + 行为源旧版
 * RoomsListView.onSearchPressItem）：
 * - bump 写 `tSearch` + `room_updated_at` 同毫秒（本机时钟）
 * - 空白 rid / 行不存在 → false 不写
 * - 进房 bump + 重试 bump：DDP 整行覆盖清掉 tSearch 后被重试恢复（覆盖防护）
 * - 离房：取消未触发重试 + 补一次 bump
 *
 * 定时竞态一律轮询断言（[awaitTSearch]，NotifyUserPersistenceTest 同款约定）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatBumperTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: DatabaseManager
    private var clock = 1_000L
    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        db = DatabaseManager(context)
        db.switchDatabase("https://chat.example.com")
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        db.resetAll()
    }

    private fun bumper(retryDelayMs: Long = 50): ChatBumper {
        val scope = CoroutineScope(Dispatchers.IO)
        scopes.add(scope)
        return ChatBumper(db.active, scope, now = { clock }, retryDelayMs = retryDelayMs)
    }

    private fun insertRow(rid: String): ChatEntity = ChatEntity(
        _id = rid, rid = rid, f = false, t = "c", ts = 0.0, ls = 0.0,
        name = "room", fname = "", open = true, alert = false,
        unread = 0.0, user_mentions = 0.0, group_mentions = 0.0,
        room_updated_at = 0.0, ro = false, archived = false,
        auto_translate_language = "", team_id = "",
    ).also { runBlocking { db.active.chatDao().insert(it) } }

    /** 轮询等待 tSearch 到达期望值（Room DAO 走 IO 线程）。 */
    private suspend fun awaitTSearch(rid: String, expect: Double, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (db.active.chatDao().getById(rid)?.tSearch == expect) return
            delay(50)
        }
        fail("tSearch of $rid never reached $expect (now ${db.active.chatDao().getById(rid)?.tSearch})")
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)

    // ---- 单次写 ----

    @Test
    fun `bump writes tSearch and room_updated_at with same millis`() = runBlocking {
        insertRow("rid-1")

        val ok = ChatBumper(db.active, this, now = { 1_726_000_000_000L }).bump("rid-1")

        assertTrue(ok)
        val row = db.active.chatDao().getById("rid-1")!!
        assertEquals(1_726_000_000_000.0, row.tSearch!!, 0.0)
        assertEquals(1_726_000_000_000.0, row.room_updated_at, 0.0)
    }

    @Test
    fun `bump returns false on blank rid or missing row without writing`() = runBlocking {
        insertRow("rid-2")

        assertFalse(ChatBumper(db.active, this).bump("   "))
        assertFalse(ChatBumper(db.active, this).bump("missing"))

        // 既有行不被触碰
        val row = db.active.chatDao().getById("rid-2")!!
        assertEquals(null, row.tSearch)
        assertEquals(0.0, row.room_updated_at, 0.0)
    }

    // ---- 进房双 bump / DDP 覆盖防护 ----

    @Test
    fun `enterRoom bumps then retry restores tSearch after DDP overwrite`() = runBlocking {
        insertRow("rid-3")
        // 重试窗 400ms：首轮 await（约 50ms 内返回）+ 覆盖写完成后重试仍未到期，时序确定
        val b = bumper(retryDelayMs = 400)

        clock = 1_000
        b.enterRoom("rid-3")
        awaitTSearch("rid-3", 1_000.0)

        // 模拟 DDP rooms-changed 整行覆盖清掉本地 tSearch（ChatMerger max 之外的直接写路径）
        val clobbered = db.active.chatDao().getById("rid-3")!!.copy(tSearch = 0.0)
        db.active.chatDao().update(clobbered)

        clock = 2_000
        awaitTSearch("rid-3", 2_000.0, timeoutMs = 5_000) // 400ms 重试恢复
    }

    @Test
    fun `leaveRoom cancels pending retry and bumps once more`() = runBlocking {
        insertRow("rid-4")
        // 重试窗 600ms：离房时重试必然未触发（首轮 await 远小于窗口），取消语义可断言
        val b = bumper(retryDelayMs = 600)

        clock = 1_000
        b.enterRoom("rid-4")
        awaitTSearch("rid-4", 1_000.0)

        db.active.chatDao().update(db.active.chatDao().getById("rid-4")!!.copy(tSearch = 0.0))

        clock = 1_500
        b.leaveRoom("rid-4")
        awaitTSearch("rid-4", 1_500.0)

        // 重试已被取消：过了重试窗口后仍是离房 bump 值，不被旧时钟回写
        delay(900)
        assertEquals(1_500.0, db.active.chatDao().getById("rid-4")!!.tSearch!!, 0.0)
    }

    // ---- RN 参数对齐 ----

    @Test
    fun `default retry delay is 800ms matching RN setTimeout`() {
        assertEquals(800L, ChatBumper.RETRY_DELAY_MS)
    }
}
