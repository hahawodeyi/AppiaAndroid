package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketSdk
import cn.appia.im.feature.settings.isGuestUser
import cn.appia.im.feature.settings.isServerVersionNewer
import cn.appia.im.feature.settings.readableAppVersion
import cn.appia.im.feature.settings.serverHostLabel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

/**
 * M5-T9 用户偏好/状态端点 wire 对照（RN services/api/userPreferences.ts + userStatus.ts +
 * logoutOtherDevices.ts 逐字段）：
 * - users.setPreferences body {userId, data}
 * - users.setStatus body {status?, message?}（缺省不编码）
 * - users.removeOtherTokens body {userId}
 * - parseBoolUserPref（RN lib/userSessionPrefs.ts:8-11）
 */
class UserPreferencesApiTest {
    private val server = MockWebServer()

    @BeforeEach
    fun setUp() {
        server.start()
    }

    @AfterEach
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun newSdk(): RocketSdk =
        RocketSdk(client = OkHttpClient()).also { it.hydrateRestSession(server.url("/").toString(), "tok", "uid") }

    private fun body(path: String): kotlinx.serialization.json.JsonObject {
        val req = server.takeRequest()
        assertEquals(path, req.path)
        return Json.parseToJsonElement(req.body.readUtf8()).jsonObject
    }

    @Test
    fun `setPreferences posts userId and data map`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        UserPreferencesApi.setUserPreferences(
            newSdk(),
            "u1",
            mapOf("appiaAvatarType" to JsonPrimitive("letter")),
        )

        val body = body("/api/v1/users.setPreferences")
        assertEquals("u1", body["userId"]!!.jsonPrimitive.content)
        assertEquals("letter", body["data"]!!.jsonObject["appiaAvatarType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `setPreferences encodes json booleans natively`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        UserPreferencesApi.setUserPreferences(
            newSdk(),
            "u1",
            mapOf("showImageSummary" to JsonPrimitive(false)),
        )

        val data = body("/api/v1/users.setPreferences")["data"]!!.jsonObject
        // RN data: Record<string, unknown>——boolean 原生形态（MessageSettingScreen 传 boolean）
        assertEquals(false, data["showImageSummary"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `setStatus posts status and message`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        UserPreferencesApi.setUserStatus(newSdk(), status = "online", message = "hi")

        val body = body("/api/v1/users.setStatus")
        assertEquals("online", body["status"]!!.jsonPrimitive.content)
        assertEquals("hi", body["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `setStatus omits absent fields`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        UserPreferencesApi.setUserStatus(newSdk(), message = "only message")

        val body = body("/api/v1/users.setStatus")
        assertEquals("only message", body["message"]!!.jsonPrimitive.content)
        assertNull(body["status"]) // JS undefined 键缺省同义
    }

    @Test
    fun `removeOtherTokens posts userId`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        UserPreferencesApi.removeOtherTokens(newSdk(), "u1")

        val body = body("/api/v1/users.removeOtherTokens")
        assertEquals("u1", body["userId"]!!.jsonPrimitive.content)
    }

    // ---- parseBoolUserPref（RN lib/userSessionPrefs.ts:8-11）----

    @Test
    fun `parseBoolUserPref true false and zero strings`() {
        assertTrue(parseBoolUserPref(JsonPrimitive(true)))
        assertFalse(parseBoolUserPref(JsonPrimitive(false)))
        assertFalse(parseBoolUserPref(JsonPrimitive("false")))
        assertFalse(parseBoolUserPref(JsonPrimitive("0")))
        assertTrue(parseBoolUserPref(JsonPrimitive("true")))
        assertTrue(parseBoolUserPref(JsonPrimitive("anything")))
    }

    @Test
    fun `parseBoolUserPref falls back on null and non primitive`() {
        assertTrue(parseBoolUserPref(null)) // 缺字段回退 true（RN 消息摘要缺省）
        assertTrue(parseBoolUserPref(JsonNull))
        assertFalse(parseBoolUserPref(null, fallback = false))
        // RN typeof 非 boolean/string → fallback（1.x 数字非字符串原语 → false）
        assertFalse(parseBoolUserPref(Json.parseToJsonElement("1"), fallback = false))
    }
}

/**
 * M5-T9 免登录端点对照（RN services/api/serverInfo.ts + appRelease.ts + compareAppVersions.ts）：
 * GET /api/info（免 IM token、非 /api/v1）与 /provider/api/v1/version。
 */
class ServerInfoApiTest {
    private val server = MockWebServer()

    @BeforeEach
    fun setUp() {
        server.start()
    }

    @AfterEach
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun client(): OkHttpClient = OkHttpClient()

    @Test
    fun `fetchServerVersion returns trimmed version`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"version":"6.4.0 "}"""))
        val v = ServerInfoApi.fetchServerVersion(server.url("/").toString(), client())
        assertEquals("6.4.0", v)
        assertEquals("/api/info", server.takeRequest().path)
    }

    @Test
    fun `fetchServerVersion null on failure false and empty`() = runBlocking {
        // HTTP 非 2xx
        server.enqueue(MockResponse().setResponseCode(500))
        assertNull(ServerInfoApi.fetchServerVersion(server.url("/").toString(), client()))
        // success:false
        server.enqueue(MockResponse().setBody("""{"success":false}"""))
        assertNull(ServerInfoApi.fetchServerVersion(server.url("/").toString(), client()))
        // 空白串 serverUrl
        assertNull(ServerInfoApi.fetchServerVersion("  ", client()))
        server.takeRequest()
    }

    @Test
    fun `fetchLatestAppRelease maps provider dto row`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"data":{"platform":"android","version":"1.2.0","url":"https://a.apk","isForceUpdate":false,"notes":"n"}}""",
            ),
        )
        val row = AppReleaseApi.fetchLatestAppRelease(
            baseUrl = server.url("/").toString(),
            localVersion = "1.1.0",
            client = client(),
        )
        assertEquals("1.2.0", row!!.version)
        assertEquals("https://a.apk", row.url)
        assertFalse(row.isForceUpdate)
        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/provider/api/v1/version"))
        assertTrue(req.path!!.contains("platform=android"))
        assertTrue(req.path!!.contains("versionName=1.1.0"))
    }

    @Test
    fun `fetchLatestAppRelease null on success false and throws on http error`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":false}"""))
        assertNull(
            AppReleaseApi.fetchLatestAppRelease(server.url("/").toString(), "1.0", client = client()),
        )
        server.enqueue(MockResponse().setResponseCode(503))
        var thrown: Exception? = null
        try {
            AppReleaseApi.fetchLatestAppRelease(server.url("/").toString(), "1.0", client = client())
        } catch (e: Exception) {
            thrown = e
        }
        assertEquals("app_release_http_503", thrown!!.message) // RN app_release_http_{status} 同款
    }

    @Test
    fun `resolveAppReleaseOpenUrl picks comma segment and falls back`() {
        assertEquals("https://a.apk", AppReleaseApi.resolveAppReleaseOpenUrl("https://a.apk,https://b.apk"))
        assertEquals("https://b.apk", AppReleaseApi.resolveAppReleaseOpenUrl(" , https://b.apk"))
        assertEquals("raw", AppReleaseApi.resolveAppReleaseOpenUrl("raw"))
        assertEquals("", AppReleaseApi.resolveAppReleaseOpenUrl(" "))
    }
}

/** 版本比较（RN utils/compareAppVersions.ts isServerVersionNewer）。 */
class VersionCompareTest {
    @Test
    fun `semver comparison wins when both coercible`() {
        assertTrue(isServerVersionNewer("1.2.1", "1.2.0"))
        assertFalse(isServerVersionNewer("1.1.0", "1.2.0")) // 服务端更旧
        assertTrue(isServerVersionNewer("v2.0.0-beta", "1.9.9")) // coerce 提取
        assertFalse(isServerVersionNewer("1.2", "1.2")) // 相等不更新
    }

    @Test
    fun `falls back to lexicographic when not coercible`() {
        // 'abc' coerce 不出 → 字典序 local < server
        assertTrue(isServerVersionNewer("beta", "alpha"))
        assertFalse(isServerVersionNewer("alpha", "beta"))
    }

    @Test
    fun `empty sides return false`() {
        assertFalse(isServerVersionNewer("", "1.0"))
        assertFalse(isServerVersionNewer("1.0", ""))
        assertFalse(isServerVersionNewer(" ", " "))
    }
}

/** 设置页辅助（serverHostLabel / readableAppVersion / guest 判定）。 */
class SettingsHelpersTest {
    @Test
    fun `serverHostLabel parses url host and strips protocol fallback`() {
        assertEquals("chat.example.com", serverHostLabel("https://chat.example.com/path"))
        assertEquals("chat.example.com", serverHostLabel("chat.example.com/x/y"))
        assertEquals(":", serverHostLabel("://nohost/")) // RN regex 同结果：无 scheme 前缀不剥，取首段 ':'
        assertEquals("", serverHostLabel("   "))
    }

    @Test
    fun `readableAppVersion replaces last dot segment with dash`() {
        assertEquals("0.5.0-1", readableAppVersion("0.5.0", 1))
        assertEquals("1.2.3-42", readableAppVersion("1.2.3", 42))
    }

    @Test
    fun `guest judgment matches substring`() {
        assertTrue(isGuestUser("appia.guest.abc123"))
        assertTrue(isGuestUser("xappia.guest"))
        assertFalse(isGuestUser("bob"))
        assertFalse(isGuestUser(""))
    }
}
