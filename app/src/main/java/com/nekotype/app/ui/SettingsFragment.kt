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
import android.widget.TextView
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
import com.nekotype.app.util.NekoLang
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
        // inflate 兜底：布局加载失败返回空视图，绝不闪退（防止特定主题/ROM 组合崩溃）
        _binding = try {
            FragmentSettingsBinding.inflate(inflater, container, false)
        } catch (e: Throwable) {
            NekoLog.error("设置页布局加载失败：${e.javaClass.simpleName}")
            return android.widget.FrameLayout(inflater.context).apply {
                addView(android.widget.TextView(context).apply {
                    text = "设置页加载失败，请尝试切换主题"
                    gravity = android.view.Gravity.CENTER
                })
            }
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (_binding == null) return
        // 每个模块独立 try-catch：一个模块初始化失败不影响其他按钮绑定
        try { BgUtils.apply(binding.root) } catch (_: Throwable) { }
        NekoLog.nav("进入设置页（Fragment）")

        // 自定义背景
        try {
            binding.btnPickBg.setOnClickListener {
                try { bgPicker.launch("image/*") } catch (_: Throwable) { toast(getString(R.string.u33)) }
            }
            binding.btnClearBg.setOnClickListener {
                AppPrefs.customBackgroundPath = ""
                BgUtils.notifyChanged()
                BgUtils.apply(binding.root)
                toast(getString(R.string.u41))
            }
        } catch (_: Throwable) { }

        // 主题（2x2 网格 + 猫娘，手动单选）
        try {
            val themeButtons = mapOf(
                R.id.btnThemeSystem to "system",
                R.id.btnThemeDark to "dark",
                R.id.btnThemeLight to "light",
                R.id.btnThemeStar to "star",
                R.id.btnThemeNeko to "neko"
            )
            themeButtons.forEach { (id, mode) ->
                view.findViewById<com.google.android.material.button.MaterialButton>(id)?.setOnClickListener {
                    switchTheme(mode)
                }
            }
        } catch (_: Throwable) { }

        // 人设语气包
        try { setupPersonaButtons(view) } catch (_: Throwable) { }

        // 情绪小猫概率
        try { setupMoodProbability(view) } catch (_: Throwable) { }

        // 悬浮按钮（Slider 值强制 clamp 到合法范围，防止 IllegalArgumentException 闪退）
        // 注意：slFabOpacity 的 valueFrom=10，不是 0！
        try {
            binding.slFabSize.value = AppPrefs.fabSizeDp.coerceIn(40, 96).toFloat()
            binding.slFabOpacity.value = AppPrefs.fabOpacity.coerceIn(10, 100).toFloat()
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
        } catch (_: Throwable) { }

        // 开机自启
        try {
            binding.swAutoStart.isChecked = AppPrefs.autoStartEnabled
            binding.swAutoStart.setOnCheckedChangeListener { _, checked ->
                if (!checked && AppPrefs.lockEnabled) {
                    toast(getString(R.string.u20))
                    binding.swAutoStart.isChecked = true
                    return@setOnCheckedChangeListener
                }
                AppPrefs.autoStartEnabled = checked
            }
        } catch (_: Throwable) { }

        // 数据导入导出
        try {
            binding.btnExportConfig.setOnClickListener { exportConfig() }
            binding.btnImportConfig.setOnClickListener { showImportDialog() }
        } catch (_: Throwable) { }

        // 黑名单
        try {
            binding.btnBlacklist.setOnClickListener {
                startActivity(Intent(requireContext(), BlacklistActivity::class.java))
            }
        } catch (_: Throwable) { }

        // 支持与反馈
        try {
            binding.ivSponsorQr.setOnClickListener { showSponsorDialog() }
            binding.btnFeedback.setOnClickListener { sendFeedbackEmail() }
            binding.btnOpenTerminal.setOnClickListener {
                startActivity(Intent(requireContext(), TerminalActivity::class.java))
            }
            binding.btnCopyGroup.setOnClickListener {
                copyToClipboard("4ldb0biz5")
                tryOpenQqChannel()
            }
        } catch (_: Throwable) { }

        // 刷新显示
        try {
            refreshTheme()
            refreshVersion()
            refreshStatsDaily()
        } catch (_: Throwable) { }
        // 隐私卡下方追加三份法律文档入口（与免责声明弹窗共用同一份文本，单一来源）
        try {
            appendLegalEntries()
        } catch (_: Throwable) { }
    }

    /** 在设置页隐私卡内追加：免责声明 / 隐私政策 / 第三方信息共享清单 / 开源许可 */
    private fun appendLegalEntries() {
        val box = _binding?.llLegal ?: return
        // 已追加过就不重复（onViewCreated 只跑一次，这里再兜一层）
        if (box.childCount > 2) return
        fun divider() = android.view.View(requireContext()).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1
            )
            setBackgroundColor(0x1F888888)
        }
        box.addView(divider())
        // 免责声明：同意过一次后首次弹窗不再出现，这里随时可查阅；长按可重置，下次启动重新弹出
        val rowDisclaimer = NekoDialog.row(requireContext(), getString(R.string.i46)) {
            NekoDialog.showDoc(requireContext(), getString(R.string.i46), getString(R.string.i47))
        }
        rowDisclaimer.setOnLongClickListener {
            AppPrefs.disclaimerAccepted = false
            toast(getString(R.string.i60))
            true
        }
        box.addView(rowDisclaimer)
        box.addView(divider())
        box.addView(NekoDialog.row(requireContext(), getString(R.string.i55)) {
            NekoDialog.showDoc(requireContext(), getString(R.string.i55), LegalDocs.privacy())
        })
        box.addView(divider())
        box.addView(NekoDialog.row(requireContext(), getString(R.string.i56)) {
            NekoDialog.showDoc(requireContext(), getString(R.string.i56), LegalDocs.thirdParty())
        })
        box.addView(divider())
        box.addView(NekoDialog.row(requireContext(), getString(R.string.i57)) {
            NekoDialog.showDoc(requireContext(), getString(R.string.i57), LegalDocs.licenses())
        })
    }

    override fun onResume() {
        super.onResume()
        if (_binding == null) return
        try {
            NekoLang.apply(binding.root)
            refreshTheme()
            refreshStatsDaily()
            lifecycleScope.launch {
                val root = withContext(Dispatchers.IO) { SysPower.isRootAvailable() }
                _binding?.tvDetails?.text = buildDetails(root)
            }
        } catch (_: Throwable) { }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && view != null) {
            refreshTheme()
            refreshStatsDaily()
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
        // 猫娘主题同步开启猫娘用语
        AppPrefs.nekoMode = (mode == "neko")
        applyThemeMode()
        // 立即刷新当前页面背景
        BgUtils.apply(binding.root)
        refreshTheme()
        // 重建 Activity 让全局主题生效
        requireActivity().recreate()
    }

    private fun applyThemeMode() {
        when (AppPrefs.themeMode) {
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "star" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            "neko" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    private fun refreshTheme() {
        if (_binding == null) return
        val selectedId = when (AppPrefs.themeMode) {
            "dark" -> R.id.btnThemeDark
            "light" -> R.id.btnThemeLight
            "star" -> R.id.btnThemeStar
            "neko" -> R.id.btnThemeNeko
            else -> R.id.btnThemeSystem
        }
        listOf(R.id.btnThemeSystem, R.id.btnThemeDark, R.id.btnThemeLight, R.id.btnThemeStar, R.id.btnThemeNeko).forEach { id ->
            view?.findViewById<com.google.android.material.button.MaterialButton>(id)?.isChecked = (id == selectedId)
        }
        binding.tvThemeHint.text = when (AppPrefs.themeMode) {
            "dark" -> getString(R.string.u134)
            "light" -> getString(R.string.u135)
            "star" -> getString(R.string.theme_star_hint)
            "neko" -> "已启用猫娘主题喵~ 全UI已切换为猫娘用语"
            else -> getString(R.string.u136)
        }
    }

    // ---------- 统计 ----------

    private fun refreshStatsDaily() {
        if (_binding == null) return
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
        // Material 多行输入框（替代裸 EditText + 自定义按钮行，统一成交互规范的对话框按钮）
        val inText = NekoDialog.input(requireContext(), getString(R.string.u129), multiline = true)
        try {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.let {
                if (it.startsWith("{") && it.contains("NekoType")) inText.editText?.setText(it)
            }
        } catch (_: Throwable) { }

        val dialog = NekoDialog.builder(requireContext())
            .setTitle(getString(R.string.u39))
            .setMessage(getString(R.string.u42))
            .setView(NekoDialog.column(requireContext(), inText))
            .setPositiveButton(getString(R.string.u130)) { _, _ ->
                val text = NekoDialog.textOf(inText).trim()
                if (text.isEmpty()) { toast(getString(R.string.u9)); return@setPositiveButton }
                val err = AppPrefs.importConfigText(text)
                if (err != null) {
                    toast(err)
                } else {
                    toast(getString(R.string.u0))
                    applyThemeMode()
                    refreshStatsDaily()
                    BgUtils.apply(binding.root)
                }
            }
            .setNegativeButton(getString(R.string.u72), null)
            .create()
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
        if (_binding == null) return
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
        NekoDialog.builder(requireContext())
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

    // ================= 人设语气包 =================

    private fun setupPersonaButtons(view: View) {
        val personas = mapOf(
            R.id.btnPersonaLoli to "萝莉语",
            R.id.btnPersonaAncient to "古风文言",
            R.id.btnPersonaDub to "译制片腔",
            R.id.btnPersonaSarcasm to "阴阳怪气",
            R.id.btnPersonaMars to "火星文",
            R.id.btnPersonaYandere to "病娇",
            R.id.btnPersonaFuta to "雌小鬼",
            R.id.btnPersonaNeko to "猫娘"
        )
        personas.forEach { (id, name) ->
            view.findViewById<TextView>(id)?.setOnClickListener {
                applyPersona(name)
            }
        }
    }

    private fun setupMoodProbability(view: View) {
        val seek = view.findViewById<android.widget.SeekBar>(R.id.seekMoodProb)
        val tvVal = view.findViewById<TextView>(R.id.tvMoodProbVal)
        val tvHint = view.findViewById<TextView>(R.id.tvMoodProbHint)
        seek?.progress = AppPrefs.nekoMoodProbability
        tvVal?.text = "${AppPrefs.effectiveMoodProbability}%"
        tvHint?.text = if (AppPrefs.nekoMode) {
            getString(R.string.i30, AppPrefs.effectiveMoodProbability, AppPrefs.nekoMoodProbability)
        } else {
            getString(R.string.i28)
        }
        seek?.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, v: Int, fromUser: Boolean) {
                AppPrefs.nekoMoodProbability = v
                tvVal?.text = "${AppPrefs.effectiveMoodProbability}%"
                tvHint?.text = if (AppPrefs.nekoMode) {
                    getString(R.string.i30, AppPrefs.effectiveMoodProbability, v)
                } else {
                    getString(R.string.i28)
                }
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
    }

    private fun applyPersona(name: String) {
        if (AppPrefs.hasPresetName(name)) {
            // 已存在则直接切换
            val preset = AppPrefs.presetList().firstOrNull { it.second == name }
            if (preset != null) {
                AppPrefs.selectPreset(preset.first)
                toast("已切换到「$name」")
                NekoLog.adjust("切换人设语气包：$name")
                return
            }
        }
        val rules = when (name) {
            "萝莉语" -> buildLoliRules()
            "古风文言" -> buildAncientRules()
            "译制片腔" -> buildDubRules()
            "阴阳怪气" -> buildSarcasmRules()
            "火星文" -> buildMarsRules()
            "病娇" -> buildYandereRules()
            "雌小鬼" -> buildFutaRules()
            "猫娘" -> buildNekoRules()
            else -> emptyList()
        }
        if (rules.isEmpty()) {
            toast("生成规则失败")
            return
        }
        AppPrefs.createPreset(name = name, rules = rules, switchTo = true)
        toast("已创建并切换到「$name」")
        NekoLog.adjust("创建人设语气包：$name（${rules.size}条规则）")
    }

    private fun rule(id: String, type: AppPrefs.RuleType, value: String, replaceTo: String = "", chance: Int = 50) =
        AppPrefs.NekoRule(id = id, type = type, value = value, replaceTo = replaceTo, chance = chance)

    private fun buildLoliRules(): List<AppPrefs.NekoRule> = listOf(
        rule("loli_replace_1", AppPrefs.RuleType.REPLACE, "我", "人家"),
        rule("loli_replace_2", AppPrefs.RuleType.REPLACE, "你", "你酱"),
        rule("loli_replace_3", AppPrefs.RuleType.REPLACE, "什么", "什喵"),
        rule("loli_replace_4", AppPrefs.RuleType.REPLACE, "没有", "才没有呢"),
        rule("loli_suffix", AppPrefs.RuleType.RANDOM_SUFFIX, "呢~|呀~|喵~|的说~|啦~|嘛~", chance = 70)
    )

    private fun buildAncientRules(): List<AppPrefs.NekoRule> = listOf(
        rule("ancient_replace_1", AppPrefs.RuleType.REPLACE, "我", "吾"),
        rule("ancient_replace_2", AppPrefs.RuleType.REPLACE, "你", "汝"),
        rule("ancient_replace_3", AppPrefs.RuleType.REPLACE, "是", "乃"),
        rule("ancient_replace_4", AppPrefs.RuleType.REPLACE, "的", "之"),
        rule("ancient_replace_5", AppPrefs.RuleType.REPLACE, "了", "矣"),
        rule("ancient_replace_6", AppPrefs.RuleType.REPLACE, "吗", "乎"),
        rule("ancient_replace_7", AppPrefs.RuleType.REPLACE, "很", "甚"),
        rule("ancient_replace_8", AppPrefs.RuleType.REPLACE, "不要", "勿"),
        rule("ancient_suffix", AppPrefs.RuleType.SUFFIX, "也")
    )

    private fun buildDubRules(): List<AppPrefs.NekoRule> = listOf(
        rule("dub_prefix", AppPrefs.RuleType.RANDOM_PREFIX, "哦，|听着，|我说，|看在上帝的份上，|老伙计，|我亲爱的朋友，", chance = 60),
        rule("dub_suffix", AppPrefs.RuleType.RANDOM_SUFFIX, "，我的老伙计|，我敢打赌|，这是真的|，相信我|，我向你保证", chance = 50),
        rule("dub_replace_1", AppPrefs.RuleType.REPLACE, "好的", "哦，当然"),
        rule("dub_replace_2", AppPrefs.RuleType.REPLACE, "不行", "哦，这可不行")
    )

    private fun buildSarcasmRules(): List<AppPrefs.NekoRule> = listOf(
        rule("sarcasm_suffix", AppPrefs.RuleType.RANDOM_SUFFIX, "，哦？|，是吗？|，不会吧不会吧|，就这？|，哦~|，笑死|，真的吗|，我不信", chance = 80),
        rule("sarcasm_replace_1", AppPrefs.RuleType.REPLACE, "好", "哦，好呢"),
        rule("sarcasm_replace_2", AppPrefs.RuleType.REPLACE, "行", "哦，行吧"),
        rule("sarcasm_replace_3", AppPrefs.RuleType.REPLACE, "可以", "哦，可以可以"),
        rule("sarcasm_replace_4", AppPrefs.RuleType.REPLACE, "厉害", "哦，厉害厉害")
    )

    private fun buildMarsRules(): List<AppPrefs.NekoRule> = listOf(
        rule("mars_replace_1", AppPrefs.RuleType.REPLACE, "我", "ωǒ"),
        rule("mars_replace_2", AppPrefs.RuleType.REPLACE, "你", "伱"),
        rule("mars_replace_3", AppPrefs.RuleType.REPLACE, "的", "の"),
        rule("mars_replace_4", AppPrefs.RuleType.REPLACE, "是", "④"),
        rule("mars_replace_5", AppPrefs.RuleType.REPLACE, "爱", "じ☆ve"),
        rule("mars_replace_6", AppPrefs.RuleType.REPLACE, "么", "麽"),
        rule("mars_replace_7", AppPrefs.RuleType.REPLACE, "吗", "嗎"),
        rule("mars_replace_8", AppPrefs.RuleType.REPLACE, "了", "嘞"),
        rule("mars_replace_9", AppPrefs.RuleType.REPLACE, "人", "亽"),
        rule("mars_replace_10", AppPrefs.RuleType.REPLACE, "心", "惢"),
        rule("mars_suffix", AppPrefs.RuleType.SUFFIX, "~")
    )

    private fun buildYandereRules(): List<AppPrefs.NekoRule> = listOf(
        rule("yd_prefix", AppPrefs.RuleType.PREFIX, "呵呵..."),
        rule("yd_suffix", AppPrefs.RuleType.SUFFIX, "♡"),
        rule("yd_rand_prefix", AppPrefs.RuleType.RANDOM_PREFIX,
            "你只能是我的|呵呵，又在看别人？|我盯着你哦|不许离开我|你的一切都是我的|为什么不回消息？|我会一直陪着你的|你逃不掉的|眼里只能有我", chance = 55),
        rule("yd_rand_suffix", AppPrefs.RuleType.RANDOM_SUFFIX,
            "...呵呵|你逃不掉的♡|只能是我的|我好喜欢你啊|不许不理我|你的心只能属于我|呵呵，真可爱|想把你藏起来|永远在一起吧", chance = 55),
        rule("yd_rep_1", AppPrefs.RuleType.REPLACE, "喜欢", "只喜欢我一个吧"),
        rule("yd_rep_2", AppPrefs.RuleType.REPLACE, "再见", "不许走"),
        rule("yd_rep_3", AppPrefs.RuleType.REPLACE, "朋友", "只能有我一个"),
        rule("yd_rep_4", AppPrefs.RuleType.REPLACE, "晚安", "梦里也只能想我"),
        rule("yd_rep_5", AppPrefs.RuleType.REPLACE, "哈哈", "呵呵"),
        rule("yd_rep_6", AppPrefs.RuleType.REPLACE, "嗯", "你在敷衍我吗")
    )

    /** 雌小鬼语言包：毒舌嘲讽、嘴硬心软，自称高高在上但被夸会害羞 */
    private fun buildFutaRules(): List<AppPrefs.NekoRule> = listOf(
        rule("futa_rand_prefix", AppPrefs.RuleType.RANDOM_PREFIX,
            "哈？|杂鱼杂鱼~|就这？|哼！|你这种杂鱼也配？|真是的，又要本小姐出手|弱爆了啦", chance = 60),
        rule("futa_rand_suffix", AppPrefs.RuleType.RANDOM_SUFFIX,
            "，杂鱼~|，不行呢~|，垃圾垃圾~|，嘿嘿♪|，弱爆了|，真是拿你没办法", chance = 60),
        rule("futa_rep_1", AppPrefs.RuleType.REPLACE, "你好", "哟，杂鱼你好啊"),
        rule("futa_rep_2", AppPrefs.RuleType.REPLACE, "谢谢", "哼，才不是特意帮你呢"),
        rule("futa_rep_3", AppPrefs.RuleType.REPLACE, "喜欢", "喜欢我？你还差得远呢"),
        rule("futa_rep_4", AppPrefs.RuleType.REPLACE, "厉害", "这算什么，本小姐随便就做到了"),
        rule("futa_rep_5", AppPrefs.RuleType.REPLACE, "好的", "哼，知道了啦"),
        rule("futa_rep_6", AppPrefs.RuleType.REPLACE, "对不起", "知道错了就好，原谅你了♪"),
        rule("futa_rep_7", AppPrefs.RuleType.REPLACE, "你好厉害", "哈？这不是理所当然的吗"),
        rule("futa_rep_8", AppPrefs.RuleType.REPLACE, "晚安", "哼，本小姐才不陪你熬夜呢……不过还是晚安啦")
    )

    /** 猫娘语言包：喵语口癖 + 撒娇卖萌，自称喵 */
    private fun buildNekoRules(): List<AppPrefs.NekoRule> = listOf(
        rule("neko_pkg_rand_prefix", AppPrefs.RuleType.RANDOM_PREFIX,
            "喵~|喵喵？|喵呜~|呜喵~", chance = 50),
        rule("neko_pkg_rand_suffix", AppPrefs.RuleType.RANDOM_SUFFIX,
            "喵~|喵♪|的说喵~|呢喵~|喵呜", chance = 70),
        rule("neko_pkg_rep_1", AppPrefs.RuleType.REPLACE, "好的", "好哒喵~"),
        rule("neko_pkg_rep_2", AppPrefs.RuleType.REPLACE, "知道了", "知道啦喵~"),
        rule("neko_pkg_rep_3", AppPrefs.RuleType.REPLACE, "嗯", "嗯喵~"),
        rule("neko_pkg_rep_4", AppPrefs.RuleType.REPLACE, "谢谢", "谢谢喵~"),
        rule("neko_pkg_rep_5", AppPrefs.RuleType.REPLACE, "不要", "不要嘛喵~"),
        rule("neko_pkg_rep_6", AppPrefs.RuleType.REPLACE, "你好", "喵呜~你好呀"),
        rule("neko_pkg_rep_7", AppPrefs.RuleType.REPLACE, "晚安", "晚安喵，要梦到喵哦~"),
        rule("neko_pkg_rep_8", AppPrefs.RuleType.REPLACE, "喜欢", "最喜欢你啦喵~")
    )
}
