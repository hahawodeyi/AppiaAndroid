package cn.appia.im.feature.chat

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 附件选择拷贝链（Robolectric + 临时目录 + file:// 直读）：
 * 存储名清洗/唯一名格式（RN sanitizeUploadFileName/buildUniqueUploadStorageName）、容量上限 100、
 * preparing→ready / prepare-failed、readyFiles 过滤、clear 代数守卫（迟到拷贝不回写）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PendingAttachmentsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val uploadsDir = File(context.cacheDir, "uploads-test-${System.nanoTime()}")
    private val scheduler = TestCoroutineScheduler()
    private val testDispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(testDispatcher)

    private val counter = intArrayOf(0)

    private fun newPending(maxCount: Int = PendingAttachments.MAX_COUNT) = PendingAttachments(
        uploadsDir = uploadsDir,
        resolver = context.contentResolver,
        scope = scope,
        maxCount = maxCount,
        newId = { "att-${counter[0].also { counter[0] = it + 1 }}" },
        clockMs = { 1_700_000_000_000L },
        random36 = { "rand01" },
        ioDispatcher = testDispatcher,
    )

    private fun sourceFile(tag: String): File =
        File(context.cacheDir, "src-$tag.txt").apply { writeText("payload-$tag") }

    private fun fileSource(tag: String) = SelectedSource(
        source = "file",
        name = "$tag.txt",
        type = "text/plain",
        size = 8L,
        uri = Uri.fromFile(sourceFile(tag)).toString(),
    )

    @Before
    fun setUp() {
        uploadsDir.mkdirs()
        counter[0] = 0
    }

    @After
    fun tearDown() {
        scope.cancel()
        uploadsDir.deleteRecursively()
        context.cacheDir.listFiles()?.filter { it.name.startsWith("src-") }?.forEach { it.delete() }
    }

    // ---- UploadFileNamer ----

    @Test
    fun `sanitize replaces illegal chars and leading dot`() {
        assertEquals("a_b_c", UploadFileNamer.sanitize("a/b\\c"))
        assertEquals("a_b__", UploadFileNamer.sanitize("a:b*?")) // RN 字符类含 *
        assertEquals("_.hidden", UploadFileNamer.sanitize(".hidden")) // RN `_${safeName}` 保留原点
        assertEquals("file", UploadFileNamer.sanitize("   "))
    }

    @Test
    fun `uniqueStorageName keeps ts rand base and lowercased extension`() {
        assertEquals(
            "ts36-rand01-Report.jpg",
            UploadFileNamer.uniqueStorageName("Report.JPG", "ts36", "rand01"),
        )
        assertEquals("ts36-rand01-noext.bin", UploadFileNamer.uniqueStorageName("noext", "ts36", "rand01"))
        // 隐藏文件：sanitize 先补 _，ext 取首尾点后段（RN extensionIndex>0 同构）
        assertEquals("ts36-rand01-_.x", UploadFileNamer.uniqueStorageName(".x", "ts36", "rand01"))
        // base 截 120
        val name = UploadFileNamer.uniqueStorageName("x".repeat(200) + ".png", "t", "r")
        assertTrue(name.startsWith("t-r-${"x".repeat(120)}.png"))
    }

    // ---- add：拷贝 + prepareStatus ----

    @Test
    fun `add creates preparing rows then ready with copied local path`() = runBlocking {
        val pending = newPending()
        val result = pending.add(listOf(fileSource("a"), fileSource("b")))

        assertEquals(2, result.sourceCount)
        assertEquals(2, result.acceptedCount)
        // 拷贝前：preparing 可见（RN 行先以 preparing 上屏）
        assertEquals(
            listOf(PrepareStatus.PREPARING, PrepareStatus.PREPARING),
            pending.items.value.map { it.prepareStatus },
        )

        scheduler.advanceUntilIdle()

        val items = pending.items.value
        assertEquals(listOf(PrepareStatus.READY, PrepareStatus.READY), items.map { it.prepareStatus })
        val copied = items.first().localPath!!
        assertTrue(copied.startsWith(uploadsDir.absolutePath + "/"))
        assertTrue(copied.endsWith(".txt"))
        assertEquals("payload-a", File(copied).readText())
        return@runBlocking Unit
    }

    @Test
    fun `copy failure marks prepare-failed like RN`() = runBlocking {
        val pending = newPending()
        pending.add(
            listOf(
                SelectedSource(
                    source = "file",
                    name = "ghost.txt",
                    type = "text/plain",
                    uri = "content://nonexistent/ghost",
                ),
            ),
        )
        scheduler.advanceUntilIdle()

        assertEquals(PrepareStatus.PREPARE_FAILED, pending.items.value.single().prepareStatus)
        assertNull(pending.items.value.single().localPath)
        assertEquals(1, pending.prepareFailedCount)
        return@runBlocking Unit
    }

    @Test
    fun `add truncates at maxCount and reports source vs accepted`() = runBlocking {
        val pending = newPending(maxCount = 2)
        val first = pending.add(listOf(fileSource("a"), fileSource("b")))
        assertEquals(2, first.acceptedCount)
        val second = pending.add(listOf(fileSource("c"), fileSource("d"), fileSource("e")))

        assertEquals(3, second.sourceCount)
        assertEquals(0, second.acceptedCount) // 容量已满（RN capacity = max(maxCount - current, 0)）
        scheduler.advanceUntilIdle()
        assertEquals(2, pending.items.value.size)
        return@runBlocking Unit
    }

    @Test
    fun `readyFiles only includes ready items`() = runBlocking {
        val pending = newPending()
        pending.add(listOf(fileSource("ok")))
        scheduler.advanceUntilIdle()
        pending.add(
            listOf(
                SelectedSource(source = "file", name = "bad", type = "t", uri = "content://nonexistent/bad"),
            ),
        )
        scheduler.advanceUntilIdle()

        val ready = pending.readyFiles
        assertEquals(1, ready.size)
        assertEquals("ok.txt", ready.single().name)
        assertEquals(1, pending.prepareFailedCount)
        assertEquals(false, pending.isPreparing)
        return@runBlocking Unit
    }

    // ---- remove / clear（含迟到拷贝代数守卫） ----

    @Test
    fun `remove drops single item`() = runBlocking {
        val pending = newPending()
        pending.add(listOf(fileSource("a"), fileSource("b")))
        scheduler.advanceUntilIdle()

        pending.remove(pending.items.value.first().id)
        assertEquals(1, pending.items.value.size)
        assertEquals(1, pending.readyFiles.size)
        return@runBlocking Unit
    }

    @Test
    fun `clear guards late copies from resurrecting items`() {
        val lateScheduler = TestCoroutineScheduler()
        val lateScope = CoroutineScope(StandardTestDispatcher(lateScheduler))
        val pending = PendingAttachments(
            uploadsDir = uploadsDir,
            resolver = context.contentResolver,
            scope = lateScope,
            newId = { "x" },
            ioDispatcher = StandardTestDispatcher(lateScheduler),
        )
        pending.add(listOf(fileSource("c")))
        pending.clear() // 代数推进：迟到的拷贝不回写（RN generationRef 同义）
        lateScheduler.advanceUntilIdle()
        assertTrue(pending.items.value.isEmpty())
        lateScope.cancel()
    }

    @Test
    fun `max count constant is 100 like RN`() {
        assertEquals(100, PendingAttachments.MAX_COUNT)
    }
}
