package cn.appia.im.core.meeting.floatwin

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import cn.appia.im.R

/**
 * TYPE_APPLICATION_OVERLAY 顶部会议横条（RN MeetingFloatWindow.java verbatim 移植）：
 * gravity TOP|CENTER_HORIZONTAL、y=状态栏+16dp、宽=屏宽-16dp、cutout SHORT_EDGES、
 * NOT_FOCUSABLE|NOT_TOUCH_MODAL、透明格式、400ms 入场动画、显示 title+duration、
 * 点击 → 移回前台（RN :82 moveAppToFront）。
 * **偏离注记**：RN 布局里 time/voice_icon 两个隐藏视图不再生成（服务本就 GONE 它们）；
 * 单例 getInstance/updateContent 语义 verbatim。
 */
class MeetingFloatWindow private constructor(
    context: Context,
    private var title: String,
    private var duration: String,
) {

    private val appContext: Context = context.applicationContext
    private var layoutParams: WindowManager.LayoutParams? = null
    private var inflate: View? = null
    private var windowManager: WindowManager? = null
    private var added = false
    private val handler = Handler(Looper.getMainLooper())

    private fun create() {
        windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val view = View.inflate(appContext, R.layout.meeting_float_window, null)
        inflate = view
        view.setOnClickListener { moveAppToFront(appContext) }
        view.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                enterAnimator()
            }
        })
        updateContent(title, duration)
    }

    private fun updateContent(nextTitle: String, nextDuration: String) {
        title = nextTitle
        duration = nextDuration
        val view = inflate ?: return
        view.findViewById<TextView>(R.id.m_title)?.text = nextTitle
        view.findViewById<TextView>(R.id.content)?.text = nextDuration
    }

    private fun enterAnimator() {
        val view = inflate ?: return
        val alpha = ObjectAnimator.ofFloat(view, "alpha", 0f, 1f)
        val translationY = ObjectAnimator.ofFloat(view, "translationY", -view.height.toFloat(), 0f)
        val set = AnimatorSet()
        set.duration = 400
        set.playTogether(alpha, translationY)
        set.interpolator = AccelerateDecelerateInterpolator()
        set.start()
    }

    @Synchronized
    fun show() {
        val view = inflate ?: error("FloatView can not be null")
        if (added) {
            view.visibility = View.VISIBLE
            return
        }
        layoutParams = floatLayoutParam(appContext).apply { gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP }
        view.visibility = View.VISIBLE
        try {
            windowManager?.addView(view, layoutParams)
            added = true
        } catch (e: Exception) {
            Log.e(TAG, "add overlay failed", e)
        }
    }

    fun remove() {
        val view = inflate
        if (view != null && windowManager != null) {
            if (view.isAttachedToWindow) {
                windowManager?.removeView(view)
                instance = null
            }
            added = false
        }
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        private const val TAG = "MeetingFloatWindow"

        @Volatile
        private var instance: MeetingFloatWindow? = null

        /** RN getInstance 同款：已存在则只 updateContent（标题/时长热更，不重建窗口）。 */
        fun getInstance(context: Context, title: String, duration: String): MeetingFloatWindow {
            val existing = instance
            if (existing != null) {
                existing.updateContent(title, duration)
                return existing
            }
            synchronized(MeetingFloatWindow::class) {
                val again = instance
                if (again == null) {
                    instance = MeetingFloatWindow(context.applicationContext, title, duration).also { it.create() }
                } else {
                    again.updateContent(title, duration)
                }
                return instance!!
            }
        }

        /** RN getStatusBarHeight 同款（InternalInsetResource/DiscouragedApi 同 RN @SuppressLint）。 */
        fun getStatusBarHeight(context: Context): Int {
            @SuppressLint("InternalInsetResource", "DiscouragedApi")
            val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resourceId != 0) {
                return context.resources.getDimensionPixelSize(resourceId)
            }
            return -1
        }

        fun dp2px(dpValue: Float): Int {
            val scale = android.content.res.Resources.getSystem().displayMetrics.density
            return (dpValue * scale + 0.5f).toInt()
        }

        /**
         * RN getFloatLayoutParam verbatim（:168-188）：TYPE_APPLICATION_OVERLAY（O+，P+ 起含
         * cutout SHORT_EDGES）、HARDWARE_ACCELERATED、NOT_FOCUSABLE|NOT_TOUCH_MODAL、
         * FULLSCREEN|LAYOUT_INSET_DECOR|LAYOUT_IN_SCREEN、宽=屏宽-16dp、y=状态栏+16dp、透明。
         */
        private fun floatLayoutParam(context: Context): WindowManager.LayoutParams {
            val params = WindowManager.LayoutParams()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    params.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            } else {
                @Suppress("DEPRECATION")
                params.type = WindowManager.LayoutParams.TYPE_PHONE
            }
            params.flags = params.flags or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            params.width = context.resources.displayMetrics.widthPixels - dp2px(16f)
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            params.y = getStatusBarHeight(context) + dp2px(16f)
            params.format = PixelFormat.TRANSPARENT
            return params
        }
    }
}

/** RN ApplicationUtils.moveAppToFront 同义（Home 键退后台后点横条拉回任务栈）。 */
internal fun moveAppToFront(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
    if (launch != null) {
        launch.addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
    }
}
