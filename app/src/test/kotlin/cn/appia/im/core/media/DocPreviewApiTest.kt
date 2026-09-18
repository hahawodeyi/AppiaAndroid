package cn.appia.im.core.media

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * DocPreviewApi 单测（RN useFilePreview.test + services/api/preview.ts 状态机对照）：
 * rooms.preview code 判定（0/1 pending、2/200 ready、其余失败）、轮询状态机
 * （retry 序列 0→1…、间隔 2s、30 次耗尽抛错）、resolvePreviewPdfUri 补鉴权。
 */
class DocPreviewApiTest {

    private fun json(s: String): JsonElement = Json.parseToJsonElement(s)

    // ── parsePreviewResponse（RN getPreviewUrl）──

    @Test
    fun `code 0 and 1 are pending`() {
        assertEquals(PreviewResult.Pending, parsePreviewResponse(json("""{"code":0}""")))
        assertEquals(PreviewResult.Pending, parsePreviewResponse(json("""{"code":1}""")))
    }

    @Test
    fun `code 2 and 200 with data are ready`() {
        assertEquals(
            PreviewResult.Ready("/file-proxy/a/b.pdf"),
            parsePreviewResponse(json("""{"code":2,"data":"/file-proxy/a/b.pdf"}""")),
        )
        assertEquals(
            PreviewResult.Ready("https://x/y.pdf"),
            parsePreviewResponse(json("""{"code":200,"data":"https://x/y.pdf"}""")),
        )
    }

    @Test
    fun `code 2 with empty data fails`() {
        assertThrows(PreviewFailedException::class.java) {
            parsePreviewResponse(json("""{"code":2,"data":""}"""))
        }
    }

    @Test
    fun `other codes fail with server message`() {
        val e = assertThrows(PreviewFailedException::class.java) {
            parsePreviewResponse(json("""{"code":3,"message":"convert failed"}"""))
        }
        assertEquals("convert failed", e.message)
    }

    @Test
    fun `missing response falls back to preview_failed`() {
        val e = assertThrows(PreviewFailedException::class.java) { parsePreviewResponse(null) }
        assertEquals("preview_failed", e.message)
    }

    // ── pollPreviewUrl 状态机（RN pollPreviewUrl）──

    @Test
    fun `poll returns ready url and records retry sequence 0 then 1`() = runBlocking {
        val retries = mutableListOf<Int>()
        val sleeps = mutableListOf<Long>()
        val url = pollPreviewUrl(
            fileId = "f1",
            maxRetry = 30,
            intervalMs = 2_000L,
            preview = { _, retry ->
                retries += retry
                if (retry == 0) json("""{"code":0}""") else json("""{"code":2,"data":"ready.pdf"}""")
            },
            sleep = { sleeps += it },
        )
        assertEquals("ready.pdf", url)
        assertEquals(listOf(0, 1), retries)
        assertEquals(listOf(2_000L), sleeps)
    }

    @Test
    fun `poll exhausts 30 retries then fails`() {
        var calls = 0
        val sleeps = mutableListOf<Long>()
        val e = assertThrows(PreviewFailedException::class.java) {
            runBlocking {
                pollPreviewUrl(
                    fileId = "f1",
                    maxRetry = 30,
                    intervalMs = 2_000L,
                    preview = { _, _ ->
                        calls += 1
                        json("""{"code":1}""")
                    },
                    sleep = { sleeps += it },
                )
            }
        }
        assertEquals("preview_failed", e.message)
        assertEquals(31, calls) // i = 0..30
        assertEquals(31, sleeps.size)
        assertEquals(2_000L, sleeps[0])
    }

    @Test
    fun `poll propagates server failure immediately`() {
        assertThrows(PreviewFailedException::class.java) {
            runBlocking {
                pollPreviewUrl(
                    fileId = "f1",
                    preview = { _, _ -> json("""{"code":9,"message":"boom"}""") },
                    sleep = { },
                )
            }
        }
    }

    // ── 白名单（RN SUPPORTED_FORMATS）──

    @Test
    fun `whitelist matches RN list`() {
        listOf("pdf", "DOCX", "xlsx", "ppt", "txt", "csv", "md", "html").forEach {
            org.junit.Assert.assertTrue(it, isSupportedPreviewFormat(it))
        }
        listOf("exe", "zip", "png", "mp4", "").forEach {
            org.junit.Assert.assertFalse(it, isSupportedPreviewFormat(it))
        }
    }

    // ── resolvePreviewPdfUri（RN 同名函数逐行为移植）──

    @Test
    fun `preview url already carrying rc_token is encoded as-is`() {
        assertEquals(
            "https://x/y.pdf?rc_token=t",
            resolvePreviewPdfUri("https://x/y.pdf?rc_token=t", "https://dl/x?rc_uid=u&rc_token=t"),
        )
    }

    @Test
    fun `absolute preview url gains auth params from download url`() {
        val out = resolvePreviewPdfUri("https://x/y.pdf", "https://server.com/f?rc_uid=u1&rc_token=t1")
        assertEquals("https://x/y.pdf?rc_uid=u1&rc_token=t1", out)
    }

    @Test
    fun `relative preview url resolves against download url origin`() {
        val out = resolvePreviewPdfUri("/file-proxy/a/b.pdf", "https://server.com/f?rc_uid=u1&rc_token=t1")
        assertEquals("https://server.com/file-proxy/a/b.pdf?rc_uid=u1&rc_token=t1", out)
    }

    @Test
    fun `empty preview url stays empty`() {
        assertEquals("", resolvePreviewPdfUri("  ", "https://server.com/f?rc_uid=u1&rc_token=t1"))
    }

    @Test
    fun `download url without auth leaves preview url bare-encoded`() {
        assertEquals("https://x/y.pdf", resolvePreviewPdfUri("https://x/y.pdf", "https://server.com/f"))
    }

    @Test
    fun `download url unparseable keeps preview url bare-encoded`() {
        // RN：downloadUrl 非 URL → readAuth null → 早退 encodeURI(trimmed)
        assertEquals("/a.pdf", resolvePreviewPdfUri("/a.pdf", "server.com/f"))
    }

    @Test
    fun `relative preview url without origin in download url returns empty`() {
        // RN：auth 命中但 downloadUrl 无 origin → 相对地址无解返回 ''
        assertEquals("", resolvePreviewPdfUri("/a.pdf", "//host-only?rc_uid=u1&rc_token=t1"))
    }
}
