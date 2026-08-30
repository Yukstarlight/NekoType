package com.nekotype.app.accessibility

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍桥接层：悬浮按钮服务通过它调用无障碍服务持有的
 * 当前输入框节点（两个进程内组件，无需跨进程）。
 */
object NekoTypeAccessibilityBridge {

    @Volatile private var service: NekoTypeAccessibilityService? = null

    @Volatile var activeNode: AccessibilityNodeInfo? = null
        private set

    fun attach(svc: NekoTypeAccessibilityService) {
        service = svc
    }

    fun detach() {
        service = null
        activeNode?.recycle()
        activeNode = null
    }

    fun isServiceReady(): Boolean = service != null

    fun hasActiveNode(): Boolean = activeNode != null

    fun setActiveNode(node: AccessibilityNodeInfo) {
        activeNode?.recycle()
        activeNode = AccessibilityNodeInfo.obtain(node)
    }

    /** 悬浮按钮点击时调用：让无障碍服务执行 变换 + 发送 */
    fun requestTransformAndSend() {
        service?.transformActiveTextAndSend()
    }
}
