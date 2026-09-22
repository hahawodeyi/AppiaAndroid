package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketSdk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 群组设置/角色端点 wire 对照（RN src/services/api/roomSettings.ts 逐字段）：
 * saveRoomSettings/addUsersToRoom 走 method.call 信封（RN callMethodRest/callMethod），
 * 其余纯 REST；t 前缀映射 c→channels/p→groups/d→im（未命中兜底 channels）；
 * team 主房移除双调用（teams.removeMember 先、{prefix}.kick 后）。
 */
class RoomSettingsApiTest {
    private val server = MockWebServer()

    @BeforeEach
    fun setUp() {
        server.start()
    }

    @AfterEach
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    /** hydrateRestSession 直接注入 REST 会话（无登录/无 DDP，同 SubscriptionsApiTest）。 */
    private fun newSdk(): RocketSdk =
        RocketSdk(client = OkHttpClient()).also { it.hydrateRestSession(server.url("/").toString(), "tok", "uid") }

    /** method.call 响应信封（message 字段 = DDP 帧 JSON 串，同 RecallApiTest.envelope）。 */
    private fun envelope(resultJson: String): String {
        val inner = Json.parseToJsonElement(
            """{"jsonrpc":"2.0","message":"mid","result":$resultJson}""",
        ).toString()
        return """{"message":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(inner))}}"""
    }

    private fun methodFrame(requestBody: String): kotlinx.serialization.json.JsonObject {
        val outer = Json.parseToJsonElement(requestBody).jsonObject
        return Json.parseToJsonElement(outer["message"]!!.jsonPrimitive.content).jsonObject
    }

    // ---- saveRoomSettings（RN :21-28 callMethodRest [rid, params]）----

    @Test
    fun `saveRoomSettings wraps rid and settings as two-element params via method call`() = runBlocking {
        server.enqueue(MockResponse().setBody(envelope("""{"result":true,"rid":"r1"}""")))
        val result = RoomSettingsApi.postSaveRoomSettings(
            newSdk(),
            "r1",
            SaveRoomSettingsParams(roomName = "\u65b0\u540d"),
        )

        val req = server.takeRequest()
        assertEquals("/api/v1/method.call/saveRoomSettings", req.path)
        val frame = methodFrame(req.body.readUtf8())
        assertEquals("method", frame["msg"]!!.jsonPrimitive.content)
        assertEquals("saveRoomSettings", frame["method"]!!.jsonPrimitive.content)
        val params = frame["params"]!!.jsonArray
        assertEquals(2, params.size) // RN [rid, params] 双元素
        assertEquals("r1", params[0].jsonPrimitive.content)
        assertEquals("\u65b0\u540d", params[1].jsonObject["roomName"]!!.jsonPrimitive.content)
        // 信封解析后的 result
        assertEquals(true, result!!.jsonObject["result"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("r1", result.jsonObject["rid"]!!.jsonPrimitive.content)
    }

    @Test
    fun `saveRoomSettings encodes all param keys and omits absent ones`() = runBlocking {
        server.enqueue(MockResponse().setBody(envelope("null")))
        RoomSettingsApi.postSaveRoomSettings(
            newSdk(),
            "r1",
            SaveRoomSettingsParams(
                appiaUsage = listOf("cop", "meeting"),
                roomDescription = "desc",
                roomTopic = "topic",
                encrypted = true,
                federated = false,
                roomAnnouncementData = RoomAnnouncementData(
                    id = "a1",
                    message = "\u516c\u544a",
                    files = listOf(RoomAnnouncementFile("f.txt", "http://x/f.txt", "txt")),
                ),
            ),
        )

        val arg = methodFrame(server.takeRequest().body.readUtf8())["params"]!!.jsonArray[1].jsonObject
        assertEquals("cop", arg["appiaUsage"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("meeting", arg["appiaUsage"]!!.jsonArray[1].jsonPrimitive.content)
        assertEquals("desc", arg["roomDescription"]!!.jsonPrimitive.content)
        assertEquals("topic", arg["roomTopic"]!!.jsonPrimitive.content)
        assertEquals(true, arg["encrypted"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(false, arg["federated"]!!.jsonPrimitive.content.toBoolean())
        val ann = arg["roomAnnouncementData"]!!.jsonObject
        assertEquals("a1", ann["_id"]!!.jsonPrimitive.content)
        assertEquals("\u516c\u544a", ann["message"]!!.jsonPrimitive.content)
        val file = ann["files"]!!.jsonArray[0].jsonObject
        assertEquals("f.txt", file["fileName"]!!.jsonPrimitive.content)
        assertEquals("http://x/f.txt", file["fileUrl"]!!.jsonPrimitive.content)
        assertEquals("txt", file["fileType"]!!.jsonPrimitive.content)
        assertNull(arg["roomName"]) // 缺省字段不编码
        assertNull(arg["roomAvatar"])
    }

    @Test
    fun `saveRoomSettings delete announcement encodes _id and type only`() = runBlocking {
        server.enqueue(MockResponse().setBody(envelope("null")))
        RoomSettingsApi.postSaveRoomSettings(
            newSdk(),
            "r1",
            SaveRoomSettingsParams(roomAnnouncementData = RoomAnnouncementData(id = "a9", type = "delete")),
        )

        val ann = methodFrame(server.takeRequest().body.readUtf8())
            .let { it["params"]!!.jsonArray[1].jsonObject["roomAnnouncementData"]!!.jsonObject }
        assertEquals("a9", ann["_id"]!!.jsonPrimitive.content)
        assertEquals("delete", ann["type"]!!.jsonPrimitive.content)
        assertNull(ann["message"])
        assertNull(ann["files"])
    }

    // ---- saveNotification（RN :37-40）----

    @Test
    fun `saveNotification posts roomId and notifications object`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        RoomSettingsApi.postSaveRoomNotification(
            newSdk(),
            "r1",
            RoomNotificationSettings(disableNotifications = "1", hideUnreadStatus = "1"),
        )

        val req = server.takeRequest()
        assertEquals("/api/v1/rooms.saveNotification", req.path)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("r1", body["roomId"]!!.jsonPrimitive.content)
        val n = body["notifications"]!!.jsonObject
        assertEquals("1", n["disableNotifications"]!!.jsonPrimitive.content)
        assertEquals("1", n["hideUnreadStatus"]!!.jsonPrimitive.content)
        assertNull(n["muteGroupMentions"]) // 缺省不编码
    }

    // ---- leave（RN :46-55 t 前缀映射）----

    @Test
    fun `leave maps c to channels`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        RoomSettingsApi.postLeaveRoom(newSdk(), "r1", "c")
        val req = server.takeRequest()
        assertEquals("/api/v1/channels.leave", req.path)
        assertEquals("""{"roomId":"r1"}""", req.body.readUtf8())
    }

    @Test
    fun `leave maps p to groups`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        RoomSettingsApi.postLeaveRoom(newSdk(), "r2", "p")
        assertEquals("/api/v1/groups.leave", server.takeRequest().path)
    }

    @Test
    fun `leave maps d to im`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        RoomSettingsApi.postLeaveRoom(newSdk(), "r3", "d")
        assertEquals("/api/v1/im.leave", server.takeRequest().path)
    }

    @Test
    fun `leave falls back to channels on unknown type`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        RoomSettingsApi.postLeaveRoom(newSdk(), "r4", "x")
        assertEquals("/api/v1/channels.leave", server.takeRequest().path)
    }

    // ---- roles（RN :67-70 GET）----

    @Test
    fun `roles uses prefix and roomId query param`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"roles":[]}"""))
        RoomSettingsApi.getRoomRoles(newSdk(), "r1", "p")
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/v1/groups.roles?roomId=r1", req.path)
    }

    // ---- toggle owner moderator ghostOwner（RN :73-91）----

    @Test
    fun `toggle owner add and remove use prefix endpoints`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        RoomSettingsApi.postToggleRoomOwner(newSdk(), "r1", "p", "u1", isOwner = true)
        val add = server.takeRequest()
        assertEquals("/api/v1/groups.addOwner", add.path)
        assertEquals("""{"roomId":"r1","userId":"u1"}""", add.body.readUtf8())

        server.enqueue(MockResponse().setBody("{}"))
        RoomSettingsApi.postToggleRoomOwner(newSdk(), "r1", "c", "u1", isOwner = false)
        val remove = server.takeRequest()
        assertEquals("/api/v1/channels.removeOwner", remove.path)
        assertEquals("""{"roomId":"r1","userId":"u1"}""", remove.body.readUtf8())
    }

    @Test
    fun `toggle moderator add and remove use prefix endpoints`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        RoomSettingsApi.postToggleRoomModerator(newSdk(), "r1", "d", "u1", isModerator = true)
        assertEquals("/api/v1/im.addModerator", server.takeRequest().path)

        server.enqueue(MockResponse().setBody("{}"))
        RoomSettingsApi.postToggleRoomModerator(newSdk(), "r1", "p", "u1", isModerator = false)
        assertEquals("/api/v1/groups.removeModerator", server.takeRequest().path)
    }

    @Test
    fun `toggle ghost owner add and remove use prefix endpoints`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        RoomSettingsApi.postToggleRoomGhostOwner(newSdk(), "r1", "p", "u1", isGhostOwner = true)
        assertEquals("/api/v1/groups.addGhostOwner", server.takeRequest().path)

        server.enqueue(MockResponse().setBody("{}"))
        RoomSettingsApi.postToggleRoomGhostOwner(newSdk(), "r1", "c", "u1", isGhostOwner = false)
        assertEquals("/api/v1/channels.removeGhostOwner", server.takeRequest().path)
    }

    // ---- removeUser（RN :94-107 team 双调用）----

    @Test
    fun `removeUser without teamId sends only kick`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        RoomSettingsApi.postRemoveUserFromRoom(newSdk(), "r1", "p", "u1")

        assertEquals(1, server.requestCount)
        val req = server.takeRequest()
        assertEquals("/api/v1/groups.kick", req.path)
        assertEquals("""{"roomId":"r1","userId":"u1"}""", req.body.readUtf8())
    }

    @Test
    fun `removeUser with teamId sends teams removeMember then kick`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))
        RoomSettingsApi.postRemoveUserFromRoom(newSdk(), "r1", "c", "u1", teamId = "team-1")

        val first = server.takeRequest()
        assertEquals("/api/v1/teams.removeMember", first.path)
        assertEquals("""{"teamId":"team-1","userId":"u1"}""", first.body.readUtf8())
        val second = server.takeRequest()
        assertEquals("/api/v1/channels.kick", second.path)
        assertEquals("""{"roomId":"r1","userId":"u1"}""", second.body.readUtf8())
    }

    @Test
    fun `removeUser trims teamId and skips empty`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        RoomSettingsApi.postRemoveUserFromRoom(newSdk(), "r1", "p", "u1", teamId = "   ")

        assertEquals(1, server.requestCount) // trim 后空串跳过 teams.removeMember
        assertEquals("/api/v1/groups.kick", server.takeRequest().path)
    }

    @Test
    fun `removeUser aborts before kick when teams removeMember fails`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"no team"}"""))
        runCatching {
            runBlocking { RoomSettingsApi.postRemoveUserFromRoom(newSdk(), "r1", "c", "u1", teamId = "team-1") }
        }
        assertEquals(1, server.requestCount) // teams.removeMember 失败 → kick 不发（RN await 链）
    }

    // ---- addUsers（RN :110-113 Meteor 单对象参数）----

    @Test
    fun `addUsersToRoom wraps single object param via method call`() = runBlocking {
        server.enqueue(MockResponse().setBody(envelope("null")))
        RoomSettingsApi.postAddUsersToRoom(newSdk(), "r1", listOf("a", "b"), listOf("dep1"), isShareRecord = true)

        val req = server.takeRequest()
        assertEquals("/api/v1/method.call/addUsersToRoom", req.path)
        val frame = methodFrame(req.body.readUtf8())
        assertEquals("addUsersToRoom", frame["method"]!!.jsonPrimitive.content)
        val params = frame["params"]!!.jsonArray
        assertEquals(1, params.size) // RN {rid, ...params} 单对象
        val arg = params[0].jsonObject
        assertEquals("r1", arg["rid"]!!.jsonPrimitive.content)
        assertEquals("a", arg["users"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("b", arg["users"]!!.jsonArray[1].jsonPrimitive.content)
        assertEquals("dep1", arg["depIds"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(true, arg["isShareRecord"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `addUsersToRoom omits isShareRecord when null`() = runBlocking {
        server.enqueue(MockResponse().setBody(envelope("null")))
        RoomSettingsApi.postAddUsersToRoom(newSdk(), "r1", listOf("a"), listOf())

        val arg = methodFrame(server.takeRequest().body.readUtf8())["params"]!!.jsonArray[0].jsonObject
        assertNull(arg["isShareRecord"])
        assertEquals(0, arg["depIds"]!!.jsonArray.size)
    }

    // ---- removeDepartment（RN :116-119）----

    @Test
    fun `removeDepartment posts rid and depIds array`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        RoomSettingsApi.postRemoveDepartmentFromRoom(newSdk(), "r1", listOf("d1", "d2"))

        val req = server.takeRequest()
        assertEquals("/api/v1/local.removeGroupUsersToRoom", req.path)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("r1", body["rid"]!!.jsonPrimitive.content)
        assertEquals("d1", body["depIds"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("d2", body["depIds"]!!.jsonArray[1].jsonPrimitive.content)
    }

    // ---- favorite / like（RN :43-44 / :57-59）----

    @Test
    fun `favorite delegates to SubscriptionsApi single implementation`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        RoomSettingsApi.postRoomsFavorite(newSdk(), "r1", true)
        val req = server.takeRequest()
        assertEquals("/api/v1/rooms.favorite", req.path)
        assertEquals("""{"roomId":"r1","favorite":true}""", req.body.readUtf8())
    }

    @Test
    fun `like posts roomId and like body`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        RoomSettingsApi.postRoomsLike(newSdk(), "r1", false)
        val req = server.takeRequest()
        assertEquals("/api/v1/rooms.like", req.path)
        assertEquals("""{"roomId":"r1","like":false}""", req.body.readUtf8())
    }

    @Test
    fun `meteor error inside envelope throws`() {
        // DDP 帧顶层 error（parseDdpResultPayload 以 error 键判失败，非 result 内嵌）
        val inner = Json.parseToJsonElement("""{"jsonrpc":"2.0","message":"mid","error":{"reason":"no permission"}}""").toString()
        val body = """{"message":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(inner))}}"""
        server.enqueue(MockResponse().setBody(body))
        val sdk = newSdk()
        val ex = runCatching {
            runBlocking { RoomSettingsApi.postSaveRoomSettings(sdk, "r1", SaveRoomSettingsParams(roomName = "x")) }
        }.exceptionOrNull()
        assertTrue(ex is cn.appia.im.core.network.rest.ApiException)
        assertEquals("no permission", ex!!.message)
    }
}
