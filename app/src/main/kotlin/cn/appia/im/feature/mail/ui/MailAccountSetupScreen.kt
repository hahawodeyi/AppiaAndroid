package cn.appia.im.feature.mail.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.R
import cn.appia.im.core.datastore.AuthSessionStore
import cn.appia.im.core.i18n.t
import cn.appia.im.core.mail.MailApiException
import cn.appia.im.core.mail.MailClient
import cn.appia.im.core.mail.MailClient.Companion.isMailThrottleError
import cn.appia.im.core.mail.MailCredentialKeyCache
import cn.appia.im.core.network.AuthUserEmail
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.api.fetchMemberProfile
import cn.appia.im.core.theme.Colors
import cn.appia.im.feature.mail.bindMailAccount
import cn.appia.im.feature.mail.testMailConnection
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 账户绑定页（RN MailAccountSetupScreen/index.tsx 逐行为移植）：
 * - 邮箱预取 user.emails[0]，缺则 fetchMemberProfile(users.info) 兜底并 mergeUserEmails 回写；
 *   都无 → 手输框 + mail_setup_no_email 提示（RN useMailUserEmail 同链）
 * - 密码 secureText；测试连接（先）+ 绑定（后）双按钮；节流错（坑 5）专用 mail_throttle toast
 * - 绑定成功 → toast mail_bind_success → onBound()（RN navigation.replace('MailChannel')）
 * - 引导图 {server}/mail/guide-step1/2.png，加载失败隐藏（RN onError 同义）
 *
 * 「账户设置」实为绑定页（RN 无解绑/删除/签名/多账户——不建多余 UI）。
 */
@Composable
fun MailAccountSetupScreen(
    sdk: RocketSdk,
    auth: AuthSessionStore,
    client: MailClient,
    keyCache: MailCredentialKeyCache,
    onBound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── 邮箱预取（RN useMailUserEmail：storedEmail → fetchMemberProfile 兜底 → error）──
    var resolvedEmail by remember { mutableStateOf(resolveUserPrimaryEmail(auth)) }
    var emailLoading by remember { mutableStateOf(resolvedEmail.isEmpty()) }
    var emailLoadError by remember { mutableStateOf(false) }
    var manualEmail by remember { mutableStateOf("") }
    val email = manualEmail.trim().ifEmpty { resolvedEmail }

    LaunchedEffect(Unit) {
        if (resolvedEmail.isNotEmpty()) return@LaunchedEffect
        val userId = auth.load()?.user?.id.orEmpty()
        if (userId.isEmpty()) {
            emailLoading = false
            emailLoadError = true
            return@LaunchedEffect
        }
        try {
            val profile = withContext(Dispatchers.IO) { fetchMemberProfile(sdk, userId) }
            val fetched = profile?.emails?.firstOrNull { it.isNotBlank() }
            if (fetched != null) {
                resolvedEmail = fetched
                // RN mergeUserEmails：非空地址列表回写会话（下次免拉）
                auth.mergeUserEmails(
                    userId,
                    profile.emails.filter { it.isNotBlank() }.map { AuthUserEmail(address = it) },
                )
                emailLoadError = false
            } else {
                emailLoadError = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emailLoadError = true
        } finally {
            emailLoading = false
        }
    }

    var password by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var binding by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<TestResult?>(null) }
    // 引导图加载失败逐张隐藏（RN guideImagesVisible {step1, step2}）
    var guide1Visible by remember { mutableStateOf(true) }
    var guide2Visible by remember { mutableStateOf(true) }

    val busy = testing || binding
    // RN canSubmit：client && email && password.trim() && !busy && !emailLoading
    val canSubmit = email.isNotBlank() && password.isNotBlank() && !busy && !emailLoading
    val serverUrl = auth.load()?.serverUrl.orEmpty()
    val guideStep1Url = serverUrl.trimEnd('/').takeIf { it.isNotEmpty() }?.let { "$it/mail/guide-step1.png" }
    val guideStep2Url = serverUrl.trimEnd('/').takeIf { it.isNotEmpty() }?.let { "$it/mail/guide-step2.png" }

    Column(
        modifier
            .fillMaxSize()
            .background(Colors.bgTertiary)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .testTag("mail-setup-screen"),
    ) {
        // hero（RN styles.hero：iconCircle 48dp primaryLight 圆 + 标题 + 副题）
        Column(
            Modifier.fillMaxWidth().padding(top = 20.dp, start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .background(Colors.primaryLight, RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center,
            ) {
                // RN EmailIcon（ic_appia_email 移植）
                Icon(
                    painterResource(R.drawable.ic_appia_email),
                    contentDescription = null,
                    tint = Colors.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(context.t("mail_setup_heading"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Colors.textPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                context.t("mail_setup_subtitle"),
                fontSize = 14.sp,
                color = Colors.textTertiary,
                textAlign = TextAlign.Center,
            )
        }

        // ── 邮箱段（RN formSection email：loading → 只显值 → 手输）──
        FormSection {
            Text(context.t("mail_setup_email_label"), fontSize = 14.sp, color = Colors.textSecondary)
            Spacer(Modifier.height(8.dp))
            when {
                emailLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Colors.primary)
                    Spacer(Modifier.size(8.dp))
                    Text(context.t("mail_setup_email_loading"), fontSize = 12.sp, color = Colors.textTertiary)
                }
                email.isNotBlank() -> Text(email, fontSize = 14.sp, color = Colors.textPrimary, modifier = Modifier.testTag("mail-setup-email"))
                else -> {
                    if (emailLoadError) {
                        Text(
                            context.t("mail_setup_no_email"),
                            fontSize = 12.sp,
                            color = Colors.danger,
                            modifier = Modifier.testTag("mail-setup-email-error").padding(bottom = 8.dp),
                        )
                    }
                    MailInputField(
                        value = manualEmail,
                        onValueChange = { manualEmail = it },
                        placeholder = context.t("mail_setup_email_placeholder"),
                        enabled = !busy,
                        keyboardType = KeyboardType.Email,
                        testTag = "mail-setup-email-input",
                    )
                }
            }
        }

        // ── 密码段 ──
        FormSection {
            Text(context.t("mail_setup_password_label"), fontSize = 14.sp, color = Colors.textSecondary)
            Spacer(Modifier.height(8.dp))
            MailInputField(
                value = password,
                onValueChange = { password = it },
                placeholder = context.t("mail_setup_password_placeholder"),
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                testTag = "mail-setup-password",
            )
        }

        // ── 按钮排（ghost 测试连接 + primary 绑定，各 flex:1）──
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionButton(
                text = context.t("mail_setup_test_connection"),
                loading = testing,
                enabled = canSubmit,
                container = Colors.bgBase,
                content = Colors.textPrimary,
                modifier = Modifier.weight(1f).testTag("mail-setup-test"),
            ) {
                scope.launch {
                    testing = true
                    testResult = null
                    try {
                        val result = withContext(Dispatchers.IO) {
                            testMailConnection(client, keyCache, email, password.trim())
                        }
                        testResult = TestResult(ok = result.success, message = result.message.ifEmpty { context.t("mail_test_success") })
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (isMailThrottleError(e)) {
                            Toast.makeText(context, context.t("mail_throttle"), Toast.LENGTH_SHORT).show()
                            testResult = TestResult(ok = false, message = context.t("mail_throttle"))
                        } else {
                            testResult = TestResult(
                                ok = false,
                                message = (e as? MailApiException)?.message ?: context.t("mail_test_failed"),
                            )
                        }
                    } finally {
                        testing = false
                    }
                }
            }
            ActionButton(
                text = context.t("mail_setup_bind"),
                loading = binding,
                enabled = canSubmit,
                container = Colors.primary,
                content = Colors.textWhite,
                modifier = Modifier.weight(1f).testTag("mail-setup-bind"),
            ) {
                scope.launch {
                    binding = true
                    try {
                        withContext(Dispatchers.IO) { bindMailAccount(client, keyCache, email, password.trim()) }
                        Toast.makeText(context, context.t("mail_bind_success"), Toast.LENGTH_SHORT).show()
                        onBound()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val message = if (isMailThrottleError(e)) context.t("mail_throttle") else context.t("mail_bind_failed")
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    } finally {
                        binding = false
                    }
                }
            }
        }

        // ── 测试结果横幅（成功 #E8FFEA/success、失败 #FFECE8/danger）──
        testResult?.let { result ->
            Text(
                result.message,
                fontSize = 12.sp,
                color = if (result.ok) Colors.success else Colors.danger,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .background(
                        if (result.ok) Color(0xFFE8FFEA) else Color(0xFFFFECE8),
                        RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("mail-setup-test-result"),
            )
        }

        // ── 引导段（标题 + 两步骤文案 + 两图，加载失败隐藏）──
        Column(
            Modifier.fillMaxWidth().padding(top = 16.dp).padding(horizontal = 16.dp)
                .background(Colors.bgTertiary),
        ) {
            HorizontalDivider(color = Colors.border)
            Spacer(Modifier.height(16.dp))
            Text(context.t("mail_setup_guide_title"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Colors.textPrimary)
            Spacer(Modifier.height(8.dp))
            Text(context.t("mail_setup_guide_step1"), fontSize = 12.sp, color = Colors.textTertiary, lineHeight = 20.sp)
            Spacer(Modifier.height(4.dp))
            Text(context.t("mail_setup_guide_step2"), fontSize = 12.sp, color = Colors.textTertiary, lineHeight = 20.sp)
            if (guide1Visible && guideStep1Url != null) {
                Spacer(Modifier.height(8.dp))
                GuideImage(url = guideStep1Url, a11y = context.t("mail_setup_guide_image1_a11y")) { guide1Visible = false }
            }
            if (guide2Visible && guideStep2Url != null) {
                Spacer(Modifier.height(8.dp))
                GuideImage(url = guideStep2Url, a11y = context.t("mail_setup_guide_image2_a11y")) { guide2Visible = false }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** RN TestResult {ok, message}。 */
private data class TestResult(val ok: Boolean, val message: String)

/** RN formSection：bgBase + 上下 hairline。 */
@Composable
private fun FormSection(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(Colors.bgBase)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        HorizontalDivider(color = Colors.borderLight)
        Spacer(Modifier.height(12.dp))
        content()
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = Colors.borderLight)
    }
}

/**
 * RN passwordInput：BasicTextField + 底部 hairline（LoginScreen UnderlineField 同构）。
 * 值非空时 label 行不渲染 placeholder。
 */
@Composable
private fun MailInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    testTag: String? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = visualTransformation,
            textStyle = TextStyle(fontSize = 14.sp, color = Colors.textPrimary),
            cursorBrush = SolidColor(Colors.primary),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(placeholder, fontSize = 14.sp, color = Colors.textDisabled)
                    }
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth().then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        )
        HorizontalDivider(thickness = 1.dp, color = Colors.border)
    }
}

/** RN ghostButton/primaryButton：minHeight 44 圆角 6；disabled 半透明。 */
@Composable
private fun ActionButton(
    text: String,
    loading: Boolean,
    enabled: Boolean,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .heightIn(min = 44.dp)
            .background(container, RoundedCornerShape(6.dp))
            .let { if (enabled) it.clickable(onClick = onClick) else it }
            .let { if (!enabled) it.alpha(0.5f) else it },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = content)
        } else {
            Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = content)
        }
    }
}

/** 引导图（RN guideImage：宽满、高 120、圆角 8、bgSecondary 垫底；onError 隐藏）。 */
@Composable
private fun GuideImage(url: String, a11y: String, onError: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(120.dp).background(Colors.bgSecondary, RoundedCornerShape(8.dp)),
    ) {
        AsyncImage(
            model = url,
            contentDescription = a11y,
            onState = { state -> if (state is AsyncImagePainter.State.Error) onError() },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** RN resolveUserPrimaryEmail：emails[0].address trim；缺 → ""（AA AuthUserEmail 已是对象形态）。 */
private fun resolveUserPrimaryEmail(auth: AuthSessionStore): String =
    auth.load()?.user?.emails?.firstOrNull()?.address?.trim().orEmpty()
