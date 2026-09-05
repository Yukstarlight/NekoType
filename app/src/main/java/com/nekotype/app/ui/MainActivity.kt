package com.nekotype.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.tabs.TabLayout
import com.nekotype.app.R
import com.nekotype.app.accessibility.NekoTypeAccessibilityService
import com.nekotype.app.databinding.ActivityMainBinding
import com.nekotype.app.overlay.FloatingButtonService
import com.nekotype.app.prefs.AppPrefs
import com.nekotype.app.prefs.AppPrefs.NekoRule
import com.nekotype.app.prefs.AppPrefs.RuleType
import com.nekotype.app.sys.SysPower
import com.nekotype.app.transform.TextTransformEngine
import com.nekotype.app.util.BgUtils
import com.nekotype.app.util.NekoLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * 主界面：顶部 Hero + 两个选项卡（权限 / 规则）。
 * - 权限页：五步权限引导 + 状态总览 + 系统能力；
 * - 规则页：规则预设（选择/删除）+ 规则列表（添加/开关/删除）+ 行为样式 + 效果预览；
 * - 右上角设置（更换模式 / 详细信息 / 版本 / 关于 / 支持反馈 / 隐私政策）。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** 外部停止请求标记（通知栏/磁贴触发，密码锁定验证用） */
        const val EXTRA_STOP_REQUEST = "nekotype_stop_request"
    }

    private lateinit var binding: ActivityMainBinding
    private val shizukuRequestCode = 1001

    /** 程序化恢复开关状态时置位，避免触发免责声明弹窗 */
    private var restoringSwitch = false

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
        onResume()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 主题：super 前设置夜间模式（recreate 后保持）
        when (AppPrefs.themeMode) {
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        BgUtils.apply(binding.root)
        NekoLog.nav("应用启动（NekoType ${versionName()}）")

        // 防篡改：签名被改 / Hook 框架 → 拒绝运行并提示
        if (AppPrefs.tampered) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.u36))
                .setMessage(getString(R.string.u45))
                .setPositiveButton(getString(R.string.u62)) { _, _ -> finish() }
                .setCancelable(false)
                .show()
            return
        }

        try {
            Shizuku.addRequestPermissionResultListener(shizukuListener)
        } catch (_: Throwable) { }

        // ---- 顶部 ----
        binding.btnSettings.setOnClickListener {
            NekoLog.nav("打开设置页")
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnToggleService.setOnClickListener { toggleService() }

        // ---- 选项卡 ----
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val isPermissions = tab.position == 0
                binding.tabPermissions.visibility = if (isPermissions) android.view.View.VISIBLE else android.view.View.GONE
                binding.tabRules.visibility = if (isPermissions) android.view.View.GONE else android.view.View.VISIBLE
                NekoLog.nav(if (isPermissions) "打开权限页" else "打开规则页")
                if (!isPermissions) renderRules()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) { }
            override fun onTabReselected(tab: TabLayout.Tab) { }
        })

        // ---- 权限引导向导 ----
        binding.btnStep1.setOnClickListener {
            NekoLog.info("前往开启无障碍服务")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnStep2.setOnClickListener {
            NekoLog.info("请求激活设备管理员")
            SysPower.requestDeviceAdmin()
        }
        binding.btnStep3.setOnClickListener {
            NekoLog.info("请求关闭省电优化（系统弹窗）")
            SysPower.requestBatteryOptimizationDialog()
        }
        binding.btnStep4.setOnClickListener {
            NekoLog.nav("打开悬浮窗授权页")
            openOverlaySettings()
        }
        binding.btnStep5Shizuku.setOnClickListener { handleShizuku() }

        // ---- 系统能力 ----
        binding.btnBatteryPriv.setOnClickListener { grantBatteryPrivileged() }

        // ---- 规则预设 ----
        binding.btnSelectRule.setOnClickListener { selectPresetDialog() }
        binding.btnDeleteRule.setOnClickListener { deletePresetDialog() }

        // ---- 添加规则 ----
        binding.btnAddRule.setOnClickListener { showAddRuleDialog() }

        // ---- 行为与样式 ----
        binding.swStyleSpaced.setOnCheckedChangeListener { _, v -> AppPrefs.styleSpaced = v }
        binding.swStyleUpper.setOnCheckedChangeListener { _, v -> AppPrefs.styleUpper = v }
        binding.swAutoSend.setOnCheckedChangeListener { _, v -> AppPrefs.autoSend = v }
        binding.swHaptic.setOnCheckedChangeListener { _, v -> AppPrefs.hapticEnabled = v }
        binding.swSnap.setOnCheckedChangeListener { _, v -> AppPrefs.snapEdges = v }
        binding.swSilentModify.setOnCheckedChangeListener { _, v ->
            AppPrefs.silentModifyEnabled = v
            NekoLog.adjust(if (v) "开启静默修改（Shizuku 直写）" else "关闭静默修改")
            if (v) toast(getString(R.string.u27))
        }
        binding.swPunctTrigger.setOnCheckedChangeListener { _, v ->
            AppPrefs.punctTriggerEnabled = v
            NekoLog.adjust(if (v) "开启标点触发（打完一句才改）" else "关闭标点触发")
        }
        binding.swEmoticon.setOnCheckedChangeListener { _, v ->
            AppPrefs.emoticonEnabled = v
            NekoLog.adjust(if (v) "开启随机颜文字（每条自动追加）" else "关闭随机颜文字")
        }
        binding.swForceKeyboard.setOnCheckedChangeListener { _, v ->
            if (restoringSwitch) return@setOnCheckedChangeListener
            if (AppPrefs.lockEnabled) {
                // 密码锁定：开关强制篡改键盘需验证密码，取消则恢复原状态
                showVerifyLockPasswordDialog(
                    if (v) getString(R.string.u63) else getString(R.string.u64),
                    onOk = {
                        if (v) enableForceKeyboard() else disableForceKeyboard()
                    },
                    onCancel = { restoreForceSwitch() }
                )
                return@setOnCheckedChangeListener
            }
            if (v) enableForceKeyboard() else disableForceKeyboard()
        }

        binding.swLock.setOnCheckedChangeListener { _, v ->
            if (restoringSwitch) return@setOnCheckedChangeListener
            if (v) {
                onLockEnableRequested()
            } else {
                onLockDisableRequested()
            }
        }
        binding.swCrashRestart.setOnCheckedChangeListener { _, v ->
            AppPrefs.crashRestartEnabled = v
            NekoLog.adjust(if (v) "开启崩溃自启" else "关闭崩溃自启")
        }

        binding.swHiddenMode.setOnCheckedChangeListener { _, v ->
            if (restoringSwitch) return@setOnCheckedChangeListener
            if (v) {
                // 开启隐藏模式：需密码锁定
                if (!AppPrefs.lockEnabled) {
                    toast(getString(R.string.u46))
                    restoreHiddenSwitch()
                    return@setOnCheckedChangeListener
                }
                showVerifyLockPasswordDialog(
                    getString(R.string.u65),
                    onOk = { applyHiddenMode(true) },
                    onCancel = { restoreHiddenSwitch() }
                )
            } else {
                showVerifyLockPasswordDialog(
                    getString(R.string.u66),
                    onOk = { applyHiddenMode(false) },
                    onCancel = { restoreHiddenSwitch() }
                )
            }
        }
        binding.swHeartbeat.setOnCheckedChangeListener { _, v ->
            if (restoringSwitch) return@setOnCheckedChangeListener
            // 关闭心跳保活需验证密码（防止别人关掉防杀手段）；开启不需要
            if (!v && AppPrefs.lockEnabled) {
                showVerifyLockPasswordDialog(
                    getString(R.string.u67),
                    onOk = { applyHeartbeat(false) },
                    onCancel = { restoreHeartbeatSwitch() }
                )
                return@setOnCheckedChangeListener
            }
            applyHeartbeat(v)
        }

        // ---- 预览 ----
        binding.btnPreview.setOnClickListener { runPreview() }

        requestNotificationPermission()
        refreshStatus()
        renderRules()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        BgUtils.apply(binding.root)
        refreshStatus()
        renderRules()
        // 悬浮窗授权返回后自动启动服务
        if (AppPrefs.serviceEnabled && Settings.canDrawOverlays(this)) {
            FloatingButtonService.start(this)
            if (overlayPending) {
                overlayPending = false
                NekoLog.ok("悬浮窗权限已授予，服务自动启动")
            }
        }
        // 外部停止请求（通知栏/磁贴）：密码锁定验证后才允许停止
        if (intent?.getBooleanExtra(EXTRA_STOP_REQUEST, false) == true) {
            intent?.removeExtra(EXTRA_STOP_REQUEST)
            handleExternalStopRequest()
        }
    }

    /** 通知栏/磁贴发起的停止请求：锁定开启时验证密码；隐藏模式下验证通过后恢复图标 */
    private fun handleExternalStopRequest() {
        if (AppPrefs.lockEnabled) {
            NekoLog.info("外部停止请求：需要验证密码")
            showVerifyLockPasswordDialog(getString(R.string.u68)) {
                // 隐藏模式：恢复桌面图标（这是隐藏模式的唯一恢复路口）
                if (AppPrefs.hiddenModeEnabled) {
                    AppPrefs.hiddenModeEnabled = false
                    SysPower.setHiddenMode(false)
                    SysPower.setUninstallBlockedByAdmin(false)
                    NekoLog.adjust("隐藏模式已关闭，桌面图标已恢复")
                }
                AppPrefs.serviceEnabled = false
                FloatingButtonService.stop(this)
                refreshStatus()
                toast(getString(R.string.u17))
            }
        } else {
            AppPrefs.serviceEnabled = false
            FloatingButtonService.stop(this)
            refreshStatus()
        }
    }

    override fun onDestroy() {
        NekoLog.nav("退出应用（回到桌面）")
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuListener)
        } catch (_: Throwable) { }
        super.onDestroy()
    }

    // ---------- 服务开关 ----------

    private fun toggleService() {
        val enabled = !AppPrefs.serviceEnabled
        if (!enabled) {
            // 停止服务：密码锁定开启时需验证密码
            if (AppPrefs.lockEnabled) {
                showVerifyLockPasswordDialog(getString(R.string.u68)) { doToggleService(false) }
                return
            }
        }
        doToggleService(enabled)
    }

    private fun doToggleService(enabled: Boolean) {
        AppPrefs.serviceEnabled = enabled
        if (enabled) {
            if (Settings.canDrawOverlays(this)) {
                FloatingButtonService.start(this)
                NekoLog.info("用户启动服务")
                toast(getString(R.string.u13))
            } else {
                NekoLog.warn("启动服务失败：未授予悬浮窗权限")
                toast(getString(R.string.u5))
                openOverlaySettings()
            }
        } else {
            FloatingButtonService.stop(this)
            NekoLog.info("用户停止服务")
        }
        refreshStatus()
    }

    // ---------- 密码锁定 ----------

    /** 用户开启密码锁定：首次设置密码，之后需验证旧密码 */
    private fun onLockEnableRequested() {
        if (AppPrefs.hasLockPassword()) {
            showVerifyLockPasswordDialog(getString(R.string.u69)) { enableLock() }
        } else {
            showSetLockPasswordDialog()
        }
    }

    /** 用户关闭密码锁定：需验证密码 */
    private fun onLockDisableRequested() {
        showVerifyLockPasswordDialog(getString(R.string.u70)) { disableLock() }
    }

    private fun enableLock() {
        AppPrefs.lockEnabled = true
        // 密码锁定 = 强制开机自启（重启后服务自动回来）
        AppPrefs.autoStartEnabled = true
        NekoLog.adjust("密码锁定已开启（开机自启已联动开启）")
        toast(getString(R.string.u59))
        renderRules()
        // 开启密码锁定后：询问是否开启隐藏模式
        askHiddenMode()
    }

    /** 开启密码锁定后询问是否开启隐藏模式（不强制） */
    private fun askHiddenMode() {
        if (AppPrefs.hiddenModeEnabled) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.u10))
            .setMessage(getString(R.string.u114))

            .setPositiveButton(getString(R.string.u112)) { _, _ -> applyHiddenMode(true) }
            .setNegativeButton(getString(R.string.u113), null)
            .show()
    }

    /** 隐藏模式：隐藏/恢复桌面图标（Shizuku pm hide 优先，回退 alias；设备管理员防卸载） */
    private fun applyHiddenMode(enabled: Boolean) {
        if (enabled) {
            // 前置条件 1：服务必须已启动并常驻 —— 通知栏「停止服务」是唯一恢复入口
            if (!AppPrefs.serviceEnabled || !Settings.canDrawOverlays(this)) {
                toast(getString(R.string.u2))
                restoreHiddenSwitch()
                return
            }
            // 前置条件 2：设备管理员必须已激活（防卸载 + 皆成同款路线）
            if (!SysPower.isDeviceAdminActive()) {
                toast(getString(R.string.u54))
                restoreHiddenSwitch()
                return
            }
        }
        AppPrefs.hiddenModeEnabled = enabled
        // 设备管理员：阻止卸载
        val adminOk = SysPower.setUninstallBlockedByAdmin(enabled)
        if (!adminOk && enabled) {
            NekoLog.warn("设备管理员阻止卸载未生效")
        }
        if (enabled) {
            // 关键：隐藏模式必须保证服务常驻 —— 自动开启心跳保活，
            // pm hide / 系统杀进程后 60 秒内自动复活，通知栏入口永不丢
            if (!AppPrefs.heartbeatEnabled) {
                AppPrefs.heartbeatEnabled = true
                NekoLog.ok("隐藏模式：心跳保活已自动开启")
                FloatingButtonService.start(this)
            }
            // 隐藏方式：Shizuku pm hide（Hail 同款，图标即时消失）优先；无 Shizuku 回退 alias
            if (SysPower.privilegedChannelReady()) {
                lifecycleScope.launch {
                    val r = withContext(Dispatchers.IO) { SysPower.shizukuHideSelf(true) }
                    if (!r.success) {
                        NekoLog.warn("Shizuku 隐藏失败（${r.output.take(60)}），回退 alias 方式")
                        SysPower.setHiddenMode(true)
                    } else {
                        NekoLog.ok("Shizuku 隐藏成功（pm hide）")
                    }
                }
            } else {
                SysPower.setHiddenMode(true)
            }
            NekoLog.adjust("开启隐藏模式：桌面图标已隐藏")
            toast(getString(R.string.u50))
        } else {
            // 恢复：Shizuku unhide 优先，回退 alias 启用
            if (SysPower.privilegedChannelReady()) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { SysPower.shizukuHideSelf(false) }
                }
            } else {
                SysPower.setHiddenMode(false)
            }
            NekoLog.adjust("关闭隐藏模式：桌面图标已恢复")
            toast(getString(R.string.u51))
        }
        renderRules()
    }

    private fun disableLock() {
        AppPrefs.lockEnabled = false
        // 关闭密码锁定时同时恢复隐藏模式（否则应用没有可见入口）
        if (AppPrefs.hiddenModeEnabled) {
            AppPrefs.hiddenModeEnabled = false
            SysPower.setHiddenMode(false)
            SysPower.setUninstallBlockedByAdmin(false)
            NekoLog.adjust("隐藏模式已随密码锁定一并关闭，桌面图标已恢复")
            toast(getString(R.string.u51))
        }
        NekoLog.adjust("密码锁定已关闭")
        toast(getString(R.string.u34))
        renderRules()
    }

    /** 首次设置密码对话框（两次输入一致） */
    private fun showSetLockPasswordDialog() {
        val et = EditText(this).apply {
            hint = getString(R.string.u73)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val et2 = EditText(this).apply {
            hint = getString(R.string.u74)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 8)
            addView(et)
            addView(et2)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.u18))
            .setMessage(getString(R.string.u12))
            .setView(box)
            .setPositiveButton(getString(R.string.u71)) { _, _ ->
                val p1 = et.text.toString()
                val p2 = et2.text.toString()
                if (p1.isEmpty()) { toast(getString(R.string.u7)); binding.swLock.isChecked = false; return@setPositiveButton }
                if (p1 != p2) { toast(getString(R.string.u11)); binding.swLock.isChecked = false; return@setPositiveButton }
                AppPrefs.setLockPassword(p1)
                enableLock()
            }
            .setNegativeButton(getString(R.string.u72)) { _, _ ->
                restoringSwitch = true
                binding.swLock.isChecked = false
                restoringSwitch = false
            }
            .setOnCancelListener {
                restoringSwitch = true
                binding.swLock.isChecked = false
                restoringSwitch = false
            }
            .show()
    }

    /** 验证密码对话框（成功回调 onOk；取消回调 onCancel，默认恢复密码锁开关） */
    private fun showVerifyLockPasswordDialog(
        title: String,
        onCancel: (() -> Unit)? = null,
        onOk: () -> Unit
    ) {
        val et = EditText(this).apply {
            hint = getString(R.string.u75)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 8)
            addView(et)
        }
        val cancelAction = { (onCancel ?: { restoreLockSwitch() }).invoke() }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(getString(R.string.u71), null)
            .setNegativeButton(getString(R.string.u72)) { _, _ -> cancelAction() }
            .setOnCancelListener { cancelAction() }
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

    /** 恢复密码锁开关为实际状态（开启/关闭操作被取消时） */
    private fun restoreLockSwitch() {
        restoringSwitch = true
        binding.swLock.isChecked = AppPrefs.lockEnabled
        restoringSwitch = false
    }

    /** 恢复强制篡改键盘开关为实际状态 */
    private fun restoreForceSwitch() {
        restoringSwitch = true
        binding.swForceKeyboard.isChecked = AppPrefs.forceKeyboardEnabled
        restoringSwitch = false
    }

    /** 恢复隐藏模式开关为实际状态 */
    private fun restoreHiddenSwitch() {
        restoringSwitch = true
        binding.swHiddenMode.isChecked = AppPrefs.hiddenModeEnabled
        restoringSwitch = false
    }

    // ---------- 悬浮窗授权 ----------

    private fun openOverlaySettings() {
        overlayPending = true
        try {
            overlayLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
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

    // ---------- 免电 / Root ----------

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

    // ---------- 规则预设 ----------

    private fun selectPresetDialog() {
        val presets = AppPrefs.presetList()
        val names = presets.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.u30))
            .setItems(names) { _, which ->
                AppPrefs.selectPreset(presets[which].first)
                NekoLog.rule("切换规则预设：${presets[which].second}")
                toast(getString(R.string.u6, presets[which].second))
                renderRules()
            }
            .setNegativeButton(getString(R.string.u72), null)
            .show()
    }

    private fun deletePresetDialog() {
        val presets = AppPrefs.presetList()
        if (presets.size <= 1) {
            toast(getString(R.string.u29))
            return
        }
        val current = AppPrefs.activePresetName()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.u22))
            .setMessage(getString(R.string.u8, current))
            .setPositiveButton(getString(R.string.u77)) { _, _ ->
                AppPrefs.deletePreset(AppPrefs.activePresetId())
                NekoLog.rule("删除规则预设：$current")
                toast(getString(R.string.u53, AppPrefs.activePresetName()))
                renderRules()
            }
            .setNegativeButton(getString(R.string.u72), null)
            .show()
    }

    // ---------- 规则列表 ----------

    private fun renderRules() {
        binding.tvActiveRule.text = getString(R.string.u78, AppPrefs.activePresetName())
        binding.swStyleSpaced.isChecked = AppPrefs.styleSpaced
        binding.swStyleUpper.isChecked = AppPrefs.styleUpper
        binding.swAutoSend.isChecked = AppPrefs.autoSend
        binding.swHaptic.isChecked = AppPrefs.hapticEnabled
        binding.swSnap.isChecked = AppPrefs.snapEdges
        binding.swSilentModify.isChecked = AppPrefs.silentModifyEnabled
        binding.swPunctTrigger.isChecked = AppPrefs.punctTriggerEnabled
        binding.swEmoticon.isChecked = AppPrefs.emoticonEnabled
        binding.swLock.isChecked = AppPrefs.lockEnabled
        binding.swHiddenMode.isChecked = AppPrefs.hiddenModeEnabled
        binding.swHeartbeat.isChecked = AppPrefs.heartbeatEnabled
        binding.swCrashRestart.isChecked = AppPrefs.crashRestartEnabled
        if (binding.swForceKeyboard.isChecked != AppPrefs.forceKeyboardEnabled) {
            restoringSwitch = true
            binding.swForceKeyboard.isChecked = AppPrefs.forceKeyboardEnabled
            restoringSwitch = false
        }

        val list = binding.llRuleList
        list.removeAllViews()
        val rules = AppPrefs.rules()
        binding.tvEmptyRules.visibility = if (rules.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE

        rules.forEach { rule ->
            val row = buildRuleRow(rule)
            list.addView(row)
            if (rule != rules.last()) {
                val divider = android.view.View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).apply { background = ContextCompat.getDrawable(this@MainActivity, R.color.divider) }
                }
                list.addView(divider)
            }
        }
    }

    /**
     * 开启强制篡改：先弹免责声明；开关只是开启设置，
     * 点击「启动服务」后才真正生效（服务启动后悬浮球隐藏、自动篡改开始）。
     */
    private fun enableForceKeyboard() {
        showForceKeyboardDisclaimer()
    }

    /** 关闭强制篡改：恢复悬浮按钮 */
    private fun disableForceKeyboard() {
        AppPrefs.forceKeyboardEnabled = false
        FloatingButtonService.reload()
        NekoLog.adjust("关闭强制篡改键盘，已恢复悬浮按钮模式")
        toast(getString(R.string.u24))
    }

    /** 应用心跳保活开关 */
    private fun applyHeartbeat(enabled: Boolean) {
        AppPrefs.heartbeatEnabled = enabled
        NekoLog.adjust(if (enabled) "开启心跳保活（每 60 秒拉活服务）" else "关闭心跳保活")
        if (enabled && AppPrefs.serviceEnabled) {
            FloatingButtonService.start(this)
        } else if (!enabled) {
            FloatingButtonService.cancelHeartbeatGlobal(this)
        }
    }

    /** 恢复心跳保活开关为实际状态 */
    private fun restoreHeartbeatSwitch() {
        restoringSwitch = true
        binding.swHeartbeat.isChecked = AppPrefs.heartbeatEnabled
        restoringSwitch = false
    }

    /** 强制篡改键盘启用前的免责声明（不确认则自动回退开关） */
    private fun showForceKeyboardDisclaimer() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.u21))
            .setMessage(getString(R.string.u115))

            .setPositiveButton(getString(R.string.u91)) { _, _ ->
                AppPrefs.forceKeyboardEnabled = true
                // 开关只是开启设置：服务运行中立即生效（隐藏悬浮球）；未启动则等点击「启动服务」后生效
                if (AppPrefs.serviceEnabled) {
                    FloatingButtonService.reload()
                }
                NekoLog.adjust("开启强制篡改键盘（已确认免责声明，启动服务后生效）")
                toast(if (AppPrefs.serviceEnabled) getString(R.string.u92) else getString(R.string.u93))
            }
            .setNegativeButton(getString(R.string.u72)) { _, _ ->
                restoringSwitch = true
                binding.swForceKeyboard.isChecked = false
                restoringSwitch = false
                NekoLog.warn("强制篡改键盘未启用（未确认免责声明）")
            }
            .setOnCancelListener {
                restoringSwitch = true
                binding.swForceKeyboard.isChecked = false
                restoringSwitch = false
            }
            .show()
    }

    /** 动态构建单条规则行 */
    private fun buildRuleRow(rule: NekoRule): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 12)
        }

        // 类型徽标（点击 = 编辑）
        val badge = TextView(this).apply {
            text = getString(rule.type.resId)
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_theme_primary))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_circle)
            setPadding(18, 6, 18, 6)
            setOnClickListener { showAddRuleDialog(rule) }
        }
        row.addView(badge)

        // 值（点击 = 编辑）
        val valueText = when (rule.type) {
            RuleType.REPLACE -> "${rule.value} → ${rule.replaceTo}"
            RuleType.RANDOM_PREFIX, RuleType.RANDOM_SUFFIX ->
                "${rule.value.ifEmpty { getString(R.string.u94) }} · ${rule.chance}%"
            RuleType.RANDOM_EMOTICON ->
                "${rule.value.ifEmpty { getString(R.string.u95) }} · ${rule.chance}%"
            else -> rule.value
        }
        val tvValue = TextView(this).apply {
            text = valueText
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 14
            }
            setOnClickListener { showAddRuleDialog(rule) }
        }
        row.addView(tvValue)

        // 编辑按钮
        val edit = TextView(this).apply {
            text = getString(R.string.u79)
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_theme_primary))
            gravity = Gravity.CENTER
            setPadding(12, 8, 8, 8)
            setOnClickListener { showAddRuleDialog(rule) }
        }
        row.addView(edit)

        // 开关（密码锁定：需验证）
        val sw = MaterialSwitch(this).apply {
            isChecked = rule.enabled
            setOnCheckedChangeListener { _, checked ->
                if (AppPrefs.lockEnabled) {
                    showVerifyLockPasswordDialog(
                        getString(R.string.u80),
                        onOk = {
                            AppPrefs.updateRule(rule.id) { it.copy(enabled = checked) }
                            NekoLog.rule("规则「${rule.type.label} ${rule.value.take(20)}」已${if (checked) "启用" else "停用"}")
                            renderRules()
                        },
                        onCancel = { renderRules() } // 取消：恢复开关显示
                    )
                    return@setOnCheckedChangeListener
                }
                AppPrefs.updateRule(rule.id) { it.copy(enabled = checked) }
                NekoLog.rule("规则「${rule.type.label} ${rule.value.take(20)}」已${if (checked) "启用" else "停用"}")
            }
        }
        row.addView(sw)

        // 删除（密码锁定：需验证）
        val del = TextView(this).apply {
            text = "✕"
            textSize = 15f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.fg_2))
            gravity = Gravity.CENTER
            setPadding(24, 8, 12, 8)
            setOnClickListener {
                val doDelete = {
                    NekoLog.rule("删除规则：${rule.type.label} ${rule.value.take(20)}")
                    AppPrefs.removeRule(rule.id)
                    renderRules()
                }
                if (AppPrefs.lockEnabled) {
                    showVerifyLockPasswordDialog(getString(R.string.u81), onOk = doDelete, onCancel = {})
                } else {
                    doDelete()
                }
            }
        }
        row.addView(del)

        return row
    }

    // ---------- 添加 / 编辑规则 ----------

    /** 规则编辑锁一次性放行标记（验证通过后本次会话允许打开对话框） */
    private var ruleLockOk = false

    private fun showAddRuleDialog(editRule: NekoRule? = null) {
        // 密码锁定：添加/编辑规则需验证密码（验证通过后本次放行）
        if (AppPrefs.lockEnabled && !ruleLockOk) {
            showVerifyLockPasswordDialog(
                getString(R.string.u82),
                onOk = {
                    ruleLockOk = true
                    showAddRuleDialog(editRule)
                },
                onCancel = {}
            )
            return
        }
        ruleLockOk = false
        // 类型选择器（左侧）
        val typeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(48, 16, 48, 8)
        }
        RuleType.entries.forEach { type ->
            typeGroup.addView(RadioButton(this).apply {
                text = getString(type.resId)
                id = type.ordinal + 1000
            })
        }
        val initialType = editRule?.type ?: RuleType.PREFIX
        typeGroup.check(initialType.ordinal + 1000)

        // 字段容器（右侧，随类型变化）
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 0, 48, 8)
        }

        // 值输入（前缀/后缀/随机值）
        val etValue = EditText(this).apply {
            hint = getString(R.string.u83)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        // 替换检测字
        val etFrom = EditText(this).apply {
            hint = getString(R.string.u84)
        }
        // 替换成
        val etTo = EditText(this).apply {
            hint = getString(R.string.u85)
        }
        // 概率
        val etChance = EditText(this).apply {
            hint = getString(R.string.u86)
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        fun refreshFields(type: RuleType) {
            fields.removeAllViews()
            when (type) {
                RuleType.PREFIX, RuleType.SUFFIX, RuleType.SUFFIX_EACH -> fields.addView(etValue)
                RuleType.RANDOM_PREFIX, RuleType.RANDOM_SUFFIX, RuleType.RANDOM_EMOTICON -> {
                    fields.addView(etValue)
                    fields.addView(etChance)
                }
                RuleType.REPLACE -> {
                    fields.addView(etFrom)
                    fields.addView(etTo)
                }
            }
        }

        // 编辑模式：预填当前规则内容
        if (editRule != null) {
            when (editRule.type) {
                RuleType.PREFIX, RuleType.SUFFIX, RuleType.SUFFIX_EACH -> etValue.setText(editRule.value)
                RuleType.RANDOM_PREFIX, RuleType.RANDOM_SUFFIX, RuleType.RANDOM_EMOTICON -> {
                    etValue.setText(editRule.value)
                    etChance.setText(editRule.chance.toString())
                }
                RuleType.REPLACE -> {
                    etFrom.setText(editRule.value)
                    etTo.setText(editRule.replaceTo)
                }
            }
        }

        typeGroup.setOnCheckedChangeListener { _, checkedId ->
            refreshFields(RuleType.entries[checkedId - 1000])
        }
        refreshFields(initialType)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(typeGroup)
            addView(fields)
        }

        val isEdit = editRule != null
        AlertDialog.Builder(this)
            .setTitle(if (isEdit) getString(R.string.u87) else getString(R.string.u88))
            .setView(content)
            .setPositiveButton(if (isEdit) getString(R.string.u89) else getString(R.string.u90)) { _, _ ->
                val type = RuleType.entries[typeGroup.checkedRadioButtonId - 1000]
                val base = when (type) {
                    RuleType.PREFIX, RuleType.SUFFIX, RuleType.SUFFIX_EACH -> {
                        val v = etValue.text.toString().trim()
                        if (v.isEmpty()) { toast(getString(R.string.u43)); return@setPositiveButton }
                        NekoRule(editRule?.id ?: "r_${System.currentTimeMillis()}", type, v,
                            enabled = editRule?.enabled ?: true)
                    }
                    RuleType.RANDOM_PREFIX, RuleType.RANDOM_SUFFIX -> {
                        val v = etValue.text.toString().trim()
                        if (v.isEmpty()) { toast(getString(R.string.u19)); return@setPositiveButton }
                        val chance = etChance.text.toString().toIntOrNull()?.coerceIn(1, 100) ?: 50
                        NekoRule(editRule?.id ?: "r_${System.currentTimeMillis()}", type, v,
                            chance = chance, enabled = editRule?.enabled ?: true)
                    }
                    RuleType.RANDOM_EMOTICON -> {
                        // 颜文字池可留空 = 使用内置颜文字库
                        val v = etValue.text.toString().trim()
                        val chance = etChance.text.toString().toIntOrNull()?.coerceIn(1, 100) ?: 50
                        NekoRule(editRule?.id ?: "r_${System.currentTimeMillis()}", type, v,
                            chance = chance, enabled = editRule?.enabled ?: true)
                    }
                    RuleType.REPLACE -> {
                        val from = etFrom.text.toString().trim()
                        val to = etTo.text.toString()
                        if (from.isEmpty()) { toast(getString(R.string.u35)); return@setPositiveButton }
                        NekoRule(editRule?.id ?: "r_${System.currentTimeMillis()}", RuleType.REPLACE, from,
                            replaceTo = to, enabled = editRule?.enabled ?: true)
                    }
                }
                if (isEdit) {
                    AppPrefs.updateRule(editRule!!.id) { base }
                    NekoLog.rule("编辑规则：${base.type.label} ${base.value.take(20)}")
                    toast(getString(R.string.u23))
                } else {
                    AppPrefs.addRule(base)
                    NekoLog.rule("新增规则：${base.type.label} ${base.value.take(20)}")
                }
                renderRules()
            }
            .setNegativeButton(getString(R.string.u72), null)
            .show()
    }

    // ---------- 预览 ----------

    private fun runPreview() {
        val sample = binding.etPreviewInput.text.toString().ifEmpty { getString(R.string.u96) }
        binding.tvPreviewOutput.text = TextTransformEngine.transform(sample).text
    }

    // ---------- 状态刷新 ----------

    private fun refreshStatus() {
        val running = AppPrefs.serviceEnabled
        binding.tvStatus.text = if (running) getString(R.string.u97) else getString(R.string.u98)
        binding.tvStatus.setTextColor(
            ContextCompat.getColor(this, if (running) R.color.md_theme_primary else R.color.md_theme_onSurfaceVariant)
        )
        binding.btnToggleService.text = if (running) getString(R.string.u99) else getString(R.string.u100)
        binding.tvStats.text = getString(R.string.u101, AppPrefs.transformCount)

        binding.chipOverlay.isChecked = Settings.canDrawOverlays(this)
        binding.chipAccessibility.isChecked = isAccessibilityEnabled()
        binding.chipBattery.isChecked = SysPower.isIgnoringBatteryOptimizations()
        binding.chipShizuku.isChecked = SysPower.isShizukuAvailable()
        binding.chipAdmin.isChecked = SysPower.isDeviceAdminActive()
        lifecycleScope.launch {
            val root = withContext(Dispatchers.IO) { SysPower.isRootAvailable() }
            binding.chipRoot.isChecked = root
        }

        // 向导步骤状态
        setStep(binding.tvStep1Status, isAccessibilityEnabled(), getString(R.string.u102), getString(R.string.u103))
        setStep(binding.tvStep2Status, SysPower.isDeviceAdminActive(), getString(R.string.u104), getString(R.string.u105))
        setStep(binding.tvStep3Status, SysPower.isIgnoringBatteryOptimizations(), getString(R.string.u106), getString(R.string.u107))
        setStep(binding.tvStep4Status, Settings.canDrawOverlays(this), getString(R.string.u108), getString(R.string.u109))
        setStep(binding.tvStep5Status, SysPower.isShizukuPermissionGranted(), getString(R.string.u110), getString(R.string.u109))

        val done = isAccessibilityEnabled() && Settings.canDrawOverlays(this)
        binding.tvWizardDone.text = if (done) getString(R.string.u111) else ""
    }

    private fun setStep(tv: TextView, ok: Boolean, failText: String, okText: String) {
        tv.text = if (ok) "✓ $okText" else failText
        tv.setTextColor(
            ContextCompat.getColor(this, if (ok) R.color.ok_green else R.color.warn_amber)
        )
    }

    // ---------- 工具 ----------

    private fun isAccessibilityEnabled(): Boolean {
        val cmp = "$packageName/${NekoTypeAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.split(":").any { it.equals(cmp, ignoreCase = true) }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200)
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Throwable) {
        "?"
    }
}
