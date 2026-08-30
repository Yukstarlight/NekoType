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
package com.nekotype.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.nekotype.app.overlay.FloatingButtonService
import com.nekotype.app.prefs.AppPrefs
import com.nekotype.app.sys.SysPower
import com.nekotype.app.transform.TextTransformEngine
import com.nekotype.app.util.NekoLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 无障碍服务（系统要求，用户在设置中开启）。
 *
 * 职责：
 * 1. 跟踪当前聚焦的输入框，供悬浮按钮读取、改写文本并触发发送；
 * 2. 智能发送：优先点击"发送"按钮（文字/ID/描述启发式），失败回退 IME 发送动作，
 *    再失败用全局手势点击发送键坐标；
 * 3. 无障碍一连接就确保悬浮服务常驻（悬浮按钮永久显示，与输入法无关）。
 */
class NekoTypeAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile private var instance: NekoTypeAccessibilityService? = null
        fun get(): NekoTypeAccessibilityService? = instance

        // API 34 起 ACTION_IME_ACTION_SEND 不再暴露为公开常量，固定值为 4（EditorInfo.IME_ACTION_SEND）
        private const val ACTION_IME_ACTION_SEND = 4
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 强制篡改键盘：防抖自动变换任务（原生键盘输入停顿后自动篡改） */
    private var autoTransformJob: Job? = null

    /**
     * 强制篡改键盘：每个输入框的增量状态。
     * userOriginal = 用户真实输入（不含附加内容）；lastSet = 上次写回内容；
     * 实时篡改时只把"新增输入"并入 userOriginal 再重新变换，避免后缀/前缀重复叠加。
     */
    private class AutoState(
        var userOriginal: String,
        var lastSet: String,
        var lastWriteTime: Long,
        var addedPrefix: String = "",
        var addedSuffix: String = ""
    )

    private val autoStates = HashMap<String, AutoState>()

    /** 强制篡改键盘：防抖毫秒数 */
    private val AUTO_TRANSFORM_DEBOUNCE_MS = 300L

    /** 回显跳过窗口：写回后 600ms 内文本与 lastSet 一致 → 视为自己的回显，忽略（防死循环） */
    private val ECHO_WINDOW_MS = 600L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        NekoTypeAccessibilityBridge.attach(this)
        // 无障碍一连接就确保悬浮服务常驻（用户开过"启动服务"即可，之后一直显示）
        if (AppPrefs.serviceEnabled) {
            FloatingButtonService.start(this)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val src = event.source ?: return
                if (src.isEditable) {
                    NekoTypeAccessibilityBridge.setActiveNode(src)
                }
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val src = event.source ?: return
                if (src.isEditable) {
                    NekoTypeAccessibilityBridge.setActiveNode(src)
                    // 强制篡改键盘：原生键盘输入时自动篡改（防抖，写回触发的事件会自动跳过）。
                    // 生效条件：开关开启 且 服务已启动（点击「启动服务」后才生效）且 不在应用黑名单
                    if (AppPrefs.forceKeyboardEnabled && AppPrefs.serviceEnabled && !isBlacklistedApp(src)) {
                        scheduleAutoTransform(src)
                    }
                }
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // 发送按钮兜底：用户手点发送时立即篡改（不等防抖、不受标点触发限制），确保发出的是篡改后文本。
                // 黑名单应用内不自动篡改（含兜底）
                val src = event.source ?: return
                if (AppPrefs.forceKeyboardEnabled && AppPrefs.serviceEnabled && isSendButtonNode(src) && !isBlacklistedApp(src)) {
                    val edit = NekoTypeAccessibilityBridge.activeNode
                    if (edit != null) {
                        autoTransformJob?.cancel()
                        scope.launch { autoTransform(edit, fromSendFallback = true) }
                    }
                }
            }
        }
    }

    override fun onInterrupt() { /* no-op */ }

    override fun onDestroy() {
        instance = null
        NekoTypeAccessibilityBridge.detach()
        super.onDestroy()
    }

    // ---------- 公共动作：变换 + 发送 ----------

    /** 读取当前输入框 → 文本变换 → 写回（带验证重试）→ 自动发送 */
    fun transformActiveTextAndSend() {
        scope.launch {
            val node = NekoTypeAccessibilityBridge.activeNode
            if (node == null) {
                NekoLog.warn("变换失败：未找到输入框节点")
                return@launch
            }
            if (!node.refresh()) {
                NekoLog.warn("变换失败：输入框节点已失效")
                return@launch
            }
            val current = node.text?.toString().orEmpty()
            if (current.isEmpty()) {
                NekoLog.warn("变换跳过：输入框内容为空")
                return@launch
            }

            val result = TextTransformEngine.transform(current)
            val transformed = result.text
            setTextAndCursor(node, transformed)

            // 验证替换是否成功，失败则重试一次（部分输入法/应用对 SET_TEXT 偶发吞事件）
            delay(150)
            if (node.refresh() && node.text?.toString() != transformed) {
                // 静默修改：优先用 Shizuku 直接注入（无弹窗、绕过应用限制）；注入失败再退回无障碍重试
                if (!silentInject(node, transformed)) {
                    setTextAndCursor(node, transformed)
                    delay(150)
                }
            }
            // 同步强制篡改键盘的增量状态，避免手动变换后自动模式重复叠加
            syncAutoState(node, result, transformed)

            AppPrefs.transformCount = AppPrefs.transformCount + 1
            AppPrefs.incrementToday()
            val ruleCount = AppPrefs.rules().count { it.enabled }
            // 记录在哪个应用中使用（从当前窗口包名解析应用名）
            val pkg = rootInActiveWindow?.packageName?.toString() ?: node.packageName?.toString()
            val appLabel = try {
                if (pkg != null) {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                } else "未知应用"
            } catch (_: Throwable) { pkg ?: "未知应用" }
            NekoLog.ok("在「$appLabel」中已变换发送（${ruleCount} 条规则生效，累计 ${AppPrefs.transformCount} 次）")

            if (AppPrefs.autoSend) {
                delay(120) // 等输入框提交文本
                performSend(node)
            }
        }
    }

    /** 写回文本并把光标移到末尾（对手同款：写回后继续输入不打断） */
    private fun setTextAndCursor(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (ok) {
                val sel = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
            }
            ok
        } catch (_: Throwable) {
            false
        }
    }

    /** 手动变换后同步增量状态，防止自动模式把手动结果当作"原文"再叠加 */
    private fun syncAutoState(node: AccessibilityNodeInfo, result: TextTransformEngine.TransformResult, written: String) {
        try {
            if (!AppPrefs.forceKeyboardEnabled) return
            val key = node.viewIdResourceName ?: "node_${node.hashCode()}"
            autoStates[key] = AutoState(
                userOriginal = result.text.removePrefix(result.addedPrefix).removeSuffix(result.addedSuffix),
                lastSet = written,
                lastWriteTime = System.currentTimeMillis(),
                addedPrefix = result.addedPrefix,
                addedSuffix = result.addedSuffix
            )
        } catch (_: Throwable) { }
    }

    // ---------- 强制篡改键盘（自动实时篡改） ----------

    /** 原生键盘输入触发：防抖后自动篡改（连续输入会重置计时器，停顿后执行） */
    private fun scheduleAutoTransform(src: AccessibilityNodeInfo) {
        try {
            if (src.isPassword) return
            autoTransformJob?.cancel()
            autoTransformJob = scope.launch {
                delay(AUTO_TRANSFORM_DEBOUNCE_MS)
                autoTransform(src)
            }
        } catch (_: Throwable) { }
    }

    private suspend fun autoTransform(src: AccessibilityNodeInfo, fromSendFallback: Boolean = false) {
        try {
            // 双保险：开关 + 服务都必须开启（点击「启动服务」后才生效）
            if (!AppPrefs.forceKeyboardEnabled || !AppPrefs.serviceEnabled) return
            if (isBlacklistedApp(src)) return
            if (!src.refresh()) return
            if (!src.isEditable) return
            val trim = src.text?.toString()?.trim().orEmpty()
            if (trim.isEmpty()) return
            // 标点触发模式（喵喵同款）：文本以标点结尾才篡改（打完一句才改）；
            // 发送兜底不受此限制（用户点发送必然处理）
            if (AppPrefs.punctTriggerEnabled && !fromSendFallback) {
                val puncts = listOf('。', '！', '？', '!', '?', '，', ',', '…', '～', '~')
                if (trim.lastOrNull() !in puncts) return
            }
            val key = src.viewIdResourceName ?: "node_${src.hashCode()}"
            val st = autoStates[key] ?: AutoState("", "", 0L)
            val now = System.currentTimeMillis()

            // 1. 回显跳过：我们写回后 600ms 内且文本与 lastSet 一致 → 自己的回显，忽略（防死循环）
            if (st.lastWriteTime > 0 && now - st.lastWriteTime < ECHO_WINDOW_MS && trim == st.lastSet) {
                st.lastWriteTime = 0L // 消费掉本次回显
                return
            }

            // 2. 增量：文本以 lastSet 开头（用户在末尾继续输入）→ 只把新增部分并入原文
            if (st.lastSet.isNotEmpty() && trim.startsWith(st.lastSet)) {
                st.userOriginal += trim.substring(st.lastSet.length)
            } else {
                // 3. 重置：用户删除/中间插入 → 从当前文本剥离上次附加的前缀/后缀，视为新原文
                var base = trim
                if (st.addedSuffix.isNotEmpty() && base.endsWith(st.addedSuffix)) {
                    base = base.substring(0, base.length - st.addedSuffix.length)
                }
                if (st.addedPrefix.isNotEmpty() && base.startsWith(st.addedPrefix)) {
                    base = base.substring(st.addedPrefix.length)
                }
                if (AppPrefs.styleSpaced) base = base.replace(" ", "")
                st.userOriginal = base.trim()
            }
            if (st.userOriginal.isEmpty()) return

            // 4. 变换并写回（基于 userOriginal，不是基于当前全文 → 永不叠加）
            val r = TextTransformEngine.transform(st.userOriginal)
            if (r.text == trim || r.text.isEmpty()) return

            st.addedPrefix = r.addedPrefix
            st.addedSuffix = r.addedSuffix
            st.lastSet = r.text
            st.lastWriteTime = now
            autoStates[key] = st
            setTextAndCursor(src, r.text)
            delay(120)
            if (src.refresh() && src.text?.toString() != r.text) {
                silentInject(src, r.text)
            }
            AppPrefs.transformCount = AppPrefs.transformCount + 1
            AppPrefs.incrementToday()
            val appLabel = try {
                val pkg = src.packageName?.toString()
                if (pkg != null) packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                else "未知应用"
            } catch (_: Throwable) { "未知应用" }
            NekoLog.ok("强制篡改键盘：在「$appLabel」自动变换为「${r.text}」")

            if (AppPrefs.autoSend) {
                delay(120)
                performSend(src)
            }
        } catch (_: Throwable) { }
    }

    /** 事件源是否为发送按钮（点击发送兜底用） */
    private fun isSendButtonNode(n: AccessibilityNodeInfo): Boolean {
        return try {
            val id = n.viewIdResourceName?.lowercase().orEmpty()
            val text = n.text?.toString().orEmpty()
            val desc = n.contentDescription?.toString().orEmpty()
            id.contains("send") || id.contains("发送") || id.contains("btn_send") ||
                    text.contains("发送") || text.equals("send", ignoreCase = true) ||
                    desc.contains("发送") || desc.equals("send", ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }

    /** 当前节点所在应用是否在自动篡改黑名单中（黑名单内不自动篡改） */
    private fun isBlacklistedApp(n: AccessibilityNodeInfo): Boolean {
        if (!AppPrefs.blacklistEnabled) return false
        return try {
            n.packageName?.toString()?.let { AppPrefs.isBlacklisted(it) } ?: false
        } catch (_: Throwable) {
            false
        }
    }

    // ---------- 静默修改（Shizuku 注入） ----------

    /**
     * 静默注入：当无障碍写回被目标应用拒绝时，通过 Shizuku/Root 执行 shell
     * input text 直接注入（无弹窗、无剪贴板提示）。仅支持 ASCII 文本。
     * @return 注入后验证通过返回 true
     */
    private suspend fun silentInject(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            if (!AppPrefs.silentModifyEnabled) return false
            if (!SysPower.isInjectionSafe(text)) return false
            if (!SysPower.privilegedChannelReady()) return false
            val r = withContext(Dispatchers.IO) { SysPower.shizukuInjectText(text) }
            if (!r.success) return false
            delay(250)
            node.refresh() && node.text?.toString() == text
        } catch (_: Throwable) {
            false
        }
    }

    /** 三级发送：点击发送键 → 手势点击 → IME 发送动作 */
    private fun performSend(editNode: AccessibilityNodeInfo) {
        val root = rootInActiveWindow
        val inputBounds = Rect().also { editNode.getBoundsInScreen(it) }
        val sendNode = if (root != null) findSendNode(root, inputBounds) else null

        // 1. 无障碍点击发送键
        if (sendNode != null && sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return
        }
        // 2. 全局手势点击发送键坐标（部分应用如微信会忽略 ACTION_CLICK）
        if (sendNode != null) {
            tapNode(sendNode)
            return
        }
        // 3. 兜底：IME 发送动作
        editNode.performAction(ACTION_IME_ACTION_SEND)
    }

    /**
     * 全平台兼容的发送键检测（打分制）：
     * - 文本/描述命中「发送/Send/送出/送信/发布…」：+100
     * - 控件 ID 命中 send/发送 等：+80
     * - Button/ImageButton 类且靠近输入框：+60
     * - 位于输入框右侧附近的小控件（典型发送键位置）：+50
     * - 文本命中但自身不可点击：沿父级找最近可点击祖先：+90
     * 取最高分；找不到返回 null。
     */
    private fun findSendNode(root: AccessibilityNodeInfo, inputBounds: Rect): AccessibilityNodeInfo? {
        val sendTexts = listOf(
            "发送", "send", "送出", "送信", "發送", "发布", "發佈", "发信", "發信", "发送给"
        )
        val sendIdHints = listOf(
            "send", "发送", "發送", "btn_send", "ivsend", "iv_send", "iv_send", "send_btn",
            "btn_send", "chat_send", "input_send", "send_button"
        )

        data class Cand(val node: AccessibilityNodeInfo, val score: Int)

        val candidates = mutableListOf<Cand>()

        fun nearInput(n: AccessibilityNodeInfo): Boolean {
            if (inputBounds.isEmpty) return true
            val b = Rect()
            n.getBoundsInScreen(b)
            if (b.isEmpty) return false
            val nearY = b.top > inputBounds.top - 400 && b.bottom < inputBounds.bottom + 400
            val nearX = b.left > inputBounds.left - 300 && b.left < inputBounds.right + 600
            return nearY && nearX
        }

        fun scoreNode(n: AccessibilityNodeInfo) {
            if (!n.isVisibleToUser || !n.isClickable || n.isEditable) return
            val text = n.text?.toString()?.trim().orEmpty()
            val desc = n.contentDescription?.toString()?.trim().orEmpty()
            val id = n.viewIdResourceName?.lowercase().orEmpty()
            val cls = n.className?.toString().orEmpty()

            var s = 0
            // 文本/描述直接命中
            if (sendTexts.any {
                    text.equals(it, ignoreCase = true) || desc.equals(it, ignoreCase = true) ||
                            text.contains(it, ignoreCase = true) || desc.contains(it, ignoreCase = true)
                }) s += 100
            // ID 命中
            if (sendIdHints.any { id.contains(it) }) s += 80
            // 按钮类控件且靠近输入框
            if (cls.contains("Button") && nearInput(n)) s += 60
            // 位于输入框右侧附近的小控件（典型发送键位置）
            if (!inputBounds.isEmpty) {
                val b = Rect()
                n.getBoundsInScreen(b)
                if (!b.isEmpty && b.left >= inputBounds.right - 150 &&
                    b.height() in 40..300 && b.width() in 40..400
                ) s += 50
            }
            if (s > 0) candidates.add(Cand(AccessibilityNodeInfo.obtain(n), s))
        }

        // 第一遍：遍历所有可点击节点打分
        fun walk(n: AccessibilityNodeInfo) {
            try {
                scoreNode(n)
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { walk(it) }
                }
            } catch (_: Throwable) { /* 节点已失效 */ }
        }

        // 第二遍：文本命中但自身不可点击 → 用最近可点击祖先（微信发送键常是子 TextView 带字）
        fun walkTextAncestor(n: AccessibilityNodeInfo) {
            try {
                val text = n.text?.toString()?.trim().orEmpty()
                val desc = n.contentDescription?.toString()?.trim().orEmpty()
                if (!n.isClickable && sendTexts.any {
                        text.equals(it, ignoreCase = true) || desc.equals(it, ignoreCase = true)
                    }
                ) {
                    var p = n.parent
                    var depth = 0
                    while (p != null && depth < 5) {
                        if (p.isClickable) {
                            candidates.add(Cand(AccessibilityNodeInfo.obtain(p), 90))
                            break
                        }
                        p = p.parent
                        depth++
                    }
                }
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { walkTextAncestor(it) }
                }
            } catch (_: Throwable) { }
        }

        try {
            walk(root)
            walkTextAncestor(root)
        } catch (_: Throwable) { }

        val best = candidates.maxByOrNull { it.score }
        candidates.forEach { if (it.node !== best?.node) it.node.recycle() }
        return best?.node
    }

    /** 用全局手势点击某节点中心（针对忽略 ACTION_CLICK 的应用） */
    private fun tapNode(node: AccessibilityNodeInfo) {
        try {
            if (Build.VERSION.SDK_INT < 24) return
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.isEmpty) return
            val path = Path().apply { moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat()) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 60)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (_: Throwable) { }
    }
}
