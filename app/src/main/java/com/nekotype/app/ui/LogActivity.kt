package com.nekotype.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Process
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nekotype.app.R
import com.nekotype.app.databinding.ActivityLogBinding
import com.nekotype.app.prefs.AppPrefs
import com.nekotype.app.util.BgUtils
import com.nekotype.app.util.NekoLog

/**
 * 日志页：
 * - 系统日志：直接抓取本应用进程的底层 logcat（含崩溃栈），可开关【轻汉化】把常见标签转成中文；
 * - 应用日志：彩色分级显示；
 * - 按钮：刷新 / 缩小字体 / 放大字体 / 复制 / 清空；顶部有「记录」「轻汉化」两个开关。
 */
class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding

    private val logListener = { refreshAppLog() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        BgUtils.apply(binding.root)
        NekoLog.nav("打开日志页")

        // 字体大小
        applyFontSize()

        // 记录开关
        binding.swLogEnabled.isChecked = AppPrefs.logEnabled
        binding.swLogEnabled.setOnCheckedChangeListener { _, checked ->
            AppPrefs.logEnabled = checked
            toast(if (checked) getString(R.string.u116) else getString(R.string.u117))
            loadSysLog()
        }

        // 轻汉化开关
        binding.swLocalize.isChecked = AppPrefs.logLocalize
        binding.swLocalize.setOnCheckedChangeListener { _, checked ->
            AppPrefs.logLocalize = checked
            NekoLog.info(if (checked) "开启终端轻汉化" else "关闭终端轻汉化")
            loadSysLog()
        }

        // 模式切换
        binding.logModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val isSys = checkedId == R.id.btnModeSys
                binding.scrollSysLog.visibility = if (isSys) View.VISIBLE else View.GONE
                binding.scrollAppLog.visibility = if (isSys) View.GONE else View.VISIBLE
                if (isSys) loadSysLog() else refreshAppLog()
            }
        }
        binding.logModeGroup.check(R.id.btnModeSys)

        // 操作按钮
        binding.btnRefreshLog.setOnClickListener { loadSysLog() }
        binding.btnShrinkFont.setOnClickListener {
            AppPrefs.logFontSize = AppPrefs.logFontSize - 1f
            applyFontSize()
            loadSysLog()
            NekoLog.info("终端字体缩小为 ${AppPrefs.logFontSize.toInt()}sp")
        }
        binding.btnGrowFont.setOnClickListener {
            AppPrefs.logFontSize = AppPrefs.logFontSize + 1f
            applyFontSize()
            loadSysLog()
            NekoLog.info("终端字体放大为 ${AppPrefs.logFontSize.toInt()}sp")
        }
        binding.btnCopyLog.setOnClickListener { copyCurrentLog() }
        binding.btnClearLog.setOnClickListener {
            if (binding.scrollAppLog.visibility == View.VISIBLE) {
                NekoLog.clear()
                refreshAppLog()
                toast(getString(R.string.u4))
            } else {
                toast(getString(R.string.u37))
            }
        }

        loadSysLog()
        refreshAppLog()
    }

    override fun onStart() {
        super.onStart()
        NekoLog.registerListener(logListener)
    }

    override fun onStop() {
        NekoLog.unregisterListener(logListener)
        super.onStop()
    }

    override fun onDestroy() {
        NekoLog.nav("退出日志页")
        super.onDestroy()
    }

    private fun applyFontSize() {
        val size = AppPrefs.logFontSize
        binding.tvSysLog.textSize = size
        binding.tvLog.textSize = size
    }

    // ---------- 系统日志（logcat） ----------

    /** 直接抓取本应用进程的底层系统日志（logcat），可开关轻汉化 */
    private fun loadSysLog() {
        if (!AppPrefs.logEnabled) {
            binding.tvSysLog.text = getString(R.string.u118)
            return
        }
        binding.tvSysLog.text = getString(R.string.u119)
        Thread {
            val text = try {
                val pid = Process.myPid()
                val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "--pid=$pid", "-t", "500"))
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor()
                if (out.isBlank()) getString(R.string.u120)
                else if (AppPrefs.logLocalize) localizeLogcat(out) else out
            } catch (t: Throwable) {
                getString(R.string.u121, t.message)
            }
            runOnUiThread { binding.tvSysLog.text = text }
        }.start()
    }

    /** 轻汉化：把常见系统标签转成中文，保留原始内容 */
    private fun localizeLogcat(raw: String): String {
        var s = raw
        val map = listOf(
            "FATAL EXCEPTION" to "致命异常",
            "AndroidRuntime" to "崩溃",
            "Caused by" to "原因",
            "Process:" to "进程：",
            "at com.nekotype.app" to "位置（NekoType）：",
            "ActivityTaskManager" to "任务管理",
            "WindowManager" to "窗口管理",
            "nekotype_fg" to "悬浮服务",
            "NekoTypeAccessibilityService" to "无障碍服务",
            "FloatingButtonService" to "悬浮按钮服务",
            "System.err" to "系统错误",
            "dalvikvm" to "虚拟机",
            "art" to "运行时"
        )
        map.forEach { (k, v) -> s = s.replace(k, v) }
        return s
    }

    // ---------- 应用日志（彩色） ----------

    private fun refreshAppLog() {
        val logs = NekoLog.entries()
        if (logs.isEmpty()) {
            binding.tvLog.text = getString(R.string.u122)
            binding.tvLog.setTextColor(ContextCompat.getColor(this, R.color.fg_2))
            return
        }
        val sb = StringBuilder()
        val spans = mutableListOf<Triple<Int, Int, Int>>()
        logs.forEach { e ->
            val line = "[${e.timeText()}] ${e.msg}\n"
            val start = sb.length
            sb.append(line)
            val colorRes = when (e.level) {
                NekoLog.OK -> R.color.ok_green
                NekoLog.WARN -> R.color.warn_amber
                NekoLog.ERROR -> R.color.log_error
                NekoLog.NAV -> R.color.log_nav
                NekoLog.ADJUST -> R.color.log_adjust
                NekoLog.RULE -> R.color.log_rule
                else -> R.color.text_secondary
            }
            spans.add(Triple(start, sb.length, colorRes))
        }
        val ss = SpannableString(sb.toString())
        spans.forEach { (s, en, c) ->
            ss.setSpan(ForegroundColorSpan(ContextCompat.getColor(this, c)), s, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        binding.tvLog.text = ss
        binding.tvLog.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        binding.tvLog.post {
            (binding.tvLog.parent as? android.widget.ScrollView)?.fullScroll(View.FOCUS_DOWN)
        }
    }

    // ---------- 复制 ----------

    private fun copyCurrentLog() {
        val text = if (binding.scrollSysLog.visibility == View.VISIBLE) {
            binding.tvSysLog.text?.toString().orEmpty()
        } else {
            NekoLog.entries().joinToString("\n") { "[${it.timeText()}] ${it.msg}" }
        }
        if (text.isBlank() || text == getString(R.string.u118) || text == getString(R.string.u120) || text == getString(R.string.u122)) {
            toast(getString(R.string.u55))
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("nekotype_log", text))
        toast(getString(R.string.u47))
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
