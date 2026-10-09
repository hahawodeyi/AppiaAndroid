package cn.appia.im.core.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 检查时机对照（RN appReleaseQuery.ts:14-30 staleTime + AppUpdatePromptHost.tsx:45,68-75）：
 * 登录门、本地版本门、3h stale 窗口、失败保旧值、回前台重查、登录翻转触发。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppReleaseCheckControllerTest {

    private class Fixture {
        var now = 1_000_000L
        var fetchCalls = 0
        var nextResult: AppReleaseRow? = null
        var nextError: Exception? = null

        val row = AppReleaseRow(
            platform = "android",
            version = "2.0.0",
            url = "https://a.example.com/app.apk",
            is_force_update = false,
            file_hash = null,
            notes = "n",
        )

        fun controller(
            loggedIn: MutableStateFlow<Boolean>,
            scope: CoroutineScope,
            staleMs: Long = 100,
        ): AppReleaseCheckController = AppReleaseCheckController(
            loggedIn = loggedIn,
            scope = scope,
            localVersion = { "1.2.3" },
            nowMs = { now },
            staleMs = staleMs,
            fetchRelease = {
                fetchCalls++
                nextError?.let { throw it }
                nextResult
            },
        )
    }

    private fun fixture() = Fixture()

    @Test
    fun `logged out never fetches`() = runTest {
        val f = fixture()
        val loggedIn = MutableStateFlow(false)
        val c = f.controller(loggedIn, backgroundScope)
        c.checkIfStale()
        assertEquals(0, f.fetchCalls)
        assertNull(c.release.value)
    }

    @Test
    fun `empty local version disables check`() = runTest {
        val f = fixture()
        val c = AppReleaseCheckController(
            loggedIn = MutableStateFlow(true),
            scope = backgroundScope,
            localVersion = { "" },
            nowMs = { f.now },
            fetchRelease = { f.fetchCalls++; f.nextResult },
        )
        c.checkIfStale()
        assertEquals(0, f.fetchCalls)
    }

    @Test
    fun `fetches once then serves from stale window`() = runTest {
        val f = fixture()
        f.nextResult = f.row
        val c = f.controller(MutableStateFlow(true), backgroundScope)
        c.checkIfStale()
        assertEquals(f.row, c.release.value)
        c.checkIfStale() // RN staleTime：窗口内不重发
        c.checkIfStale()
        assertEquals(1, f.fetchCalls)
    }

    @Test
    fun `refetches only after stale window elapses`() = runTest {
        val f = fixture()
        f.nextResult = f.row
        val c = f.controller(MutableStateFlow(true), backgroundScope, staleMs = 100)
        c.checkIfStale()
        assertEquals(1, f.fetchCalls)
        f.now += 100 // 恰到 staleTime：仍新鲜（RN isStaleByTime 用 <=）
        c.checkIfStale()
        assertEquals(1, f.fetchCalls)
        f.now += 1
        c.checkIfStale()
        assertEquals(2, f.fetchCalls)
        assertEquals(f.row, c.release.value)
    }

    @Test
    fun `failure keeps previous release and retries next trigger`() = runTest {
        val f = fixture()
        f.nextResult = f.row
        val c = f.controller(MutableStateFlow(true), backgroundScope)
        c.checkIfStale()
        assertEquals(1, f.fetchCalls)

        f.nextError = IllegalStateException("app_release_http_500")
        f.now += 101 // 过窗 + 失败
        c.checkIfStale()
        assertEquals(2, f.fetchCalls)
        assertEquals(f.row, c.release.value) // RN error 态不清 data
        // 失败不动 lastSuccessAt → 仍 stale，下次触发立刻重试
        c.checkIfStale()
        assertEquals(3, f.fetchCalls)
    }

    @Test
    fun `success with null release still caches for the window`() = runTest {
        // RN：success=false → queryFn 正常返回 null → data=null 照样进 staleTime 缓存
        val f = fixture()
        f.nextResult = null
        val c = f.controller(MutableStateFlow(true), backgroundScope)
        c.checkIfStale()
        assertEquals(1, f.fetchCalls)
        f.now += 50
        c.checkIfStale()
        assertEquals(1, f.fetchCalls)
        assertNull(c.release.value)
    }

    @Test
    fun `start reacts to login flip and onAppForeground refetches when stale`() = runTest {
        val f = fixture()
        f.nextResult = f.row
        val loggedIn = MutableStateFlow(false)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val c = f.controller(loggedIn, scope, staleMs = 100)
        try {
            c.start()
            loggedIn.value = true // RN enabled 翻转 → 立即查询
            assertEquals(1, f.fetchCalls)

            f.now += 101 // 过窗后回前台 → 重查（AppUpdatePromptHost.tsx:68-75）
            c.onAppForeground()
            assertEquals(2, f.fetchCalls)
        } finally {
            scope.cancel()
        }
    }
}
