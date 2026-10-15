package cn.appia.im.feature.voice.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.lib.getIncomingCallRecordId
import cn.appia.im.core.voice.lib.resolveIncomingCallerLabel
import kotlinx.coroutines.flow.StateFlow

/** RN features/voice/assets 三件套的 AA 落位（Task 11 裁定：png → drawable，wav → assets）。 */
internal object VoiceAssets {
    /** call_bg.png → drawable（全屏背景，确认页 + overlay 共用）。const 会在增量编译下产生
     *  内联/ getter 调用不匹配（NoSuchMethodError）——普通 val 保持 getter 调用稳定。 */
    val CALL_BG: Int = cn.appia.im.R.drawable.voice_call_bg

    /** call_end.png → drawable（挂断大钮）。 */
    val CALL_END: Int = cn.appia.im.R.drawable.voice_call_end
}

/** 全屏黑底容器（RN root backgroundColor #000000 等价）。 */
@Composable
internal fun BoxFill(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) { content() }
}

/** call_bg 全屏背景（RN ImageBackground resizeMode cover）。 */
@Composable
internal fun VoiceCallBackground() {
    Image(
        painter = painterResource(VoiceAssets.CALL_BG),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * StateFlow 直读（Host/子组件统一入口；collectAsState 标准用法收口一处）。
 */
@Composable
internal fun <T> StateFlow<T>.collectAsStateCompat() = collectAsState()

/**
 * RN useStableIncomingCallerLabel（:1-27）Composable 形态：同 recordId 下缓存 label，
 * callMsg 变更时用缓存作 previous 再解析，避免 sync 抖动在 name/username 间闪烁。
 * remember 单参存 Pair(recordId, label)——RN useRef({recordId, label}) 等价。
 */
@Composable
internal fun rememberStableIncomingCallerLabel(callMsg: VoiceCallMsg?): String {
    val recordId = getIncomingCallRecordId(callMsg)
    val cache = remember { arrayOf("", "") } // [0]=recordId, [1]=label
    return remember(callMsg, recordId) {
        if (cache[0] != recordId) {
            cache[0] = recordId
            cache[1] = resolveIncomingCallerLabel(callMsg, null)
        } else {
            cache[1] = resolveIncomingCallerLabel(callMsg, cache[1])
        }
        cache[1]
    }
}
