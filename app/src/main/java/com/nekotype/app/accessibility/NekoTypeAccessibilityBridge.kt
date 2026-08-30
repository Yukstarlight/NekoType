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
