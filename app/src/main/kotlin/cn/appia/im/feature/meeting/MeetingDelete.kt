package cn.appia.im.feature.meeting

import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.api.MeetingApi
import cn.appia.im.core.network.api.RoomAnnouncementData
import cn.appia.im.core.network.api.RoomSettingsApi
import cn.appia.im.core.network.api.SaveRoomSettingsParams

/** RN MeetingDeleteScope（promptMeetingDelete.ts:6）：single=周期单次 / series=周期整串 / normal=普通。 */
enum class MeetingDeleteScope { SINGLE, SERIES, NORMAL }

/**
 * RN executeMeetingDelete（executeMeetingDelete.ts:7-19）：周期单次走
 * [MeetingApi.cancelPeriodicSingleMeeting]；普通/整串走
 * [RoomSettingsApi.postSaveRoomSettings] `roomAnnouncementData {_id, type:'delete'}`。
 * 失败异常上抛（调用方 toast meeting_cancelFailed）。
 */
suspend fun executeMeetingDelete(
    sdk: RocketSdk,
    rid: String,
    announcementId: String,
    scope: MeetingDeleteScope,
) {
    if (scope == MeetingDeleteScope.SINGLE) {
        MeetingApi.cancelPeriodicSingleMeeting(sdk, rid, announcementId)
        return
    }
    RoomSettingsApi.postSaveRoomSettings(
        sdk, rid,
        SaveRoomSettingsParams(
            roomAnnouncementData = RoomAnnouncementData(id = announcementId, type = "delete"),
        ),
    )
}
