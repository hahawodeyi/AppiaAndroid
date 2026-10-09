package cn.appia.im.core.push

import android.content.Context
import android.util.Log
import com.alibaba.sdk.android.push.MessageReceiver
import com.alibaba.sdk.android.push.notification.CPushMessage

/**
 * 阿里云推送回调接收器（SDK MessageReceiver 直连，无 RN 桥）。
 *
 * 当前仅接 SDK 默认通知展示 + 日志；点击进房 / ejson 解析 / 语音分流在 T5 接管：
 * onNotificationOpened → PushPayloadParser → PushClickRouter（待建）。
 */
class AppiaAliyunPushReceiver : MessageReceiver() {
    companion object {
        private const val TAG = "push:payload"
    }

    override fun onNotification(context: Context, title: String, summary: String, extraMap: Map<String, String>) {
        Log.i(TAG, "onNotification title=$title summary=$summary extras=$extraMap")
    }

    override fun onNotificationOpened(context: Context, title: String, summary: String, extraMap: String) {
        Log.i(TAG, "onNotificationOpened title=$title summary=$summary extra=$extraMap")
        // TODO(T5): 解析 extra 的 ejson → host 校验 → 进房导航队列
    }

    override fun onNotificationRemoved(context: Context, messageId: String) {
        Log.i(TAG, "onNotificationRemoved messageId=$messageId")
    }

    override fun onMessage(context: Context, message: CPushMessage) {
        Log.i(TAG, "onMessage messageId=${message.messageId} title=${message.title}")
        // TODO(T5): 透传消息分流（同 RN pushService onMessage）
    }

    override fun onNotificationClickedWithNoAction(context: Context, title: String, summary: String, extraMap: String) {
        Log.i(TAG, "onNotificationClickedWithNoAction title=$title extra=$extraMap")
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
    }
}
