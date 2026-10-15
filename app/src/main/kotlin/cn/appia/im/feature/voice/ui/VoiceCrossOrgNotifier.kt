package cn.appia.im.feature.voice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.voice.VoicePhase
import cn.appia.im.core.voice.lib.getVoiceOrgLogoUrl
import cn.appia.im.core.voice.lib.getVoiceOrgShortName
import cn.appia.im.domain.voice.VoiceSessionController
import cn.appia.im.domain.voice.VoiceSessionDeps
import cn.appia.im.domain.voice.VoiceSessionContext
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 跨组织来电发现（M10-T12 / RN VoiceCrossOrgNotifier/index.tsx 189 行移植；progress.md
 * 归属裁定：App 级装配组件 + 轮询域逻辑）。
 *
 * 轮询（RN :46-77）：idle 相位 + sessionContext 就绪 → oncallGetCurrentRecordV2 →
 * 外来 org（org ≠ ctx.org 且 status=calling 且非本人发起）→ 呈现横幅 + notifyHost2
 * fire-and-forget。触发点：挂载即一次 + AppState active（AA：MainActivity.onResume
 * 直接调用 poll）。
 *
 * 动作（RN :90-135）：ignore = oncallReject（org = record.org）+ 收横幅；
 * switch = switchToVoiceOrg（deps 缝 → OrgSwitchCoordinator），失败 toast
 * voice_crossorg_switchUnavailable（RN no_candidate/switch_failed 细分收窄为 Boolean，
 * deps KDoc 裁定）。
 *
 * ponytail: RN 的 useEffect 依赖 phase 变化重跑轮询——AA 只保留挂载 + 前台两触发
 * （phase 回 idle 的下一次前台/重挂载自然补拉；DB 同步与推送链是主被叫同步主路径，
 * 本轮询只兜「外来 org 记录」这一推送/DB 均不可见的场景，触发密度足够）。
 */
class VoiceCrossOrgNotifier(
    private val controller: VoiceSessionController,
    private val deps: VoiceSessionDeps,
    private val scope: CoroutineScope,
    /** 轮询缝（测试注入记录器；生产 = OncallApi.getCurrentRecordV2）。 */
    private val fetchCurrentRecords: suspend (ctx: VoiceSessionContext, username: String) -> List<CrossOrgRecord> = { _, _ -> emptyList() },
    /** notifyHost2 缝（生产 = OncallApi.notifyHost2；fire-and-forget，失败吞）。 */
    private val notifyHost: suspend (ctx: VoiceSessionContext, recordOrg: String, recordId: String, username: String) -> Unit = { _, _, _, _ -> },
) {

    /** RN :24-28 CrossOrgRecord。 */
    data class CrossOrgRecord(
        val org: String,
        val recordId: String,
        val status: String? = null,
        val initiatorUsername: String? = null,
        val initiatorName: String? = null,
    )

    /** 横幅数据（org + 展示名；orgName null = 不渲染，RN getVoiceOrgShortName null 同义）。 */
    data class BannerState(val record: CrossOrgRecord, val initiatorName: String, val orgName: String)

    /** 横幅态（poll 写入；ignore/switch 收敛 null）。UI collect 渲染。 */
    private val _banner = kotlinx.coroutines.flow.MutableStateFlow<BannerState?>(null)
    val banner: kotlinx.coroutines.flow.StateFlow<BannerState?> = _banner

    /** RN :46-77 pollCrossOrg（挂载/前台双触发——MainActivity onResume 调用）。 */
    fun poll() {
        if (controller.store.state.value.phase != VoicePhase.Idle) return
        val ctx = deps.sessionContext() ?: return
        scope.launch {
            val rows = runCatching { fetchCurrentRecords(ctx, ctx.username) }.getOrDefault(emptyList())
            val localOrg = ctx.org.lowercase()
            val foreign = rows.firstOrNull {
                it.org.isNotEmpty() && it.org.lowercase() != localOrg &&
                    it.status == "calling" && it.initiatorUsername != ctx.username
            }
            if (foreign == null || foreign.recordId.isEmpty()) {
                _banner.value = null
                return@launch
            }
            _banner.value = BannerState(foreign, foreign.initiatorName.orEmpty(), foreign.org)
            scope.launch { runCatching { notifyHost(ctx, foreign.org, foreign.recordId, ctx.username) } }
        }
    }

    /** RN :90-100 onReject：oncallReject(record.org) + 收横幅（失败吞）。 */
    fun ignore(record: CrossOrgRecord) {
        val ctx = deps.sessionContext() ?: run { _banner.value = null; return }
        scope.launch {
            runCatching { deps.reject(ctx.oncallContext(), record.recordId, ctx.username, record.org) }
            _banner.value = null
        }
    }

    /** 登出收尾（VoiceRoomHandle.shutdown；L-4）：清横幅防跨账号残留。 */
    fun dismissBanner() {
        _banner.value = null
    }

    /** RN :102-124 onSwitchOrg：switchToVoiceOrg → 失败 toast（i18n key 回调承载）。 */
    fun switchOrg(record: CrossOrgRecord, onError: (String) -> Unit) {
        scope.launch {
            _banner.value = null
            val ok = runCatching { deps.switchToVoiceOrg(record.org) }.getOrDefault(false)
            if (!ok) onError("voice_crossorg_switchunavailable")
        }
    }
}

/**
 * 横幅宿主（RN render 半部 :137-189）：VoiceCallHost 同层挂载。前台触发点 =
 * MainActivity.onResume 直接调用 notifier.poll（宿主不重复轮询）。
 */
@Composable
fun VoiceCrossOrgNotifierBanner(
    notifier: VoiceCrossOrgNotifier?,
    t: (String) -> String,
) {
    if (notifier == null) return
    val state by notifier.banner.collectAsStateCompat()
    var errorKey by remember { mutableStateOf<String?>(null) }

    // M-4（终审）：switch 失败 → AlertDialog（RN showVoiceError 同构；旧 toast 一帧即逝
    // 且与横幅兄弟节点叠放，两缺陷一并消除）
    errorKey?.let { key ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { errorKey = null },
            title = { Text(t(key)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { errorKey = null }) {
                    Text(t("voice_ok"))
                }
            },
        )
    }

    val s = state ?: return
    val record = s.record
    val orgName = getVoiceOrgShortName(record.org, t)
    if (orgName == null) return // RN :144-146 getVoiceOrgShortName null → 不渲染
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Row(
            Modifier
                .fillMaxWidth(0.98f)
                    .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF000000))
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("qa-voice-cross-org-notifier"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    s.initiatorName,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = getVoiceOrgLogoUrl(record.org),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        t("voice_crossorg_from").replace("{{org}}", orgName),
                        color = Color(0xFF9A9A9A),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            Text(
                t("voice_crossorg_ignore"),
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF3A3A3A))
                    .clickable { notifier.ignore(record) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("qa-voice-cross-org-ignore"),
            )
            Text(
                t("voice_crossorg_switch").replace("{{org}}", orgName),
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF1B5BFF))
                    .clickable {
                        notifier.switchOrg(record) { errorKey = it }
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("qa-voice-cross-org-switch"),
            )
        }
    }
}
