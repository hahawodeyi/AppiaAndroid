package cn.appia.im.core.network

import cn.appia.im.core.network.rest.AuthInterceptor
import cn.appia.im.core.network.rest.AuthSession
import cn.appia.im.core.network.rest.RetrofitFactory
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * RocketHttp 进程级共享 + RetrofitFactory per-call 超时（对照 RN restClient.ts:44-50 per-request timeout）。
 */
class RocketHttpTest {
    private val server = MockWebServer()

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun builtClient(timeoutMs: Long? = null): OkHttpClient =
        RetrofitFactory.create(server.url("/").toString(), { null }, timeoutMs)
            .callFactory() as OkHttpClient

    @Test
    fun `rocket http client is a process wide singleton`() {
        assertSame(RocketHttp.client, RocketHttp.client)
    }

    @Test
    fun `create derives client sharing pool and dispatcher`() {
        val derived = builtClient()

        assertSame(RocketHttp.client.connectionPool, derived.connectionPool)
        assertSame(RocketHttp.client.dispatcher, derived.dispatcher)
        assertTrue(derived.interceptors.any { it is AuthInterceptor }, "auth interceptor must be attached")
    }

    @Test
    fun `timeout propagates to derived client call timeout`() {
        assertEquals(30_000, builtClient(timeoutMs = 30_000L).callTimeoutMillis)
        assertEquals(RocketHttp.client.callTimeoutMillis, builtClient(timeoutMs = null).callTimeoutMillis)
    }

    @Test
    fun `call timeout aborts slow response`() {
        server.enqueue(MockResponse().setBody("{}").setBodyDelay(1, java.util.concurrent.TimeUnit.SECONDS))
        val api = RetrofitFactory.create(server.url("/").toString(), { null }, timeoutMs = 150L)
        val info = api.create(cn.appia.im.core.network.rest.RocketApi::class.java)

        val err = kotlinx.coroutines.runBlocking { runCatching { info.serverInfo() }.exceptionOrNull() }

        assertTrue(err != null, "delayed body beyond callTimeout must fail")
        assertTrue(err !is cn.appia.im.core.network.rest.AuthSessionExpiredException)
    }
}
