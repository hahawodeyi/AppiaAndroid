package cn.appia.im.core.push

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// JUnit4 + Robolectric（钉 SDK 34），meta-data 解析走真实合并 manifest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AliyunPushBootstrapTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        AliyunPushBootstrap.resetForTest()
    }

    // ---- 结果码谓词（坑 1：10000 与 PUSH_20110 均为成功） ----

    @Test
    fun `10000 is success`() {
        assertTrue(AliyunPushBootstrap.isSuccessCode(AliyunPushBootstrap.CODE_SUCCESS))
    }

    @Test
    fun `PUSH_20110 already-registered is also success`() {
        assertTrue(AliyunPushBootstrap.isSuccessCode(AliyunPushBootstrap.CODE_ALREADY_REGISTERED))
    }

    @Test
    fun `other codes are not success`() {
        assertFalse(AliyunPushBootstrap.isSuccessCode("1001"))
        assertFalse(AliyunPushBootstrap.isSuccessCode("200"))
        assertFalse(AliyunPushBootstrap.isSuccessCode(""))
        assertFalse(AliyunPushBootstrap.isSuccessCode(null))
    }

    // ---- 幂等闸门 ----

    @Test
    fun `double init guard - first wins`() {
        assertTrue(AliyunPushBootstrap.tryStart())
        assertFalse(AliyunPushBootstrap.tryStart())
    }

    @Test
    fun `init skips and does not crash when meta-data loader returns null`() {
        AliyunPushBootstrap.init(ApplicationProvider.getApplicationContext()) { null }
        // meta-data 缺失路径：闸门回滚，后续调用可重试
        assertTrue(AliyunPushBootstrap.tryStart())
    }

    // ---- manifest meta-data 解析 ----

    @Test
    fun `parses appkey and appsecret from merged manifest`() {
        val config = readManifestPushConfig(context)
        assertEquals("334251807", config?.appKey)
        assertEquals("4d9ed252fb624a24bc87f5b637a046b1", config?.appSecret)
    }
}
