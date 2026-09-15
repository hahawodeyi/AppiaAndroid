package cn.appia.im.feature.chatlist

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.datastore.AuthSession
import cn.appia.im.core.datastore.AuthSessionStore
import cn.appia.im.core.datastore.InMemoryKvStore
import cn.appia.im.core.database.DatabaseManager
import cn.appia.im.core.network.AuthUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ChatListViewModel 守卫 + Room Flow 分组上屏（Robolectric + 本地 Room，同 DatabaseManagerTest 约定）。
 * 用 runBlocking + 实时超时等待 Room 的跨线程发射（invalidation 经 endTransaction 触发，同实例写有效）。
 */
@RunWith(RobolectricTestRunner::class)
// application=plain Application：AppiaApplication.onCreate 会初始化 MMKV，native .so 在 JVM 下不可加载
@Config(sdk = [34], application = Application::class)
class ChatListViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var manager: DatabaseManager

    @Before
    fun setUp() {
        manager = DatabaseManager(context)
    }

    @After
    fun tearDown() {
        manager.resetAll()
    }

    private fun viewModel(serverUrl: String): ChatListViewModel {
        val auth = AuthSessionStore(InMemoryKvStore())
        auth.save(AuthSession(token = "token", user = AuthUser(id = "user-x"), serverUrl = serverUrl))
        return ChatListViewModel(
            dbManager = manager,
            serverUrl = serverUrl,
            auth = auth,
            scope = CoroutineScope(Dispatchers.IO),
        )
    }

    @Test
    fun `room flow emits filtered rows grouped into three sections`() {
        runBlocking {
            val serverUrl = "https://chat-a.example.com"
            manager.switchDatabase(serverUrl)
            val vm = viewModel(serverUrl)
            val db = manager.databaseFor(manager.normalizeServer(serverUrl))
            db.chatDao().insertAll(
                listOf(
                    chatRow(_id = "self", t = "d", lm = 90.0, uids = """["user-x"]"""),
                    chatRow(_id = "todo-1", todoCount = 1.0, lm = 100.0),
                    chatRow(_id = "ch", lm = 50.0),
                    // observeList WHERE 三条件各排除一行
                    chatRow(_id = "bot-x", bot = true, lm = 200.0),
                    chatRow(_id = "closed", open = false, lm = 200.0),
                    chatRow(_id = "arch", archived = true, lm = 200.0),
                ),
            )

            val sections = withTimeout(5_000) {
                vm.sections.first { s -> s.sumOf { it.chats.size } >= 3 }
            }

            assertEquals(
                listOf(ChatListSectionKey.ASSISTANT, ChatListSectionKey.TODO, ChatListSectionKey.CHANNELS),
                sections.map { it.key },
            )
            assertEquals(listOf("self"), sections[0].chats.map { it._id })
            assertEquals(listOf("todo-1"), sections[1].chats.map { it._id })
            assertEquals(listOf("ch"), sections[2].chats.map { it._id })
        }
    }

    @Test
    fun `stale org db callbacks are cleared by activeDb guard`() {
        runBlocking {
            val serverA = "https://chat-a.example.com"
            val serverB = "https://chat-b.example.com"
            manager.switchDatabase(serverA)
            val vm = viewModel(serverA)
            val dbA = manager.databaseFor(manager.normalizeServer(serverA))
            dbA.chatDao().insert(chatRow(_id = "a-1", lm = 10.0))
            withTimeout(5_000) { vm.sections.first { it.isNotEmpty() } }

            // 切组织：active 指向 B；A 库再写 → 该库回调被守卫拦截（对照 RN 换服先清空 chats）
            manager.switchDatabase(serverB)
            dbA.chatDao().insert(chatRow(_id = "a-2", lm = 20.0))
            withTimeout(5_000) { vm.sections.first { it.isEmpty() } }
        }
    }
}
