package cn.appia.im.core.network.api

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.database.AppiaDatabase
import cn.appia.im.core.settings.ServerSettingRegistry
import cn.appia.im.core.settings.publicSettingBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * settings.public wire 对照（RN syncPublicSettingsFromRegistry.ts）：
 * 122 键 → 50/批 → 3 批请求；query `{"_id":{"$in":[...]}}` + count；upsertAll REPLACE 整行；
 * 未注册 id 跳过；单批失败继续。Robolectric 真 Room 库验证落库与 observeById。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsPublicApiTest {

    private val server = MockWebServer()
    private lateinit var db: AppiaDatabase

    @Before
    fun setUp() {
        server.start()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppiaDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        db.close()
    }

    private fun newSdk(): cn.appia.im.core.network.RocketSdk =
        cn.appia.im.core.network.RocketSdk(client = OkHttpClient()).also {
            it.hydrateRestSession(server.url("/").toString(), "tok", "uid")
        }

    private fun sync(sdk: cn.appia.im.core.network.RocketSdk = newSdk()): Int = runBlocking {
        SettingsPublicApi.syncPublicSettings(sdk) { rows -> db.settingDao().upsertAll(rows) }
    }

    private fun settingsBody(vararg pairs: Pair<String, String>): String =
        """{"success":true,"settings":[${pairs.joinToString(",") { (id, v) -> """{"_id":"$id","value":$v}""" }}]}"""

    // ---- 分批 wire ----

    @Test
    fun `chunks registry ids into batches of fifty`() {
        assertEquals(50, SettingsPublicApi.CHUNK_SIZE)
        assertEquals(3, SettingsPublicApi.chunkIds(ServerSettingRegistry.ids).size)
        assertEquals(ServerSettingRegistry.ids, SettingsPublicApi.chunkIds(ServerSettingRegistry.ids).flatten())
        assertEquals(50, SettingsPublicApi.chunkIds(ServerSettingRegistry.ids)[0].size)
        assertEquals(22, SettingsPublicApi.chunkIds(ServerSettingRegistry.ids)[2].size)
    }

    @Test
    fun `sync sends three chunked requests with in-query and count`() {
        repeat(3) { server.enqueue(MockResponse().setBody(settingsBody())) }
        sync()

        assertEquals(3, server.requestCount)
        val reqs = (0 until 3).map { server.takeRequest() }
        reqs.forEach { req ->
            assertEquals("GET", req.method)
            assertTrue(req.path!!.startsWith("/api/v1/settings.public?"))
        }

        fun inIds(req: okhttp3.mockwebserver.RecordedRequest): List<String> {
            val q = Json.parseToJsonElement(req.requestUrl!!.queryParameter("query")!!).jsonObject
            val arr = q["_id"]!!.jsonObject["\$in"] as kotlinx.serialization.json.JsonArray
            return arr.map { it.jsonPrimitive.content }
        }

        // query JSON 形状 + count 对齐 RN encodeQuery（对象 → JSON.stringify）
        assertEquals(ServerSettingRegistry.ids.take(50), inIds(reqs[0]))
        assertEquals(ServerSettingRegistry.ids.drop(50).take(50), inIds(reqs[1]))
        assertEquals(ServerSettingRegistry.ids.drop(100), inIds(reqs[2]))
        assertEquals("50", reqs[0].requestUrl!!.queryParameter("count"))
        assertEquals("50", reqs[1].requestUrl!!.queryParameter("count"))
        assertEquals("22", reqs[2].requestUrl!!.queryParameter("count"))
    }

    // ---- 落库与归一 ----

    @Test
    fun `sync upserts prepared rows across all three type columns`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                settingsBody(
                    "Enterprise_Name" to "\"Appia Inc\"",
                    "UI_Use_Real_Name" to "\"true\"",
                    "Message_GroupingPeriod" to "\"300\"",
                    "Hide_System_Messages" to """["mute_unmute","other"]""",
                    "Agent_Bot_List" to "\"[\\\"b1\\\"]\"",
                ),
            ),
        )
        val written = sync()
        assertEquals(5, written)

        val dao = db.settingDao()
        assertEquals("Appia Inc", dao.getById("Enterprise_Name")!!.value_as_string)
        assertEquals(true, dao.getById("UI_Use_Real_Name")!!.value_as_boolean)
        assertEquals(300.0, dao.getById("Message_GroupingPeriod")!!.value_as_number)
        assertEquals("""["user-muted","user-unmuted","other"]""", dao.getById("Hide_System_Messages")!!.value_as_string)
        assertEquals("[\"b1\"]", dao.getById("Agent_Bot_List")!!.value_as_string)
    }

    @Test
    fun `unregistered ids are skipped but batch continues`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                settingsBody(
                    "Unknown_Key_XXX" to "1",
                    "Site_Name" to "\"Appia\"",
                    "Another_Unregistered" to "\"x\"",
                ),
            ),
        )
        assertEquals(1, sync())
        assertNull(db.settingDao().getById("Unknown_Key_XXX"))
        assertEquals("Appia", db.settingDao().getById("Site_Name")!!.value_as_string)
    }

    @Test
    fun `replace semantics clear stale value column on type switch`() = runBlocking {
        // RN applyPrepared 先清三列再写目标列：REPLACE 整行等价——串行覆盖不残留。
        // 每次 sync 走满 3 批请求：余批入队空 settings 响应（注册表内 id 但空集）。
        server.enqueue(MockResponse().setBody(settingsBody("Site_Name" to "\"Appia\"")))
        repeat(2) { server.enqueue(MockResponse().setBody(settingsBody())) }
        sync()
        server.enqueue(MockResponse().setBody(settingsBody("Site_Name" to "\"New\"")))
        repeat(2) { server.enqueue(MockResponse().setBody(settingsBody())) }
        sync()
        val row = db.settingDao().getById("Site_Name")!!
        assertEquals("New", row.value_as_string)
        assertNull(row.value_as_boolean)
        assertNull(row.value_as_number)
    }

    @Test
    fun `failed chunk is warned and next chunk continues`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
        server.enqueue(MockResponse().setBody(settingsBody("Site_Name" to "\"Appia\"")))
        repeat(1) { server.enqueue(MockResponse().setBody(settingsBody())) } // 第三批
        assertEquals(1, sync())
        assertEquals(3, server.requestCount) // 500 批后继续走完剩余批
        assertEquals("Appia", db.settingDao().getById("Site_Name")!!.value_as_string)
    }

    // ---- DAO observeById Flow（usePublicSettingBoolean 等价基础）----

    @Test
    fun `observeById emits null then row after upsert`() = runBlocking {
        val dao = db.settingDao()
        // 缺行首发 null（usePublicSettingBoolean 缺省回退的 Flow 基础）
        assertNull(dao.observeById("UI_Use_Real_Name").first())
        dao.upsert(
            ServerSettingRegistry.prepareSettingEntity("UI_Use_Real_Name", Json.parseToJsonElement("true"))!!,
        )
        // Room invalidation 重发新行；轮询直至非 null（Robolectric invalidation 走后台执行器）
        val deadline = System.nanoTime() + 5_000_000_000L
        var row: cn.appia.im.core.database.entity.SettingEntity? = dao.observeById("UI_Use_Real_Name").first()
        while (row == null && System.nanoTime() < deadline) {
            delay(10)
            row = dao.observeById("UI_Use_Real_Name").first()
        }
        assertEquals(true, row!!.value_as_boolean)
    }

    @Test
    fun `publicSettingBoolean reads row or falls back to default`() = runBlocking {
        val dao = db.settingDao()
        assertEquals(false, dao.publicSettingBoolean("Appia_Show_External_Partners"))
        assertEquals(true, dao.publicSettingBoolean("Appia_Show_External_Partners", true))
        dao.upsert(
            ServerSettingRegistry.prepareSettingEntity(
                "Appia_Show_External_Partners",
                Json.parseToJsonElement("true"),
            )!!,
        )
        assertEquals(true, dao.publicSettingBoolean("Appia_Show_External_Partners", false))
    }
}
