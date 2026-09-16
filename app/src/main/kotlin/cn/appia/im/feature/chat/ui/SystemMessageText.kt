package cn.appia.im.feature.chat.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.i18n.t
import cn.appia.im.core.messaging.SystemMessageTexts

/** RN SystemMessage/index.tsx styles.text：12sp #999 居中（不走主题色板，RN 硬编码）。 */
private val SystemGray = Color(0xFF999999)

/** Context 版 t：资源查 key（小写）+ `{{x}}` 占位替换（RN i18next 语义）。 */
fun systemMessageT(context: android.content.Context): (String, Map<String, String>) -> String =
    { key, params ->
        var s = context.t(key)
        for ((k, v) in params) s = s.replace("{{$k}}", v)
        s
    }

/**
 * 系统消息行（RN SystemMessage：居中 12sp 灰字；文本来自 SystemMessageTexts 逐条映射）。
 * announcement 三型（room_*_announcement）**不走本组件**——isSystemMessageRow 排除后与 RN 同走普通行。
 */
@Composable
fun SystemMessageText(message: MessageEntity, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Text(
        text = SystemMessageTexts.infoText(message, systemMessageT(context)),
        color = SystemGray,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("qa-system-message"),
    )
}
