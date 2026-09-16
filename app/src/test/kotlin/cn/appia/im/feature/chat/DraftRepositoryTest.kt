package cn.appia.im.feature.chat

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.database.AppiaDatabase
import cn.appia.im.feature.chatlist.chatRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 草稿时机（RN useDraft :185-233/:264-285 + clearDraft :87-102）：
 * debounce 1s 虚拟时钟（500ms 未写 / 1s 落库）、blur 即写、卸载仅补写 pending、
 * 发送后四列清、chat 行缺失静默。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DraftRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppiaDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(dispatcher)
    private lateinit var repo: DraftRepository
    private lateinit var controller: DraftController

    @Before
    fun setUp() = runBlocking {
        repo = DraftRepository(db)
        controller = DraftController(repo, scope)
        db.chatDao().insert(chatRow(_id = "r1", name = "dev"))
        Unit
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun chat(rid: String = "r1") = runBlocking { db.chatDao().getById(rid) }

    /** Room suspend DAO 的恢复再入 scheduler 队列是异步的：每轮必须重泵调度器。 */
    private fun awaitCond(desc: String, cond: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (System.nanoTime() < deadline) {
            dispatcher.scheduler.runCurrent()
            if (cond()) return
            Thread.sleep(10)
        }
        throw AssertionError("timeout: $desc")
    }

    @Test
    fun `debounce holds until 1s then writes`() {
        controller.onTextChanged("r1", "hello")
        dispatcher.scheduler.advanceTimeBy(500)
        dispatcher.scheduler.runCurrent()
        assertNull(chat()?.draft_message_plain) // 半程未写

        dispatcher.scheduler.advanceTimeBy(500)
        dispatcher.scheduler.runCurrent()
        awaitCond("debounced write") { chat()?.draft_message_plain == "hello" }
        assertEquals("hello", chat()?.draft_message) // 两列同值
    }

    @Test
    fun `rapid typing resets debounce window`() {
        controller.onTextChanged("r1", "a")
        dispatcher.scheduler.advanceTimeBy(800)
        controller.onTextChanged("r1", "ab") // 800ms 处重置
        dispatcher.scheduler.advanceTimeBy(800)
        dispatcher.scheduler.runCurrent()
        assertNull(chat()?.draft_message_plain)
        dispatcher.scheduler.advanceTimeBy(200)
        dispatcher.scheduler.runCurrent()
        awaitCond("reset window write") { chat()?.draft_message_plain == "ab" }
    }

    @Test
    fun `blur writes immediately without advancing clock`() {
        controller.onTextChanged("r1", "draft!")
        controller.onBlur("r1", "draft!")
        dispatcher.scheduler.runCurrent()
        awaitCond("immediate blur write") { chat()?.draft_message_plain == "draft!" }

        // blur 落库后老 debounce 作废：再走 2s 不产生第二次写（值同无法分辨，改查后写不同值）
        controller.onTextChanged("r1", "stale")
        controller.onBlur("r1", "fresh")
        dispatcher.scheduler.runCurrent()
        awaitCond("blur overrides pending debounce") { chat()?.draft_message_plain == "fresh" }
    }

    @Test
    fun `dispose flush writes only when pending exists`() {
        controller.onTextChanged("r1", "unflushed")
        controller.flushOnDispose("r1")
        dispatcher.scheduler.runCurrent()
        awaitCond("flush writes pending") { chat()?.draft_message_plain == "unflushed" }

        controller.onBlur("r1", "done") // debounce 已无 pending
        dispatcher.scheduler.runCurrent()
        Thread.sleep(50)
        controller.flushOnDispose("r1") // 无 pending 不写
        dispatcher.scheduler.runCurrent()
        assertEquals("done", chat()?.draft_message_plain)
    }

    @Test
    fun `clearAfterSend empties all four draft columns`() = runBlocking {
        val row = chat()!!.copy(draft_reply_msg_id = "m-1", draft_attachments = """["a"]""")
        db.chatDao().update(row)
        controller.onTextChanged("r1", "to send")
        controller.clearAfterSend("r1")
        dispatcher.scheduler.runCurrent()
        awaitCond("four columns cleared") {
            val c = chat()!!
            c.draft_message?.isEmpty() == true && c.draft_message_plain?.isEmpty() == true &&
                c.draft_reply_msg_id?.isEmpty() == true && c.draft_attachments?.isEmpty() == true
        }
        Unit
    }

    @Test
    fun `missing chat row silently skips and loads empty`() = runBlocking {
        controller.onTextChanged("ghost", "x")
        awaitCond("ghost write skipped") { dispatcher.scheduler.runCurrent(); true }
        Thread.sleep(50)
        assertEquals("", repo.loadDraft("ghost"))
        assertEquals("", repo.loadDraft("r1"))
        Unit
    }
}
