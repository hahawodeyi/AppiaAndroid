package cn.appia.im.feature.agents.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.text.BasicTextField
import cn.appia.im.R
import cn.appia.im.core.i18n.t
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.api.ClawAgentPayload
import cn.appia.im.core.network.api.ClawAgentsApi
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.feature.agents.ClawAgentQuickAddError
import cn.appia.im.feature.agents.ClawAgentQuickAddResult
import cn.appia.im.feature.agents.isDuplicateClawAgentIdError
import cn.appia.im.feature.agents.parseClawAgentQuickAddInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Agent 创建/编辑页（M7-T8 / RN AgentEditorScreen/index.tsx）：
 * - create：快捷添加（Base64/JSON 凭证粘贴解析 → 三字段回填，credentialReady 门控保存）+
 *   名称；成功回投 createdUsername（选人器回插选中）。
 * - edit：名称/Agent ID/服务 URL/API Key（留空沿用旧密钥）；initialId = 目录行 id。
 * - 重复 id：服务端错误文案 isDuplicateClawAgentIdError → agents_duplicateagentid 友好弹窗。
 */
@Composable
fun AgentEditorScreen(
    mode: String, // 'create' | 'edit'
    sdk: RocketSdk,
    onBack: () -> Unit,
    onSaved: (createdUsername: String?) -> Unit,
    initialName: String = "",
    initialAgentId: String = "",
    initialServiceUrl: String = "",
    initialId: String = "",
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isCreate = mode != "edit"

    var name by rememberSaveable { mutableStateOf(initialName) }
    var agentId by rememberSaveable { mutableStateOf(initialAgentId) }
    var url by rememberSaveable { mutableStateOf(initialServiceUrl) }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var quickAddRaw by rememberSaveable { mutableStateOf("") }
    var credentialReady by rememberSaveable { mutableStateOf(false) }
    var quickError by rememberSaveable { mutableStateOf<String?>(null) }
    var formError by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var guidePreviewOpen by remember { mutableStateOf(false) }
    var alert by remember { mutableStateOf<Pair<String, String>?>(null) }

    val canSave = !saving && name.trim().isNotEmpty() &&
        (
            if (isCreate) {
                credentialReady && agentId.trim().isNotEmpty() && url.trim().isNotEmpty() && apiKey.trim().isNotEmpty()
            } else {
                agentId.trim().isNotEmpty() && url.trim().isNotEmpty()
            }
            )

    fun applyQuickAdd() {
        // RN decodeBase64 = Buffer(quick-base64)；AA 用 android.util.Base64 同语义
        val decode: (String) -> String = { raw ->
            String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT), Charsets.UTF_8)
        }
        when (val r = parseClawAgentQuickAddInput(quickAddRaw, decode)) {
            is ClawAgentQuickAddResult.Failed -> {
                credentialReady = false
                quickError = context.t(
                    when (r.error) {
                        ClawAgentQuickAddError.MISSING_FIELDS -> "agents_quickMissing"
                        ClawAgentQuickAddError.EMPTY -> "agents_required"
                        ClawAgentQuickAddError.INVALID_JSON -> "agents_quickInvalid"
                    },
                )
            }
            is ClawAgentQuickAddResult.Ok -> {
                agentId = r.agentId
                url = r.url
                apiKey = r.apiKey
                if (name.trim().isEmpty()) name = r.agentId
                quickAddRaw = ""
                credentialReady = true
                quickError = null
            }
        }
    }

    fun resetQuickAdd() {
        quickAddRaw = ""
        agentId = ""
        url = ""
        apiKey = ""
        name = ""
        credentialReady = false
        quickError = null
    }

    fun save() {
        if (!canSave) {
            formError = context.t("agents_required")
            return
        }
        saving = true
        formError = null
        scope.launch {
            try {
                val payload = ClawAgentPayload(
                    name = name.trim(),
                    agentId = agentId.trim(),
                    url = url.trim(),
                    apiKey = apiKey.trim(),
                )
                val createdUsername = if (isCreate) {
                    ClawAgentsApi.createClawAgent(sdk, payload)
                } else {
                    ClawAgentsApi.updateClawAgent(sdk, initialId, payload)
                    null
                }
                android.widget.Toast.makeText(
                    context,
                    context.t(if (isCreate) "agents_created" else "agents_updated"),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                onSaved(createdUsername)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message.orEmpty()
                val detail = if (isDuplicateClawAgentIdError(message)) {
                    context.t("agents_duplicateagentid").replace("{{id}}", agentId.trim())
                } else {
                    message.ifEmpty { context.t("agents_savefailedtitle") }
                }
                alert = context.t("agents_savefailedtitle") to detail
            } finally {
                saving = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 导航条（RN navbar：返回 / 标题 / 保存）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹",
                color = colors.headerTintColor,
                fontSize = 26.sp,
                modifier = Modifier
                    .width(40.dp)
                    .clickable(enabled = !saving, onClick = onBack)
                    .testTag("agent-editor-back"),
            )
            Text(
                context.t(if (isCreate) "agents_navTitleCreate" else "agents_navTitleEdit"),
                Modifier.weight(1f),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                context.t("agents_save"),
                color = if (canSave) colors.tintColor else colors.auxiliaryText,
                fontSize = 15.sp,
                modifier = Modifier
                    .width(56.dp)
                    .clickable(enabled = canSave, onClick = ::save)
                    .testTag("agent-editor-save"),
            )
        }
        HorizontalDivider(color = colors.separatorColor)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag("agent-editor-scroll"),
        ) {
            if (isCreate) {
                Text(context.t("agents_quickLabel"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(context.t("agents_quickGuide"), color = colors.auxiliaryText, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Image(
                    painter = painterResource(R.drawable.claw_agent_credential_guide),
                    contentDescription = context.t("agents_guideA11y"),
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { guidePreviewOpen = true }
                        .testTag("agent-editor-guide"),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        context.t("agents_quickPlaceholder"),
                        Modifier.weight(1f),
                        color = colors.auxiliaryText,
                        fontSize = 13.sp,
                    )
                    Text(
                        context.t("agents_quickApply"),
                        color = colors.tintColor,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clickable(enabled = !saving, onClick = ::applyQuickAdd)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("agent-editor-quick-apply"),
                    )
                }
                EditorField(
                    value = quickAddRaw,
                    onValueChange = { quickAddRaw = it },
                    placeholder = context.t("agents_quickPlaceholder"),
                    enabled = !saving,
                    minLines = 3,
                    testTag = "agent-editor-quick-input",
                )
                quickError?.let {
                    Text(
                        it,
                        color = Color(0xFFF53F3F),
                        fontSize = 13.sp,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .testTag("agent-editor-quick-error"),
                    )
                }
                if (isCreate && credentialReady) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            context.t("agents_quickReady"),
                            Modifier.weight(1f),
                            color = colors.auxiliaryText,
                            fontSize = 13.sp,
                        )
                        Text(
                            context.t("agents_quickReparse"),
                            color = colors.tintColor,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .clickable(enabled = !saving, onClick = ::resetQuickAdd)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("agent-editor-quick-reparse"),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            Text(context.t("agents_nameLabel"), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            EditorField(
                value = name,
                onValueChange = { name = it },
                placeholder = context.t("agents_namePlaceholder"),
                enabled = !saving,
                testTag = "agent-editor-name",
            )
            if (!isCreate) {
                FieldLabel(context.t("agents_agentIdLabel"))
                EditorField(
                    value = agentId,
                    onValueChange = { agentId = it },
                    placeholder = context.t("agents_agentIdPlaceholder"),
                    enabled = !saving,
                    testTag = "agent-editor-agent-id",
                )
                FieldLabel(context.t("agents_urlLabel"))
                EditorField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = context.t("agents_urlPlaceholder"),
                    enabled = !saving,
                    testTag = "agent-editor-url",
                )
                FieldLabel(context.t("agents_apiKeyLabel"))
                EditorField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    placeholder = context.t(
                        if (isCreate) "agents_apiKeyPlaceholder" else "agents_apiKeyEditPlaceholder",
                    ),
                    enabled = !saving,
                    testTag = "agent-editor-api-key",
                )
            }
            formError?.let {
                Text(
                    it,
                    color = Color(0xFFF53F3F),
                    fontSize = 13.sp,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .testTag("agent-editor-form-error"),
                )
            }
        }
    }

    if (guidePreviewOpen) {
        Dialog(onDismissRequest = { guidePreviewOpen = false }) {
            Column(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.85f))
                    .padding(12.dp)
                    .testTag("agent-editor-guide-preview"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.claw_agent_credential_guide),
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    context.t("agents_cancel"),
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clickable { guidePreviewOpen = false }
                        .padding(8.dp),
                )
            }
        }
    }

    alert?.let { (title, message) ->
        AlertDialog(
            onDismissRequest = { alert = null },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { alert = null }) { Text(context.t("agents_cancel")) }
            },
            modifier = Modifier.testTag("agent-editor-save-error"),
        )
    }
}

/** RN styles.field label（小节字段标题）。 */
@Composable
private fun FieldLabel(text: String) {
    Spacer(Modifier.height(12.dp))
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(4.dp))
}

/** 素输入框（RN AppText.TextInput 无边框形态 + 占位；背景随主题 messageboxBackground）。 */
@Composable
private fun EditorField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    testTag: String,
    minLines: Int = 1,
) {
    val colors = LocalAppiaColors.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        minLines = minLines,
        textStyle = androidx.compose.ui.text.TextStyle(
            color = colors.bodyText,
            fontSize = 15.sp,
        ),
        decorationBox = { inner ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.messageboxBackground)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                if (value.isEmpty()) {
                    Text(placeholder, color = colors.auxiliaryText, fontSize = 15.sp)
                }
                inner()
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
    )
}
