package cn.appia.im.core.settings

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 注册表对照 RN src/constants/serverSettingRegistry.ts 逐条抽查（TDD）：
 * 五关键 id 类型映射 / 全表分布 / 归一边界（布尔串"true"/数字串/数组 JSON/mute_unmute 展开/未注册丢弃）。
 */
class ServerSettingRegistryTest {

    // ---- 五关键 id（任务简报钉死）----

    @Test
    fun `key ids map to RN column types`() {
        assertEquals(SettingColumnType.STRING, ServerSettingRegistry.columnOf["Enterprise_Name"])
        assertEquals(SettingColumnType.BOOLEAN, ServerSettingRegistry.columnOf["UI_Use_Real_Name"])
        assertEquals(SettingColumnType.BOOLEAN, ServerSettingRegistry.columnOf["Appia_Show_External_Partners"])
        assertEquals(SettingColumnType.STRING, ServerSettingRegistry.columnOf["Agent_Bot_List"])
        assertEquals(SettingColumnType.STRING, ServerSettingRegistry.columnOf["Appia_Claw_Agent_Visibility"])
    }

    @Test
    fun `registry distribution matches RN source`() {
        // RN 源 grep 实测：52 boolean / 55 string / 14 number / 1 array = 122（计划「86 键」为笔误）
        assertEquals(122, ServerSettingRegistry.columnOf.size)
        assertEquals(122, ServerSettingRegistry.ids.size)
        assertEquals(52, ServerSettingRegistry.columnOf.values.count { it == SettingColumnType.BOOLEAN })
        assertEquals(55, ServerSettingRegistry.columnOf.values.count { it == SettingColumnType.STRING })
        assertEquals(14, ServerSettingRegistry.columnOf.values.count { it == SettingColumnType.NUMBER })
        assertEquals(1, ServerSettingRegistry.columnOf.values.count { it == SettingColumnType.ARRAY })
    }

    @Test
    fun `ids order is stable insertion order`() {
        // 分批请求顺序 = 注册表键序（0 基第 50/末位抽查）
        val ids = ServerSettingRegistry.ids
        assertEquals("Accounts_AllowEmailChange", ids.first())
        assertEquals("uniqueID", ids[49])
        assertEquals("Appia_Claw_Agent_Visibility", ids.last())
    }

    // ---- prepare 归一边界 ----

    private fun prepare(id: String, valueJson: String) =
        ServerSettingRegistry.prepareSettingEntity(id, Json.parseToJsonElement(valueJson))

    @Test
    fun `prepare boolean from true string and json true`() {
        // 服务端对布尔 setting 可能回串 "true"（旧版 parseSettings 兼容口径）
        val fromString = prepare("UI_Use_Real_Name", "\"true\"")
        assertEquals(true, fromString!!.value_as_boolean)
        assertEquals(null, fromString.value_as_string)
        val fromJson = prepare("UI_Use_Real_Name", "true")
        assertEquals(true, fromJson!!.value_as_boolean)
        val fromFalseString = prepare("UI_Use_Real_Name", "\"false\"")
        assertEquals(false, fromFalseString!!.value_as_boolean)
        val fromNull = prepare("UI_Use_Real_Name", "null")
        assertEquals(false, fromNull!!.value_as_boolean)
    }

    @Test
    fun `prepare number from numeric string`() {
        val e = prepare("Message_GroupingPeriod", "\"300\"")
        assertEquals(300.0, e!!.value_as_number)
        assertEquals(null, e.value_as_string)
        val direct = prepare("Appia_Search_Person_Limit", "20")
        assertEquals(20.0, direct!!.value_as_number)
        // 非数字串 → 0（RN Number("x") = NaN，SQLite 无 NaN，落 0）
        val garbage = prepare("Message_GroupingPeriod", "\"abc\"")
        assertEquals(0.0, garbage!!.value_as_number)
    }

    @Test
    fun `prepare string null becomes empty`() {
        val e = prepare("Enterprise_Name", "null")
        assertEquals("", e!!.value_as_string)
        val s = prepare("Agent_Bot_List", "\"[\\\"bot1\\\"]\"")
        assertEquals("[\"bot1\"]", s!!.value_as_string)
    }

    @Test
    fun `prepare array expands mute_unmute and stores JSON string`() {
        // RN expandHideSystemMessages：mute_unmute → user-muted + user-unmuted
        val e = prepare("Hide_System_Messages", """["mute_unmute","other"]""")
        assertEquals("""["user-muted","user-unmuted","other"]""", e!!.value_as_string)
        // 非数组（null/标量）→ 空数组串
        val empty = prepare("Hide_System_Messages", "null")
        assertEquals("[]", empty!!.value_as_string)
    }

    @Test
    fun `prepare unregistered id returns null`() {
        assertNull(ServerSettingRegistry.prepareSettingEntity("Unknown_Key_XXX", Json.parseToJsonElement("1")))
        assertNull(ServerSettingRegistry.prepareSettingEntity("", Json.parseToJsonElement("1")))
    }

    @Test
    fun `prepare sets updated_at epoch millis`() {
        val before = System.currentTimeMillis()
        val e = prepare("Site_Name", "\"Appia\"")
        assertTrue(e!!._updated_at!! >= before)
        assertTrue(e._updated_at!! <= System.currentTimeMillis())
    }

    // ---- usePublicSettingBoolean 等价（缺行回 default）----

    @Test
    fun `publicSettingBoolean falls back to default on missing row`() {
        // 缺行 → default（DAO getById 返回 null 路径；in-memory DAO 见 SettingsPublicApiTest 全链）
        val missing = FakeSettingDao()
        kotlinx.coroutines.runBlocking {
            assertEquals(true, missing.publicSettingBoolean("UI_Use_Real_Name", true))
            assertEquals(false, missing.publicSettingBoolean("UI_Use_Real_Name"))
        }
        // 有行但布尔列为 null（换列类型后）→ 仍回 default
        val row = FakeSettingDao()
        row.rows["UI_Use_Real_Name"] = cn.appia.im.core.database.entity.SettingEntity(
            _id = "UI_Use_Real_Name", value_as_string = "x",
        )
        kotlinx.coroutines.runBlocking {
            assertEquals(true, row.publicSettingBoolean("UI_Use_Real_Name", true))
        }
    }

    /** 最小 getById 桩：只验证 default 回退语义，upsert 链路走 Robolectric 真 DAO。 */
    private class FakeSettingDao : cn.appia.im.core.database.dao.SettingDao {
        val rows = mutableMapOf<String, cn.appia.im.core.database.entity.SettingEntity>()
        override suspend fun insert(entity: cn.appia.im.core.database.entity.SettingEntity) { rows[entity._id] = entity }
        override suspend fun getById(id: String) = rows[id]
        override suspend fun getAll() = rows.values.toList()
        override suspend fun update(entity: cn.appia.im.core.database.entity.SettingEntity) { rows[entity._id] = entity }
        override suspend fun delete(entity: cn.appia.im.core.database.entity.SettingEntity) { rows.remove(entity._id) }
        override fun observe() = kotlinx.coroutines.flow.MutableStateFlow(rows.values.toList())
        override suspend fun upsertAll(entities: List<cn.appia.im.core.database.entity.SettingEntity>) {
            entities.forEach { rows[it._id] = it }
        }
        override suspend fun upsert(entity: cn.appia.im.core.database.entity.SettingEntity) { rows[entity._id] = entity }
        override fun observeById(id: String) = kotlinx.coroutines.flow.MutableStateFlow(rows[id])
    }
}
