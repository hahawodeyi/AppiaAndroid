package cn.appia.im.feature.labor

import cn.appia.im.core.network.api.WorktableItem

/**
 * 劳动入口点击三支路由（RN screens/LaborScreen/openLaborItem.ts:27-95 移植）：
 * 1. type===3（私信员工服务）→ extra.name → openDirectMessage 链（M4 resolveDirectChatRid）
 *    → RoomRoute('d')；缺名/失败 Alert 不导航。
 * 2. name==='消息待办' || type===10 → TodoList。
 * 3. 其余 → InAppWeb：url 空 → `{host}/appia_fe/nonsupport`；考勤打卡/企业滴滴
 *    先询定位权限（拒绝 Alert 不打开——权限申请本身由屏侧 launcher 承担，见 LaborScreen）。
 *
 * 拆成纯判定 + 纯 DM 链两步（导航/Alert 回调由屏侧装配），与 RN LaborNavigationApi 注入同构。
 */

/** RN NONSUPPORT_PATH（openLaborItem.ts:22）。 */
private const val NONSUPPORT_PATH = "/appia_fe/nonsupport"

/** openLaborItem.ts:39-91 三支判定结果。 */
sealed interface LaborAction {
    /** type 3：私信员工服务（DM 建房进房）。 */
    data object DirectMessage : LaborAction

    /** 消息待办 → 全量待办列表。 */
    data object TodoList : LaborAction

    /** 其余 → 应用内 WebView。 */
    data class Web(
        val url: String,
        val title: String,
        val needAuth: Boolean,
        val source: String?,
        val needVPN: Boolean,
        /** true = 打开 WebView 前需先取得精确定位权限（考勤打卡/企业滴滴）。 */
        val needsLocation: Boolean,
    ) : LaborAction
}

/** RN laborItemNeedsLocationPermission（考勤打卡/企业滴滴两名称命中）。 */
fun laborItemNeedsLocationPermission(name: String): Boolean =
    name == LABOR_ITEM_NAME_ENTERPRISE_DIDI || name == LABOR_ITEM_NAME_ATTENDANCE_CHECKIN

/** openLaborItem.ts:39-91 三支判定（DM 的 username 校验在 [openLaborDirectMessage]）。 */
fun resolveLaborAction(item: WorktableItem, serverUrl: String): LaborAction =
    when {
        item.type == LABOR_ITEM_TYPE_DIRECT_SERVICE -> LaborAction.DirectMessage
        item.name == LABOR_ITEM_NAME_MESSAGE_TODO || item.type == LABOR_ITEM_TYPE_MESSAGE_TODO ->
            LaborAction.TodoList
        else -> LaborAction.Web(
            url = item.url.trim().ifEmpty { "${serverUrl.trimEnd('/')}$NONSUPPORT_PATH" },
            title = item.name,
            needAuth = item.needAuth,
            source = item.extra?.source,
            needVPN = item.extra?.needVPN == true,
            needsLocation = laborItemNeedsLocationPermission(item.name),
        )
    }

/** RN openLaborItem.ts:39-62 DM 结果（缺名/无 rid/异常三失败态，由屏侧映射 Alert 文案）。 */
sealed interface LaborDmResult {
    data class Room(val rid: String) : LaborDmResult
    data object MissingUsername : LaborDmResult
    data object Failed : LaborDmResult
}

/**
 * RN openLaborItem.ts:40-62：extra.name 空白 → MissingUsername（Alert dmFailed+dmMissingUsername）；
 * [resolve] = M4 openDirectMessage 链（resolveDirectChatRid：knownRid → 本地 chats → im.create）；
 * null（链内已 catch 全异常）→ Failed（Alert dmFailed+dmFailed）。
 */
suspend fun openLaborDirectMessage(
    item: WorktableItem,
    resolve: suspend (username: String) -> String?,
): LaborDmResult {
    val username = item.extra?.name?.trim().orEmpty()
    if (username.isEmpty()) return LaborDmResult.MissingUsername
    val rid = resolve(username) ?: return LaborDmResult.Failed
    return LaborDmResult.Room(rid)
}
