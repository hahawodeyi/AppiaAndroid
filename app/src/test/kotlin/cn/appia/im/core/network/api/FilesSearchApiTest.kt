package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketSdk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * files.search 对照（RN src/lib/chat/filesSearch.test.ts）：
 * body `{text, cursor?}` / 顶层与 data 嵌套双形状 / cursor·filesHasMore 双名 / 缺省保守空列表。
 */
class FilesSearchApiTest {
    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun newSdk(): RocketSdk =
        RocketSdk(client = OkHttpClient()).also { it.hydrateRestSession(server.url("/").toString(), "tok", "uid") }

    @Test
    fun `blank text returns empty without request`() = runBlocking {
        val page = FilesSearchApi.search(newSdk(), "   ", null)
        assertEquals(0, page.files.size)
        assertNull(page.nextCursor)
        assertFalse(page.hasMore)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `posts text body and maps top-level shape`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"files":[{"_id":"f1","name":"a.pdf"}],"nextCursor":"c1","hasMore":true}""",
            ),
        )
        val page = FilesSearchApi.search(newSdk(), "x", null)

        val req = server.takeRequest()
        assertEquals("/api/v1/files.search", req.path)
        assertEquals("POST", req.method)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("x", body["text"]!!.jsonPrimitive.content)
        assertEquals(1, page.files.size)
        assertEquals("c1", page.nextCursor)
        assertTrue(page.hasMore)
    }

    @Test
    fun `passes cursor when provided`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"files":[],"hasMore":false}"""))
        FilesSearchApi.search(newSdk(), "x", """{"score":1}""")

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("""{"score":1}""", body["cursor"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reads files from nested data object`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"data":{"files":[{"_id":"f2","name":"b.docx"}],"nextCursor":"c2","hasMore":false}}""",
            ),
        )
        val page = FilesSearchApi.search(newSdk(), "doc", null)
        assertEquals(1, page.files.size)
        assertEquals("f2", (page.files[0] as? JsonObject)?.get("_id")?.jsonPrimitive?.content)
        assertEquals("c2", page.nextCursor)
        assertFalse(page.hasMore)
    }

    @Test
    fun `falls back to filesHasMore name and infers from cursor`() = runBlocking {
        // filesHasMore 双名优先于推断
        server.enqueue(
            MockResponse().setBody("""{"files":[{"_id":"f1"}],"nextCursor":"c1","filesHasMore":false}"""),
        )
        val page = FilesSearchApi.search(newSdk(), "x", null)
        assertFalse(page.hasMore)

        // 无任何 hasMore 字段：有 cursor + 非空页 → 推断 true（RN :55）
        server.enqueue(
            MockResponse().setBody("""{"files":[{"_id":"f1"}],"nextCursor":"c1"}"""),
        )
        val page2 = FilesSearchApi.search(newSdk(), "x", null)
        assertTrue(page2.hasMore)

        // 无 cursor → 推断 false
        server.enqueue(MockResponse().setBody("""{"files":[{"_id":"f1"}]}"""))
        val page3 = FilesSearchApi.search(newSdk(), "x", null)
        assertFalse(page3.hasMore)
    }
}
