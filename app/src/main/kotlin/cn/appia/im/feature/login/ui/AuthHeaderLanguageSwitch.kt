package cn.appia.im.feature.login.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.datastore.KvStore
import cn.appia.im.core.i18n.LocaleController
import cn.appia.im.core.i18n.resolveActiveLanguage
import cn.appia.im.core.i18n.t

/**
 * AuthHeaderLanguageSwitch 等价（RN screens/auth/AuthHeaderLanguageSwitch.tsx）：
 * 登录头两段下拉（English/中文），立即生效并持久化。
 * RN 下拉显示 resolveActiveLanguage(preference)（无 system 段——登录头只有两值），
 * 选中写 useI18nStore.setLanguage；Android 同语义直写 LocaleController。
 */
@Composable
fun AuthHeaderLanguageSwitch(kv: KvStore, systemLanguageTag: String) {
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val active = resolveActiveLanguage(
        LocaleController.load(kv).takeIf { it != "system" },
        systemLanguageTag,
    )
    val currentLabel = if (active == "zh") {
        context.t("auth_lang_option_zh")
    } else {
        context.t("auth_lang_option_en")
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Box {
            Row(
                Modifier
                    .background(Color(0x33FFFFFF), RoundedCornerShape(6.dp))
                    .clickable { menuOpen = true }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .testTag("auth-header-language-dropdown"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("$currentLabel ▾", color = Color.White, fontSize = 13.sp)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(context.t("auth_lang_option_en")) },
                    onClick = {
                        menuOpen = false
                        LocaleController.apply(kv, "en")
                    },
                )
                DropdownMenuItem(
                    text = { Text(context.t("auth_lang_option_zh")) },
                    onClick = {
                        menuOpen = false
                        LocaleController.apply(kv, "zh")
                    },
                )
            }
        }
    }
}
