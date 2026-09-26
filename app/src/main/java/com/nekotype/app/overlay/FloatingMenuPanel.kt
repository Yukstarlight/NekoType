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
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.nekotype.app.R
import com.nekotype.app.prefs.AppPrefs
import com.nekotype.app.prefs.AppPrefs.NekoRule
import com.nekotype.app.prefs.AppPrefs.RuleType
import com.nekotype.app.ui.HomeActivity
import com.nekotype.app.ui.LogActivity
import com.nekotype.app.ui.TerminalActivity
import com.nekotype.app.util.NekoLog
import com.nekotype.app.util.setTextSizeDimen

/**
 * 悬浮球长按快捷菜单（系统级悬浮窗，三页式）。
 *
 * 页面：
 * - [Mode.MAIN]    主菜单：回到应用 / 切换规则预设 / 进入规则开关 / 进入行为与样式 /
 *                  立即变换发送 / 规则页 / 设置 / 日志 / 终端 / 停止服务；
 * - [Mode.RULES]   规则开关：当前预设每条规则可直接启用/禁用 + 编辑（弹出悬浮编辑框）；
 * - [Mode.BEHAVIOR]行为与样式：字符间加空格 / 转为大写 / 自动发送 / 触感反馈 / 边缘吸附 /
 *                  静默修改 / 标点触发 / 随机颜文字 / 删除优化 / 语音输入优化 / 按等级全局执行。
 *
 * 交互：
 * - 纯文字菜单（无图标），仅手动关闭（右上 ✕ / 底部「关闭菜单」/ 点击面板外遮罩），
 *   不再定时自动关闭；
 * - 所有页面共用同一个可拖动面板：按住标题栏可拖到屏幕任意位置，松手记忆，
 *   下次弹出仍在原位置；切换页面不改变位置。
 *
 * 实现：不依赖 Material 组件与主题属性，全部原生 View + GradientDrawable 自绘，
 * 颜色按夜间模式硬编码，保证 Service 上下文下稳定弹出。
 */
class FloatingMenuPanel(private val service: FloatingButtonService) {

    enum class Mode { MAIN, RULES, BEHAVIOR }

    companion object {
        private const val TAG = "NekoMenu"

        @Volatile private var current: FloatingMenuPanel? = null

        fun isShowing(): Boolean = current?.isShowing == true

        fun dismissCurrent() {
            current?.dismiss()
        }

        private fun reportError(msg: String, t: Throwable?) {
            Log.e(TAG, msg, t)
            NekoLog.error("悬浮菜单：$msg${t?.let { "（${it.javaClass.simpleName}: ${it.message}）" } ?: ""}")
        }
    }

    private val ctx: Context get() = service
    private val wm: WindowManager by lazy {
        ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }
    private val density: Float by lazy { ctx.resources.displayMetrics.density }

    private var mode = Mode.MAIN
    private var root: FrameLayout? = null
    private var card: LinearLayout? = null
    private var contentHost: LinearLayout? = null
    private var footerHost: LinearLayout? = null
    private var titleView: TextView? = null
    private var scrollView: ScrollView? = null
    var isShowing = false
        private set

    // ---------- 配色（按夜间模式，不读主题属性） ----------

    private fun isNight(): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    private fun cPanel(): Int = if (isNight()) 0xF21C1C20.toInt() else 0xF7FFFFFF.toInt()
    private fun cText(): Int = if (isNight()) 0xFFF2F2F2.toInt() else 0xFF16161A.toInt()
    private fun cTextDim(): Int = if (isNight()) 0xFF9E9EA6.toInt() else 0xFF6B6B73.toInt()
    private fun cAccent(): Int = if (isNight()) 0xFF8AB4FF.toInt() else 0xFF2F5FD0.toInt()
    private fun cDivider(): Int = if (isNight()) 0x33FFFFFF else 0x1A000000
    private fun cStroke(): Int = if (isNight()) 0x33FFFFFF else 0x14000000
    private fun cPressed(): Int = if (isNight()) 0x22FFFFFF else 0x0F000000
    private fun cPillOn(): Int = if (isNight()) 0x338AB4FF else 0x222F5FD0
    private fun cPillOff(): Int = if (isNight()) 0x22FFFFFF else 0x0D000000

    // ---------- 显示 / 关闭 / 切换 ----------

    /**
     * 在悬浮球坐标附近显示主菜单。
     */
    fun show(anchorX: Int, anchorY: Int, anchorW: Int, anchorH: Int) {
        if (isShowing) {
            Log.d(TAG, "show() ignored: already showing")
            return
        }
        mode = Mode.MAIN
        try {
            val out = android.graphics.Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getSize(out)
            val screenW = out.x
            val screenH = out.y
            if (screenW <= 0 || screenH <= 0) {
                reportError("屏幕尺寸异常 ${screenW}x$screenH", null)
                return
            }

            val panelW = dp(276)
            val maxScrollH = (screenH * 0.5f).toInt()
            val built = buildPanel(panelW, maxScrollH)
            card = built.card
            contentHost = built.host
            footerHost = built.footer
            titleView = built.title
            scrollView = built.scroll

            built.card.measure(
                View.MeasureSpec.makeMeasureSpec(panelW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val panelH = built.card.measuredHeight.coerceAtLeast(dp(120))

            // 定位：用户拖动过的位置优先；否则自动锚定在悬浮球旁
            val gap = dp(8)
            val maxX = (screenW - panelW - gap).coerceAtLeast(gap)
            val maxY = (screenH - panelH - gap).coerceAtLeast(gap)
            val savedX = AppPrefs.fabMenuX
            val savedY = AppPrefs.fabMenuY
            var x: Int
            var y: Int
            if (savedX >= 0 && savedY >= 0) {
                x = savedX.coerceIn(gap, maxX)
                y = savedY.coerceIn(gap, maxY)
            } else {
                val ballCx = anchorX + anchorW / 2
                val onRightHalf = ballCx > screenW / 2
                x = if (onRightHalf) anchorX - panelW - gap else anchorX + anchorW + gap
                y = anchorY + anchorH + dp(6)
                if (y + panelH > screenH - gap) y = anchorY - panelH - dp(6)
                x = x.coerceIn(gap, maxX)
                y = y.coerceIn(gap, maxY)
            }

            val fr = FrameLayout(ctx).apply {
                setBackgroundColor(0x77000000)
                isClickable = true
                isFocusable = false
                setOnClickListener { dismiss() }
            }
            val cardLp = FrameLayout.LayoutParams(panelW, panelH).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = x
                topMargin = y
            }
            fr.addView(built.card, cardLp)
            // 面板可拖动：按住标题栏可拖到任意位置，松手记忆
            built.card.setOnTouchListener(dragListener(cardLp, screenW, screenH))
            root = fr

            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }

            wm.addView(fr, p)
            isShowing = true
            current = this
            Log.i(TAG, "menu shown OK")
            NekoLog.info("悬浮菜单已弹出")
        } catch (t: Throwable) {
            isShowing = false
            current = null
            root = null
            reportError("菜单弹出失败", t)
            try {
                Toast.makeText(ctx, "菜单弹出失败：${t.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) { }
        }
    }

    /** 在当前面板内切换页面（位置不变，内容与标题刷新） */
    fun switchMode(newMode: Mode) {
        if (!isShowing) return
        mode = newMode
        titleView?.text = titleFor(newMode)
        contentHost?.let { buildBody(it) }
        footerHost?.let { buildFooter(it) }
        scrollView?.scrollTo(0, 0)
        Log.d(TAG, "switched to $newMode")
    }

    fun dismiss() {
        val r = root ?: run {
            isShowing = false
            if (current === this) current = null
            return
        }
        try { wm.removeView(r) } catch (t: Throwable) { Log.w(TAG, "removeView failed", t) }
        root = null
        card = null
        contentHost = null
        footerHost = null
        titleView = null
        scrollView = null
        isShowing = false
        if (current === this) current = null
        Log.d(TAG, "menu dismissed")
    }

    private fun titleFor(m: Mode): String = when (m) {
        Mode.MAIN -> str(R.string.fm_title)
        Mode.RULES -> str(R.string.fm_open_rules_panel)
        Mode.BEHAVIOR -> str(R.string.fm_open_behavior_panel)
    }

    // ---------- 面板构建 ----------

    private data class Built(
        val card: LinearLayout,
        val host: LinearLayout,
        val footer: LinearLayout,
        val title: TextView,
        val scroll: ScrollView
    )

    /**
     * 标题（可拖动）→ 分隔 → 滚动内容（高度按内容自适应，上限 maxScrollH）→ 分隔 → 底部操作栏。
     * 全部显式高度，不用 layout_weight 撑内容，避免 wrap_content 父容器下 weight 失效。
     */
    private fun buildPanel(panelW: Int, maxScrollH: Int): Built {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cPanel(), dp(18).toFloat(), cStroke(), dp(1))
            setPadding(dp(14), dp(12), dp(14), dp(6))
            elevation = dp(8).toFloat()
        }

        // 标题栏（整块可作为拖动把手）
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(ctx).apply {
            text = titleFor(mode)
            setTextSizeDimen(R.dimen.ts_15_5)
            setTextColor(cText())
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(ctx).apply {
            text = "✕"
            setTextSizeDimen(R.dimen.ts_15)
            setTextColor(cTextDim())
            setPadding(dp(10), dp(6), dp(4), dp(6))
            background = rowBg()
            isClickable = true
            setOnClickListener { dismiss() }
        })
        card.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        card.addView(makeDivider())

        // 滚动内容区
        val host = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        buildBody(host)
        try {
            host.measure(
                View.MeasureSpec.makeMeasureSpec(panelW - dp(28), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
        } catch (t: Throwable) { Log.w(TAG, "content measure failed", t) }
        val contentH = host.measuredHeight
        val scrollH = if (contentH in 1 until maxScrollH) contentH else maxScrollH
        val scroll = ScrollView(ctx).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            addView(host, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        card.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, scrollH
        ))

        card.addView(makeDivider())

        // 底部操作栏（固定，不随内容滚动）
        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        buildFooter(footer)
        card.addView(footer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        return Built(card, host, footer, title, scroll)
    }

    // ---------- 页面内容 ----------

    private fun buildBody(host: LinearLayout) {
        host.removeAllViews()
        when (mode) {
            Mode.MAIN -> bodyMain(host)
            Mode.RULES -> bodyRules(host)
            Mode.BEHAVIOR -> bodyBehavior(host)
        }
    }

    private fun buildFooter(host: LinearLayout) {
        host.removeAllViews()
        if (mode == Mode.MAIN) {
            host.addView(footerBtn(str(R.string.fm_close), accent = true, gravity = Gravity.CENTER) { dismiss() })
            return
        }
        host.addView(footerBtn(str(R.string.fm_back), accent = true, gravity = Gravity.START) { switchMode(Mode.MAIN) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        host.addView(footerBtn(str(R.string.fm_close), accent = false, gravity = Gravity.END) { dismiss() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    /** 主菜单：回到应用 / 规则预设 / 入口 / 常用操作（纯文字，无图标） */
    private fun bodyMain(host: LinearLayout) {
        host.addView(actionRow(str(R.string.fm_open_app), highlight = true) {
            openApp(HomeActivity.TAB_HOME)
            dismiss()
        })

        host.addView(sectionTitle(str(R.string.fm_section_presets)))
        val presets = AppPrefs.presetList()
        val curId = AppPrefs.activePresetId()
        if (presets.isEmpty()) {
            host.addView(hintRow(str(R.string.fm_no_preset)))
        } else {
            presets.forEach { (id, name) ->
                val isCur = id == curId
                host.addView(presetRow(name, AppPrefs.ruleCountOf(id), isCur) {
                    if (isCur) {
                        dismiss()
                        return@presetRow
                    }
                    try {
                        AppPrefs.selectPreset(id)
                        NekoLog.rule("悬浮菜单切换规则预设：$name")
                        toast(str(R.string.fm_preset_switched, AppPrefs.activePresetName()))
                    } catch (t: Throwable) { Log.w(TAG, "selectPreset failed", t) }
                    contentHost?.let { buildBody(it) }
                })
            }
        }
        host.addView(actionRow(str(R.string.fm_preset_manage), false) {
            openApp(HomeActivity.TAB_RULES)
            dismiss()
        })

        host.addView(sectionTitle(str(R.string.fm_section_other)))
        host.addView(actionRow(str(R.string.fm_open_rules_panel), false) { switchMode(Mode.RULES) })
        host.addView(actionRow(str(R.string.fm_open_behavior_panel), false) { switchMode(Mode.BEHAVIOR) })
        host.addView(actionRow(str(R.string.fm_action_transform), false) {
            dismiss()
            service.performTransformNow()
        })
        host.addView(actionRow(str(R.string.fm_action_rules), false) {
            openApp(HomeActivity.TAB_RULES)
            dismiss()
        })
        host.addView(actionRow(str(R.string.fm_action_settings), false) {
            openApp(HomeActivity.TAB_SETTINGS)
            dismiss()
        })
        host.addView(actionRow(str(R.string.fm_action_log), false) {
            startAct(LogActivity::class.java)
            dismiss()
        })
        host.addView(actionRow(str(R.string.fm_action_terminal), false) {
            startAct(TerminalActivity::class.java)
            dismiss()
        })
        host.addView(actionRow(str(R.string.fm_action_stop), false) {
            dismiss()
            stopServiceViaHome()
        })
    }

    /** 规则开关页：当前预设每条规则可直接启用/禁用 + 编辑 */
    private fun bodyRules(host: LinearLayout) {
        host.addView(hintRow(str(R.string.fm_current_preset, AppPrefs.activePresetName())))
        val rules = AppPrefs.rules()
        if (rules.isEmpty()) {
            host.addView(hintRow(str(R.string.fm_no_rules)))
            return
        }
        rules.forEach { host.addView(ruleRow(it)) }
    }

    /** 行为与样式页：与 App 规则页「行为与样式」卡片一致的全部开关 */
    private fun bodyBehavior(host: LinearLayout) {
        host.addView(toggleRow(str(R.string.t48), AppPrefs.styleSpaced) { AppPrefs.styleSpaced = it })
        host.addView(toggleRow(str(R.string.t49), AppPrefs.styleUpper) { AppPrefs.styleUpper = it })
        host.addView(toggleRow(str(R.string.t50), AppPrefs.autoSend) { AppPrefs.autoSend = it })
        host.addView(toggleRow(str(R.string.t51), AppPrefs.hapticEnabled) { AppPrefs.hapticEnabled = it })
        host.addView(toggleRow(str(R.string.t52), AppPrefs.snapEdges) { AppPrefs.snapEdges = it })
        host.addView(toggleRow(str(R.string.t53), AppPrefs.silentModifyEnabled) { v ->
            AppPrefs.silentModifyEnabled = v
            NekoLog.adjust(if (v) "开启静默修改（Shizuku 直写）" else "关闭静默修改")
        })
        host.addView(toggleRow(str(R.string.t57), AppPrefs.punctTriggerEnabled) { AppPrefs.punctTriggerEnabled = it })
        host.addView(toggleRow(str(R.string.t59), AppPrefs.emoticonEnabled) { AppPrefs.emoticonEnabled = it })
        host.addView(toggleRow(str(R.string.t1001), AppPrefs.deleteOptimizeEnabled) { AppPrefs.deleteOptimizeEnabled = it })
        host.addView(toggleRow(str(R.string.t1003), AppPrefs.voiceInputOptimizeEnabled) { AppPrefs.voiceInputOptimizeEnabled = it })
        host.addView(toggleRow(str(R.string.i43), AppPrefs.priorityGlobalEnabled) { AppPrefs.priorityGlobalEnabled = it })
    }

    // ---------- 行组件 ----------

    private fun sectionTitle(label: String): View = TextView(ctx).apply {
        text = label
        setTextSizeDimen(R.dimen.ts_12)
        setTextColor(cAccent())
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(4), dp(12), dp(4), dp(4))
    }

    private fun makeDivider(): View = View(ctx).apply {
        setBackgroundColor(cDivider())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { topMargin = dp(6); bottomMargin = dp(6) }
    }

    private fun hintRow(text: String): View = TextView(ctx).apply {
        this.text = text
        setTextSizeDimen(R.dimen.ts_12_5)
        setTextColor(cTextDim())
        setPadding(dp(4), dp(8), dp(4), dp(8))
    }

    /** 普通可点击行（纯文字，无图标） */
    private fun actionRow(label: String, highlight: Boolean, onClick: () -> Unit): View {
        val row = TextView(ctx).apply {
            text = label
            setTextSizeDimen(R.dimen.ts_13_5)
            setTextColor(if (highlight) cAccent() else cText())
            if (highlight) setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), dp(11), dp(8), dp(11))
            background = rowBg()
            isClickable = true
            setOnClickListener { haptic(); onClick() }
        }
        return row
    }

    /** 预设行：名称 + 条数 + 当前标记（纯文字） */
    private fun presetRow(name: String, count: Int, isCur: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = rowBg()
            isClickable = true
            setOnClickListener { haptic(); onClick() }
        }
        row.addView(TextView(ctx).apply {
            text = name
            setTextSizeDimen(R.dimen.ts_14)
            setTextColor(cText())
            if (isCur) setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(ctx).apply {
            text = buildString {
                append(str(R.string.fm_preset_rules_count, count))
                if (isCur) append("  ✓")
            }
            setTextSizeDimen(R.dimen.ts_12)
            setTextColor(if (isCur) cAccent() else cTextDim())
        })
        return row
    }

    /** 规则行：摘要 + 开/关 + 编辑 */
    private fun ruleRow(rule: NekoRule): View {
        var on = rule.enabled
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(9), dp(8), dp(9))
        }
        val valueText = when (rule.type) {
            RuleType.REPLACE -> "${rule.value} → ${rule.replaceTo}"
            RuleType.RANDOM_PREFIX, RuleType.RANDOM_SUFFIX,
            RuleType.RANDOM_PREFIX_ONCE, RuleType.RANDOM_SUFFIX_ONCE ->
                "${rule.value.ifEmpty { str(R.string.u94) }} · ${rule.chance}%"
            else -> rule.value
        }
        val summary = TextView(ctx).apply {
            text = str(rule.type.resId) + "  " + valueText
            setTextSizeDimen(R.dimen.ts_13_5)
            setTextColor(cText())
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        row.addView(summary, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 开/关药丸
        val pill = TextView(ctx).apply {
            setTextSizeDimen(R.dimen.ts_12)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setTypeface(typeface, Typeface.BOLD)
        }
        fun renderPill() {
            pill.text = if (on) str(R.string.fm_on) else str(R.string.fm_off)
            pill.setTextColor(if (on) cAccent() else cTextDim())
            pill.background = rounded(if (on) cPillOn() else cPillOff(), dp(10).toFloat(), Color.TRANSPARENT, 0)
        }
        renderPill()
        pill.isClickable = true
        pill.setOnClickListener {
            on = !on
            renderPill()
            haptic()
            AppPrefs.updateRule(rule.id) { it.copy(enabled = on) }
            NekoLog.rule("悬浮菜单：规则「${rule.type.label} ${rule.value.take(16)}」已${if (on) "启用" else "停用"}")
        }
        row.addView(pill, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(8) })

        // 编辑
        val edit = TextView(ctx).apply {
            text = str(R.string.fm_edit)
            setTextSizeDimen(R.dimen.ts_13)
            setTextColor(cAccent())
            setPadding(dp(10), dp(6), dp(2), dp(6))
            isClickable = true
            setOnClickListener {
                haptic()
                RuleEditOverlay(service).show(rule) {
                    contentHost?.let { buildBody(it) }
                }
            }
        }
        row.addView(edit, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(4) })
        return row
    }

    /** 开关行：文字 + 开/关药丸（不依赖 MaterialSwitch） */
    private fun toggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit): View {
        var on = value
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = rowBg()
            isClickable = true
        }
        row.addView(TextView(ctx).apply {
            text = label
            setTextSizeDimen(R.dimen.ts_13_5)
            setTextColor(cText())
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val pill = TextView(ctx).apply {
            setTextSizeDimen(R.dimen.ts_12)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setTypeface(typeface, Typeface.BOLD)
        }
        fun renderPill() {
            pill.text = if (on) str(R.string.fm_on) else str(R.string.fm_off)
            pill.setTextColor(if (on) cAccent() else cTextDim())
            pill.background = rounded(if (on) cPillOn() else cPillOff(), dp(10).toFloat(), Color.TRANSPARENT, 0)
        }
        renderPill()
        row.addView(pill)
        row.setOnClickListener {
            on = !on
            renderPill()
            haptic()
            onChange(on)
        }
        return row
    }

    /** 底部操作栏按钮（纯文字） */
    private fun footerBtn(label: String, accent: Boolean, gravity: Int, onClick: () -> Unit): View =
        TextView(ctx).apply {
            text = label
            setTextSizeDimen(R.dimen.ts_14)
            setTextColor(if (accent) cAccent() else cTextDim())
            setGravity(gravity)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = rowBg()
            isClickable = true
            setOnClickListener { haptic(); onClick() }
        }

    // ---------- 操作 ----------

    private fun openApp(tab: String) {
        try {
            ctx.startActivity(Intent(ctx, HomeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(HomeActivity.EXTRA_TAB, tab)
            })
        } catch (t: Throwable) {
            Log.w(TAG, "openApp failed", t)
            toast(str(R.string.fm_open_fail))
        }
    }

    private fun startAct(cls: Class<*>) {
        try {
            ctx.startActivity(Intent(ctx, cls).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        } catch (t: Throwable) {
            Log.w(TAG, "startActivity failed", t)
            toast(str(R.string.fm_open_fail))
        }
    }

    /** 停止服务：复用 HomeActivity 的停止入口（密码锁定会弹验证框） */
    private fun stopServiceViaHome() {
        try {
            ctx.startActivity(Intent(ctx, HomeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(HomeActivity.EXTRA_STOP_REQUEST, true)
            })
        } catch (t: Throwable) {
            Log.w(TAG, "stopService intent failed", t)
            toast(str(R.string.fm_open_fail))
        }
    }

    // ---------- 面板拖动 ----------

    /**
     * 面板拖动：按住标题栏（或面板空白处）可拖到屏幕任意位置，
     * 松手后位置写入 AppPrefs 跨会话记忆；范围限制在屏幕内。
     */
    private fun dragListener(
        lp: FrameLayout.LayoutParams,
        screenW: Int,
        screenH: Int
    ): View.OnTouchListener {
        val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        return View.OnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.leftMargin
                    startY = lp.topMargin
                    moved = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                        moved = true
                    }
                    if (moved) {
                        val gap = dp(4)
                        val maxX = (screenW - lp.width - gap).coerceAtLeast(gap)
                        val maxY = (screenH - lp.height - gap).coerceAtLeast(gap)
                        lp.leftMargin = (startX + dx.toInt()).coerceIn(gap, maxX)
                        lp.topMargin = (startY + dy.toInt()).coerceIn(gap, maxY)
                        v.layoutParams = lp
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    if (moved) {
                        AppPrefs.fabMenuX = lp.leftMargin
                        AppPrefs.fabMenuY = lp.topMargin
                        Log.d(TAG, "panel moved to (${lp.leftMargin},${lp.topMargin})")
                        NekoLog.adjust("快捷菜单面板已移动到 (${lp.leftMargin},${lp.topMargin})")
                    }
                    true
                }
                else -> false
            }
        }
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

    /** 行背景：按下略亮，普通透明（不依赖主题 attr） */
    private fun rowBg(): Drawable = StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_pressed),
            rounded(cPressed(), dp(10).toFloat(), Color.TRANSPARENT, 0)
        )
        addState(intArrayOf(), rounded(Color.TRANSPARENT, dp(10).toFloat(), Color.TRANSPARENT, 0))
    }

    private fun str(resId: Int, vararg args: Any): String =
        try {
            if (args.isEmpty()) ctx.getString(resId) else ctx.getString(resId, *args)
        } catch (_: Throwable) { "" }

    private fun haptic() {
        try {
            card?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        } catch (_: Throwable) { }
    }

    private fun toast(msg: String) {
        try { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() } catch (_: Throwable) { }
    }
}
