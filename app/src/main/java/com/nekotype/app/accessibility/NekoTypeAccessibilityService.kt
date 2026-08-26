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
import com.nekotype.app.transform.TextTransformEngine
import com.nekotype.app.util.NekoLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
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

            val transformed = TextTransformEngine.transform(current)
            setText(node, transformed)

            // 验证替换是否成功，失败则重试一次（部分输入法/应用对 SET_TEXT 偶发吞事件）
            delay(150)
            if (node.refresh() && node.text?.toString() != transformed) {
                setText(node, transformed)
                delay(150)
            }

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

    private fun setText(node: AccessibilityNodeInfo, text: String) {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
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
