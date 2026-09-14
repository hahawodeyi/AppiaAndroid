package cn.appia.im.core.push

import cn.appia.im.core.datastore.InMemoryKvStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * PushTokenRegistrar wire 断言（MockWebServer）：对照 RN services/notification/pushService.ts
 * - register :150-175：POST push.token `{value,type:'gcm',appName:'cn.appia.im'}`，先存后发、失败吞；
 * - unregister :178-188：无存量 token 不发；DELETE `{token}` 无鉴权头，成功才移除。
 */
class PushTokenRegistrarTest {
    private val server = MockWebServer()
    private val kv = InMemoryKvStore()

    @AfterEach
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun registrar(deviceId: String?): Pair<PushTokenRegistrar, String> {
        server.start()
        val reg = PushTokenRegistrar(kv, OkHttpClient(), deviceIdProvider = { deviceId })
        return reg to server.url("/").toString()
    }

    @Test
    fun `register posts wire body with auth headers and stores deviceId first`() = runBlocking {
        val (reg, host) = registrar("device-1")
        server.enqueue(MockResponse().setBody("{}"))

        reg.register(host, authToken = "tok-1", userId = "u-1")

        val request = server.takeRequest()
        assertEquals("/api/v1/push.token", request.path)
        assertEquals("POST", request.method)
        assertEquals(
            """{"value":"device-1","type":"gcm","appName":"cn.appia.im"}""",
            request.body.readUtf8(),
        )
        assertEquals("tok-1", request.getHeader("X-Auth-Token")) // RN sdk.post：登录后会话头
        assertEquals("u-1", request.getHeader("X-User-Id"))
        // RN :163 先存后发（响应尚未消费时键已就位）
        assertEquals("device-1", kv.getString("push_device_token", ""))
    }

    @Test
    fun `register without deviceId skips request and storage`() = runBlocking {
        val (reg, host) = registrar(null)

        reg.register(host, authToken = "tok-1", userId = "u-1") // RN :158-161：skip，不发不存

        assertEquals(0, server.requestCount)
        assertFalse(kv.contains("push_device_token"))
    }

    @Test
    fun `register failure is swallowed and deviceId kept`() = runBlocking {
        val (reg, host) = registrar("device-1")
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"boom"}"""))

        reg.register(host, authToken = "tok-1", userId = "u-1") // 不抛（RN :172-174）

        assertEquals("device-1", kv.getString("push_device_token", ""))

        // 连接级失败同样吞掉
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        reg.register(host, authToken = "tok-1", userId = "u-1")
    }

    @Test
    fun `unregister deletes token body without auth headers and clears storage on success`() = runBlocking {
        val (reg, host) = registrar("device-1")
        kv.putString("push_device_token", "device-1")
        server.enqueue(MockResponse().setBody("{}"))

        reg.unregister(host)

        val request = server.takeRequest()
        assertEquals("/api/v1/push.token", request.path)
        assertEquals("DELETE", request.method)
        assertEquals("""{"token":"device-1"}""", request.body.readUtf8())
        assertNull(request.getHeader("X-Auth-Token")) // RN 调用点在 teardown 后：无会话头
        assertNull(request.getHeader("X-User-Id"))
        assertFalse(kv.contains("push_device_token")) // RN :184 成功才移除
    }

    @Test
    fun `unregister without stored token skips request`() = runBlocking {
        val (reg, host) = registrar("device-1")

        reg.unregister(host) // RN :179-180：无 token 直接返回

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `unregister failure is swallowed and token kept`() = runBlocking {
        val (reg, host) = registrar("device-1")
        kv.putString("push_device_token", "device-1")
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"boom"}"""))

        reg.unregister(host) // 不抛（RN :185-187）

        assertEquals("device-1", kv.getString("push_device_token", ""))
    }
}
