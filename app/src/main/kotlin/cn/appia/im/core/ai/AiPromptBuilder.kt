package cn.appia.im.core.ai

/**
 * 员工服务「转人工」触发关键词（RN lib/ai/aiPrompts.ts；功能性指令，非用户可见 UI 文案）。
 */
const val TRANSFER_TO_AGENT_KEYWORD = "转人工"

/** RN aiPrompts.ts IMAGE_PROMPT_TEMPLATE（发给 AI 的功能性数据，保留中文）。 */
fun imagePromptTemplate(url: String): String = "\n\n请分析图片内容：<image-url>$url</image-url>"

/** RN aiPrompts.ts FILE_PROMPT_TEMPLATE（siteUrl 取 `Site_Url` 设置）。 */
fun filePromptTemplate(siteUrl: String, url: String): String = "\n\n附件信息：$siteUrl/file-proxy/$url"

/** 附件子集（RN extractAIPrompt 的 Att：image_url 优先于 url）。 */
data class AiPromptAttachment(val imageUrl: String? = null, val url: String? = null)

/**
 * RN lib/ai/extractAIPrompt.ts：文本 + 逐附件追加模板——有 image_url 走图片模板，
 * 否则有 url 走 file-proxy 模板；附件为空即原文。
 */
fun extractAIPrompt(msg: String, attachments: List<AiPromptAttachment>, siteUrl: String): String {
    var prompt = msg
    for (att in attachments) {
        val imageUrl = att.imageUrl
        if (!imageUrl.isNullOrEmpty()) {
            prompt += imagePromptTemplate(imageUrl)
            continue
        }
        val url = att.url
        if (!url.isNullOrEmpty()) prompt += filePromptTemplate(siteUrl, url)
    }
    return prompt
}
