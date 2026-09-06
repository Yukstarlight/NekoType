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
        _binding = FragmentRulesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        BgUtils.apply(binding.root)

        // 规则预设：加号按钮 = 选择规则（与选择按钮同功能）
        binding.btnAddPreset.setOnClickListener { selectPresetDialog() }
        binding.btnSelectRule.setOnClickListener { selectPresetDialog() }
        binding.btnDeleteRule.setOnClickListener { deletePresetDialog() }

        // 添加规则
        binding.btnAddRule.setOnClickListener { showAddRuleDialog() }

        // 行为与样式
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

        // 预览
        binding.btnPreview.setOnClickListener { runPreview() }

        renderRules()
    }

    override fun onResume() {
        super.onResume()
        BgUtils.apply(binding.root)
        renderRules()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ---------- 规则预设 ----------

    private fun selectPresetDialog() {
        val presets = AppPrefs.presetList()
        val names = presets.map { it.second }.toTypedArray()
        AlertDialog.Builder(requireContext())
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
        AlertDialog.Builder(requireContext())
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

        val list = binding.llRuleList
        list.removeAllViews()
        val rules = AppPrefs.rules()
        binding.tvEmptyRules.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE

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

        // 值（点击 = 编辑）
        val valueText = when (rule.type) {
            RuleType.REPLACE -> "${rule.value} → ${rule.replaceTo}"
            RuleType.RANDOM_PREFIX, RuleType.RANDOM_SUFFIX ->
                "${rule.value.ifEmpty { getString(R.string.u94) }} · ${rule.chance}%"
            RuleType.RANDOM_EMOTICON ->
                "${rule.value.ifEmpty { getString(R.string.u95) }} · ${rule.chance}%"
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

        val etValue = EditText(requireContext()).apply {
            hint = getString(R.string.u83)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val etFrom = EditText(requireContext()).apply { hint = getString(R.string.u84) }
        val etTo = EditText(requireContext()).apply { hint = getString(R.string.u85) }
        val etChance = EditText(requireContext()).apply {
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

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(typeGroup)
            addView(fields)
        }

        val isEdit = editRule != null
        AlertDialog.Builder(requireContext())
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

    // ---------- 密码验证 ----------

    private fun showVerifyLockPasswordDialog(
        title: String,
        onCancel: (() -> Unit)? = null,
        onOk: () -> Unit
    ) {
        val et = EditText(requireContext()).apply {
            hint = getString(R.string.u75)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 8)
            addView(et)
        }
        val cancelAction = { (onCancel ?: { }).invoke() }
        val dialog = AlertDialog.Builder(requireContext())
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

    // ---------- 预览 ----------

    private fun runPreview() {
        val sample = binding.etPreviewInput.text.toString().ifEmpty { getString(R.string.u96) }
        binding.tvPreviewOutput.text = TextTransformEngine.transform(sample).text
    }

    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}
