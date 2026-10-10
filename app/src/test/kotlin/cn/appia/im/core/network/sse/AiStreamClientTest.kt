package cn.appia.im.core.network.sse

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * AI SSE 客户端对照（M7-T6 / RN lib/ai/aiStream.ts + streamData.ts）：
 * 六 type 分发、非 JSON raw、text??content??raw 次序、小写鉴权头、wire body、
 * FINISH 停读纪律与「取消收集即关闭连接」（原始 socket 服务端钉死验证）。
 */
class AiStreamClientTest {

    private val server = MockWebServer()

    @BeforeEach
    fun setUp() {
        server.start()
    }

    @AfterEach
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun client() = AiStreamClient(OkHttpClient())

    private fun request(endpoint: String = "/api/v1/bot.sendToAI", prompt: String = "hi", agentUserId: String? = null) =
        AiStreamRequest(
            server = server.url("/").toString(),
            token = "tok",
            userId = "uid",
            endpoint = endpoint,
            rid = "r1",
            prompt = prompt,
            agentUserId = agentUserId,
        )

    private fun sse(vararg chunks: String): String =
        chunks.joinToString("") { "data: $it\n\n" }

    private fun collect(vararg chunks: String, endpoint: String = "/api/v1/bot.sendToAI", agentUserId: String? = null) =
        runBlocking {
            server.enqueue(MockResponse().setBody(sse(*chunks)))
            client().stream(request(endpoint = endpoint, agentUserId = agentUserId)).toList()
        }

    // ── chunk 分发（RN :62-77）──

    @Test
    fun `MESSAGE START EVENT types emit text increments`() {
        assertEquals(
            listOf(
                AiStreamEvent.Text("a1"),
                AiStreamEvent.Text("b2"),
                AiStreamEvent.Text("c3"),
                AiStreamEvent.Finished,
            ),
            collect("""{"type":"MESSAGE","text":"a1"}""", """{"type":"START","text":"b2"}""", """{"type":"EVENT","text":"c3"}"""),
        )
    }

    @Test
    fun `FINISH type finishes and ignores chunks after it`() {
        assertEquals(
            listOf(AiStreamEvent.Text("a"), AiStreamEvent.Finished),
            collect("""{"type":"MESSAGE","text":"a"}""", """{"type":"FINISH"}""", """{"type":"MESSAGE","text":"late"}"""),
        )
    }

    @Test
    fun `DONE type finishes and ignores chunks after it`() {
        assertEquals(
            listOf(AiStreamEvent.Finished),
            collect("""{"type":"DONE"}""", """{"type":"MESSAGE","text":"late"}"""),
        )
    }

    @Test
    fun `ERROR type errors with error field`() {
        assertEquals(
            listOf(AiStreamEvent.Error("boom")),
            collect("""{"type":"ERROR","error":"boom"}"""),
        )
    }

    @Test
    fun `ERROR type without error field falls back to AI error`() {
        assertEquals(
            listOf(AiStreamEvent.Error("AI error")),
            collect("""{"type":"ERROR"}"""),
        )
    }

    @Test
    fun `error field on any chunk terminates`() {
        assertEquals(
            listOf(AiStreamEvent.Error("quota exceeded")),
            collect("""{"type":"MESSAGE","error":"quota exceeded"}"""),
        )
    }

    @Test
    fun `non-json chunk falls back to raw text`() {
        assertEquals(listOf(AiStreamEvent.Text("oops"), AiStreamEvent.Finished), collect("oops"))
    }

    @Test
    fun `text wins over content wins over raw`() {
        assertEquals(
            listOf(AiStreamEvent.Text("t"), AiStreamEvent.Text("c"), AiStreamEvent.Finished),
            collect("""{"text":"t","content":"ignored"}""", """{"content":"c"}"""),
        )
    }

    @Test
    fun `empty text chunk emits nothing`() {
        assertEquals(listOf(AiStreamEvent.Finished), collect("""{"type":"MESSAGE","text":""}"""))
    }

    @Test
    fun `server closing stream without FINISH still finishes`() {
        assertEquals(listOf(AiStreamEvent.Text("a"), AiStreamEvent.Finished), collect("""{"type":"MESSAGE","text":"a"}"""))
    }

    @Test
    fun `multi-line data payload joins with newline`() {
        server.enqueue(
            MockResponse().setBody("data: {\"text\":\"a\"}\ndata: {\"text\":\"b\"}\n\n"),
        )
        val events = runBlocking { client().stream(request()).toList() }
        assertEquals(
            listOf(AiStreamEvent.Text("{\"text\":\"a\"}\n{\"text\":\"b\"}"), AiStreamEvent.Finished),
            events,
        )
    }

    @Test
    fun `comment lines are ignored`() {
        server.enqueue(MockResponse().setBody(": heartbeat\n\ndata: {\"type\":\"MESSAGE\",\"text\":\"a\"}\n\n"))
        val events = runBlocking { client().stream(request()).toList() }
        assertEquals(listOf(AiStreamEvent.Text("a"), AiStreamEvent.Finished), events)
    }

    // ── 错误路径 ──

    @Test
    fun `http error responds network`() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(listOf(AiStreamEvent.Error("network")), runBlocking { client().stream(request()).toList() })
    }

    @Test
    fun `connection failure responds network`() {
        // 关闭的端口 = 连接拒绝，避免 DISCONNECT_AT_START 触发 OkHttp 重试等待
        val dead = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port = dead.localPort
        dead.close()
        assertEquals(
            listOf(AiStreamEvent.Error("network")),
            runBlocking { client().stream(request().copy(server = "http://127.0.0.1:$port")).toList() },
        )
    }

    // ── wire（RN :24-39）──

    @Test
    fun `request wire path headers lowercase and body`() = runBlocking {
        collect("""{"type":"FINISH"}""", endpoint = "/api/v1/bot.saveToClawAgent", agentUserId = "u9")
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/bot.saveToClawAgent", recorded.path)
        assertEquals("POST", recorded.method)
        assertEquals("application/json", recorded.getHeader("content-type")!!.split(";").first())
        // 小写鉴权头（坑 11）：按传输原样名断言
        val names = recorded.headers.names()
        assertTrue("x-auth-token" in names, "names=$names")
        assertTrue("x-user-id" in names, "names=$names")
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"prompt\":\"hi\""))
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"rid\":\"r1\""))
        assertTrue(body.contains("\"agentUserId\":\"u9\""))
    }

    @Test
    fun `agentUserId omitted when absent`() = runBlocking {
        collect("""{"type":"FINISH"}""")
        val body = server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()
        assertFalse(body.contains("agentUserId"))
    }

    // ── FINISH 停读 + 取消关闭连接（原始 socket 服务端验证）──

    /** 原始 socket SSE 服务端：写首个 chunk 后**保持连接不关**，钉死观察客户端是否主动关流。 */
    private class HeldOpenSseServer(firstChunkPayload: String) : Thread() {
        val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port: Int get() = serverSocket.localPort
        val firstChunkWritten = CountDownLatch(1)
        val clientClosed = CountDownLatch(1)
        /** 客户端关流后补写的 chunk 是否被拒（写失败 = 客户端确已断开）。 */
        @Volatile
        var lateWriteRejected = false
            private set

        private val firstChunk = "data: $firstChunkPayload\n\n"

        override fun run() {
            try {
                val socket = serverSocket.accept()
                try {
                    serve(socket)
                } finally {
                    runCatching { socket.close() }
                }
            } catch (e: Exception) {
                // 测试结束后 accept 失败属正常
            }
        }

        private fun serve(socket: Socket) {
            val input = socket.getInputStream()
            // 读请求头到空行
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val c = input.read()
                if (c == -1) return
                head.append(c.toChar())
            }
            val out = socket.getOutputStream()
            out.write(
                "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray(),
            )
            out.flush()
            writeChunk(out, firstChunk)
            firstChunkWritten.countDown()
            // 钉死等待：客户端必须主动关连接（FINISH 停读 / 取消），否则 5s 超时判负
            awaitClientClose(socket)
            clientClosed.countDown()
            // 客户端已断：补写 chunk 应被拒（首写或后续写触发 RST）
            lateWriteRejected = try {
                repeat(3) {
                    writeChunk(out, "data: {\"type\":\"MESSAGE\",\"text\":\"late\"}\n\n")
                    Thread.sleep(30)
                }
                false
            } catch (e: java.io.IOException) {
                true
            }
        }

        private fun awaitClientClose(socket: Socket) {
            socket.soTimeout = 50
            val input = socket.getInputStream()
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                val b = try {
                    input.read()
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: java.io.IOException) {
                    return
                }
                if (b == -1) return
            }
            throw AssertionError("client never closed the connection")
        }

        private fun writeChunk(out: java.io.OutputStream, payload: String) {
            val bytes = payload.toByteArray()
            out.write((bytes.size.toString(16) + "\r\n").toByteArray())
            out.write(bytes)
            out.write("\r\n".toByteArray())
            out.flush()
        }
    }

    private fun heldOpenRequest(raw: HeldOpenSseServer) = request().copy(server = "http://127.0.0.1:${raw.port}")

    @Test
    fun `FINISH stops reading without waiting for server close`() {
        val raw = HeldOpenSseServer("""{"type":"MESSAGE","text":"a"}""" + "\n\n" + """data: {"type":"FINISH"}""")
        raw.start()
        val events = runBlocking {
            withTimeout(10_000) { client().stream(heldOpenRequest(raw)).toList() }
        }
        raw.join(10_000)
        assertEquals(listOf(AiStreamEvent.Text("a"), AiStreamEvent.Finished), events)
        assertTrue(raw.clientClosed.await(0, TimeUnit.MILLISECONDS), "client should have closed right after FINISH")
        assertTrue(raw.lateWriteRejected, "late chunk write must fail on closed client")
        raw.serverSocket.close()
    }

    @Test
    fun `cancelling the collector closes the underlying call`() {
        val raw = HeldOpenSseServer("""{"type":"MESSAGE","text":"a"}""")
        raw.start()
        runBlocking {
            val events = Channel<AiStreamEvent>(Channel.UNLIMITED)
            // IO 调度：阻塞读不得占住 runBlocking 事件循环线程
            val job = launch(Dispatchers.IO) {
                client().stream(heldOpenRequest(raw)).collect { events.send(it) }
            }
            withTimeout(10_000) {
                assertEquals(AiStreamEvent.Text("a"), events.receive())
            }
            job.cancel()
            withTimeout(10_000) { job.join() }
            assertTrue(job.isCancelled)
        }
        raw.join(10_000)
        assertTrue(raw.firstChunkWritten.await(0, TimeUnit.MILLISECONDS))
        assertTrue(raw.clientClosed.await(5, TimeUnit.SECONDS), "cancelling collection must close the connection")
        assertTrue(raw.lateWriteRejected, "late chunk write must fail on closed client")
        raw.serverSocket.close()
    }

    // ── parseStreamData 单元（RN streamData.ts:11-21）──

    @Test
    fun `parseStreamData full json object`() {
        val d = parseStreamData("""{"type":"MESSAGE","text":"t","content":"c","message":"m","error":"e"}""")!!
        assertEquals("MESSAGE", d.type)
        assertEquals("t", d.text)
        assertEquals("c", d.content)
        assertEquals("m", d.message)
        assertEquals("e", d.error)
        assertNull(d.raw)
    }

    @Test
    fun `parseStreamData non-json becomes raw`() {
        assertEquals("plain text", parseStreamData("plain text")!!.raw)
    }

    @Test
    fun `parseStreamData empty returns null`() {
        assertNull(parseStreamData(""))
    }

    @Test
    fun `parseStreamData json non-object has no fields and no raw`() {
        val d = parseStreamData("""["a"]""")!!
        assertNull(d.type)
        assertNull(d.text)
        assertNull(d.raw)
    }
}
