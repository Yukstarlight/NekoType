/*
 * NekoType
 *
 * BSD 2-Clause License
 *
 * Copyright (c) 2026, Yukstarlight
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.nekotype.app.prefs

import android.content.Context
import com.nekotype.app.NekoTypeApp
import org.json.JSONArray
import org.json.JSONObject

/**
 * 所有用户配置的持久化存储（SharedPreferences 单例封装）。
 *
 * v2.3 重构：规则改为【规则列表】模型 —— 每条规则可选类型
 * （前缀 / 后缀 / 随机前缀 / 随机后缀 / 替换文本），随机规则可自定义触发概率；
 * 多套规则预设（选择/删除）继续保留，每套预设拥有独立的规则列表。
 */
object AppPrefs {

    private const val NAME = "nekotype_prefs"
    private val sp by lazy {
        NekoTypeApp.instance.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    // ================= 规则类型 =================

    /**
     * 内置颜文字库（随机颜文字规则留空时使用）：
     * 猫系原创（NekoType 专属，条目不与竞品重复）+ 经典日式颜文字。
     */
    fun builtinEmoticons(): List<String> = listOf(
        // NekoType 原创猫系（全新条目，不撞车）
        "(=ΦωΦ=)", "(=ↀωↀ=)", "(ฅ´・ω・`ฅ)", "ฅ(^・ω・^)ฅ", "(ฅ₌ω₌ฅ)",
        "٩(ฅ́•ฅ̀*)۶", "ฅ(>ω<ฅ)", "(=^◕ω◕^=)", "ฅ(•ω•ฅ)", "ฅ(´▽`ฅ)",
        "(=^ᴗ^=)", "ฅ(๑•̀ㅁ•́ฅ)", "ヾ(ฅ•ω•ฅ)ノ", "ฅ(´ω`ฅ)", "喵ฅ(＾・ω・＾ฅ)",
        "(=；ω；=)ฅ", "ฅ(≧◡≦)ฅ", "(ฅᵔωᵔฅ)", "ฅ^•.₃•^ฅ", "ฅ(≧▽≦)ฅ",
        // 经典日式颜文字
        "(＾▽＾)", "(*≧▽≦)", "ヾ(≧▽≦*)o", "ヽ(●´∀`●)ﾉ", "(*^▽^*)",
        "(｡•̀ᴗ-)✧", "(•̀ᴗ•́)و", "(￣▽￣)ノ", "(*≧ω≦)", "╰(*´︶`*)╯",
        "(๑•̀ㅂ•́)و✧", "(◕‿◕✿)", "(●´ω｀●)", "(´･ω･`)", "(ノ◕ヮ◕)ノ*:･ﾟ✧",
        "(っ˘ω˘ς)", "(≧∇≦)ﾉ", "(＾ω＾)", "(｡♥‿♥｡)", "(≧▽≦)",
        "ヾ(•ω•`)o", "(*´∀`*)", "(・∀・)", "(*´▽`*)", "(￣ω￣;)",
        "ヽ(>∀<☆)ノ", "⊂(・▽・⊂)", "(*/ω＼*)", "(｀・ω・´)", "o(≧▽≦)o",
        "ヾ(＾∇＾)", "(◍•ᴗ•◍)", "✧(≖ ◡ ≖✿)", "(*˘︶˘*).｡.:*♡", "(˶ᵔ ᵕ ᵔ˶)",
        "(*´ω｀*)", "(っ´ω`)っ", "(´｡• ᵕ •｡`)", "ʕ•ᴥ•ʔ", "(￣▽￣)ゞ"
    )

    /** 行为与样式：随机颜文字总开关（开启后每条消息自动加随机颜文字，喵喵同款） */
    var emoticonEnabled: Boolean
        get() = sp.getBoolean("emoticon", false)
        set(v) = sp.edit().putBoolean("emoticon", v).apply()

    enum class RuleType(val label: String) {
        PREFIX("前缀"),
        SUFFIX("后缀"),
        RANDOM_PREFIX("随机前缀"),
        RANDOM_SUFFIX("随机后缀"),
        SUFFIX_EACH("每句后缀"),
        RANDOM_EMOTICON("随机颜文字"),
        REPLACE("替换文本");

        companion object {
            fun fromName(name: String): RuleType =
                entries.firstOrNull { it.name == name } ?: PREFIX
        }
    }

    // ================= 单条规则模型 =================

    data class NekoRule(
        val id: String,
        val type: RuleType,
        /** 前缀/后缀：附加文本；随机：池（| 分隔）；替换：检测的字 */
        val value: String,
        /** 替换文本专用：替换成什么 */
        val replaceTo: String = "",
        /** 随机类型专用：触发概率 1-100 */
        val chance: Int = 50,
        val enabled: Boolean = true
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("type", type.name)
            put("value", value)
            put("replaceTo", replaceTo)
            put("chance", chance)
            put("enabled", enabled)
        }

        companion object {
            fun fromJson(o: JSONObject): NekoRule = NekoRule(
                id = o.optString("id", "rule_${System.currentTimeMillis()}"),
                type = RuleType.fromName(o.optString("type", "PREFIX")),
                value = o.optString("value", ""),
                replaceTo = o.optString("replaceTo", ""),
                chance = o.optInt("chance", 50).coerceIn(1, 100),
                enabled = o.optBoolean("enabled", true)
            )
        }
    }

    // ================= 规则预设模型 =================

    data class RuleConfig(
        val id: String,
        val name: String,
        val rules: List<NekoRule>,
        val styleSpaced: Boolean,
        val styleUpper: Boolean
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("rules", JSONArray().apply { rules.forEach { put(it.toJson()) } })
            put("styleSpaced", styleSpaced)
            put("styleUpper", styleUpper)
        }

        companion object {
            fun fromJson(o: JSONObject): RuleConfig = RuleConfig(
                id = o.optString("id", "default"),
                name = o.optString("name", "默认规则"),
                rules = run {
                    val arr = o.optJSONArray("rules")
                    if (arr != null) {
                        (0 until arr.length()).mapNotNull { i ->
                            try { NekoRule.fromJson(arr.getJSONObject(i)) } catch (_: Throwable) { null }
                        }
                    } else {
                        // 旧版 v2.2 数据迁移：把平铺配置转成规则列表
                        migrateLegacy(o)
                    }
                },
                styleSpaced = o.optBoolean("styleSpaced", false),
                styleUpper = o.optBoolean("styleUpper", false)
            )

            /** 旧版（v2.2 及更早）平铺字段 → 规则列表 */
            private fun migrateLegacy(o: JSONObject): List<NekoRule> {
                val rules = mutableListOf<NekoRule>()
                if (o.optBoolean("prefixEnabled", true)) {
                    val p = o.optString("prefix", "")
                    if (p.isNotEmpty()) rules.add(NekoRule("m_prefix", RuleType.PREFIX, p))
                }
                if (o.optBoolean("suffixEnabled", true)) {
                    val s = o.optString("suffix", "")
                    if (s.isNotEmpty()) rules.add(NekoRule("m_suffix", RuleType.SUFFIX, s))
                }
                if (o.optBoolean("randomPrefixEnabled", false)) {
                    rules.add(
                        NekoRule("m_rp", RuleType.RANDOM_PREFIX, jsonArrToPool(o.optJSONArray("randomPrefixPool")),
                            chance = o.optInt("randomPrefixChance", 50))
                    )
                }
                if (o.optBoolean("randomSuffixEnabled", false)) {
                    rules.add(
                        NekoRule("m_rs", RuleType.RANDOM_SUFFIX, jsonArrToPool(o.optJSONArray("randomPool")),
                            chance = o.optInt("randomSuffixChance", 50))
                    )
                }
                // 旧自定义替换：{"的":"の", ...} JSON 对象
                val custom = o.optJSONObject("customReplacements")
                if (custom != null) {
                    val it = custom.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        val v = custom.optString(k)
                        if (k.isNotEmpty()) rules.add(NekoRule("m_r_${rules.size}", RuleType.REPLACE, k, replaceTo = v))
                    }
                }
                return rules
            }

            private fun jsonArrToPool(a: JSONArray?): String {
                if (a == null) return ""
                return (0 until a.length())
                    .mapNotNull { a.optString(it).takeIf { s -> s.isNotEmpty() } }
                    .joinToString("|")
            }
        }
    }

    // ================= 预设存储 =================

    private fun rulesList(): List<RuleConfig> {
        val raw = sp.getString("rules_json", null)
        if (raw == null) {
            val legacy = RuleConfig(
                id = "default", name = "默认规则",
                rules = emptyList(),
                styleSpaced = sp.getBoolean("style_spaced", false),
                styleUpper = sp.getBoolean("style_upper", false)
            )
            saveRules(listOf(legacy))
            return listOf(legacy)
        }
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                try { RuleConfig.fromJson(arr.getJSONObject(i)) } catch (_: Throwable) { null }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun saveRules(list: List<RuleConfig>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString("rules_json", arr.toString()).apply()
    }

    private fun activeId(): String = sp.getString("active_rule", "default")!!

    private fun activePreset(): RuleConfig {
        val list = rulesList()
        val id = activeId()
        return list.firstOrNull { it.id == id } ?: list.firstOrNull() ?: RuleConfig(
            id = "default", name = "默认规则", rules = emptyList(), styleSpaced = false, styleUpper = false
        )
    }

    private fun updateActivePreset(mutate: (RuleConfig) -> RuleConfig) {
        val list = rulesList().toMutableList()
        val id = activeId()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = mutate(list[idx])
        } else {
            list.add(0, mutate(activePreset()))
        }
        saveRules(list)
    }

    // ================= 预设管理 API =================

    fun presetList(): List<Pair<String, String>> = rulesList().map { it.id to it.name }

    fun activePresetId(): String = activeId()

    fun activePresetName(): String = activePreset().name

    fun selectPreset(id: String) {
        sp.edit().putString("active_rule", id).apply()
    }

    fun deletePreset(id: String) {
        val list = rulesList().filterNot { it.id == id }
        if (list.isEmpty()) return
        saveRules(list)
        if (activeId() == id) selectPreset(list.first().id)
    }

    // ================= 规则 CRUD（作用于当前预设） =================

    fun rules(): List<NekoRule> = activePreset().rules

    fun addRule(rule: NekoRule) {
        updateActivePreset { it.copy(rules = it.rules + rule) }
    }

    fun updateRule(id: String, mutate: (NekoRule) -> NekoRule) {
        updateActivePreset { preset ->
            preset.copy(rules = preset.rules.map { if (it.id == id) mutate(it) else it })
        }
    }

    fun removeRule(id: String) {
        updateActivePreset { it.copy(rules = it.rules.filterNot { r -> r.id == id }) }
    }

    // ---------- 样式（全局，作用于当前预设） ----------
    var styleSpaced: Boolean
        get() = activePreset().styleSpaced
        set(v) = updateActivePreset { it.copy(styleSpaced = v) }
    var styleUpper: Boolean
        get() = activePreset().styleUpper
        set(v) = updateActivePreset { it.copy(styleUpper = v) }

    // ================= 行为（全局） =================
    var autoSend: Boolean
        get() = sp.getBoolean("auto_send", true)
        set(v) = sp.edit().putBoolean("auto_send", v).apply()
    var hapticEnabled: Boolean
        get() = sp.getBoolean("haptic_enabled", true)
        set(v) = sp.edit().putBoolean("haptic_enabled", v).apply()
    var snapEdges: Boolean
        get() = sp.getBoolean("snap_edges", true)
        set(v) = sp.edit().putBoolean("snap_edges", v).apply()

    // ================= 行为与样式（增强） =================

    /** 静默修改：写回被目标应用拒绝时，通过 Shizuku 直接注入文本（无弹窗、无提示） */
    var silentModifyEnabled: Boolean
        get() = sp.getBoolean("silent_modify", false)
        set(v) = sp.edit().putBoolean("silent_modify", v).apply()

    /** 强制篡改键盘：原生键盘输入时自动按规则篡改文本（无需点悬浮按钮） */
    var forceKeyboardEnabled: Boolean
        get() = sp.getBoolean("force_keyboard", false)
        set(v) = sp.edit().putBoolean("force_keyboard", v).apply()

    /** 标点触发模式：强制篡改时，输入以标点结尾（。！？,）才触发篡改（喵喵同款：打完一句才改） */
    var punctTriggerEnabled: Boolean
        get() = sp.getBoolean("punct_trigger", false)
        set(v) = sp.edit().putBoolean("punct_trigger", v).apply()

    // ================= 悬浮按钮位置 =================
    var buttonX: Int
        get() = sp.getInt("button_x", -1)
        set(v) = sp.edit().putInt("button_x", v).apply()
    var buttonY: Int
        get() = sp.getInt("button_y", -1)
        set(v) = sp.edit().putInt("button_y", v).apply()

    // ================= 悬浮按钮 =================
    /** 按钮大小（dp） */
    var fabSizeDp: Int
        get() = sp.getInt("fab_size_dp", 56)
        set(v) = sp.edit().putInt("fab_size_dp", v.coerceIn(40, 96)).apply()

    /** 按钮透明度（1-100%） */
    var fabOpacity: Int
        get() = sp.getInt("fab_opacity", 100)
        set(v) = sp.edit().putInt("fab_opacity", v.coerceIn(10, 100)).apply()

    /** 空闲时收起成小圆点（可选开关） */
    var fabCollapseEnabled: Boolean
        get() = sp.getBoolean("fab_collapse", true)
        set(v) = sp.edit().putBoolean("fab_collapse", v).apply()

    /** 柔光玻璃效果（可选开关，安全实现，无 BLUR_BEHIND） */
    var fabGlassEnabled: Boolean
        get() = sp.getBoolean("fab_glass", true)
        set(v) = sp.edit().putBoolean("fab_glass", v).apply()

    /** 开机自启（可选开关） */
    var autoStartEnabled: Boolean
        get() = sp.getBoolean("auto_start", true)
        set(v) = sp.edit().putBoolean("auto_start", v).apply()

    // ================= 每日统计 =================

    /** 每日变换次数：日期(MM-dd) -> 次数 */
    var dailyStats: Map<String, Long>
        get() {
            val raw = sp.getString("daily_stats", "")!!
            if (raw.isEmpty()) return emptyMap()
            return raw.split(",").mapNotNull { seg ->
                val p = seg.split(":")
                if (p.size == 2) p[0] to (p[1].toLongOrNull() ?: 0L) else null
            }.toMap()
        }
        set(v) = sp.edit().putString("daily_stats", v.entries.joinToString(",") { "${it.key}:${it.value}" }).apply()

    fun incrementToday() {
        val today = java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        val map = dailyStats.toMutableMap()
        map[today] = (map[today] ?: 0L) + 1
        dailyStats = map
    }

    // ================= 配置导入导出 =================

    /** 读取原始规则 JSON（导出用） */
    fun rawRulesJson(): String? = sp.getString("rules_json", null)

    /** 覆盖规则 JSON（导入用） */
    fun replaceRulesJson(json: String) {
        sp.edit().putString("rules_json", json).apply()
    }

    /** 导出全部配置为可读文本（规则 + 行为 + 外观等） */
    fun exportConfigText(): String {
        val sb = StringBuilder()
        sb.append("规则------------------------\n")
        rules().forEach { r ->
            when (r.type) {
                RuleType.PREFIX -> sb.append("前缀：${r.value}\n")
                RuleType.SUFFIX -> sb.append("后缀：${r.value}\n")
                RuleType.SUFFIX_EACH -> sb.append("每句后缀：${r.value}\n")
                RuleType.RANDOM_PREFIX -> sb.append("随机前缀：${r.value.split("|").joinToString(" ")}  ${r.chance}%\n")
                RuleType.RANDOM_SUFFIX -> sb.append("随机后缀：${r.value.split("|").joinToString(" ")}  ${r.chance}%\n")
                RuleType.RANDOM_EMOTICON -> sb.append("随机颜文字：${r.value.ifEmpty { "内置库" }.split("|").joinToString(" ")}  ${r.chance}%\n")
                RuleType.REPLACE -> sb.append("文本替换：${r.value}  ${r.replaceTo}\n")
            }
        }
        sb.append("行为与样式----------------------\n")
        sb.append("字符间加空格：${onOff(styleSpaced)}\n")
        sb.append("转为大写：${onOff(styleUpper)}\n")
        sb.append("改写后自动发送：${onOff(autoSend)}\n")
        sb.append("点击震动反馈：${onOff(hapticEnabled)}\n")
        sb.append("拖拽边缘自动吸附：${onOff(snapEdges)}\n")
        sb.append("静默修改：${onOff(silentModifyEnabled)}\n")
        sb.append("强制篡改键盘：${onOff(forceKeyboardEnabled)}\n")
        sb.append("标点触发：${onOff(punctTriggerEnabled)}\n")
        sb.append("随机颜文字：${onOff(emoticonEnabled)}\n")
        sb.append("应用黑名单：${onOff(blacklistEnabled)}\n")
        sb.append("黑名单应用：").append(blacklist().joinToString(",")).append("\n")
        sb.append("心跳保活：${onOff(heartbeatEnabled)}\n")
        sb.append("崩溃自启：${onOff(crashRestartEnabled)}\n")
        sb.append("密码锁定：${onOff(lockEnabled)}\n")
        sb.append("隐藏模式：${onOff(hiddenModeEnabled)}\n")
        sb.append("----------------------------------------\n")
        sb.append("外观：${themeText(themeMode)}\n")
        sb.append("按钮大小：$fabSizeDp\n")
        sb.append("按钮透明度：$fabOpacity\n")
        sb.append("收起圆点：${onOff(fabCollapseEnabled)}\n")
        sb.append("柔光玻璃：${onOff(fabGlassEnabled)}\n")
        sb.append("开机自启：${onOff(autoStartEnabled)}\n")
        return sb.toString()
    }

    private fun onOff(b: Boolean) = if (b) "开" else "关"

    private fun themeText(m: String) = when (m) {
        "dark" -> "深色"
        "light" -> "浅色"
        else -> "跟随系统"
    }

    private fun themeValue(s: String) = when (s.trim()) {
        "深色" -> "dark"
        "浅色" -> "light"
        else -> "system"
    }

    private fun onOffValue(s: String) = s.trim() == "开"

    /** 导入配置（支持可读文本格式；兼容旧 JSON），返回 null 表示成功，否则返回错误信息 */
    fun importConfigText(text: String): String? {
        val trimmed = text.trim()
        return if (trimmed.startsWith("{")) {
            importJsonConfig(trimmed)
        } else {
            importTextConfig(text)
        }
    }

    private fun importJsonConfig(text: String): String? {
        return try {
            val o = JSONObject(text)
            if (o.optString("app") != "NekoType") {
                "不是有效的 NekoType 配置文件"
            } else {
                o.optJSONArray("rules")?.let { replaceRulesJson(it.toString()) }
                o.optJSONObject("behaviors")?.let { b ->
                    autoSend = b.optBoolean("auto_send", autoSend)
                    hapticEnabled = b.optBoolean("haptic", hapticEnabled)
                    snapEdges = b.optBoolean("snap", snapEdges)
                    fabSizeDp = b.optInt("fab_size", fabSizeDp)
                    fabOpacity = b.optInt("fab_opacity", fabOpacity)
                    fabCollapseEnabled = b.optBoolean("fab_collapse", fabCollapseEnabled)
                    fabGlassEnabled = b.optBoolean("fab_glass", fabGlassEnabled)
                    autoStartEnabled = b.optBoolean("auto_start", autoStartEnabled)
                    themeMode = b.optString("theme", themeMode)
                    privilegeMode = b.optString("mode", privilegeMode)
                }
                o.optString("active_rule").takeIf { it.isNotEmpty() }?.let { selectPreset(it) }
                null
            }
        } catch (t: Throwable) {
            "导入失败：${t.message}"
        }
    }

    /** 解析可读文本格式配置 */
    private fun importTextConfig(text: String): String? {
        try {
            val newRules = mutableListOf<NekoRule>()
            var inRules = false
            var inBehaviors = false
            var anyData = false
            text.lineSequence().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEach
                when {
                    line.startsWith("规则") -> { inRules = true; inBehaviors = false; anyData = true }
                    line.startsWith("行为与样式") -> { inBehaviors = true; inRules = false; anyData = true }
                    line.startsWith("----") -> { /* 分隔线 */ }
                    inRules && line.startsWith("前缀：") -> {
                        anyData = true
                        val v = line.removePrefix("前缀：").trim()
                        if (v.isNotEmpty()) newRules.add(NekoRule("i_${System.currentTimeMillis()}_${newRules.size}", RuleType.PREFIX, v))
                    }
                    inRules && line.startsWith("后缀：") -> {
                        anyData = true
                        val v = line.removePrefix("后缀：").trim()
                        if (v.isNotEmpty()) newRules.add(NekoRule("i_${System.currentTimeMillis()}_${newRules.size}", RuleType.SUFFIX, v))
                    }
                    inRules && line.startsWith("每句后缀：") -> {
                        anyData = true
                        val v = line.removePrefix("每句后缀：").trim()
                        if (v.isNotEmpty()) newRules.add(NekoRule("i_${System.currentTimeMillis()}_${newRules.size}", RuleType.SUFFIX_EACH, v))
                    }
                    inRules && line.startsWith("随机前缀：") -> {
                        anyData = true
                        parseRandomRule(line.removePrefix("随机前缀："), RuleType.RANDOM_PREFIX, newRules)
                    }
                    inRules && line.startsWith("随机后缀：") -> {
                        anyData = true
                        parseRandomRule(line.removePrefix("随机后缀："), RuleType.RANDOM_SUFFIX, newRules)
                    }
                    inRules && line.startsWith("随机颜文字：") -> {
                        anyData = true
                        val rest = line.removePrefix("随机颜文字：").trim()
                        if (rest.contains("内置库")) {
                            // 导出时空池显示为"内置库" → 导回时空 value（用内置颜文字库）
                            val chance = Regex("(\\d+)%").find(rest)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 100) ?: 50
                            newRules.add(NekoRule("i_${System.currentTimeMillis()}_${newRules.size}", RuleType.RANDOM_EMOTICON, "", chance = chance))
                        } else {
                            parseRandomRule(rest, RuleType.RANDOM_EMOTICON, newRules)
                        }
                    }
                    inRules && line.startsWith("文本替换：") -> {
                        anyData = true
                        val rest = line.removePrefix("文本替换：").trim()
                        val parts = rest.split(Regex("\\s{2,}"))
                        val from = if (parts.isNotEmpty()) parts[0].trim() else ""
                        val to = if (parts.size >= 2) parts[1].trim() else ""
                        if (from.isNotEmpty()) newRules.add(NekoRule("i_${System.currentTimeMillis()}_${newRules.size}", RuleType.REPLACE, from, replaceTo = to))
                    }
                    inBehaviors && line.startsWith("字符间加空格：") -> styleSpaced = onOffValue(line.removePrefix("字符间加空格："))
                    inBehaviors && line.startsWith("转为大写：") -> styleUpper = onOffValue(line.removePrefix("转为大写："))
                    inBehaviors && line.startsWith("改写后自动发送：") -> autoSend = onOffValue(line.removePrefix("改写后自动发送："))
                    inBehaviors && line.startsWith("点击震动反馈：") -> hapticEnabled = onOffValue(line.removePrefix("点击震动反馈："))
                    inBehaviors && line.startsWith("拖拽边缘自动吸附：") -> snapEdges = onOffValue(line.removePrefix("拖拽边缘自动吸附："))
                    inBehaviors && line.startsWith("静默修改：") -> silentModifyEnabled = onOffValue(line.removePrefix("静默修改："))
                    inBehaviors && line.startsWith("强制篡改键盘：") -> forceKeyboardEnabled = onOffValue(line.removePrefix("强制篡改键盘："))
                    inBehaviors && line.startsWith("标点触发：") -> punctTriggerEnabled = onOffValue(line.removePrefix("标点触发："))
                    inBehaviors && line.startsWith("随机颜文字：") -> emoticonEnabled = onOffValue(line.removePrefix("随机颜文字："))
                    inBehaviors && line.startsWith("应用黑名单：") -> blacklistEnabled = onOffValue(line.removePrefix("应用黑名单："))
                    inBehaviors && line.startsWith("黑名单应用：") -> {
                        val pkgs = line.removePrefix("黑名单应用：").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        if (pkgs.isNotEmpty()) {
                            sp.edit().putStringSet("blacklist_packages", pkgs.toSet()).apply()
                        }
                    }
                    inBehaviors && line.startsWith("心跳保活：") -> heartbeatEnabled = onOffValue(line.removePrefix("心跳保活："))
                    inBehaviors && line.startsWith("崩溃自启：") -> crashRestartEnabled = onOffValue(line.removePrefix("崩溃自启："))
                    // 密码锁定 / 隐藏模式：安全状态，导入配置不触碰（防止导入配置被用来解锁）
                    line.startsWith("外观：") -> { anyData = true; themeMode = themeValue(line.removePrefix("外观：")) }
                    line.startsWith("按钮大小：") -> { anyData = true; line.removePrefix("按钮大小：").trim().toIntOrNull()?.let { fabSizeDp = it } }
                    line.startsWith("按钮透明度：") -> { anyData = true; line.removePrefix("按钮透明度：").trim().toIntOrNull()?.let { fabOpacity = it } }
                    line.startsWith("收起圆点：") -> { anyData = true; fabCollapseEnabled = onOffValue(line.removePrefix("收起圆点：")) }
                    line.startsWith("柔光玻璃：") -> { anyData = true; fabGlassEnabled = onOffValue(line.removePrefix("柔光玻璃：")) }
                    line.startsWith("开机自启：") -> { anyData = true; autoStartEnabled = onOffValue(line.removePrefix("开机自启：")) }
                }
            }
            if (!anyData) return "无法识别配置内容"
            // 用解析出的规则替换当前预设的规则
            updateActivePreset { it.copy(rules = newRules) }
            return null
        } catch (t: Throwable) {
            return "导入失败：${t.message}"
        }
    }

    /** 解析"内容池  概率%"行（概率可选） */
    private fun parseRandomRule(rest: String, type: RuleType, out: MutableList<NekoRule>) {
        val chanceMatch = Regex("(\\d+)%").find(rest)
        val chance = chanceMatch?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 100) ?: 50
        val poolRaw = rest.replace(Regex("\\s+\\d+%$"), "").trim()
        val pool = poolRaw.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString("|")
        if (pool.isNotEmpty()) {
            out.add(NekoRule("i_${System.currentTimeMillis()}_${out.size}", type, pool, chance = chance))
        }
    }

    // ================= 统计 =================
    var transformCount: Long
        get() = sp.getLong("transform_count", 0L)
        set(v) = sp.edit().putLong("transform_count", v).apply()

    // ================= 服务状态 =================
    var serviceEnabled: Boolean
        get() = sp.getBoolean("service_enabled", false)
        set(v) = sp.edit().putBoolean("service_enabled", v).apply()

    /** 篡改检测标志（签名不匹配 / Hook 框架）：为 true 时各入口拒绝运行 */
    var tampered: Boolean
        get() = sp.getBoolean("tampered", false)
        set(v) = sp.edit().putBoolean("tampered", v).apply()

    // ================= 密码锁定（防杀后台） =================

    /**
     * 密码锁定状态：由加密载荷派生（lock_payload 存在即锁定）。
     * 不直接存 XML 布尔值——防止通过编辑 SharedPreferences XML 把 false 改成 true 绕过。
     * 关闭锁 = 清除加密载荷（需密码验证后调用）。
     */
    var lockEnabled: Boolean
        get() = lockPayload.isNotEmpty() || lockHash.isNotEmpty()
        set(v) {
            if (!v) {
                // 关闭锁：清除密码数据（含旧格式）
                sp.edit()
                    .remove("lock_payload")
                    .remove("lock_hash")
                    .remove("lock_salt")
                    .apply()
            }
            // 开启不写任何字段：状态完全由 payload 派生
        }

    /** 隐藏模式：隐藏桌面图标（后台无法显示，别人杀不掉；通知栏停止可恢复） */
    var hiddenModeEnabled: Boolean
        get() = sp.getBoolean("hidden_mode", false)
        set(v) = sp.edit().putBoolean("hidden_mode", v).apply()

    /** 应用黑名单：这些应用内不自动篡改（强制篡改键盘 / 静默修改自动注入不生效） */
    var blacklistEnabled: Boolean
        get() = sp.getBoolean("blacklist_enabled", false)
        set(v) = sp.edit().putBoolean("blacklist_enabled", v).apply()

    /** 黑名单包名集合 */
    fun blacklist(): Set<String> = sp.getStringSet("blacklist_packages", emptySet())!!

    fun isBlacklisted(pkg: String): Boolean =
        blacklistEnabled && sp.getStringSet("blacklist_packages", emptySet())!!.contains(pkg)

    fun addBlacklist(pkg: String) {
        val s = sp.getStringSet("blacklist_packages", emptySet())!!.toMutableSet()
        s.add(pkg)
        sp.edit().putStringSet("blacklist_packages", s).apply()
    }

    fun removeBlacklist(pkg: String) {
        val s = sp.getStringSet("blacklist_packages", emptySet())!!.toMutableSet()
        s.remove(pkg)
        sp.edit().putStringSet("blacklist_packages", s).apply()
    }

    /** Shizuku 隐藏：隐藏模式用 Shizuku pm hide（图标即时消失，Hail同款）；关闭则用普通方式（alias） */
    var shizukuHideEnabled: Boolean
        get() = sp.getBoolean("shizuku_hide", false)
        set(v) = sp.edit().putBoolean("shizuku_hide", v).apply()

    /** 心跳保活：AlarmManager 定时唤醒拉活服务（皆成同款；耗电略增，默认关） */
    var heartbeatEnabled: Boolean
        get() = sp.getBoolean("heartbeat", false)
        set(v) = sp.edit().putBoolean("heartbeat", v).apply()

    /** 崩溃自启：进程崩溃时自动拉起悬浮服务（皆成同款；默认开，无害） */
    var crashRestartEnabled: Boolean
        get() = sp.getBoolean("crash_restart", true)
        set(v) = sp.edit().putBoolean("crash_restart", v).apply()

    /** 随机盐（hex，Argon2 兼容旧格式时用） */
    private var lockSalt: String
        get() = sp.getString("lock_salt", "")!!
        set(v) = sp.edit().putString("lock_salt", v).apply()

    /** 密码哈希：Argon2id 编码串（含盐与参数，自校验）；旧版本为 SHA-256 hex */
    private var lockHash: String
        get() = sp.getString("lock_hash", "")!!
        set(v) = sp.edit().putString("lock_hash", v).apply()

    /**
     * Keystore 加密的密码载荷：密码哈希用系统安全芯片（AndroidKeyStore）的 AES 密钥加密后存储。
     * root 改了 prefs 也解不开、验不过；篡改（解密失败）视为仍锁定，防绕过。
     */
    private var lockPayload: String
        get() = sp.getString("lock_payload", "")!!
        set(v) = sp.edit().putString("lock_payload", v).apply()

    /** Argon2id 实例（JNI，进程内复用） */
    private val argon2 by lazy { com.lambdapioneer.argon2kt.Argon2Kt() }

    private val LOCK_KEY_ALIAS = "nekotype_lock_key"

    /** AndroidKeyStore：生成/读取不可导出的 AES-GCM 密钥（硬件级保护） */
    private fun lockKey(): javax.crypto.SecretKey? = try {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(LOCK_KEY_ALIAS)) {
            ks.getKey(LOCK_KEY_ALIAS, null) as javax.crypto.SecretKey
        } else {
            val gen = javax.crypto.KeyGenerator.getInstance(
                android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )
            gen.init(
                android.security.keystore.KeyGenParameterSpec.Builder(
                    LOCK_KEY_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                            android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            gen.generateKey()
        }
    } catch (_: Throwable) {
        null
    }

    /** AES-GCM 加密：base64(iv + 密文) */
    private fun encryptPayload(plain: String): String? {
        return try {
            val key = lockKey() ?: return null
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray())
            val out = ByteArray(iv.size + ct.size)
            System.arraycopy(iv, 0, out, 0, iv.size)
            System.arraycopy(ct, 0, out, iv.size, ct.size)
            android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP)
        } catch (_: Throwable) {
            null
        }
    }

    /** AES-GCM 解密；失败（密钥缺失/数据被篡改）返回 null */
    private fun decryptPayload(encoded: String): String? {
        return try {
            val key = lockKey() ?: return null
            val raw = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, 12)
            val ct = raw.copyOfRange(12, raw.size)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct))
        } catch (_: Throwable) {
            null
        }
    }

    /** 是否已设置过密码（载荷存在即视为有锁——即使被篡改也按锁定处理，防绕过） */
    fun hasLockPassword(): Boolean = lockPayload.isNotEmpty() || lockHash.isNotEmpty()

    /** 设置/重置密码（首次开启无需旧密码）；Argon2id 慢哈希 + Keystore 硬件加密存储 */
    fun setLockPassword(pwd: String) {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val hash = try {
            val res = argon2.hash(
                mode = com.lambdapioneer.argon2kt.Argon2Mode.ARGON2_ID,
                password = pwd.toByteArray(),
                salt = salt,
                tCostInIterations = 2,
                mCostInKibibyte = 8192,
                parallelism = 1,
                hashLengthInBytes = 32
            )
            Charsets.UTF_8.decode(res.encodedOutput).toString()
        } catch (_: Throwable) {
            "" // JNI 不可用时回退
        }
        if (hash.isNotEmpty()) {
            // 优先 Keystore 加密存储
            val payload = encryptPayload(hash)
            if (payload != null) {
                sp.edit()
                    .putString("lock_payload", payload)
                    .remove("lock_hash")
                    .remove("lock_salt")
                    .apply()
                return
            }
        }
        // Keystore/Argon2 不可用：回退旧格式（SHA-256）
        lockSalt = salt.joinToString("") { "%02x".format(it) }
        lockHash = if (hash.isNotEmpty()) hash else sha256(lockSalt + pwd)
    }

    /** 校验密码：优先 Keystore 载荷（篡改/解密失败一律拒绝）；兼容旧格式 */
    fun verifyLockPassword(pwd: String): Boolean {
        val payload = lockPayload
        if (payload.isNotEmpty()) {
            val hash = decryptPayload(payload) ?: return false // 被篡改/密钥丢失：拒绝
            return try {
                argon2.verify(
                    mode = com.lambdapioneer.argon2kt.Argon2Mode.ARGON2_ID,
                    encoded = hash,
                    password = pwd.toByteArray()
                )
            } catch (_: Throwable) {
                false
            }
        }
        val stored = lockHash
        if (stored.isEmpty()) return false
        return if (stored.startsWith("\$argon2")) {
            try {
                argon2.verify(
                    mode = com.lambdapioneer.argon2kt.Argon2Mode.ARGON2_ID,
                    encoded = stored,
                    password = pwd.toByteArray()
                )
            } catch (_: Throwable) {
                false
            }
        } else {
            lockSalt.isNotEmpty() && sha256(lockSalt + pwd) == stored
        }
    }

    private fun sha256(input: String): String = try {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    } catch (_: Throwable) {
        ""
    }

    // ================= 运行模式 =================
    var privilegeMode: String
        get() = sp.getString("privilege_mode", "basic")!!
        set(v) = sp.edit().putString("privilege_mode", v).apply()

    // ================= 外观（主题） =================
    /** system / dark / light */
    var themeMode: String
        get() = sp.getString("theme_mode", "system")!!
        set(v) = sp.edit().putString("theme_mode", v).apply()

    /** 自定义背景图片路径（"" = 使用默认背景） */
    var customBackgroundPath: String
        get() = sp.getString("custom_bg", "")!!
        set(v) = sp.edit().putString("custom_bg", v).apply()

    /** 日志记录总开关 */
    var logEnabled: Boolean
        get() = sp.getBoolean("log_enabled", true)
        set(v) = sp.edit().putBoolean("log_enabled", v).apply()

    /** 日志终端字体大小（sp） */
    var logFontSize: Float
        get() = sp.getFloat("log_font_size", 10f)
        set(v) = sp.edit().putFloat("log_font_size", v.coerceIn(7f, 18f)).apply()

    /** 终端轻汉化（系统日志标签转中文） */
    var logLocalize: Boolean
        get() = sp.getBoolean("log_localize", true)
        set(v) = sp.edit().putBoolean("log_localize", v).apply()
}
