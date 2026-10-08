package cn.appia.im.feature.settings

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.datastore.AuthSession
import cn.appia.im.core.datastore.AuthSessionStore
import cn.appia.im.core.datastore.InMemoryKvStore
import cn.appia.im.core.database.AppiaDatabase
import cn.appia.im.core.database.DatabaseManager
import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.network.AuthUser
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.api.RoomNotificationSettings
import cn.appia.im.core.network.api.RoomSettingsApi
import cn.appia.im.domain.session.RealtimeSessionManager
import cn.appia.im.feature.roominfo.RoomInfoActions
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun chatRow(_id: String = "r1"): ChatEntity = ChatEntity(
    _id = _id, f = false, t = "c", ts = 0.0, ls = 0.0, name = "n", fname = "fn", rid = _id,
    open = true, alert = false, unread = 0.0, user_mentions = 0.0, group_mentions = 0.0,
    room_updated_at = 0.0, ro = false, archived = false, auto_translate_language = "en",
    team_id = "",
)

/**
 * M5-T9 清除缓存链（RN clearLocalCache.ts 逐序：teardown → 删库 → uploads best-effort →
 * 重挂库 → re-bootstrap）。RealtimeSessionManager 为真实例（DDP 不可达——bootstrap 的
 * connect 挂起 = 断网等价；序列断言看文件副作用，不等 bootstrap 完成）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ClearLocalCacheTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = AuthSessionStore(InMemoryKvStore())
    private val dbManager = DatabaseManager(context)

    @Before
    fun setUp() {
        store.save(
            AuthSession(
                token = "tok-1",
                user = AuthUser(id = "u-1", username = "bob", name = "Bob"),
                serverUrl = "https://s1",
            ),
        )
        dbManager.switchDatabase("https://s1")
    }

    @After
    fun tearDown() {
        dbManager.resetAll()
    }

    private fun manager(): RealtimeSessionManager {
        val sdk = RocketSdk(client = OkHttpClient())
        return RealtimeSessionManager(sdk, dbManager, syncInitial = {})
    }

    @Test
    fun `not authenticated throws`() {
        val clear = ClearLocalCache(AuthSessionStore(InMemoryKvStore()), dbManager, manager())
        assertThrows(IllegalStateException::class.java) { runBlocking { clear.clear() } }
    }

    @Test
    fun `clear deletes database file and uploads dir then reactivates`() = runBlocking {
        // 预置：业务库写一行（确保文件落盘）+ uploads 临时文件
        dbManager.active.chatDao().insert(chatRow())
        val dbFile = context.getDatabasePath(DatabaseManager.dbNameFor(dbManager.normalizeServer("https://s1")))
        assertTrue(dbFile.exists())
        val uploads = File(context.cacheDir, "uploads").apply { mkdirs() }
        File(uploads, "pending.bin").writeBytes(byteArrayOf(1, 2, 3))

        val clear = ClearLocalCache(store, dbManager, manager()).also { it.uploadsDir = uploads }
        clear.clear()

        // 步骤 2：库文件已删（teardown → resetDatabase 序）
        assertFalse(dbFile.exists())
        // 步骤 3：uploads 清空（best-effort）
        assertFalse(File(uploads, "pending.bin").exists())
        // 步骤 4：active 已指回业务库（重建的新实例——同 normalized key）
        assertTrue(dbManager.active === dbManager.databaseFor(dbManager.normalizeServer("https://s1")))
        // 步骤 5：re-bootstrap 已发起（generation 防竞态内建于 manager——不崩即序完整）
    }

    @Test
    fun `clear with missing uploads dir does not throw`() = runBlocking {
        val clear = ClearLocalCache(store, dbManager, manager())
            .also { it.uploadsDir = File(context.cacheDir, "uploads-not-exists") }
        clear.clear() // best-effort：目录不存在零副作用不抛
    }
}

/**
 * M5-T9 saveNotification 三键参数面（plan Produces：三键全参 '1'/'0' 字符串值；
 * muteGroupMentions 单键与 mute 双键解耦——对照 RN 参数面就三键）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RoomNotificationThreeKeyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppiaDatabase::class.java)
        .allowMainThreadQueries().build()
    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        db.close()
    }

    private fun newSdk(): RocketSdk =
        RocketSdk(client = OkHttpClient()).also { it.hydrateRestSession(server.url("/").toString(), "tok", "uid") }

    @Test
    fun `all three keys encode as one zero strings`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        RoomSettingsApi.postSaveRoomNotification(
            newSdk(),
            "r1",
            RoomNotificationSettings(disableNotifications = "1", muteGroupMentions = "0", hideUnreadStatus = "1"),
        )
        assertEquals(
            """{"roomId":"r1","notifications":{"disableNotifications":"1","muteGroupMentions":"0","hideUnreadStatus":"1"}}""",
            server.takeRequest().body.readUtf8(),
        )
    }

    @Test
    fun `setRoomNotifications sends only provided keys as wire strings`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        RoomInfoActions(newSdk(), db).setRoomNotifications(
            "r1", disableNotifications = true, muteGroupMentions = false, hideUnreadStatus = true,
        )
        assertEquals(
            """{"roomId":"r1","notifications":{"disableNotifications":"1","muteGroupMentions":"0","hideUnreadStatus":"1"}}""",
            server.takeRequest().body.readUtf8(),
        )
    }

    @Test
    fun `setRoomMentionsMuted posts single muteGroupMentions key decoupled from mute pair`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        RoomInfoActions(newSdk(), db).setRoomMentionsMuted("r1", muted = true)
        // 单键解耦：不含 disableNotifications/hideUnreadStatus（mute 双键另有入口）
        assertEquals(
            """{"roomId":"r1","notifications":{"muteGroupMentions":"1"}}""",
            server.takeRequest().body.readUtf8(),
        )
    }

    @Test
    fun `setRoomMentionsMuted false encodes zero`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        RoomInfoActions(newSdk(), db).setRoomMentionsMuted("r1", muted = false)
        assertEquals(
            """{"roomId":"r1","notifications":{"muteGroupMentions":"0"}}""",
            server.takeRequest().body.readUtf8(),
        )
    }

    @Test
    fun `mute pair still sends setRoomMuted with local write`() = runBlocking {
        db.chatDao().insert(chatRow())
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        RoomInfoActions(newSdk(), db).setRoomMuted("r1", true)
        assertEquals(
            """{"roomId":"r1","notifications":{"disableNotifications":"1","hideUnreadStatus":"1"}}""",
            server.takeRequest().body.readUtf8(),
        )
        assertTrue(db.chatDao().getById("r1")!!.hide_unread_status == true)
    }
}
