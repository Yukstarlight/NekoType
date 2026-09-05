package com.nekotype.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nekotype.app.util.NekoLog
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File

/**
 * 内嵌终端页（MUIT 集成终端 Beta）：
 * 复用 Termux 的 terminal-emulator / terminal-view 引擎，通过 JNI PTY 跑 Android 系统 shell。
 */
class TerminalActivity : AppCompatActivity(), TerminalSessionClient, TerminalViewClient {

    private var terminalView: TerminalView? = null
    private var session: TerminalSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LOW_PROFILE
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
        setContentView(com.nekotype.app.R.layout.activity_terminal)
        val tv = findViewById<com.termux.view.TerminalView>(com.nekotype.app.R.id.terminalView)
        terminalView = tv
        tv.setTerminalViewClient(this)
        // 字体大小：setTextSize 参数直接给 Paint.setTextSize（px），须把 dp 转 px
        // 14dp 适中（16dp 偏大）
        val fontSizePx = (14 * resources.displayMetrics.density).toInt()
        tv.setTextSize(fontSizePx)
        // 用内置 JetBrains Mono 等宽字体，不跟随系统字体（用户改过系统字体，显示会怪）
        try {
            val tf = android.graphics.Typeface.createFromAsset(assets, "fonts/jetbrains_mono.ttf")
            tv.setTypeface(tf)
        } catch (_: Throwable) {
            tv.setTypeface(android.graphics.Typeface.MONOSPACE)
        }
        // 纯黑背景
        tv.setBackgroundColor(android.graphics.Color.BLACK)
        // 让 TerminalView 可获取焦点（键盘输入前提）
        tv.isFocusable = true
        tv.isFocusableInTouchMode = true
        tv.requestFocus()

        startShell()
    }

    private fun startShell() {
        try {
            val tv = terminalView
            if (tv == null) { finish(); return }
            // 1. 横幅作为 mksh 的 ENV rc 文件：内容必须是合法 shell 命令（每行都会被执行）
            val rcFile = File(filesDir, "muit_profile")
            try {
                val lines = listOf(
                    "echo 'Welcome to Mist Unveils Infinite Tomorrows \u96c6\u6210\u7ec8\u7aef'",
                    "echo 'NekoType x Android Linux - \u7cfb\u7edf sh + toybox \u547d\u4ee4\u96c6'",
                    "echo '\u53ef\u7528\u547d\u4ee4: \u8f93\u5165 toybox --help \u67e5\u770b\u5168\u90e8\u547d\u4ee4\u5217\u8868'",
                    "echo '\u5e38\u7528: ls cat echo pwd date ps grep tar vi chmod netstat'",
                    "echo '\u8f93\u5165 exit \u9000\u51fa\u7ec8\u7aef'",
                    "echo ''"
                )
                rcFile.writeText(lines.joinToString("\n") + "\n")
            } catch (_: Throwable) { }
            // 2. 系统 sh 交互模式（-i）+ ENV 指向 rc 文件（横幅自动打印）
            val shPath = "/system/bin/sh"
            val cwd = "/data/data/com.nekotype.app"
            val env = arrayOf(
                "HOME=/data/data/com.nekotype.app",
                "TERM=xterm-256color",
                "PATH=/system/bin:/system/xbin:/system/bin",
                "PWD=" + cwd,
                "ENV=" + rcFile.absolutePath
            )
            val s = TerminalSession(shPath, cwd, arrayOf("sh", "-i"), env, 1000, this)
            session = s
            // 布局完成后 attach + updateSize，确保 PTY/子进程初始化
            tv.post {
                try {
                    if (tv.width > 0 && tv.height > 0) {
                        tv.attachSession(s)
                        tv.updateSize()
                    }
                    NekoLog.ok("内嵌终端已启动")
                } catch (t: Throwable) {
                    NekoLog.error("内嵌终端启动失败：${t.message}")
                    Toast.makeText(this@TerminalActivity, "终端启动失败：${t.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        } catch (t: Throwable) {
            NekoLog.error("内嵌终端启动失败：${t.message}")
            Toast.makeText(this@TerminalActivity, "终端启动失败：${t.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        terminalView?.let {
            it.postDelayed({
                // 进入页面自动弹出键盘（终端需要键盘输入）
                it.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT)
            }, 300)
        }
    }

    override fun onPause() {
        super.onPause()
        terminalView?.onScreenUpdated()
    }

    /** 键盘弹出/收起、旋转等配置变化时，重新计算终端行列数让画面跟着键盘走 */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        terminalView?.post {
            try {
                terminalView?.updateSize()
            } catch (_: Throwable) { }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            session?.finishIfRunning()
        } catch (_: Throwable) { }
    }

    /** 物理返回键：键盘开着先收键盘，再按一次退出 */
    override fun onBackPressed() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (imm.isAcceptingText) {
            imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        } else {
            super.onBackPressed()
        }
    }

    // ---------- TerminalSessionClient ----------

    override fun onTextChanged(changedSession: TerminalSession) { terminalView?.onScreenUpdated() }
    override fun onTitleChanged(changedSession: TerminalSession) { }
    override fun onSessionFinished(finishedSession: TerminalSession) {
        runOnUiThread {
            Toast.makeText(this@TerminalActivity, "终端已退出", Toast.LENGTH_SHORT).show()
            this@TerminalActivity.finish()
        }
    }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
        } catch (_: Throwable) { }
    }
    override fun onPasteTextFromClipboard(session: TerminalSession?) { }
    override fun onBell(session: TerminalSession) { }
    override fun onColorsChanged(session: TerminalSession) { terminalView?.onScreenUpdated() }
    override fun onTerminalCursorStateChange(state: Boolean) { }
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) { }
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String, message: String) { NekoLog.error("终端[$tag] $message") }
    override fun logWarn(tag: String, message: String) { NekoLog.warn("终端[$tag] $message") }
    override fun logInfo(tag: String, message: String) { }
    override fun logDebug(tag: String, message: String) { }
    override fun logVerbose(tag: String, message: String) { }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { NekoLog.error("终端[$tag] $message ${e.message}") }
    override fun logStackTrace(tag: String, e: Exception) { NekoLog.error("终端[$tag] ${e.message}") }

    // ---------- TerminalViewClient ----------

    override fun onScale(scale: Float): Float = scale
    override fun onSingleTapUp(e: MotionEvent) {
        // 单击屏幕：如果键盘没开就弹出来
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        terminalView?.let { imm.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT) }
    }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) { }
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() { }
}
