package cn.appia.im.core.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import cn.appia.im.core.database.dao.SettingDao
import cn.appia.im.core.database.entity.SettingEntity
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** setting `_id` 落库列类型（RN serverSettingRegistry.ts 的 `valueAsXxx` 列名等价）。 */
enum class SettingColumnType { STRING, BOOLEAN, NUMBER, ARRAY }

/**
 * 全站公开 setting 的 `_id` → 落库列类型（RN src/constants/serverSettingRegistry.ts 逐条转录）。
 * 由 settings.public 分批同步 / public-settings-changed 流式增量共用；未注册 id 保守丢弃（RN 同）。
 *
 * 计划口径「86 键」为笔误：RN 源注册表实为 122 键（52 boolean / 55 string / 14 number / 1 array，
 * ServerSettingRegistryTest 钉死分布）。
 */
object ServerSettingRegistry {

    /** 插入序 LinkedHashMap：分批请求顺序稳定（wire 测试断言首批 id）。 */
    val columnOf: Map<String, SettingColumnType> = linkedMapOf(
        "Accounts_AllowEmailChange" to SettingColumnType.BOOLEAN,
        "Accounts_AllowPasswordChange" to SettingColumnType.BOOLEAN,
        "Accounts_AllowRealNameChange" to SettingColumnType.BOOLEAN,
        "Accounts_AllowUserAvatarChange" to SettingColumnType.BOOLEAN,
        "Accounts_AllowUserProfileChange" to SettingColumnType.BOOLEAN,
        "Accounts_AllowUserStatusMessageChange" to SettingColumnType.BOOLEAN,
        "Accounts_AllowUsernameChange" to SettingColumnType.BOOLEAN,
        "Accounts_AvatarBlockUnauthenticatedAccess" to SettingColumnType.BOOLEAN,
        "Accounts_CustomFields" to SettingColumnType.STRING,
        "Accounts_EmailOrUsernamePlaceholder" to SettingColumnType.STRING,
        "Accounts_EmailVerification" to SettingColumnType.BOOLEAN,
        "Accounts_NamePlaceholder" to SettingColumnType.STRING,
        "Accounts_PasswordPlaceholder" to SettingColumnType.STRING,
        "Accounts_PasswordReset" to SettingColumnType.BOOLEAN,
        "Accounts_RegistrationForm" to SettingColumnType.STRING,
        "Accounts_RegistrationForm_LinkReplacementText" to SettingColumnType.STRING,
        "Accounts_ShowFormLogin" to SettingColumnType.BOOLEAN,
        "Accounts_ManuallyApproveNewUsers" to SettingColumnType.BOOLEAN,
        "API_Use_REST_For_DDP_Calls" to SettingColumnType.BOOLEAN,
        "Accounts_iframe_enabled" to SettingColumnType.BOOLEAN,
        "Accounts_Iframe_api_url" to SettingColumnType.STRING,
        "Accounts_Iframe_api_method" to SettingColumnType.STRING,
        "CROWD_Enable" to SettingColumnType.BOOLEAN,
        "DirectMesssage_maxUsers" to SettingColumnType.NUMBER,
        "E2E_Enable" to SettingColumnType.BOOLEAN,
        "Accounts_Directory_DefaultView" to SettingColumnType.STRING,
        "FEDERATION_Enabled" to SettingColumnType.BOOLEAN,
        "Hide_System_Messages" to SettingColumnType.ARRAY,
        "LDAP_Enable" to SettingColumnType.BOOLEAN,
        "Livechat_request_comment_when_closing_conversation" to SettingColumnType.BOOLEAN,
        "Jitsi_Enabled" to SettingColumnType.BOOLEAN,
        "Jitsi_SSL" to SettingColumnType.BOOLEAN,
        "Jitsi_Domain" to SettingColumnType.STRING,
        "Jitsi_Enabled_TokenAuth" to SettingColumnType.BOOLEAN,
        "Jitsi_URL_Room_Hash" to SettingColumnType.BOOLEAN,
        "Jitsi_URL_Room_Prefix" to SettingColumnType.STRING,
        "Message_AllowDeleting" to SettingColumnType.BOOLEAN,
        "Message_AllowDeleting_BlockDeleteInMinutes" to SettingColumnType.NUMBER,
        "Message_AllowEditing" to SettingColumnType.BOOLEAN,
        "Message_AllowEditing_BlockEditInMinutes" to SettingColumnType.NUMBER,
        "Message_AllowPinning" to SettingColumnType.BOOLEAN,
        "Message_AllowStarring" to SettingColumnType.BOOLEAN,
        "Message_AudioRecorderEnabled" to SettingColumnType.BOOLEAN,
        "Message_GroupingPeriod" to SettingColumnType.NUMBER,
        "Message_TimeFormat" to SettingColumnType.STRING,
        "Message_TimeAndDateFormat" to SettingColumnType.STRING,
        "Site_Name" to SettingColumnType.STRING,
        "Site_Url" to SettingColumnType.STRING,
        "Store_Last_Message" to SettingColumnType.BOOLEAN,
        "uniqueID" to SettingColumnType.STRING,
        "UI_Allow_room_names_with_special_chars" to SettingColumnType.BOOLEAN,
        "UI_Use_Real_Name" to SettingColumnType.BOOLEAN,
        "Assets_favicon_512" to SettingColumnType.STRING,
        "Appia_Message_Read_Receipt_Enabled" to SettingColumnType.BOOLEAN,
        "Appia_Message_Read_Receipt_Store_Users" to SettingColumnType.BOOLEAN,
        "Threads_enabled" to SettingColumnType.BOOLEAN,
        "FileUpload_MediaTypeWhiteList" to SettingColumnType.STRING,
        "FileUpload_MaxFileSize" to SettingColumnType.NUMBER,
        "API_Gitlab_URL" to SettingColumnType.STRING,
        "AutoTranslate_Enabled" to SettingColumnType.BOOLEAN,
        "CAS_enabled" to SettingColumnType.BOOLEAN,
        "CAS_login_url" to SettingColumnType.STRING,
        "Force_Screen_Lock" to SettingColumnType.BOOLEAN,
        "Force_Screen_Lock_After" to SettingColumnType.NUMBER,
        "Allow_Save_Media_to_Gallery" to SettingColumnType.BOOLEAN,
        "Accounts_AllowInvisibleStatusOption" to SettingColumnType.STRING,
        "Agent_Bot_List" to SettingColumnType.STRING,
        "Jitsi_Enable_Teams" to SettingColumnType.BOOLEAN,
        "Jitsi_Enable_Channels" to SettingColumnType.BOOLEAN,
        "Canned_Responses_Enable" to SettingColumnType.BOOLEAN,
        "Livechat_allow_manual_on_hold" to SettingColumnType.BOOLEAN,
        "Accounts_AvatarExternalProviderUrl" to SettingColumnType.STRING,
        "VideoConf_Enable_DMs" to SettingColumnType.BOOLEAN,
        "VideoConf_Enable_Channels" to SettingColumnType.BOOLEAN,
        "VideoConf_Enable_Groups" to SettingColumnType.BOOLEAN,
        "VideoConf_Enable_Teams" to SettingColumnType.BOOLEAN,
        "Accounts_AllowDeleteOwnAccount" to SettingColumnType.BOOLEAN,
        "Number_of_users_autocomplete_suggestions" to SettingColumnType.NUMBER,
        "Presence_broadcast_disabled" to SettingColumnType.BOOLEAN,
        "Enterprise_ID" to SettingColumnType.STRING,
        "Enterprise_Name" to SettingColumnType.STRING,
        "Udesk_Buttons_Expired" to SettingColumnType.NUMBER,
        "Org_Matrix_Domain" to SettingColumnType.STRING,
        "Staff_Service_Names" to SettingColumnType.STRING,
        "StaffChatService_Enable_Self_Hosted" to SettingColumnType.STRING,
        "StaffChatService_Whitelist_Users" to SettingColumnType.STRING,
        "Appia_NoMessageBox_Robots" to SettingColumnType.STRING,
        "Appia_Department_Settings" to SettingColumnType.STRING,
        "Appia_EMT_Settings_230507" to SettingColumnType.STRING,
        "Appia_Xiao_Mian_Hua_Bucket" to SettingColumnType.STRING,
        "Appia_Xiao_Mian_Hua_Endpoint" to SettingColumnType.STRING,
        "Appia_Xiao_Mian_Hua_Upload_Dir" to SettingColumnType.STRING,
        "Appia_Xiao_Mian_Hua_Url_Prefix" to SettingColumnType.STRING,
        "Appia_Role_Sort_Settings" to SettingColumnType.STRING,
        "Appia_Dynamic_Max_File_Size" to SettingColumnType.NUMBER,
        "Appia_Dynamic_Whitelist" to SettingColumnType.STRING,
        "Shimo_Api_Url" to SettingColumnType.STRING,
        "Shimo_Web_Url" to SettingColumnType.STRING,
        "Appia_Room_Side_Menu_StaffServiceButton" to SettingColumnType.STRING,
        "Appia_OAW_Url" to SettingColumnType.STRING,
        "Appia_Hrm_Update_Time" to SettingColumnType.NUMBER,
        "Appia_Fanwei_Mobile_Url" to SettingColumnType.STRING,
        "Appia_Create_External_Discussion_Members" to SettingColumnType.STRING,
        "Appia_Create_External_Channel_Members" to SettingColumnType.STRING,
        "Appia_Webview_Global_Proxy" to SettingColumnType.STRING,
        "Appia_Get_Unread_Msgs_Interval" to SettingColumnType.NUMBER,
        "Appia_Fast_Model_ChatGpt_Url" to SettingColumnType.STRING,
        "Avatar_Team_Dot_Color" to SettingColumnType.STRING,
        "Avatar_Channel_Dot_Color" to SettingColumnType.STRING,
        "Avatar_Discussion_Dot_Color" to SettingColumnType.STRING,
        "Avatar_Dot_Show" to SettingColumnType.BOOLEAN,
        "Avatar_Local_Version" to SettingColumnType.STRING,
        "Appia_Search_Person_Limit" to SettingColumnType.NUMBER,
        "Appia_Search_PersonInRoom_Limit" to SettingColumnType.NUMBER,
        "Appia_Search_Room_Limit" to SettingColumnType.NUMBER,
        "Appia_Show_External_Partners" to SettingColumnType.BOOLEAN,
        "Appia_Meeting_Enable" to SettingColumnType.BOOLEAN,
        "Appia_Custom_Emoji_Map" to SettingColumnType.STRING,
        "Appia_Editor_Emoji_List" to SettingColumnType.STRING,
        "Appia_Hrm_Leader_Setting" to SettingColumnType.STRING,
        "Appia_Mail_Channel_Enabled" to SettingColumnType.BOOLEAN,
        "Appia_Claw_Agent_Visibility" to SettingColumnType.STRING,
    )

    /** RN getRegisteredPublicSettingIds：注册表键序（分批同步的请求顺序）。 */
    val ids: List<String> = columnOf.keys.toList()

    /** RN hideSystemMessagesExpansion（旧版 parseSettings 同款）：mute_unmute 一拆二。 */
    private fun expandHideSystemMessages(raw: JsonElement?): String {
        val arr = raw as? JsonArray ?: return "[]"
        return JsonArray(
            buildList {
                for (el in arr) {
                    if (el is JsonPrimitive && el.content == "mute_unmute") {
                        add(JsonPrimitive("user-muted"))
                        add(JsonPrimitive("user-unmuted"))
                    } else {
                        add(el)
                    }
                }
            },
        ).toString()
    }

    private fun asBoolean(v: JsonElement?): Boolean = when (v) {
        null, is JsonNull -> false
        is JsonPrimitive -> v.booleanOrNull
            ?: v.content.equals("true", ignoreCase = true)
            || (v.doubleOrNull?.let { it != 0.0 } == true)
        else -> false
    }

    private fun asString(v: JsonElement?): String = when (v) {
        null, is JsonNull -> ""
        is JsonPrimitive -> v.content
        else -> v.toString()
    }

    private fun asNumber(v: JsonElement?): Double = when (v) {
        null, is JsonNull -> 0.0
        is JsonPrimitive -> v.doubleOrNull
            ?: v.content.toDoubleOrNull()
            ?: if (v.booleanOrNull == true) 1.0 else 0.0
        else -> 0.0
    }

    /**
     * RN preparePublicSettingFromApi：`{_id, value}` → 可 upsert 的 [SettingEntity]。
     * 未注册 id → null（保守丢弃）；整行 REPLACE 即 RN 的「清三列再写目标列」语义。
     * `_updated_at` 存 ms epoch（RN settingUpdatedAt = Date.now()）。
     */
    fun prepareSettingEntity(id: String, value: JsonElement?): SettingEntity? {
        return when (columnOf[id]) {
            SettingColumnType.BOOLEAN -> SettingEntity(
                _id = id,
                value_as_boolean = asBoolean(value),
                _updated_at = System.currentTimeMillis().toDouble(),
            )
            SettingColumnType.STRING -> SettingEntity(
                _id = id,
                value_as_string = asString(value),
                _updated_at = System.currentTimeMillis().toDouble(),
            )
            SettingColumnType.NUMBER -> SettingEntity(
                _id = id,
                value_as_number = asNumber(value),
                _updated_at = System.currentTimeMillis().toDouble(),
            )
            SettingColumnType.ARRAY -> SettingEntity(
                _id = id,
                value_as_string = if (id == "Hide_System_Messages") {
                    expandHideSystemMessages(value)
                } else {
                    (value as? JsonArray ?: JsonArray(emptyList())).toString()
                },
                _updated_at = System.currentTimeMillis().toDouble(),
            )
            null -> null
        }
    }
}

/**
 * RN usePublicSettingBoolean(id, default) 等价：读 `settings` 行的布尔列，缺行/缺列回 [default]
 * （RN rows.length === 0 或 valueAsBoolean 为空时保留 defaultValue）。
 */
suspend fun SettingDao.publicSettingBoolean(id: String, default: Boolean = false): Boolean =
    getById(id)?.value_as_boolean ?: default

/**
 * RN usePublicSettingBoolean hook 等价的 Compose 消费形态（M5-T4 首消费方定形态——T1 报告留的口子）：
 * observeById 表读 + collectAsState，缺行/布尔列 null 回 [default]。UI 响应式（行变化即重组）；
 * 一次性读用 [publicSettingBoolean]。
 */
@Composable
fun rememberPublicSettingBoolean(dao: SettingDao, id: String, default: Boolean = false): Boolean {
    val row by remember(dao, id) { dao.observeById(id).map { it?.value_as_boolean } }
        .collectAsState(initial = default)
    return row ?: default
}
