package cn.appia.im.core.push

import android.content.Context
import android.util.Log
import com.alibaba.sdk.android.push.MessageReceiver
import com.alibaba.sdk.android.push.notification.CPushMessage

/**
 * 阿里云推送回调接收器（SDK MessageReceiver 直连，无 RN 桥）。
 *
 * 点击动作（opened / clickedWithNoAction，RN pushService.ts:130-132 同径）→ PushClickRouter：
 * 解析 extra 的 ejson → host 校验 → 90s 待导航队列（T5 drain 进房）。
 * 到达（onNotification / receivedInApp）→ CrossOrgUnreadRefresh：跨组织未读刷新
 * （backlog #9，RN pushService.ts:127/:137；sink 缝未注册前仅日志）。移除仍仅日志；
 * onMessage 透传分流归 M10（CPushMessage 无 extraMap，不挂刷新）。
 */
class AppiaAliyunPushReceiver : MessageReceiver() {
    companion object {
        private const val TAG = "push:payload"
    }

    override fun onNotification(context: Context, title: String, summary: String, extraMap: Map<String, String>) {
        Log.i(TAG, "onNotification title=$title summary=$summary extras=$extraMap")
        CrossOrgUnreadRefresh.routeFromExtraMap(extraMap)
    }

    override fun onNotificationOpened(context: Context, title: String, summary: String, extraMap: String) {
        Log.i(TAG, "onNotificationOpened title=$title summary=$summary extra=$extraMap")
        PushClickRouter.shared.onNotificationOpened(title, summary, extraMap)
    }

    override fun onNotificationRemoved(context: Context, messageId: String) {
        Log.i(TAG, "onNotificationRemoved messageId=$messageId")
    }

    override fun onMessage(context: Context, message: CPushMessage) {
        Log.i(TAG, "onMessage messageId=${message.messageId} title=${message.title}")
        // TODO(M10): 透传消息分流（同 RN pushService onMessage）
    }

    override fun onNotificationClickedWithNoAction(context: Context, title: String, summary: String, extraMap: String) {
        Log.i(TAG, "onNotificationClickedWithNoAction title=$title extra=$extraMap")
        // RN pushService.ts:130-132 与 opened 同径 → 同一路由
        PushClickRouter.shared.onNotificationOpened(title, summary, extraMap)
    }

    override fun onNotificationReceivedInApp(
        context: Context,
        title: String,
        summary: String,
        extraMap: Map<String, String>,
        openType: Int,
        openActivity: String,
        openUrl: String,
    ) {
        Log.i(TAG, "onNotificationReceivedInApp title=$title openType=$openType")
        CrossOrgUnreadRefresh.routeFromExtraMap(extraMap) // RN pushService.ts:137 前台到达同刷新
    }
}
