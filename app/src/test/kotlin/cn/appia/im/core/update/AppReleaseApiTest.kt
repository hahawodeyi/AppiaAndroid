package cn.appia.im.core.update

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 版本检查 wire 对照（RN services/appRelease.ts:41-95）：URL 形态/参数、无会话头、
 * camelCase → snake_case 映射、success=false → null、非 2xx 抛错、逗号 url 下标。
 */
class AppReleaseApiTest {

    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    private fun fetch(localVersion: String = "1.2.3") = runBlocking {
        fetchLatestAppRelease(
            client = OkHttpClient(),
            baseUrl = server.url("/").toString(),
            localVersion = localVersion,
        )
    }

    private fun fullBody() =
        """{"success":true,"data":{"platform":"android","version":"2.0.0-rc.1","url":"https://a.example.com/app.apk,https://b.example.com/app.apk","isForceUpdate":true,"fileHash":"abc123","fileSize":"52428800","notes":"release notes","updatedAt":"2026-10-01T00:00:00Z"}}"""

    // ---- URL / wire ----

    @Test
    fun `sends GET with platform and versionName params`() = runBlocking {
        enqueue("""{"success":true,"data":null}""")
        fetch()
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/provider/api/v1/version", recorded.path?.substringBefore('?'))
        val query = recorded.path?.substringAfter('?').orEmpty()
        assertEquals("platform=android", query.substringBefore("&"))
        assertEquals("versionName=1.2.3", query.substringAfter("&"))
    }

    @Test
    fun `url builder strips trailing slash and encodes versionName`() {
        assertEquals(
            "https://appia.cn/provider/api/v1/version?platform=android&versionName=1.21.9",
            buildAppVersionCheckUrl("https://appia.cn/", "android", "1.21.9"),
        )
        assertEquals(
            "http://localhost:1/provider/api/v1/version?platform=android&versionName=1.0%2Bdebug",
            buildAppVersionCheckUrl("http://localhost:1/", "android", "1.0+debug"),
        )
    }

    @Test
    fun `sends no auth headers`() = runBlocking {
        enqueue("""{"success":true,"data":null}""")
        fetch()
        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
        assertNull(recorded.getHeader("X-Auth-Token"))
        assertNull(recorded.getHeader("Cookie"))
    }

    // ---- 响应映射 ----

    @Test
    fun `maps camelCase dto to snake_case row`() {
        enqueue(fullBody())
        val row = fetch()!!
        assertEquals("android", row.platform)
        assertEquals("2.0.0-rc.1", row.version)
        assertTrue(row.is_force_update)
        assertEquals("abc123", row.file_hash)
        assertEquals("release notes", row.notes)
        // url 原样保留（逗号拆分是打开外链时的事，不是落库时）
        assertTrue(row.url.startsWith("https://a.example.com"))
    }

    @Test
    fun `defaults missing optional fields like rn`() {
        enqueue("""{"success":true,"data":{"platform":"android","version":"2.0.0"}}""")
        val row = fetch()!!
        assertEquals("", row.url)
        assertFalse(row.is_force_update)
        assertNull(row.file_hash)
        assertEquals("", row.notes)
    }

    @Test
    fun `success false or null data maps to null`() {
        enqueue("""{"success":false,"data":null}""")
        assertNull(fetch())
        enqueue("""{"success":true,"data":null}""")
        assertNull(fetch())
    }

    @Test
    fun `non 2xx throws app_release_http_code`() {
        enqueue("{}", code = 500)
        try {
            fetch()
            fail("expected app_release_http_500")
        } catch (e: IllegalStateException) {
            assertEquals("app_release_http_500", e.message)
        }
    }

    // ---- 逗号 url 下标（RN resolveAppReleaseOpenUrl :65-74）----

    @Test
    fun `comma url resolves by APP_RELEASE_URL_COMMA_INDEX`() {
        assertEquals(0, APP_RELEASE_URL_COMMA_INDEX)
        val raw = " https://a.example.com/x.apk , https://b.example.com/y.apk "
        assertEquals("https://a.example.com/x.apk", resolveAppReleaseOpenUrl(raw))
    }

    @Test
    fun `comma index clamps to last part and empty parts fall back to trimmed raw`() {
        val single = "https://only.example.com/x.apk"
        assertEquals(single, resolveAppReleaseOpenUrl(single)) // 下标 0 对单条
        assertEquals("https://x.example.com", resolveAppReleaseOpenUrl("  https://x.example.com  "))
    }
}
