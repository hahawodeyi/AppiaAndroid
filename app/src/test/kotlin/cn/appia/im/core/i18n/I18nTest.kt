package cn.appia.im.core.i18n

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric 官方仅支持 JUnit4 runner，经 vintage 引擎混跑在 JUnit Platform 上；
// SDK 36 沙箱要 Java 21，工程是 Java 17，故钉在 SDK 34
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class I18nTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `key count matches RN json`() {
        // I18nKeys.ALL 由迁移脚本生成；断言数量 > 800，防止脚本漏搬
        assertTrue(I18nKeys.ALL.size > 800)
    }

    @Test
    fun `lookup is case-insensitive on module prefix`() {
        assertEquals(context.t("roominfo_moremembers"), context.t("roomInfo_moreMembers"))
    }

    @Test
    fun `xml escaped characters survive resource round-trip`() {
        // & 与 ' 由迁移脚本转义， getString 时应还原为原始文案
        assertEquals("COP & Docs", context.t("Room_Sort_COP"))
        assertEquals("What's new", context.t("appUpdate_whatsNew"))
    }

    @Test
    fun `missing key falls back to key itself`() {
        assertEquals("noSuch_key_here", context.t("noSuch_key_here"))
    }

    @Test
    fun `percent literal is safe on no-arg call`() {
        // 回归：getString(resId, *args) 空参 spread 仍走 String.format，
        // 裸 % 结尾的文案（appupdate_downloading）会抛 UnknownFormatConversionException
        assertEquals("Downloading update {{percent}}%", context.t("appUpdate_downloading"))
    }
}
