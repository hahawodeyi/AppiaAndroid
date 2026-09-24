package cn.appia.im.domain.presence

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 索引协议（RN parseStreamUserPresenceMessage.test + brief 红线）：
 * args[0]=[?, index, statusText?] → USER_STATUSES[index]；越界回退 OFFLINE；
 * 字符串直传（协议外形态）整帧丢弃。
 */
class PresenceStreamParserTest {

    private fun frame(uid: String, arg: String) =
        """{"msg":"changed","collection":"stream-user-presence","id":"e1",
           "fields":{"uid":"$uid","args":[$arg]}}""".replace("\n", " ")

    @Test
    fun `parses uid and status index from ddp message`() {
        val parsed = PresenceStreamParser.parse(Json.parseToJsonElement(frame("user1", "[null,1,\"Online\"]")).jsonObject)
        assertEquals("user1", parsed?.userId)
        assertEquals(TUserStatus.ONLINE, parsed?.status)
        assertEquals("Online", parsed?.statusText)
    }

    @Test
    fun `maps every user status index`() {
        // RN USER_STATUSES = ['offline','online','away','busy','disabled','loading']
        val expected = listOf(
            TUserStatus.OFFLINE, TUserStatus.ONLINE, TUserStatus.AWAY,
            TUserStatus.BUSY, TUserStatus.DISABLED, TUserStatus.LOADING,
        )
        assertEquals(expected, PresenceStreamParser.USER_STATUS_INDEX)
        expected.forEachIndexed { idx, status ->
            val parsed = PresenceStreamParser.parse(Json.parseToJsonElement(frame("u", "[null,$idx]")).jsonObject)
            assertEquals(status, parsed?.status, "index $idx")
        }
    }

    @Test
    fun `out of range index falls back to offline`() {
        val parsed = PresenceStreamParser.parse(Json.parseToJsonElement(frame("u", "[null,9]")).jsonObject)
        assertEquals(TUserStatus.OFFLINE, parsed?.status)
        val negative = PresenceStreamParser.parse(Json.parseToJsonElement(frame("u", "[null,-1]")).jsonObject)
        assertEquals(TUserStatus.OFFLINE, negative?.status)
    }

    @Test
    fun `string status payload is dropped not mapped`() {
        // 协议红线：字符串直传全 miss（typeof statusIndex !== 'number' → null）
        val parsed = PresenceStreamParser.parse(Json.parseToJsonElement(frame("u", "[null,'online']")).jsonObject)
        assertNull(parsed)
    }

    @Test
    fun `returns null when payload incomplete`() {
        assertNull(PresenceStreamParser.parse(Json.parseToJsonElement("""{"fields":{}}""")))
        assertNull(PresenceStreamParser.parse(Json.parseToJsonElement("""{"fields":{"uid":"u"}}""")))
        assertNull(PresenceStreamParser.parse(Json.parseToJsonElement("""{"fields":{"args":[[null,1]]}}""")))
        assertNull(PresenceStreamParser.parse(Json.parseToJsonElement("""{"fields":{"uid":"u","args":["online"]}}""")))
    }
}
