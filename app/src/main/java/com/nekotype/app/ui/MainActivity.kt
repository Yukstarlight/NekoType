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

    private lateinit var binding: ActivityMainBinding
    private val shizukuRequestCode = 1001

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
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        BgUtils.apply(binding.root)
        NekoLog.nav("应用启动（NekoType ${versionName()}）")

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
        binding.btnStep5Basic.setOnClickListener {
            AppPrefs.privilegeMode = "basic"
            NekoLog.adjust("已切换到基础模式")
            toast("已切换到基础模式（仅悬浮窗 + 无障碍）")
            refreshStatus()
        }

        // ---- 系统能力 ----
        binding.btnBatteryPriv.setOnClickListener { grantBatteryPrivileged() }
        binding.btnRoot.setOnClickListener { testRoot() }

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

        // ---- 预览 ----
        binding.btnPreview.setOnClickListener { runPreview() }

        requestNotificationPermission()
        refreshStatus()
        renderRules()
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

    private fun testRoot() {
        lifecycleScope.launch {
            val root = withContext(Dispatchers.IO) { SysPower.isRootAvailable() }
            if (!root) {
                binding.tvPrivLog.text = "未检测到 Root（Magisk / KernelSU / APatch）"
                NekoLog.warn("未检测到 Root")
                toast("未检测到 Root")
                return@launch
            }
            val r = withContext(Dispatchers.IO) { SysPower.execShell("id") }
            binding.tvPrivLog.text = "通道: ${r.channel}\n输出: ${r.output}"
            NekoLog.ok("Root 可用，通道 ${r.channel}")
            toast("Root 可用，通道: ${r.channel}")
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

        // 开关
        val sw = MaterialSwitch(this).apply {
            isChecked = rule.enabled
            setOnCheckedChangeListener { _, checked ->
                AppPrefs.updateRule(rule.id) { it.copy(enabled = checked) }
                NekoLog.rule("规则「${rule.type.label} ${rule.value.take(20)}」已${if (checked) "启用" else "停用"}")
            }
        }
        row.addView(sw)

        // 删除
        val del = TextView(this).apply {
            text = "✕"
            textSize = 15f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.fg_2))
            gravity = Gravity.CENTER
            setPadding(24, 8, 12, 8)
            setOnClickListener {
                NekoLog.rule("删除规则：${rule.type.label} ${rule.value.take(20)}")
                AppPrefs.removeRule(rule.id)
                renderRules()
            }
        }
        row.addView(del)

        return row
    }

    // ---------- 添加 / 编辑规则 ----------

    private fun showAddRuleDialog(editRule: NekoRule? = null) {
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
                RuleType.PREFIX, RuleType.SUFFIX -> fields.addView(etValue)
                RuleType.RANDOM_PREFIX, RuleType.RANDOM_SUFFIX -> {
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
                RuleType.PREFIX, RuleType.SUFFIX -> etValue.setText(editRule.value)
                RuleType.RANDOM_PREFIX, RuleType.RANDOM_SUFFIX -> {
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
                    RuleType.PREFIX, RuleType.SUFFIX -> {
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
        binding.tvPreviewOutput.text = TextTransformEngine.transform(sample)
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
