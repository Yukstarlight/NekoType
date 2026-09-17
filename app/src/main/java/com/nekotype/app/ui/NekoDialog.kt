package com.nekotype.app.ui

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * 统一对话框样式（本次 UI 优化）：
 * 全项目原本用 androidx.appcompat 的 AlertDialog（系统原生外观：方角、灰按钮、裸 EditText），
 * 与 Material3 主题格格不入。这里统一换成 Material3 对话框 + Material 输入框。
 *
 * 放在 com.nekotype.app.ui 包内 → 同包直接调用，调用方无需新增 import；
 * MaterialAlertDialogBuilder 继承自 AlertDialog.Builder，链式 API 完全兼容，
 * create() 返回的仍是 androidx AlertDialog，原有 dialogRef / getButton 写法不用改。
 */
object NekoDialog {

    /** 统一入口：Material3 圆角对话框 */
    fun builder(ctx: Context): MaterialAlertDialogBuilder = MaterialAlertDialogBuilder(ctx)

    /**
     * Material 输入框（filled 样式），替代裸 EditText：
     * 自带浮动标签、圆角背景、错误提示位。
     */
    fun input(
        ctx: Context,
        hint: String,
        value: String = "",
        numeric: Boolean = false,
        multiline: Boolean = false,
        password: Boolean = false
    ): TextInputLayout {
        val layout = TextInputLayout(ctx).apply {
            this.hint = hint
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_FILLED
            if (password) {
                endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 10) }
        }
        val edit = TextInputEditText(ctx).apply {
            setText(value)
            if (value.isNotEmpty()) setSelection(value.length)
            inputType = when {
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                numeric -> InputType.TYPE_CLASS_NUMBER
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (multiline) {
                minLines = 4
                maxLines = 8
                gravity = Gravity.TOP or Gravity.START
            }
            isSingleLine = !multiline
        }
        layout.addView(edit)
        return layout
    }

    /** 从输入框取文本（配合 [input] 使用） */
    fun textOf(layout: TextInputLayout): String =
        layout.editText?.text?.toString().orEmpty()

    /** 垂直内容容器：统一左右留白，避免贴边 */
    fun column(ctx: Context, vararg views: View): LinearLayout {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 24), dp(ctx, 4), dp(ctx, 24), dp(ctx, 8))
        }
        views.forEach { box.addView(it) }
        return box
    }

    /** 内容过高时套一层滚动，避免按钮/字段被挤出屏幕（添加规则对话框原来就踩过这个坑） */
    fun scroll(content: View): ScrollView = ScrollView(content.context).apply {
        isFillViewport = false
        addView(
            content,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /** 可点击条目行（法律文档入口等）：左侧标题 + 右侧箭头，带点击水波纹 */
    fun row(ctx: Context, label: String, onClick: () -> Unit): android.widget.TextView {
        val tv = android.widget.TextView(ctx).apply {
            this.text = "$label    ›"
            textSize = 14f
            setPadding(dp(ctx, 4), dp(ctx, 14), dp(ctx, 4), dp(ctx, 14))
            isClickable = true
            isFocusable = true
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick() }
        }
        // 主题色
        val tvAttr = android.util.TypedValue()
        if (ctx.theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, tvAttr, true)) {
            tv.setTextColor(
                if (tvAttr.resourceId != 0) androidx.core.content.ContextCompat.getColor(ctx, tvAttr.resourceId)
                else tvAttr.data
            )
        }
        return tv
    }

    /** 展示长文档（隐私政策 / 第三方清单 / 开源许可）：可滚动、占屏 90% 高、可长按复制 */
    fun showDoc(ctx: Context, title: String, body: String) {
        val tv = android.widget.TextView(ctx).apply {
            text = body
            textSize = 12.5f
            setLineSpacing(0f, 1.3f)
            setTextIsSelectable(true)
        }
        val d = builder(ctx)
            .setTitle(title)
            .setView(scroll(column(ctx, tv)))
            .setPositiveButton(ctx.getString(com.nekotype.app.R.string.i58), null)
            .create()
        d.show()
        try {
            val dm = ctx.resources.displayMetrics
            d.window?.setLayout((dm.widthPixels * 0.92f).toInt(), (dm.heightPixels * 0.9f).toInt())
        } catch (_: Throwable) { }
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
