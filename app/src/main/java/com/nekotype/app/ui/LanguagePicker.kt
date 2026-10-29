package com.nekotype.app.ui

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.nekotype.app.R
import com.nekotype.app.prefs.AppPrefs
import java.util.Locale

/**
 * 应用语言选择：对齐系统「应用语言」的语义。
 *
 * - 「跟随系统」= 清空应用级覆盖（LocaleListCompat 空列表），不是锁定某个语言；
 * - 每个语言行显示该语言的自称 + 当前界面语言下的名称（两者相同则只显示一个）；
 * - 标签与 locales_config 保持一致（zh-Hans / zh-Hant），不再混用 zh / zh-TW。
 */
object LanguagePicker {

    /** "" 表示跟随系统；其余与 res/values-* 目录及 locales_config.xml 对应 */
    private val TAGS = arrayOf("", "zh-Hans", "zh-Hant", "en", "tr", "ja", "ko", "de")

    fun normalize(tag: String): String = when (tag) {
        "zh", "zh-CN", "zh-rCN" -> "zh-Hans"
        "zh-TW", "zh-rTW", "zh-Hant" -> "zh-Hant"
        else -> tag
    }

    fun currentTag(): String = normalize(AppPrefs.appLangTag)

    fun apply(tag: String) {
        val t = normalize(tag)
        val list = if (t.isEmpty()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(t)
        }
        AppCompatDelegate.setApplicationLocales(list)
        AppPrefs.appLangTag = t
    }

    fun show(ctx: Context) {
        val current = currentTag()
        val here = Locale.getDefault()
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        var dialog: androidx.appcompat.app.AlertDialog? = null

        TAGS.forEach { tag ->
            val target = if (tag.isEmpty()) here else Locale.forLanguageTag(tag)
            val native = if (tag.isEmpty()) {
                ctx.getString(R.string.hc_follow_system)
            } else {
                displayName(target, target)
            }
            val localized = if (tag.isEmpty()) {
                here.getDisplayName(here)
            } else {
                displayName(target, here)
            }
            val selected = tag == current

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isClickable = true
                isFocusable = true
                setBackgroundColor(
                    if (selected) 0x1A2196F3 else 0x00000000
                )
            }

            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(TextView(ctx).apply {
                text = native
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(
                    if (selected) ContextCompat.getColor(ctx, R.color.md_theme_primary) else onSurface(ctx)
                )
            })
            if (localized.isNotBlank() && !localized.equals(native, ignoreCase = true)) {
                texts.addView(TextView(ctx).apply {
                    text = localized
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(ContextCompat.getColor(ctx, R.color.fg_2))
                    setPadding(0, dp(2), 0, 0)
                })
            }
            row.addView(texts)

            if (selected) {
                row.addView(TextView(ctx).apply {
                    text = "✓"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(ContextCompat.getColor(ctx, R.color.md_theme_primary))
                })
            }

            row.setOnClickListener {
                apply(tag)
                dialog?.dismiss()
            }
            box.addView(row)
        }

        dialog = NekoDialog.builder(ctx)
            .setTitle(ctx.getString(R.string.u52))
            .setView(NekoDialog.scroll(box))
            .setNegativeButton(ctx.getString(R.string.u72), null)
            .show()
    }

    private fun onSurface(ctx: Context): Int {
        val tv = TypedValue()
        return try {
            ctx.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, tv, true)
            if (tv.resourceId != 0) ContextCompat.getColor(ctx, tv.resourceId) else tv.data
        } catch (_: Throwable) {
            0xFF1A1A1A.toInt()
        }
    }

    private fun displayName(target: Locale, inLocale: Locale): String {
        return try {
            if (target.script.isNotEmpty()) target.getDisplayScript(inLocale)
            else target.getDisplayName(inLocale)
        } catch (_: Throwable) {
            target.getDisplayName(inLocale)
        }
    }
}
