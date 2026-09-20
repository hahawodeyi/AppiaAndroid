package cn.appia.im.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.i18n.t
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.feature.chat.ALL_MEMBER
import cn.appia.im.feature.chat.MentionCandidate
import cn.appia.im.feature.chat.buildMentionCandidates

/**
 * @提及选人页（RN screens/MentionSuggestion 同构）：
 * - 搜索框（初值 = mention-trigger 的 query）+ name/username includes 过滤（buildMentionCandidates）；
 * - 非 agent 房：`ALL_MEMBER` 写死首项 + 多选切换（底栏计数 + Done）；
 * - agent 房：候选 = `Agent_Bot_List`×`Appia_Claw_Agent_Visibility` 门控的机器人，无 ALL 行、无多选；
 * - 单选即选即退，多选 Done 后携全组退出；候选加载经 [loadCandidates] 注入（装配处接
 *   `GET appia/room/members/v2` / settings 门控，门控纯函数在 MentionSource.kt 单测覆盖）；
 * - 选中结果经 [onSelected] 上抛（装配处 = previousBackStackEntry.savedStateHandle，
 *   Navigation Compose 跨屏结果惯例——共享 Flow 在选人页打开期间无订阅者会丢事件，不采）。
 */
@Composable
fun MentionSuggestionScreen(
    initialQuery: String,
    isAgentRoom: Boolean,
    loadCandidates: suspend (isAgentRoom: Boolean) -> List<MentionCandidate>,
    onSelected: (List<MentionCandidate>) -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    var query by remember { mutableStateOf(initialQuery) }
    var allMembers by remember { mutableStateOf<List<MentionCandidate>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var multiSelect by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }

    LaunchedEffect(isAgentRoom) {
        allMembers = runCatching { loadCandidates(isAgentRoom) }.getOrDefault(emptyList())
        loading = false
    }

    // 候选过滤走 buildMentionCandidates（评审 Important-5：与单测钉的同一实现，不再屏内重写）
    val filtered = remember(allMembers, query) { buildMentionCandidates(allMembers, query, includeAllMember = false) }
    val display = if (isAgentRoom) filtered else listOf(ALL_MEMBER) + filtered

    Column(Modifier.fillMaxSize().background(colors.backgroundColor)) {
        RoomHeader(title = context.t("mentionsuggestion_title"), onBack = onBack)

        TextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .testTag("qa-mention-search"),
            placeholder = {
                Text(context.t(if (isAgentRoom) "mentionsuggestion_botplaceholder" else "mentionsuggestion_title"))
            },
            singleLine = true,
        )

        if (!isAgentRoom) {
            Text(
                if (multiSelect) context.t("mentionsuggestion_cancelmultiselect") else context.t("mentionsuggestion_multiselect"),
                color = colors.tintColor,
                fontSize = 14.sp,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable {
                        multiSelect = !multiSelect
                        selectedIds = emptySet()
                    }
                    .testTag("qa-mention-multiselect-toggle"),
            )
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.testTag("qa-mention-loading"))
            }
            display.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (isAgentRoom) context.t("mentionsuggestion_nobots") else context.t("mentionsuggestion_title"),
                    color = colors.auxiliaryText,
                    fontSize = 14.sp,
                    modifier = Modifier.testTag("qa-mention-empty"),
                )
            }
            else -> LazyColumn(Modifier.fillMaxSize().testTag("qa-mention-list")) {
                items(display, key = { it.id }) { member ->
                    val isAllRow = member === ALL_MEMBER
                    val checked = member.id in selectedIds
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isAllRow) {
                                    // ALL 行恒单选即退（RN renderHeader onPress）
                                    emitSelectionAndBack(listOf(member), onSelected, onBack)
                                } else if (multiSelect) {
                                    selectedIds = if (checked) selectedIds - member.id else selectedIds + member.id
                                } else {
                                    emitSelectionAndBack(listOf(member), onSelected, onBack)
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("qa-mention-row-${member.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(colors.chatComponentBackground),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (isAllRow) "@" else member.displayName.take(1).uppercase(),
                                color = colors.primary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            member.displayName,
                            color = colors.bodyText,
                            fontSize = 15.sp,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        if (multiSelect && !isAllRow) {
                            Checkbox(checked = checked, onCheckedChange = null)
                        }
                    }
                }
            }
        }

        if (multiSelect) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.messageboxBackground)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    context.t("mentionsuggestion_selectedcount").replace("{{count}}", selectedIds.size.toString()),
                    color = colors.auxiliaryText,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                val doneEnabled = selectedIds.isNotEmpty()
                Text(
                    context.t("mentionsuggestion_done"),
                    color = if (doneEnabled) colors.tintColor else colors.auxiliaryText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable(enabled = doneEnabled) {
                            val picked = display.filter { it.id in selectedIds && it !== ALL_MEMBER }
                            if (picked.isNotEmpty()) emitSelectionAndBack(picked, onSelected, onBack)
                        }
                        .testTag("qa-mention-done"),
                )
            }
        }
    }
}

/** 选中即退（RN emitAndGoBack）：上抛结果后 popBack。 */
private fun emitSelectionAndBack(members: List<MentionCandidate>, onSelected: (List<MentionCandidate>) -> Unit, onBack: () -> Unit) {
    onSelected(members)
    onBack()
}
