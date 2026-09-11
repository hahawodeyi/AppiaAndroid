package cn.appia.im.core.network

import cn.appia.im.core.network.rest.ApiException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

private val json = Json { ignoreUnknownKeys = true }

private fun JsonObject?.str(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

private fun parse(raw: String): JsonObject? = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()

/**
 * 脚本化 DDP WS 服务端：connect→connected、ping→pong、method:login→result（登录结果形状），
 * 记录全部客户端帧（resume 断言用）。connected 必须在收到客户端 connect 帧后回（与 DdpClientTest
 * 的 DdpServer 同口径）——在 onOpen 抢发会与 DdpClient 的监听注册竞态，握手随机挂死。
 */
private class LoginMethodServer : WebSocketListener() {
    val frames = CopyOnWriteArrayList<String>()
    private val wsRef = AtomicReference<WebSocket?>(null)

    override fun onOpen(webSocket: WebSocket, response: Response) {
        wsRef.set(webSocket)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        frames.add(text)
        val obj = parse(text) ?: return
        when (obj.str("msg")) {
            "connect" -> webSocket.send("""{"msg":"connected","session":"s"}""")
            "ping" -> webSocket.send("""{"msg":"pong"}""")
            "method" -> if (obj.str("method") == "login") {
                webSocket.send(
                    """{"msg":"result","id":"${obj.str("id")}",""" +
                        """"result":{"id":"ddp-user-1","token":"ddp-token","createCipher":{"${'$'}date":1690000000000}}}""",
                )
            }
        }
    }

    /** 轮询等待满足条件的客户端帧（真实时间，超时失败并打印已收帧）。 */
    fun awaitFrame(desc: String, timeoutMs: Long = 5_000, predicate: (JsonObject) -> Boolean): JsonObject {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            for (raw in frames) {
                parse(raw)?.let { if (predicate(it)) return it }
            }
            Thread.sleep(10)
        }
        throw AssertionError("timeout waiting for frame [$desc], received=$frames")
    }
}

/**
 * RocketSdk 集成测试（MockWebServer REST + WebSocket，纯 JVM）。
 * 对照 brief 验收：5 变体 wire body 逐字段、REST 成功→DDP login{resume}、REST 失败不触碰 DDP、
 * method.call 编码与信封解析、`data ?? resp` 平铺两形态、hydrate/clear/initialize。
 */
class RocketSdkTest {
    private val server = MockWebServer()
    private val sdks = CopyOnWriteArrayList<RocketSdk>()

    @AfterEach
    fun tearDown() {
        // 与 DdpClientTest 同口径：先停客户端循环、强杀 socket，再容忍 MockWebServer 5.3 shutdown 卡死
        sdks.forEach { sdk ->
            sdk.ddp?.let {
                runCatching { it.disconnect() }
                runCatching { it.cancelTransport() }
            }
        }
        sdks.clear()
        runCatching { server.shutdown() }
    }

    /** 按响应顺序入队：REST（可选）→ WS 升级（可选），再建 sdk 并 initialize 到 mock host。 */
    private fun newSdk(
        restBody: String? = null,
        restCode: Int = 200,
        ws: WebSocketListener? = null,
    ): RocketSdk {
        server.start()
        if (restBody != null) {
            server.enqueue(MockResponse().setResponseCode(restCode).setBody(restBody))
        }
        if (ws != null) server.enqueue(MockResponse().withWebSocketUpgrade(ws))
        return RocketSdk(client = OkHttpClient()).also {
            it.initialize(server.url("/").toString())
            sdks.add(it)
        }
    }

    /** REST 登录（非 LDAP）成功并完成 DDP resume 的现成 sdk（供 get/post/methodCall 用例复用）。 */
    private fun loggedInSdk(): RocketSdk {
        val sdk = newSdk(
            restBody = """{"status":"success","data":{"userId":"u-1","authToken":"t-1","me":{"username":"bob"}}}""",
            ws = LoginMethodServer(),
        )
        return sdk.also { runBlocking { it.login(LoginCredentials.Password("bob", "secret")) } }
    }

    /** takeRequest 按 FIFO 出队：登录 REST + DDP WS 升级两条已在 login 用例覆盖，弹掉以对准后续请求。 */
    private fun skipLoginRequest() {
        server.takeRequest() // POST /api/v1/login
        server.takeRequest() // GET /websocket（upgrade）
    }

    // ---- 语义 1：login wire body 逐字段（5 变体）+ 成功后 DDP login{resume} ----

    @Test
    fun `login posts password wire body and resumes ddp with token`() = runBlocking {
        val ws = LoginMethodServer()
        val sdk = newSdk(
            restBody = """{"status":"success","data":{"userId":"u-1","authToken":"t-1","me":{"username":"bob","roles":["admin"]}}}""",
            ws = ws,
        )

        val result = sdk.login(LoginCredentials.Password("bob", "secret", ic = JsonPrimitive("ic-9")))

        val rest = server.takeRequest()
        assertEquals("/api/v1/login", rest.path)
        assertEquals("""{"username":"bob","password":"secret","ic":"ic-9"}""", rest.body.readUtf8())
        assertNull(rest.getHeader("X-Auth-Token"), "login must not carry stale session token")
        assertEquals("t-1", result.authToken)
        assertEquals("u-1", result.userId)
        assertEquals("bob", result.me?.username)
        // REST 成功 → 立即 DDP login{resume}，resume token = REST authToken（RN sdk/index.ts:179）
        val frame = ws.awaitFrame("login method") { it.str("method") == "login" }
        val params = frame["params"] as JsonArray
        assertEquals("t-1", (params[0] as JsonObject).str("resume"))
        assertTrue(frame.str("id")!!.startsWith("ddp-"))
        assertTrue(sdk.hasDdpUserId())
    }

    @Test
    fun `login ldap wire body hits verify-ic`() = runBlocking {
        val sdk = newSdk(
            restBody = """{"data":{"userId":"u-2","authToken":"t-2"}}""",
            ws = LoginMethodServer(),
        )

        sdk.login(LoginCredentials.Password("carol", "ldap-pass", ic = JsonPrimitive("ic-2"), ldap = true))

        val rest = server.takeRequest()
        assertEquals("/api/v1/verify-ic", rest.path)
        assertEquals(
            """{"username":"carol","ldapPass":"ldap-pass","ldap":true,"ldapOptions":{},"ic":"ic-2"}""",
            rest.body.readUtf8(),
        )
    }

    @Test
    fun `login sms wire body has no ic`() = runBlocking {
        val sdk = newSdk(
            restBody = """{"data":{"userId":"u-3","authToken":"t-3"}}""",
            ws = LoginMethodServer(),
        )

        sdk.login(LoginCredentials.Sms(phone = "13800000000", code = "1234", areaCode = "+86"))

        val rest = server.takeRequest()
        assertEquals("/api/v1/login", rest.path)
        assertEquals(
            """{"smsCode":true,"phone":"13800000000","code":"1234","areaCode":"+86"}""",
            rest.body.readUtf8(),
        )
    }

    @Test
    fun `login switchOrg wire body carries three fields`() = runBlocking {
        val sdk = newSdk(
            restBody = """{"data":{"userId":"u-4","authToken":"t-4"}}""",
            ws = LoginMethodServer(),
        )

        sdk.login(LoginCredentials.SwitchOrg(userId = "u-old", userToken = "tok-old", url = "https://a.cn"))

        val rest = server.takeRequest()
        assertEquals("/api/v1/login", rest.path)
        assertEquals(
            """{"userId":"u-old","userToken":"tok-old","url":"https://a.cn"}""",
            rest.body.readUtf8(),
        )
        assertNull(rest.getHeader("X-Auth-Token"), "org switch login must not carry stale org token")
    }

    @Test
    fun `login cas wire body nests credentialToken`() = runBlocking {
        val sdk = newSdk(
            restBody = """{"data":{"userId":"u-5","authToken":"t-5"}}""",
            ws = LoginMethodServer(),
        )

        sdk.login(LoginCredentials.Cas("cas-ticket"))

        val rest = server.takeRequest()
        assertEquals("/api/v1/login", rest.path)
        assertEquals("""{"cas":{"credentialToken":"cas-ticket"}}""", rest.body.readUtf8())
    }

    // ---- 语义 2：REST 失败不触碰 DDP，错误上抛不吞 ----

    @Test
    fun `rest failure propagates and never touches ddp`() = runBlocking {
        val sdk = newSdk(restCode = 500, restBody = """{"message":"bad credentials"}""")

        val ex = runCatching { sdk.login(LoginCredentials.Password("bob", "wrong")) }.exceptionOrNull()

        assertInstanceOf(ApiException::class.java, ex)
        assertTrue(ex?.message?.contains("bad credentials") == true)
        assertEquals(1, server.requestCount, "only the REST request; DDP must stay untouched")
        assertFalse(sdk.hasDdpUserId())
    }

    @Test
    fun `success response without authToken throws instead of setting session`() = runBlocking {
        val sdk = newSdk(restBody = """{"status":"success"}""")

        val ex = runCatching { sdk.login(LoginCredentials.Password("bob", "x")) }.exceptionOrNull()

        assertTrue(ex?.message?.contains("missing authToken") == true)
        assertFalse(sdk.hasDdpUserId())
    }

    // ---- 语义 3：`data ?? resp` 平铺两形态 ----

    @Test
    fun `bare login response without data wrapper still resolves fields`() = runBlocking {
        val sdk = newSdk(
            restBody = """{"status":"success","userId":"u-6","authToken":"t-6","me":{"username":"bob"}}""",
            ws = LoginMethodServer(),
        )

        val result = sdk.login(LoginCredentials.Password("bob", "x"))

        assertEquals("t-6", result.authToken)
        assertEquals("u-6", result.userId)
        assertEquals("bob", result.me?.username)
    }

    @Test
    fun `wrapped data wins over top level decoys`() = runBlocking {
        val sdk = newSdk(
            restBody = """{"data":{"userId":"u-in","authToken":"t-in"},"userId":"u-out","authToken":"t-out"}""",
            ws = LoginMethodServer(),
        )

        val result = sdk.login(LoginCredentials.Password("bob", "x"))

        assertEquals("t-in", result.authToken)
        assertEquals("u-in", result.userId)
    }

    // ---- 语义 4：get/post 会话头注入 + 响应平铺 + JSON body ----

    @Test
    fun `get and post carry session headers and flatten responses`() = runBlocking {
        val sdk = loggedInSdk()

        server.enqueue(MockResponse().setBody("""{"data":{"rooms":[]},"success":true}"""))
        val got = sdk.get("rooms.get", params = mapOf("count" to "50"))

        skipLoginRequest() // 登录请求已出队，对准 get
        val getRequest = server.takeRequest()
        assertEquals("/api/v1/rooms.get?count=50", getRequest.path)
        assertEquals("t-1", getRequest.getHeader("X-Auth-Token"))
        assertEquals("u-1", getRequest.getHeader("X-User-Id"))
        assertEquals("""{"rooms":[]}""", got.toString())

        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        sdk.post("rooms.info", buildJsonObject { put("rid", "r-1") })

        val postRequest = server.takeRequest()
        assertEquals("/api/v1/rooms.info", postRequest.path)
        assertEquals("""{"rid":"r-1"}""", postRequest.body.readUtf8())
    }

    @Test
    fun `get without session throws not logged in`() = runBlocking {
        val sdk = newSdk()

        val ex = runCatching { sdk.get("rooms.get") }.exceptionOrNull()

        assertInstanceOf(IllegalStateException::class.java, ex)
        assertEquals("Not logged in", ex?.message)
    }

    // ---- 语义 5：method.call REST 回退（编码 + message 信封 + 解析）----

    @Test
    fun `methodCall posts message envelope to endpoint and unwraps result`() = runBlocking {
        val sdk = loggedInSdk()
        server.enqueue(
            MockResponse().setBody(
                """{"message":"{\"msg\":\"result\",\"id\":\"ddp-x\",\"result\":{\"subs\":3}}"}""",
            ),
        )

        val result = sdk.methodCall("spotlightv2", params = listOf(JsonPrimitive("query")))

        skipLoginRequest()
        val request = server.takeRequest()
        assertEquals("/api/v1/method.call/spotlightv2", request.path)
        val body = parse(request.body.readUtf8())!!
        val message = parse(body.str("message")!!)!!
        assertEquals("method", message.str("msg"))
        assertEquals("spotlightv2", message.str("method"))
        assertTrue(message.str("id")!!.startsWith("ddp-"))
        assertEquals("query", (message["params"] as JsonArray)[0].jsonPrimitive.content)
        assertEquals("""{"subs":3}""", result.toString())
    }

    @Test
    fun `methodCall encodes slash in method name`() = runBlocking {
        val sdk = loggedInSdk()
        server.enqueue(MockResponse().setBody("""{"result":{"ok":true}}"""))

        sdk.methodCall("a/b")

        skipLoginRequest()
        assertEquals("/api/v1/method.call/a%2Fb", server.takeRequest().path)
    }

    @Test
    fun `methodCall parses string result payload and data envelope`() {
        // result 本身是 JSON 串（RN parseDdpResultPayload 再解一层）
        val inner = parseMethodCallRestResponse(parse("""{"message":"{\"result\":\"{\\\"x\\\":1}\"}"}"""))
        assertEquals("""{"x":1}""", inner.toString())

        // 网关把信封放 data 下
        val viaData = parseMethodCallRestResponse(parse("""{"data":{"message":{"result":[1,2]}}}"""))
        assertEquals("[1,2]", viaData.toString())

        // 无 message：有 result 取 result，否则原样
        assertEquals("""{"y":2}""", parseMethodCallRestResponse(parse("""{"result":{"y":2}}""")).toString())
        assertEquals("""{"z":3}""", parseMethodCallRestResponse(parse("""{"z":3}""")).toString())
    }

    @Test
    fun `methodCall error payload throws with reason`() {
        val ex = runCatching {
            parseMethodCallRestResponse(parse("""{"message":"{\"error\":{\"reason\":\"no-perm\"}}"}"""))
        }.exceptionOrNull()

        assertInstanceOf(ApiException::class.java, ex)
        assertEquals("no-perm", ex?.message)
    }

    @Test
    fun `methodCall invalid message json throws`() {
        val ex = runCatching {
            parseMethodCallRestResponse(parse("""{"message":"not-json"}"""))
        }.exceptionOrNull()

        assertTrue(ex?.message?.contains("invalid message JSON") == true)
    }

    // ---- 语义 6：hydrate / clear / initialize ----

    @Test
    fun `hydrateRestSession injects headers for subsequent calls`() = runBlocking {
        val sdk = newSdk()
        val host = server.url("/").toString()

        sdk.hydrateRestSession(host, "tok-9", "u-9")
        sdk.hydrateRestSession(host, "tok-9", "u-9") // 同 token+userId+server：幂等 no-op（RN :102-107）

        server.enqueue(MockResponse().setBody("""{"data":{"ok":1}}"""))
        val got = sdk.get("any")

        val request = server.takeRequest()
        assertEquals("tok-9", request.getHeader("X-Auth-Token"))
        assertEquals("u-9", request.getHeader("X-User-Id"))
        assertEquals("""{"ok":1}""", got.toString())
    }

    @Test
    fun `clearRestSession removes session so get throws`() = runBlocking {
        val sdk = loggedInSdk()
        sdk.clearRestSession()

        val ex = runCatching { sdk.get("rooms.get") }.exceptionOrNull()

        assertEquals("Not logged in", ex?.message)
    }

    @Test
    fun `hydrate on different server reinitializes ddp host`() = runBlocking {
        val sdk = newSdk()
        val firstHost = sdk.server
        val second = MockWebServer()
        second.start()

        sdk.hydrateRestSession(second.url("/").toString(), "tok-10", "u-10")

        assertEquals(ServerUrl.normalizeServer(second.url("/").toString()), sdk.server)
        assertFalse(firstHost == sdk.server)
        runCatching { second.shutdown() }
    }

    @Test
    fun `initialize normalizes trailing slashes and rebuilds ddp`() {
        val sdk = RocketSdk(client = OkHttpClient()).also { sdks.add(it) }
        sdk.initialize("https://a.example.cn///")

        assertEquals("https://a.example.cn", sdk.server)
        assertTrue(sdk.ddp != null)
        assertFalse(sdk.hasDdpUserId())
    }

    @Test
    fun `resume on uninitialized sdk fails fast`() = runBlocking {
        val sdk = RocketSdk(client = OkHttpClient()).also { sdks.add(it) }

        val ex = runCatching { sdk.resume("tok") }.exceptionOrNull()

        assertEquals("RocketSdk not initialized", ex?.message)
    }

    // ---- 语义 7：AuthUser 组装（RN auth/userFromLoginMe.ts:26-42）----

    @Test
    fun `buildAuthUserFromLogin assembles fields with omission rules`() {
        val user = buildAuthUserFromLogin(
            "u-1",
            LoginMe(
                username = "bob",
                name = "Bob",
                statusText = "hi",
                roles = JsonArray(listOf(JsonPrimitive("admin"), JsonPrimitive("core"), JsonPrimitive(true))),
                emails = listOf(
                    LoginMeEmail(address = " a@x.cn ", verified = true),
                    LoginMeEmail(address = "   ", verified = false),
                ),
                settings = LoginMeSettings(preferences = JsonObject(mapOf("theme" to JsonPrimitive("dark")))),
            ),
        )

        assertEquals("u-1", user.id)
        assertEquals("bob", user.username)
        assertEquals("Bob", user.name)
        assertEquals("hi", user.statusText)
        assertEquals(listOf("admin", "core"), user.roles) // 非字符串元素被过滤
        assertEquals(listOf(AuthUserEmail("a@x.cn", true)), user.emails) // trim + 去空
        assertEquals(mapOf("theme" to JsonPrimitive("dark")), user.preferences)
    }

    @Test
    fun `buildAuthUserFromLogin omits empty collections and tolerates roles string`() {
        val user = buildAuthUserFromLogin(
            "u-2",
            LoginMe(
                roles = JsonPrimitive("""["admin","x"]"""), // JSON 串形态
                emails = listOf(LoginMeEmail(address = "  ", verified = true)), // 全空 → 省略
            ),
        )

        assertEquals("", user.username)
        assertEquals(listOf("admin", "x"), user.roles)
        assertNull(user.emails)
        assertNull(user.preferences)
        assertNull(user.name)

        // roles 解析失败/缺省 → 空
        assertEquals(emptyList<String>(), parseUserRoles(JsonPrimitive("not-json")))
        assertEquals(emptyList<String>(), parseUserRoles(null))
        val bare = buildAuthUserFromLogin("u-3", null)
        assertEquals("u-3", bare.id)
        assertNull(bare.roles)
    }
}
