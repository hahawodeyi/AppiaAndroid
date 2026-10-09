package cn.appia.im.core.push

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PushPayloadParser 全兜底层测试（坑 5：四层兜底逐层移植 RN extractPushEjsonPayload.ts:104-167）。
 * 覆盖：raw.ejson → extra/extras 双重转义 → data 变体 → extra 整袋即业务体 → 顶层 rid+type →
 * 裸 rid 兜底 'c' → roomId+msgType=oncall 最小体，以及合并/标题/映射/退化形态。
 */
class PushPayloadParserTest {

    private fun rawOf(json: String) = Json.parseToJsonElement(json).jsonObject

    /** 将业务体 JSON 字符串挂到 raw 的指定 key 下（等价 `{"<key>":"<body>"}`，程序化转义防手写错）。 */
    private fun rawStr(key: String, body: String) = buildJsonObject {
        put(key, JsonPrimitive(body))
    }

    /** 构造 canonical Android 点击形态 raw：{title, summary, extra:"{\"ejson\":\"...\"}"}（双重转义，程序化构造）。 */
    private fun canonicalRaw(
        ejson: String,
        title: String = "General",
        summary: String = "hello",
        bagKey: String = "extra",
    ) = buildJsonObject {
        put("title", JsonPrimitive(title))
        put("summary", JsonPrimitive(summary))
        put(bagKey, JsonPrimitive(buildJsonObject { put("ejson", JsonPrimitive(ejson)) }.toString()))
    }

    private val fullEjson = """{"rid":"GENERAL","name":" General","sender":{"username":"bob","name":"Bob"},"type":"c","host":"https://a.cn","messageType":"text","messageId":"m-1"}"""

    // ---- 层 1：raw.ejson 顶层 JSON 字符串（iOS / 到达事件） ----

    @Test
    fun `tier1 raw ejson top-level JSON string`() {
        val raw = rawStr("ejson", fullEjson)
        val p = PushPayloadParser.parsePushPayload(raw)
        assertEquals("GENERAL", p?.rid)
        assertEquals("c", p?.t)
        assertEquals("m-1", p?.messageId)
    }

    @Test
    fun `tier1 raw ejson as plain object also accepted`() {
        val raw = rawOf("""{"ejson":$fullEjson}""")
        assertEquals("GENERAL", PushPayloadParser.parsePushPayload(raw)?.rid)
    }

    // ---- 层 2：extra/extras 袋内 ejson（Android 点击实际形态，双重转义） ----

    @Test
    fun `tier2 canonical Android opened shape - extra bag with double-encoded ejson`() {
        val p = PushPayloadParser.parsePushPayload(canonicalRaw(fullEjson))
        assertEquals("GENERAL", p?.rid)
        assertEquals("c", p?.t)
        assertEquals("m-1", p?.messageId)
    }

    @Test
    fun `tier2 extras key variant`() {
        val p = PushPayloadParser.parsePushPayload(canonicalRaw(fullEjson, bagKey = "extras"))
        assertEquals("GENERAL", p?.rid)
    }

    @Test
    fun `tier2 extra bag with data field instead of ejson`() {
        val raw = buildJsonObject {
            put("extra", JsonPrimitive(buildJsonObject { put("data", JsonPrimitive(fullEjson)) }.toString()))
        }
        assertEquals("GENERAL", PushPayloadParser.parsePushPayload(raw)?.rid)
    }

    @Test
    fun `tier2 raw data top-level`() {
        val raw = rawStr("data", fullEjson)
        assertEquals("GENERAL", PushPayloadParser.parsePushPayload(raw)?.rid)
    }

    // ---- 层 3：extra 整袋即业务体 / extra 本身即 ejson 字符串 ----

    @Test
    fun `tier3 extra bag itself is the business body`() {
        val raw = rawStr("extra", fullEjson)
        val p = PushPayloadParser.parsePushPayload(raw)
        assertEquals("GENERAL", p?.rid)
        assertEquals("m-1", p?.messageId)
    }

    @Test
    fun `tier3 raw extra string that is not a bag falls to direct body parse`() {
        // extra 非 JSON 对象（如纯文本）→ 各层失败 → null
        val raw = rawOf("""{"extra":"not-json"}""")
        assertNull(PushPayloadParser.parsePushPayload(raw))
    }

    // ---- 层 4：顶层 rid+type 直挂 / payload.rid+type ----

    @Test
    fun `tier4 top-level rid+type direct`() {
        val raw = rawOf("""{"rid":"r-top","type":"d","name":"n","sender":{"username":"u","name":"n"},"host":"https://a.cn","messageId":"m-2"}""")
        val p = PushPayloadParser.parsePushPayload(raw)
        assertEquals("r-top", p?.rid)
        assertEquals("d", p?.t)
        assertEquals("m-2", p?.messageId)
    }

    @Test
    fun `tier4b payload rid+type nested`() {
        val raw = rawOf("""{"payload":$fullEjson}""")
        assertEquals("GENERAL", PushPayloadParser.parsePushPayload(raw)?.rid)
    }

    // ---- 层 5：裸 rid 兜底 'c'（厂商通道丢 type） ----

    @Test
    fun `tier5 bare rid falls back to channel c with empty sender`() {
        val raw = rawOf("""{"rid":"r-bare","messageId":"m-3"}""")
        val p = PushPayloadParser.parsePushPayload(raw)
        assertEquals("r-bare", p?.rid)
        assertEquals("c", p?.t)
        // RN 裸 rid 层 name 兜底 ''（已定义空串，非 null）
        assertEquals("", p?.title)
        assertNull(p?.host)
        assertEquals("m-3", p?.messageId)
    }

    // ---- 层 6：roomId + msgType=oncall 最小体 ----

    @Test
    fun `tier6 roomId plus msgType oncall builds minimal oncall body`() {
        val raw = rawOf("""{"roomId":"rm-1","msgType":"oncall","title":"Call","recordId":"rec-1","channelId":"ch-1","org":"org-1"}""")
        val p = PushPayloadParser.parsePushPayload(raw)
        assertEquals("rm-1", p?.rid)
        assertEquals("p", p?.t) // 最小体 type 兜底 'p'
        assertEquals("Call", p?.title)
        val voice = p?.voiceOncall
        assertEquals("rec-1", voice?.recordId)
        assertEquals("org-1", voice?.org)
        assertFalse(voice?.isCallEnd ?: true)
    }

    // ---- 退化 / 无法解析 ----

    @Test
    fun `unparseable garbage returns null`() {
        assertNull(PushPayloadParser.parsePushPayload(rawOf("""{"foo":"bar"}""")))
        assertNull(PushPayloadParser.parsePushPayload(rawOf("""{"extra":"[1,2,3]"}""")))
    }

    @Test
    fun `ejson without rid falls through to next tier`() {
        // 层1 ejson 无 rid → 落到裸 rid 层
        val raw = rawOf("""{"ejson":"{\"name\":\"x\"}","rid":"r-fall"}""")
        val p = PushPayloadParser.parsePushPayload(raw)
        assertEquals("r-fall", p?.rid)
        assertEquals("c", p?.t)
    }

    // ---- 合并：顶层与 extra 袋字段并入解析体（RN mergeTopLevelVoiceFields） ----

    @Test
    fun `top-level voice fields merge into parsed body`() {
        val ejson = """{"rid":"r-voice","type":"c","name":"n","sender":{"username":"u","name":"n"},"host":"","messageType":"oncall"}"""
        val raw = buildJsonObject {
            put("ejson", JsonPrimitive(ejson))
            put("msgType", JsonPrimitive("oncall"))
            put("recordId", JsonPrimitive("rec-9"))
            put("channelId", JsonPrimitive("ch-9"))
            put("org", JsonPrimitive("org-9"))
        }
        val p = PushPayloadParser.parsePushPayload(raw)
        assertTrue(p?.voiceOncall != null)
        assertEquals("rec-9", p?.voiceOncall?.recordId)
        assertEquals("org-9", p?.voiceOncall?.org)
    }

    @Test
    fun `bag oncall flag one string recognized as oncall`() {
        // RN: bag.oncall === '1' → rawOncallFlag → merge msgType='oncall'
        val raw = rawOf("""{"rid":"r-1","type":"c","oncall":"1","name":"n","sender":{"username":"u","name":"n"}}""")
        val p = PushPayloadParser.parsePushPayload(raw)
        assertTrue(p?.voiceOncall != null)
    }

    @Test
    fun `parsed oncall true string is not voice oncall (RN strict check)`() {
        // RN buildVoiceOncallParams: oncall === true || === 'oncall'；'true' 字符串不满足
        // （ejson 内层 oncall='true'，bag 顶层无 oncall 标记 → msgType 不合并 → 非语音）
        val ejson = """{"rid":"r-1","type":"c","oncall":"true","name":"n","sender":{"username":"u","name":"n"},"host":""}"""
        assertNull(PushPayloadParser.parsePushPayload(rawStr("ejson", ejson))?.voiceOncall)
    }

    @Test
    fun `bag-level oncall true string still recognized via msgType merge`() {
        // 顶层 bag oncall='true' → rawOncallFlag → merge msgType='oncall' → RN 照样识别语音
        val raw = rawOf("""{"rid":"r-1","type":"c","oncall":"true","name":"n","sender":{"username":"u","name":"n"}}""")
        assertTrue(PushPayloadParser.parsePushPayload(raw)?.voiceOncall != null)
    }

    // ---- ROOM_TYPE_MAP / 标题 / isCall / messageId 容忍缺失 ----

    @Test
    fun `room type map and unknown fallback`() {
        fun rawOfT(t: String) = rawOf("""{"rid":"r","type":"$t","sender":{"username":"u","name":"n"},"name":"n","host":"","messageId":""}""")
        assertEquals("d", PushPayloadParser.parsePushPayload(rawOfT("d"))?.t)
        assertEquals("p", PushPayloadParser.parsePushPayload(rawOfT("p"))?.t)
        assertEquals("l", PushPayloadParser.parsePushPayload(rawOfT("l"))?.t)
        assertEquals("c", PushPayloadParser.parsePushPayload(rawOfT("weird"))?.t)
    }

    @Test
    fun `title rules - d takes sender username, l takes sender name then name, else name`() {
        fun payloadOf(type: String, sender: String, name: String) =
            rawOf("""{"rid":"r","type":"$type","sender":$sender,"name":"$name","host":""}""")
        assertEquals("dm-user", PushPayloadParser.parsePushPayload(payloadOf("d", """{"username":"dm-user","name":"DM Name"}""", "ignored"))?.title)
        assertEquals("om-sender", PushPayloadParser.parsePushPayload(payloadOf("l", """{"username":"u","name":"om-sender"}""", "om-name"))?.title)
        // RN `sender?.name ?? name`：空串是已定义值，?? 不回落（RN 语义原样）
        assertEquals("", PushPayloadParser.parsePushPayload(payloadOf("l", """{"username":"u","name":""}""", "om-name"))?.title)
        // sender.name 缺失 → 回落 name
        assertEquals("om-name", PushPayloadParser.parsePushPayload(payloadOf("l", """{"username":"u"}""", "om-name"))?.title)
        assertEquals("chan-name", PushPayloadParser.parsePushPayload(payloadOf("c", """{"username":"u","name":"n"}""", "chan-name"))?.title)
    }

    // 坑8：ejson 无 messageId 时只进房不高亮
    @Test
    fun `missing messageId tolerated for navigation`() {
        val ejson = """{"rid":"r-nomsg","type":"c","name":"n","sender":{"username":"u","name":"n"},"host":"https://a.cn","messageId":""}"""
        val p = PushPayloadParser.parsePushPayload(rawStr("ejson", ejson))
        assertEquals("r-nomsg", p?.rid)
        assertNull(p?.messageId)
    }

    @Test
    fun `isCall true only for jitsi_call_started`() {
        val started = fullEjson.replace("\"messageType\":\"text\"", "\"messageType\":\"jitsi_call_started\"")
        assertTrue(PushPayloadParser.parsePushPayload(rawStr("ejson", started))?.isCall == true)
        assertFalse(PushPayloadParser.parsePushPayload(rawStr("ejson", fullEjson))?.isCall ?: true)
    }

    @Test
    fun `voice oncall recognized via msgType or messageType or oncall`() {
        fun oncallEjson(field: String) =
            """{"rid":"rv","type":"c","name":"n","sender":{"username":"u","name":"n"},"host":"","$field":${if (field == "oncall") "true" else "\"oncall\""}}"""
        for (field in listOf("msgType", "messageType", "oncall")) {
            val p = PushPayloadParser.parsePushPayload(rawStr("ejson", oncallEjson(field)))
            assertTrue(p?.voiceOncall != null, "field=$field should be recognized as oncall")
        }
    }

    @Test
    fun `isCallEnd passthrough to voice params`() {
        val ejson = """{"rid":"rv","type":"c","name":"n","sender":{"username":"u","name":"n"},"host":"","msgType":"oncall","isCallEnd":true}"""
        val p = PushPayloadParser.parsePushPayload(rawStr("ejson", ejson))
        assertEquals(true, p?.voiceOncall?.isCallEnd)
    }
}
