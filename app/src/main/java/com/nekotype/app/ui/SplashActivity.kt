package com.nekotype.app.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.nekotype.app.R

/**
 * 启动页：图标弹性弹出 + 标题淡入，1秒后自动进入主界面。
 * 纯原生属性动画实现，零第三方依赖，兼容 Android 8.0+。
 * 所有动画操作均有 try-catch 保护，确保任何异常都不会卡住启动。
 */
class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_splash)
            startAnimations()
        } catch (e: Throwable) {
            // 布局或动画异常时直接跳转，不卡住
            goHome()
            return
        }
        // 1秒后跳转主界面（动画播完即走，不浪费时间）
        handler.postDelayed({ goHome() }, 1000)
    }

    private fun startAnimations() {
        val icon = findViewById<View>(R.id.ivSplashIcon)
        val title = findViewById<View>(R.id.tvSplashTitle)
        val subtitle = findViewById<View>(R.id.tvSplashSubtitle)
        val version = findViewById<View>(R.id.tvSplashVersion)

        // 图标圆形裁剪（API 21+，视图布局完成后生效）
        try {
            icon.clipToOutline = true
            icon.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    val size = minOf(view.width, view.height)
                    if (size <= 0) return
                    val left = (view.width - size) / 2
                    val top = (view.height - size) / 2
                    outline.setOval(left, top, left + size, top + size)
                }
            }
        } catch (_: Throwable) { }

        // 图标：弹性缩放弹出
        try {
            icon.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(600)
                .setInterpolator(OvershootInterpolator(1.2f))
                .start()
        } catch (_: Throwable) {
            icon.alpha = 1f; icon.scaleX = 1f; icon.scaleY = 1f
        }

        // 标题：延迟淡入 + 上移
        try {
            title.animate()
                .alpha(1f).translationY(0f)
                .setStartDelay(300).setDuration(400)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } catch (_: Throwable) { title.alpha = 1f; title.translationY = 0f }

        // 副标题：延迟淡入
        try {
            subtitle.animate()
                .alpha(1f).translationY(0f)
                .setStartDelay(500).setDuration(400)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } catch (_: Throwable) { subtitle.alpha = 1f; subtitle.translationY = 0f }

        // 版本号：延迟淡入
        try {
            version.animate()
                .alpha(1f)
                .setStartDelay(700).setDuration(300)
                .start()
        } catch (_: Throwable) { version.alpha = 1f }
    }

    private fun goHome() {
        if (finished) return
        finished = true
        try {
            startActivity(Intent(this, HomeActivity::class.java))
            overridePendingTransition(R.anim.activity_enter, R.anim.activity_exit)
        } catch (_: Throwable) { }
        finish()
    }

    @Suppress("MissingSuperCall") // 启动页故意禁止返回，不调用 super
    override fun onBackPressed() {
        // 启动页禁止返回
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
