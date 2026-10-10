package cn.appia.im.core.push

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 跨组织未读刷新路由测试（backlog #9，RN crossOrgUnreadRefresh.test.ts 对照移植）：
 * 到达事件 host ≠ 当前主体 → 刷新；host/主体缺失或同主体（含子域）→ 跳过；解析失败不抛。
 * （走 android.util.Log → Robolectric，PushClickRouterTest 同款。）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CrossOrgUnreadRefreshTest {

    private val current = { "https://ssc.appia.cn" }

    private fun rawOf(ejson: String): JsonObject = buildJsonObject {
        put("ejson", JsonPrimitive(ejson))
    }

    /** sink 缝挂卸（@Volatile var，finally 复原防串场）。 */
    private fun withRefreshes(vararg counts: IntArray, block: () -> Unit) {
        CrossOrgUnreadRefresh.action = { counts.forEach { it[0]++ } }
        try {
            block()
        } finally {
            CrossOrgUnreadRefresh.action = null
        }
    }

    // ---- host 分流（RN maybeRefreshCrossOrgUnreadFromPush :16-26）----

    @Test
    fun `same host skips refresh`() {
        val n = intArrayOf(0)
        withRefreshes(n) {
            CrossOrgUnreadRefresh.route(
                rawOf("""{"rid":"r1","type":"c","host":"https://ssc.appia.cn"}"""),
                current,
            )
        }
        assertEquals(0, n[0])
    }

    @Test
    fun `cross-org host triggers refresh once`() {
        val n = intArrayOf(0)
        withRefreshes(n) {
            CrossOrgUnreadRefresh.route(
                rawOf("""{"rid":"r1","type":"c","host":"https://appia.cn"}"""),
                current,
            )
            CrossOrgUnreadRefresh.route(
                rawOf("""{"rid":"r1","type":"c","host":"https://ssc.appia.cn"}"""),
                current,
            )
        }
        assertEquals(1, n[0])
    }

    @Test
    fun `missing push host or missing current server skips refresh`() {
        val n = intArrayOf(0)
        withRefreshes(n) {
            CrossOrgUnreadRefresh.route(rawOf("""{"rid":"r1","type":"c"}"""), current)
            CrossOrgUnreadRefresh.route(
                rawOf("""{"rid":"r1","type":"c","host":"https://appia.cn"}"""),
            ) { null }
        }
        assertEquals(0, n[0])
    }

    @Test
    fun `subdomain push counts as another org per RN arrival semantics`() {
        // RN isSameHost 含协议互为包含：push.ssc.appia.cn ≠ ssc.appia.cn（点击路径的
        // 子域后缀=同主体语义不适用到达刷新——宁宽勿漏，漏刷代价仅角标暂旧）
        val n = intArrayOf(0)
        withRefreshes(n) {
            CrossOrgUnreadRefresh.route(
                rawOf("""{"rid":"r1","type":"c","host":"https://push.ssc.appia.cn"}"""),
                current,
            )
        }
        assertEquals(1, n[0])
    }

    // ---- 解析失败（RN routeCrossOrgUnreadFromPushEvent :28-36 静默）----

    @Test
    fun `malformed raw does not throw and skips refresh`() {
        val n = intArrayOf(0)
        withRefreshes(n) {
            CrossOrgUnreadRefresh.route(rawOf("not-json"), current)
            CrossOrgUnreadRefresh.route(rawOf("""{"name":"no-rid"}"""), current)
        }
        assertEquals(0, n[0])
    }

    // ---- extraMap 形态（receiver 到达回调入参 → raw 袋）----

    @Test
    fun `extraMap entries become raw string bag`() {
        val raw = extraMapToRaw(
            mapOf(
                "ejson" to """{"rid":"r1","type":"c","host":"https://appia.cn"}""",
                "title" to "hello",
            ),
        )
        assertTrue((raw["ejson"] as JsonPrimitive).isString)
        assertEquals("hello", (raw["title"] as JsonPrimitive).content)

        val n = intArrayOf(0)
        withRefreshes(n) { CrossOrgUnreadRefresh.route(raw, current) }
        assertEquals(1, n[0])
    }

    // ---- sink 缝（未读数据面未建：未注册仅日志；注册后回调且异常吞）----

    @Test
    fun `action failure is swallowed and does not break routing`() {
        CrossOrgUnreadRefresh.action = { error("boom") }
        try {
            CrossOrgUnreadRefresh.route(
                rawOf("""{"rid":"r1","type":"c","host":"https://appia.cn"}"""),
                current,
            )
        } finally {
            CrossOrgUnreadRefresh.action = null
        }
    }
}
