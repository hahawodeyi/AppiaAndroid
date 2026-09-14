package cn.appia.im.feature.org.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.datastore.LoginSwitchCandidate
import cn.appia.im.core.i18n.t
import cn.appia.im.core.network.ServerUrl
import coil3.compose.AsyncImage

/**
 * 我的企业/主体切换弹层（RN MineMenuScreen:172-205 的公司纵向列表最小版，T11 完整接线）：
 * 候选列表 + 当前主体标记（selected 行勾选且点击无效，RN :86-88 next === cur 直接 return）+
 * 点击回调 onSwitch(candidate)（T11 里包 OrgSwitchCoordinator.switchTo + 失败告警）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrgSwitchSheet(
    candidates: List<LoginSwitchCandidate>,
    currentServerUrl: String,
    onSwitch: (LoginSwitchCandidate) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
        modifier = modifier,
    ) {
        Text(
            LocalContext.current.t("drawer_myenterprise"),
            Modifier.padding(horizontal = 16.dp),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn {
            items(candidates, key = { it.appiaUrl }) { company ->
                val selected =
                    ServerUrl.normalizeServer(company.appiaUrl) == ServerUrl.normalizeServer(currentServerUrl)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !selected) { onSwitch(company) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = company.companyLogo,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    // RN :195 按 locale 取 companyNameCn/companyName；M1 无 locale 开关，中文名优先
                    Text(
                        company.companyNameCn.ifEmpty { company.companyName },
                        Modifier.weight(1f),
                        fontSize = 15.sp,
                        maxLines = 1,
                    )
                    if (selected) {
                        Checkbox(checked = true, onCheckedChange = null)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
