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
import com.nekotype.app.R
import com.nekotype.app.prefs.AppPrefs
import com.nekotype.app.sys.SysPower
import com.nekotype.app.transform.TextTransformEngine
import com.nekotype.app.util.NekoLog
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 无障碍服务（系统要求，用户在设置中开启）。
 *
 * 职责：
 * 1. 跟踪当前聚焦的输入框，供悬浮按钮读取、改写文本并触发发送；
 * 2. 智能发送：优先点击"发送"按钮（文字/ID/描述启发式），失败回退 IME 发送动作，
 *    再失败用全局手势点击发送键坐标；
 * 3. 无障碍一连接就确保悬浮服务常驻（悬浮按钮永久显示，与输入法无关）。
 */
open class NekoTypeAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile private var instance: NekoTypeAccessibilityService? = null
        fun get(): NekoTypeAccessibilityService? = instance

        // API 34 起 ACTION_IME_ACTION_SEND 不再暴露为公开常量，固定值为 4（EditorInfo.IME_ACTION_SEND）
        private const val ACTION_IME_ACTION_SEND = 4

        // ===== 微信深度适配（多级兜底策略）=====
        /** 微信包名 */
        const val WECHAT_PKG = "com.tencent.mm"

        /** 微信输入框控件 ID：微信资源名随版本变化，历史 ID 全部保留逐个尝试 */
        private val WECHAT_EDIT_IDS = listOf(
            "com.tencent.mm:id/chatting_content_et",
            "com.tencent.mm:id/alk",
            "com.tencent.mm:id/alj",
            "com.tencent.mm:id/y5"
        )

        /** 微信发送键控件 ID（同样多版本兜底） */
        private val WECHAT_SEND_IDS = listOf(
            "com.tencent.mm:id/chatting_send_btn",
            "com.tencent.mm:id/anv",
            "com.tencent.mm:id/emoji_send_btn"
        )

        /** QQ / QQ 轻聊版 / TIM 发送键控件 ID */
        private val QQ_SEND_IDS = listOf(
            "com.tencent.mobileqq:id/fun_btn",
            "com.tencent.mobileqq:id/send_btn",
            "com.tencent.mobileqq:id/sendBtn",
            "com.tencent.mobileqqi:id/send_btn",
            "com.tencent.tim:id/send_btn"
        )

        /** 微信发送键文本（部分版本发送键是带文字的 TextView） */
        private val WECHAT_SEND_TEXTS = listOf("发送", "Send")

        /** 粘贴菜单文字（长按粘贴兜底用） */
        private val PASTE_TEXTS = listOf("粘贴", "粘貼", "Paste")
    }

        /**
     * 全局协程异常兜底：无障碍服务里任何未捕获异常都不允许杀掉进程——
     * 进程被杀/崩溃会被系统记成"此服务出现故障"并停用（设置页那一行红字）。
     */
    private val crashGuard = kotlinx.coroutines.CoroutineExceptionHandler { _, t ->
        android.util.Log.e("NekoA11y", "协程异常已吞: ${t.javaClass.simpleName}: ${t.message}")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + crashGuard)

    /** 强制篡改键盘：防抖自动变换任务（原生键盘输入停顿后自动篡改） */
    private var autoTransformJob: Job? = null

    /** 删除优化的恢复任务：用户停手 500ms 后解除"删除模式"并重新改写 */
    private var deleteRecoveryJob: Job? = null

    /** 防重入处理锁（processing 标志：并发事件不重复处理） */
    @Volatile private var autoProcessing = false

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
        var addedSuffix: String = "",
        /** 删除优化用：上一次看到的输入框文本（用于判断"是不是在删字"） */
        var lastSeenText: String = "",
        /** 盲操作（读不到文本时只追加后缀）用：上次追加时间，冷却防重复 */
        var lastBlindTime: Long = 0L
    )

    private val autoStates = HashMap<String, AutoState>()

    /** 最近一次窗口切换的包名（仅记录，不用于取消任务） */
    private var lastWindowPkg = ""

    /** 启动服务后待应用一次"应用预设映射"（只应用一次，之后不再静默改预设） */
    private var mappingPending = false
    private var lastServiceEnabled = false

    /**
     * 应用预设映射：按映射切换当前预设。**只在服务启动时调用一次**——
     * 原来挂在前台应用变化上，进任何被映射的应用都会把你手选的预设改掉且不还原。
     */
    private fun applyMappedPreset(pkg: String) {
        try {
            val mapped = AppPrefs.mappedPresetFor(pkg) ?: return
            if (mapped == AppPrefs.activePresetId()) return
            if (AppPrefs.presetList().none { it.first == mapped }) return
            AppPrefs.selectPreset(mapped)
            NekoLog.adjust("服务启动：应用映射 $pkg → 「${AppPrefs.activePresetName()}」")
        } catch (_: Throwable) {
        }
    }

    /** 强制篡改键盘：内容变化事件（微信刷屏式触发）的短防抖毫秒数 */
    private val AUTO_TRANSFORM_DEBOUNCE_MS = 150L

    /** 回显跳过窗口：写回后 600ms 内文本与 lastSet 一致 → 视为自己的回显，忽略（防死循环） */
    private val ECHO_WINDOW_MS = 600L

    override fun onServiceConnected() {
        try {
            onServiceConnectedSafe()
        } catch (t: Throwable) {
            // 无障碍回调里异常逃逸 → 系统会把服务标记为"此服务出现故障"并停用，必须全吞
            android.util.Log.e("NekoA11y", "onServiceConnected 异常已吞: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun onServiceConnectedSafe() {
        super.onServiceConnected()
        instance = this
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            // 关键：flagReportViewIds 才能对微信等其他应用用 findAccessibilityNodeInfosByViewId；
            // flagIncludeNotImportantViews 否则微信输入框（标记 not important）根本不进节点树
            flags = AccessibilityServiceInfo.DEFAULT or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 50
        }
        NekoTypeAccessibilityBridge.attach(this)
        mappingPending = true
        lastServiceEnabled = AppPrefs.serviceEnabled
        // 连接时如果已经停在被映射的应用里，立即应用一次
        try {
            val fg = rootInActiveWindow?.packageName?.toString().orEmpty()
            if (AppPrefs.serviceEnabled && fg.isNotEmpty() && AppPrefs.mappedPresetFor(fg) != null) {
                mappingPending = false
                applyMappedPreset(fg)
            }
        } catch (_: Throwable) {
        }
        // 无障碍一连接就确保悬浮服务常驻（用户开过"启动服务"即可，之后一直显示）
        if (AppPrefs.serviceEnabled) {
            FloatingButtonService.start(this)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        try {
            handleAccessibilityEvent(event)
        } catch (t: Throwable) {
            // 【关键】无障碍回调里任何异常逃逸，系统都会把服务判定为故障并停用
            // （设置里显示"此服务出现故障"）。这里一律吞掉，保证服务不死。
            android.util.Log.e(
                "NekoA11y",
                "事件处理异常已吞(type=${event.eventType}): ${t.javaClass.simpleName}: ${t.message}"
            )
        }
    }

    /** 微信事件节流（NyaNote 同款 300ms）：合并微信刷屏式事件，避免每次都扫窗口树 */
    private var lastWeChatProbeTime = 0L

    /** 最近一次微信事件携带的文本（事件里的 EditText 内容，节点读空时的原文来源） */
    @Volatile private var lastWeChatEventText = ""

    /**
     * 微信事件统一入口（NyaNote 同款架构）：
     * **完全不依赖 event.source**（本机型上微信事件 source 常为 null，依赖它 = 静默全灭），
     * 只把事件当"闹钟"，输入框从活动窗口根现找。每个失败点都有日志。
     */
    private var adminAutoJob: kotlinx.coroutines.Job? = null

    /**
     * 无障碍直写：设备管理员一键激活（皆成守护孩子端同款思路）。
     * 打开系统激活页后，由本服务轮询找到「激活」按钮直接点击——
     * 不依赖用户手点、不依赖 Shizuku、页面哪怕显示异常也能点中。
     */
    fun startDeviceAdminAutoActivate() {
        if (SysPower.isDeviceAdminActive()) {
            NekoLog.ok("设备管理员已激活，无需重复操作")
            return
        }
        try {
            val intent = android.content.Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, SysPower.adminComponent)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (t: Throwable) {
            NekoLog.error("打开设备管理员激活页失败：" + t.javaClass.simpleName)
            return
        }
        adminAutoJob?.cancel()
        adminAutoJob = scope.launch {
            // 最多 12 秒，每 600ms 找一次「激活」按钮
            val texts = listOf("激活", "启用", "Activate", "Activate this device admin app")
            repeat(20) {
                delay(600)
                val root = getBestRoot(null) ?: return@repeat
                val btn = findNodeByText(root, texts)
                if (btn != null) {
                    val clicked = btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (!clicked) tapNode(btn)
                    NekoLog.ok("设备管理员：无障碍直点「激活」已执行")
                    delay(800)
                    if (SysPower.isDeviceAdminActive()) NekoLog.ok("设备管理员激活成功 ✅")
                    return@launch
                }
            }
            NekoLog.warn("设备管理员：12 秒内未找到激活按钮")
        }
    }

    private fun handleWeChatEvent(event: AccessibilityEvent) {
        val now = System.currentTimeMillis()
        if (now - lastWeChatProbeTime < 300L) return
        lastWeChatProbeTime = now
        // 首选：事件自带节点（微信屏蔽控件树时这是唯一能拿到输入框的途径）
        lastWeChatEventText = try { event.text?.joinToString("").orEmpty() } catch (_: Throwable) { "" }
        val srcNode = try { event.source } catch (_: Throwable) { null }
        // NyaNote 同款：在事件回调内【同步】从活动窗口根找输入框——回调上下文的窗口树访问
        // 最可靠（协程延迟后再取会拿到 systemui 空壳根）；事件节点只读不出内容时以树节点为准
        var edit: AccessibilityNodeInfo? = srcNode
        if (edit == null || edit.text.isNullOrEmpty()) {
            val root = try { rootInActiveWindow } catch (_: Throwable) { null }
            val found = root?.takeIf { it.childCount > 0 }?.let { r ->
                findWeChatEditById(r) ?: findEditableNode()
            }
            android.util.Log.e("NekoA11y",
                "回调内直查: " + (if (root != null) "root=${pkgOf(root)} cc=${root.childCount}" else "root=null") +
                        " found=${found != null} srcText=${edit?.text?.take(10) ?: "null"}")
            if (found != null) edit = found
        }
        val node = edit
        if (node != null) {
            NekoTypeAccessibilityBridge.setActiveNode(node)
            if (shouldAutoTransform(node)) {
                scheduleAutoTransform(node, immediate = true)
            }
        } else {
            gateLog("微信输入框未找到", windowsSummary())
        }
    }

    private fun handleAccessibilityEvent(event: AccessibilityEvent) {
        // 诊断：微信事件全记录（Log.e 级别，EMUI 不会过滤；定位问题用）
        val isWeChatPkg = event.packageName?.toString() == WECHAT_PKG
        if (isWeChatPkg) {
            val srcOk = try { event.source != null } catch (_: Throwable) { false }
            android.util.Log.e(
                "NekoA11y",
                "微信事件 type=${event.eventType} text=${event.text?.joinToString("")?.take(20)} cls=${event.className} src=$srcOk"
            )
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                // 微信走统一入口（不依赖 source）
                if (isWeChatPkg) { handleWeChatEvent(event); return }
                val src = event.source ?: return
                if (src.isEditable) {
                    NekoTypeAccessibilityBridge.setActiveNode(src)
                }
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                if (isWeChatPkg) { handleWeChatEvent(event); return }
                val src = event.source ?: return
                val editNode = if (src.isEditable) src else null
                if (editNode != null) {
                    NekoTypeAccessibilityBridge.setActiveNode(editNode)
                    if (shouldAutoTransform(editNode)) {
                        scheduleAutoTransform(editNode, immediate = true)
                    }
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 微信等自研输入框不触发常规焦点/文本事件，靠内容变化事件驱动
                if (!isWeChatPkg) return
                handleWeChatEvent(event)
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // 窗口切换只记录包名，**不取消防抖、不清空状态**。
                // 旧实现在这里取消 pending 任务 + 清空 autoStates，微信里切键盘/面板时会打断 1000ms 防抖，
                // 导致"强制篡改键盘"永远排不上队（篡改模式在微信失效的元凶之一）。
                lastWindowPkg = event.packageName?.toString().orEmpty()
                // 应用预设映射：只在服务启动后应用一次（不再随前台应用变化静默改项目前预设）
                if (AppPrefs.serviceEnabled && !lastServiceEnabled) mappingPending = true
                lastServiceEnabled = AppPrefs.serviceEnabled
                if (mappingPending && AppPrefs.serviceEnabled && lastWindowPkg.isNotEmpty() &&
                    AppPrefs.mappedPresetFor(lastWindowPkg) != null
                ) {
                    // 没被映射的前台应用不消耗这次机会：等真正进入被映射的应用时应用一次
                    mappingPending = false
                    applyMappedPreset(lastWindowPkg)
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
                } else if (isSendButtonNode(src)) {
                    if (!AppPrefs.forceKeyboardEnabled) gateLog("发送兜底未触发：强制篡改键盘未开启")
                    else if (!AppPrefs.serviceEnabled) gateLog("发送兜底未触发：服务开关未开启")
                    else gateLog("发送兜底未触发：名单拦截 pkg=${pkgOf(src)}")
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

    /**
     * 开发者模式：导出当前窗口的无障碍节点树（文本）。
     * 每行一个节点：类名 / 资源 ID / 文本 / 描述 / 属性 / 屏幕坐标。
     * 微信等应用用 uiautomator dump 拿不到节点树，只能靠无障碍读，是适配排错的关键手段。
     */
    fun dumpWindowTree(): String? {
        return try {
            val root = getBestRoot() ?: return "（取不到窗口根节点：无障碍可能未连接或无前台窗口）"
            val sb = StringBuilder()
            sb.append("package=").append(root.packageName).append("  window=").append(root.windowId).append("\n")
            var count = 0
            fun walk(n: AccessibilityNodeInfo, depth: Int) {
                if (depth > 14 || count > 600) return
                count++
                val cls = n.className?.toString()?.substringAfterLast('.') ?: "?"
                sb.append("  ".repeat(depth.coerceAtMost(14)))
                sb.append(cls)
                n.viewIdResourceName?.let { sb.append("  id=").append(it) }
                val t = n.text?.toString()
                if (!t.isNullOrEmpty()) sb.append("  text=\"").append(t.replace("\n", "\\n").take(50)).append("\"")
                val d = n.contentDescription?.toString()
                if (!d.isNullOrEmpty()) sb.append("  desc=\"").append(d.replace("\n", "\\n").take(50)).append("\"")
                sb.append("  editable=").append(n.isEditable)
                sb.append("  clickable=").append(n.isClickable)
                sb.append("  focused=").append(n.isFocused)
                val b = android.graphics.Rect().also { n.getBoundsInScreen(it) }
                sb.append("  [").append(b.left).append(",").append(b.top).append(",")
                    .append(b.right).append(",").append(b.bottom).append("]")
                sb.append("\n")
                for (i in 0 until n.childCount) {
                    val c = n.getChild(i) ?: continue
                    walk(c, depth + 1)
                    c.recycle()
                }
            }
            walk(root, 0)
            root.recycle()
            sb.append("（共 $count 个节点）\n").toString()
        } catch (t: Throwable) {
            "节点树导出失败: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    // ---------- 公共动作：变换 + 发送 ----------


    /** 读取当前输入框 → 文本变换 → 写回（带验证重试）→ 自动发送 */
    fun transformActiveTextAndSend() {
        scope.launch {
            // 优先用事件缓存的 activeNode；失效或为空时主动遍历窗口查找（兼容微信等自定义输入框）
            var node = NekoTypeAccessibilityBridge.activeNode
            if (node == null || !node.refresh()) {
                node = findEditableNode()
                if (node != null) {
                    NekoTypeAccessibilityBridge.setActiveNode(node)
                    NekoLog.info("主动查找输入框成功（兼容自定义输入框）")
                }
            }
            if (node == null) {
                NekoLog.warn("变换失败：未找到输入框节点（请确认光标在输入框内）")
                return@launch
            }
            if (!node.refresh()) {
                NekoLog.warn("变换失败：输入框节点已失效")
                return@launch
            }

            // 读取文本：只读 node.text（不碰剪贴板——反复复制会打扰用户剪贴板）；
            // 读不到（自定义输入框不暴露文本）则跳过本轮
            var current = node.text?.toString().orEmpty()
            android.util.Log.e("NekoA11y", "读取 node.text 长度=${current.length} pkg=${pkgOf(node)}")
            if (current.isEmpty()) {
                // 微信等自定义输入框不暴露文本 → 盲操作：不读原文，只追加后缀类规则产物
                android.util.Log.e("NekoA11y", "读取为空 → 盲操作追加 pkg=${pkgOf(node)}")
                val suffix = buildBlindSuffix()
                if (suffix.isEmpty()) {
                    android.util.Log.e("NekoA11y", "盲操作：无可用后缀规则")
                    android.widget.Toast.makeText(this@NekoTypeAccessibilityService, getString(R.string.bm_blind_no_suffix), android.widget.Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val r = withContext(Dispatchers.IO) { SysPower.shizukuAppendText(suffix) }
                android.util.Log.e("NekoA11y", "盲操作追加 '$suffix' ok=${r.success} ch=${r.channel}")
                if (r.success) {
                    android.widget.Toast.makeText(this@NekoTypeAccessibilityService, getString(R.string.u171), android.widget.Toast.LENGTH_SHORT).show()
                    if (AppPrefs.autoSend) {
                        delay(150)
                        performSend(node)
                    }
                } else {
                    android.widget.Toast.makeText(this@NekoTypeAccessibilityService, getString(R.string.u173), android.widget.Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val result = TextTransformEngine.transform(current)
            val transformed = result.text

            // 写回文本：五级兜底（微信等自研输入框会拒绝大部分无障碍写文本动作）
            // ① ACTION_SET_TEXT + 光标置尾
            // ② 剪贴板粘贴（聚焦 + 全选 + ACTION_PASTE）——微信首选
            // ③ Shizuku 静默注入
            // ④ 再试一次剪贴板粘贴
            // ⑤ 长按输入框 → 点击弹菜单里的「粘贴」
            var writeOk = setTextAndCursor(node, transformed)
            android.util.Log.e("NekoA11y", "写①SET_TEXT ok=$writeOk")
            delay(150)
            if (!writeOk || !verifyWritten(node, transformed)) {
                // 第 2 级：Shizuku 按键注入（不占用剪贴板，优先）
                if (SysPower.privilegedChannelReady()) {
                    val s2 = injectShizukuKeys(node, transformed)
                    android.util.Log.e("NekoA11y", "写②Shizuku注入 ok=$s2")
                    if (s2) {
                        delay(200)
                        writeOk = verifyWritten(node, transformed)
                    }
                }
                // 第 3 级：剪贴板粘贴 —— 仅在特权通道不可用时才用（避免占用/覆盖用户剪贴板 + 系统"已复制"提示）
                if (!writeOk && !SysPower.privilegedChannelReady()) {
                    val p3 = pasteViaClipboard(node, transformed)
                    android.util.Log.e("NekoA11y", "写③剪贴板粘贴 pasted=$p3 verify=${verifyWritten(node, transformed)}")
                    if (p3) {
                        delay(200)
                        writeOk = verifyWritten(node, transformed)
                    }
                    // 第 4 级：剪贴板粘贴重试
                    if (!writeOk) {
                        val p4 = pasteViaClipboard(node, transformed)
                        android.util.Log.e("NekoA11y", "写④剪贴板重试 pasted=$p4 verify=${verifyWritten(node, transformed)}")
                        delay(250)
                        writeOk = verifyWritten(node, transformed)
                    }
                    // 第 5 级：长按粘贴菜单兜底
                    if (!writeOk) {
                        android.util.Log.e("NekoA11y", "写⑤长按粘贴兜底")
                        longPressPaste(node, transformed)
                        delay(400)
                        writeOk = true
                    }
                }
            }
            android.util.Log.e("NekoA11y", "写回结束 writeOk=$writeOk text=${transformed.take(20)}")

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
                } else getString(R.string.u163)
            } catch (_: Throwable) { pkg ?: getString(R.string.u163) }
            NekoLog.ok("在「$appLabel」中已变换发送（${ruleCount} 条规则生效，累计 ${AppPrefs.transformCount} 次）")

            // 微信下不自动发送（用户自己点发送），其他应用保持自动发送
            if (AppPrefs.autoSend && pkg != WECHAT_PKG) {
                // 语音输入优化开启时：延迟 3 秒发送，避免语音补标点后没说完就发出
                delay(if (AppPrefs.voiceInputOptimizeEnabled) 3000L else 120L)
                performSend(node)
            }
        }
    }

    /**
     * 主动遍历当前窗口查找输入框节点（兼容微信等自定义输入框：isEditable 可能为 false 或不触发焦点事件）。
     * 查找优先级：① isEditable=true  ② className 含 EditText  ③ className 含 Input
     *           ④ 有文本的可聚焦节点  ⑤ 页面底部的可聚焦节点（输入框通常在底部）
     */
    private fun findEditableNode(): AccessibilityNodeInfo? {
        val root = getBestRoot() ?: return null
        // 第 1 级：微信输入框按已知控件 ID 精确定位（微信不暴露 isEditable，ID 随版本变化，逐个尝试）
        if (root.packageName?.toString() == WECHAT_PKG) {
            findWeChatEditById(root)?.let {
                NekoLog.info("微信输入框命中（控件 ID 精确定位）")
                return it
            }
        }
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        try {
            collectEditableNodes(root, candidates, depth = 0)
        } catch (_: Throwable) { }
        if (candidates.isEmpty()) return null
        // 第 2 级：isEditable
        // 第 3 级：className 含 EditText / ChatEditText / MMEditText
        // 第 4 级：className 含 Input / Compose / Composer / RichText / Chat
        // 第 5 级：焦点节点 → 有文本的节点 → 页面底部最后一个候选
        return candidates.firstOrNull { it.isEditable }
            ?: candidates.firstOrNull { c ->
                c.className?.let { n -> listOf("EditText", "ChatEditText", "MMEditText").any { n.contains(it, true) } } == true
            }
            ?: candidates.firstOrNull { c ->
                c.className?.let { n -> listOf("Input", "Compose", "Composer", "RichText", "Chat").any { n.contains(it, true) } } == true
            }
            ?: candidates.firstOrNull { it.isFocused }
            ?: candidates.firstOrNull { !it.text.isNullOrEmpty() }
            ?: candidates.lastOrNull()
            ?: if (root.packageName?.toString() == WECHAT_PKG) findLeafTextNode(root) else null
    }

    private fun findLeafTextNode(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 40) return null
        return try {
            if (node.childCount == 0 && !node.text.isNullOrEmpty()) return AccessibilityNodeInfo.obtain(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val hit = findLeafTextNode(child, depth + 1)
                if (hit != null) return hit
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 取当前根节点：优先 rootInActiveWindow；为空时遍历所有窗口兜底
     * （部分 ROM/应用在特定场景取不到"活动窗口"，直接 return null 会导致整体失效）。
     */
    private fun getBestRoot(): AccessibilityNodeInfo? {
        try {
            rootInActiveWindow?.let { return it }
        } catch (_: Throwable) { }
        try {
            for (w in windows) {
                val r = w.root ?: continue
                if (r.packageName != null) return r
            }
        } catch (_: Throwable) { }
        return null
    }

    /** 取节点所属包名 */
    private fun pkgOf(node: AccessibilityNodeInfo): String =
        try { node.packageName?.toString().orEmpty() } catch (_: Throwable) { "" }

    /**
     * 取"可用"的窗口根。rootInActiveWindow 在 EMUI 上可能返回空壳（childCount=0 的死根，
     * 树拉取静默失败），导致按 ID 查找和全树扫描全部扑空。
     * 顺序：活动窗口非空根（可指定包名）→ 各窗口指定包名的非空根 → 任意非空根。
     */
    private fun getBestRoot(pkg: String? = null): AccessibilityNodeInfo? {
        try {
            rootInActiveWindow?.let {
                if (it.childCount > 0 && (pkg == null || pkgOf(it) == pkg)) return it
            }
        } catch (_: Throwable) { }
        try {
            for (w in windows) {
                val r = w.root ?: continue
                if (r.childCount > 0 && (pkg == null || pkgOf(r) == pkg)) return r
            }
        } catch (_: Throwable) { }
        if (pkg != null) {
            // 指定包名找不到时放宽到任意非空根（有些 ROM 把聊天页挂在别的包名窗口里）
            try {
                for (w in windows) {
                    val r = w.root ?: continue
                    if (r.childCount > 0) return r
                }
            } catch (_: Throwable) { }
        }
        return null
    }

    /** 所有窗口概览（找框失败时诊断用） */
    private fun windowsSummary(): String {
        val sb = StringBuilder()
        try {
            rootInActiveWindow?.let { sb.append(" active=[pkg=${pkgOf(it)} cc=${it.childCount}]") }
        } catch (_: Throwable) { }
        try {
            for (w in windows) {
                val r = w.root ?: continue
                sb.append(" w=[pkg=${pkgOf(r)} cc=${r.childCount} layer=${w.layer} focused=${w.isFocused}]")
            }
        } catch (_: Throwable) { }
        return sb.toString().ifEmpty { " (windows 列表为空或不可访问)" }
    }

    /**
     * 微信输入框：按已知控件 ID 查找（多版本 ID 逐个尝试）。
     * 前提：无障碍服务需开启 flagReportViewIds，否则拿不到 viewIdResourceName。
     */
    private fun findWeChatEditById(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in WECHAT_EDIT_IDS) {
            try {
                val list = root.findAccessibilityNodeInfosByViewId(id)
                if (list.isNullOrEmpty()) continue
                list.firstOrNull {
                    it.isEditable ||
                            it.className?.let { c -> c.contains("EditText", true) || c.contains("Chat", true) } == true
                }?.let { return AccessibilityNodeInfo.obtain(it) }
                // ID 命中但属性不符，也取第一个兜底（微信版本差异）
                list.firstOrNull()?.let { return AccessibilityNodeInfo.obtain(it) }
            } catch (_: Throwable) { }
        }
        return null
    }

    /** 微信发送键：按已知控件 ID 查找（多版本兜底） */
    private fun findWeChatSendById(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in WECHAT_SEND_IDS) {
            try {
                val list = root.findAccessibilityNodeInfosByViewId(id)
                if (list.isNullOrEmpty()) continue
                val n = list.firstOrNull { it.isVisibleToUser } ?: list.firstOrNull() ?: continue
                return AccessibilityNodeInfo.obtain(n)
            } catch (_: Throwable) { }
        }
        return null
    }

    /** 递归收集可能的输入框节点，限制深度防止性能问题 */
    private fun collectEditableNodes(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (depth > 30 || out.size > 80) return
        val cls = node.className?.toString() ?: ""
        // 扩大查找范围：微信等自研输入框 isEditable 可能为 false、className 也不含 EditText
        // 只要可聚焦就纳入候选，后续按优先级筛选
        val isEdit = node.isEditable ||
                cls.contains("EditText", true) ||
                cls.contains("Input", true) ||
                cls.contains("Text", true) ||
                node.isFocusable
        if (isEdit) {
            out.add(AccessibilityNodeInfo.obtain(node))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectEditableNodes(child, out, depth + 1)
            child.recycle()
        }
    }

    /** 写回文本并把光标移到末尾（写回后继续输入不打断） */
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

    /**
     * 剪贴板粘贴兜底：复制文本到剪贴板 → 全选输入框 → 执行粘贴。
     * 用于微信等不支持 ACTION_SET_TEXT 的自定义输入框，兼容性最强。
     * 仅在 SET_TEXT 失败时走这里（正常路径不碰剪贴板）。
     */
    private fun pasteViaClipboard(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val oldClip = clipboard.primaryClip
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("nekotype", text))
            // 先聚焦输入框
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            // 全选：关键！微信读不到 node.text（长度 0），必须用 10000 兜底，
            // 否则 SET_SELECTION(0,0) 等于没选中 → 粘贴变成"插入"而不是"替换"
            val len = node.text?.length?.takeIf { it > 0 } ?: 10000
            val sel = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
            // 执行粘贴
            val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            // 还原剪贴板：Android 10+ 后台应用读不到剪贴板内容（primaryClip == null），
            // 这种情况下**绝不能 clear** —— 否则会把用户自己的剪贴板内容抹掉。读得到才还原。
            try {
                if (oldClip != null) clipboard.setPrimaryClip(oldClip)
            } catch (_: Throwable) { }
            pasted
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 剪贴板区间替换：选中 [selStart, selEnd) → PASTE 覆盖为 pasteText。
     * 只触碰选中区间——@提及等富文本 span 只要不在区间内就分毫不动（保住 [@小白 ] 结构）。
     * selStart == selEnd 时为纯插入。
     */
    private fun pasteReplaceRange(
        node: AccessibilityNodeInfo, selStart: Int, selEnd: Int, pasteText: String
    ): Boolean {
        return try {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val oldClip = clipboard.primaryClip
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("nekotype", pasteText))
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val sel = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, selStart)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, selEnd)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            try { if (oldClip != null) clipboard.setPrimaryClip(oldClip) } catch (_: Throwable) { }
            ok
        } catch (_: Throwable) {
            false
        }
    }

    /** 写回校验：读得到文本就比对；读不到（微信）视为成功，交由后续发送验证 */
    private fun verifyWritten(node: AccessibilityNodeInfo, expected: String): Boolean {
        return try {
            if (!node.refresh()) return false
            val cur = node.text?.toString().orEmpty()
            cur.isEmpty() || cur == expected
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 第五级兜底：手势长按输入框 → 在所有窗口（含系统弹窗）里找「粘贴」并点击。
     * 用于微信等既拒绝 SET_TEXT 又拒绝 ACTION_PASTE 的版本。
     */
    private suspend fun longPressPaste(node: AccessibilityNodeInfo, text: String) {
        try {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val oldClip = clipboard.primaryClip
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("nekotype", text))
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            if (bounds.isEmpty) return
            val path = Path().apply { moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat()) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 700)
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
            delay(480)
            val target = findNodeByTextInAllWindows(PASTE_TEXTS)
            if (target != null) {
                if (!target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) tapNode(target)
                NekoLog.info("长按粘贴菜单兜底已执行")
            } else {
                NekoLog.warn("长按粘贴兜底：所有窗口中均未找到「粘贴」菜单项")
            }
            try {
                if (oldClip != null) clipboard.setPrimaryClip(oldClip)
            } catch (_: Throwable) { }
        } catch (_: Throwable) { }
    }

    /** 在所有窗口（含系统弹窗/浮层）里按文本查找节点 */
    private fun findNodeByTextInAllWindows(texts: List<String>): AccessibilityNodeInfo? {
        try {
            getBestRoot()?.let { r -> findNodeByText(r, texts)?.let { return it } }
        } catch (_: Throwable) { }
        try {
            for (w in windows) {
                val r = w.root ?: continue
                findNodeByText(r, texts)?.let { return it }
            }
        } catch (_: Throwable) { }
        return null
    }

    /** 在节点树里按文本/描述精确匹配查找（用于粘贴菜单等系统弹窗） */
    private fun findNodeByText(root: AccessibilityNodeInfo, texts: List<String>, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 25) return null
        try {
            val t = root.text?.toString()?.trim().orEmpty()
            val d = root.contentDescription?.toString()?.trim().orEmpty()
            if (texts.any { t.equals(it, ignoreCase = true) || d.equals(it, ignoreCase = true) }) {
                return AccessibilityNodeInfo.obtain(root)
            }
            for (i in 0 until root.childCount) {
                val child = root.getChild(i) ?: continue
                val hit = findNodeByText(child, texts, depth + 1)
                if (hit != null) return hit
            }
        } catch (_: Throwable) { }
        return null
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

    /**
     * 原生键盘输入触发自动篡改。
     * immediate=true（文本变化事件 realtime）：立即处理不防抖——之前"半秒延迟"就来自防抖等待；
     * immediate=false（微信内容变化事件）：短防抖合并高频事件（微信会刷屏式触发）。
     * 语音输入优化开启时统一用长停顿判定（语音出字断续，立即改写会打断）。
     */
    /** 限频诊断日志：同一 key 3 秒内只打一次（微信事件刷屏，不限制会淹死日志） */
    private val gateLogTimes = HashMap<String, Long>()
    private fun gateLog(key: String, detail: String = "") {
        val now = System.currentTimeMillis()
        val last = gateLogTimes[key] ?: 0L
        if (now - last < 3000L) return
        gateLogTimes[key] = now
        android.util.Log.e("NekoA11y", "拦截: $key${if (detail.isEmpty()) "" else " | $detail"}")
    }

    /** 自动篡改前置门槛统一判定（含拦截原因日志，方便 ADB/Shizuku 抓 logcat 定位） */
    private fun shouldAutoTransform(node: AccessibilityNodeInfo): Boolean {
        if (!AppPrefs.forceKeyboardEnabled) { gateLog("强制篡改键盘未开启"); return false }
        if (!AppPrefs.serviceEnabled) { gateLog("服务开关未开启"); return false }
        if (isBlacklistedApp(node)) { gateLog("名单拦截 pkg=${pkgOf(node)} 模式=${if (AppPrefs.isWhiteListMode()) "白名单" else "黑名单"}"); return false }
        return true
    }

    private fun scheduleAutoTransform(src: AccessibilityNodeInfo, immediate: Boolean = false) {
        try {
            if (src.isPassword) { gateLog("节点被标记为密码框"); return }
            autoTransformJob?.cancel()
            val debounce = when {
                AppPrefs.voiceInputOptimizeEnabled -> AppPrefs.voiceDebounceMs.toLong()
                immediate -> 0L
                else -> AUTO_TRANSFORM_DEBOUNCE_MS
            }
            autoTransformJob = scope.launch {
                if (debounce > 0) delay(debounce)
                // 看门狗：单次处理最长 15 秒，超时强制结束并释放处理锁，
                // 防止底层通道挂死把 autoProcessing 永久锁住（表现=之后所有事件全部跳过）
                withTimeout(15000) { autoTransform(src) }
            }
        } catch (_: Throwable) { }
    }

    private val lockWatchdog = android.os.Handler(android.os.Looper.getMainLooper())
    private val lockReleaseRunnable = Runnable {
        if (autoProcessing) {
            autoProcessing = false
            android.util.Log.e("NekoA11y", "处理锁 20s 未释放，强制解锁（节点 binder 调用疑似挂死）")
        }
    }

    /** 处理进行中到达的新事件：记下最新节点，本次处理完成后补跑一次（旧实现直接丢弃 → 快速打字约一半改写丢失） */
    @Volatile private var pendingRerun: AccessibilityNodeInfo? = null

    private suspend fun autoTransform(src: AccessibilityNodeInfo, fromSendFallback: Boolean = false) {
        // 防重入（processing 标志）
        if (autoProcessing) {
            pendingRerun = src
            return
        }
        autoProcessing = true
        lockWatchdog.postDelayed(lockReleaseRunnable, 20000)
        try {
            autoTransformInner(src, fromSendFallback)
            // 补跑积压的最新事件（文本级去重 + 回显窗口会挡住重复写回，不会叠加）
            val p = pendingRerun
            pendingRerun = null
            if (p != null) autoTransformInner(p, false)
        } finally {
            lockWatchdog.removeCallbacks(lockReleaseRunnable)
            autoProcessing = false
        }
    }

    private suspend fun autoTransformInner(src: AccessibilityNodeInfo, fromSendFallback: Boolean = false) {
        try {
            // 篡改模式门槛：强制篡改键盘 + 首页「启动服务」都必须开（用户要求：
            // 没开服务不许改字）。其余逻辑（窗口切换不打断、处理时重找节点、微信不自动发送）
            if (!AppPrefs.forceKeyboardEnabled || !AppPrefs.serviceEnabled) {
                gateLog("inner 门槛：forceKeyboard=${AppPrefs.forceKeyboardEnabled} service=${AppPrefs.serviceEnabled}")
                return
            }
            if (isBlacklistedApp(src)) {
                gateLog("inner 名单拦截 pkg=${pkgOf(src)}")
                return
            }
            val pkg = pkgOf(src)
            val isWeChat = pkg == WECHAT_PKG
            // 先用事件节点（立即处理时它刚被找到、是新鲜的，省去重找开销）；
            // 微信跳过 refresh：对加固窗口的节点做 binder 调用可能挂死，事件里的节点本身就是新鲜的；
            // 失效时才重新查找兜底（微信视图可能已被回收）
            android.util.Log.e("NekoA11y", "自动篡改开始 pkg=$pkg fromSend=$fromSendFallback")
            var node: AccessibilityNodeInfo = if (isWeChat) {
                src
            } else if (src.refresh()) {
                src
            } else {
                return
            }
            // 微信输入框 isEditable=false 且常不报焦点（自研控件）→ 门槛只看"是不是微信"
            if (!node.isEditable && !isWeChat) {
                android.util.Log.e("NekoA11y", "自动篡改跳过: pkg=$pkg editable=${node.isEditable}")
                return
            }
            // 内存防护：autoStates 按输入框 viewId 累积，超过上限清掉最旧的一半（防长期运行泄漏）
            if (autoStates.size > 200) {
                val stale = autoStates.keys.take(autoStates.size / 2)
                stale.forEach { autoStates.remove(it) }
            }
            var trim = node.text?.toString()?.trim().orEmpty()
            // 不再用剪贴板回读：反复「全选→复制」会不停写剪贴板、
            // 在微信里弹"已复制"，就是"老是复制东西"的元凶。读不到文本就跳过本轮。
            android.util.Log.e("NekoA11y", "自动篡改读取: pkg=$pkg len=${trim.length} text=${trim.take(20)}")
            if (trim.isEmpty()) {
                // 微信等自定义输入框读不到文本 → 盲操作：只追加后缀类规则产物
                // @提及保护期内同样不改（读不到文本时沿用最近一次判定，避免打断选人弹窗）
                // NyaNote 同款兜底（实验）：节点读空 ≠ 没内容——
                // ① 事件文本兜底：微信事件自带 EditText 当前内容，直接当原文
                // ② 窗口根重找输入框再读（NyaNote 实测可读全文并 SET_TEXT 写回成功）
                // ③ 全部落空才盲注入
                if (isWeChat && AppPrefs.wechatExperimentalEnabled) {
                    if (lastWeChatEventText.isNotEmpty()) {
                        trim = lastWeChatEventText.trim()
                        android.util.Log.e("NekoA11y", "节点读空，改用事件文本: len=${trim.length} text=${trim.take(20)}")
                    }
                    if (trim.isEmpty()) {
                        val root = getBestRoot(WECHAT_PKG)
                        if (root == null) {
                            android.util.Log.e("NekoA11y", "重找框: 窗口根不可用 |${windowsSummary()}")
                        } else {
                            val alt = findWeChatEditById(root) ?: findEditableNode()
                            if (alt == null) {
                                android.util.Log.e("NekoA11y", "重找框: 找框失败 root=${pkgOf(root)} cc=${root.childCount}")
                            } else {
                                val t2 = alt.text?.toString()?.trim().orEmpty()
                                android.util.Log.e("NekoA11y", "重找框读取: len=${t2.length} text=${t2.take(20)}")
                                if (t2.isNotEmpty()) {
                                    trim = t2
                                    node = alt
                                }
                            }
                        }
                    }
                }
                if (trim.isEmpty()) {
                    blindAppendSuffix(node, fromSendFallback)
                    return
                }
            }
            // 标点触发模式：文本以标点结尾才篡改（打完一句才改）；
            // 发送兜底不受此限制（用户点发送必然处理）
            if (AppPrefs.punctTriggerEnabled && !fromSendFallback) {
                val puncts = AppPrefs.punctTriggerChars.toSet()
                if (trim.lastOrNull() !in puncts) return
            }
            val key = node.viewIdResourceName ?: "node_${node.hashCode()}"
            val st = autoStates[key] ?: AutoState("", "", 0L)
            val now = System.currentTimeMillis()

            // 0. 文本级自我写回去重（比时间窗更可靠，微信下尤其必要）：
            //    当前文本就是我们上次写回的内容 → 自己触发的回显，直接忽略，避免重复叠加
            if (st.lastSet.isNotEmpty() && trim == st.lastSet) {
                st.lastWriteTime = 0L
                return
            }

            // 1. 回显跳过：我们写回后 600ms 内且文本与 lastSet 一致 → 自己的回显，忽略（防死循环）
            if (st.lastWriteTime > 0 && now - st.lastWriteTime < ECHO_WINDOW_MS && trim == st.lastSet) {
                st.lastWriteTime = 0L // 消费掉本次回显
                return
            }

            // 1.5 删除优化（deleteOptimize）：用户正在删字 → 暂不改写，
            //     否则会出现"后缀删不掉/越删越多"。停手 500ms 后自动恢复并重新改写。
            //     判定必须精确：只有「文本变短 且 上一版文本以新版开头」才是真的退格删字。
            //     早期用「变短 或 等长变化」判定，会把替换类规则自己写回的结果（如 什么→什）
            //     误判成用户正在删字 → 永远停在暂停分支 → 规则整体不生效。
            //     微信下发文字频繁、回读不一致，删除优化同样不启用。
            val deleteOptOn = AppPrefs.deleteOptimizeEnabled && !isWeChat
            if (deleteOptOn && !fromSendFallback) {
                val prev = st.lastSeenText
                val deleting = prev.isNotEmpty() && trim.length < prev.length && prev.startsWith(trim)
                st.lastSeenText = trim
                if (deleting) {
                    android.util.Log.e("NekoA11y", "删除优化：检测到删除（$prev → $trim），暂不改写")
                    deleteRecoveryJob?.cancel()
                    deleteRecoveryJob = scope.launch {
                        delay(500)
                        android.util.Log.e("NekoA11y", "删除优化：停手 500ms，恢复改写")
                        autoTransform(src)
                    }
                    return
                }
                if (deleteRecoveryJob?.isActive == true) {
                    android.util.Log.e("NekoA11y", "删除优化：删除过程中，暂不改写")
                    return
                }
            } else if (deleteOptOn && fromSendFallback) {
                // 用户点发送时无视删除模式（必然要处理）
                deleteRecoveryJob?.cancel()
                st.lastSeenText = trim
            }

            // 2. 增量：文本以 lastSet 开头（用户在末尾继续输入）→ 只把新增部分并入原文
            if (st.lastSet.isNotEmpty() && trim.startsWith(st.lastSet)) {
                st.userOriginal += trim.substring(st.lastSet.length)
            } else {
                // 3. 重置：用户删除/中间插入 → 从当前文本剥离上次附加的前缀/后缀，视为新原文
                var base = trim
                // 后缀由 appendSuffixSmart 插到了句末标点之前，需用配对的 stripSmartSuffix 剥离，
                // 否则 `endsWith(suffix)` 会失配（结尾是标点）→ 残留后缀 → 下次重复叠加
                if (st.addedSuffix.isNotEmpty()) {
                    base = TextTransformEngine.stripSmartSuffix(base, st.addedSuffix)
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
            st.lastSeenText = r.text
            autoStates[key] = st
            // 六级兜底写回（篡改键盘模式）：Shizuku 静默注入默认启用、无需用户开关，
            // 且走 newProcess 通道优先（免绑定 UserService，不白等 8 秒），显著提高篡改成功率。
            val writeOk = writeBackSixLevel(node, r.text)
            android.util.Log.e("NekoA11y", "自动篡改写回六级兜底 ok=$writeOk text=${r.text.take(20)}")
            AppPrefs.transformCount = AppPrefs.transformCount + 1
            AppPrefs.incrementToday()
            val appLabel = try {
                val pkg = node.packageName?.toString()
                if (pkg != null) packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                else getString(R.string.u163)
            } catch (_: Throwable) { getString(R.string.u163) }
            NekoLog.ok("强制篡改键盘：在「$appLabel」自动变换为「${r.text}」")

            // 微信下不自动发送（改完由用户自己点发送，避免和手点发送叠加/抢发）
            if (AppPrefs.autoSend && !isWeChat) {
                delay(if (AppPrefs.voiceInputOptimizeEnabled) 3000L else 120L)
                performSend(node)
            }
        } catch (_: Throwable) { }
    }

    /** 事件源是否为发送按钮（点击发送兜底用） */
    private fun isSendButtonNode(n: AccessibilityNodeInfo): Boolean {
        return try {
            val rawId = n.viewIdResourceName.orEmpty()
            // 微信发送键精确 ID（点击发送兜底用）
            if (rawId.isNotEmpty() && WECHAT_SEND_IDS.any { it.equals(rawId, true) }) return true
            // QQ / TIM 发送键精确 ID
            if (rawId.isNotEmpty() && QQ_SEND_IDS.any { it.equals(rawId, true) }) return true
            val id = rawId.lowercase()
            val text = n.text?.toString().orEmpty()
            val desc = n.contentDescription?.toString().orEmpty()
            id.contains("send") || id.contains("发送") || id.contains("btn_send") ||
                    text.contains("发送") || text.equals("send", ignoreCase = true) ||
                    desc.contains("发送") || desc.equals("send", ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }

    /** 当前节点所在应用是否被名单拦截（黑名单模式：名单内不篡改；白名单模式：名单外不篡改） */
    private fun isBlacklistedApp(n: AccessibilityNodeInfo): Boolean {
        if (!AppPrefs.blacklistEnabled) return false
        return try {
            n.packageName?.toString()?.let { AppPrefs.isBlockedApp(it) } ?: false
        } catch (_: Throwable) {
            false
        }
    }

    // ---------- 静默修改（Shizuku 注入） ----------

    /**
     * 六级兜底写回（篡改键盘模式专用）：逐级尝试、每级都校验，成功率远高于单一通道。
     *
     * ① 无障碍 ACTION_SET_TEXT + 光标置尾（最快，绝大多数应用够用）
     * ② Shizuku 直注 `input text`（全选 + 注入；ASCII；**newProcess 通道优先**，无绑定等待）
     * ③ Shizuku 写系统剪贴板 + 全选 + 无障碍 PASTE（中文/Unicode；同样 newProcess 优先）
     * ④ 应用内剪贴板粘贴（聚焦 + 全选 + PASTE）
     * ⑤ 应用内剪贴板粘贴重试（应对焦点抖动 / 时序问题）
     * ⑥ 长按输入框 → 点击弹菜单「粘贴」（微信等同时拒绝以上所有通道时）
     *
     * 说明：本路径的 Shizuku 注入**不需要用户开启任何开关**（只要 Shizuku 可用且已授权），
     * 目的是把"改不进去"的概率压到最低；Shizuku 不可用时自动跳过 ②③，不影响 ①④⑤⑥。
     */
    private suspend fun writeBackSixLevel(node: AccessibilityNodeInfo, text: String): Boolean {
        val t0 = android.os.SystemClock.elapsedRealtime()
        fun stamp(stage: String) = android.util.Log.i("NekoWrite",
            "$stage +" + (android.os.SystemClock.elapsedRealtime() - t0) + "ms text=" + text.take(12))
        val isWeChat = pkgOf(node) == WECHAT_PKG
        // 微信实验适配（NyaNote 同款语义）：
        // ① SET_TEXT 返回 true 即成功，不做读回校验（微信自研输入框 refresh/读回经常失败，
        //    旧逻辑会把它误判成失败而掉进 Shizuku 注入（newProcess 起 sh 进程 ≈2s）——
        //    这就是"改个字等两秒"的元凶）；
        // ①.5 SET_TEXT 被拒时立即走剪贴板粘贴（免起进程，比 Shizuku 快）；
        // ②⑥ Shizuku 注入仍在剪贴板之后兜底。开关关闭时全部回退旧校验逻辑。
        val wechatExp = isWeChat && AppPrefs.wechatExperimentalEnabled
        var ok = setTextAndCursor(node, text)
        if (ok) {
            if (wechatExp) {
                stamp("写回①SET_TEXT 成功（微信信任返回值，不校验）")
                return true
            }
            delay(150)
            if (verifyWritten(node, text)) {
                stamp("写回①SET_TEXT 成功")
                return true
            }
        }
        if (wechatExp && pasteViaClipboard(node, text)) {
            stamp("写回①.5 剪贴板粘贴 成功（实验）")
            return true
        }
        if (SysPower.privilegedChannelReady()) {
            if (injectShizukuKeys(node, text)) {
                delay(200)
                if (wechatExp || verifyWritten(node, text)) {
                    stamp("写回②Shizuku 按键注入 成功")
                    return true
                }
            }
        }
        // 剪贴板路径：仅在特权通道不可用时使用。
        // 有 Shizuku 时按键注入已能完成，不必再占用/覆盖用户剪贴板（Android 10+ 还读不到旧内容，
        // 会连"还原"都做不到 → 用户剪贴板被消息内容顶掉 + 系统弹"已复制"）。
        if (!SysPower.privilegedChannelReady()) {
            if (pasteViaClipboard(node, text)) {
                delay(200)
                if (verifyWritten(node, text)) {
                    stamp("写回③剪贴板粘贴 成功")
                    return true
                }
            }
            if (pasteViaClipboard(node, text)) {
                delay(250)
                if (verifyWritten(node, text)) {
                    stamp("写回④剪贴板重试 成功")
                    return true
                }
            }
            stamp("写回⑤长按粘贴兜底")
            longPressPaste(node, text)
            delay(400)
            if (verifyWritten(node, text)) {
                stamp("写回⑤长按粘贴 成功")
                return true
            }
        }
        if (SysPower.privilegedChannelReady()) {
            delay(300)
            if (injectShizukuKeys(node, text)) {
                delay(200)
                if (wechatExp || verifyWritten(node, text)) {
                    stamp("写回⑥Shizuku 注入重试 成功")
                    return true
                }
            }
        }
        stamp("写回六级全部失败，交给发送前校验")
        return true
    }

    /** Shizuku 按键注入（全选 + 注入任意 Unicode），newProcess 通道优先 */
    private suspend fun injectShizukuKeys(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            if (!SysPower.privilegedChannelReady()) return false
            val r = withContext(Dispatchers.IO) { SysPower.shizukuInjectText(text) }
            if (!r.success) return false
            delay(250)
            node.refresh() && node.text?.toString() == text
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 静默注入：当无障碍写回被目标应用拒绝时，通过 Shizuku 按键注入执行。
     * 注：受「静默修改」总开关控制（用户可见功能）；
     * 篡改键盘模式的六级兜底 [writeBackSixLevel] 不经过本开关，默认就会尝试注入。
     * @return 注入后验证通过返回 true
     */
    private suspend fun silentInject(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            if (!AppPrefs.silentModifyEnabled) return false
            injectShizukuKeys(node, text)
        } catch (_: Throwable) {
            false
        }
    }

    // ---------- 盲操作（微信等读不到输入框文本时的后缀追加） ----------

    /** 盲操作冷却：读不到文本无法用增量算法去重，靠时间冷却防重复追加 */
    private val blindCooldownMs = 2500L

    /**
     * 盲操作后缀：只收集**追加类**规则的产物（固定后缀 / 随机后缀 / 随机后缀不固定 / 随机颜文字），
     * 这些规则追加时不需要知道原文；前缀、替换类规则在盲操作下跳过。
     * @return 拼接好的后缀文本；没有可用的追加规则时返回空串
     */
    private fun buildBlindSuffix(): String {
        val sb = StringBuilder()
        val rules = AppPrefs.rules().filter { it.enabled }
        rules.filter { it.type == AppPrefs.RuleType.SUFFIX }
            .sortedByDescending { it.priority }
            .forEach { if (it.value.isNotEmpty()) sb.append(it.value) }
        rules.filter { it.type == AppPrefs.RuleType.RANDOM_SUFFIX }
            .sortedByDescending { it.priority }
            .forEach {
                if (Random.nextInt(100) < it.chance) {
                    TextTransformEngine.parsePool(it.value).randomOrNull()?.let { p -> sb.append(p) }
                }
            }
        val once = rules.filter {
            it.type == AppPrefs.RuleType.RANDOM_SUFFIX_ONCE &&
                    Random.nextInt(100) < it.chance &&
                    TextTransformEngine.parsePool(it.value).isNotEmpty()
        }
        if (once.isNotEmpty()) {
            sb.append(TextTransformEngine.parsePool(once.random().value).random())
        }
        if (AppPrefs.emoticonEnabled) {
            AppPrefs.builtinEmoticons().randomOrNull()?.let {
                if (sb.isNotEmpty()) sb.append(" ")
                sb.append(it)
            }
        }
        return sb.toString()
    }

    /**
     * 盲操作追加：输入框读不到文本（微信等）时，把光标移到末尾并注入后缀（Shizuku 按键注入）。
     * 发送兜底（用户点发送）不受冷却限制；自动路径按 2.5 秒冷却防重复。
     */
    private suspend fun blindAppendSuffix(node: AccessibilityNodeInfo, fromSendFallback: Boolean) {
        val key = node.viewIdResourceName ?: "node_${node.hashCode()}"
        val st = autoStates[key] ?: AutoState("", "", 0L)
        val now = System.currentTimeMillis()
        if (!fromSendFallback && now - st.lastBlindTime < blindCooldownMs) {
            android.util.Log.e("NekoA11y", "盲操作：冷却中，跳过")
            return
        }
        val suffix = buildBlindSuffix()
        if (suffix.isEmpty()) {
            android.util.Log.e("NekoA11y", "盲操作：无可用后缀规则，跳过")
            return
        }
        android.util.Log.e("NekoA11y", "盲操作追加: '$suffix'")
        val r = withContext(Dispatchers.IO) { SysPower.shizukuAppendText(suffix) }
        android.util.Log.e("NekoA11y", "盲操作结果 ok=${r.success} ch=${r.channel} out=${r.output}")
        if (r.success) {
            st.lastBlindTime = now
            autoStates[key] = st
            AppPrefs.transformCount = AppPrefs.transformCount + 1
            AppPrefs.incrementToday()
            NekoLog.ok("盲操作追加成功：$suffix")
            if (AppPrefs.autoSend && pkgOf(node) != WECHAT_PKG) {
                delay(150)
                performSend(node)
            }
        } else {
            android.util.Log.e("NekoA11y", "盲操作追加失败: ch=${r.channel} out=${r.output}")
        }
    }

    /** 五级发送兜底：微信发送键 ID 点击 → 微信发送键手势 → 通用打分点击 → 通用手势 → IME 发送动作 */
    private fun performSend(editNode: AccessibilityNodeInfo) {
        val root = getBestRoot()
        val inputBounds = Rect().also { editNode.getBoundsInScreen(it) }
        val isWeChat = pkgOf(editNode) == WECHAT_PKG

        // 第 1 级：微信发送键按控件 ID 精确定位（最可靠）
        val wxSend = if (root != null && isWeChat) findWeChatSendById(root) else null
        android.util.Log.e("NekoA11y", "发送: isWeChat=$isWeChat wxSend=${wxSend?.viewIdResourceName}")
        if (wxSend != null && wxSend.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return
        }
        // 第 2 级：微信发送键手势点击（微信常忽略 ACTION_CLICK）
        if (wxSend != null) {
            tapNode(wxSend)
            return
        }
        // 第 3 级：通用打分制发送键无障碍点击
        val sendNode = if (root != null) findSendNode(root, inputBounds) else null
        if (sendNode != null && sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return
        }
        // 第 4 级：通用发送键手势点击
        if (sendNode != null) {
            tapNode(sendNode)
            return
        }
        // 第 5 级：兜底 IME 发送动作
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
            // 微信发送键精确 ID：最高优先级（chatting_send_btn / anv / emoji_send_btn）
            val rawId = try { n.viewIdResourceName.orEmpty() } catch (_: Throwable) { "" }
            if (rawId.isNotEmpty() && WECHAT_SEND_IDS.any { it.equals(rawId, true) }) s += 120
            if (rawId.isNotEmpty() && QQ_SEND_IDS.any { it.equals(rawId, true) }) s += 120
            // 微信发送键文本（发送 / Send）
            if (WECHAT_SEND_TEXTS.any { text.equals(it, true) || desc.equals(it, true) }) s += 40
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
