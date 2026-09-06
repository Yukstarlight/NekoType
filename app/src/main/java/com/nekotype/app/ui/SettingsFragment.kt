package com.nekotype.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.nekotype.app.R
import com.nekotype.app.accessibility.NekoTypeAccessibilityService
import com.nekotype.app.databinding.FragmentSettingsBinding
import com.nekotype.app.prefs.AppPrefs
import com.nekotype.app.overlay.FloatingButtonService
import com.nekotype.app.sys.SysPower
import com.nekotype.app.util.BgUtils
import com.nekotype.app.util.NekoLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 设置页 Fragment：外观 / 悬浮按钮 / 高级 / 数据 / 黑名单 / 详细信息 / 关于 / 反馈。
 * 从 SettingsActivity 迁移而来，保留全部功能。
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val bgPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                val out = File(requireContext().filesDir, "custom_bg.jpg")
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
                AppPrefs.customBackgroundPath = out.absolutePath
                BgUtils.apply(binding.root)
                toast(getString(R.string.u1))
            } catch (_: Throwable) {
                toast(getString(R.string.u38))
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        BgUtils.apply(binding.root)
        NekoLog.nav("进入设置页（Fragment）")

        // 自定义背景
        binding.btnPickBg.setOnClickListener {
            try { bgPicker.launch("image/*") } catch (_: Throwable) { toast(getString(R.string.u33)) }
        }
        binding.btnClearBg.setOnClickListener {
            AppPrefs.customBackgroundPath = ""
            BgUtils.apply(binding.root)
            toast(getString(R.string.u41))
        }

        // 主题（2x2 网格，手动单选）
        val themeButtons = mapOf(
            R.id.btnThemeSystem to "system",
            R.id.btnThemeDark to "dark",
            R.id.btnThemeLight to "light",
            R.id.btnThemeStar to "star"
        )
        themeButtons.forEach { (id, mode) ->
            view.findViewById<com.google.android.material.button.MaterialButton>(id)?.setOnClickListener {
                switchTheme(mode)
            }
        }

        // 悬浮按钮
        binding.slFabSize.value = AppPrefs.fabSizeDp.toFloat()
        binding.slFabOpacity.value = AppPrefs.fabOpacity.toFloat()
        binding.tvFabSize.text = "${AppPrefs.fabSizeDp}dp"
        binding.tvFabOpacity.text = "${AppPrefs.fabOpacity}%"
        binding.slFabSize.addOnChangeListener { _, value, _ ->
            val v = value.toInt()
            AppPrefs.fabSizeDp = v
            binding.tvFabSize.text = "${v}dp"
            FloatingButtonService.applyStyle(v, AppPrefs.fabOpacity)
        }
        binding.slFabOpacity.addOnChangeListener { _, value, _ ->
            val v = value.toInt()
            AppPrefs.fabOpacity = v
            binding.tvFabOpacity.text = "${v}%"
            FloatingButtonService.applyStyle(AppPrefs.fabSizeDp, v)
        }

        binding.swFabCollapse.isChecked = AppPrefs.fabCollapseEnabled
        binding.swFabCollapse.setOnCheckedChangeListener { _, checked ->
            AppPrefs.fabCollapseEnabled = checked
            FloatingButtonService.reload()
        }
        binding.swFabGlass.isChecked = AppPrefs.fabGlassEnabled
        binding.swFabGlass.setOnCheckedChangeListener { _, checked ->
            AppPrefs.fabGlassEnabled = checked
            FloatingButtonService.reload()
        }

        // 开机自启
        binding.swAutoStart.isChecked = AppPrefs.autoStartEnabled
        binding.swAutoStart.setOnCheckedChangeListener { _, checked ->
            if (!checked && AppPrefs.lockEnabled) {
                toast(getString(R.string.u20))
                binding.swAutoStart.isChecked = true
                return@setOnCheckedChangeListener
            }
            AppPrefs.autoStartEnabled = checked
        }

        // 数据导入导出
        binding.btnExportConfig.setOnClickListener { exportConfig() }
        binding.btnImportConfig.setOnClickListener { showImportDialog() }

        // 黑名单
        binding.btnBlacklist.setOnClickListener {
            startActivity(Intent(requireContext(), BlacklistActivity::class.java))
        }

        // 支持与反馈
        binding.ivSponsorQr.setOnClickListener { showSponsorDialog() }
        binding.btnFeedback.setOnClickListener { sendFeedbackEmail() }
        binding.btnOpenTerminal.setOnClickListener {
            startActivity(Intent(requireContext(), TerminalActivity::class.java))
        }
        binding.btnCopyGroup.setOnClickListener {
            copyToClipboard("4ldb0biz5")
            tryOpenQqChannel()
        }

        refreshTheme()
        refreshVersion()
        refreshStatsDaily()
    }

    override fun onResume() {
        super.onResume()
        refreshTheme()
        refreshStatsDaily()
        lifecycleScope.launch {
            val root = withContext(Dispatchers.IO) { SysPower.isRootAvailable() }
            binding.tvDetails.text = buildDetails(root)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ---------- 主题 ----------

    private fun switchTheme(mode: String) {
        if (AppPrefs.themeMode == mode) return
        AppPrefs.themeMode = mode
        applyThemeMode()
        // 立即刷新当前页面背景
        BgUtils.apply(binding.root)
        refreshTheme()
        // 重建 Activity 让全局主题（含星空背景）生效
        requireActivity().recreate()
    }

    private fun applyThemeMode() {
        when (AppPrefs.themeMode) {
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "star" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    private fun refreshTheme() {
        val selectedId = when (AppPrefs.themeMode) {
            "dark" -> R.id.btnThemeDark
            "light" -> R.id.btnThemeLight
            "star" -> R.id.btnThemeStar
            else -> R.id.btnThemeSystem
        }
        listOf(R.id.btnThemeSystem, R.id.btnThemeDark, R.id.btnThemeLight, R.id.btnThemeStar).forEach { id ->
            view?.findViewById<com.google.android.material.button.MaterialButton>(id)?.isChecked = (id == selectedId)
        }
        binding.tvThemeHint.text = when (AppPrefs.themeMode) {
            "dark" -> getString(R.string.u134)
            "light" -> getString(R.string.u135)
            "star" -> getString(R.string.theme_star_hint)
            else -> getString(R.string.u136)
        }
    }

    // ---------- 统计 ----------

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

    // ---------- 导入导出 ----------

    private fun exportConfig() {
        val text = AppPrefs.exportConfigText()
        if (text.isEmpty()) {
            toast(getString(R.string.u40))
            return
        }
        try {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("nekotype_config", text))
        } catch (_: Throwable) { }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.u123))
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, getString(R.string.u124)))
    }

    private fun showImportDialog() {
        val et = EditText(requireContext()).apply {
            hint = getString(R.string.u129)
            minLines = 4
            maxLines = 6
            gravity = android.view.Gravity.TOP
        }
        try {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.let {
                if (it.startsWith("{") && it.contains("NekoType")) et.setText(it)
            }
        } catch (_: Throwable) { }

        var dialogRef: AlertDialog? = null
        val btnImport = MaterialButton(requireContext()).apply {
            text = getString(R.string.u130)
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val text = et.text.toString().trim()
                if (text.isEmpty()) { toast(getString(R.string.u9)); return@setOnClickListener }
                val err = AppPrefs.importConfigText(text)
                if (err != null) {
                    toast(err)
                } else {
                    toast(getString(R.string.u0))
                    applyThemeMode()
                    refreshStatsDaily()
                    BgUtils.apply(binding.root)
                    dialogRef?.dismiss()
                }
            }
        }
        val btnCancel = MaterialButton(requireContext()).apply {
            text = getString(R.string.u72)
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { dialogRef?.dismiss() }
        }
        val btnRow = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            addView(btnImport)
            addView(btnCancel)
        }
        val content = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
            addView(et)
            addView(btnRow)
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.u39))
            .setMessage(getString(R.string.u42))
            .setView(content)
            .create()
        dialogRef = dialog
        dialog.show()
    }

    // ---------- 详细信息 ----------

    private fun buildDetails(rootOk: Boolean): String {
        val ctx = requireContext()
        val sb = StringBuilder()
        sb.append(getString(R.string.u137)).append(if (Settings.canDrawOverlays(ctx)) getString(R.string.u138) else getString(R.string.u139)).append('\n')
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
        val info = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
        val versionName = info.versionName ?: "?"
        val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toString() else info.versionCode.toString()
        binding.tvVersion.text = getString(R.string.u155, versionName, versionCode, requireContext().packageName)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val ctx = requireContext()
        val cmp = "${ctx.packageName}/${NekoTypeAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.split(":").any { it.equals(cmp, ignoreCase = true) }
    }

    // ---------- 支持与反馈 ----------

    private fun showSponsorDialog() {
        val img = ImageView(requireContext()).apply {
            setImageResource(R.drawable.sponsor_qr)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.u44))
            .setMessage(getString(R.string.u31))
            .setView(img)
            .setPositiveButton(getString(R.string.u156), null)
            .show()
    }

    private fun sendFeedbackEmail() {
        try {
            val info = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            val version = "${info.versionName} (${if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode})"
            val subject = getString(R.string.u157)
            val body = getString(R.string.u158, version, Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE,
                if (SysPower.isShizukuPermissionGranted()) getString(R.string.u109) else getString(R.string.u108))
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

    private fun tryOpenQqChannel() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://pd.qq.com/s/4ldb0biz5?b=9")))
        } catch (_: Throwable) {
            toast(getString(R.string.u57))
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("nekotype", text))
            toast(getString(R.string.u15, text))
        } catch (_: Throwable) { }
    }

    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}
