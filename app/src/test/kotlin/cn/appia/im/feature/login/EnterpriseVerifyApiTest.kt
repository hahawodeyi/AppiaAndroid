package cn.appia.im.feature.login

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * 对照 RN verifyEnterprise.ts:12-24：POST {envHost 去尾斜杠}/provider/api/v1/verify，
 * JSON body {identity: trim}，不看 HTTP 状态码、响应体即结果。
 */
class EnterpriseVerifyApiTest {
    private val server = MockWebServer()

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun call(host: String = server.url("/").toString(), code: String = "CODE"): VerifyEnterpriseResponse =
        runBlocking { verifyEnterprise(host, code) }

    private fun enqueue(body: String, status: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(status).setBody(body))
    }

    @Test
    fun `posts to provider verify with trimmed identity`() {
        enqueue("""{"success":true,"servers":[{"url":"https://s1"}]}""")

        call(code = "  ab12  ")

        val recorded = server.takeRequest()
        assertEquals("/provider/api/v1/verify", recorded.path)
        assertEquals("POST", recorded.method)
        assertTrue(
            recorded.getHeader("Content-Type")!!.startsWith("application/json"),
            "Content-Type must be application/json（RN headers）",
        )
        assertEquals("""{"identity":"ab12"}""", recorded.body.readUtf8())
    }

    @Test
    fun `strips all trailing slashes from envHost`() {
        enqueue("""{"success":true,"servers":[{"url":"https://s1"}]}""")

        // server.url("/") = http://localhost:port/，尾部补 /// 复刻 RN replace(/\/+$/,'') 输入
        call(host = server.url("/").toString() + "///")

        assertEquals("/provider/api/v1/verify", server.takeRequest().path)
    }

    @Test
    fun `success with servers parses all company fields and passes`() {
        enqueue(
            """{"success":true,"servers":[{""" +
                """"url":"https://appia.cn","name":"Bitmain CN","ename":"Bitmain","logo":"https://l.png","selected":true}]}""",
        )

        val resp = call()

        // success && servers 非空才放行（RN EnterpriseCodeScreen:74）
        assertTrue(resp.pass)
        val s = resp.servers!!.single()
        assertEquals("https://appia.cn", s.url)
        assertEquals("Bitmain CN", s.name)
        assertEquals("Bitmain", s.ename)
        assertEquals("https://l.png", s.logo)
        assertEquals(true, s.selected)
    }

    @Test
    fun `success with empty servers does not pass`() {
        enqueue("""{"success":true,"servers":[]}""")

        val resp = call()

        assertFalse(resp.pass)
    }

    @Test
    fun `failure msg surfaced and does not pass`() {
        enqueue("""{"success":false,"msg":"enterprise not found"}""")

        val resp = call()

        assertFalse(resp.pass)
        assertEquals("enterprise not found", resp.msg)
    }

    @Test
    fun `non-json body throws for screen unknown fallback`() {
        enqueue("<html>404</html>")

        assertThrows<SerializationException> { call() }
    }

    @Test
    fun `http 500 with json failure body still parsed like RN`() {
        // RN verifyEnterprise.ts 不检查 response.ok，直接 response.json()
        enqueue("""{"success":false,"msg":"server busy"}""", status = 500)

        val resp = call()

        assertFalse(resp.pass)
        assertEquals("server busy", resp.msg)
    }

    @Test
    fun `missing optional fields default to null`() {
        enqueue("""{"success":true,"servers":[{"url":"https://s1"}]}""")

        val s = call().servers!!.single()

        assertNull(s.name)
        assertNull(s.ename)
        assertNull(s.logo)
        assertNull(s.selected)
    }
}
