package cn.appia.im.core.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * RN lib/ai/extractAIPrompt.ts + aiPrompts.ts 模板对照（M7-T6）：
 * 文本 + 图片 `\n\n请分析图片内容：<image-url>{url}</image-url>` +
 * 文件 `\n\n附件信息：{siteUrl}/file-proxy/{url}`，image_url 优先。
 */
class AiPromptBuilderTest {

    @Test
    fun `text only returns msg as-is`() {
        assertEquals("你好", extractAIPrompt("你好", emptyList(), "https://a.cn"))
    }

    @Test
    fun `image attachment appends image-url template`() {
        val prompt = extractAIPrompt("看图", listOf(AiPromptAttachment(imageUrl = "/file/upload/i.png")), "")
        assertEquals("看图\n\n请分析图片内容：<image-url>/file/upload/i.png</image-url>", prompt)
    }

    @Test
    fun `file attachment appends file-proxy template with siteUrl`() {
        val prompt = extractAIPrompt("见附件", listOf(AiPromptAttachment(url = "/d/file.pdf")), "https://a.cn")
        assertEquals("见附件\n\n附件信息：https://a.cn/file-proxy//d/file.pdf", prompt)
    }

    @Test
    fun `image_url takes precedence when both present`() {
        val att = AiPromptAttachment(imageUrl = "/i.png", url = "/f.pdf")
        assertEquals("x\n\n请分析图片内容：<image-url>/i.png</image-url>", extractAIPrompt("x", listOf(att), "https://a.cn"))
    }

    @Test
    fun `multiple attachments append in order`() {
        val prompt = extractAIPrompt(
            "m",
            listOf(
                AiPromptAttachment(url = "/a.pdf"),
                AiPromptAttachment(imageUrl = "/b.png"),
                AiPromptAttachment(url = "/c.txt"),
            ),
            "https://s.cn",
        )
        assertEquals(
            "m" +
                "\n\n附件信息：https://s.cn/file-proxy//a.pdf" +
                "\n\n请分析图片内容：<image-url>/b.png</image-url>" +
                "\n\n附件信息：https://s.cn/file-proxy//c.txt",
            prompt,
        )
    }

    @Test
    fun `attachment with neither url is skipped`() {
        assertEquals("m", extractAIPrompt("m", listOf(AiPromptAttachment()), "https://a.cn"))
    }

    @Test
    fun `empty msg yields template-only prompt`() {
        assertEquals("\n\n请分析图片内容：<image-url>u</image-url>", extractAIPrompt("", listOf(AiPromptAttachment(imageUrl = "u")), ""))
    }

    @Test
    fun `transfer keyword constant matches RN`() {
        assertEquals("转人工", TRANSFER_TO_AGENT_KEYWORD)
    }
}
