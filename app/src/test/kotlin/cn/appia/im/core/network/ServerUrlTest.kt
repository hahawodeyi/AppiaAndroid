package cn.appia.im.core.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 对照 RN 四处口径不一的 server 规范化（db.ts:41-47 / auth.ts:21 / ddpClient.ts:62-69 / restClient.ts:40）：
 * normalizeServer 统一取严（trim + 去全部尾斜杠）；normalizeServerToDbKey 逐字符复刻 db.ts。
 */
class ServerUrlTest {

    // ---- normalizeServer：trim + 去全部尾斜杠 ----

    @Test
    fun `normalizeServer keeps bare host intact`() {
        assertEquals("https://appia.cn", ServerUrl.normalizeServer("https://appia.cn"))
    }

    @Test
    fun `normalizeServer strips single trailing slash`() {
        assertEquals("https://appia.cn", ServerUrl.normalizeServer("https://appia.cn/"))
    }

    @Test
    fun `normalizeServer strips all trailing slashes`() {
        assertEquals("appia.cn", ServerUrl.normalizeServer("appia.cn///"))
    }

    @Test
    fun `normalizeServer keeps path but trims and strips trailing slash`() {
        assertEquals("https://a.b.c/p", ServerUrl.normalizeServer(" https://a.b.c/p/ "))
    }

    @Test
    fun `normalizeServer of empty stays empty`() {
        assertEquals("", ServerUrl.normalizeServer("   "))
    }

    // ---- normalizeServerToDbKey：复刻 db.ts:41-47（(^\w+:|^)\/\/ 去协议、全部 /→.）----

    @Test
    fun `dbKey strips https protocol`() {
        assertEquals("appia.cn", ServerUrl.normalizeServerToDbKey("https://appia.cn"))
    }

    @Test
    fun `dbKey turns trailing slash into dot`() {
        assertEquals("appia.cn.", ServerUrl.normalizeServerToDbKey("https://appia.cn/"))
    }

    @Test
    fun `dbKey without protocol keeps host and turns all slashes into dots`() {
        assertEquals("appia.cn...", ServerUrl.normalizeServerToDbKey("appia.cn///"))
    }

    @Test
    fun `dbKey keeps path segments as dot chain`() {
        assertEquals("a.b.c.p.", ServerUrl.normalizeServerToDbKey("https://a.b.c/p/"))
    }

    @Test
    fun `dbKey trims and is empty for blank input`() {
        assertEquals("", ServerUrl.normalizeServerToDbKey("   "))
    }

    // ---- wsUrl：复用 DDP hostToWs ----

    @Test
    fun `wsUrl derives wss for https and strips trailing slash`() {
        assertEquals("wss://appia.cn/websocket", ServerUrl.wsUrl("https://appia.cn/"))
    }

    @Test
    fun `wsUrl derives ws for http`() {
        assertEquals("ws://a.b/websocket", ServerUrl.wsUrl("http://a.b"))
    }
}
