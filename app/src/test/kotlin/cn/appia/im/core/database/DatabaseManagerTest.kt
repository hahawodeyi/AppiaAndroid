package cn.appia.im.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.database.entity.ChatEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

// 同 SchemaParityTest：JUnit4 + Robolectric，钉 SDK 34
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = DatabaseManager(context)

    @After
    fun tearDown() {
        manager.resetAll()
    }

    @Test
    fun `normalize server mirrors db ts rules`() {
        assertEquals("chat.example.com", manager.normalizeServer("https://chat.example.com"))
        assertEquals("chat.example.com.", manager.normalizeServer("https://chat.example.com/"))
        assertEquals("chat.example.com", manager.normalizeServer(" chat.example.com "))
        assertEquals(DatabaseManager.PRELOGIN_NORMALIZED, manager.normalizeServer(""))
    }

    @Test
    fun `databaseFor caches instance per normalized server`() {
        val a = manager.databaseFor("chat.example.com")
        val b = manager.databaseFor("chat.example.com")
        assertEquals(a, b)
        assertEquals("appia_chat.example.com.db", DatabaseManager.dbNameFor("chat.example.com"))
        assertEquals("appia_prelogin.db", DatabaseManager.dbNameFor(DatabaseManager.PRELOGIN_NORMALIZED))
    }

    @Test
    fun `switchDatabase points active at new server db`() {
        val prelogin = manager.active
        val switched = manager.switchDatabase("https://chat.example.com")
        assertEquals(switched, manager.active)
        assertNotEquals(prelogin, manager.active)
    }

    @Test
    fun `resetAll closes and deletes db files`() = runTest {
        val db = manager.switchDatabase("https://chat.example.com")
        db.chatDao().insert(sampleChat())
        val path = context.getDatabasePath("appia_chat.example.com.db")
        assertTrue(path.exists())

        manager.resetAll()
        assertFalse(path.exists())
        assertFalse(File(path.path + "-wal").exists())
        assertFalse(File(path.path + "-shm").exists())

        // 清理后可重新建库使用，数据为空
        val fresh = manager.databaseFor("chat.example.com")
        assertEquals(0, fresh.chatDao().getAll().size)
        assertEquals(manager.databaseFor(DatabaseManager.PRELOGIN_NORMALIZED), manager.active)
    }

    @Test
    fun `resetDatabase clears only target org and falls back to prelogin`() = runTest {
        val orgA = "https://chat-a.example.com"
        val orgB = "https://chat-b.example.com"
        manager.switchDatabase(orgA).also { it.chatDao().insert(sampleChat()) }
        manager.switchDatabase(orgB).also { it.chatDao().insert(sampleChat()) }
        manager.switchDatabase(orgA) // 让待清理库成为 active，验证回落
        val pathA = context.getDatabasePath(DatabaseManager.dbNameFor(manager.normalizeServer(orgA)))
        val pathB = context.getDatabasePath(DatabaseManager.dbNameFor(manager.normalizeServer(orgB)))
        assertTrue(pathA.exists())

        manager.resetDatabase(manager.normalizeServer(orgA))

        // A：文件全清、可重建且为空；active 回落占位库（对照 db.ts resetServerDatabaseByUrl）
        assertFalse(pathA.exists())
        assertFalse(File(pathA.path + "-wal").exists())
        assertFalse(File(pathA.path + "-shm").exists())
        assertEquals(manager.databaseFor(DatabaseManager.PRELOGIN_NORMALIZED), manager.active)
        assertEquals(0, manager.databaseFor(manager.normalizeServer(orgA)).chatDao().getAll().size)
        // B：其它组织本地数据原样保留（resetAll 会误删）
        assertTrue(pathB.exists())
        assertEquals(1, manager.databaseFor(manager.normalizeServer(orgB)).chatDao().getAll().size)
    }

    private fun sampleChat() = ChatEntity(
        _id = "rid-1", subscription_doc_id = "sub-doc-1",
        f = true, t = "c", ts = 1700000000000.0, ls = 1700000001000.0,
        name = "general", fname = "General", rid = "rid-1", open = true, alert = false,
        unread = 0.0, user_mentions = 0.0, group_mentions = 0.0, room_updated_at = 1700000002000.0,
        ro = false, archived = false, auto_translate_language = "en", team_id = "",
    )
}
