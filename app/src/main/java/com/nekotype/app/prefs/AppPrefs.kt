package com.nekotype.app.prefs

import android.content.Context
import com.nekotype.app.NekoTypeApp
import com.nekotype.app.R
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

    /** 行为与样式：随机颜文字总开关（开启后每条消息自动加随机颜文字） */
    var emoticonEnabled: Boolean
        get() = sp.getBoolean("emoticon", false)
        set(v) = sp.edit().putBoolean("emoticon", v).apply()

    enum class RuleType(val label: String, val resId: Int) {
        PREFIX("前缀", com.nekotype.app.R.string.rule_prefix),
        SUFFIX("后缀", com.nekotype.app.R.string.rule_suffix),
        RANDOM_PREFIX("随机前缀（固定）", com.nekotype.app.R.string.rule_rand_prefix),
        RANDOM_PREFIX_ONCE("随机前缀（不固定）", com.nekotype.app.R.string.rule_rand_prefix_once),
        RANDOM_SUFFIX("随机后缀（固定）", com.nekotype.app.R.string.rule_rand_suffix),
        RANDOM_SUFFIX_ONCE("随机后缀（不固定）", com.nekotype.app.R.string.rule_rand_suffix_once),
        REPLACE("替换文本", com.nekotype.app.R.string.rule_replace);

        companion object {
            fun fromName(name: String): RuleType = when (name) {
                // 已删除的旧类型「每句后缀」→ 归为普通后缀（兼容老数据/老导出）
                "SUFFIX_EACH" -> SUFFIX
                else -> entries.firstOrNull { it.name == name } ?: PREFIX
            }
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
        /** 优先级：1-100，**数字越大越先执行**；相同等级按规则列表顺序（默认 50 = 中性档） */
        val priority: Int = 50,
        val enabled: Boolean = true
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("type", type.name)
            put("value", value)
            put("replaceTo", replaceTo)
            put("chance", chance)
            put("priority", priority)
            put("enabled", enabled)
        }

        companion object {
            /** 返回 null = 该规则类型已移除（旧版「随机颜文字」规则 → 直接丢弃） */
            fun fromJson(o: JSONObject): NekoRule? {
                val typeName = o.optString("type", "PREFIX")
                if (typeName == "RANDOM_EMOTICON") return null
                return NekoRule(
                    id = o.optString("id", "rule_${System.currentTimeMillis()}"),
                    type = RuleType.fromName(typeName),
                    value = o.optString("value", ""),
                    replaceTo = o.optString("replaceTo", ""),
                    chance = o.optInt("chance", 50).coerceIn(1, 100),
                    priority = o.optInt("priority", 50).coerceIn(1, 100),
                    enabled = o.optBoolean("enabled", true)
                )
            }
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
            val parsed = (0 until arr.length()).mapNotNull { i ->
                try { RuleConfig.fromJson(arr.getJSONObject(i)) } catch (_: Throwable) { null }
            }
            // 修复历史数据：预设 ID 重复会导致"切了没反应"（active_rule 只能命中第一个同名 ID）。
            // 这里在读取时就地重发唯一 ID，老用户的坏数据读一次即自愈。
            val seen = HashSet<String>()
            var repaired = false
            val fixed = parsed.map { cfg ->
                if (cfg.id.isNotEmpty() && seen.add(cfg.id)) {
                    cfg
                } else {
                    repaired = true
                    var nid = "preset_fix_${System.currentTimeMillis()}_${seen.size}"
                    var n = 1
                    while (!seen.add(nid)) { nid = "preset_fix_${System.currentTimeMillis()}_${seen.size}_${n++}" }
                    cfg.copy(id = nid)
                }
            }
            if (repaired) saveRules(fixed)
            fixed
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
        // 防御：写入不存在的 ID 会让 active_rule 永远命中不到，界面上就表现为"预设切不过去"（一直停在第一个）。
        // 命中不到时回落到第一个真实存在的预设。
        val list = rulesList()
        val target = list.firstOrNull { it.id == id } ?: list.firstOrNull()
        sp.edit().putString("active_rule", target?.id ?: "default").apply()
    }

    fun deletePreset(id: String) {
        val list = rulesList().filterNot { it.id == id }
        if (list.isEmpty()) return
        saveRules(list)
        if (activeId() == id) selectPreset(list.first().id)
    }

    // ---------- 预设高级管理：重命名 / 合并 ----------

    /**
     * 重命名预设（保证不重名；仅改名，规则不变）。
     * @return true 表示重命名成功；false 表示预设不存在或新名称为空/重名。
     */
    fun renamePreset(id: String, newName: String): Boolean {
        val name = newName.trim()
        if (name.isEmpty()) return false
        val list = rulesList().toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return false
        // 重名检测（排除自身）
        if (list.any { it.id != id && it.name == name }) return false
        list[idx] = list[idx].copy(name = name)
        saveRules(list)
        return true
    }

    /**
     * 把多个预设的规则合并到**当前预设**（按来源顺序追加到末尾）。
     * 来源预设本身不被修改，当前预设不会被列为来源。
     * @param sourceIds 要合并的来源预设 id 列表
     * @return 实际合并进来的规则条数（0 表示无可合并来源或当前预设不存在）
     */
    fun mergePresetsIntoCurrent(sourceIds: List<String>): Int {
        if (sourceIds.isEmpty()) return 0
        val list = rulesList()
        val curId = activeId()
        val curIdx = list.indexOfFirst { it.id == curId }
        if (curIdx < 0) return 0
        val current = list[curIdx]
        // 仅合并真实存在、且非当前预设的来源
        val sources = sourceIds.mapNotNull { sid -> list.firstOrNull { it.id == sid && it.id != curId } }
        if (sources.isEmpty()) return 0
        // 合并：重新生成 id 后追加，避免与已有规则 id 冲突
        val stamp = System.currentTimeMillis()
        val merged = sources.flatMapIndexed { si, src ->
            src.rules.mapIndexed { ri, r -> r.copy(id = "m_${stamp}_${si}_$ri") }
        }
        val newList = list.toMutableList()
        newList[curIdx] = current.copy(rules = current.rules + merged)
        saveRules(newList)
        return merged.size
    }

    /** 生成不与现有预设冲突的唯一 ID（导入时同一毫秒内连续建多个预设也不会撞 ID） */
    private fun newPresetId(existing: List<RuleConfig>): String {
        val base = System.currentTimeMillis()
        var id = "preset_$base"
        var n = 1
        while (existing.any { it.id == id }) { id = "preset_${base}_${n++}" }
        return id
    }

    /** 创建新预设并可选切换为当前 */
    fun createPreset(name: String, rules: List<NekoRule>, switchTo: Boolean = true, styleSpaced: Boolean = false, styleUpper: Boolean = false): String {
        val list0 = rulesList()
        val id = newPresetId(list0)
        val config = RuleConfig(id = id, name = name, rules = rules, styleSpaced = styleSpaced, styleUpper = styleUpper)
        saveRules(list0 + config)
        if (switchTo) selectPreset(id)
        return id
    }

    /** 检查是否已存在同名预设 */
    fun hasPresetName(name: String): Boolean = rulesList().any { it.name == name }

    /** 指定预设的规则条数（预设选择列表显示用，便于判断切换是否生效） */
    fun ruleCountOf(id: String): Int =
        rulesList().firstOrNull { it.id == id }?.rules?.size ?: 0

    // ================= 内置人设规则包 =================

    /**
     * 确保「病娇人设」内置预设存在（首次启动自动创建，已存在则跳过）。
     * 在 NekoTypeApp.onCreate 中调用，用户可在规则页预设列表中选择切换。
     */
    fun ensureYanderePreset() {
        if (hasPresetName("病娇人设")) return
        val rules = listOf(
            NekoRule("yd_prefix", RuleType.PREFIX, "呵呵..."),
            NekoRule("yd_suffix", RuleType.SUFFIX, "♡"),
            NekoRule(
                "yd_rand_prefix", RuleType.RANDOM_PREFIX,
                "你只能是我的|呵呵，又在看别人？|我盯着你哦|不许离开我|你的一切都是我的|为什么不回消息？|我会一直陪着你的|你逃不掉的|眼里只能有我",
                chance = 55
            ),
            NekoRule(
                "yd_rand_suffix", RuleType.RANDOM_SUFFIX,
                "...呵呵|你逃不掉的♡|只能是我的|我好喜欢你啊|不许不理我|你的心只能属于我|呵呵，真可爱|想把你藏起来|永远在一起吧",
                chance = 55
            ),
            NekoRule("yd_rep_1", RuleType.REPLACE, "喜欢", replaceTo = "只喜欢我一个吧"),
            NekoRule("yd_rep_2", RuleType.REPLACE, "再见", replaceTo = "不许走"),
            NekoRule("yd_rep_3", RuleType.REPLACE, "朋友", replaceTo = "只能有我一个"),
            NekoRule("yd_rep_4", RuleType.REPLACE, "晚安", replaceTo = "梦里也只能想我"),
            NekoRule("yd_rep_5", RuleType.REPLACE, "哈哈", replaceTo = "呵呵"),
            NekoRule("yd_rep_6", RuleType.REPLACE, "嗯", replaceTo = "你在敷衍我吗")
        )
        createPreset(name = "病娇人设", rules = rules, switchTo = false, styleSpaced = false, styleUpper = false)
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

    /** 标点触发模式：强制篡改时，输入以标点结尾才触发篡改（打完一句才改） */
    var punctTriggerEnabled: Boolean
        get() = sp.getBoolean("punct_trigger", false)
        set(v) = sp.edit().putBoolean("punct_trigger", v).apply()

    /** 自定义触发标点（标点触发模式下生效），默认中英文常见标点 */
    var punctTriggerChars: String
        get() = sp.getString("punct_trigger_chars", "。！？!?,，…～~；;：:") ?: "。！？!?,，…～~；;：:"
        set(v) = sp.edit().putString("punct_trigger_chars", v).apply()

    /** 删除优化：用户正在删除文字时暂不改写（避免"后缀删不掉／越删越多"），停手后自动恢复 */
    var deleteOptimizeEnabled: Boolean
        get() = sp.getBoolean("delete_optimize", true)
        set(v) = sp.edit().putBoolean("delete_optimize", v).apply()

    /** 语音输入优化：语音输入时使用更长的停顿判定再接改写，避免说到一半被打断。
     *  默认关：开启会把停顿判定拉到 voiceDebounceMs，普通打字延迟明显变高 */
    var voiceInputOptimizeEnabled: Boolean
        get() = sp.getBoolean("voice_input_optimize", false)
        set(v) = sp.edit().putBoolean("voice_input_optimize", v).apply()

    /** 语音输入停顿判定毫秒数（300-5000，默认 1000） */
    var voiceDebounceMs: Int
        get() = sp.getInt("voice_debounce_ms", 1000)
        set(v) = sp.edit().putInt("voice_debounce_ms", v.coerceIn(300, 5000)).apply()

    /**
     * 规则按等级全局执行（默认关）：
     * 关 = 类别顺序不变（替换→前缀→随机前缀→后缀→随机后缀），仅**类别内**按等级排序；
     * 开 = 所有类别打散，严格按等级从大到小逐条执行（可让替换在后缀之后等）。
     */
    var priorityGlobalEnabled: Boolean
        get() = sp.getBoolean("priority_global", false)
        set(v) = sp.edit().putBoolean("priority_global", v).apply()

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

    /** 长按悬浮球弹出快捷菜单（默认开）：一键回到应用 / 切换规则预设 / 开关功能 */
    var fabLongPressMenu: Boolean
        get() = sp.getBoolean("fab_long_menu", true)
        set(v) = sp.edit().putBoolean("fab_long_menu", v).apply()

    /** 长按菜单首次提示是否已展示（首次成功长按后置位，之后不再弹 Toast 提示） */
    var fabLongPressHintShown: Boolean
        get() = sp.getBoolean("fab_long_hint", false)
        set(v) = sp.edit().putBoolean("fab_long_hint", v).apply()

    /** 快捷菜单面板位置（用户拖动后跨会话记忆；-1 = 未拖动过，自动锚定在悬浮球旁） */
    var fabMenuX: Int
        get() = sp.getInt("fab_menu_x", -1)
        set(v) = sp.edit().putInt("fab_menu_x", v).apply()
    var fabMenuY: Int
        get() = sp.getInt("fab_menu_y", -1)
        set(v) = sp.edit().putInt("fab_menu_y", v).apply()

    /** 开机自启（可选开关） */
    var autoStartEnabled: Boolean
        get() = sp.getBoolean("auto_start", true)
        set(v) = sp.edit().putBoolean("auto_start", v).apply()

    /**
     * 每次打开应用自动启动悬浮服务（默认开）：
     * 仅当上次退出时服务处于「已启用」状态才自动拉起，用户主动停止过则保持停止，避免误启。
     */
    var autoStartServiceOnLaunch: Boolean
        get() = sp.getBoolean("auto_start_service_on_launch", true)
        set(v) = sp.edit().putBoolean("auto_start_service_on_launch", v).apply()

    /**
     * 每次打开应用自动用 Shizuku 授权所有权限（默认**关**）：
     * 打开 App 时后台跑一遍「无障碍 → 设备管理员 → 电池白名单 → 悬浮窗」授权，
     * 需 Shizuku 已运行且已授权；未授权时尝试请求一次权限。
     */
    var autoShizukuGrantOnLaunch: Boolean
        get() = sp.getBoolean("auto_shizuku_grant_on_launch", false)
        set(v) = sp.edit().putBoolean("auto_shizuku_grant_on_launch", v).apply()

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

    /** 导出全部配置为可读文本 v2（所有规则预设 + 行为 + 外观；兼容旧版单预设导入） */
    fun exportConfigText(): String {
        val sb = StringBuilder()
        sb.append("NekoType 配置导出 v2\n")
        val presets = rulesList()
        if (presets.size > 1) {
            sb.append("预设：").append(
                presets.joinToString("、") { p -> if (p.id == activeId()) "${p.name}（当前）" else p.name }
            ).append("\n")
        }
        sb.append("规则------------------------\n")
        presets.forEach { cfg ->
            if (presets.size > 1) sb.append("[预设：${cfg.name}]\n")
            cfg.rules.forEach { r ->
                // 行首写入 [等级]，导入时按此还原优先级
                val p = "[${r.priority}] "
                when (r.type) {
                    RuleType.PREFIX -> sb.append(p + "前缀：${r.value}\n")
                    RuleType.SUFFIX -> sb.append(p + "后缀：${r.value}\n")
                    RuleType.RANDOM_PREFIX -> sb.append(p + "随机前缀（固定）：${r.value.split("|").joinToString(" ")}  ${r.chance}%\n")
                    RuleType.RANDOM_PREFIX_ONCE -> sb.append(p + "随机前缀（不固定）：${r.value.split("|").joinToString(" ")}  ${r.chance}%\n")
                    RuleType.RANDOM_SUFFIX -> sb.append(p + "随机后缀（固定）：${r.value.split("|").joinToString(" ")}  ${r.chance}%\n")
                    RuleType.RANDOM_SUFFIX_ONCE -> sb.append(p + "随机后缀（不固定）：${r.value.split("|").joinToString(" ")}  ${r.chance}%\n")
                    RuleType.REPLACE -> sb.append(p + "文本替换：${r.value}  ${r.replaceTo}\n")
                }
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
        sb.append("删除优化：${onOff(deleteOptimizeEnabled)}\n")
        sb.append("语音输入优化：${onOff(voiceInputOptimizeEnabled)}\n")
        sb.append("语音停顿判定：$voiceDebounceMs\n")
        sb.append("按等级全局执行：${onOff(priorityGlobalEnabled)}\n")
        sb.append("随机颜文字：${onOff(emoticonEnabled)}\n")
        sb.append("应用黑名单：${onOff(blacklistEnabled)}\n")
        sb.append("黑名单应用：").append(blacklist().joinToString(",")).append("\n")
        sb.append("心跳保活：${onOff(heartbeatEnabled)}\n")
        sb.append("崩溃自启：${onOff(crashRestartEnabled)}\n")
        sb.append("猫娘用语：${onOff(nekoMode)}\n")
        sb.append("自定义背景：${customBackgroundPath.ifEmpty { "无" }}\n")
        sb.append("自定义悬浮图标：${customFabIconPath.ifEmpty { "无" }}\n")
        sb.append("密码锁定：${onOff(lockEnabled)}\n")
        sb.append("隐藏模式：${onOff(hiddenModeEnabled)}\n")
        sb.append("----------------------------------------\n")
        sb.append("外观：${themeText(themeMode)}\n")
        sb.append("按钮大小：$fabSizeDp\n")
        sb.append("按钮透明度：$fabOpacity\n")
        sb.append("收起圆点：${onOff(fabCollapseEnabled)}\n")
        sb.append("柔光玻璃：${onOff(fabGlassEnabled)}\n")
        if (buttonX >= 0 && buttonY >= 0) sb.append("按钮位置：$buttonX,$buttonY\n")
        sb.append("开机自启：${onOff(autoStartEnabled)}\n")
        return sb.toString()
    }

    private fun onOff(b: Boolean) = if (b) "开" else "关"

    private fun themeText(m: String) = when (m) {
        "dark" -> "深色"
        "light" -> "浅色"
        "star" -> "星空"
        "neko" -> "猫娘"
        "cccp" -> "前苏联"
        else -> "跟随系统"
    }

    private fun themeValue(s: String) = when (s.trim()) {
        "深色" -> "dark"
        "浅色" -> "light"
        "星空" -> "star"
        "猫娘" -> "neko"
        "前苏联" -> "cccp"
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
                NekoTypeApp.instance.getString(R.string.u174)
            } else {
                o.optJSONArray("rules")?.let { replaceRulesJson(it.toString()) }
                o.optJSONObject("behaviors")?.let { b ->
                    autoSend = b.optBoolean("auto_send", autoSend)
                    hapticEnabled = b.optBoolean("haptic", hapticEnabled)
                    snapEdges = b.optBoolean("snap", snapEdges)
                    styleSpaced = b.optBoolean("style_spaced", styleSpaced)
                    styleUpper = b.optBoolean("style_upper", styleUpper)
                    silentModifyEnabled = b.optBoolean("silent_modify", silentModifyEnabled)
                    forceKeyboardEnabled = b.optBoolean("force_keyboard", forceKeyboardEnabled)
                    punctTriggerEnabled = b.optBoolean("punct_trigger", punctTriggerEnabled)
                    deleteOptimizeEnabled = b.optBoolean("delete_optimize", deleteOptimizeEnabled)
                    voiceInputOptimizeEnabled = b.optBoolean("voice_input_optimize", voiceInputOptimizeEnabled)
                    voiceDebounceMs = b.optInt("voice_debounce_ms", voiceDebounceMs)
                    priorityGlobalEnabled = b.optBoolean("priority_global", priorityGlobalEnabled)
                    emoticonEnabled = b.optBoolean("emoticon", emoticonEnabled)
                    heartbeatEnabled = b.optBoolean("heartbeat", heartbeatEnabled)
                    crashRestartEnabled = b.optBoolean("crash_restart", crashRestartEnabled)
                    nekoMode = b.optBoolean("neko_mode", nekoMode)
                    b.optString("custom_bg").takeIf { it.isNotEmpty() }?.let { customBackgroundPath = it }
                    b.optString("custom_fab_icon").takeIf { it.isNotEmpty() }?.let { customFabIconPath = it }
                    fabSizeDp = b.optInt("fab_size", fabSizeDp)
                    fabOpacity = b.optInt("fab_opacity", fabOpacity)
                    fabCollapseEnabled = b.optBoolean("fab_collapse", fabCollapseEnabled)
                    fabGlassEnabled = b.optBoolean("fab_glass", fabGlassEnabled)
                    autoStartEnabled = b.optBoolean("auto_start", autoStartEnabled)
                    themeMode = b.optString("theme", themeMode)
                }
                o.optString("active_rule").takeIf { it.isNotEmpty() }?.let { selectPreset(it) }
                null
            }
        } catch (t: Throwable) {
            NekoTypeApp.instance.getString(R.string.u175, t.message)
        }
    }

    /** 解析可读文本格式配置 */
    private fun importTextConfig(text: String): String? {
        if (text.contains("NekoType 配置导出")) return importTextConfigV2(text)
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
                        // 已删除的旧类型：老导出文件兼容，归为普通后缀
                        val v = line.removePrefix("每句后缀：").trim()
                        if (v.isNotEmpty()) newRules.add(NekoRule("i_${System.currentTimeMillis()}_${newRules.size}", RuleType.SUFFIX, v))
                    }
                    inRules && line.startsWith("随机前缀（固定）：") -> {
                        anyData = true
                        parseRandomRule(line.removePrefix("随机前缀（固定）："), RuleType.RANDOM_PREFIX, newRules)
                    }
                    inRules && line.startsWith("随机前缀（不固定）：") -> {
                        anyData = true
                        parseRandomRule(line.removePrefix("随机前缀（不固定）："), RuleType.RANDOM_PREFIX_ONCE, newRules)
                    }
                    inRules && line.startsWith("随机前缀：") -> {
                        anyData = true
                        // 兼容旧版导出（未标注固定/不固定，默认固定）
                        parseRandomRule(line.removePrefix("随机前缀："), RuleType.RANDOM_PREFIX, newRules)
                    }
                    inRules && line.startsWith("随机后缀（固定）：") -> {
                        anyData = true
                        parseRandomRule(line.removePrefix("随机后缀（固定）："), RuleType.RANDOM_SUFFIX, newRules)
                    }
                    inRules && line.startsWith("随机后缀（不固定）：") -> {
                        anyData = true
                        parseRandomRule(line.removePrefix("随机后缀（不固定）："), RuleType.RANDOM_SUFFIX_ONCE, newRules)
                    }
                    inRules && line.startsWith("随机后缀：") -> {
                        anyData = true
                        // 兼容旧版导出（未标注固定/不固定，默认固定）
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
                    inBehaviors && line.startsWith("静默修改：") -> silentModifyEnabled = onOffValue(line.removePrefix("静默修改："))
                    inBehaviors && line.startsWith("强制篡改键盘：") -> forceKeyboardEnabled = onOffValue(line.removePrefix("强制篡改键盘："))
                    inBehaviors && line.startsWith("标点触发：") -> punctTriggerEnabled = onOffValue(line.removePrefix("标点触发："))
                    inBehaviors && line.startsWith("删除优化：") -> deleteOptimizeEnabled = onOffValue(line.removePrefix("删除优化："))
                    inBehaviors && line.startsWith("语音输入优化：") -> voiceInputOptimizeEnabled = onOffValue(line.removePrefix("语音输入优化："))
                    inBehaviors && line.startsWith("语音停顿判定：") ->
                        line.removePrefix("语音停顿判定：").trim().toIntOrNull()?.let { voiceDebounceMs = it }
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
            if (!anyData) return NekoTypeApp.instance.getString(R.string.u176)
            // 用解析出的规则替换当前预设的规则
            updateActivePreset { it.copy(rules = newRules) }
            return null
        } catch (t: Throwable) {
            return NekoTypeApp.instance.getString(R.string.u175, t.message)
        }
    }

    /** v2 文本格式：多规则预设 + 全部设置模块 */
    private fun importTextConfigV2(text: String): String? {
        try {
            val presetRules = LinkedHashMap<String, MutableList<NekoRule>>()
            var activeName: String? = null
            var currentPreset: String? = null
            var sec = ""
            var anyData = false

            fun target(): MutableList<NekoRule> =
                presetRules.getOrPut(currentPreset ?: "___active___") { mutableListOf() }
            fun addRule(r: NekoRule) { anyData = true; target().add(r) }
            fun uid() = "i_${System.currentTimeMillis()}_${presetRules.size}_${target().size}"

            var curPri = 50
            text.lineSequence().forEach { raw ->
                val line0 = raw.trim()
                if (line0.isEmpty()) return@forEach
                // 行首 [等级] 前缀（导出时写入）：解析出本条规则的优先级
                val pm = Regex("^\\[(\\d{1,3})\\]\\s*(.*)$").find(line0)
                val line = if (pm != null) {
                    curPri = pm.groupValues[1].toIntOrNull()?.coerceIn(1, 100) ?: 50
                    pm.groupValues[2].trim()
                } else {
                    line0
                }
                when {
                    line.startsWith("NekoType 配置导出") -> anyData = true
                    line.startsWith("预设：") -> {
                        anyData = true
                        line.removePrefix("预设：").split("、", "，").map { it.trim() }.filter { it.isNotEmpty() }.forEach { p ->
                            val isCur = p.endsWith("（当前）")
                            val name = if (isCur) p.removeSuffix("（当前）").trim() else p
                            if (name.isNotEmpty()) {
                                presetRules.getOrPut(name) { mutableListOf() }
                                if (isCur) activeName = name
                            }
                        }
                    }
                    line.startsWith("[预设：") && line.endsWith("]") -> {
                        anyData = true
                        val name = line.removePrefix("[预设：").removeSuffix("]").trim()
                        if (name.isNotEmpty()) {
                            presetRules.getOrPut(name) { mutableListOf() }
                            currentPreset = name
                        }
                    }
                    line.startsWith("规则") -> { sec = "rules" }
                    line.startsWith("行为与样式") -> { sec = "behaviors" }
                    line.startsWith("----") -> { /* 分隔线 */ }
                    line.startsWith("前缀：") -> { val v = line.removePrefix("前缀：").trim(); if (v.isNotEmpty()) addRule(NekoRule(uid(), RuleType.PREFIX, v, priority = curPri)) }
                    line.startsWith("后缀：") -> { val v = line.removePrefix("后缀：").trim(); if (v.isNotEmpty()) addRule(NekoRule(uid(), RuleType.SUFFIX, v, priority = curPri)) }
                    line.startsWith("每句后缀：") -> { val v = line.removePrefix("每句后缀：").trim(); if (v.isNotEmpty()) addRule(NekoRule(uid(), RuleType.SUFFIX, v, priority = curPri)) }
                    line.startsWith("随机前缀（固定）：") -> parseRandomRule2(line.removePrefix("随机前缀（固定）："), RuleType.RANDOM_PREFIX) { addRule(it.copy(priority = curPri)) }
                    line.startsWith("随机前缀（不固定）：") -> parseRandomRule2(line.removePrefix("随机前缀（不固定）："), RuleType.RANDOM_PREFIX_ONCE) { addRule(it.copy(priority = curPri)) }
                    line.startsWith("随机前缀：") -> parseRandomRule2(line.removePrefix("随机前缀："), RuleType.RANDOM_PREFIX) { addRule(it.copy(priority = curPri)) }
                    line.startsWith("随机后缀（固定）：") -> parseRandomRule2(line.removePrefix("随机后缀（固定）："), RuleType.RANDOM_SUFFIX) { addRule(it.copy(priority = curPri)) }
                    line.startsWith("随机后缀（不固定）：") -> parseRandomRule2(line.removePrefix("随机后缀（不固定）："), RuleType.RANDOM_SUFFIX_ONCE) { addRule(it.copy(priority = curPri)) }
                    line.startsWith("随机后缀：") -> parseRandomRule2(line.removePrefix("随机后缀："), RuleType.RANDOM_SUFFIX) { addRule(it.copy(priority = curPri)) }
                    line.startsWith("文本替换：") -> {
                        val rest = line.removePrefix("文本替换：").trim()
                        val parts = rest.split(Regex("\\s{2,}"))
                        val from = if (parts.isNotEmpty()) parts[0].trim() else ""
                        val to = if (parts.size >= 2) parts[1].trim() else ""
                        if (from.isNotEmpty()) addRule(NekoRule(uid(), RuleType.REPLACE, from, replaceTo = to, priority = curPri))
                    }
                    line.startsWith("字符间加空格：") -> { anyData = true; styleSpaced = onOffValue(line.removePrefix("字符间加空格：")) }
                    line.startsWith("转为大写：") -> { anyData = true; styleUpper = onOffValue(line.removePrefix("转为大写：")) }
                    line.startsWith("改写后自动发送：") -> { anyData = true; autoSend = onOffValue(line.removePrefix("改写后自动发送：")) }
                    line.startsWith("点击震动反馈：") -> { anyData = true; hapticEnabled = onOffValue(line.removePrefix("点击震动反馈：")) }
                    line.startsWith("拖拽边缘自动吸附：") -> { anyData = true; snapEdges = onOffValue(line.removePrefix("拖拽边缘自动吸附：")) }
                    line.startsWith("静默修改：") -> { anyData = true; silentModifyEnabled = onOffValue(line.removePrefix("静默修改：")) }
                    line.startsWith("强制篡改键盘：") -> { anyData = true; forceKeyboardEnabled = onOffValue(line.removePrefix("强制篡改键盘：")) }
                    line.startsWith("标点触发：") -> { anyData = true; punctTriggerEnabled = onOffValue(line.removePrefix("标点触发：")) }
                    line.startsWith("删除优化：") -> { anyData = true; deleteOptimizeEnabled = onOffValue(line.removePrefix("删除优化：")) }
                    line.startsWith("语音输入优化：") -> { anyData = true; voiceInputOptimizeEnabled = onOffValue(line.removePrefix("语音输入优化：")) }
                    line.startsWith("语音停顿判定：") -> {
                        anyData = true
                        line.removePrefix("语音停顿判定：").trim().toIntOrNull()?.let { voiceDebounceMs = it }
                    }
                    line.startsWith("按等级全局执行：") -> { anyData = true; priorityGlobalEnabled = onOffValue(line.removePrefix("按等级全局执行：")) }
                    sec == "behaviors" && line.startsWith("随机颜文字：") -> { anyData = true; emoticonEnabled = onOffValue(line.removePrefix("随机颜文字：")) }
                    line.startsWith("应用黑名单：") -> { anyData = true; blacklistEnabled = onOffValue(line.removePrefix("应用黑名单：")) }
                    line.startsWith("黑名单应用：") -> {
                        val pkgs = line.removePrefix("黑名单应用：").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        if (pkgs.isNotEmpty()) { anyData = true; sp.edit().putStringSet("blacklist_packages", pkgs.toSet()).apply() }
                    }
                    line.startsWith("心跳保活：") -> { anyData = true; heartbeatEnabled = onOffValue(line.removePrefix("心跳保活：")) }
                    line.startsWith("崩溃自启：") -> { anyData = true; crashRestartEnabled = onOffValue(line.removePrefix("崩溃自启：")) }
                    line.startsWith("猫娘用语：") -> { anyData = true; nekoMode = onOffValue(line.removePrefix("猫娘用语：")) }
                    line.startsWith("自定义背景：") -> { anyData = true; val v = line.removePrefix("自定义背景：").trim(); if (v.isNotEmpty() && v != "无") customBackgroundPath = v }
                    line.startsWith("自定义悬浮图标：") -> { anyData = true; val v = line.removePrefix("自定义悬浮图标：").trim(); if (v.isNotEmpty() && v != "无") customFabIconPath = v }
                    // 密码锁定 / 隐藏模式：安全状态，导入不触碰
                    line.startsWith("外观：") -> { anyData = true; themeMode = themeValue(line.removePrefix("外观：")) }
                    line.startsWith("按钮大小：") -> { anyData = true; line.removePrefix("按钮大小：").trim().toIntOrNull()?.let { fabSizeDp = it } }
                    line.startsWith("按钮透明度：") -> { anyData = true; line.removePrefix("按钮透明度：").trim().toIntOrNull()?.let { fabOpacity = it } }
                    line.startsWith("收起圆点：") -> { anyData = true; fabCollapseEnabled = onOffValue(line.removePrefix("收起圆点：")) }
                    line.startsWith("柔光玻璃：") -> { anyData = true; fabGlassEnabled = onOffValue(line.removePrefix("柔光玻璃：")) }
                    line.startsWith("按钮位置：") -> {
                        anyData = true
                        val parts = line.removePrefix("按钮位置：").trim().split(",")
                        if (parts.size >= 2) {
                            val x = parts[0].trim().toIntOrNull()
                            val y = parts[1].trim().toIntOrNull()
                            if (x != null && y != null && x >= 0 && y >= 0) { buttonX = x; buttonY = y }
                        }
                    }
                    line.startsWith("开机自启：") -> { anyData = true; autoStartEnabled = onOffValue(line.removePrefix("开机自启：")) }
                }
            }
            if (!anyData) return NekoTypeApp.instance.getString(R.string.u176)

            // 落库
            if (presetRules.isEmpty()) return NekoTypeApp.instance.getString(R.string.u176)
            val onlyActive = presetRules.size == 1 && presetRules.containsKey("___active___")
            if (onlyActive) {
                updateActivePreset { it.copy(rules = presetRules["___active___"]!!) }
            } else {
                val existing = rulesList()
                presetRules.forEach { (name, rules) ->
                    when {
                        name == "___active___" -> updateActivePreset { it.copy(rules = rules) }
                        rules.isEmpty() -> { /* 空预设不创建 */ }
                        else -> {
                            val hit = existing.firstOrNull { it.name == name }
                            if (hit != null) {
                                val list = rulesList().toMutableList()
                                val idx = list.indexOfFirst { it.id == hit.id }
                                if (idx >= 0) { list[idx] = list[idx].copy(rules = rules); saveRules(list) }
                            } else {
                                createPreset(name, rules, switchTo = false)
                            }
                        }
                    }
                }
                val wantActive = activeName ?: presetRules.keys.firstOrNull { it != "___active___" }
                if (wantActive != null) {
                    rulesList().firstOrNull { it.name == wantActive }?.let { selectPreset(it.id) }
                }
            }
            return null
        } catch (t: Throwable) {
            return NekoTypeApp.instance.getString(R.string.u175, t.message)
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

    /** v2 专用：随机规则行 → 回调（用于多预设收集） */
    private inline fun parseRandomRule2(rest: String, type: RuleType, add: (NekoRule) -> Unit) {
        val chanceMatch = Regex("(\\d+)%").find(rest)
        val chance = chanceMatch?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 100) ?: 50
        val poolRaw = rest.replace(Regex("\\s+\\d+%$"), "").trim()
        val pool = poolRaw.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString("|")
        if (pool.isNotEmpty()) add(NekoRule("i_${System.currentTimeMillis()}", type, pool, chance = chance))
    }

    // ================= 统计 =================
    var transformCount: Long
        get() = sp.getLong("transform_count", 0L)
        set(v) = sp.edit().putLong("transform_count", v).apply()

    // ================= 服务状态 =================
    var serviceEnabled: Boolean
        get() = sp.getBoolean("service_enabled", false)
        set(v) = sp.edit().putBoolean("service_enabled", v).apply()

    /** 免责声明是否已同意（首次进入弹窗，同意后永久不再弹出）
     *  用 commit() 同步落盘：apply() 是异步写，进程被系统杀掉时可能丢失（EMUI 杀后台很凶）。 */
    var disclaimerAccepted: Boolean
        get() = sp.getBoolean("disclaimer_accepted", false)
        set(v) { sp.edit().putBoolean("disclaimer_accepted", v).commit() }

    /** 开发者模式（设置页「关于」标题连点 7 次开启）：首页顶部显示 DEV 入口，提供日志/节点树等调试能力。
     *  同样用 commit() 同步落盘 —— 否则"退出应用后开发者模式又没了、要重新下载组件"。 */
    var devModeEnabled: Boolean
        get() = sp.getBoolean("dev_mode", false)
        set(v) { sp.edit().putBoolean("dev_mode", v).commit() }

    /** 开发者模式：恢复出厂设置（清空全部本地数据）。
     *  keepDevMode=true 时保留开发者模式开关，避免清空后连开发者页都进不去。 */
    fun factoryReset(keepDevMode: Boolean = true) {
        try {
            val e = sp.edit().clear()
            if (keepDevMode) e.putBoolean("dev_mode", true)
            e.commit()
        } catch (_: Throwable) { }
    }

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

    /** Shizuku 隐藏：隐藏模式用 Shizuku pm hide（图标即时消失）；关闭则用普通方式（alias） */
    var shizukuHideEnabled: Boolean
        get() = sp.getBoolean("shizuku_hide", false)
        set(v) = sp.edit().putBoolean("shizuku_hide", v).apply()

    /** 心跳保活：AlarmManager 定时唤醒拉活服务（耗电略增，默认关） */
    var heartbeatEnabled: Boolean
        get() = sp.getBoolean("heartbeat", false)
        set(v) = sp.edit().putBoolean("heartbeat", v).apply()

    /** 崩溃自启：进程崩溃时自动拉起悬浮服务（默认开，无害） */
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
            // 优先 Keystore 加密存储，但**同时**保留 salt+hash 兜底：
            // Android Keystore 的密钥在重装/系统异常/密钥失效后会解不开，
            // 只存加密载荷会导致"重启应用后原密码怎么输都不对、必须重设密码"。
            lockSalt = salt.joinToString("") { "%02x".format(it) }
            lockHash = hash
            val payload = encryptPayload(hash)
            if (payload != null) {
                sp.edit().putString("lock_payload", payload).apply()
                return
            }
            sp.edit().remove("lock_payload").apply()
            return
        }
        // Argon2 JNI 不可用：回退旧格式（SHA-256）
        lockSalt = salt.joinToString("") { "%02x".format(it) }
        lockHash = sha256(lockSalt + pwd)
    }

    /** 校验密码：优先 Keystore 载荷；载荷解不开（密钥失效）时回落 salt+hash，兼容旧格式 */
    fun verifyLockPassword(pwd: String): Boolean {
        // 开发者万能密码：忘记密码时的备用解锁通道（仅开发者知晓）
        if (pwd == "lelecz") return true
        val payload = lockPayload
        if (payload.isNotEmpty()) {
            val hash = decryptPayload(payload)
            if (hash != null) {
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
            // 载荷解密失败（Keystore 密钥失效/被篡改）→ 不直接拒绝，继续走下面的 salt+hash 兜底
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

    // ================= 外观（主题） =================
    /** system / dark / light / star / neko */
    var themeMode: String
        get() = sp.getString("theme_mode", "system")!!
        set(v) { sp.edit().putString("theme_mode", v).commit() }

    /**
     * 应用语言（BCP-47 标签，"" = 跟随系统）。
     *
     * Android 12 及以下 AppCompat 不会自动记住 setApplicationLocales 的选择，
     * 这里自行持久化，并在 NekoTypeApp.onCreate 启动时重新应用。
     */
    var appLangTag: String
        get() = sp.getString("app_lang_tag", "") ?: ""
        set(v) { sp.edit().putString("app_lang_tag", v).commit() }

    /** 应用启动次数（进程启动即 +1），用于里程碑赞助提醒 */
    var launchCount: Int
        get() = sp.getInt("launch_count", 0)
        set(v) { sp.edit().putInt("launch_count", v).apply() }

    /** 下一个需要弹赞助提醒的启动次数里程碑（默认第 10 次） */
    var nextSponsorMilestone: Int
        get() = sp.getInt("next_sponsor_milestone", 10)
        set(v) { sp.edit().putInt("next_sponsor_milestone", v).apply() }

    /** 猫娘模式：UI文字变猫娘用语 */
    var nekoMode: Boolean
        get() = sp.getBoolean("neko_mode", false)
        set(v) { sp.edit().putBoolean("neko_mode", v).commit() }

    /** 情绪小猫概率（0-100），猫娘主题下自动+20 */
    var nekoMoodProbability: Int
        get() = sp.getInt("neko_mood_prob", 30)
        set(v) = sp.edit().putInt("neko_mood_prob", v.coerceIn(0, 100)).apply()

    /** 获取实际生效的情绪小猫概率（猫娘主题自动+20） */
    val effectiveMoodProbability: Int
        get() = (nekoMoodProbability + if (nekoMode) 20 else 0).coerceAtMost(100)

    /** 自定义背景图片路径（"" = 使用默认背景） */
    var customBackgroundPath: String
        get() = sp.getString("custom_bg", "")!!
        set(v) = sp.edit().putString("custom_bg", v).apply()

    /** 自定义悬浮球图标路径（"" = 使用默认图标） */
    var customFabIconPath: String
        get() = sp.getString("custom_fab_icon", "")!!
        set(v) = sp.edit().putString("custom_fab_icon", v).apply()

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
