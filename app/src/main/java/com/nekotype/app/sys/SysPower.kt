package com.nekotype.app.sys

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.provider.Settings
import com.nekotype.app.NekoTypeApp
import com.nekotype.app.R
import com.nekotype.app.admin.NekoTypeDeviceAdminReceiver
import com.nekotype.app.prefs.AppPrefs
import com.nekotype.app.shizuku.FixedCommands
import com.nekotype.app.shizuku.NekoShellService
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 系统级能力封装：依次尝试 Root → Shizuku 执行 shell 命令，
 * 以及电池优化白名单、设备管理员状态等查询/跳转。
 *
 * 两种特权通道任选其一即可获得"关闭电池优化/系统命令"能力：
 * - Root：Magisk / KernelSU / APatch 提供的 su
 * - Shizuku：adb 授权（无线调试/ADB）后，通过 UserService 以 shell 权限执行命令
 */
object SysPower {

    data class ExecResult(val success: Boolean, val output: String, val channel: String)

    /** 一键授权中单步结果 */
    data class PermissionStep(
        val name: String,
        val success: Boolean,
        val output: String,
        val channel: String
    )

    // ---------- Root ----------

    fun isRootAvailable(): Boolean = try {
        val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(5, TimeUnit.SECONDS)
        out.contains("uid=0") || p.exitValue() == 0
    } catch (_: Throwable) {
        false
    }

    // ---------- Shizuku ----------

    /** UserService 绑定超时：超过这个时间还没连上就解锁重试，避免一次失败永久卡死 */
    private const val BIND_TIMEOUT_MS = 8000L

    /** 单次动作回包超时 */
    private const val ACTION_TIMEOUT_MS = 10000L

    /** 最后一次 Shizuku UserService 绑定的详细错误（供诊断显示） */
    @Volatile var lastShizukuError: String? = null

    fun isShizukuAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun isShizukuPermissionGranted(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun requestShizukuPermission(requestCode: Int) {
        try {
            Shizuku.requestPermission(requestCode)
        } catch (_: Throwable) { /* Shizuku 未安装/未运行 */ }
    }

    // ---------- Shizuku 长连接管理（v2.6.5 重构：一次绑定复用，不再每动作 bind/unbind） ----------

    /** 已连接的 UserService Messenger（null=未连接） */
    @Volatile private var shellMessenger: Messenger? = null

    /** UserService 是否处于已连接状态（onServiceConnected 已回调且 binder 有效） */
    @Volatile private var shellConnected = false

    /** 正在绑定中（避免重复发起 bindUserService） */
    @Volatile private var shellBinding = false

    /** 本次绑定发起的时刻：超过 [BIND_TIMEOUT_MS] 仍未连上就自动解锁重试 */
    @Volatile private var bindStartedAt = 0L

    /** 通道追踪（内存环形缓冲）：EMUI 会过滤应用日志，排错只能靠它 */
    private val traceLog = ArrayDeque<String>()
    private val traceFmt = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())

    private fun trace(msg: String) {
        try {
            traceLog.addLast("${traceFmt.format(java.util.Date())}  $msg")
            while (traceLog.size > 200) traceLog.removeFirst()
        } catch (_: Throwable) { }
    }

    /** 应用版本号：作为 UserService 的 version 传给 Shizuku（应用更新后自动重启 UserService，避免复用旧进程） */
    private val shellVersion: Int by lazy {
        try {
            NekoTypeApp.instance.packageManager
                .getPackageInfo(NekoTypeApp.instance.packageName, 0).versionCode
        } catch (_: Throwable) { 1 }
    }

    /**
     * 复用的 UserServiceArgs。
     * 关键点：必须设置 version —— 否则应用每次更新后，Shizuku 会继续复用旧版本启动的
     * UserService 进程（旧 dex/旧注册），表现为"通道一直连不上、怎么重试都没用"。
     */
    private val shellArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(NekoTypeApp.instance, NekoShellService::class.java))
            .processNameSuffix(":shizuku")
            .version(shellVersion)
    }

    /** 长连接的 ServiceConnection：onServiceDisconnected 自动重连 */
    private val shellConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            shellBinding = false
            if (binder != null) {
                shellMessenger = Messenger(binder)
                shellConnected = true
                lastShizukuError = null
                trace("onServiceConnected ✓ 连接成功（${System.currentTimeMillis() - bindStartedAt}ms）")
            } else {
                shellConnected = false
                trace("onServiceConnected 但 binder 为 null")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellMessenger = null
            shellConnected = false
            shellBinding = false
            trace("onServiceDisconnected，立即重连")
            bindShellService()
        }

        override fun onBindingDied(name: ComponentName?) {
            // 宿主（Shizuku 端 UserService）死亡：清状态重绑，避免通道假死
            shellMessenger = null
            shellConnected = false
            shellBinding = false
            trace("onBindingDied，清理并重绑")
            bindShellService()
        }

        override fun onNullBinding(name: ComponentName?) {
            // onBind 返回 null（服务端异常）：不清理 messenger 会永远卡在"绑定中"
            shellMessenger = null
            shellConnected = false
            shellBinding = false
            lastShizukuError = "onNullBinding：UserService 启动异常（onBind 返回 null），请重启 Shizuku 后重试"
            trace("onNullBinding：UserService 启动异常")
        }
    }

    /** Shizuku Binder 上下线监听 */
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        trace("Shizuku binder 送达，尝试绑定 UserService")
        bindShellService()
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        trace("Shizuku binder 断开，清理 UserService 连接")
        shellMessenger = null
        shellConnected = false
        shellBinding = false
    }

    /**
     * 初始化 Shizuku 通道（NekoTypeApp.onCreate 调用）：
     * 用 **sticky** 监听 —— ContentProvider 先于 Application 初始化，binder 很可能在我们注册监听前
     * 就已送达；非 sticky 监听在这种情况下永远不会回调，通道就再也不绑定了（旧实现的坑）。
     */
    fun initShizukuChannel() {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            trace("已注册 Shizuku 上下线监听（sticky）")
        } catch (t: Throwable) {
            trace("注册监听失败: ${t.javaClass.simpleName}: ${t.message}")
        }
        bindShellService()
    }

    /**
     * 绑定 UserService（幂等）。force=true 时先清状态强制重绑。
     * 绑定失败会自动在 [BIND_TIMEOUT_MS] 后解锁，不会永久阻塞后续重试。
     */
    fun bindShellService(force: Boolean = false) {
        if (force) {
            shellMessenger = null
            shellConnected = false
            shellBinding = false
        }
        if (shellBinding && System.currentTimeMillis() - bindStartedAt > BIND_TIMEOUT_MS) {
            trace("上次绑定超时（>${BIND_TIMEOUT_MS}ms 未连上）→ 解锁重试")
            shellBinding = false
        }
        if (shellConnected) return
        if (shellBinding) return
        if (!isShizukuAvailable()) {
            trace("跳过绑定：Shizuku 服务未运行（pingBinder=false）")
            return
        }
        // Shizuku 11+：UserService 绑定需要 API 权限；未授权时服务端会直接拒绝，
        // onServiceConnected 永不回调 → 表现为"通道一直不可用"。这里明确拦截并给出可读原因，
        // 而不是让每次绑定都静默超时。（Sui/pre-v11 无需权限，跳过此检查）
        if (shizukuServerVersion() >= 11 && !isShizukuPermissionGranted()) {
            lastShizukuError = "Shizuku API 权限未授予：请点击 App 内「Shizuku 授权」或「一键授权」，在弹窗中允许；" +
                    "或在 Shizuku 应用 → 应用管理 → 开启 NekoType"
            trace("跳过绑定：API 权限未授予（Shizuku v${shizukuServerVersion()}）")
            return
        }
        try {
            shellBinding = true
            bindStartedAt = System.currentTimeMillis()
            trace("发起 bindUserService（version=$shellVersion，Shizuku=${shizukuVersionText()}）")
            Shizuku.bindUserService(shellArgs, shellConnection)
        } catch (t: Throwable) {
            shellBinding = false
            trace("bindUserService 抛异常: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** 正常解绑长连接（unbindAll=false，只清当前连接，不杀 UserService 进程） */
    fun unbindShellService() {
        try {
            Shizuku.unbindUserService(shellArgs, shellConnection, false)
        } catch (_: Throwable) { }
        shellMessenger = null
        shellConnected = false
        shellBinding = false
        trace("已解绑长连接（unbindAll=false）")
    }

    /** 强制重置连接（unbindAll=true，杀掉整个 UserService 进程后重新绑定；仅在连接卡死/Shizuku 异常时用） */
    fun resetShellService() {
        try {
            Shizuku.unbindUserService(shellArgs, shellConnection, true)
        } catch (_: Throwable) { }
        shellMessenger = null
        shellConnected = false
        shellBinding = false
        trace("已强制重置（unbindAll=true，杀掉 UserService 进程）")
        bindShellService()
    }

    /** Shizuku 本体版本描述（排错用） */
    private fun shizukuVersionText(): String = try {
        "v${Shizuku.getVersion()}" + if (isShizukuPermissionGranted()) " 权限已授予" else " 权限未授予"
    } catch (_: Throwable) { "未知" }

    /** Shizuku 服务端版本号（获取失败返回 -1，视为无需权限检查的老版本/Sui） */
    private fun shizukuServerVersion(): Int = try {
        Shizuku.getVersion()
    } catch (_: Throwable) { -1 }

    /** 开发者模式：通道追踪 + 当前状态摘要（不依赖 logcat，EMUI 过滤日志也能看） */
    fun devChannelTrace(): String {
        val sb = StringBuilder()
        sb.appendLine("---- 当前状态 ----")
        sb.appendLine("Shizuku 存活(pingBinder): ${isShizukuAvailable()}")
        sb.appendLine("Shizuku 版本/权限: ${shizukuVersionText()}")
        sb.appendLine("通道可用: ${privilegedChannelReady()}    最近使用的通道: $lastChannelUsed")
        sb.appendLine("UserService 已连接: $shellConnected （false 不代表不可用，会自动降级 newProcess）")
        sb.appendLine("Messenger 有效: ${shellMessenger != null}")
        sb.appendLine("绑定中(未超时): ${shellBinding && System.currentTimeMillis() - bindStartedAt <= BIND_TIMEOUT_MS}")
        sb.appendLine("本次 version 参数: $shellVersion")
        sb.appendLine("最近错误: ${lastShizukuError ?: "无"}")
        sb.appendLine()
        sb.appendLine("---- 通道追踪（最近 ${traceLog.size} 条）----")
        sb.appendLine(if (traceLog.isEmpty()) "（暂无记录）" else traceLog.joinToString("\n"))
        return sb.toString()
    }

    /**
     * 通过 Shizuku UserService 执行【固定动作】（Messenger 方式，安全审计后重构）：
     * 不再传输任意 shell 命令，只发送固定动作类型 + 受限参数，
     * 由 NekoShellService 内部执行写死的命令。
     * 返回 null 表示绑定失败/超时/未授权。
     */
    private fun runShizukuAction(action: Int, data: Bundle? = null): String? {
        // 确保长连接已建立（未连接则触发绑定并等待最多8秒：UserService 首次冷启动
        // 需 fork app_process + 加载 APK（含 dexopt），慢设备上 5 秒不够，会被误判为通道不可用）
        if (!shellConnected) {
            trace("动作 $action：未连接 → 触发绑定并等待")
            bindShellService()
            val waitStart = System.currentTimeMillis()
            while (!shellConnected && System.currentTimeMillis() - waitStart < 8000) {
                Thread.sleep(100)
            }
            if (!shellConnected) {
                val reason = lastShizukuError
                    ?: "UserService 连接失败：8秒内 onServiceConnected 未触发（Shizuku 拒绝绑定或 UserService 启动失败）"
                lastShizukuError = reason
                trace("动作 $action：等待 8s 仍未连接 → 失败（原因：$reason）")
                // 【关键修复】绑定失败必须清掉 shellBinding：否则之后所有 bindShellService() 都会在第一行
                // 直接 return，通道一直假死到 APP 重启（Shizuku 晚启动/拒绝绑定一次就永久失效）。
                shellBinding = false
                try { Shizuku.unbindUserService(shellArgs, shellConnection, false) } catch (_: Throwable) { }
                return null
            }
            trace("动作 $action：连接就绪，继续发送")
        }

        val messenger = shellMessenger ?: run {
            lastShizukuError = "Messenger 为 null（连接状态异常）"
            shellConnected = false
            return null
        }

        val latch = CountDownLatch(1)
        var result: String? = null
        var ok = false

        val clientHandler = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.what == NekoShellService.MSG_RESULT) {
                    ok = msg.data.getBoolean(NekoShellService.KEY_OK, false)
                    result = msg.data.getString(NekoShellService.KEY_OUT)
                    latch.countDown()
                }
            }
        }
        val clientMessenger = Messenger(clientHandler)

        try {
            lastShizukuError = null
            val msg = Message.obtain(null, action)
            msg.data = data ?: Bundle()
            msg.replyTo = clientMessenger
            messenger.send(msg)

            // 10秒超时：长连接已建立，仅等服务端执行+回包
            if (!latch.await(10, TimeUnit.SECONDS)) {
                lastShizukuError = "服务端10秒未回包（连接可能已死，触发重置）"
                trace("动作 $action：回包超时 10s → 清理连接")
                shellMessenger = null
                shellConnected = false
                shellBinding = false
                return null
            }

            if (!ok) {
                lastShizukuError = "服务端返回失败: ${result ?: ""}"
                trace("动作 $action：服务端返回失败（${result ?: ""}）")
            } else {
                trace("动作 $action：成功")
            }
            return if (ok) result ?: "" else null
        } catch (t: Throwable) {
            lastShizukuError = "发送消息异常: ${t.javaClass.simpleName}: ${t.message}"
            trace("动作 $action：发送异常 ${t.javaClass.simpleName}: ${t.message}")
            // 发送失败通常意味着连接已断，清理状态待下次自动重连
            shellMessenger = null
            shellConnected = false
            shellBinding = false
            return null
        }
    }

    /**
     * Shizuku 通道详细诊断：依次检查 pingBinder → API权限 → UserService绑定 → 服务端响应，
     * 返回每一步的结果字符串，用于定位"通道不可用"的具体原因。
     */
    fun diagnoseShizuku(): String {
        val sb = StringBuilder()
        // 1. pingBinder
        val ping = try { Shizuku.pingBinder(); true } catch (t: Throwable) { false }
        sb.appendLine("1. Shizuku服务存活: ${if (ping) "✓" else "✗ (Shizuku未运行/未连接)"}")
        if (!ping) return sb.toString()
        // 2. 长连接状态
        sb.appendLine("2. UserService长连接: ${if (shellConnected && shellMessenger != null) "✓ 已连接" else "○ 未连接（将自动尝试绑定）"}")
        // 3. 绑定探测（用免电白名单动作；未连接时 runShizukuAction 内部会触发绑定+等待）
        val r = shizukuAction(NekoShellService.MSG_BATTERY_WHITELIST)
        if (r != null) {
            sb.appendLine("3. UserService绑定: ✓ 成功")
            sb.appendLine("4. 服务端响应: ✓ (shell权限正常)")
        } else {
            sb.appendLine("3. UserService绑定: ✗ 失败")
            val err = lastShizukuError
            if (err != null) {
                sb.appendLine("   具体原因: $err")
            }
            sb.appendLine("   建议：①确认已在 Shizuku 应用/弹窗中授予 NekoType 权限（UserService 绑定必需）")
            sb.appendLine("         ②重启Shizuku服务后重开APP ③开发者模式调用「重绑 Shizuku」强制重连")
        }
        return sb.toString()
    }

    // ---------- 设备管理员 ----------

    val adminComponent: ComponentName
        get() = ComponentName(NekoTypeApp.instance, NekoTypeDeviceAdminReceiver::class.java)

    fun isDeviceAdminActive(): Boolean = try {
        val dpm = NekoTypeApp.instance.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.isAdminActive(adminComponent)
    } catch (_: Throwable) {
        false
    }

    fun requestDeviceAdmin() {
        try {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, NekoTypeApp.instance.getString(R.string.u162))
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            NekoTypeApp.instance.startActivity(intent)
        } catch (_: Throwable) { }
    }

    // ---------- 电池优化 ----------

    fun isIgnoringBatteryOptimizations(): Boolean = try {
        val pm = NekoTypeApp.instance.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(NekoTypeApp.instance.packageName)
    } catch (_: Throwable) {
        false
    }

    /** 弹出系统"允许忽略电池优化"对话框（用户手动确认） */
    fun requestBatteryOptimizationDialog() {
        try {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:${NekoTypeApp.instance.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            NekoTypeApp.instance.startActivity(intent)
        } catch (_: Throwable) { }
    }

    /** 通过 Root/Shizuku 直接写入电池优化白名单，无需弹窗（固定动作，无任意命令通道） */
    fun grantBatteryWhitelistPrivileged(): ExecResult {
        val r = shizukuAction(NekoShellService.MSG_BATTERY_WHITELIST) ?: return ExecResult(false, NekoTypeApp.instance.getString(R.string.u173), "none")
        return ExecResult(true, r, lastChannelUsed)
    }

    /**
     * 打开系统「自启动管理」页（通过系统接口）：
     * 国内 ROM（尤其 vivo/iQOO OriginOS「橘子」、小米、OPPO、华为）只认自启动权限，
     * 光加电池白名单不够——不开自启动会被系统冻结后台，无障碍服务和改写都会失效。
     * 依次尝试各厂商页面，全失败则退到应用详情页，再失败给提示。
     */
    fun openAutoStartSettings(context: Context) {
        val pages = listOf(
            // vivo / iQOO（OriginOS）
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            // 小米 / 红米
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            // OPPO / 一加 / realme（ColorOS）
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            // 华为 / 荣耀
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            // 三星
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")
        )
        for (cn in pages) {
            try {
                context.startActivity(Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Throwable) { }
        }
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Throwable) {
            try {
                android.widget.Toast.makeText(context, context.getString(R.string.hc_autostart_fail), android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) { }
        }
    }

    /** 通过 Shizuku 授予「显示在应用上层」权限（appops 直写，无需跳转设置页） */
    fun grantOverlayPrivileged(): ExecResult {
        val r = shizukuAction(NekoShellService.MSG_GRANT_OVERLAY) ?: return ExecResult(false, NekoTypeApp.instance.getString(R.string.u173), "none")
        return ExecResult(true, r, lastChannelUsed)
    }

    /** 通过 Shizuku 开启无障碍服务（settings put secure，保留已有无障碍服务） */
    fun grantAccessibilityPrivileged(): ExecResult {
        val r = shizukuAction(NekoShellService.MSG_GRANT_ACCESSIBILITY) ?: return ExecResult(false, NekoTypeApp.instance.getString(R.string.u173), "none")
        return ExecResult(true, r, lastChannelUsed)
    }

    /** 通过 Shizuku 激活设备管理员（dpm set-active-admin，shell 权限可直接执行） */
    fun grantDeviceAdminPrivileged(): ExecResult {
        val r = shizukuAction(NekoShellService.MSG_GRANT_DEVICE_ADMIN) ?: return ExecResult(false, NekoTypeApp.instance.getString(R.string.u173), "none")
        return ExecResult(true, r, lastChannelUsed)
    }

    /**
     * 一键授权所有权限：依次执行 无障碍 → 设备管理员 → 电池优化 → 悬浮窗。
     * 每步独立记录成功/失败和输出，任一步失败不影响后续步骤。
     * 必须在后台线程调用（每次绑定 UserService 有阻塞等待）。
     * @return 每步的详细结果列表
     */
    fun grantAllPermissions(): List<PermissionStep> {
        val ctx = NekoTypeApp.instance
        val steps = mutableListOf<PermissionStep>()

        // 1. 无障碍
        runCatching {
            val r = grantAccessibilityPrivileged()
            steps.add(PermissionStep(ctx.getString(R.string.u180), r.success, r.output, r.channel))
        }.getOrElse { steps.add(PermissionStep(ctx.getString(R.string.u180), false, it.message ?: "error", "none")) }

        // 2. 设备管理员
        runCatching {
            val r = grantDeviceAdminPrivileged()
            steps.add(PermissionStep(ctx.getString(R.string.u181), r.success, r.output, r.channel))
        }.getOrElse { steps.add(PermissionStep(ctx.getString(R.string.u181), false, it.message ?: "error", "none")) }

        // 3. 电池优化白名单
        runCatching {
            val r = grantBatteryWhitelistPrivileged()
            steps.add(PermissionStep(ctx.getString(R.string.u182), r.success, r.output, r.channel))
        }.getOrElse { steps.add(PermissionStep(ctx.getString(R.string.u182), false, it.message ?: "error", "none")) }

        // 4. 悬浮窗
        runCatching {
            val r = grantOverlayPrivileged()
            steps.add(PermissionStep(ctx.getString(R.string.u183), r.success, r.output, r.channel))
        }.getOrElse { steps.add(PermissionStep(ctx.getString(R.string.u183), false, it.message ?: "error", "none")) }

        return steps
    }

    /**
     * 开机自启后的权限恢复：用 Shizuku 重新写入 无障碍 / 免电白名单 / 悬浮窗。
     *
     * 为什么要它：系统（尤其国产 ROM）重启后经常把无障碍服务清掉，以前必须连电脑用
     * `adb shell settings put secure enabled_accessibility_services ...` 手动恢复；
     * 现在开机时自动用 Shizuku 写回去，用户无感。
     *
     * 会等待 Shizuku 就绪（最多 30 秒），必须在后台线程调用。
     */
    fun restoreAllPrivilegesViaShizuku(): String {
        val sb = StringBuilder()
        var waited = 0
        while (!isShizukuAvailable() && waited < 30_000) {
            try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            waited += 1000
        }
        if (!isShizukuAvailable()) return "Shizuku 未运行，跳过开机权限恢复"
        val steps = listOf(
            "无障碍" to { grantAccessibilityPrivileged() },
            "免电白名单" to { grantBatteryWhitelistPrivileged() },
            "悬浮窗" to { grantOverlayPrivileged() }
        )
        for ((name, action) in steps) {
            val r = try { action() } catch (t: Throwable) { ExecResult(false, t.message ?: "error", "none") }
            sb.append(name).append(if (r.success) " ✓" else " ✗").append("（").append(r.channel).append("）")
            if (!r.success && r.output.isNotEmpty()) sb.append(" ").append(r.output.replace("\n", " ").take(80))
            sb.append('\n')
        }
        return sb.toString().trim()
    }

    /** 诊断：执行 id 确认特权通道（固定动作专用，仅供状态页显示通道信息） */
    fun execIdForStatus(): ExecResult {
        // 状态页仅需确认通道可达，用免电白名单动作探测即可（不做任意命令）
        val ok = shizukuAction(NekoShellService.MSG_BATTERY_WHITELIST) != null
        return ExecResult(ok, if (ok) lastChannelUsed else "", if (ok) lastChannelUsed else "none")
    }

    /** 最近一次成功使用的通道名（UserService / newProcess），供状态页与诊断显示 */
    @Volatile var lastChannelUsed: String = "none"
        private set

    /**
     * Shizuku 通道是否可用（静默修改等特权能力的前提）。
     *
     * v2.6.6 起：不再只看 UserService 长连接。特权动作**先走 AIDL 直连服务端的 newProcess 通道**
     * （免绑定、随取随用，见 [execRemote]），失败才回退 UserService 长连接，
     * 因此只要 Shizuku 存活且已授权，通道就可用——原先"UserService 没绑上就整个通道不可用"
     * 正是长期"Shizuku 用不了"的根因；v2.7.9 起调换优先级以消除无 UserService 时白等 8 秒的问题。
     */
    fun privilegedChannelReady(): Boolean = isShizukuAvailable() && isShizukuPermissionGranted()

    /** UserService 长连接是否已建立（诊断显示用；为 false 不代表通道不可用，会自动走 newProcess） */
    fun isUserServiceConnected(): Boolean = shellConnected && shellMessenger != null

    private fun shizukuAction(action: Int, data: Bundle? = null): String? {
        return try {
            if (!isShizukuAvailable()) return null
            val viaProc = execActionViaRemoteProcess(action, data)
            if (viaProc != null) {
                lastChannelUsed = "newProcess"
                return viaProc
            }
            val viaUser = runShizukuAction(action, data)
            if (viaUser != null) {
                lastChannelUsed = "UserService"
                return viaUser
            }
            trace("动作 $action：两条通道都失败（${lastShizukuError ?: "未知"}）")
            null
        } catch (t: Throwable) {
            trace("动作 $action：通道异常 ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    // ---------- 降级通道：AIDL 直连 Shizuku 服务端 newProcess ----------

    /**
     * 让 Shizuku 服务端起一个 shell 进程执行**固定命令**（不依赖 UserService）。
     *
     * 走的是公开 AIDL：`moe.shizuku.server.IShizukuService.newProcess()`。
     * 客户端 API 里 `Shizuku.newProcess()` 被设为 private，但 AIDL 接口本身是公开的，
     * 我们通过 `Shizuku.getBinder()` + `Stub.asInterface` 直接调用，不需要反射、不碰隐藏 API。
     *
     * 安全：命令只能来自 [FixedCommands]（固定模板 + 受限参数），客户端无法传任意命令。
     */
    private fun execActionViaRemoteProcess(action: Int, data: Bundle?): String? {
        val pkg = NekoTypeApp.instance.packageName
        val cmd = FixedCommands.build(action, data, pkg)
        if (cmd != null) return execRemote(cmd)
        // 悬浮窗授权：名称 op 不被识别时用数字 op（24）兜底
        if (action == NekoShellService.MSG_GRANT_OVERLAY) {
            FixedCommands.overlayFallback(pkg)?.let { return execRemote(it) }
        }
        lastShizukuError = "动作 $action 参数非法或不支持（newProcess 通道）"
        return null
    }

    /** 执行一条固定命令并取回输出（newProcess 通道） */
    private fun execRemote(cmd: String): String? {
        return try {
            if (!isShizukuAvailable()) {
                lastShizukuError = "Shizuku 服务未运行"
                return null
            }
            val binder = Shizuku.getBinder() ?: run {
                lastShizukuError = "Shizuku.getBinder() 为 null"
                return null
            }
            val svc = moe.shizuku.server.IShizukuService.Stub.asInterface(binder) ?: run {
                lastShizukuError = "IShizukuService.asInterface 失败"
                return null
            }
            val proc = svc.newProcess(arrayOf("sh", "-c", cmd), null, null) ?: run {
                lastShizukuError = "服务端 newProcess 返回 null（可能未授权）"
                return null
            }
            // 错误流单独线程读，避免输出/错误很多时互相阻塞
            var errText = ""
            val errThread = Thread {
                try {
                    errText = android.os.ParcelFileDescriptor.AutoCloseInputStream(proc.errorStream)
                        .bufferedReader().readText()
                } catch (_: Throwable) { }
            }
            errThread.start()
            val out = android.os.ParcelFileDescriptor.AutoCloseInputStream(proc.inputStream)
                .bufferedReader().readText()
            val code = try { proc.waitFor() } catch (_: Throwable) { -1 }
            try { errThread.join(1500) } catch (_: Throwable) { }
            val combined = (out + errText).trim()
            if (code == 0) {
                lastShizukuError = null
                return combined
            }
            lastShizukuError = "newProcess exit=$code: ${combined.take(160)}"
            null
        } catch (t: Throwable) {
            lastShizukuError = "newProcess 通道异常: ${t.javaClass.simpleName}: ${t.message}"
            trace(lastShizukuError ?: "newProcess 异常")
            null
        }
    }

    // ---------- 静默修改（文本注入） ----------

    /** 文本是否可被 shell input 注入（仅 ASCII；含引号/百分号的做转义处理） */
    fun isInjectionSafe(text: String): Boolean =
        text.isNotEmpty() && text.all { it.code in 0x20..0x7E && it != '\'' } && !text.contains("%s")

    /**
     * 通过 Shizuku 注入文本到当前聚焦输入框（替换式：先全选再注入）：
     * - ASCII：newProcess 通道 `input text`（免绑定，最快）
     * - 非 ASCII（中文/颜文字/Emoji）：回退 UserService 的 InputManager **按键注入**
     *   （Unicode 级真打字，微信/Termux 等非原生输入框同样有效）
     * 全程不碰剪贴板、无任何弹窗提示。
     * 安全：走固定动作，服务端白名单命令 / 固定注入逻辑。
     */
    fun shizukuInjectText(text: String): ExecResult {
        if (text.isEmpty()) return ExecResult(false, "empty", "none")
        if (shizukuAction(NekoShellService.MSG_INJECT_SELECT_ALL) == null) {
            return ExecResult(false, "select-all 失败（$lastShizukuError）", "none")
        }
        val out = shizukuAction(NekoShellService.MSG_INJECT_KEYS, Bundle().apply { putString("text", text) })
            ?: return ExecResult(false, NekoTypeApp.instance.getString(R.string.u173), "none")
        return ExecResult(true, out, lastChannelUsed)
    }

    /**
     * 通过 Shizuku 把文本**追加**到当前输入框光标处（盲操作专用：不读原文）：
     * 先把光标移到末尾（CTRL+END），再按键注入。
     * 用于微信等读不到输入框内容的场景——只追加后缀/随机后缀，无需知道原文。
     */
    fun shizukuAppendText(text: String): ExecResult {
        if (text.isEmpty()) return ExecResult(false, "empty", "none")
        shizukuAction(NekoShellService.MSG_INJECT_MOVE_END)
        val out = shizukuAction(NekoShellService.MSG_INJECT_KEYS, Bundle().apply { putString("text", text) })
            ?: return ExecResult(false, NekoTypeApp.instance.getString(R.string.u173), "none")
        return ExecResult(true, out, lastChannelUsed)
    }

    /**
     * 通过 Shizuku 写入系统剪贴板（支持中文/Unicode）。
     * 注：v2.7.9 起中文注入已改用按键注入（[shizukuInjectText]），剪贴板路径仅在
     * 需要由无障碍端显式 ACTION_PASTE 的少数场景使用。
     */
    fun shizukuSetClipboard(text: String): ExecResult {
        if (text.isEmpty()) return ExecResult(false, "empty", "none")
        val out = shizukuAction(NekoShellService.MSG_CLIPBOARD_SET, Bundle().apply { putString("text", text) })
            ?: return ExecResult(false, NekoTypeApp.instance.getString(R.string.u173), "none")
        return ExecResult(true, out, lastChannelUsed)
    }

    // ---------- 开发者模式 ----------

    /** 开发者模式：重置并重新绑定 Shizuku 长连接（必须在后台线程调用） */
    fun devResetShizuku(): String {
        return try {
            resetShellService()
            Thread.sleep(400)
            val ok = privilegedChannelReady()
            "已重置并重新绑定：UserService=${if (ok) "已连接" else "未连接"}  ${lastShizukuError ?: ""}"
        } catch (t: Throwable) {
            "重置失败: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /**
     * 抓取系统日志（开发者模式）：
     * 优先走 Shizuku 固定动作（logcat 全系统日志，命令模板写死、tag 受限过滤）；
     * Shizuku 不可用时退回抓本进程日志（Android 允许应用读自己进程的 logcat，无需任何权限）。
     * 必须在后台线程调用。
     */
    fun devLogcat(filter: String?, crash: Boolean): ExecResult {
        val data = Bundle().apply {
            if (!filter.isNullOrBlank()) putString("tag", filter.trim())
        }
        shizukuAction(if (crash) NekoShellService.MSG_LOGCAT_CRASH else NekoShellService.MSG_LOGCAT_DUMP, data)?.let {
            return ExecResult(true, it, lastChannelUsed)
        }
        // 本地兜底：--pid 只读本进程日志
        return try {
            val pid = android.os.Process.myPid()
            val tag = filter?.trim().orEmpty()
            val tagPart = if (tag.matches(Regex("[A-Za-z0-9_.\\-]{1,32}"))) " $tag:V *:S" else ""
            // 不用 --pid：部分 ROM（EMUI）不接受该参数，会把整个缓冲（含别的进程）倒出来
            val cmd = if (crash) "logcat -b crash -d -t 500"
            else "logcat -d -t 2000$tagPart"
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val raw = p.inputStream.bufferedReader().readText()
            // 代码层过滤：只保留本进程日志（logcat 行首为时间 + PID + TID）
            val out = raw.lineSequence().filter { line ->
                line.contains(" $pid ") || line.contains(NekoTypeApp.instance.packageName) || line.startsWith("---")
            }.joinToString("\n").takeLast(200_000)
            p.errorStream.bufferedReader().readText()
            p.waitFor()
            ExecResult(out.isNotEmpty(), out, "local")
        } catch (t: Throwable) {
            ExecResult(false, "本地 logcat 失败: ${t.message}", "local")
        }
    }

    // ---------- 隐藏模式（隐藏桌面图标） ----------

    /**
     * Shizuku 隐藏/恢复自身（pm hide）：
     * 图标立即从桌面消失（launcher 即时刷新，无华为缓存问题）。
     * 注意：pm hide 会终止当前进程，但已注册的心跳闹钟仍可拉起服务保持通知栏入口。
     * 请勿在主线程调用（会阻塞）。
     */
    fun shizukuHideSelf(hidden: Boolean): ExecResult {
        val out = shizukuAction(NekoShellService.MSG_HIDE_SELF, Bundle().apply { putBoolean("hidden", hidden) })
            ?: return ExecResult(false, NekoTypeApp.instance.getString(R.string.u173), "none")
        return ExecResult(true, out, lastChannelUsed)
    }

    /**
     * 隐藏/恢复桌面图标：通过禁用/启用 activity-alias 的 LAUNCHER 入口实现（
     * 无需 Shizuku/设备管理员——应用可禁用自身组件）。
     * 隐藏后桌面图标消失，但 MainActivity 仍可被显式 Intent（通知栏/磁贴）拉起。
     */
    fun setHiddenMode(hidden: Boolean) {
        try {
            val pm = NekoTypeApp.instance.packageManager
            val alias = ComponentName(NekoTypeApp.instance, "com.nekotype.app.MainActivityAlias")
            pm.setComponentEnabledSetting(
                alias,
                if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            // 部分桌面（如华为）不主动刷新图标缓存，主动广播通知刷新，
            // 避免残留图标点击后跳「应用信息」
            try {
                val i = Intent(
                    Intent.ACTION_PACKAGE_CHANGED,
                    android.net.Uri.parse("package:${NekoTypeApp.instance.packageName}")
                )
                i.putExtra(Intent.EXTRA_CHANGED_COMPONENT_NAME, alias.flattenToString())
                NekoTypeApp.instance.sendBroadcast(i)
            } catch (_: Throwable) { }
        } catch (_: Throwable) { }
    }

    /**
     * 隐藏模式下的卸载保护：设备管理员激活时阻止卸载，
     * 必须先取消激活才能卸载，别人删不掉）。
     * @param enabled true=阻止卸载，false=恢复
     */
    fun setUninstallBlockedByAdmin(enabled: Boolean): Boolean {
        return try {
            val dpm = NekoTypeApp.instance.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isAdminActive(adminComponent)) {
                dpm.setUninstallBlocked(adminComponent, NekoTypeApp.instance.packageName, enabled)
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }
}
