package cn.appia.im.feature.chat.forward

import cn.appia.im.core.network.api.ForwardSearchRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 转发搜索 debounce（RN useForwardSearch.ts：300ms debounce + requestId 防陈旧 + 空词即清 +
 * 失败清空）：requestId 语义由「换词取消上游协程」等价实现。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForwardSearcherTest {

    @Test
    fun `debounces 300ms and drops stale queries`() = runTest {
        val fetched = mutableListOf<String>()
        val searcher = ForwardSearcher({ q -> fetched += q; listOf(ForwardSearchRow.User("u1", q, q)) }, backgroundScope)
        // WhileSubscribed 需要常驻收集者
        backgroundScope.launch { searcher.state.collect {} }

        searcher.onQueryChanged("a")
        advanceTimeBy(299)
        searcher.onQueryChanged("ab") // 300ms 窗口内换词 → 'a' 永不发请求
        advanceTimeBy(299)
        runCurrent()
        assertEquals(emptyList<String>(), fetched)
        assertTrue(searcher.state.value.loading)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("ab"), fetched)
        assertFalse(searcher.state.value.loading)
        assertEquals(listOf(ForwardSearchRow.User("u1", "ab", "ab")), searcher.state.value.rows)
    }

    @Test
    fun `blank query clears immediately without fetch`() = runTest {
        val fetched = mutableListOf<String>()
        val searcher = ForwardSearcher({ q -> fetched += q; emptyList<ForwardSearchRow>() }, backgroundScope)
        backgroundScope.launch { searcher.state.collect {} }

        searcher.onQueryChanged("\u5f20")
        advanceTimeBy(301)
        runCurrent()
        assertEquals(listOf("\u5f20"), fetched)

        searcher.onQueryChanged("") // 空词：立即清空，不 fetch
        runCurrent()
        assertFalse(searcher.state.value.loading)
        assertTrue(searcher.state.value.rows.isEmpty())
        assertEquals(1, fetched.size)
    }

    @Test
    fun `search failure yields empty rows and stops loading`() = runTest {
        val searcher = ForwardSearcher({ throw RuntimeException("boom") }, backgroundScope)
        backgroundScope.launch { searcher.state.collect {} }
        searcher.onQueryChanged("x")
        advanceTimeBy(301)
        runCurrent()
        assertFalse(searcher.state.value.loading)
        assertTrue(searcher.state.value.rows.isEmpty())
    }
}
