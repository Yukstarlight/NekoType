package com.nekotype.app.ui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nekotype.app.accessibility.NekoTypeAccessibilityBridge
import com.nekotype.app.R
import com.nekotype.app.databinding.ActivityDevBinding
import com.nekotype.app.overlay.FloatingButtonService
import com.nekotype.app.prefs.AppPrefs
import com.nekotype.app.sys.SysPower
import com.nekotype.app.transform.TextTransformEngine
import com.nekotype.app.util.BgUtils
import com.nekotype.app.util.NekoLog
import com.nekotype.app.util.TamperGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import com.nekotype.app.util.setTextSizeDimen

/**
 * 开发者模式（隐藏功能）：设置页「关于」标题连点 7 次，经"下载额外组件"确认进入，开启后常驻。
 *
 * 内容四块：
 * 1. 应用信息：版本 / 进程 / 无障碍 / 悬浮球 / Shizuku / 设备 / 签名校验，可复制诊断信息与配置
 * 2. 日志：系统日志（Shizuku 全系统、无 Shizuku 退回本进程）、崩溃日志、应用内日志、清空
 * 3. 应用黑名单·模糊搜索：多关键词 / 子串 / 子序列匹配 + 相关度排序，点结果直接增删黑名单
 * 4. 维护与重置：重载悬浮球、重绑 Shizuku、重置免责声明、清空当前预设规则、重启应用
 *
 * 本页为调试工具，文案直接使用中文（与内置终端同风格，不进多语言资源）。
 */
class DevActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDevBinding

    /** 已安装应用缓存（包名 → 名称），首次使用时加载 */
    private var appCache: List<Triple<String, String, ApplicationInfo>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDevBinding.inflate(layoutInflater)
        setContentView(binding.root)
        try { BgUtils.apply(binding.root) } catch (_: Throwable) { }

        binding.btnDevInfo.setOnClickListener { refreshInfo() }
        binding.btnDevCopyDiag.setOnClickListener { copyDiagnostics() }
        binding.btnDevCopyConfig.setOnClickListener {
            copyText(AppPrefs.exportConfigText(), getString(R.string.dev_config_what))
        }

        // 日志
        binding.btnDevLogcat.setOnClickListener { grabLogcat(crash = false) }
        binding.btnDevCrash.setOnClickListener { grabLogcat(crash = true) }
        binding.btnDevAppLog.setOnClickListener { showAppLog() }
        binding.btnDevClearLog.setOnClickListener {
            NekoLog.clear()
            toast(getString(R.string.dev_toast_log_cleared))
        }

        // 黑名单模糊搜索
        binding.etDevAppQuery.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { updateAppQueryHint(s?.toString().orEmpty()) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
        })
        binding.btnDevAppSearch.setOnClickListener { showAppSearchResult(custom = null) }
        binding.btnDevAppBlacklisted.setOnClickListener { showAppSearchResult(custom = "__blacklisted__") }

        // 维护与重置
        binding.btnDevReloadFab.setOnClickListener {
            FloatingButtonService.reload()
            toast(getString(R.string.dev_toast_reloaded))
        }
        binding.btnDevRebindShizuku.setOnClickListener {
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { SysPower.devResetShizuku() }
                showLog(getString(R.string.dev_rebind_title), r + "\n\n" + SysPower.devChannelTrace())
            }
        }
        binding.btnDevShizukuTrace.setOnClickListener {
            showLog(getString(R.string.dev_trace_title), SysPower.devChannelTrace())
        }
        binding.btnDevResetDisclaimer.setOnClickListener {
            AppPrefs.disclaimerAccepted = false
            toast(getString(R.string.dev_toast_disclaimer_reset))
        }
        binding.btnDevClearRules.setOnClickListener { confirmClearRules() }
        binding.btnDevRestart.setOnClickListener { restartApp() }
        binding.btnDevFactoryReset.setOnClickListener { confirmFactoryReset() }

        // 调试工具
        binding.btnDevTree.setOnClickListener {
            lifecycleScope.launch {
                val tree = withContext(Dispatchers.IO) { NekoTypeAccessibilityBridge.dumpWindowTree() }
                showLog(getString(R.string.dev_tree_title), tree ?: "（无障碍服务未连接）")
            }
        }
        binding.btnDevPerf.setOnClickListener { runRuleBenchmark() }

        refreshInfo()
        updateAppQueryHint("")
    }

    // ---------- 应用信息 ----------

    private fun infoText(): String {
        val pm = packageManager
        val pkg = packageName
        val pi = pm.getPackageInfo(pkg, 0)
        val vc = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        val ai = pi.applicationInfo
        val a11yEnabled = try {
            val enabled = android.provider.Settings.Secure.getString(
                contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            enabled.split(":").any { it.startsWith(pkg) }
        } catch (_: Throwable) { false }
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return buildString {
            appendLine("版本: ${pi.versionName} (versionCode $vc)")
            appendLine("包名: $pkg")
            appendLine("进程: PID ${android.os.Process.myPid()}  UID ${android.os.Process.myUid()}")
            appendLine("targetSdk: ${ai.targetSdkVersion}   minSdk: ${Build.VERSION.SDK_INT}")
            appendLine("APK: ${ai.sourceDir}")
            appendLine("安装于: ${fmt.format(java.util.Date(pi.firstInstallTime))}   更新于: ${fmt.format(java.util.Date(pi.lastUpdateTime))}")
            appendLine("签名校验: ${if (TamperGuard.isSignatureValid(this@DevActivity)) "正常（与发布签名一致）" else "不匹配（非官方包）"}")
            appendLine("Hook 框架: ${if (TamperGuard.hasHookFramework()) "检测到" else "未检测到"}")
            appendLine()
            appendLine("无障碍: 系统开关=${if (a11yEnabled) "开" else "关"}  服务连接=${if (NekoTypeAccessibilityBridge.isServiceReady()) "已连接" else "未连接"}")
            appendLine("悬浮球服务: ${if (FloatingButtonService.isRunning()) "运行中" else "未运行"}")
            appendLine("Shizuku: 存活=${if (SysPower.isShizukuAvailable()) "是" else "否"}  权限=${if (SysPower.isShizukuPermissionGranted()) "已授予" else "未授予"}")
            appendLine("特权通道: ${if (SysPower.privilegedChannelReady()) "可用" else "不可用"}  最近通道=${SysPower.lastChannelUsed}")
            appendLine("UserService 长连接: ${if (SysPower.isUserServiceConnected()) "已连接" else "未连接（会自动降级 newProcess 直连）"}")
            appendLine("Shizuku 最近错误: ${SysPower.lastShizukuError ?: "无"}")
            appendLine("电池白名单: ${if (SysPower.isIgnoringBatteryOptimizations()) "已忽略" else "未忽略"}")
            appendLine()
            appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})   Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("屏幕: ${resources.displayMetrics.widthPixels}×${resources.displayMetrics.heightPixels} @${resources.displayMetrics.densityDpi}dpi")
            appendLine("预设: ${AppPrefs.activePresetName()}  规则: ${AppPrefs.rules().size} 条  累计变换: ${AppPrefs.transformCount}")
            appendLine("开关: 启动服务=${AppPrefs.serviceEnabled}  强制篡改=${AppPrefs.forceKeyboardEnabled}  静默修改=${AppPrefs.silentModifyEnabled}  黑名单=${AppPrefs.blacklistEnabled}")
        }
    }

    private fun refreshInfo() {
        binding.tvDevInfo.text = infoText()
    }

    /** 复制诊断信息：应用信息 + 最近 30 条应用内日志（反馈时直接粘给对方） */
    private fun copyDiagnostics() {
        val logs = NekoLog.entries().takeLast(30)
            .joinToString("\n") { "${it.timeText()}  [${levelName(it.level)}]  ${it.msg}" }
        val text = infoText() + "\n---------- 最近日志 ----------\n" + logs.ifEmpty { "（暂无）" }
        copyText(text, getString(R.string.dev_diag_what))
    }

    // ---------- 日志 ----------

    private fun grabLogcat(crash: Boolean) {
        val filter = binding.etDevFilter.text?.toString()?.trim().orEmpty()
        binding.btnDevLogcat.isEnabled = false
        binding.btnDevCrash.isEnabled = false
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { SysPower.devLogcat(filter, crash) }
            binding.btnDevLogcat.isEnabled = true
            binding.btnDevCrash.isEnabled = true
            val title = if (crash) getString(R.string.dev_crash_title) else getString(R.string.dev_logcat_title)
            val body = if (r.success) {
                getString(R.string.dev_source_label, if (r.channel == "shizuku") getString(R.string.dev_source_shizuku) else getString(R.string.dev_source_local)) + "\n" +
                        getString(R.string.dev_filter_label, filter.ifEmpty { getString(R.string.dev_filter_none) }) + "\n\n" + r.output
            } else {
                getString(R.string.dev_grab_failed, r.output)
            }
            showLog(title, body)
        }
    }

    private fun showAppLog() {
        val entries = NekoLog.entries()
        val body = if (entries.isEmpty()) getString(R.string.dev_applog_empty)
        else entries.joinToString("\n") { "${it.timeText()}  [${levelName(it.level)}]  ${it.msg}" }
        showLog(getString(R.string.dev_applog_title, entries.size), body)
    }

    private fun levelName(level: Int): String = when (level) {
        NekoLog.INFO -> "信息"
        NekoLog.OK -> "成功"
        NekoLog.WARN -> "警告"
        NekoLog.ERROR -> "错误"
        NekoLog.NAV -> "页面"
        NekoLog.ADJUST -> "设置"
        NekoLog.RULE -> "规则"
        else -> "$level"
    }

    // ---------- 应用黑名单 · 模糊搜索 ----------

    private fun loadApps(): List<Triple<String, String, ApplicationInfo>> {
        appCache?.let { return it }
        val pm = packageManager
        val self = packageName
        val out = ArrayList<Triple<String, String, ApplicationInfo>>()
        try {
            pm.getInstalledApplications(PackageManager.MATCH_ALL).forEach { info ->
                if (info.packageName == self) return@forEach
                if (pm.getLaunchIntentForPackage(info.packageName) == null) return@forEach
                val label = try { pm.getApplicationLabel(info).toString() } catch (_: Throwable) { info.packageName }
                out.add(Triple(info.packageName, label, info))
            }
        } catch (_: Throwable) { }
        out.sortBy { it.second.lowercase(Locale.getDefault()) }
        appCache = out
        return out
    }

    /** 模糊匹配打分：0 = 不命中。多关键词需全部命中；前缀 > 子串 > 包名 > 子序列 */
    private fun fuzzyScore(query: String, label: String, pkg: String): Int {
        val q = query.trim()
        if (q.isEmpty()) return 1
        val l = label.lowercase(Locale.getDefault())
        val p = pkg.lowercase(Locale.getDefault())
        val terms = q.lowercase(Locale.getDefault()).split(" ", "\u3000").filter { it.isNotEmpty() }
        if (terms.isEmpty()) return 1
        var total = 0
        for (t in terms) {
            val s = when {
                l == t -> 100
                l.startsWith(t) -> 80
                l.contains(t) -> 60
                p.contains(t) -> 45
                isSubsequence(t, l) -> 30
                isSubsequence(t, p) -> 18
                else -> return 0
            }
            total += s
        }
        return total
    }

    private fun isSubsequence(needle: String, hay: String): Boolean {
        var i = 0
        for (c in hay) {
            if (i < needle.length && c == needle[i]) i++
            if (i == needle.length) return true
        }
        return needle.isEmpty()
    }

    private fun searchApps(query: String, onlyBlacklisted: Boolean): List<Pair<String, String>> {
        val list = loadApps()
        val black = AppPrefs.blacklist()
        val filtered = if (onlyBlacklisted) {
            list.filter { black.contains(it.first) }
                .filter { query.isEmpty() || fuzzyScore(query, it.second, it.first) > 0 }
        } else {
            list.filter { fuzzyScore(query, it.second, it.first) > 0 }
        }
        val sorted = if (query.isBlank()) filtered
        else filtered.sortedByDescending { fuzzyScore(query, it.second, it.first) }
        return sorted.map { it.first to it.second }
    }

    private fun updateAppQueryHint(query: String) {
        try {
            val n = searchApps(query, onlyBlacklisted = false).size
            binding.tvDevAppHint.text = if (query.isBlank()) {
                getString(R.string.dev_bl_summary, loadApps().size, AppPrefs.blacklist().size)
            } else {
                getString(R.string.dev_bl_matched, n)
            }
        } catch (_: Throwable) { }
    }

    /** 结果弹窗：点击条目即切换黑名单状态，列表实时刷新 */
    private fun showAppSearchResult(custom: String?) {
        val onlyBlack = custom == "__blacklisted__"
        val query = binding.etDevAppQuery.text?.toString()?.trim().orEmpty()
        val results = searchApps(query, onlyBlack)
        if (results.isEmpty()) {
            toast(if (onlyBlack) getString(R.string.dev_bl_empty) else getString(R.string.dev_bl_nomatch))
            return
        }
        val black = AppPrefs.blacklist()
        val items = results.map { (pkg, label) ->
            val mark = if (black.contains(pkg)) "✓" else "○"
            "$label\n$pkg   $mark"
        }.toTypedArray()

        fun open() {
            NekoDialog.builder(this)
                .setTitle(if (onlyBlack) getString(R.string.dev_bl_only_title, results.size) else getString(R.string.dev_bl_result_title, results.size))
                .setItems(items) { _, which ->
                    val pkg = results[which].first
                    if (AppPrefs.blacklist().contains(pkg)) {
                        AppPrefs.removeBlacklist(pkg)
                        toast(getString(R.string.dev_bl_removed, results[which].second))
                    } else {
                        AppPrefs.addBlacklist(pkg)
                        toast(getString(R.string.dev_bl_added, results[which].second))
                    }
                    // 切换后刷新列表（保持在同一批结果里继续操作）
                    showAppSearchResult(custom)
                }
                .setNegativeButton(com.nekotype.app.R.string.i58, null)
                .show()
        }
        open()
    }

    // ---------- 维护与重置 ----------

    /** 规则引擎性能基准：预热 20 次 + 计时 200 次，给出最快/中位/平均/最慢与吞吐 */
    private fun runRuleBenchmark() {
        binding.btnDevPerf.isEnabled = false
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                try {
                    val rules = AppPrefs.rules()
                    val enabled = rules.count { it.enabled }
                    val sample = "这是一段用于性能测试的中文输入文本，hello world 12345，哈哈，晚安。"
                    repeat(20) { TextTransformEngine.transform(sample) } // 预热 JIT
                    val n = 200
                    val times = LongArray(n)
                    for (i in 0 until n) {
                        val t0 = System.nanoTime()
                        TextTransformEngine.transform(sample)
                        times[i] = System.nanoTime() - t0
                    }
                    val total = times.sum()
                    times.sort()
                    buildString {
                        appendLine("规则总数: ${rules.size}（启用 $enabled，停用 ${rules.size - enabled}）")
                        appendLine("按等级全局执行: ${AppPrefs.priorityGlobalEnabled}")
                        appendLine("字符间加空格: ${AppPrefs.styleSpaced}   转大写: ${AppPrefs.styleUpper}   随机颜文字: ${AppPrefs.emoticonEnabled}")
                        appendLine("样本长度: ${sample.length} 字")
                        appendLine("循环: $n 次（另 20 次预热不计）")
                        appendLine()
                        appendLine("最快: ${"%.1f".format(times.first() / 1000.0)} µs")
                        appendLine("中位: ${"%.1f".format(times[n / 2] / 1000.0)} µs")
                        appendLine("平均: ${"%.1f".format(total / n / 1000.0)} µs")
                        appendLine("最慢: ${"%.1f".format(times.last() / 1000.0)} µs")
                        appendLine("吞吐: ${if (total > 0) n * 1_000_000_000L / total else 0} 次/秒")
                        appendLine()
                        appendLine("参考：单次改写耗时远小于打字间隔（≥100ms）时手感无差别。")
                    }
                } catch (t: Throwable) {
                    "基准测试失败: ${t.javaClass.simpleName}: ${t.message}"
                }
            }
            binding.btnDevPerf.isEnabled = true
            showLog(getString(R.string.dev_perf_title), report)
        }
    }

    /** 恢复出厂：第一次确认（说明影响范围） */
    private fun confirmFactoryReset() {
        NekoDialog.builder(this)
            .setTitle(getString(R.string.dev_factory_title))
            .setMessage(getString(R.string.dev_factory_msg))
            .setPositiveButton(getString(R.string.dev_confirm_ok)) { _, _ -> confirmFactoryResetFinal() }
            .setNegativeButton(getString(R.string.i58), null)
            .show()
    }

    /** 恢复出厂：第二次确认（不可撤销） */
    private fun confirmFactoryResetFinal() {
        NekoDialog.builder(this)
            .setTitle(getString(R.string.dev_factory_confirm_title))
            .setMessage(getString(R.string.dev_factory_confirm_msg))
            .setPositiveButton(getString(R.string.dev_factory_ok)) { _, _ -> doFactoryReset() }
            .setNegativeButton(getString(R.string.i58), null)
            .show()
    }

    /** 真正清空：先把当前配置复制到剪贴板备份，再清数据 + 清日志 + 重启应用 */
    private fun doFactoryReset() {
        try { copyText(AppPrefs.exportConfigText(), getString(R.string.dev_config_what)) } catch (_: Throwable) { }
        try { NekoLog.clear() } catch (_: Throwable) { }
        AppPrefs.factoryReset(keepDevMode = true)
        toast(getString(R.string.dev_factory_done))
        android.os.Handler(mainLooper).postDelayed({ restartApp() }, 500)
    }

    private fun confirmClearRules() {
        val count = AppPrefs.rules().size
        if (count == 0) { toast(getString(R.string.dev_no_rules)); return }
        NekoDialog.builder(this)
            .setTitle(getString(R.string.dev_confirm_title))
            .setMessage(getString(R.string.dev_confirm_msg, AppPrefs.activePresetName(), count))
            .setPositiveButton(getString(R.string.dev_confirm_ok)) { _, _ ->
                AppPrefs.rules().forEach { AppPrefs.removeRule(it.id) }
                NekoLog.rule("开发者模式：清空当前预设全部规则（$count 条）")
                toast(getString(R.string.dev_cleared, count))
                refreshInfo()
            }
            .setNegativeButton(com.nekotype.app.R.string.u72, null)
            .show()
    }

    /** 重启应用：闹钟 0.5 秒后拉起启动页，再杀掉当前进程 */    private fun restartApp() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val i = Intent(this, SplashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(
                this, 1001, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.set(AlarmManager.RTC, System.currentTimeMillis() + 500, pi)
            toast(getString(R.string.dev_restarting))
            android.os.Handler(mainLooper).postDelayed({
                android.os.Process.killProcess(android.os.Process.myPid())
            }, 250)
        } catch (t: Throwable) {
            toast(getString(R.string.dev_restart_failed, t.message ?: ""))
        }
    }

    // ---------- 结果展示 ----------

    /** 统一结果弹窗：等宽滚动文本 + 复制 / 分享 / 关闭 */
    private fun showLog(title: String, body: String) {
        val tv = android.widget.TextView(this).apply {
            text = body
            setTextSizeDimen(R.dimen.ts_11_5)
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            setLineSpacing(0f, 1.2f)
        }
        val dialog = NekoDialog.builder(this)
            .setTitle(title)
            .setView(NekoDialog.scroll(NekoDialog.column(this, tv)))
            .setPositiveButton(getString(R.string.dev_copy)) { _, _ -> copyText(body, title) }
            .setNeutralButton(getString(R.string.dev_share)) { _, _ -> shareText(title, body) }
            .setNegativeButton(getString(R.string.i58), null)
            .create()
        dialog.show()
        try {
            val dm = resources.displayMetrics
            dialog.window?.setLayout((dm.widthPixels * 0.94f).toInt(), (dm.heightPixels * 0.88f).toInt())
        } catch (_: Throwable) { }
    }

    private fun copyText(text: String, what: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("dev", text))
            toast(getString(R.string.dev_copy_done, what, text.length))
        } catch (_: Throwable) { }
    }

    private fun shareText(title: String, text: String) {
        try {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, text.take(200_000))
            }
            startActivity(Intent.createChooser(i, getString(R.string.dev_share) + " " + title))
        } catch (_: Throwable) { }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
