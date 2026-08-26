package com.nekotype.app.transform

import com.nekotype.app.prefs.AppPrefs
import com.nekotype.app.prefs.AppPrefs.NekoRule
import com.nekotype.app.prefs.AppPrefs.RuleType
import kotlin.random.Random

/**
 * 核心变换引擎：对即将发送的消息文本依次应用当前预设中的全部【启用】规则。
 *
 * 应用顺序：替换文本 → 前缀（含随机前缀）→ 后缀（含随机后缀）→ 样式。
 * 随机规则按各自的触发概率（1-100）决定是否生效。
 */
object TextTransformEngine {

    /** 变换一条消息文本（App 内预览与悬浮按钮共用同一入口） */
    fun transform(input: String): String {
        if (input.isEmpty()) return input
        var text = input
        val rules = AppPrefs.rules().filter { it.enabled }

        // 1. 替换文本（检测的字 -> 替换的文字）
        rules.filter { it.type == RuleType.REPLACE }.forEach { r ->
            if (r.value.isNotEmpty()) text = text.replace(r.value, r.replaceTo)
        }

        // 2. 前缀：固定前缀 + 随机前缀（按概率触发）
        rules.filter { it.type == RuleType.PREFIX }.forEach { r ->
            if (r.value.isNotEmpty()) text = r.value + text
        }
        rules.filter { it.type == RuleType.RANDOM_PREFIX }.forEach { r ->
            if (Random.nextInt(100) < r.chance) {
                val pool = r.value.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                if (pool.isNotEmpty()) text = pool.random() + text
            }
        }

        // 3. 后缀：固定后缀 + 随机后缀（按概率触发）
        rules.filter { it.type == RuleType.SUFFIX }.forEach { r ->
            if (r.value.isNotEmpty()) text = text + r.value
        }
        rules.filter { it.type == RuleType.RANDOM_SUFFIX }.forEach { r ->
            if (Random.nextInt(100) < r.chance) {
                val pool = r.value.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                if (pool.isNotEmpty()) text = text + pool.random()
            }
        }

        // 4. 样式
        if (AppPrefs.styleSpaced) {
            text = text.toCharArray().joinToString(" ")
        }
        if (AppPrefs.styleUpper) {
            text = text.uppercase()
        }

        return text
    }

    /** 随机池解析（| 分隔） */
    fun parsePool(raw: String): List<String> =
        raw.split("|").map { it.trim() }.filter { it.isNotEmpty() }
}
