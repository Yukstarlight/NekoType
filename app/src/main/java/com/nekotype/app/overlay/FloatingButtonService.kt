package com.nekotype.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.nekotype.app.R
import com.nekotype.app.accessibility.NekoTypeAccessibilityBridge
import com.nekotype.app.prefs.AppPrefs
import com.nekotype.app.ui.MainActivity
import com.nekotype.app.util.NekoLog
import kotlin.math.abs

/**
 * 悬浮按钮前台服务（永久常驻）。
 *
 * - 服务运行时按钮**一直显示**在屏幕边缘（默认右侧居中），与输入法无关；
 * - 单击 = 读取当前输入框 → 文本变换 → 自动发送；
 * - 按住拖动 = 自由摆放，松手记忆位置，可开启边缘吸附；
 * - 液态玻璃外观：渐变 + 半透明 + Android 12+ 背景模糊（毛玻璃）。
 */
class FloatingButtonService : Service() {

    companion object {
        const val CHANNEL_ID = "nekotype_fg"
        const val NOTIF_ID = 1001

        @Volatile private var instance: FloatingButtonService? = null

        fun start(context: Context) {
            try {
                val intent = Intent(context, FloatingButtonService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Throwable) {
                // Android 12+ 后台启动前台服务受限时静默降级：不会崩溃
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingButtonService::class.java))
        }

        /** 设置页调整大小/透明度后调用：立即重建悬浮按钮 */
        fun reload() {
            instance?.let {
                it.hideButton()
                it.button = null
                it.params = null
                it.showButton()
            }
        }

        /** 实时应用大小/透明度（不重建，避免滑块拖动时抖动） */
        fun applyStyle(sizeDp: Int, opacity: Int) {
            instance?.applyStyleInternal(sizeDp, opacity)
        }
    }

    private lateinit var wm: WindowManager
    private var button: ImageView? = null
    private var params: WindowManager.LayoutParams? = null
    private var buttonAdded = false

    // ---------- 收起成小圆点 ----------
    private var collapsed = false
    private var expandOnlyNextClick = false
    private val collapseDelay = 5000L
    private val collapseRunnable = Runnable { collapseToDot() }

    private fun scheduleCollapse() {
        val b = button ?: return
        if (!AppPrefs.fabCollapseEnabled) return
        b.removeCallbacks(collapseRunnable)
        b.postDelayed(collapseRunnable, collapseDelay)
    }

    private fun collapseToDot() {
        val b = button ?: return
        if (collapsed) return
        collapsed = true
        val p = params ?: return
        val old = p.width
        val dot = (28 * resources.displayMetrics.density).toInt()
        p.width = dot
        p.height = dot
        p.x += (old - dot) / 2
        p.y += (old - dot) / 2
        try { wm.updateViewLayout(b, p) } catch (_: Throwable) { }
    }

    private fun expandFromDot() {
        val b = button ?: return
        if (!collapsed) return
        collapsed = false
        val p = params ?: return
        val old = p.width
        val full = (AppPrefs.fabSizeDp * resources.displayMetrics.density).toInt()
        p.width = full
        p.height = full
        p.x -= (full - old) / 2
        p.y -= (full - old) / 2
        try { wm.updateViewLayout(b, p) } catch (_: Throwable) { }
    }

    /** 实时应用大小/透明度（不重建按钮，避免抖动） */
    private fun applyStyleInternal(sizeDp: Int, opacity: Int) {
        val b = button ?: return
        val p = params ?: return
        // 透明度实时生效
        b.alpha = opacity / 100f
        // 大小：收起状态不调整（展开时按最新大小）
        if (!collapsed) {
            val newSize = (sizeDp * resources.displayMetrics.density).toInt()
            if (p.width != newSize) {
                val old = p.width
                p.width = newSize
                p.height = newSize
                p.x += (old - newSize) / 2
                p.y += (old - newSize) / 2
                try { wm.updateViewLayout(b, p) } catch (_: Throwable) { }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        NekoLog.ok("悬浮服务已启动，按钮常驻屏幕边缘")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppPrefs.serviceEnabled = false
            NekoLog.info("服务已停止")
            stopSelf()
            return START_NOT_STICKY
        }
        // 永久显示：服务运行期间按钮常驻（无需输入法触发）
        showButton()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        hideButton()
        instance = null
        super.onDestroy()
    }

    // 注意：不能在属性初始化时用 packageName（服务构造期间 Context 尚未挂载会 NPE），
    // 必须惰性取值
    private val ACTION_STOP by lazy { "$packageName.action.STOP_FLOATING" }

    // ---------- 通知 ----------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID, "NekoType 悬浮按钮",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "保持 NekoType 悬浮按钮常驻" }
                mgr.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(): Notification {
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPi = PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, FloatingButtonService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NekoType 运行中")
            .setContentText("悬浮按钮常驻屏幕边缘，点击自动改写并发送")
            .setSmallIcon(R.drawable.ic_neko)
            .setContentIntent(openPi)
            .addAction(0, "停止服务", stopPi)
            .setOngoing(true)
            .build()
    }

    // ---------- 悬浮按钮 ----------

    private fun showButton() {
        collapsed = false
        if (button == null) {
            addButton()
        } else if (!buttonAdded) {
            try {
                wm.addView(button, params)
                buttonAdded = true
            } catch (_: Throwable) { /* 悬浮窗权限未授予 */ }
        }
        scheduleCollapse()
    }

    private fun hideButton() {
        button?.removeCallbacks(collapseRunnable)
        if (button != null && buttonAdded) {
            try {
                wm.removeView(button)
            } catch (_: Throwable) { }
            buttonAdded = false
        }
    }

    private fun addButton() {
        val density = resources.displayMetrics.density
        val size = (AppPrefs.fabSizeDp * density).toInt()
        val p = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (AppPrefs.buttonX >= 0 && AppPrefs.buttonY >= 0) {
                x = AppPrefs.buttonX
                y = AppPrefs.buttonY
            } else {
                // 默认位置：屏幕右侧垂直居中
                val out = android.graphics.Point()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getSize(out)
                x = out.x - size - 28
                y = out.y / 2 - size / 2
            }
            // 注意：不再使用 FLAG_BLUR_BEHIND / setBlurBehindRadius ——
            // 部分 ROM（如 MIUI/HyperOS）会因此把模糊层盖满整个屏幕（含系统设置），已移除
        }

        val btn = ImageView(this).apply {
            setImageResource(R.drawable.ctw_icon)
            scaleType = ImageView.ScaleType.CENTER_CROP
            // 按键反馈：柔光玻璃（可选）或渐变底 + 点击水波纹；图片按圆形裁剪
            background = getDrawable(
                if (AppPrefs.fabGlassEnabled) R.drawable.bg_fab_glass else R.drawable.bg_fab_ripple
            )
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            // 柔光玻璃：图标更透，让玻璃质感可见
            val glass = AppPrefs.fabGlassEnabled
            setPadding(6, 6, 6, 6)
            // 透明度（用户可调）
            alpha = AppPrefs.fabOpacity / 100f
            if (glass) setImageAlpha(204)
            setOnClickListener { onNekoButtonClick() }
            setOnTouchListener(DragTouchListener(p))
            contentDescription = "NekoType 变换发送按钮"
        }
        params = p
        button = btn
        try {
            wm.addView(btn, p)
            buttonAdded = true
        } catch (_: Throwable) {
            // 悬浮窗权限未授予：按钮暂不显示
        }
    }

    private inner class DragTouchListener(private val p: WindowManager.LayoutParams) :
        View.OnTouchListener {

        private val slop = ViewConfiguration.get(this@FloatingButtonService).scaledTouchSlop
        // 以按下点为原点计算累计位移，避免"慢拖被误判为点击"或"轻点被误判为拖拽"
        private var originX = 0f
        private var originY = 0f
        private var originParamX = 0
        private var originParamY = 0
        private var moved = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    originX = event.rawX
                    originY = event.rawY
                    originParamX = p.x
                    originParamY = p.y
                    moved = false
                    // 收起状态下点击 = 先展开，本次不触发变换
                    if (collapsed) {
                        expandOnlyNextClick = true
                        expandFromDot()
                    } else {
                        expandOnlyNextClick = false
                    }
                    v.removeCallbacks(collapseRunnable)
                    // 按压反馈：按下变暗
                    v.alpha = 0.8f
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val totalDx = event.rawX - originX
                    val totalDy = event.rawY - originY
                    if (!moved && (abs(totalDx) > slop || abs(totalDy) > slop)) moved = true
                    if (moved) {
                        p.x = originParamX + totalDx.toInt()
                        p.y = originParamY + totalDy.toInt()
                        try { wm.updateViewLayout(v, p) } catch (_: Throwable) { }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    v.alpha = 1f
                    if (!moved) {
                        v.performClick()
                        return true
                    }
                    if (AppPrefs.snapEdges) snapToEdge(v)
                    AppPrefs.buttonX = p.x
                    AppPrefs.buttonY = p.y
                    scheduleCollapse()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                    scheduleCollapse()
                    return true
                }
            }
            return false
        }

        private fun snapToEdge(v: View) {
            try {
                val out = android.graphics.Point()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getSize(out)
                val leftDist = p.x
                val rightDist = out.x - p.x - v.width
                p.x = if (leftDist < rightDist) 0 else out.x - v.width
            } catch (_: Throwable) { }
        }
    }

    // ---------- 核心：变换 + 发送 ----------

    private fun onNekoButtonClick() {
        // 收起状态下点击 = 只展开，不触发变换
        if (expandOnlyNextClick) {
            expandOnlyNextClick = false
            scheduleCollapse()
            return
        }
        val b = button
        if (AppPrefs.hapticEnabled && b != null) {
            try { b.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) } catch (_: Throwable) { }
        }
        when {
            !NekoTypeAccessibilityBridge.isServiceReady() -> {
                NekoLog.warn("点击按钮：无障碍服务未开启")
                Toast.makeText(this, "请先开启无障碍服务（App 权限引导第 1 步）", Toast.LENGTH_SHORT).show()
            }
            !NekoTypeAccessibilityBridge.hasActiveNode() -> {
                NekoLog.warn("点击按钮：未检测到输入框焦点")
                Toast.makeText(this, "请先点一下输入框，再点本按钮", Toast.LENGTH_SHORT).show()
            }
            else -> {
                // 委托给无障碍服务桥接层：读取当前输入框 → 变换 → 写入 → 发送
                NekoTypeAccessibilityBridge.requestTransformAndSend()
                Toast.makeText(this, "✓ 已变换并发送", Toast.LENGTH_SHORT).show()
            }
        }
        scheduleCollapse()
    }
}
