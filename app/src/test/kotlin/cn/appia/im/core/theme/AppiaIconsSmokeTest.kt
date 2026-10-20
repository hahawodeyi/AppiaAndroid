package cn.appia.im.core.theme

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createComposeRule
import cn.appia.im.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M12-T2：十个手工移植 drawable 可加载可渲染（pathData 语法错在此暴露）。M12-T7 增补 edit/restore/delete + voice 两徽标。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AppiaIconsSmokeTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `fifteen hand-ported icons render`() {
        rule.setContent {
            Row {
                Icon(painterResource(R.drawable.ic_appia_mute), contentDescription = null)
                Icon(painterResource(R.drawable.ic_appia_send), contentDescription = null)
                Icon(painterResource(R.drawable.ic_appia_stop), contentDescription = null)
                Icon(painterResource(R.drawable.ic_appia_email), contentDescription = null)
                Icon(painterResource(R.drawable.ic_appia_compose), contentDescription = null)
                Icon(painterResource(R.drawable.ic_appia_right_arrow), contentDescription = null)
                Icon(painterResource(R.drawable.ic_appia_unread), contentDescription = null)
                Icon(painterResource(R.drawable.ic_appia_read), contentDescription = null)
                Icon(painterResource(R.drawable.ic_appia_star), contentDescription = null)
                Icon(painterResource(R.drawable.ic_appia_star_filled), contentDescription = null)
                Icon(painterResource(R.drawable.ic_appia_edit), contentDescription = null)
                Icon(painterResource(R.drawable.ic_appia_restore), contentDescription = null)
                Icon(painterResource(R.drawable.ic_appia_delete), contentDescription = null)
                Icon(painterResource(R.drawable.ic_appia_voice_muted_badge), contentDescription = null)
                Icon(painterResource(R.drawable.ic_appia_voice_speaking_badge), contentDescription = null)
            }
        }
        rule.waitForIdle() // pathData 非法在此抛异常
    }
}
