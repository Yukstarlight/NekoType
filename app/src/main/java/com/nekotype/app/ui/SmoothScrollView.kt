package com.nekotype.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.core.widget.NestedScrollView
import com.nekotype.app.prefs.AppPrefs
import kotlin.math.abs
import kotlin.math.min

/**
 * 伪流畅滚动：把系统 fling 换成缓出曲线（比原生 spline 更"顺"），
 * 停下后若离卡片顶端很近就轻微吸附对齐。
 */
class SmoothScrollView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : NestedScrollView(ctx, attrs, defStyleAttr) {

    private var anim: ValueAnimator? = null
    private val ease = DecelerateInterpolator(2.2f)

    private fun stopEase() {
        anim?.cancel()
        anim = null
    }

    override fun fling(velocityY: Int) {
        if (!AppPrefs.smoothScroll || abs(velocityY) < 400) {
            super.fling(velocityY)
            return
        }
        val target = (scrollY + (velocityY * 0.30f).toInt()).coerceIn(0, maxScrollY())
        val dist = target - scrollY
        if (dist == 0) {
            super.fling(velocityY)
            return
        }
        val start = scrollY
        val dur = (170 + min(300, abs(velocityY) / 55)).toLong()
        stopEase()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = dur
            interpolator = ease
            addUpdateListener { a -> scrollTo(0, start + (dist * (a.animatedValue as Float)).toInt()) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    snapNearest()
                }
            })
            start()
        }
    }

    private fun snapNearest() {
        val content = getChildAt(0) as? ViewGroup ?: return
        val cur = scrollY
        var best = cur
        var bestD = Int.MAX_VALUE
        for (i in 0 until content.childCount) {
            val c = content.getChildAt(i)
            if (c.visibility != View.VISIBLE) continue
            val d = abs(c.top - cur)
            if (d < bestD) {
                bestD = d
                best = c.top
            }
        }
        val thr = (resources.displayMetrics.density * 72).toInt()
        if (best != cur && bestD <= thr) animateTo(best, 170)
    }

    private fun animateTo(y: Int, dur: Long) {
        val start = scrollY
        if (start == y) return
        stopEase()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = dur
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { a ->
                scrollTo(0, start + ((y - start) * (a.animatedValue as Float)).toInt())
            }
            start()
        }
    }

    private fun maxScrollY(): Int {
        val child = getChildAt(0) ?: return 0
        val viewport = height - paddingTop - paddingBottom
        return (child.height - viewport).coerceAtLeast(0)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) stopEase()
        return super.onInterceptTouchEvent(ev)
    }
}
