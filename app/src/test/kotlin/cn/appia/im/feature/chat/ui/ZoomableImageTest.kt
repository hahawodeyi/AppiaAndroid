package cn.appia.im.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.unit.dp
import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ZoomableImage 手势单测（Robolectric 注入触摸；模拟器 input 间隔超双击窗口，捏合无法合成）：
 * 双击 2x 开关、pinch 放大夹 4x、捏合缩回 1x 下限（binding 裁定 2 的 1-4x 语义）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ZoomableImageTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setUpZoom(onScale: (Float) -> Unit) {
        compose.setContent {
            ZoomableImage(
                modifier = Modifier.size(300.dp),
                onScaleChanged = onScale,
            ) {
                Box(Modifier.fillMaxSize().background(Color.Red))
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `double click toggles 2x and back to 1x`() {
        var scale = 1f
        setUpZoom { scale = it }
        compose.onNodeWithTag("qa-zoomable").performTouchInput { doubleClick(center) }
        compose.runOnIdle { assertEquals(2f, scale) }
        compose.onNodeWithTag("qa-zoomable").performTouchInput { doubleClick(center) }
        compose.runOnIdle { assertEquals(1f, scale) }
    }

    @Test
    fun `pinch out zooms up and clamps at 4x`() {
        var scale = 1f
        setUpZoom { scale = it }
        compose.onNodeWithTag("qa-zoomable").performTouchInput {
            pinch(
                start0 = center,
                end0 = center + androidx.compose.ui.geometry.Offset(600f, 600f),
                start1 = center,
                end1 = center - androidx.compose.ui.geometry.Offset(600f, 600f),
            )
        }
        compose.runOnIdle { assertEquals(4f, scale) }
    }

    @Test
    fun `pinch in below 1x clamps at 1x`() {
        var scale = 1f
        setUpZoom { scale = it }
        // 先双击到 2x，再向内捏合：1x 为下限
        compose.onNodeWithTag("qa-zoomable").performTouchInput { doubleClick(center) }
        compose.onNodeWithTag("qa-zoomable").performTouchInput {
            // 注入点须落在节点内（Robolectric 密度 1.0，300px 宽 → 半宽 150px）
            pinch(
                start0 = center + androidx.compose.ui.geometry.Offset(100f, 0f),
                end0 = center + androidx.compose.ui.geometry.Offset(20f, 0f),
                start1 = center - androidx.compose.ui.geometry.Offset(100f, 0f),
                end1 = center - androidx.compose.ui.geometry.Offset(20f, 0f),
            )
        }
        compose.runOnIdle { assertEquals(1f, scale) }
    }
}
