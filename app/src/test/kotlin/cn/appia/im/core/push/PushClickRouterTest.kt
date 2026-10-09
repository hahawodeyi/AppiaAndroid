package cn.appia.im.core.push

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PushClickRouter + isPushHostMatchingCurrentServer 测试（JUnit4+Robolectric：路由走 android.util.Log）。
 * host 校验对照 RN pushNavigation.ts:153-176；路由对照 handleNotificationOpen :241-277。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PushClickRouterTest {

    // ---- host 校验（纯函数，RN :166-176） ----

    @Test
    fun `exact hostname match`() {
        assertTrue(isPushHostMatchingCurrentServer("https://a.cn", "https://a.cn"))
        assertTrue(isPushHostMatchingCurrentServer("a.cn", "a.cn"))
    }

    @Test
    fun `protocol and trailing slash ignored`() {
        assertTrue(isPushHostMatchingCurrentServer("https://a.cn/", "http://a.cn"))
        assertTrue(isPushHostMatchingCurrentServer("http://a.cn", "https://a.cn/x"))
        assertTrue(isPushHostMatchingCurrentServer("https://a.cn///", "a.cn"))
    }

    @Test
    fun `subdomain suffix matches both directions`() {
        assertTrue(isPushHostMatchingCurrentServer("https://push.a.cn", "https://a.cn"))
        assertTrue(isPushHostMatchingCurrentServer("https://a.cn", "https://push.a.cn"))
    }

    @Test
    fun `different hosts do not match`() {
        assertFalse(isPushHostMatchingCurrentServer("https://a.cn", "https://b.cn"))
        assertFalse(isPushHostMatchingCurrentServer("https://a.cn.evil.com", "https://a.cn"))
    }

    @Test
    fun `case insensitive`() {
        assertTrue(isPushHostMatchingCurrentServer("https://A.CN", "a.cn"))
        assertTrue(isPushHostMatchingCurrentServer("A.CN", "a.CN"))
    }

    @Test
    fun `missing host or server always matches`() {
        assertTrue(isPushHostMatchingCurrentServer(null, "https://a.cn"))
        assertTrue(isPushHostMatchingCurrentServer("", "https://a.cn"))
        assertTrue(isPushHostMatchingCurrentServer("https://a.cn", null))
        assertTrue(isPushHostMatchingCurrentServer("https://a.cn", ""))
    }

    // ---- PushClickRouter（handleNotificationOpen :241-277 流程） ----

    /** canonical Android 点击 extra（双重转义 ejson 袋） */
    private fun canonicalExtra(ejson: String): String =
        buildJsonObject {
            put("ejson", JsonPrimitive(ejson))
        }.toString()

    private val ejsonRoom =
        """{"rid":"GENERAL","name":" General","sender":{"username":"bob","name":"Bob"},"type":"c","host":"https://a.cn","messageType":"text","messageId":"m-1"}"""

    private class Fixture(serverUrl: String?) {
        val queue = PendingPushNavigation()
        val router = PushClickRouter(queue = queue, currentServerProvider = { serverUrl })
    }

    @Test
    fun `valid open parses and enqueues intent`() {
        val fx = Fixture("https://a.cn")
        fx.router.onNotificationOpened("General", "hello", canonicalExtra(ejsonRoom))
        val out = fx.queue.drain()
        assertEquals(1, out.size)
        assertEquals("GENERAL", out[0].rid)
        assertEquals("c", out[0].t)
        assertEquals("m-1", out[0].messageId)
    }

    // 坑6：多组织防护——他服推送点击不得进房
    @Test
    fun `host mismatch skips room jump`() {
        val fx = Fixture("https://other.cn")
        fx.router.onNotificationOpened("General", "hello", canonicalExtra(ejsonRoom))
        assertTrue(fx.queue.drain().isEmpty())
    }

    @Test
    fun `unparseable payload is silently skipped`() {
        val fx = Fixture("https://a.cn")
        fx.router.onNotificationOpened("t", "s", "not-json{{{")
        assertTrue(fx.queue.drain().isEmpty())
    }

    @Test
    fun `oncall voice intent deferred to M10 - not enqueued`() {
        val ejson = """{"rid":"rv","type":"c","name":"n","sender":{"username":"u","name":"n"},"host":"https://a.cn","msgType":"oncall"}"""
        val fx = Fixture("https://a.cn")
        fx.router.onNotificationOpened("t", "s", canonicalExtra(ejson))
        assertTrue(fx.queue.drain().isEmpty())
    }

    @Test
    fun `oncall call-end falls through to room navigation`() {
        val ejson = """{"rid":"rv","type":"c","name":"n","sender":{"username":"u","name":"n"},"host":"https://a.cn","msgType":"oncall","isCallEnd":true}"""
        val fx = Fixture("https://a.cn")
        fx.router.onNotificationOpened("t", "s", canonicalExtra(ejson))
        val out = fx.queue.drain()
        assertEquals(1, out.size)
        assertEquals("rv", out[0].rid)
    }

    @Test
    fun `missing server url does not block enqueue`() {
        val fx = Fixture(null)
        fx.router.onNotificationOpened("General", "hello", canonicalExtra(ejsonRoom))
        assertEquals(1, fx.queue.drain().size)
    }
}
