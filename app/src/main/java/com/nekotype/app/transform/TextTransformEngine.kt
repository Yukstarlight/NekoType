package com.nekotype.app.transform

import com.nekotype.app.prefs.AppPrefs
import com.nekotype.app.prefs.AppPrefs.NekoRule
import com.nekotype.app.prefs.AppPrefs.RuleType
import kotlin.random.Random

/**
 * 核心变换引擎：对即将发送的消息文本依次应用当前预设中的全部【启用】规则。
 *
 * 应用顺序（默认「类别模式」）：替换文本 → 前缀（含随机前缀）→ 后缀（含随机后缀）→ 样式。
 * 每条规则带**优先级**（1-100，数字越大越先执行），类别内按优先级排序、同级保持列表顺序。
 * 若开启「按等级全局执行」，则所有类别打散、严格按优先级逐条执行。
 * 随机规则按各自的触发概率（1-100）决定是否生效。
 *
 * 返回 TransformResult：除变换结果外，还携带本次实际附加的前缀/后缀，
 * 供「强制篡改键盘」的增量算法剥离上次附加内容，避免实时篡改时重复叠加。
 */
object TextTransformEngine {

    /** 变换结果：text=最终文本；addedPrefix/addedSuffix=本次实际附加的前缀/后缀（未含样式处理） */
    data class TransformResult(
        val text: String,
        val addedPrefix: String = "",
        val addedSuffix: String = ""
    )

    /** 按优先级排序：数字越大越先执行；等级相同 → 保持规则列表原顺序（稳定排序） */
    private fun List<NekoRule>.byPriority(): List<NekoRule> = sortedByDescending { it.priority }

    /** 变换一条消息文本（App 内预览与悬浮按钮共用同一入口） */
    fun transform(input: String): TransformResult {
        if (input.isEmpty()) return TransformResult(input)
        // 全局等级模式：所有类别打散，严格按优先级逐条执行
        if (AppPrefs.priorityGlobalEnabled) return transformByPriority(input)

        var text = input
        var addedPrefix = ""
        var addedSuffix = ""
        val rules = AppPrefs.rules().filter { it.enabled }

        // 1. 替换文本（检测的字 -> 替换的文字）
        rules.filter { it.type == RuleType.REPLACE }.byPriority().forEach { r ->
            if (r.value.isNotEmpty()) text = text.replace(r.value, r.replaceTo)
        }

        // 2. 前缀：固定前缀 + 随机前缀（按概率触发）
        rules.filter { it.type == RuleType.PREFIX }.byPriority().forEach { r ->
            if (r.value.isNotEmpty()) {
                text = r.value + text
                addedPrefix += r.value
            }
        }
        // 2.1 随机前缀（固定）：命中的规则【全部生效】，按等级顺序叠加（等级高的在最左）
        val fixedPrefixPicks = mutableListOf<String>()
        rules.filter { it.type == RuleType.RANDOM_PREFIX }.byPriority().forEach { r ->
            if (Random.nextInt(100) < r.chance) {
                val pool = parsePool(r.value)
                if (pool.isNotEmpty()) fixedPrefixPicks += pool.random()
            }
        }
        if (fixedPrefixPicks.isNotEmpty()) {
            val joined = fixedPrefixPicks.joinToString("")
            text = joined + text
            addedPrefix += joined
        }
        // 2.2 随机前缀（不固定）：命中的规则里【只随机取一条】生效
        val oncePrefixRules = rules.filter {
            it.type == RuleType.RANDOM_PREFIX_ONCE && Random.nextInt(100) < it.chance && parsePool(it.value).isNotEmpty()
        }
        if (oncePrefixRules.isNotEmpty()) {
            val pick = parsePool(oncePrefixRules.random().value).random()
            text = pick + text
            addedPrefix += pick
        }

        // 3. 后缀：固定后缀 + 随机后缀
        rules.filter { it.type == RuleType.SUFFIX }.byPriority().forEach { r ->
            if (r.value.isNotEmpty()) {
                text = text + r.value
                addedSuffix += r.value
            }
        }
        // 3.1 随机后缀（固定）：命中的规则【全部生效】，按等级顺序追加
        rules.filter { it.type == RuleType.RANDOM_SUFFIX }.byPriority().forEach { r ->
            if (Random.nextInt(100) < r.chance) {
                val pool = parsePool(r.value)
                if (pool.isNotEmpty()) {
                    val pick = pool.random()
                    text = text + pick
                    addedSuffix += pick
                }
            }
        }
        // 3.1b 随机后缀（不固定）：命中的规则里【只随机取一条】生效
        val onceSuffixRules = rules.filter {
            it.type == RuleType.RANDOM_SUFFIX_ONCE && Random.nextInt(100) < it.chance && parsePool(it.value).isNotEmpty()
        }
        if (onceSuffixRules.isNotEmpty()) {
            val pick = parsePool(onceSuffixRules.random().value).random()
            text = text + pick
            addedSuffix += pick
        }

        val tail = applyTail(text, addedSuffix)
        return TransformResult(tail.first, addedPrefix, tail.second)
    }

    /**
     * 全局等级模式：把所有启用的规则按优先级从大到小排成一条流水线，逐条执行。
     * 说明：此模式下每条随机规则独立生效（命中后从自己的池里取一条），
     * 「固定 / 不固定」的多规则合并语义只在默认（类别）模式下有效。
     */
    private fun transformByPriority(input: String): TransformResult {
        var text = input
        var addedPrefix = ""
        var addedSuffix = ""
        for (r in AppPrefs.rules().filter { it.enabled }.byPriority()) {
            when (r.type) {
                RuleType.REPLACE -> if (r.value.isNotEmpty()) text = text.replace(r.value, r.replaceTo)
                RuleType.PREFIX -> if (r.value.isNotEmpty()) {
                    text = r.value + text
                    addedPrefix += r.value
                }
                RuleType.SUFFIX -> if (r.value.isNotEmpty()) {
                    text = text + r.value
                    addedSuffix += r.value
                }
                RuleType.RANDOM_PREFIX, RuleType.RANDOM_PREFIX_ONCE ->
                    if (Random.nextInt(100) < r.chance) {
                        val pool = parsePool(r.value)
                        if (pool.isNotEmpty()) {
                            val pick = pool.random()
                            text = pick + text
                            addedPrefix += pick
                        }
                    }
                RuleType.RANDOM_SUFFIX, RuleType.RANDOM_SUFFIX_ONCE ->
                    if (Random.nextInt(100) < r.chance) {
                        val pool = parsePool(r.value)
                        if (pool.isNotEmpty()) {
                            val pick = pool.random()
                            text = text + pick
                            addedSuffix += pick
                        }
                    }
            }
        }
        val tail = applyTail(text, addedSuffix)
        return TransformResult(tail.first, addedPrefix, tail.second)
    }

    /** 收尾：颜文字总开关 + 样式（两种模式共用），返回 (文本, addedSuffix) */
    private fun applyTail(text0: String, suffix0: String): Pair<String, String> {
        var text = text0
        var addedSuffix = suffix0
        if (AppPrefs.emoticonEnabled) {
            val pool = AppPrefs.builtinEmoticons()
            if (pool.isNotEmpty()) {
                val pick = pool.random()
                text = text.trimEnd() + " " + pick
                addedSuffix += " " + pick
            }
        }
        if (AppPrefs.styleSpaced) {
            text = text.toCharArray().joinToString(" ")
        }
        if (AppPrefs.styleUpper) {
            text = text.uppercase()
        }
        return text to addedSuffix
    }

    /** 随机池解析（| 分隔） */
    fun parsePool(raw: String): List<String> =
        raw.split("|").map { it.trim() }.filter { it.isNotEmpty() }
}
