package com.nekotype.app.ui

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.materialswitch.MaterialSwitch
import com.nekotype.app.R
import com.nekotype.app.databinding.FragmentRulesBinding
import com.nekotype.app.prefs.AppPrefs
import com.nekotype.app.prefs.AppPrefs.NekoRule
import com.nekotype.app.prefs.AppPrefs.RuleType
import com.nekotype.app.transform.TextTransformEngine
import com.nekotype.app.util.BgUtils
import com.nekotype.app.util.NekoLang
import com.nekotype.app.util.NekoLog

/**
 * 规则页：规则预设（加号选择）+ 规则列表 + 行为样式 + 效果预览。
 * 从 MainActivity 规则页逻辑迁移而来，保留全部功能。
 */
class RulesFragment : Fragment() {

    private var _binding: FragmentRulesBinding? = null
    private val binding get() = _binding!!

    /** 规则编辑锁一次性放行标记 */
    private var ruleLockOk = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // inflate 兜底：布局加载失败返回空视图，绝不闪退
        _binding = try {
            FragmentRulesBinding.inflate(inflater, container, false)
        } catch (e: Throwable) {
            NekoLog.error("规则页布局加载失败：${e.javaClass.simpleName}")
            return android.widget.FrameLayout(inflater.context).apply {
                addView(android.widget.TextView(context).apply {
                    text = "规则页加载失败，请尝试切换主题"
                    gravity = android.view.Gravity.CENTER
                })
            }
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // inflate 兜底失败时 _binding 为 null，直接返回避免 NPE
        val b = _binding ?: return
        try {
        BgUtils.apply(b.root)

        // 规则预设：加号 = 新建预设（把当前规则保存为新预设），选择 = 切换已有预设
        b.btnAddPreset.setOnClickListener { showNewPresetDialog() }
        b.btnSelectRule.setOnClickListener { selectPresetDialog() }
        b.btnDeleteRule.setOnClickListener { deletePresetDialog() }

        // 添加规则
        b.btnAddRule.setOnClickListener { showAddRuleDialog() }

        // 行为与样式
        b.swStyleSpaced.setOnCheckedChangeListener { _, v -> AppPrefs.styleSpaced = v }
        b.swStyleUpper.setOnCheckedChangeListener { _, v -> AppPrefs.styleUpper = v }
        b.swAutoSend.setOnCheckedChangeListener { _, v -> AppPrefs.autoSend = v }
        b.swHaptic.setOnCheckedChangeListener { _, v -> AppPrefs.hapticEnabled = v }
        b.swSnap.setOnCheckedChangeListener { _, v -> AppPrefs.snapEdges = v }
        b.swSilentModify.setOnCheckedChangeListener { _, v ->
            AppPrefs.silentModifyEnabled = v
            NekoLog.adjust(if (v) "开启静默修改（Shizuku 直写）" else "关闭静默修改")
            if (v) toast(getString(R.string.u27))
        }
        b.swPunctTrigger.setOnCheckedChangeListener { _, v ->
            AppPrefs.punctTriggerEnabled = v
            NekoLog.adjust(if (v) "开启标点触发（打完一句才改）" else "关闭标点触发")
        }
        // 自定义触发标点：文本变化时保存
        b.etPunctChars.setText(AppPrefs.punctTriggerChars)
        b.etPunctChars.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val v = s?.toString() ?: ""
                if (v != AppPrefs.punctTriggerChars) AppPrefs.punctTriggerChars = v
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { }
        })
        b.swEmoticon.setOnCheckedChangeListener { _, v ->
            AppPrefs.emoticonEnabled = v
            NekoLog.adjust(if (v) "开启随机颜文字（每条自动追加）" else "关闭随机颜文字")
        }
        b.swDeleteOptimize.setOnCheckedChangeListener { _, v ->
            AppPrefs.deleteOptimizeEnabled = v
            NekoLog.adjust(if (v) "开启删除优化（删字时不改写）" else "关闭删除优化")
        }
        b.swVoiceOptimize.setOnCheckedChangeListener { _, v ->
            AppPrefs.voiceInputOptimizeEnabled = v
            NekoLog.adjust(if (v) "开启语音输入优化（停顿 ${AppPrefs.voiceDebounceMs / 1000.0}s 再改写）" else "关闭语音输入优化")
        }
        b.swPriorityGlobal.setOnCheckedChangeListener { _, v ->
            AppPrefs.priorityGlobalEnabled = v
            NekoLog.adjust(if (v) "开启按等级全局执行规则" else "关闭按等级全局执行（改为类别内排序）")
        }

        // 预览
        b.btnPreview.setOnClickListener { runPreview() }

        renderRules()
        } catch (e: Throwable) {
            NekoLog.error("规则页初始化失败：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        val b = _binding ?: return
        try {
            BgUtils.apply(b.root)
            NekoLang.apply(b.root)
            renderRules()
        } catch (_: Throwable) { }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && view != null) {
            try { renderRules() } catch (_: Throwable) { }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ---------- 规则预设 ----------

    private fun selectPresetDialog() {
        val presets = AppPrefs.presetList()
        val currentId = AppPrefs.activePresetId()
        // 列表项带"规则条数 + 当前标记"：预设重名时也能一眼看出到底切没切过去
        val names = presets.map { (id, name) ->
            val count = AppPrefs.ruleCountOf(id)
            buildString {
                append(name)
                append("（")
                append(count)
                append(" 条规则）")
                if (id == currentId) append("  ✓ 当前")
            }
        }.toTypedArray()
        NekoDialog.builder(requireContext())
            .setTitle(getString(R.string.u30))
            .setItems(names) { _, which ->
                AppPrefs.selectPreset(presets[which].first)
                NekoLog.rule("切换规则预设：${presets[which].second}")
                toast(getString(R.string.u6, AppPrefs.activePresetName()))
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
        NekoDialog.builder(requireContext())
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

    /** 新建预设：把当前规则保存为新预设 */
    private fun showNewPresetDialog() {
        val inName = NekoDialog.input(requireContext(), "输入预设名称")
        val box = NekoDialog.column(requireContext(), inName)
        NekoDialog.builder(requireContext())
            .setTitle("新建规则预设")
            .setMessage("将当前规则另存为新预设")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val name = NekoDialog.textOf(inName).trim()
                if (name.isEmpty()) { toast("名称不能为空"); return@setPositiveButton }
                if (AppPrefs.hasPresetName(name)) { toast("已存在同名预设"); return@setPositiveButton }
                AppPrefs.createPreset(
                    name = name,
                    rules = AppPrefs.rules().toList(),
                    switchTo = true,
                    styleSpaced = AppPrefs.styleSpaced,
                    styleUpper = AppPrefs.styleUpper
                )
                NekoLog.rule("新建规则预设：$name")
                toast("已创建并切换到「$name」")
                renderRules()
            }
            .setNegativeButton(getString(R.string.u72), null)
            .show()
    }

    // ---------- 规则列表 ----------

    private fun renderRules() {
        val b = _binding ?: return
        b.tvActiveRule.text = getString(R.string.u78, AppPrefs.activePresetName())
        b.swStyleSpaced.isChecked = AppPrefs.styleSpaced
        b.swStyleUpper.isChecked = AppPrefs.styleUpper
        b.swAutoSend.isChecked = AppPrefs.autoSend
        b.swHaptic.isChecked = AppPrefs.hapticEnabled
        b.swSnap.isChecked = AppPrefs.snapEdges
        b.swSilentModify.isChecked = AppPrefs.silentModifyEnabled
        b.swPunctTrigger.isChecked = AppPrefs.punctTriggerEnabled
        b.swEmoticon.isChecked = AppPrefs.emoticonEnabled
        b.swDeleteOptimize.isChecked = AppPrefs.deleteOptimizeEnabled
        b.swVoiceOptimize.isChecked = AppPrefs.voiceInputOptimizeEnabled
        b.swPriorityGlobal.isChecked = AppPrefs.priorityGlobalEnabled

        val list = b.llRuleList
        list.removeAllViews()
        val rules = AppPrefs.rules()
        b.tvEmptyRules.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE

        rules.forEach { rule ->
            val row = buildRuleRow(rule)
            list.addView(row)
            if (rule != rules.last()) {
                val divider = View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).apply { background = ContextCompat.getDrawable(requireContext(), R.color.divider) }
                }
                list.addView(divider)
            }
        }
    }

    /** 动态构建单条规则行 */
    private fun buildRuleRow(rule: NekoRule): LinearLayout {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 12)
        }

        // 类型徽标（点击 = 编辑）
        val badge = TextView(requireContext()).apply {
            text = getString(rule.type.resId)
            textSize = 11f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.md_theme_primary))
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_circle)
            setPadding(18, 6, 18, 6)
            setOnClickListener { showAddRuleDialog(rule) }
        }
        row.addView(badge)

        // 等级徽标（数字越大越先执行；点击也进编辑）
        val priBadge = TextView(requireContext()).apply {
            text = "P${rule.priority}"
            textSize = 10f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.md_theme_primary))
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_circle)
            setPadding(14, 5, 14, 5)
            (layoutParams as? LinearLayout.LayoutParams)?.marginStart = 10
            setOnClickListener { showAddRuleDialog(rule) }
        }
        row.addView(priBadge)

        // 值（点击 = 编辑）
        val valueText = when (rule.type) {
            RuleType.REPLACE -> "${rule.value} → ${rule.replaceTo}"
            RuleType.RANDOM_PREFIX, RuleType.RANDOM_SUFFIX,
            RuleType.RANDOM_PREFIX_ONCE, RuleType.RANDOM_SUFFIX_ONCE ->
                "${rule.value.ifEmpty { getString(R.string.u94) }} · ${rule.chance}%"
            else -> rule.value
        }
        val tvValue = TextView(requireContext()).apply {
            text = valueText
            textSize = 14f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 14
            }
            setOnClickListener { showAddRuleDialog(rule) }
        }
        row.addView(tvValue)

        // 编辑按钮
        val edit = TextView(requireContext()).apply {
            text = getString(R.string.u79)
            textSize = 13f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.md_theme_primary))
            gravity = Gravity.CENTER
            setPadding(12, 8, 8, 8)
            setOnClickListener { showAddRuleDialog(rule) }
        }
        row.addView(edit)

        // 开关（密码锁定：需验证）
        val sw = MaterialSwitch(requireContext()).apply {
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
                        onCancel = { renderRules() }
                    )
                    return@setOnCheckedChangeListener
                }
                AppPrefs.updateRule(rule.id) { it.copy(enabled = checked) }
                NekoLog.rule("规则「${rule.type.label} ${rule.value.take(20)}」已${if (checked) "启用" else "停用"}")
            }
        }
        row.addView(sw)

        // 删除（密码锁定：需验证）
        val del = TextView(requireContext()).apply {
            text = "✕"
            textSize = 15f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.fg_2))
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

    private fun showAddRuleDialog(editRule: NekoRule? = null) {
        // 密码锁定：添加/编辑规则需验证密码
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

        val typeGroup = RadioGroup(requireContext()).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(48, 16, 48, 8)
        }
        RuleType.entries.forEach { type ->
            typeGroup.addView(RadioButton(requireContext()).apply {
                text = getString(type.resId)
                id = type.ordinal + 1000
            })
        }
        val initialType = editRule?.type ?: RuleType.PREFIX
        typeGroup.check(initialType.ordinal + 1000)

        val fields = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 0, 48, 8)
        }

        // Material 输入框（替代裸 EditText：圆角填充背景 + 浮动标签）
        val inValue = NekoDialog.input(requireContext(), getString(R.string.u83))
        val inFrom = NekoDialog.input(requireContext(), getString(R.string.u84))
        val inTo = NekoDialog.input(requireContext(), getString(R.string.u85))
        val inChance = NekoDialog.input(requireContext(), getString(R.string.u86), numeric = true)
        // 优先级（等级）：1-100，数字越大越先执行；所有规则类型都显示
        val inPriority = NekoDialog.input(requireContext(), getString(R.string.i42), numeric = true)

        fun refreshFields(type: RuleType) {
            fields.removeAllViews()
            when (type) {
                RuleType.PREFIX, RuleType.SUFFIX -> fields.addView(inValue)
                RuleType.RANDOM_PREFIX, RuleType.RANDOM_SUFFIX,
                RuleType.RANDOM_PREFIX_ONCE, RuleType.RANDOM_SUFFIX_ONCE -> {
                    fields.addView(inValue)
                    fields.addView(inChance)
                }
                RuleType.REPLACE -> {
                    fields.addView(inFrom)
                    fields.addView(inTo)
                }
            }
            fields.addView(inPriority)
        }

        if (editRule != null) {
            when (editRule.type) {
                RuleType.PREFIX, RuleType.SUFFIX -> inValue.editText?.setText(editRule.value)
                RuleType.RANDOM_PREFIX, RuleType.RANDOM_SUFFIX,
                RuleType.RANDOM_PREFIX_ONCE, RuleType.RANDOM_SUFFIX_ONCE -> {
                    inValue.editText?.setText(editRule.value)
                    inChance.editText?.setText(editRule.chance.toString())
                }
                RuleType.REPLACE -> {
                    inFrom.editText?.setText(editRule.value)
                    inTo.editText?.setText(editRule.replaceTo)
                }
            }
        }
        inPriority.editText?.setText((editRule?.priority ?: 50).toString())

        typeGroup.setOnCheckedChangeListener { _, checkedId ->
            refreshFields(RuleType.entries[checkedId - 1000])
        }
        refreshFields(initialType)

        // 内容较多（8 个规则类型 + 字段）→ 套滚动，等级输入框不会再被挤出屏幕
        val content = NekoDialog.scroll(NekoDialog.column(requireContext(), typeGroup, fields))

        val isEdit = editRule != null
        NekoDialog.builder(requireContext())
            .setTitle(if (isEdit) getString(R.string.u87) else getString(R.string.u88))
            .setView(content)
            .setPositiveButton(if (isEdit) getString(R.string.u89) else getString(R.string.u90)) { _, _ ->
                val type = RuleType.entries[typeGroup.checkedRadioButtonId - 1000]
                // 优先级（等级）：1-100，数字越大越先执行；留空 = 50
                val pri = NekoDialog.textOf(inPriority).toIntOrNull()?.coerceIn(1, 100) ?: 50
                val base = when (type) {
                    RuleType.PREFIX, RuleType.SUFFIX -> {
                        val v = NekoDialog.textOf(inValue).trim()
                        if (v.isEmpty()) { toast(getString(R.string.u43)); return@setPositiveButton }
                        NekoRule(editRule?.id ?: "r_${System.currentTimeMillis()}", type, v,
                            priority = pri, enabled = editRule?.enabled ?: true)
                    }
                    RuleType.RANDOM_PREFIX, RuleType.RANDOM_SUFFIX,
                    RuleType.RANDOM_PREFIX_ONCE, RuleType.RANDOM_SUFFIX_ONCE -> {
                        val v = NekoDialog.textOf(inValue).trim()
                        if (v.isEmpty()) { toast(getString(R.string.u19)); return@setPositiveButton }
                        val chance = NekoDialog.textOf(inChance).toIntOrNull()?.coerceIn(1, 100) ?: 50
                        NekoRule(editRule?.id ?: "r_${System.currentTimeMillis()}", type, v,
                            chance = chance, priority = pri, enabled = editRule?.enabled ?: true)
                    }
                    RuleType.REPLACE -> {
                        val from = NekoDialog.textOf(inFrom).trim()
                        val to = NekoDialog.textOf(inTo)
                        if (from.isEmpty()) { toast(getString(R.string.u35)); return@setPositiveButton }
                        NekoRule(editRule?.id ?: "r_${System.currentTimeMillis()}", RuleType.REPLACE, from,
                            replaceTo = to, priority = pri, enabled = editRule?.enabled ?: true)
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

    // ---------- 密码验证 ----------

    private fun showVerifyLockPasswordDialog(
        title: String,
        onCancel: (() -> Unit)? = null,
        onOk: () -> Unit
    ) {
        // Material 密码输入框（自带显示/隐藏小眼睛）
        val inPwd = NekoDialog.input(requireContext(), getString(R.string.u75), password = true)
        val box = NekoDialog.column(requireContext(), inPwd)
        val cancelAction = { (onCancel ?: { }).invoke() }
        val dialog = NekoDialog.builder(requireContext())
            .setTitle(title)
            .setView(box)
            .setPositiveButton(getString(R.string.u71), null)
            .setNegativeButton(getString(R.string.u72)) { _, _ -> cancelAction() }
            .setOnCancelListener { cancelAction() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (AppPrefs.verifyLockPassword(NekoDialog.textOf(inPwd))) {
                    dialog.dismiss()
                    onOk()
                } else {
                    toast(getString(R.string.u25))
                    inPwd.editText?.text?.clear()
                }
            }
        }
        dialog.show()
    }

    // ---------- 预览 ----------

    private fun runPreview() {
        val b = _binding ?: return
        val sample = b.etPreviewInput.text.toString().ifEmpty { getString(R.string.u96) }
        b.tvPreviewOutput.text = TextTransformEngine.transform(sample).text
    }

    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}
