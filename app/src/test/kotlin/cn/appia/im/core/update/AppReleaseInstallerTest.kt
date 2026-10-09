package cn.appia.im.core.update

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * 安装器对照（RN services/appReleaseInstaller.ts）：缓存命中跳下载（坑 13）、临时名→正式名顺序、
 * 进度 0..1 边界（total 未知恒 0）、非 2xx 抛错、下载前清临时、清理旧版 APK、ACTION_VIEW 安装意图。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppReleaseInstallerTest {

    private val server = MockWebServer()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var installer: AppReleaseInstaller

    @Before
    fun setUp() {
        server.start()
        installer = AppReleaseInstaller(context, OkHttpClient())
        // Robolectric FileProvider 静态缓存/符号链接歧义（生产默认 FileProvider 不受影响）——
        // 注入假 content Uri 保持 ACTION_VIEW 意图断言（type/flags/scheme）仍走真实路径
        installer.uriForFile = { android.net.Uri.parse("content://test.provider/" + it.name) }
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        installer.downloadDir().deleteRecursively()
    }

    private fun row(version: String = "1.2.4", url: String? = null) = AppReleaseRow(
        platform = "android",
        version = version,
        url = url ?: server.url("/apk/Appia-$version.apk").toString(),
        is_force_update = false,
        file_hash = null,
        notes = "",
    )

    private fun finalApk(version: String = "1.2.4") = installer.apkFileFor(version)

    private fun tempApk() = java.io.File(installer.downloadDir(), "appia-release-downloading.apk")

    @Test
    fun cacheHitSkipsDownloadAndInstallsDirectly() = runBlocking {
        finalApk().writeBytes("cached-apk".toByteArray())
        installer.downloadAndInstall(row())
        assertEquals(0, server.requestCount) // 坑 13：同版本 APK 已在即不触网
        assertEquals("cached-apk", finalApk().readText())
        assertFalse(tempApk().exists())
    }

    @Test
    fun downloadsThenRenamesTempToFinalName() = runBlocking {
        server.enqueue(MockResponse().setBody("apk-content"))
        installer.downloadAndInstall(row())
        assertEquals(1, server.requestCount)
        assertEquals("/apk/Appia-1.2.4.apk", server.takeRequest().path)
        assertEquals("apk-content", finalApk().readText()) // 下载落正式名
        assertFalse(tempApk().exists()) // 临时名已改名移除
    }

    @Test
    fun progressStaysWithinBoundsAndEndsAtOne() = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(4096)))
        val values = mutableListOf<Float>()
        installer.downloadAndInstall(row()) { values.add(it) }
        assertTrue(values.isNotEmpty())
        assertTrue(values.all { it in 0f..1f })
        assertEquals(1f, values.last())
    }

    @Test
    fun progressIsZeroWhenContentLengthUnknown() = runBlocking {
        server.enqueue(MockResponse().setChunkedBody("chunked-apk-content", 5))
        val values = mutableListOf<Float>()
        installer.downloadAndInstall(row()) { values.add(it) }
        assertTrue(values.isNotEmpty())
        assertTrue(values.all { it == 0f }) // RN :67 total<=0 → 恒 0
    }

    @Test
    fun non2xxThrowsHttpErrorAndLeavesNoTemp() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        try {
            installer.downloadAndInstall(row())
            fail("expected http error")
        } catch (e: IllegalStateException) {
            assertEquals("app_release_download_http_404", e.message)
        }
        assertFalse(tempApk().exists())
        assertFalse(finalApk().exists())
    }

    @Test
    fun staleTempFileRemovedBeforeDownloadAttempt() = runBlocking {
        tempApk().writeBytes("stale".toByteArray())
        server.enqueue(MockResponse().setResponseCode(500))
        try {
            installer.downloadAndInstall(row())
            fail("expected http error")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.startsWith("app_release_download_http_"))
        }
        assertFalse(tempApk().exists()) // RN :56 先清临时名
    }

    @Test
    fun cleanupDeletesTempAndNonNewerApksKeepsNewer() = runBlocking {
        val dir = installer.downloadDir()
        java.io.File(dir, "appia-release-1.2.3.apk").writeBytes("a".toByteArray())
        val newer = java.io.File(dir, "appia-release-1.2.4.apk")
        newer.writeBytes("b".toByteArray())
        tempApk().writeBytes("c".toByteArray())
        val other = java.io.File(dir, "unrelated.txt").also { it.writeBytes("d".toByteArray()) }

        installer.cleanupInstalled(localVersion = "1.2.3")

        assertFalse(java.io.File(dir, "appia-release-1.2.3.apk").exists()) // 不严格新于本地 → 删
        assertFalse(tempApk().exists()) // 临时文件 → 删
        assertTrue(newer.exists()) // 新版本缓存（未安装）→ 留（RN :102 判定）
        assertTrue(other.exists()) // 前缀不匹配 → 留
    }

    @Test
    fun cleanupParsesEncodedPrereleaseVersionNames() = runBlocking {
        val dir = installer.downloadDir()
        val prerelease = installer.apkFileFor("1.2.3-beta.1").also { it.writeBytes("a".toByteArray()) }
        val emptyVersion = java.io.File(dir, "appia-release-.apk").also { it.writeBytes("b".toByteArray()) }

        installer.cleanupInstalled(localVersion = "1.2.3")

        assertFalse(prerelease.exists()) // coerce 1.2.3 不严格新于 1.2.3 → 删
        assertTrue(emptyVersion.exists()) // 空版本段解析失败 → 留
    }

    @Test
    fun installFiresActionViewWithContentUriAndGrantFlags() = runBlocking {
        server.enqueue(MockResponse().setBody("apk"))
        installer.downloadAndInstall(row())

        val intent = Shadows.shadowOf(context).nextStartedActivity
        assertNotNull(intent)
        assertEquals(android.content.Intent.ACTION_VIEW, intent.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertTrue(intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("content", intent.data!!.scheme) // FileProvider content URI（非 file://）
    }
}
