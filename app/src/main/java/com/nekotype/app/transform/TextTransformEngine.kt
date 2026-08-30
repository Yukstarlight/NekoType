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

    /** 变换一条消息文本（App 内预览与悬浮按钮共用同一入口） */
    fun transform(input: String): TransformResult {
        if (input.isEmpty()) return TransformResult(input)
        var text = input
        var addedPrefix = ""
        var addedSuffix = ""
        val rules = AppPrefs.rules().filter { it.enabled }

        // 1. 替换文本（检测的字 -> 替换的文字）
        rules.filter { it.type == RuleType.REPLACE }.forEach { r ->
            if (r.value.isNotEmpty()) text = text.replace(r.value, r.replaceTo)
        }

        // 2. 前缀：固定前缀 + 随机前缀（按概率触发）
        rules.filter { it.type == RuleType.PREFIX }.forEach { r ->
            if (r.value.isNotEmpty()) {
                text = r.value + text
                addedPrefix += r.value
            }
        }
        rules.filter { it.type == RuleType.RANDOM_PREFIX }.forEach { r ->
            if (Random.nextInt(100) < r.chance) {
                val pool = r.value.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                if (pool.isNotEmpty()) {
                    val pick = pool.random()
                    text = pick + text
                    addedPrefix += pick
                }
            }
        }

        // 3. 后缀：固定后缀 + 随机后缀 + 每句后缀（按概率触发）
        rules.filter { it.type == RuleType.SUFFIX }.forEach { r ->
            if (r.value.isNotEmpty()) {
                text = text + r.value
                addedSuffix += r.value
            }
        }
        rules.filter { it.type == RuleType.SUFFIX_EACH }.forEach { r ->
            if (r.value.isNotEmpty()) {
                val before = text
                text = appendPerSentence(text, r.value)
                addedSuffix += r.value
            }
        }
        rules.filter { it.type == RuleType.RANDOM_SUFFIX }.forEach { r ->
            if (Random.nextInt(100) < r.chance) {
                val pool = r.value.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                if (pool.isNotEmpty()) {
                    val pick = pool.random()
                    text = text + pick
                    addedSuffix += pick
                }
            }
        }

        // 3.5 随机颜文字（喵喵同款：文本末尾加空格 + 随机颜文字；留空用内置颜文字库）
        rules.filter { it.type == RuleType.RANDOM_EMOTICON }.forEach { r ->
            if (Random.nextInt(100) < r.chance) {
                val pool = if (r.value.isNotBlank()) {
                    r.value.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                } else {
                    AppPrefs.builtinEmoticons()
                }
                if (pool.isNotEmpty()) {
                    val pick = pool.random()
                    text = text.trimEnd() + " " + pick
                    addedSuffix += " " + pick
                }
            }
        }

        // 3.6 行为与样式总开关：开启后每条消息自动加随机颜文字（必发，喵喵同款）
        if (AppPrefs.emoticonEnabled) {
            val pool = AppPrefs.builtinEmoticons()
            if (pool.isNotEmpty()) {
                val pick = pool.random()
                text = text.trimEnd() + " " + pick
                addedSuffix += " " + pick
            }
        }

        // 4. 样式
        if (AppPrefs.styleSpaced) {
            text = text.toCharArray().joinToString(" ")
        }
        if (AppPrefs.styleUpper) {
            text = text.uppercase()
        }

        return TransformResult(text, addedPrefix, addedSuffix)
    }

    /**
     * 每句追加后缀（喵喵同款）：按标点（，。！？!? 空格）断句，每句末尾加后缀，标点保留。
     * 例：文本「好的。收到」+ 后缀「喵」→「好的喵。收到喵」
     */
    fun appendPerSentence(text: String, suffix: String): String {
        if (suffix.isEmpty()) return text
        val pattern = Regex("([，,。！!？?\\s]+)")
        val parts = mutableListOf<String>()
        val seps = mutableListOf<String>()
        var i = 0
        for (m in pattern.findAll(text)) {
            parts.add(text.substring(i, m.range.first))
            seps.add(m.value)
            i = m.range.last + 1
        }
        if (i < text.length) {
            parts.add(text.substring(i))
        } else if (parts.isNotEmpty() && i == text.length) {
            parts.add("")
        }
        if (parts.isEmpty()) parts.add(text)
        val sb = StringBuilder()
        parts.forEachIndexed { idx, part ->
            val t = part.trim()
            if (t.isNotEmpty()) {
                sb.append(t).append(suffix)
            }
            if (idx < seps.size) sb.append(seps[idx])
        }
        val result = sb.toString().trim()
        return result.ifEmpty { text + suffix }
    }

    /** 随机池解析（| 分隔） */
    fun parsePool(raw: String): List<String> =
        raw.split("|").map { it.trim() }.filter { it.isNotEmpty() }
}
