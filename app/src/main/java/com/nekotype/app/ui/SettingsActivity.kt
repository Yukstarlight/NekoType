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
                    toast("自定义背景已应用")
                } catch (_: Throwable) {
                    toast("背景设置失败")
                }
            }
        }
        binding.btnPickBg.setOnClickListener {
            try {
                bgPicker.launch("image/*")
            } catch (_: Throwable) {
                toast("无法打开图片选择器")
            }
        }
        binding.btnClearBg.setOnClickListener {
            AppPrefs.customBackgroundPath = ""
            BgUtils.apply(binding.root)
            NekoLog.adjust("恢复默认背景")
            toast("已恢复默认背景")        }

        // ---- 外观（主题） ----
        binding.themeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                AppPrefs.themeMode = when (checkedId) {
                    R.id.btnThemeDark -> "dark"
                    R.id.btnThemeLight -> "light"
                    else -> "system"
                }
                val mode = when (AppPrefs.themeMode) {
                    "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                AppCompatDelegate.setDefaultNightMode(mode)
                NekoLog.adjust("外观切换为：${themeLabel(AppPrefs.themeMode)}")
                refreshTheme()
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
            AppPrefs.autoStartEnabled = checked
            NekoLog.adjust(if (checked) "开启开机自启" else "关闭开机自启")
        }

        // ---- 数据导入导出 ----
        binding.btnExportConfig.setOnClickListener {
            val text = AppPrefs.exportConfigText()
            if (text.isEmpty()) {
                toast("导出失败")
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
                putExtra(Intent.EXTRA_SUBJECT, "NekoType 配置")
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(send, "分享 NekoType 配置"))
        }
        binding.btnImportConfig.setOnClickListener { showImportDialog() }

        binding.modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                AppPrefs.privilegeMode = when (checkedId) {
                    R.id.btnModeShizuku -> "shizuku"
                    R.id.btnModeRoot -> "root"
                    else -> "basic"
                }
                NekoLog.adjust("运行模式切换为：${modeLabel(AppPrefs.privilegeMode)}")
                refreshMode()
            }
        }

        // ---- 支持与反馈 ----
        binding.ivSponsorQr.setOnClickListener { showSponsorDialog() }
        binding.btnFeedback.setOnClickListener {
            NekoLog.info("发起意见反馈（邮件）")
            sendFeedbackEmail()
        }
        binding.btnCopyGroup.setOnClickListener {
            NekoLog.info("复制 QQ 群号：1007865515")
            copyToClipboard("1007865515")
            tryOpenQqGroup()
        }

        refreshMode()
        refreshTheme()
        refreshVersion()
        refreshStatsDaily()
    }

    /** 右上角菜单：「日志」入口（位于 NekoType 设置标题右侧） */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "日志").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            NekoLog.nav("打开日志页")
            startActivity(Intent(this, LogActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        refreshMode()
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
        sb.append("今日变换：").append(stats[today] ?: 0).append(" 次（累计 ").append(AppPrefs.transformCount).append(" 次）\n\n最近 7 天：\n")
        val cal = java.util.Calendar.getInstance()
        for (i in 6 downTo 0) {
            cal.timeInMillis = System.currentTimeMillis()
            cal.add(java.util.Calendar.DAY_OF_YEAR, -i)
            val day = fmt.format(cal.time)
            sb.append("  ").append(day).append("：").append(stats[day] ?: 0).append(" 次\n")
        }
        binding.tvStatsDaily.text = sb.toString()
    }

    /** 导入配置对话框（可从剪贴板粘贴） */
    private fun showImportDialog() {
        val et = EditText(this).apply {
            hint = "粘贴 NekoType 配置文本"
            minLines = 4
            gravity = android.view.Gravity.TOP
        }
        // 预填剪贴板内容
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.let {
                if (it.startsWith("{") && it.contains("NekoType")) et.setText(it)
            }
        } catch (_: Throwable) { }
        AlertDialog.Builder(this)
            .setTitle("导入配置")
            .setMessage("粘贴从「导出配置」得到的文本")
            .setView(et)
            .setPositiveButton("导入") { _, _ ->
                val text = et.text.toString().trim()
                if (text.isEmpty()) { toast("内容为空"); return@setPositiveButton }
                val err = AppPrefs.importConfigText(text)
                if (err != null) {
                    NekoLog.error("导入配置失败：$err")
                    toast(err)
                } else {
                    NekoLog.ok("配置导入成功")
                    toast("配置导入成功")
                    // 主题可能变化，重新应用
                    val mode = when (AppPrefs.themeMode) {
                        "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                        "light" -> AppCompatDelegate.MODE_NIGHT_NO
                        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    }
                    AppCompatDelegate.setDefaultNightMode(mode)
                    refreshStatsDaily()
                    BgUtils.apply(binding.root)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun themeLabel(m: String): String = when (m) {
        "dark" -> "深色"
        "light" -> "浅色"
        else -> "跟随系统"
    }

    private fun modeLabel(m: String): String = when (m) {
        "shizuku" -> "Shizuku"
        "root" -> "Root"
        else -> "基础"
    }

    private fun refreshMode() {
        val mode = AppPrefs.privilegeMode
        binding.modeGroup.check(
            when (mode) {
                "shizuku" -> R.id.btnModeShizuku
                "root" -> R.id.btnModeRoot
                else -> R.id.btnModeBasic
            }
        )
        binding.tvModeHint.text = when (mode) {
            "shizuku" -> "Shizuku 模式：系统命令经 Shizuku（shell 权限）执行。需安装 Shizuku 并授权（无线调试/ADB）。"
            "root" -> "Root 模式：系统命令经 su 执行。需 Magisk / KernelSU / APatch。"
            else -> "基础模式：仅悬浮窗 + 无障碍，核心变换发送功能不受影响；不执行系统命令。"
        }
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
            "dark" -> "深色模式：纯黑界面"
            "light" -> "浅色模式：明亮界面"
            else -> "跟随系统：随系统深色/浅色设置自动切换"
        }
    }

    private fun buildDetails(rootOk: Boolean): String {
        val sb = StringBuilder()
        sb.append("悬浮窗权限：").append(if (Settings.canDrawOverlays(this)) "✓ 已授予" else "✗ 未授予").append('\n')
        sb.append("无障碍服务：").append(if (isAccessibilityEnabled()) "✓ 已开启" else "✗ 未开启").append('\n')
        sb.append("电池优化：").append(if (SysPower.isIgnoringBatteryOptimizations()) "✓ 已免电" else "✗ 未免电").append('\n')
        sb.append("Shizuku：").append(if (SysPower.isShizukuAvailable()) "✓ 可用" else "✗ 未检测到").append('\n')
        sb.append("Root：").append(if (rootOk) "✓ 可用" else "✗ 未检测到").append('\n')
        sb.append("设备管理员：").append(if (SysPower.isDeviceAdminActive()) "✓ 已激活" else "✗ 未激活").append('\n')
        sb.append("运行模式：").append(
            when (AppPrefs.privilegeMode) {
                "shizuku" -> "Shizuku"
                "root" -> "Root"
                else -> "基础"
            }
        ).append('\n')
        sb.append("服务状态：").append(if (AppPrefs.serviceEnabled) "● 运行中" else "○ 已停止").append('\n')
        sb.append("累计变换：").append(AppPrefs.transformCount).append(" 次")
        return sb.toString()
    }

    private fun refreshVersion() {
        val info = packageManager.getPackageInfo(packageName, 0)
        val versionName = info.versionName ?: "?"
        val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toString() else info.versionCode.toString()
        binding.tvVersion.text = "版本名：$versionName\n版本号：$versionCode\n最低系统：Android 8.0（API 26）\n目标系统：Android 14（API 34）\n包名：$packageName"
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
            .setTitle("赞助支持")
            .setMessage("喜欢 NekoType 的话，赞助一下开发者吧！")
            .setView(img)
            .setPositiveButton("关闭", null)
            .show()
    }

    /** 建议反馈：跳转邮件并自动填充，发送至 TR114512@qq.com */
    private fun sendFeedbackEmail() {
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            val version = "${info.versionName} (${if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode})"
            val subject = "NekoType 建议反馈"
            val body = "版本：$version\n设备：${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n运行模式：${AppPrefs.privilegeMode}\n\n建议/问题：\n"
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:TR114512@qq.com")
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
            }
            startActivity(Intent.createChooser(intent, "发送反馈"))
        } catch (_: Throwable) {
            toast("未找到邮件应用，请手动发送至 TR114512@qq.com")
            copyToClipboard("TR114512@qq.com")
        }
    }

    /** 复制群号，并尝试拉起 QQ 群卡片 */
    private fun tryOpenQqGroup() {
        try {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("mqqapi://card/show_pslcard?src_type=internal&version=1&uin=1007865515&card_type=group&source=external")
            )
            startActivity(intent)
        } catch (_: Throwable) {
            toast("群号已复制：1007865515，请在 QQ 中搜索加入")
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("nekotype", text))
            toast("已复制：$text")
        } catch (_: Throwable) { }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
