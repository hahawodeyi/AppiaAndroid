package com.wix.reactnativenotifications.ali

import android.util.Log
import com.alibaba.sdk.android.push.AndroidPopupActivity

/**
 * EMAS 厂商通道辅助弹窗 Activity。
 *
 * 必须继承 [AndroidPopupActivity]（普通 Activity 即使类名匹配也会被 SDK 判定非法，
 * 表现为 `104_2162 activity not found`）。离线设备经厂商通道下发时 SDK 以本类为
 * PendingIntent 着陆页构造通知。
 *
 * 外部核对项：类全限定名（com.wix.reactnativenotifications.ali.PopupPushActivity）
 * 须与 EMAS 控制台「辅助弹窗 activity」配置精确一致——沿用 RN 现网同包同类名。
 */
class PopupPushActivity : AndroidPopupActivity() {
    companion object {
        private const val TAG = "push:popup"
    }

    override fun onSysNoticeOpened(title: String?, summary: String?, extMap: Map<String, String>?) {
        Log.i(TAG, "onSysNoticeOpened title=$title summary=$summary extras=$extMap")
        // TODO(T5): 如需厂商通道点击进房，在此解析 extMap 并路由（RN 亦未在此进房，仅处理 androidOpenUrl）
    }
}
