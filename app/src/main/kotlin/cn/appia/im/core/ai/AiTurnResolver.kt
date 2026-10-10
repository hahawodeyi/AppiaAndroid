package cn.appia.im.core.ai

/** RN parseBotMentions.ts:39 正则（\b 词界：`.botx` 不命中）。 */
private val BOT_MENTION_RE = Regex("@([A-Za-z0-9_.-]+\\.bot)\\b")

/** RN parseBotMentions.ts:63 剔除正则（含其后一个空格）。 */
private val BOT_MENTION_STRIP_RE = Regex("@[A-Za-z0-9_.-]+\\.bot\\b\\s?")

/**
 * RN lib/ai/parseBotMentions.ts extractBotMentionsFromText：从纯文本提取 `@xxx.bot`，
 * 保序去重（发送路径不携带 mentions 列表，触发判定用文本兜底解析）。
 */
fun extractBotMentionsFromText(msg: String): List<String> {
    if (msg.isEmpty()) return emptyList()
    val seen = LinkedHashSet<String>()
    for (m in BOT_MENTION_RE.findAll(msg)) seen.add(m.groupValues[1])
    return seen.toList()
}

/**
 * RN parseBotMentions.ts stripBotMentionsFromText：剔除 `@xxx.bot`（含其后一个空格）再 trim——
 * 发给 bot 的 prompt 不应包含 @bot 自身（坑 7）；仅剔 `.bot` 后缀，不影响 @ 人。
 */
fun stripBotMentionsFromText(msg: String): String =
    if (msg.isEmpty()) "" else BOT_MENTION_STRIP_RE.replace(msg, "").trim()

/** RN aiTriggerWiring.ts BuildTriggerContextInput。 */
data class AiTurnInput(
    val rid: String,
    val fromAgent: Boolean,
    val isStaffService: Boolean,
    /** RN `'urobot' | 'agent' | null`：`agent` = 已转人工，不触发。 */
    val staffAssignType: String? = null,
    val msg: String,
)

/** RN resolveAiTurn 命中返回（aiTriggerWiring.ts:44-50）。 */
data class AiTurn(
    val bots: List<String>,
    val prompt: String,
    val relatedUserMessageId: String,
    val inAgentRoom: Boolean,
)

/**
 * RN hooks/useAiTrigger.ts:57-67 shouldTriggerAi：staffService 房（非人工态、非「转人工」）恒触发；
 * 普通/agent 房需 @bot 且至少一个可解析端点（some 语义）。
 */
fun shouldTriggerAi(
    isStaffService: Boolean,
    staffAssignType: String?,
    msg: String,
    mentions: List<String>,
    agentBotList: List<String>,
): Boolean {
    if (isStaffService) {
        if (staffAssignType == "agent") return false
        if (msg.contains(TRANSFER_TO_AGENT_KEYWORD)) return false
        return true
    }
    if (mentions.isEmpty()) return false
    return mentions.any { resolveBotEndpoints(it, agentBotList) != null }
}

/**
 * RN screens/RoomScreen/aiTriggerWiring.ts resolveAiTurn（+ useAiTrigger getTriggerPlan 合成）：
 * 命中返回 bot 列表 + prompt + 关联用户消息 id；未命中 null。纯函数，设置（agentBotList/siteUrl）
 * 由调用方注入。staffService 无 @：bots 固定 `['staffService.bot']`，prompt 原样传入；
 * @bot 路径：bots = 全部文本 mention，prompt 剔除 @bot 后组装（附件模板归 T7 消息面，
 * RN buildTriggerContext 的 attachments 恒为空数组，照抄）。
 */
fun resolveAiTurn(
    input: AiTurnInput,
    agentBotList: List<String>,
    siteUrl: String,
    userMessageId: String,
): AiTurn? {
    val mentions = extractBotMentionsFromText(input.msg)
    if (!shouldTriggerAi(input.isStaffService, input.staffAssignType, input.msg, mentions, agentBotList)) {
        return null
    }
    val bots = if (input.isStaffService) listOf("staffService.bot") else mentions
    if (bots.isEmpty()) return null
    val msgForPrompt = if (input.isStaffService) input.msg else stripBotMentionsFromText(input.msg)
    return AiTurn(
        bots = bots,
        prompt = extractAIPrompt(msgForPrompt, emptyList(), siteUrl),
        relatedUserMessageId = userMessageId,
        inAgentRoom = input.fromAgent,
    )
}
