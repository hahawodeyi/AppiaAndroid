package cn.appia.im.feature.chat

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.database.AppiaDatabase
import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.network.RocketSdk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RecallActions 撤回序列（Robolectric + 内存 Room + MockWebServer，RN onRecall doRecall :863-889）：
 * **先**快照 original_content 落库（RN captureRecalledOriginalContent :82-99，吞错不阻塞），
 * **再** POST message.recall；本地不硬删（等 DDP rollback 回推）。批量撤回不快照（RN 同）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecallActionsTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppiaDatabase::class.java)
        .allowMainThreadQueries()
        .build()
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

    private suspend fun insertRow(): MessageEntity {
        val row = MessageEntity(
            _id = "m1", rid = "r1", ts = 100.0,
            u = """{"_id":"me","username":"me","name":"Me"}""",
            alias = "", parse_urls = "[]", _updated_at = 100.0,
            msg = "\u539f\u59cb\u6587\u672c", tmid = "t9", mentions = """[{"_id":"u9"}]""",
        )
        db.messageDao().insert(row)
        return row
    }

    @Test
    fun `recall snapshots original_content before posting message recall`() = runBlocking {
        insertRow()
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        RecallActions(newSdk(), db).recall(db.messageDao().getById("m1")!!)

        // 快照先落库（8 键 JSON，含原文与 tmid）
        val row = db.messageDao().getById("m1")!!
        assertTrue(row.original_content!!.contains("\"msg\":\"\u539f\u59cb\u6587\u672c\""))
        assertTrue(row.original_content!!.contains("\"tmid\":\"t9\""))
        // wire = POST message.recall {id}
        val req = server.takeRequest()
        assertEquals("/api/v1/message.recall", req.path)
        assertEquals("""{"id":"m1"}""", req.body.readUtf8())
        // 本地不硬删（行仍在，等 DDP rollback 回推改写）
        assertEquals("\u539f\u59cb\u6587\u672c", db.messageDao().getById("m1")!!.msg)
    }

    @Test
    fun `batch recall posts ids without snapshot`() = runBlocking {
        insertRow()
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        RecallActions(newSdk(), db).batchRecall(listOf("m1"))

        assertNull(db.messageDao().getById("m1")!!.original_content) // 批量路径不快照（RN 同）
        val req = server.takeRequest()
        assertEquals("/api/v1/message.batch.recall", req.path)
        assertEquals("""{"ids":["m1"]}""", req.body.readUtf8())
    }
}
