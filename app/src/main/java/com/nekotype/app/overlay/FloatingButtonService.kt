package com.nekotype.app.overlay

import android.app.AlarmManager
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
                // Android 12+ 后台启动前台服务受限时降级为普通启动（服务已在前台时也能生效）
                try {
                    context.startService(Intent(context, FloatingButtonService::class.java))
                } catch (_: Throwable) { /* 系统级限制（如华为应用启动管理），App 无法绕过 */ }
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

        /** 取消心跳闹钟（关闭心跳保活开关时调用） */
        fun cancelHeartbeatGlobal(context: Context) {
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pi = PendingIntent.getService(
                    context, 1001,
                    Intent(context, FloatingButtonService::class.java).apply {
                        action = "${context.packageName}.action.HEARTBEAT"
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.cancel(pi)
            } catch (_: Throwable) { }
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
        // 心跳保活（皆成同款，用户开关控制）：每 60 秒闹钟唤醒检查，服务被系统杀了也能自动拉活
        startHeartbeat()
        NekoLog.ok("悬浮服务已启动，按钮常驻屏幕边缘")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 防篡改：签名被改 / Hook 框架 → 拒绝运行
        if (AppPrefs.tampered) {
            AppPrefs.serviceEnabled = false
            NekoLog.warn("检测到篡改，悬浮服务拒绝运行")
            stopSelf()
            return START_NOT_STICKY
        }
        // 心跳唤醒：确认服务还活着，按钮显示（若因被系统清理而重建）
        if (intent?.action == ACTION_HEARTBEAT) {
            if (AppPrefs.heartbeatEnabled && AppPrefs.serviceEnabled) {
                showButton()
                scheduleNextHeartbeat()
            } else {
                stopSelf()
                return START_NOT_STICKY
            }
            return START_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            // 密码锁定：通知栏停止入口也需验证，拉起主界面弹密码框
            if (AppPrefs.lockEnabled) {
                NekoLog.warn("密码锁定：停止服务需在应用内验证密码")
                try {
                    val i = Intent(this, com.nekotype.app.ui.MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra(com.nekotype.app.ui.MainActivity.EXTRA_STOP_REQUEST, true)
                    }
                    startActivity(i)
                } catch (_: Throwable) { }
                return START_STICKY
            }
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

    /**
     * 防杀后台：任务被从最近任务划掉时，若开启了密码锁定或隐藏模式则延时重启服务，
     * 配合隐藏模式让 NekoType 划掉后自动复活（部分 ROM 限制立即重启，延时 1.5s；
     * 用前台服务拉起，Android 12 对 FGS 的后台启动豁免更多）。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (AppPrefs.lockEnabled || AppPrefs.hiddenModeEnabled) {
            NekoLog.ok("检测到任务被划掉，防杀后台：延时自动重启服务")
            android.os.Handler(mainLooper).postDelayed({
                try {
                    val i = Intent(this, FloatingButtonService::class.java)
                    i.action = ACTION_RESTART
                    if (Build.VERSION.SDK_INT >= 26) {
                        startForegroundService(i)
                    } else {
                        startService(i)
                    }
                } catch (_: Throwable) {
                    try {
                        startService(Intent(this, FloatingButtonService::class.java).apply { action = ACTION_RESTART })
                    } catch (_: Throwable) { }
                }
            }, 1500)
        }
    }

    override fun onDestroy() {
        cancelHeartbeat()
        hideButton()
        instance = null
        super.onDestroy()
    }

    // 注意：不能在属性初始化时用 packageName（服务构造期间 Context 尚未挂载会 NPE），
    // 必须惰性取值
    private val ACTION_STOP by lazy { "$packageName.action.STOP_FLOATING" }
    private val ACTION_RESTART by lazy { "$packageName.action.RESTART_FLOATING" }
    private val ACTION_HEARTBEAT by lazy { "$packageName.action.HEARTBEAT" }

    // ---------- 心跳保活（皆成同款，用户开关控制） ----------

    private val HEARTBEAT_INTERVAL_MS = 60_000L

    private fun startHeartbeat() {
        if (AppPrefs.heartbeatEnabled && AppPrefs.serviceEnabled) {
            scheduleNextHeartbeat()
        }
    }

    private fun scheduleNextHeartbeat() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            // 用 getForegroundService：Android 12 允许精确闹钟触发前台服务启动（豁免场景），
            // 华为等 ROM 拦截后台启动的概率更低
            val pi = if (Build.VERSION.SDK_INT >= 26) {
                PendingIntent.getForegroundService(
                    this, 1001,
                    Intent(this, FloatingButtonService::class.java).apply { action = ACTION_HEARTBEAT },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getService(
                    this, 1001,
                    Intent(this, FloatingButtonService::class.java).apply { action = ACTION_HEARTBEAT },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            // 精确闹钟 + 允许待机唤醒（省电模式下也能触发）
            am.setExactAndAllowWhileIdle(AlarmManager.RTC, System.currentTimeMillis() + HEARTBEAT_INTERVAL_MS, pi)
        } catch (_: Throwable) {
            // 部分 ROM 禁精确闹钟 → 回退普通闹钟
            try {
                val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pi = PendingIntent.getService(
                    this, 1001,
                    Intent(this, FloatingButtonService::class.java).apply { action = ACTION_HEARTBEAT },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.set(AlarmManager.RTC, System.currentTimeMillis() + HEARTBEAT_INTERVAL_MS, pi)
            } catch (_: Throwable) { }
        }
    }

    private fun cancelHeartbeat() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getService(
                this, 1001,
                Intent(this, FloatingButtonService::class.java).apply { action = ACTION_HEARTBEAT },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
        } catch (_: Throwable) { }
    }

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
        // 停止按钮直接拉起 MainActivity（点通知启动 Activity 有后台豁免权；
        // 不能从服务 startActivity —— Android 12 会拦截后台启动）。
        // 密码锁定开启时在 App 内验证后才真正停止。
        val stopIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(MainActivity.EXTRA_STOP_REQUEST, true)
        }
        val stopPi = PendingIntent.getActivity(
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
        // 强制篡改模式与悬浮按钮互斥：强制篡改开启时不显示悬浮球（服务 + 通知保留）
        if (AppPrefs.forceKeyboardEnabled) {
            hideButton()
            return
        }
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
