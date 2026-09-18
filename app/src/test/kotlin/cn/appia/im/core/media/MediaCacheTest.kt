package cn.appia.im.core.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * MediaCache 单测（RN resolveCachedImage.test.ts 对照）：缓存 key（query 剥离、稳定、区分路径）、
 * 命中秒回、in-flight 并发去重（同 key 一次 HTTP）、失败抛错；下载文件名清洗（RN fileDownload.ts）。
 */
class MediaCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private var requestCount = 0

    @Before
    fun setUp() {
        requestCount = 0
        MediaCache.clearInFlight()
        server.dispatcher = countingDispatcher
        server.start()
    }

    private val countingDispatcher = object : okhttp3.mockwebserver.Dispatcher() {
        override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
            requestCount += 1
            return MockResponse().setBody("file-bytes")
        }
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        MediaCache.clearInFlight()
    }

    // ── 缓存 key（RN stripQuery + buildKey 的 SHA-256 替代形态）──

    @Test
    fun `cache key strips query so token rotation keeps cache`() {
        val a = MediaCache.cacheKey("https://s/file-upload/1/2/a.png?rc_uid=u1&rc_token=t1")
        val b = MediaCache.cacheKey("https://s/file-upload/1/2/a.png?rc_uid=u2&rc_token=t2")
        assertEquals(a, b) // RN stripQuery 同义：query（含 v=etag/rc_token）不进 key
    }

    @Test
    fun `cache key distinguishes paths and is stable`() {
        assertNotEquals(
            MediaCache.cacheKey("https://s/a.png"),
            MediaCache.cacheKey("https://s/b.png"),
        )
        assertEquals(MediaCache.cacheKey("https://s/a.png"), MediaCache.cacheKey("https://s/a.png"))
    }

    // ── fetch（命中 / 去重 / 失败）──

    @Test
    fun `fetch downloads once then serves from cache`() = runBlocking {
        val url = server.url("/file-upload/1/2/a.png?rc_uid=u1&rc_token=t1").toString()
        val first = MediaCache.fetch(tmp.root, url, OkHttpClient())
        assertEquals("file-bytes", first.readText())
        assertTrue(first.isFile)

        // 命中：不再发请求
        val second = MediaCache.fetch(tmp.root, url, OkHttpClient())
        assertEquals(first.absolutePath, second.absolutePath)
        assertEquals(1, requestCount)
    }

    @Test
    fun `concurrent fetches for same url download once`() = runBlocking {
        val url = server.url("/f/a.png").toString()
        val results = List(4) {
            async(Dispatchers.IO) { MediaCache.fetch(tmp.root, url, OkHttpClient()) }
        }.map { it.await() }
        assertEquals(1, requestCount)
        assertTrue(results.all { it.isFile && it.readText() == "file-bytes" })
    }

    @Test
    fun `http failure throws and leaves no cached file`() {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                MockResponse().setResponseCode(500)
        }
        val url = server.url("/f/err.png").toString()
        assertThrows(IllegalStateException::class.java) {
            runBlocking { MediaCache.fetch(tmp.root, url, OkHttpClient()) }
        }
        assertTrue(!MediaCache.cachedFile(tmp.root, url).exists())
    }

    @Test
    fun `different urls land in separate cache entries`() = runBlocking {
        val a = MediaCache.fetch(tmp.root, server.url("/f/a.png").toString(), OkHttpClient())
        val b = MediaCache.fetch(tmp.root, server.url("/f/b.png").toString(), OkHttpClient())
        assertNotEquals(a.absolutePath, b.absolutePath)
        assertEquals(2, requestCount)
    }

    // ── 下载文件名（RN sanitizeDownloadFileName）──

    @Test
    fun `sanitize download file name strips query and hash`() {
        assertEquals("annual.pdf", sanitizeDownloadFileName(" annual.pdf?rc_token=x#frag "))
        assertEquals("a.png", sanitizeDownloadFileName("a.png"))
        assertEquals("document", sanitizeDownloadFileName("   "))
        assertEquals("document", sanitizeDownloadFileName("?only-query"))
    }
}
