package cn.appia.im.feature.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.theme.LocalAppiaColors

/**
 * RN resolveRoomHeaderTitle（lib/chat/resolveRoomHeaderTitle.ts）：本地 chats 行已同步时用
 * roomTitleFromChat（fname→dname→name→_id），否则回退路由参数（push/deeplink 占位标题）。
 */
internal fun resolveRoomHeaderTitle(routeTitle: String?, chat: ChatEntity?): String {
    val row = chat ?: return routeTitle?.trim().orEmpty()
    return row.fname.trim().ifEmpty {
        row.dname?.trim().orEmpty().ifEmpty { row.name.trim().ifEmpty { row._id } }
    }
}

/** 房间头部（RN StackHeaderBack + 标题）：返回键 + 居中标题（可点 → 房间信息页，RN openRoomInfo）
 *  + 右侧动作槽（RN navigation.setOptions headerRight——T5 成员页 remove 模式移除钮首用）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onTitleClick: (() -> Unit)? = null,
    headerAction: (@Composable () -> Unit)? = null,
) {
    val colors = LocalAppiaColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    title,
                    color = colors.headerTitleColor,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .let { m -> if (onTitleClick != null) m.clickable(onClick = onTitleClick) else m }
                        .testTag("qa-room-header-title"),
                )
            },
            navigationIcon = {
                // 无 icons 依赖（material3 未带 icons-core 扩展）：文本箭头足够，语义由 testTag/描述承担
                Text(
                    "←",
                    color = colors.headerTintColor,
                    fontSize = 22.sp,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .size(40.dp)
                        .wrapContentSize(Alignment.Center)
                        .clickable(onClick = onBack)
                        .testTag("qa-room-header-back"),
                )
            },
            actions = {
                headerAction?.invoke()
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = colors.headerBackground,
            ),
        )
    }
}
