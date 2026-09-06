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
import com.nekotype.app.util.NekoLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

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
            toast(
                if (grantResult == PackageManager.PERMISSION_GRANTED) getString(R.string.u60)
                else getString(R.string.u61)
            )
            refreshStatus()
        }
    }

    private var overlayPending = false
    private val overlayLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        overlayPending = false
        refreshStatus()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        BgUtils.apply(binding.root)

        try { Shizuku.addRequestPermissionResultListener(shizukuListener) } catch (_: Throwable) { }

        binding.btnToggleService.setOnClickListener { toggleService() }

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

        // 系统能力
        binding.btnBatteryPriv.setOnClickListener { grantBatteryPrivileged() }

        requestNotificationPermission()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        BgUtils.apply(binding.root)
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

    override fun onDestroyView() {
        super.onDestroyView()
        try { Shizuku.removeRequestPermissionResultListener(shizukuListener) } catch (_: Throwable) { }
        _binding = null
    }

    // ---------- 语言切换 ----------

    /** 全局 UI 语言切换：简体中文 / 繁體中文 / English */
    private fun showLanguageDialog() {
        val tags = arrayOf("zh", "zh-TW", "en")
        val langs = arrayOf("简体中文", "繁體中文", "English")
        val checked = when (androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags()) {
            "zh-TW" -> 1
            "en" -> 2
            else -> 0
        }
        var chosen = checked
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.u52))
            .setSingleChoiceItems(langs, checked) { _, which ->
                chosen = which
            }
            .setPositiveButton(getString(R.string.u71)) { _, _ ->
                try {
                    androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
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
        val dialog = AlertDialog.Builder(requireContext())
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
            SysPower.isShizukuPermissionGranted() -> {
                lifecycleScope.launch {
                    val r = withContext(Dispatchers.IO) { SysPower.execIdForStatus() }
                    binding.tvPrivLog.text = getString(R.string.u76, r.channel, r.output)
                    NekoLog.ok("Shizuku 已授权，通道 ${r.channel}")
                    toast(getString(R.string.u3))
                }
            }
            else -> SysPower.requestShizukuPermission(shizukuRequestCode)
        }
        refreshStatus()
    }

    // ---------- 免电 ----------

    private fun grantBatteryPrivileged() {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { SysPower.grantBatteryWhitelistPrivileged() }
            binding.tvPrivLog.text = getString(R.string.u76, r.channel, r.output)
            if (r.success) {
                NekoLog.ok("电池优化白名单已写入（${r.channel}）")
                toast(getString(R.string.u49, r.channel))
            } else {
                NekoLog.error("免电写入失败：${r.output}")
                toast(getString(R.string.u56, r.output))
            }
            refreshStatus()
        }
    }

    // ---------- 状态刷新 ----------

    private fun refreshStatus() {
        val ctx = context ?: return
        val running = AppPrefs.serviceEnabled
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
        lifecycleScope.launch {
            val root = withContext(Dispatchers.IO) { SysPower.isRootAvailable() }
            binding.chipRoot.isChecked = root
        }

        setStep(binding.tvStep1Status, isAccessibilityEnabled(), getString(R.string.u102), getString(R.string.u103))
        setStep(binding.tvStep2Status, SysPower.isDeviceAdminActive(), getString(R.string.u104), getString(R.string.u105))
        setStep(binding.tvStep3Status, SysPower.isIgnoringBatteryOptimizations(), getString(R.string.u106), getString(R.string.u107))
        setStep(binding.tvStep4Status, Settings.canDrawOverlays(ctx), getString(R.string.u108), getString(R.string.u109))
        setStep(binding.tvStep5Status, SysPower.isShizukuPermissionGranted(), getString(R.string.u110), getString(R.string.u109))

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

    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}
