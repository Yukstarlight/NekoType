package com.nekotype.app.util

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nekotype.app.R
import com.nekotype.app.prefs.AppPrefs

/**
 * 启动次数里程碑 · 赞助提醒。
 *
 * 达到指定启动次数时弹出赞助码（与「设置 → 支持与反馈」里同一张图 sponsor_qr），
 * 两个按钮：「下次一定」「已赞助」。
 *
 * 点哪个都不记录「永久关闭」——只把指针推进到下一个里程碑，
 * 所以到下一次里程碑时照常再弹，跟上次点了哪个按钮无关。
 */
object SponsorDialog {

    /**
     * 里程碑（启动次数）：第 10 次开始，
     * 之后 50/100/200/300/500/1000，再每 1000 一次直到 10000。
     */
    val MILESTONES = listOf(
        10, 50, 100, 200, 300, 500,
        1000, 2000, 3000, 4000, 5000, 6000, 7000, 8000, 9000, 10000
    )

    /**
     * 启动时判断是否需要弹出。
     * 弹出前先把指针推进到下一个里程碑，避免同一次启动重复弹、
     * 也保证「上次点了哪个按钮」不影响下一次里程碑。
     */
    fun maybeShow(ctx: Context) {
        try {
            val count = AppPrefs.launchCount
            val next = AppPrefs.nextSponsorMilestone
            if (count <= 0 || count < next) return
            AppPrefs.nextSponsorMilestone = MILESTONES.firstOrNull { it > count } ?: (count + 1)
            show(ctx, count)
        } catch (_: Throwable) { /* 提醒失败不影响正常使用 */ }
    }

    private fun show(ctx: Context, count: Int) {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 24), dp(ctx, 20), dp(ctx, 24), 0)
        }
        root.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.sp_title, count)
            textSize = 17f
            gravity = Gravity.CENTER_HORIZONTAL
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.sp_hint)
            textSize = 13f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(ctx, 8), 0, 0)
        })
        root.addView(ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 150), dp(ctx, 150)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(ctx, 14)
            }
            setImageResource(R.drawable.sponsor_qr)
            setBackgroundResource(R.drawable.bg_qr_frame)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8))
            contentDescription = ctx.getString(R.string.sp_hint)
        })
        MaterialAlertDialogBuilder(ctx)
            .setView(root)
            .setPositiveButton(ctx.getString(R.string.sp_sponsored)) { _, _ ->
                NekoLog.adjust("赞助提醒(第 $count 次启动)：已赞助")
            }
            .setNegativeButton(ctx.getString(R.string.sp_later)) { _, _ ->
                NekoLog.adjust("赞助提醒(第 $count 次启动)：下次一定")
            }
            .show()
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
