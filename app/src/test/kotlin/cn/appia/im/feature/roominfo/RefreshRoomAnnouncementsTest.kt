package cn.appia.im.feature.roominfo

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.database.AppiaDatabase
import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.permissions.PermissionsStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T7 wire：refreshRoomAnnouncements（rooms.info → chats 公告两列回写，RN
 * refreshRoomAnnouncements.test.ts 移植 + Android 侧补充分支）。
 * 发布/删除/改名 saveRoomSettings wire 在 RoomSettingsApiTest（T3 已钉三形态）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RefreshRoomAnnouncementsTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppiaDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
        PermissionsStore.reset()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        db.close()
        PermissionsStore.reset()
    }

    private fun newSdk(): RocketSdk =
        RocketSdk(client = OkHttpClient()).also { it.hydrateRestSession(server.url("/").toString(), "tok", "uid") }

    private suspend fun insertChat(): ChatEntity {
        val row = ChatEntity(
            _id = "r1", f = false, t = "c", ts = 0.0, ls = 0.0, name = "n", fname = "fn", rid = "r1",
            open = true, alert = false, unread = 0.0, user_mentions = 0.0, group_mentions = 0.0,
            room_updated_at = 0.0, ro = false, archived = false, auto_translate_language = "en",
            team_id = "",
        )
        db.chatDao().insert(row)
        return row
    }

    @Test
    fun `writes announcement fields from rooms info to chat row`() = runBlocking {
        insertChat()
        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"room":{
                    "_id":"r1",
                    "announcement":{"message":"hello"},
                    "announcements":[{"_id":"a1","message":"one"}]
                }}""",
            ),
        )
        RoomInfoActions(newSdk(), db).refreshRoomAnnouncements("r1")

        assertEquals("/api/v1/rooms.info?roomId=r1", server.takeRequest().path)
        val row = db.chatDao().getById("r1")!!
        assertEquals("""{"message":"hello"}""", row.announcement)
        assertEquals("""[{"_id":"a1","message":"one"}]""", row.announcements)
    }

    @Test
    fun `json columns are normalized serialization`() = runBlocking {
        // rooms.info 原始字段序列化与 RN JSON.stringify 等价（键序无关、空格无差异）
        insertChat()
        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"room":{"announcement":"plain text","announcements":[{"message":"m"}]}}""",
            ),
        )
        RoomInfoActions(newSdk(), db).refreshRoomAnnouncements("r1")

        val row = db.chatDao().getById("r1")!!
        assertEquals("plain text", row.announcement) // 串直存（RN toJsonColumn typeof string）
        assertEquals("""[{"message":"m"}]""", row.announcements)
    }

    @Test
    fun `skips when room lacks both keys`() = runBlocking {
        insertChat()
        server.enqueue(MockResponse().setBody("""{"success":true,"room":{"_id":"r1"}}"""))
        RoomInfoActions(newSdk(), db).refreshRoomAnnouncements("r1")

        server.takeRequest()
        val row = db.chatDao().getById("r1")!!
        assertNull(row.announcement)
        assertNull(row.announcements)
    }

    @Test
    fun `network failure keeps local columns`() = runBlocking {
        insertChat()
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{}"""))
        RoomInfoActions(newSdk(), db).refreshRoomAnnouncements("r1")

        // 静默返回（RN catch）——本地零改动
        val row = db.chatDao().getById("r1")!!
        assertNull(row.announcement)
    }

    @Test
    fun `missing chat row is ignored`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"success":true,"room":{"announcement":{"message":"x"}}}"""),
        )
        RoomInfoActions(newSdk(), db).refreshRoomAnnouncements("r1")
        assertNull(db.chatDao().getById("r1"))
    }

    @Test
    fun `response without success flag is skipped`() = runBlocking {
        // RN `!res?.success` → return（refreshRoomAnnouncements.ts:27）
        insertChat()
        server.enqueue(
            MockResponse().setBody("""{"room":{"announcement":{"message":"x"}}}"""),
        )
        RoomInfoActions(newSdk(), db).refreshRoomAnnouncements("r1")

        val row = db.chatDao().getById("r1")!!
        assertNull(row.announcement)
    }

    @Test
    fun `json null column writes null`() = runBlocking {
        insertChat()
        db.chatDao().update(db.chatDao().getById("r1")!!.copy(announcement = "old"))
        server.enqueue(
            MockResponse().setBody("""{"success":true,"room":{"announcement":null}}"""),
        )
        RoomInfoActions(newSdk(), db).refreshRoomAnnouncements("r1")

        val row = db.chatDao().getById("r1")!!
        assertNull(row.announcement) // RN toJsonColumn(null) → undefined（列置空）
    }
}
