package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketSdk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * spotlightv2 聚合搜索对照（RN src/services/api/spotlight.ts:67-151 fetchForwardSelectSearch）：
 * REST 包 DDP call 参数数组逐位 + 解析（user 去重/联邦剔除/inactive 剔除/d 型房转 user/rank 排序）。
 */
class SpotlightApiTest {
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

    private fun envelope(resultJson: String): String {
        val inner = Json.parseToJsonElement(
            """{"jsonrpc":"2.0","message":"mid","result":$resultJson}""",
        ).toString()
        return """{"message":${JsonPrimitive(inner).toString()}}"""
    }

    @Test
    fun `params array matches RN bit by bit`() = runBlocking {
        server.enqueue(MockResponse().setBody(envelope("{}")))
        SpotlightApi.fetchForwardSelectSearch(newSdk(), "\u5f20")

        val req = server.takeRequest()
        assertEquals("/api/v1/method.call/spotlightv2", req.path)
        val outer = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        val message = Json.parseToJsonElement(outer["message"]!!.jsonPrimitive.content).jsonObject
        assertEquals("method", message["msg"]!!.jsonPrimitive.content)
        assertEquals("spotlightv2", message["method"]!!.jsonPrimitive.content)
        val params = message["params"]!!.jsonArray
        // RN :71-85：[text, [], {users,rooms,includeFederatedRooms,isMessageFull}, null, 50,60,60, true]
        assertEquals("\u5f20", params[0].jsonPrimitive.content)
        assertEquals(JsonArray(emptyList()), params[1])
        assertEquals(
            JsonObject(
                mapOf(
                    "users" to JsonPrimitive(true),
                    "rooms" to JsonPrimitive(true),
                    "includeFederatedRooms" to JsonPrimitive(true),
                    "isMessageFull" to JsonPrimitive(false),
                ),
            ),
            params[2],
        )
        assertEquals(JsonNull, params[3])
        assertEquals(50, params[4].jsonPrimitive.content.toInt())
        assertEquals(60, params[5].jsonPrimitive.content.toInt())
        assertEquals(60, params[6].jsonPrimitive.content.toInt())
        assertEquals(true, params[7].jsonPrimitive.content.toBoolean())
        assertEquals(8, params.size)
    }

    @Test
    fun `parses users rooms usersInRooms with rank order`() = runBlocking {
        val result = """
            {
              "users": [
                {"_id":"u1","username":"zhangsan","name":"\u5f20\u4e09","primaryOrgName":"PMT"},
                {"_id":"u2","username":"lisi","name":"\u674e\u56db"}
              ],
              "rooms": [
                {"_id":"rid-p","t":"p","fname":"\u9879\u76ee\u7fa4"},
                {"_id":"rid-c","t":"c","name":"\u516c\u544a\u9891\u9053"}
              ],
              "usersInRooms": [{"room":{"_id":"rid-p2","t":"p","dname":"\u641c\u7d22\u7fa4"}}]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(envelope(result)))
        val rows = SpotlightApi.fetchForwardSelectSearch(newSdk(), "x")

        // RN 排序：user(1) → 房间 t=='p'(2) → 其余(3)；同 rank 保持插入序
        assertEquals(
            listOf(
                ForwardSearchRow.User("u1", "zhangsan", "\u5f20\u4e09", "PMT"),
                ForwardSearchRow.User("u2", "lisi", "\u674e\u56db", null),
                ForwardSearchRow.Room("rid-p", "\u9879\u76ee\u7fa4", "p"),
                ForwardSearchRow.Room("rid-p2", "\u641c\u7d22\u7fa4", "p"),
                ForwardSearchRow.Room("rid-c", "\u516c\u544a\u9891\u9053", "c"),
            ),
            rows,
        )
    }

    @Test
    fun `filters inactive federated duplicates and maps d rooms to user rows`() = runBlocking {
        val result = """
            {
              "users": [
                {"_id":"u1","username":"a:b","name":"\u8054\u90a6"},
                {"_id":"u2","username":"zhang","name":"\u5f20","active":false},
                {"_id":"u3","username":"dup","name":"\u7532"},
                {"_id":"u4","username":"dup","name":"\u4e59"}
              ],
              "rooms": [
                {"_id":"rid-d","t":"d","username":"wangwu","fname":"\u738b\u4e94"},
                {"_id":"rid-d","t":"d","username":"wangwu","fname":"\u738b\u4e94"}
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(envelope(result)))
        val rows = SpotlightApi.fetchForwardSelectSearch(newSdk(), "x")

        assertEquals(
            listOf(
                ForwardSearchRow.User("u3", "dup", "\u7532", null), // 重复 username 去重（首个保留；users 循环先插）
                ForwardSearchRow.User("rid-d", "wangwu", "\u738b\u4e94", null), // d 型房 → user 行，userId=rid
            ),
            rows,
        )
        assertEquals("user:dup", rows[0].key)
        assertEquals("user:wangwu", rows[1].key)
    }

    @Test
    fun `blank text returns empty without request`() = runBlocking {
        assertTrue(SpotlightApi.fetchForwardSelectSearch(newSdk(), "   ").isEmpty())
        assertEquals(0, server.requestCount)
    }
}
