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
                if (grantResult == PackageManager.PERMISSION_GRANTED) "Shizuku 授权成功 ✅"
                else "Shizuku 授权被拒绝 ❌"
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
                .setTitle("安全警告")
                .setMessage("检测到 NekoType 已被篡改（签名不匹配或运行在 Hook 框架下）。\n\n为保护你的安全与密码锁有效性，本应用已停止运行。\n请从官方渠道重新安装。")
                .setPositiveButton("退出") { _, _ -> finish() }
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
            if (v) toast("静默修改已开启（需 Shizuku 或 Root 模式）")
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
                    if (v) "开启强制篡改键盘需要验证密码" else "关闭强制篡改键盘需要验证密码",
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
                    toast("请先开启密码锁定")
                    restoreHiddenSwitch()
                    return@setOnCheckedChangeListener
                }
                showVerifyLockPasswordDialog(
                    "开启隐藏模式需要验证密码",
                    onOk = { applyHiddenMode(true) },
                    onCancel = { restoreHiddenSwitch() }
                )
            } else {
                showVerifyLockPasswordDialog(
                    "关闭隐藏模式需要验证密码",
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
                    "关闭心跳保活需要验证密码",
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
            showVerifyLockPasswordDialog("停止服务需要验证密码") {
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
                toast("服务已停止")
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
                showVerifyLockPasswordDialog("停止服务需要验证密码") { doToggleService(false) }
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
                toast("服务已启动，悬浮按钮常驻屏幕边缘")
            } else {
                NekoLog.warn("启动服务失败：未授予悬浮窗权限")
                toast("请先授予「显示在其他应用上层」权限")
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
            showVerifyLockPasswordDialog("开启密码锁定需要验证密码") { enableLock() }
        } else {
            showSetLockPasswordDialog()
        }
    }

    /** 用户关闭密码锁定：需验证密码 */
    private fun onLockDisableRequested() {
        showVerifyLockPasswordDialog("关闭密码锁定需要验证密码") { disableLock() }
    }

    private fun enableLock() {
        AppPrefs.lockEnabled = true
        // 密码锁定 = 强制开机自启（重启后服务自动回来）
        AppPrefs.autoStartEnabled = true
        NekoLog.adjust("密码锁定已开启（开机自启已联动开启）")
        toast("密码锁定已开启，开机自启已联动开启")
        renderRules()
        // 开启密码锁定后：询问是否开启隐藏模式
        askHiddenMode()
    }

    /** 开启密码锁定后询问是否开启隐藏模式（不强制） */
    private fun askHiddenMode() {
        if (AppPrefs.hiddenModeEnabled) return
        AlertDialog.Builder(this)
            .setTitle("隐藏模式")
            .setMessage(
                "是否开启「隐藏模式」？\n\n" +
                        "开启后：\n" +
                        "· 桌面图标消失，后台无法显示，别人找不到、杀不掉\n" +
                        "· 应用名称保持原名（不加密）\n" +
                        "· 心跳保活自动开启，通知栏「停止服务」为唯一恢复入口\n\n" +
                        "⚠️ 强烈建议（备用入口）：\n" +
                        "1. 下拉通知栏，把「NekoType」磁贴添加到快捷设置面板——即使服务被杀，磁贴也能点开恢复\n" +
                        "2. 华为手机：设置 → 应用 → 应用启动管理 → NekoType → 设为「手动管理」并允许自启动/后台活动，否则系统会拦截服务复活\n\n" +
                        "可在行为与样式中随时关闭（需密码）。"
            )
            .setPositiveButton("开启") { _, _ -> applyHiddenMode(true) }
            .setNegativeButton("暂不", null)
            .show()
    }

    /** 隐藏模式：隐藏/恢复桌面图标（Shizuku pm hide 优先，回退 alias；设备管理员防卸载） */
    private fun applyHiddenMode(enabled: Boolean) {
        if (enabled) {
            // 前置条件 1：服务必须已启动并常驻 —— 通知栏「停止服务」是唯一恢复入口
            if (!AppPrefs.serviceEnabled || !Settings.canDrawOverlays(this)) {
                toast("请先点击「启动服务」让悬浮服务常驻，再开启隐藏模式\n（通知栏「停止服务」+ 密码是唯一恢复入口）")
                restoreHiddenSwitch()
                return
            }
            // 前置条件 2：设备管理员必须已激活（防卸载 + 皆成同款路线）
            if (!SysPower.isDeviceAdminActive()) {
                toast("请先激活设备管理员（权限引导第 2 步），再开启隐藏模式")
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
            toast("隐藏模式已开启：图标已隐藏\n心跳保活已自动开启，通知栏「停止服务」+ 密码可恢复")
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
            toast("隐藏模式已关闭，桌面图标已恢复")
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
            toast("隐藏模式已关闭，桌面图标已恢复")
        }
        NekoLog.adjust("密码锁定已关闭")
        toast("密码锁定已关闭")
        renderRules()
    }

    /** 首次设置密码对话框（两次输入一致） */
    private fun showSetLockPasswordDialog() {
        val et = EditText(this).apply {
            hint = "设置锁定密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val et2 = EditText(this).apply {
            hint = "再次输入确认"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 8)
            addView(et)
            addView(et2)
        }
        AlertDialog.Builder(this)
            .setTitle("设置密码")
            .setMessage("首次开启无需旧密码。密码仅以哈希保存在本地，请牢记。")
            .setView(box)
            .setPositiveButton("确定") { _, _ ->
                val p1 = et.text.toString()
                val p2 = et2.text.toString()
                if (p1.isEmpty()) { toast("密码不能为空"); binding.swLock.isChecked = false; return@setPositiveButton }
                if (p1 != p2) { toast("两次输入不一致"); binding.swLock.isChecked = false; return@setPositiveButton }
                AppPrefs.setLockPassword(p1)
                enableLock()
            }
            .setNegativeButton("取消") { _, _ ->
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
            hint = "输入锁定密码"
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
            .setPositiveButton("确定", null)
            .setNegativeButton("取消") { _, _ -> cancelAction() }
            .setOnCancelListener { cancelAction() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (AppPrefs.verifyLockPassword(et.text.toString())) {
                    dialog.dismiss()
                    onOk()
                } else {
                    toast("密码错误")
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
                toast("未检测到 Shizuku 服务。请安装 Shizuku 并用「无线调试」或「ADB」启动")
            }
            SysPower.isShizukuPermissionGranted() -> {
                lifecycleScope.launch {
                    val r = withContext(Dispatchers.IO) { SysPower.execShell("id") }
                    binding.tvPrivLog.text = "通道: ${r.channel}\n输出: ${r.output}"
                    NekoLog.ok("Shizuku 已授权，通道 ${r.channel}")
                    toast("Shizuku 已授权 ✅")
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
            binding.tvPrivLog.text = "通道: ${r.channel}\n输出: ${r.output}"
            if (r.success) {
                NekoLog.ok("电池优化白名单已写入（${r.channel}）")
                toast("电池优化白名单已写入（${r.channel}）✅")
            } else {
                NekoLog.error("免电写入失败：${r.output}")
                toast("免电写入失败：${r.output}")
            }
            refreshStatus()
        }
    }

    // ---------- 规则预设 ----------

    private fun selectPresetDialog() {
        val presets = AppPrefs.presetList()
        val names = presets.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择规则预设")
            .setItems(names) { _, which ->
                AppPrefs.selectPreset(presets[which].first)
                NekoLog.rule("切换规则预设：${presets[which].second}")
                toast("已切换到「${presets[which].second}」")
                renderRules()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deletePresetDialog() {
        val presets = AppPrefs.presetList()
        if (presets.size <= 1) {
            toast("至少保留一套预设")
            return
        }
        val current = AppPrefs.activePresetName()
        AlertDialog.Builder(this)
            .setTitle("删除规则预设")
            .setMessage("确定删除当前预设「$current」吗？删除后不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                AppPrefs.deletePreset(AppPrefs.activePresetId())
                NekoLog.rule("删除规则预设：$current")
                toast("已删除，切换到「${AppPrefs.activePresetName()}」")
                renderRules()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 规则列表 ----------

    private fun renderRules() {
        binding.tvActiveRule.text = "当前：${AppPrefs.activePresetName()}"
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
        toast("已关闭强制篡改，恢复悬浮按钮模式")
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
            .setTitle("免责声明")
            .setMessage(
                "「强制篡改键盘」开启后，在使用原生键盘输入时，输入框内容将被自动按你的规则篡改，并可能自动发送。\n\n" +
                        "请务必确认：\n" +
                        "1. 本功能仅用于本人设备、合法合规的个人用途；\n" +
                        "2. 开启后，所有应用的输入内容都可能被自动改写（包括聊天、搜索、评论等场景），请注意区分场景使用；\n" +
                        "3. 禁止用于骚扰、诈骗、伪造信息、冒充他人等非法用途，由此产生的全部后果由使用者自行承担；\n" +
                        "4. 需要保持无障碍服务开启；随时可关闭本开关恢复正常输入。\n\n" +
                        "点击「我已知晓并同意」即表示你已阅读并接受以上内容。"
            )
            .setPositiveButton("我已知晓并同意") { _, _ ->
                AppPrefs.forceKeyboardEnabled = true
                // 开关只是开启设置：服务运行中立即生效（隐藏悬浮球）；未启动则等点击「启动服务」后生效
                if (AppPrefs.serviceEnabled) {
                    FloatingButtonService.reload()
                }
                NekoLog.adjust("开启强制篡改键盘（已确认免责声明，启动服务后生效）")
                toast(if (AppPrefs.serviceEnabled) "强制篡改键盘已开启" else "强制篡改键盘已开启，点击「启动服务」后生效")
            }
            .setNegativeButton("取消") { _, _ ->
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
            text = rule.type.label
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
                "${rule.value.ifEmpty { "默认池" }} · ${rule.chance}%"
            RuleType.RANDOM_EMOTICON ->
                "${rule.value.ifEmpty { "内置颜文字库" }} · ${rule.chance}%"
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
            text = "编辑"
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
                        "开关规则需要验证密码",
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
                    showVerifyLockPasswordDialog("删除规则需要验证密码", onOk = doDelete, onCancel = {})
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
                "添加/编辑规则需要验证密码",
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
                text = type.label
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
            hint = "内容（随机：用 | 分隔，如 qwq|awa）"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        // 替换检测字
        val etFrom = EditText(this).apply {
            hint = "检测的字，如：的"
        }
        // 替换成
        val etTo = EditText(this).apply {
            hint = "替换成，如：の"
        }
        // 概率
        val etChance = EditText(this).apply {
            hint = "触发概率 %（1-100）"
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
            .setTitle(if (isEdit) "编辑规则" else "添加规则")
            .setView(content)
            .setPositiveButton(if (isEdit) "保存" else "添加") { _, _ ->
                val type = RuleType.entries[typeGroup.checkedRadioButtonId - 1000]
                val base = when (type) {
                    RuleType.PREFIX, RuleType.SUFFIX, RuleType.SUFFIX_EACH -> {
                        val v = etValue.text.toString().trim()
                        if (v.isEmpty()) { toast("内容不能为空"); return@setPositiveButton }
                        NekoRule(editRule?.id ?: "r_${System.currentTimeMillis()}", type, v,
                            enabled = editRule?.enabled ?: true)
                    }
                    RuleType.RANDOM_PREFIX, RuleType.RANDOM_SUFFIX -> {
                        val v = etValue.text.toString().trim()
                        if (v.isEmpty()) { toast("随机池不能为空"); return@setPositiveButton }
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
                        if (from.isEmpty()) { toast("请填写检测的字"); return@setPositiveButton }
                        NekoRule(editRule?.id ?: "r_${System.currentTimeMillis()}", RuleType.REPLACE, from,
                            replaceTo = to, enabled = editRule?.enabled ?: true)
                    }
                }
                if (isEdit) {
                    AppPrefs.updateRule(editRule!!.id) { base }
                    NekoLog.rule("编辑规则：${base.type.label} ${base.value.take(20)}")
                    toast("规则已保存")
                } else {
                    AppPrefs.addRule(base)
                    NekoLog.rule("新增规则：${base.type.label} ${base.value.take(20)}")
                }
                renderRules()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 预览 ----------

    private fun runPreview() {
        val sample = binding.etPreviewInput.text.toString().ifEmpty { "你好，这是一条测试消息！" }
        binding.tvPreviewOutput.text = TextTransformEngine.transform(sample).text
    }

    // ---------- 状态刷新 ----------

    private fun refreshStatus() {
        val running = AppPrefs.serviceEnabled
        binding.tvStatus.text = if (running) "● 运行中" else "○ 已停止"
        binding.tvStatus.setTextColor(
            ContextCompat.getColor(this, if (running) R.color.md_theme_primary else R.color.md_theme_onSurfaceVariant)
        )
        binding.btnToggleService.text = if (running) "停止服务" else "启动服务"
        binding.tvStats.text = "累计 ${AppPrefs.transformCount} 次"

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
        setStep(binding.tvStep1Status, isAccessibilityEnabled(), "未开启", "已开启")
        setStep(binding.tvStep2Status, SysPower.isDeviceAdminActive(), "未激活", "已激活")
        setStep(binding.tvStep3Status, SysPower.isIgnoringBatteryOptimizations(), "未免电", "已免电")
        setStep(binding.tvStep4Status, Settings.canDrawOverlays(this), "未授权", "已授权")
        setStep(binding.tvStep5Status, SysPower.isShizukuPermissionGranted(), "可选（或使用基础模式）", "已授权")

        val done = isAccessibilityEnabled() && Settings.canDrawOverlays(this)
        binding.tvWizardDone.text = if (done) "🎉 核心权限已就绪，可以开始使用了！" else ""
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
