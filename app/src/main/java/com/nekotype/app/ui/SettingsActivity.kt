package com.nekotype.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import com.nekotype.app.R
import com.nekotype.app.accessibility.NekoTypeAccessibilityService
import com.nekotype.app.databinding.ActivitySettingsBinding
import com.nekotype.app.overlay.FloatingButtonService
import com.nekotype.app.prefs.AppPrefs
import com.nekotype.app.sys.SysPower
import com.nekotype.app.util.BgUtils
import com.nekotype.app.util.NekoLog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页：更换运行模式（基础/Shizuku/Root）、详细信息、版本信息、关于、支持与反馈。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        // 主题：super 前设置夜间模式（recreate 后保持）
        when (AppPrefs.themeMode) {
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        BgUtils.apply(binding.root)
        NekoLog.nav("进入设置页")

        // ---- 自定义背景 ----
        val bgPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                try {
                    val out = File(filesDir, "custom_bg.jpg")
                    contentResolver.openInputStream(uri)?.use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    }
                    AppPrefs.customBackgroundPath = out.absolutePath
                    BgUtils.apply(binding.root)
                    NekoLog.adjust("更换自定义背景成功")
                    toast(getString(R.string.u1))
                } catch (_: Throwable) {
                    toast(getString(R.string.u38))
                }
            }
        }
        binding.btnPickBg.setOnClickListener {
            try {
                bgPicker.launch("image/*")
            } catch (_: Throwable) {
                toast(getString(R.string.u33))
            }
        }
        binding.btnClearBg.setOnClickListener {
            AppPrefs.customBackgroundPath = ""
            BgUtils.apply(binding.root)
            NekoLog.adjust("恢复默认背景")
            toast(getString(R.string.u41))        }

        // ---- 外观（主题） ----
        binding.themeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val mode = when (checkedId) {
                    R.id.btnThemeDark -> "dark"
                    R.id.btnThemeLight -> "light"
                    else -> "system"
                }
                switchTheme(mode)
            }
        }

        // ---- 悬浮按钮大小/透明度 ----
        binding.slFabSize.value = AppPrefs.fabSizeDp.toFloat()
        binding.slFabOpacity.value = AppPrefs.fabOpacity.toFloat()
        binding.tvFabSize.text = "${AppPrefs.fabSizeDp}dp"
        binding.tvFabOpacity.text = "${AppPrefs.fabOpacity}%"
        binding.slFabSize.addOnChangeListener { _, value, _ ->
            val v = value.toInt()
            AppPrefs.fabSizeDp = v
            binding.tvFabSize.text = "${v}dp"
            // 实时轻量应用（不重建，避免抖动）
            FloatingButtonService.applyStyle(v, AppPrefs.fabOpacity)
        }
        binding.slFabSize.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) { }
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                NekoLog.adjust("悬浮按钮大小调整为 ${AppPrefs.fabSizeDp}dp")
            }
        })
        binding.slFabOpacity.addOnChangeListener { _, value, _ ->
            val v = value.toInt()
            AppPrefs.fabOpacity = v
            binding.tvFabOpacity.text = "${v}%"
            // 实时轻量应用（不重建，避免抖动）
            FloatingButtonService.applyStyle(AppPrefs.fabSizeDp, v)
        }
        binding.slFabOpacity.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) { }
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                NekoLog.adjust("悬浮按钮透明度调整为 ${AppPrefs.fabOpacity}%")
            }
        })

        // ---- 收起小圆点 ----
        binding.swFabCollapse.isChecked = AppPrefs.fabCollapseEnabled
        binding.swFabCollapse.setOnCheckedChangeListener { _, checked ->
            AppPrefs.fabCollapseEnabled = checked
            NekoLog.adjust(if (checked) "开启按钮收起小圆点" else "关闭按钮收起小圆点")
            FloatingButtonService.reload()
        }

        // ---- 柔光玻璃 ----
        binding.swFabGlass.isChecked = AppPrefs.fabGlassEnabled
        binding.swFabGlass.setOnCheckedChangeListener { _, checked ->
            AppPrefs.fabGlassEnabled = checked
            NekoLog.adjust(if (checked) "开启柔光玻璃效果" else "关闭柔光玻璃效果")
            FloatingButtonService.reload()
        }

        // ---- 开机自启 ----
        binding.swAutoStart.isChecked = AppPrefs.autoStartEnabled
        binding.swAutoStart.setOnCheckedChangeListener { _, checked ->
            if (!checked && AppPrefs.lockEnabled) {
                // 密码锁定：开机自启保持开启（防杀后台的一部分，重启后自动复活）
                NekoLog.warn("密码锁定中：开机自启保持开启")
                toast(getString(R.string.u20))
                binding.swAutoStart.isChecked = true
                return@setOnCheckedChangeListener
            }
            AppPrefs.autoStartEnabled = checked
            NekoLog.adjust(if (checked) "开启开机自启" else "关闭开机自启")
        }

        // ---- 数据导入导出 ----
        binding.btnExportConfig.setOnClickListener {
            val text = AppPrefs.exportConfigText()
            if (text.isEmpty()) {
                toast(getString(R.string.u40))
                return@setOnClickListener
            }
            // 先复制到剪贴板兜底，再弹出分享
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("nekotype_config", text))
            } catch (_: Throwable) { }
            NekoLog.info("导出配置")
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.u123))
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(send, getString(R.string.u124)))
        }
        binding.btnImportConfig.setOnClickListener { showImportDialog() }

        // ---- 应用黑名单 ----
        binding.btnBlacklist.setOnClickListener {
            NekoLog.nav("打开应用黑名单")
            startActivity(Intent(this, BlacklistActivity::class.java))
        }

        // ---- 支持与反馈 ----
        binding.ivSponsorQr.setOnClickListener { showSponsorDialog() }
        binding.btnFeedback.setOnClickListener {
            NekoLog.info("发起意见反馈（邮件）")
            sendFeedbackEmail()
        }
        binding.btnCopyGroup.setOnClickListener {
            NekoLog.info("复制 QQ 频道号：4ldb0biz5")
            copyToClipboard("4ldb0biz5")
            tryOpenQqChannel()
        }

        refreshTheme()
        refreshVersion()
        refreshStatsDaily()
    }

    /** 右上角菜单：「语言」+「日志」入口（语言在左，日志在右） */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 2, 0, getString(R.string.u125)).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 1, 1, getString(R.string.u126)).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 2) {
            showLanguageDialog()
            return true
        }
        if (item.itemId == 1) {
            NekoLog.nav("打开日志页")
            startActivity(Intent(this, LogActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /** 语言切换对话框：简体中文 / 繁體中文 / English（确定后应用） */
    private fun showLanguageDialog() {
        val tags = arrayOf("zh", "zh-TW", "en")
        val langs = arrayOf("简体中文", "繁體中文", "English")
        val checked = when (AppCompatDelegate.getApplicationLocales().toLanguageTags()) {
            "zh-TW" -> 1
            "en" -> 2
            else -> 0
        }
        var chosen = checked
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.u52))
            .setSingleChoiceItems(langs, checked) { _, which ->
                chosen = which
            }
            .setPositiveButton(getString(R.string.u71)) { _, _ ->
                try {
                    AppCompatDelegate.setApplicationLocales(
                        androidx.core.os.LocaleListCompat.forLanguageTags(tags[chosen])
                    )
                    NekoLog.adjust("语言切换为：${langs[chosen]}")
                } catch (_: Throwable) {
                    toast(getString(R.string.u14))
                }
            }
            .setNegativeButton(getString(R.string.u72), null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshTheme()
        refreshStatsDaily()
        // Root 检测会拉起 su 进程，放后台线程
        lifecycleScope.launch {
            val root = withContext(Dispatchers.IO) { SysPower.isRootAvailable() }
            binding.tvDetails.text = buildDetails(root)
        }
    }

    override fun onDestroy() {
        NekoLog.nav("退出设置页")
        super.onDestroy()
    }

    /** 每日统计：今日 + 最近 7 天 */
    private fun refreshStatsDaily() {
        val stats = AppPrefs.dailyStats
        val fmt = java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault())
        val today = fmt.format(java.util.Date())
        val sb = StringBuilder()
        sb.append(getString(R.string.u127, stats[today] ?: 0, AppPrefs.transformCount))
        val cal = java.util.Calendar.getInstance()
        for (i in 6 downTo 0) {
            cal.timeInMillis = System.currentTimeMillis()
            cal.add(java.util.Calendar.DAY_OF_YEAR, -i)
            val day = fmt.format(cal.time)
            sb.append(getString(R.string.u128, day, stats[day] ?: 0))
        }
        binding.tvStatsDaily.text = sb.toString()
    }

    /** 导入配置对话框（可从剪贴板粘贴） */
    private fun showImportDialog() {
        val et = EditText(this).apply {
            hint = getString(R.string.u129)
            minLines = 4
            maxLines = 6
            gravity = android.view.Gravity.TOP
        }
        // 预填剪贴板内容
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.let {
                if (it.startsWith("{") && it.contains("NekoType")) et.setText(it)
            }
        } catch (_: Throwable) { }

        // 自定义布局：输入框 + 导入/取消按钮（按钮固定可见，不受系统对话框按钮渲染问题影响）
        var dialogRef: AlertDialog? = null
        val btnImport = com.google.android.material.button.MaterialButton(this).apply {
            text = getString(R.string.u130)
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val text = et.text.toString().trim()
                if (text.isEmpty()) { toast(getString(R.string.u9)); return@setOnClickListener }
                val err = AppPrefs.importConfigText(text)
                if (err != null) {
                    NekoLog.error("导入配置失败：$err")
                    toast(err)
                } else {
                    NekoLog.ok("配置导入成功")
                    toast(getString(R.string.u0))
                    applyThemeMode()
                    refreshStatsDaily()
                    BgUtils.apply(binding.root)
                    dialogRef?.dismiss()
                }
            }
        }
        val btnCancel = com.google.android.material.button.MaterialButton(this).apply {
            text = getString(R.string.u72)
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { dialogRef?.dismiss() }
        }
        val btnRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            addView(btnImport)
            addView(btnCancel)
        }
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(24, 8, 24, 8)
            addView(et)
            addView(btnRow)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.u39))
            .setMessage(getString(R.string.u42))
            .setView(content)
            .create()
        dialogRef = dialog
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        )
        dialog.show()
    }

    /** 切换主题：保存 + 应用 + 刷新选中态（setDefaultNightMode 变化触发重建，主题在 onCreate 重新应用） */
    private fun switchTheme(mode: String) {
        AppPrefs.themeMode = mode
        applyThemeMode()
        NekoLog.adjust("外观切换为：${themeLabel(mode)}")
        refreshTheme()
    }

    /** 应用主题（浅色 / 深色 / 跟随系统） */
    private fun applyThemeMode() {
        when (AppPrefs.themeMode) {
            "dark" -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                setTheme(R.style.Theme_NekoType)
            }
            "light" -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                setTheme(R.style.Theme_NekoType)
            }
            else -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                setTheme(R.style.Theme_NekoType)
            }
        }
    }

    private fun themeLabel(m: String): String = when (m) {
        "dark" -> getString(R.string.u131)
        "light" -> getString(R.string.u132)
        else -> getString(R.string.u133)
    }

    private fun refreshTheme() {
        binding.themeGroup.check(
            when (AppPrefs.themeMode) {
                "dark" -> R.id.btnThemeDark
                "light" -> R.id.btnThemeLight
                else -> R.id.btnThemeSystem
            }
        )
        binding.tvThemeHint.text = when (AppPrefs.themeMode) {
            "dark" -> getString(R.string.u134)
            "light" -> getString(R.string.u135)
            else -> getString(R.string.u136)
        }
    }

    private fun buildDetails(rootOk: Boolean): String {
        val sb = StringBuilder()
        sb.append(getString(R.string.u137)).append(if (Settings.canDrawOverlays(this)) getString(R.string.u138) else getString(R.string.u139)).append('\n')
        sb.append(getString(R.string.u140)).append(if (isAccessibilityEnabled()) getString(R.string.u141) else getString(R.string.u142)).append('\n')
        sb.append(getString(R.string.u143)).append(if (SysPower.isIgnoringBatteryOptimizations()) getString(R.string.u144) else getString(R.string.u145)).append('\n')
        sb.append(getString(R.string.u146)).append(if (SysPower.isShizukuAvailable()) getString(R.string.u147) else getString(R.string.u148)).append('\n')
        sb.append(getString(R.string.u149)).append(if (rootOk) getString(R.string.u147) else getString(R.string.u148)).append('\n')
        sb.append(getString(R.string.u150)).append(if (SysPower.isDeviceAdminActive()) getString(R.string.u151) else getString(R.string.u152)).append('\n')
        sb.append(getString(R.string.u153)).append(if (AppPrefs.serviceEnabled) getString(R.string.u97) else getString(R.string.u98)).append('\n')
        sb.append(getString(R.string.u154, AppPrefs.transformCount))
        return sb.toString()
    }

    private fun refreshVersion() {
        val info = packageManager.getPackageInfo(packageName, 0)
        val versionName = info.versionName ?: "?"
        val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toString() else info.versionCode.toString()
        binding.tvVersion.text = getString(R.string.u155, versionName, versionCode, packageName)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val cmp = "$packageName/${NekoTypeAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.split(":").any { it.equals(cmp, ignoreCase = true) }
    }

    // ---------- 支持与反馈 ----------

    /** 赞助码放大查看 */
    private fun showSponsorDialog() {
        val img = ImageView(this).apply {
            setImageResource(R.drawable.sponsor_qr)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.u44))
            .setMessage(getString(R.string.u31))
            .setView(img)
            .setPositiveButton(getString(R.string.u156), null)
            .show()
    }

    /** 建议反馈：跳转邮件并自动填充，发送至 TR114512@qq.com */
    private fun sendFeedbackEmail() {
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            val version = "${info.versionName} (${if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode})"
            val subject = getString(R.string.u157)
            val body = getString(R.string.u158, version, Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, if (SysPower.isShizukuPermissionGranted()) getString(R.string.u109) else getString(R.string.u108))
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:TR114512@qq.com")
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.u159)))
        } catch (_: Throwable) {
            toast(getString(R.string.u58))
            copyToClipboard("TR114512@qq.com")
        }
    }

    /** 复制频道号，并尝试拉起 QQ 频道 */
    private fun tryOpenQqChannel() {
        try {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://pd.qq.com/s/4ldb0biz5?b=9")
            )
            startActivity(intent)
        } catch (_: Throwable) {
            toast(getString(R.string.u57))
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("nekotype", text))
            toast(getString(R.string.u15, text))
        } catch (_: Throwable) { }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
