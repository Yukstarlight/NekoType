package com.nekotype.app.overlay

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.nekotype.app.R
import com.nekotype.app.prefs.AppPrefs
import com.nekotype.app.prefs.AppPrefs.NekoRule
import com.nekotype.app.prefs.AppPrefs.RuleType
import com.nekotype.app.util.NekoLog
import com.nekotype.app.util.setTextSizeDimen

/**
 * 悬浮规则编辑浮窗：从「规则开关」面板点「编辑」弹出，直接在任意应用上层编辑一条规则
 * （值 / 检测的字·替换为 / 概率 / 等级），保存即写回当前预设。
 *
 * 与菜单面板不同：这是**可聚焦**的浮窗（EditText 需要输入焦点，会唤起键盘），
 * 全屏遮罩点空白关闭，底部「保存 / 取消」按钮。密码锁定开启时，需先输入密码再保存。
 * 不依赖 Material 组件，原生 EditText + GradientDrawable 自绘。
 */
class RuleEditOverlay(private val service: FloatingButtonService) {

    companion object {
        private const val TAG = "NekoRuleEdit"

        @Volatile private var current: RuleEditOverlay? = null

        fun isShowing(): Boolean = current?.isShowing == true

        fun dismissCurrent() { current?.dismiss() }
    }

    private val ctx: Context get() = service
    private val wm: WindowManager by lazy {
        ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }
    private val density: Float by lazy { ctx.resources.displayMetrics.density }

    private var root: FrameLayout? = null
    private var card: LinearLayout? = null
    var isShowing = false
        private set

    private fun isNight(): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    private fun cPanel(): Int = if (isNight()) 0xF21C1C20.toInt() else 0xF7FFFFFF.toInt()
    private fun cText(): Int = if (isNight()) 0xFFF2F2F2.toInt() else 0xFF16161A.toInt()
    private fun cTextDim(): Int = if (isNight()) 0xFF9E9EA6.toInt() else 0xFF6B6B73.toInt()
    private fun cAccent(): Int = if (isNight()) 0xFF8AB4FF.toInt() else 0xFF2F5FD0.toInt()
    private fun cDivider(): Int = if (isNight()) 0x33FFFFFF else 0x1A000000
    private fun cStroke(): Int = if (isNight()) 0x33FFFFFF else 0x14000000
    private fun cFieldBg(): Int = if (isNight()) 0xFF2A2A2F.toInt() else 0xFFEFEFF2.toInt()
    private fun cPressed(): Int = if (isNight()) 0x22FFFFFF else 0x0F000000

    fun show(rule: NekoRule, onSaved: () -> Unit) {
        if (isShowing) {
            Log.d(TAG, "show() ignored: already showing")
            return
        }
        try {
            val out = android.graphics.Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getSize(out)
            val screenW = out.x
            if (screenW <= 0) return

            val card = buildCard(rule, onSaved)
            this.card = card

            val fr = FrameLayout(ctx).apply {
                setBackgroundColor(0x99000000.toInt())
                isClickable = true
                isFocusable = false
                setOnClickListener { dismiss() }
            }
            val cardW = dp(300).coerceAtMost(screenW - dp(32))
            val lp = FrameLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
            fr.addView(card, lp)
            root = fr

            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // 可聚焦：EditText 需要输入焦点唤起键盘；点遮罩外（其实窗口已全屏）由遮罩 onClick 关闭
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                        or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            }

            wm.addView(fr, p)
            isShowing = true
            current = this
            Log.i(TAG, "rule edit shown for ${rule.type} ${rule.value.take(12)}")
        } catch (t: Throwable) {
            isShowing = false
            current = null
            root = null
            Log.e(TAG, "rule edit show failed", t)
            NekoLog.error("规则编辑浮窗弹出失败：${t.javaClass.simpleName}: ${t.message}")
            try {
                Toast.makeText(ctx, "编辑浮窗弹出失败：${t.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) { }
        }
    }

    fun dismiss() {
        val r = root ?: run {
            isShowing = false
            if (current === this) current = null
            return
        }
        // 隐藏键盘（若当前焦点在 EditText 上）
        try {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            card?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
        } catch (_: Throwable) { }
        try { wm.removeView(r) } catch (t: Throwable) { Log.w(TAG, "removeView failed", t) }
        root = null
        card = null
        isShowing = false
        if (current === this) current = null
        Log.d(TAG, "rule edit dismissed")
    }

    private fun buildCard(rule: NekoRule, onSaved: () -> Unit): LinearLayout {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cPanel(), dp(18).toFloat(), cStroke(), dp(1))
            setPadding(dp(16), dp(14), dp(16), dp(8))
            elevation = dp(10).toFloat()
        }

        // 标题 + 类型
        card.addView(TextView(ctx).apply {
            text = str(R.string.u87)
            setTextSizeDimen(R.dimen.ts_16)
            setTextColor(cText())
            setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(TextView(ctx).apply {
            text = str(rule.type.resId)
            setTextSizeDimen(R.dimen.ts_12)
            setTextColor(cAccent())
            setPadding(0, dp(2), 0, 0)
        })
        card.addView(makeDivider())

        val fields = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }

        val inValue = input(str(R.string.u83), rule.type == RuleType.PREFIX || rule.type == RuleType.SUFFIX)
        inValue.setText(rule.value)
        val inFrom = input(str(R.string.u84))
        inFrom.setText(rule.value)
        val inTo = input(str(R.string.u85))
        inTo.setText(rule.replaceTo)
        val inChance = input(str(R.string.u86), numeric = true)
        inChance.setText(rule.chance.toString())
        val inPriority = input(str(R.string.i42), numeric = true)
        inPriority.setText(rule.priority.toString())

        fun refreshFields(type: RuleType) {
            fields.removeAllViews()
            when (type) {
                RuleType.PREFIX, RuleType.SUFFIX -> fields.addView(inValue)
                RuleType.RANDOM_PREFIX, RuleType.RANDOM_SUFFIX,
                RuleType.RANDOM_PREFIX_ONCE, RuleType.RANDOM_SUFFIX_ONCE -> {
                    fields.addView(inValue)
                    fields.addView(inChance)
                }
                RuleType.REPLACE -> {
                    fields.addView(inFrom)
                    fields.addView(inTo)
                }
            }
            fields.addView(inPriority)
        }
        refreshFields(rule.type)
        val scroll = ScrollView(ctx).apply {
            addView(fields, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        card.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })

        // 密码锁定：保存前需验证（与 App 内编辑规则一致）
        val inPwd: EditText? = if (AppPrefs.lockEnabled) {
            val tv = TextView(ctx).apply {
                text = str(R.string.u75)
                setTextSizeDimen(R.dimen.ts_12)
                setTextColor(cTextDim())
                setPadding(0, dp(6), 0, 0)
            }
            card.addView(tv)
            input("", password = true).also {
                card.addView(it, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) })
            }
        } else null

        card.addView(makeDivider())

        // 按钮栏
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val saveBtn = TextView(ctx).apply {
            text = str(R.string.u89)
            setTextSizeDimen(R.dimen.ts_14)
            setTextColor(cAccent())
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(14), dp(10), dp(10), dp(10))
            background = rowBg()
            isClickable = true
            setOnClickListener {
                val type = rule.type
                val pri = textOf(inPriority).toIntOrNull()?.coerceIn(1, 100) ?: 50
                val base: NekoRule? = when (type) {
                    RuleType.PREFIX, RuleType.SUFFIX -> {
                        val v = textOf(inValue).trim()
                        if (v.isEmpty()) { toast(str(R.string.u43)); return@setOnClickListener }
                        rule.copy(value = v, priority = pri)
                    }
                    RuleType.RANDOM_PREFIX, RuleType.RANDOM_SUFFIX,
                    RuleType.RANDOM_PREFIX_ONCE, RuleType.RANDOM_SUFFIX_ONCE -> {
                        val v = textOf(inValue).trim()
                        if (v.isEmpty()) { toast(str(R.string.u19)); return@setOnClickListener }
                        val chance = textOf(inChance).toIntOrNull()?.coerceIn(1, 100) ?: 50
                        rule.copy(value = v, chance = chance, priority = pri)
                    }
                    RuleType.REPLACE -> {
                        val from = textOf(inFrom).trim()
                        if (from.isEmpty()) { toast(str(R.string.u35)); return@setOnClickListener }
                        val to = textOf(inTo)
                        rule.copy(value = from, replaceTo = to, priority = pri)
                    }
                }
                if (base == null) return@setOnClickListener
                // 密码锁定验证
                if (inPwd != null) {
                    val pwd = textOf(inPwd)
                    if (!AppPrefs.verifyLockPassword(pwd)) {
                        toast(str(R.string.u25))
                        return@setOnClickListener
                    }
                }
                try {
                    AppPrefs.updateRule(rule.id) { base }
                    NekoLog.rule("悬浮编辑规则：${base.type.label} ${base.value.take(16)}")
                    toast(str(R.string.u23))
                    dismiss()
                    onSaved()
                } catch (t: Throwable) {
                    Log.e(TAG, "save rule failed", t)
                    toast("保存失败：${t.javaClass.simpleName}")
                }
            }
        }
        val cancelBtn = TextView(ctx).apply {
            text = str(R.string.u72)
            setTextSizeDimen(R.dimen.ts_14)
            setTextColor(cTextDim())
            setPadding(dp(10), dp(10), dp(14), dp(10))
            background = rowBg()
            isClickable = true
            setOnClickListener { dismiss() }
        }
        bar.addView(cancelBtn)
        bar.addView(saveBtn)
        card.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })

        return card
    }

    // ---------- 绘制工具 ----------

    private fun dp(v: Int): Int = (v * density).toInt()

    private fun rounded(fill: Int, radius: Float, stroke: Int, strokeW: Int): Drawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(fill)
            if (strokeW > 0) setStroke(strokeW, stroke)
        }

    private fun rowBg(): Drawable = StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_pressed),
            rounded(cPressed(), dp(10).toFloat(), Color.TRANSPARENT, 0)
        )
        addState(intArrayOf(), rounded(Color.TRANSPARENT, dp(10).toFloat(), Color.TRANSPARENT, 0))
    }

    private fun makeDivider(): View = View(ctx).apply {
        setBackgroundColor(cDivider())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { topMargin = dp(6); bottomMargin = dp(6) }
    }

    /** 裸 EditText（带标签 + 圆角填充背景），不依赖 Material TextInputLayout */
    private fun input(hint: String, numeric: Boolean = false, password: Boolean = false): EditText {
        return EditText(ctx).apply {
            this.hint = hint
            background = rounded(cFieldBg(), dp(10).toFloat(), cStroke(), dp(1))
            setTextColor(cText())
            setHintTextColor(cTextDim())
            setTextSizeDimen(R.dimen.ts_14)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            inputType = when {
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                numeric -> InputType.TYPE_CLASS_NUMBER
                else -> InputType.TYPE_CLASS_TEXT
            }
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
    }

    private fun textOf(et: EditText): String = et.text?.toString().orEmpty()

    private fun str(resId: Int, vararg args: Any): String =
        try {
            if (args.isEmpty()) ctx.getString(resId) else ctx.getString(resId, *args)
        } catch (_: Throwable) { "" }

    private fun toast(msg: String) {
        try { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() } catch (_: Throwable) { }
    }
}
