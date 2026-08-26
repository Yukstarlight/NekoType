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

    enum class RuleType(val label: String) {
        PREFIX("前缀"),
        SUFFIX("后缀"),
        RANDOM_PREFIX("随机前缀"),
        RANDOM_SUFFIX("随机后缀"),
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
                RuleType.RANDOM_PREFIX -> sb.append("随机前缀：${r.value.split("|").joinToString(" ")}  ${r.chance}%\n")
                RuleType.RANDOM_SUFFIX -> sb.append("随机后缀：${r.value.split("|").joinToString(" ")}  ${r.chance}%\n")
                RuleType.REPLACE -> sb.append("文本替换：${r.value}  ${r.replaceTo}\n")
            }
        }
        sb.append("行为与样式----------------------\n")
        sb.append("字符间加空格：${onOff(styleSpaced)}\n")
        sb.append("转为大写：${onOff(styleUpper)}\n")
        sb.append("改写后自动发送：${onOff(autoSend)}\n")
        sb.append("点击震动反馈：${onOff(hapticEnabled)}\n")
        sb.append("拖拽边缘自动吸附：${onOff(snapEdges)}\n")
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
                    inRules && line.startsWith("随机前缀：") -> {
                        anyData = true
                        parseRandomRule(line.removePrefix("随机前缀："), RuleType.RANDOM_PREFIX, newRules)
                    }
                    inRules && line.startsWith("随机后缀：") -> {
                        anyData = true
                        parseRandomRule(line.removePrefix("随机后缀："), RuleType.RANDOM_SUFFIX, newRules)
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
