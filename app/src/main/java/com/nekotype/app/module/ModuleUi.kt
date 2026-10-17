package com.nekotype.app.module

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView

/** 模块界面辅助：把模块注册的卡片注入设置页、统一卡片外观 */
object ModuleUi {

    private const val CARD_TAG = "nekotype_module_card"

    fun injectSettingsCards(ctx: Context, container: LinearLayout) {
        val stale = (0 until container.childCount).map { container.getChildAt(it) }
            .filter { it.tag == CARD_TAG }
        stale.forEach { container.removeView(it) }
        for (c in ModuleHooks.settingsCards()) {
            try {
                container.addView(buildCard(ctx, c.title, c.builder(ctx)).also { it.tag = CARD_TAG })
            } catch (_: Throwable) {
            }
        }
    }

    fun buildCard(ctx: Context, title: String, content: View): View {
        val card = MaterialCardView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 14) }
            radius = dp(ctx, 20).toFloat()
            cardElevation = dp(ctx, 1).toFloat()
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
        }
        box.addView(TextView(ctx).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
        })
        box.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(ctx, 8) })
        card.addView(box)
        return card
    }

    /** 按模块的 hideFeature 声明隐藏/恢复视图（模块卸载后会恢复显示） */
    fun applyHidden(view: View?, key: String) {
        if (view == null) return
        try {
            view.visibility = if (ModuleHooks.isFeatureHidden(key)) View.GONE else View.VISIBLE
        } catch (_: Throwable) {
        }
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
