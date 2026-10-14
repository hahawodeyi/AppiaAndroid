package cn.appia.im.core.voice

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * OncallApi 网关行为（RN oncallApi.test.ts 全量移植 + brief 追加用例：
 * init 尾问号 / join 无尾问号 / HTTP 错误前缀 / notify 无鉴权头 / reject org 覆写 /
 * notifyHost2 record org / 宽容数字 code / isOncallSuccess）。
 * RN 测试名以注释保留；AA 用例名意译对齐。
 */
class OncallApiTest {

    private val server = MockWebServer()
    private lateinit var api: OncallApi
    private val ctx = OncallContext(org = "bitmain", businessToken = "btok", apiHost = "")

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setBody("""{"code":"0","data":{},"success":true}""")
        }
        server.start()
        api = OncallApi(
            baseUrlProvider = { server.url("/").toString().trimEnd('/') },
            baseHttpClient = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun informBody() = InformInitBody(
        channelId = "ch-1",
        initiator = "alice",
        initiatorName = "Alice",
        initiatorAppiaId = "id-alice",
        initiatorUid = 100L,
        org = "bitmain",
        receivers = listOf(VoiceChatReceiver(receiver = "bob", name = "Bob")),
        roomId = "room-1",
        roomType = "d",
    )

    private fun RecordedRequest.bodyObj() = Json.parseToJsonElement(body.readUtf8()).jsonObject

    // RN: oncallInformInit POSTs /api/v1/oncall/init with org and token headers
    @Test
    fun `informInit posts init path with trailing question mark and token org headers`() = runBlocking {
        api.informInit(ctx, informBody())
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        // RN oncallApi.ts:123 路径字面量带尾问号（verbatim）
        assertEquals("/api/v1/oncall/init?", req.path)
        assertEquals("btok", req.getHeader("token"))
        assertEquals("bitmain", req.getHeader("org"))
        assertTrue(req.getHeader("Content-Type")?.startsWith("application/json") == true)
        val body = req.bodyObj()
        assertEquals("ch-1", (body["channelId"] as JsonPrimitive).content)
        // receivers 数组原样透传
        val receivers = body["receivers"] as JsonArray
        assertEquals(1, receivers.size)
        assertEquals("bob", (receivers[0].jsonObject["receiver"] as JsonPrimitive).content)
    }

    // RN: oncallFetchAgoraToken GETs v2 token with X-Auth-Token
    @Test
    fun `fetchAgoraToken GETs v2 token with X-Auth-Token`() = runBlocking {
        api.fetchAgoraToken(ctx, "channel-1", 42L)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/v2/token?channelName=channel-1&uid=42", req.path)
        assertEquals("btok", req.getHeader("X-Auth-Token"))
        assertEquals("bitmain", req.getHeader("org"))
        assertNull(req.getHeader("token"))
    }

    // RN: oncallUpdateRecordStatus POSTs status update body
    @Test
    fun `updateRecordStatus posts status update body`() = runBlocking {
        api.updateRecordStatus(ctx, "rec-1", "talking")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        // RN oncallApi.ts:146 路径字面量带尾问号
        assertEquals("/api/v1/oncall/record/status/update?", req.path)
        assertEquals(
            buildJsonObject {
                put("recordId", "rec-1")
                put("status", "talking")
            },
            req.bodyObj(),
        )
    }

    // RN: oncallGetRecordStatus GETs v1 record status
    @Test
    fun `getRecordStatus GETs v1 record status`() = runBlocking {
        api.getRecordStatus(ctx, "rec-2")
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("btok", req.getHeader("X-Auth-Token"))
        assertEquals("/api/v1/oncall/record/status?recordId=rec-2", req.path)
    }

    // RN: oncallGetRecordStatusV2 GETs v2 with record org query
    @Test
    fun `getRecordStatusV2 GETs v2 with record org query`() = runBlocking {
        api.getRecordStatusV2(ctx, "rec-3", "other-org")
        val req = server.takeRequest()
        assertEquals("/api/v2/oncall/record/status?recordId=rec-3&org=other-org", req.path)
    }

    // RN: oncallGetCurrentRecordV2 GETs current record for username
    @Test
    fun `getCurrentRecordV2 GETs current record for username`() = runBlocking {
        api.getCurrentRecordV2(ctx, "alice")
        val req = server.takeRequest()
        assertEquals("/api/v2/oncall/user/current/record?username=alice", req.path)
    }

    // RN: oncallJoin POSTs join payload with deviceId（brief 追加：无尾问号）
    @Test
    fun `join posts payload without trailing question mark`() = runBlocking {
        api.join(ctx, JoinBody(org = "bitmain", recordId = "rec-4", username = "bob", userUid = 9L, deviceId = "device-1"))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        // RN oncallApi.ts:204 路径无尾问号（verbatim）
        assertEquals("/api/v1/oncall/user/join", req.path)
        assertEquals(
            buildJsonObject {
                put("org", "bitmain")
                put("recordId", "rec-4")
                put("username", "bob")
                put("userUid", 9L)
                put("deviceId", "device-1")
            },
            req.bodyObj(),
        )
    }

    // brief 追加：userUid/deviceId null → 键省略（RN JSON.stringify 丢 undefined，:196-200）
    @Test
    fun `join omits null userUid and deviceId keys`() = runBlocking {
        api.join(ctx, JoinBody(org = "bitmain", recordId = "rec-4", username = "bob"))
        val body = server.takeRequest().bodyObj()
        assertEquals(
            buildJsonObject {
                put("org", "bitmain")
                put("recordId", "rec-4")
                put("username", "bob")
            },
            body,
        )
    }

    // RN: oncallReject and oncallLeave POST with org in body
    @Test
    fun `reject and leave post with ctx org in body`() = runBlocking {
        api.reject(ctx, "rec-5", "carol")
        api.leave(ctx, "rec-5", "carol")
        val reject = server.takeRequest()
        val leave = server.takeRequest()
        assertEquals("/api/v1/oncall/user/reject", reject.path)
        assertEquals("/api/v1/oncall/user/leave", leave.path)
        val expected = buildJsonObject {
            put("org", "bitmain")
            put("recordId", "rec-5")
            put("username", "carol")
        }
        assertEquals(expected, reject.bodyObj())
        assertEquals(expected, leave.bodyObj())
        assertEquals("btok", reject.getHeader("token"))
        assertEquals("btok", leave.getHeader("token"))
    }

    // brief 追加：reject notificationOrg 覆写 body.org，头 org 仍 ctx.org（RN :220）
    @Test
    fun `reject uses notification org override in body`() = runBlocking {
        api.reject(ctx, "rec-5", "carol", notificationOrg = "notify-org")
        val req = server.takeRequest()
        assertEquals("bitmain", req.getHeader("org"))
        assertEquals(
            buildJsonObject {
                put("org", "notify-org")
                put("recordId", "rec-5")
                put("username", "carol")
            },
            req.bodyObj(),
        )
    }

    // RN: oncallAddUsers POSTs add user body
    @Test
    fun `addUsers posts add user body`() = runBlocking {
        api.addUsers(
            ctx,
            AddUsersBody(
                receivers = listOf(VoiceChatReceiver(receiver = "dave")),
                recordId = "rec-6",
                org = "bitmain",
                initiatorAppiaId = "id-host",
                initiator = "host",
                initiatorName = "Host",
            ),
        )
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/oncall/add/user", req.path)
        assertEquals("rec-6", (req.bodyObj()["recordId"] as JsonPrimitive).content)
    }

    // RN: oncallGetNotify POSTs without business token header
    @Test
    fun `getNotify posts without auth headers and uses record org in body`() = runBlocking {
        api.getNotify(ctx, org = "bitmain", recordId = "rec-7", username = "eve", recordOrg = "record-org")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/oncall/user/get/notify", req.path)
        assertNull(req.getHeader("token"))
        assertNull(req.getHeader("X-Auth-Token"))
        assertEquals("bitmain", req.getHeader("org"))
        assertEquals(
            buildJsonObject {
                put("org", "record-org")
                put("recordId", "rec-7")
                put("username", "eve")
            },
            req.bodyObj(),
        )
    }

    // brief 追加：notifyHost（legacy Server.notifyHost）body.org = ctx.org，无鉴权头
    @Test
    fun `notifyHost posts ctx org in body without auth headers`() = runBlocking {
        api.notifyHost(ctx, "rec-8", "frank")
        val req = server.takeRequest()
        assertNull(req.getHeader("token"))
        assertNull(req.getHeader("X-Auth-Token"))
        assertEquals("bitmain", req.getHeader("org"))
        assertEquals(
            "bitmain",
            (req.bodyObj()["org"] as JsonPrimitive).content,
        )
    }

    // brief 追加：notifyHost2 跨组织——body.org = recordOrg，头 org 仍 ctx.org
    @Test
    fun `notifyHost2 posts record org in body with ctx org header`() = runBlocking {
        api.notifyHost2(ctx, "record-org", "rec-9", "grace")
        val req = server.takeRequest()
        assertEquals("bitmain", req.getHeader("org"))
        assertEquals(
            buildJsonObject {
                put("org", "record-org")
                put("recordId", "rec-9")
                put("username", "grace")
            },
            req.bodyObj(),
        )
    }

    // RN: invalidates token cache and throws on code 40002
    // AA 锁定：缓存失效移交流程方（Controller），本类只抛（实现锁定）
    @Test
    fun `code 40002 throws token expired`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("""{"code":"40002"}""")
        }
        val err = runCatching { api.leave(ctx, "r", "u") }.exceptionOrNull()
        assertTrue(err is OncallTokenExpiredException)
        assertEquals("oncall_token_expired", err!!.message)
    }

    // brief 追加：code 数字形态 40002 同样命中（Controller 裁定宽容解析）
    @Test
    fun `numeric code 40002 also throws token expired`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("""{"code":40002}""")
        }
        val err = runCatching { api.getRecordStatus(ctx, "r") }.exceptionOrNull()
        assertTrue(err is OncallTokenExpiredException)
    }

    // brief 追加：HTTP ≥400 → oncall_http_error: + json.message（RN :104-111）
    @Test
    fun `http error surfaces json message with prefix`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(400).setBody("""{"code":"1","message":"boom"}""")
        }
        val err = runCatching { api.leave(ctx, "r", "u") }.exceptionOrNull()
        assertTrue(err is OncallHttpException)
        assertEquals("oncall_http_error:boom", err!!.message)
    }

    // brief 追加：无 JSON message → HTTP <status> 兜底
    @Test
    fun `http error without json message falls back to HTTP status`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(500).setBody("oops not json")
        }
        val err = runCatching { api.leave(ctx, "r", "u") }.exceptionOrNull()
        assertTrue(err is OncallHttpException)
        assertEquals("oncall_http_error:HTTP 500", err!!.message)
    }

    // brief 追加：isOncallSuccess（oncallResponse.ts:3-6 + Controller success!=false 裁定）
    @Test
    fun `isOncallSuccess accepts string and numeric zero and rejects success false`() {
        assertTrue(isOncallSuccess(OncallResponse(code = JsonPrimitive("0"))))
        assertTrue(isOncallSuccess(OncallResponse(code = JsonPrimitive(0))))
        assertTrue(isOncallSuccess(OncallResponse(code = JsonPrimitive("0"), success = true)))
        assertFalse(isOncallSuccess(OncallResponse(code = JsonPrimitive("0"), success = false)))
        assertFalse(isOncallSuccess(OncallResponse(code = JsonPrimitive("1"))))
        assertFalse(isOncallSuccess(OncallResponse()))
    }
}
