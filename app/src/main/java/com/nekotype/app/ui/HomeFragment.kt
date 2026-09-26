package com.nekotype.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.nekotype.app.R
import com.nekotype.app.accessibility.NekoTypeAccessibilityService
import com.nekotype.app.databinding.FragmentHomeBinding
import com.nekotype.app.overlay.FloatingButtonService
import com.nekotype.app.prefs.AppPrefs
import com.nekotype.app.sys.SysPower
import com.nekotype.app.util.BgUtils
import com.nekotype.app.util.NekoLang
import com.nekotype.app.util.NekoLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File

/**
 * 首页（启动页）：服务状态总览 + 权限引导五步 + 系统能力。
 * 从 MainActivity 权限页逻辑迁移而来，保留全部功能。
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val shizukuRequestCode = 1001

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == shizukuRequestCode) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                toast(getString(R.string.u60))
                // 权限授权成功后，自动触发 UserService 绑定探测
                probeShizukuChannel()
            } else {
                toast(getString(R.string.u61))
                binding.tvPrivLog.text = "Shizuku API 权限被拒绝，UserService 绑定可能失败。\n请在 Shizuku 应用 → 应用管理 → 开启 NekoType 后重试。"
            }
            refreshStatus()
        }
    }

    private var overlayPending = false
    private val overlayLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        overlayPending = false
        refreshStatus()
    }

    /** 自定义悬浮球图标选择器 */
    private val fabIconPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                val out = File(requireContext().filesDir, "custom_fab_icon.png")
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
                AppPrefs.customFabIconPath = out.absolutePath
                updateFabIconPreview()
                restartFabIfRunning()
                toast(getString(R.string.hc_fab_icon_updated))
            } catch (_: Throwable) {
                toast(getString(R.string.hc_image_save_fail))
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // inflate 兜底：布局加载失败返回空视图，绝不闪退
        _binding = try {
            FragmentHomeBinding.inflate(inflater, container, false)
        } catch (e: Throwable) {
            NekoLog.error("首页布局加载失败：${e.javaClass.simpleName}")
            return android.widget.FrameLayout(inflater.context).apply {
                addView(android.widget.TextView(context).apply {
                    text = getString(R.string.hc_load_fail_home)
                    gravity = android.view.Gravity.CENTER
                })
            }
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        BgUtils.apply(binding.root)

        try { Shizuku.addRequestPermissionResultListener(shizukuListener) } catch (_: Throwable) { }

        binding.btnToggleService.setOnClickListener { toggleService() }

        // 开发者模式入口（DEV 徽标，开发者模式开启后显示在 NekoType 标题旁）
        binding.btnDev.setOnClickListener {
            NekoLog.nav("首页 DEV 入口：进入开发者模式")
            startActivity(Intent(requireContext(), DevActivity::class.java))
        }

        // 语言切换（全局 UI 语言：简体中文 / 繁體中文 / English）
        binding.btnLanguage.setOnClickListener { showLanguageDialog() }

        // 权限引导
        binding.btnStep1.setOnClickListener {
            NekoLog.info("前往开启无障碍服务")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnStep2.setOnClickListener {
            NekoLog.info("请求激活设备管理员")
            SysPower.requestDeviceAdmin()
        }
        binding.btnStep3.setOnClickListener {
            NekoLog.info("请求关闭省电优化")
            SysPower.requestBatteryOptimizationDialog()
        }
        binding.btnStep4.setOnClickListener {
            NekoLog.nav("打开悬浮窗授权页")
            openOverlaySettings()
        }
        binding.btnStep5Shizuku.setOnClickListener { handleShizuku() }
        // 自启动保活：vivo/iQOO(OriginOS)、小米、OPPO、华为等国内 ROM 必开，否则后台被冻结
        binding.btnStep6.setOnClickListener {
            NekoLog.adjust("前往开启自启动权限")
            SysPower.openAutoStartSettings(requireContext())
        }

        // 系统能力
        binding.btnGrantAll.setOnClickListener { grantAllPermissions() }

        // 自定义悬浮球图标
        binding.btnPickFabIcon.setOnClickListener {
            try { fabIconPicker.launch("image/*") } catch (_: Throwable) { toast(getString(R.string.hc_no_image_picker)) }
        }
        binding.btnResetFabIcon.setOnClickListener {
            AppPrefs.customFabIconPath = ""
            updateFabIconPreview()
            restartFabIfRunning()
            toast(getString(R.string.hc_icon_restored))
        }
        updateFabIconPreview()

        requestNotificationPermission()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        BgUtils.apply(binding.root)
        NekoLang.apply(binding.root)
        refreshStatus()
        // 悬浮窗授权返回后自动启动服务
        if (AppPrefs.serviceEnabled && context?.let { Settings.canDrawOverlays(it) } == true) {
            context?.let { FloatingButtonService.start(it) }
            if (overlayPending) {
                overlayPending = false
                NekoLog.ok("悬浮窗权限已授予，服务自动启动")
            }
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // 底部导航用 hide/show 切换，onResume 不会触发；
        // 这里刷新保证"从设置页开启开发者模式后切回首页，DEV 徽标立即出现"
        if (!hidden && _binding != null) {
            try {
                BgUtils.apply(binding.root)
                refreshStatus()
            } catch (_: Throwable) { }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try { Shizuku.removeRequestPermissionResultListener(shizukuListener) } catch (_: Throwable) { }
        _binding = null
    }

    // ---------- 语言切换 ----------

    /** 全局 UI 语言切换：跟随系统（默认）/ 简体中文 / 繁體中文 / English */
    private fun showLanguageDialog() {
        val tags = arrayOf("", "zh", "zh-TW", "en", "tr", "ru", "ja", "ko", "de")
        val langs = arrayOf(getString(R.string.hc_follow_system), "简体中文", "繁體中文", "English", "Türkçe", "Русский", "日本語", "한국어", "Deutsch")
        val checked = when (androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags()) {
            "zh" -> 1
            "zh-TW" -> 2
            "en" -> 3
            "tr" -> 4
            "ru" -> 5
            "ja" -> 6
            "ko" -> 7
            "de" -> 8
            else -> 0 // 空 = 跟随系统（默认）
        }
        var chosen = checked
        NekoDialog.builder(requireContext())
            .setTitle(getString(R.string.u52))
            .setSingleChoiceItems(langs, checked) { _, which ->
                chosen = which
            }
            .setPositiveButton(getString(R.string.u71)) { _, _ ->
                try {
                    // 跟随系统 = 清空应用级语言覆盖（getEmptyLocaleList）
                    val list = if (chosen == 0) {
                        androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                    } else {
                        androidx.core.os.LocaleListCompat.forLanguageTags(tags[chosen])
                    }
                    androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(list)
                    com.nekotype.app.prefs.AppPrefs.appLangTag = tags[chosen]
                    NekoLog.adjust("语言切换为：${langs[chosen]}")
                } catch (_: Throwable) {
                    toast(getString(R.string.u14))
                }
            }
            .setNegativeButton(getString(R.string.u72), null)
            .show()
    }

    // ---------- 服务开关 ----------

    private fun toggleService() {
        val enabled = !AppPrefs.serviceEnabled
        if (!enabled && AppPrefs.lockEnabled) {
            showVerifyLockPasswordDialog(getString(R.string.u68)) { doToggleService(false) }
            return
        }
        doToggleService(enabled)
    }

    private fun doToggleService(enabled: Boolean) {
        val ctx = context ?: return
        AppPrefs.serviceEnabled = enabled
        if (enabled) {
            if (Settings.canDrawOverlays(ctx)) {
                FloatingButtonService.start(ctx)
                NekoLog.info("用户启动服务")
                toast(getString(R.string.u13))
            } else {
                NekoLog.warn("启动服务失败：未授予悬浮窗权限")
                toast(getString(R.string.u5))
                openOverlaySettings()
            }
        } else {
            FloatingButtonService.stop(ctx)
            NekoLog.info("用户停止服务")
        }
        refreshStatus()
    }

    // ---------- 密码锁定验证（停止服务时） ----------

    private fun showVerifyLockPasswordDialog(title: String, onOk: () -> Unit) {
        val et = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.u75)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 16, 48, 8)
            addView(et)
        }
        val dialog = NekoDialog.builder(requireContext())
            .setTitle(title)
            .setView(box)
            .setPositiveButton(getString(R.string.u71), null)
            .setNegativeButton(getString(R.string.u72), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (AppPrefs.verifyLockPassword(et.text.toString())) {
                    dialog.dismiss()
                    onOk()
                } else {
                    toast(getString(R.string.u25))
                    et.text.clear()
                }
            }
        }
        dialog.show()
    }

    // ---------- 悬浮窗授权 ----------

    private fun openOverlaySettings() {
        overlayPending = true
        try {
            overlayLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${requireContext().packageName}")
                )
            )
        } catch (_: Throwable) {
            overlayPending = false
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    // ---------- Shizuku ----------

    private fun handleShizuku() {
        when {
            !SysPower.isShizukuAvailable() -> {
                NekoLog.warn("Shizuku：未检测到服务")
                toast(getString(R.string.u26))
            }
            !SysPower.isShizukuPermissionGranted() -> {
                // Shizuku 13.x：UserService 绑定前需先获取 API 权限，否则静默拒绝
                binding.tvPrivLog.text = getString(R.string.hc_shizuku_requesting)
                try {
                    SysPower.requestShizukuPermission(shizukuRequestCode)
                } catch (t: Throwable) {
                    binding.tvPrivLog.text = getString(R.string.hc_shizuku_req_fail, t.javaClass.simpleName, t.message)
                }
            }
            else -> {
                probeShizukuChannel()
            }
        }
        refreshStatus()
    }

    /** 绑定 UserService 探测通道（API 权限已获取后调用） */
    private fun probeShizukuChannel() {
        binding.tvPrivLog.text = getString(R.string.hc_shizuku_binding)
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { SysPower.execIdForStatus() }
            if (r.success) {
                binding.tvPrivLog.text = getString(R.string.u76, r.channel, r.output)
                NekoLog.ok("Shizuku 已授权，通道 ${r.channel}")
                toast(getString(R.string.u3))
            } else {
                val diag = withContext(Dispatchers.IO) { SysPower.diagnoseShizuku() }
                binding.tvPrivLog.text = getString(R.string.hc_shizuku_probe_fail, diag)
                NekoLog.warn(getString(R.string.hc_shizuku_probe_fail, diag))
                toast(getString(R.string.hc_shizuku_bind_fail))
            }
            refreshStatus()
        }
    }

    // ---------- 一键授权所有权限 ----------

    /**
     * 通过 Shizuku 一键写入所有权限：无障碍 → 设备管理员 → 电池优化 → 悬浮窗。
     * 执行前检查 Shizuku 状态；每步独立记录成功/失败，失败时在日志区显示具体原因。
     */
    private fun grantAllPermissions() {
        val ctx = context ?: return
        if (!SysPower.isShizukuAvailable()) {
            NekoLog.warn("一键授权失败：未检测到 Shizuku 服务")
            binding.tvPrivLog.text = getString(R.string.u184)
            toast(getString(R.string.u26))
            return
        }
        // Shizuku 11+：UserService 绑定前必须先获得 API 权限，否则所有步骤必然失败
        if (!SysPower.isShizukuPermissionGranted()) {
            binding.tvPrivLog.text = getString(R.string.hc_shizuku_requesting2)
            try {
                SysPower.requestShizukuPermission(shizukuRequestCode)
            } catch (t: Throwable) {
                binding.tvPrivLog.text = getString(R.string.hc_shizuku_req_fail, t.javaClass.simpleName, t.message)
            }
            return
        }

        binding.btnGrantAll.isEnabled = false
        binding.btnGrantAll.text = getString(R.string.u186)
        binding.tvPrivLog.text = getString(R.string.u187)

        lifecycleScope.launch {
            val steps = withContext(Dispatchers.IO) { SysPower.grantAllPermissions() }
            // 构建详细日志：每步显示 ✓/✗ + 名称 + 输出
            val sb = StringBuilder()
            var successCount = 0
            var failCount = 0
            steps.forEachIndexed { i, step ->
                val mark = if (step.success) "✓" else "✗"
                if (step.success) successCount++ else failCount++
                sb.appendLine("${i + 1}. $mark ${step.name}  [${step.channel}]")
                if (step.output.isNotEmpty()) {
                    step.output.lineSequence().forEach { line ->
                        if (line.isNotBlank()) sb.appendLine("   $line")
                    }
                }
                sb.appendLine()
            }
            sb.appendLine(getString(R.string.u188, successCount, failCount))
            // 失败时额外提示
            if (failCount > 0) {
                sb.appendLine()
                sb.appendLine(getString(R.string.u189))
                sb.appendLine(getString(R.string.u190))
            }
            binding.tvPrivLog.text = sb.toString()

            // 记录日志
            if (failCount == 0) {
                NekoLog.ok("一键授权全部成功（$successCount 项）")
                toast(getString(R.string.u191, successCount))
            } else {
                NekoLog.error("一键授权完成：成功 $successCount 项，失败 $failCount 项")
                toast(getString(R.string.u192, successCount, failCount))
            }

            binding.btnGrantAll.isEnabled = true
            binding.btnGrantAll.text = getString(R.string.t1000)
            refreshStatus()
        }
    }

    // ---------- 状态刷新 ----------

    private fun refreshStatus() {
        val ctx = context ?: return
        val running = AppPrefs.serviceEnabled
        // 开发者模式入口：仅开启时显示（NekoType 标题旁 DEV 徽标）
        binding.btnDev.visibility = if (AppPrefs.devModeEnabled) View.VISIBLE else View.GONE
        binding.tvStatus.text = if (running) getString(R.string.u97) else getString(R.string.u98)
        binding.tvStatus.setTextColor(
            ContextCompat.getColor(ctx, if (running) R.color.md_theme_primary else R.color.md_theme_onSurfaceVariant)
        )
        binding.btnToggleService.text = if (running) getString(R.string.u99) else getString(R.string.u100)
        binding.tvStats.text = getString(R.string.u101, AppPrefs.transformCount)

        binding.chipOverlay.isChecked = Settings.canDrawOverlays(ctx)
        binding.chipAccessibility.isChecked = isAccessibilityEnabled()
        binding.chipBattery.isChecked = SysPower.isIgnoringBatteryOptimizations()
        binding.chipShizuku.isChecked = SysPower.isShizukuAvailable()
        binding.chipAdmin.isChecked = SysPower.isDeviceAdminActive()

        setStep(binding.tvStep1Status, isAccessibilityEnabled(), getString(R.string.u102), getString(R.string.u103))
        setStep(binding.tvStep2Status, SysPower.isDeviceAdminActive(), getString(R.string.u104), getString(R.string.u105))
        setStep(binding.tvStep3Status, SysPower.isIgnoringBatteryOptimizations(), getString(R.string.u106), getString(R.string.u107))
        setStep(binding.tvStep4Status, Settings.canDrawOverlays(ctx), getString(R.string.u108), getString(R.string.u109))
        setStep(binding.tvStep5Status, SysPower.isShizukuAvailable(), getString(R.string.u110), getString(R.string.u109))
        // 自启动状态系统无法查询 → 固定提示（vivo/iQOO 等不手动开会被冻结）
        binding.tvStep6Status.text = getString(R.string.i53)

        val done = isAccessibilityEnabled() && Settings.canDrawOverlays(ctx)
        binding.tvWizardDone.text = if (done) getString(R.string.u111) else ""
    }

    private fun setStep(tv: TextView, ok: Boolean, failText: String, okText: String) {
        tv.text = if (ok) "✓ $okText" else failText
        tv.setTextColor(
            ContextCompat.getColor(requireContext(), if (ok) R.color.ok_green else R.color.warn_amber)
        )
    }

    private fun isAccessibilityEnabled(): Boolean {
        val ctx = context ?: return false
        val cmp = "${ctx.packageName}/${NekoTypeAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.split(":").any { it.equals(cmp, ignoreCase = true) }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            requireContext().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200)
        }
    }

    /** 更新悬浮球图标预览 */
    private fun updateFabIconPreview() {
        val path = AppPrefs.customFabIconPath
        if (path.isNotEmpty() && File(path).exists()) {
            try {
                val bmp = android.graphics.BitmapFactory.decodeFile(path)
                binding.ivFabIconPreview.setImageBitmap(bmp)
            } catch (_: Throwable) {
                binding.ivFabIconPreview.setImageResource(R.drawable.ctw_icon)
            }
        } else {
            binding.ivFabIconPreview.setImageResource(R.drawable.ctw_icon)
        }
    }

    /** 悬浮球正在运行时重建按钮，让新图标立刻生效 */
    private fun restartFabIfRunning() {
        try {
            if (FloatingButtonService.isRunning()) {
                FloatingButtonService.reload()
            }
        } catch (_: Throwable) { }
    }

    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}
