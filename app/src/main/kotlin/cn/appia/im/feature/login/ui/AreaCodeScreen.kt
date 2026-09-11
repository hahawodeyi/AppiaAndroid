package cn.appia.im.feature.login.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cn.appia.im.core.i18n.t
import cn.appia.im.feature.login.AuthApi
import cn.appia.im.feature.login.LoginAreaCodeOption

private const val TAG_LOADING = "area_code_loading"

/**
 * 区号选择页（对照 RN AreaCodeScreen/index.tsx）。
 * 进入即拉取列表（加载态），回落 +86 由 AuthApi.loginGetAreaCodes 兜底；
 * 点选经 onSelect 回调（RN onSelect + goBack 的 goBack 由导航接线方处理）。
 * `fetch` 参数化：UI 测试注入 fake 不触网。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AreaCodeScreen(
    server: String,
    onSelect: (LoginAreaCodeOption) -> Unit,
    onBack: () -> Unit = {},
    fetch: suspend (String, String, String) -> List<LoginAreaCodeOption> = AuthApi::loginGetAreaCodes,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0].toLanguageTag()
    // null = 加载中；RN:31 先渲染 +86 回落行、:35-37 有数据再替换（回落统一收敛在 AuthApi）
    var rows by remember { mutableStateOf<List<LoginAreaCodeOption>?>(null) }

    LaunchedEffect(server, locale) {
        rows = fetch(server, locale, context.t("login_area_china"))
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(context.t("login_select_country_title"), maxLines = 1) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("←") }
                },
            )
        },
    ) { padding ->
        val current = rows
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.testTag(TAG_LOADING))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(current, key = { "${it.areaCode}.${it.code ?: "default"}" }) { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(item) } // RN:44-47 onPick → onSelect + goBack
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            item.label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f).padding(end = 12.dp),
                        )
                        Text(item.areaCode, style = MaterialTheme.typography.bodyLarge)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
