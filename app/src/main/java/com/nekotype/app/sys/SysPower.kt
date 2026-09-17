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

    /** 复用的 UserServiceArgs（进程名由 processNameSuffix 指定，manifest 不需声明 android:process） */
    private val shellArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(NekoTypeApp.instance, NekoShellService::class.java))
            .processNameSuffix(":shizuku")
    }

    /** 长连接的 ServiceConnection：onServiceDisconnected 自动重连 */
    private val shellConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            android.util.Log.e("SysPower", "UserService 长连接 onServiceConnected binder=${binder != null}")
            shellBinding = false
            if (binder != null) {
                shellMessenger = Messenger(binder)
                shellConnected = true
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            android.util.Log.e("SysPower", "UserService 长连接 onServiceDisconnected，触发自动重连")
            shellMessenger = null
            shellConnected = false
            bindShellService()
        }
    }

    /** Shizuku Binder 存活监听：Shizuku 重启后自动重连 UserService */
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        android.util.Log.e("SysPower", "Shizuku Binder received，尝试绑定 UserService")
        bindShellService()
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        android.util.Log.e("SysPower", "Shizuku Binder dead，清理 UserService 连接")
        shellMessenger = null
        shellConnected = false
    }

    /** 初始化 Shizuku 通道：注册 Binder 监听 + 预绑定（在 NekoTypeApp.onCreate 调用一次） */
    fun initShizukuChannel() {
        try {
            Shizuku.addBinderReceivedListener(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
        } catch (_: Throwable) { }
        if (isShizukuAvailable()) {
            bindShellService()
        }
    }

    /** 绑定 UserService（幂等：已连接/绑定中则跳过；Shizuku 未运行则跳过） */
    fun bindShellService() {
        if (shellConnected || shellBinding) return
        if (!isShizukuAvailable()) return
        try {
            shellBinding = true
            android.util.Log.e("SysPower", "bindUserService（长连接）开始...")
            Shizuku.bindUserService(shellArgs, shellConnection)
        } catch (t: Throwable) {
            shellBinding = false
            android.util.Log.e("SysPower", "bindUserService 异常", t)
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
    }

    /** 强制重置连接（unbindAll=true，杀掉整个 UserService 进程后重新绑定；仅在连接卡死/Shizuku 异常时用） */
    fun resetShellService() {
        try {
            Shizuku.unbindUserService(shellArgs, shellConnection, true)
        } catch (_: Throwable) { }
        shellMessenger = null
        shellConnected = false
        shellBinding = false
        bindShellService()
    }

    /**
     * 通过 Shizuku UserService 执行【固定动作】（Messenger 方式，安全审计后重构）：
     * 不再传输任意 shell 命令，只发送固定动作类型 + 受限参数，
     * 由 NekoShellService 内部执行写死的命令。
     * 返回 null 表示绑定失败/超时/未授权。
     */
    private fun runShizukuAction(action: Int, data: Bundle? = null): String? {
        // 确保长连接已建立（未连接则触发绑定并等待最多5秒）
        if (!shellConnected) {
            bindShellService()
            val waitStart = System.currentTimeMillis()
            while (!shellConnected && System.currentTimeMillis() - waitStart < 5000) {
                Thread.sleep(100)
            }
            if (!shellConnected) {
                lastShizukuError = "UserService 连接失败：5秒内 onServiceConnected 未触发（Shizuku 拒绝绑定或 UserService 启动失败）"
                android.util.Log.e("SysPower", "runShizukuAction 连接超时")
                // 【关键修复】绑定失败必须清掉 shellBinding：否则之后所有 bindShellService() 都会在第一行
                // 直接 return，通道一直假死到 APP 重启（Shizuku 晚启动/拒绝绑定一次就永久失效）。
                shellBinding = false
                try { Shizuku.unbindUserService(shellArgs, shellConnection, false) } catch (_: Throwable) { }
                return null
            }
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
                android.util.Log.e("SysPower", "runShizukuAction 回包超时，重置连接")
                shellMessenger = null
                shellConnected = false
                return null
            }

            if (!ok) {
                lastShizukuError = "服务端返回失败: ${result ?: ""}"
                android.util.Log.e("SysPower", "runShizukuAction 服务端失败: $lastShizukuError")
            }
            return if (ok) result ?: "" else null
        } catch (t: Throwable) {
            lastShizukuError = "发送消息异常: ${t.javaClass.simpleName}: ${t.message}"
            android.util.Log.e("SysPower", "runShizukuAction 发送异常", t)
            // 发送失败通常意味着连接已断，清理状态待下次自动重连
            shellMessenger = null
            shellConnected = false
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
            sb.appendLine("   建议：①重启Shizuku服务后重开APP ②确认Shizuku版本≥12 ③调用 resetShellService() 强制重连")
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
        return ExecResult(true, r, "shizuku")
    }

    /**
     * 打开系统「自启动管理」页（照抄开源实现的做法）：
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
                android.widget.Toast.makeText(context, "无法打开自启动设置，请在系统设置中手动查找", android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) { }
        }
    }

    /** 通过 Shizuku 授予「显示在应用上层」权限（appops 直写，无需跳转设置页） */
    fun grantOverlayPrivileged(): ExecResult {
        val r = shizukuAction(NekoShellService.MSG_GRANT_OVERLAY) ?: return ExecResult(false, NekoTypeApp.instance.getString(R.string.u173), "none")
        return ExecResult(true, r, "shizuku")
    }

    /** 通过 Shizuku 开启无障碍服务（settings put secure，保留已有无障碍服务） */
    fun grantAccessibilityPrivileged(): ExecResult {
        val r = shizukuAction(NekoShellService.MSG_GRANT_ACCESSIBILITY) ?: return ExecResult(false, NekoTypeApp.instance.getString(R.string.u173), "none")
        return ExecResult(true, r, "shizuku")
    }

    /** 通过 Shizuku 激活设备管理员（dpm set-active-admin，shell 权限可直接执行） */
    fun grantDeviceAdminPrivileged(): ExecResult {
        val r = shizukuAction(NekoShellService.MSG_GRANT_DEVICE_ADMIN) ?: return ExecResult(false, NekoTypeApp.instance.getString(R.string.u173), "none")
        return ExecResult(true, r, "shizuku")
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

    /** 诊断：执行 id 确认特权通道（固定动作专用，仅供状态页显示通道信息） */
    fun execIdForStatus(): ExecResult {
        // 状态页仅需确认通道可达，用免电白名单动作探测即可（不做任意命令）
        val ok = shizukuAction(NekoShellService.MSG_BATTERY_WHITELIST) != null
        return ExecResult(ok, if (ok) "shell" else "", "shizuku")
    }

    /** Shizuku 通道当前是否可用（静默修改的前提）
     *  v2.6.5 起改为判断长连接状态：UserService 已绑定且 Messenger 有效。
     *  仅 pingBinder 通不够——Shizuku 活着但 UserService 没绑上时，调用必然失败。 */
    fun privilegedChannelReady(): Boolean = shellConnected && shellMessenger != null

    private fun shizukuAction(action: Int, data: Bundle? = null): String? {
        return try {
            // UserService 绑定无需 API 权限，仅需 Shizuku 服务存活
            if (!isShizukuAvailable()) return null
            runShizukuAction(action, data)
        } catch (_: Throwable) {
            null
        }
    }

    // ---------- 静默修改（文本注入） ----------

    /** 文本是否可被 shell input 注入（仅 ASCII；含引号/百分号的做转义处理） */
    fun isInjectionSafe(text: String): Boolean =
        text.isNotEmpty() && text.all { it.code in 0x20..0x7E && it != '\'' } && !text.contains("%s")

    /**
     * 通过 Shizuku 静默注入文本到当前聚焦输入框：
     * - ASCII 文本：先全选（CTRL+A）再 input text 替换全文，无弹窗无剪贴板提示
     * - 非 ASCII（中文/Unicode）：写入系统剪贴板（cmd clipboard set），返回 channel=shizuku-clipboard，
     *   调用方需配合无障碍 ACTION_PASTE 完成粘贴（剪贴板写入本身不触发系统提示）
     * 安全：走固定动作，服务端白名单命令。
     */
    fun shizukuInjectText(text: String): ExecResult {
        if (text.isEmpty()) return ExecResult(false, "empty", "none")
        return if (isInjectionSafe(text)) {
            // ASCII：全选 + input text 直接注入
            shizukuAction(NekoShellService.MSG_INJECT_SELECT_ALL)
            val out = shizukuAction(NekoShellService.MSG_INJECT_TEXT, Bundle().apply { putString("text", text) })
                ?: return ExecResult(false, NekoTypeApp.instance.getString(R.string.u173), "none")
            ExecResult(true, out, "shizuku")
        } else {
            // 非 ASCII（中文等）：走系统剪贴板写入，由调用方配合无障碍粘贴
            val out = shizukuAction(NekoShellService.MSG_CLIPBOARD_SET, Bundle().apply { putString("text", text) })
                ?: return ExecResult(false, NekoTypeApp.instance.getString(R.string.u173), "none")
            ExecResult(true, out, "shizuku-clipboard")
        }
    }

    /**
     * 通过 Shizuku 写入系统剪贴板（支持中文/Unicode），用于静默注入的中文场景补充。
     * 写入后由无障碍端执行 ACTION_PASTE 完成粘贴。
     */
    fun shizukuSetClipboard(text: String): ExecResult {
        if (text.isEmpty()) return ExecResult(false, "empty", "none")
        val out = shizukuAction(NekoShellService.MSG_CLIPBOARD_SET, Bundle().apply { putString("text", text) })
            ?: return ExecResult(false, NekoTypeApp.instance.getString(R.string.u173), "none")
        return ExecResult(true, out, "shizuku")
    }

    // ---------- 隐藏模式（隐藏桌面图标） ----------

    /**
     * Shizuku 隐藏/恢复自身（Hail「雹」同款 pm hide）：
     * 图标立即从桌面消失（launcher 即时刷新，无华为缓存问题）。
     * 注意：pm hide 会终止当前进程，但已注册的心跳闹钟仍可拉起服务保持通知栏入口。
     * 请勿在主线程调用（会阻塞）。
     */
    fun shizukuHideSelf(hidden: Boolean): ExecResult {
        val out = shizukuAction(NekoShellService.MSG_HIDE_SELF, Bundle().apply { putBoolean("hidden", hidden) })
            ?: return ExecResult(false, NekoTypeApp.instance.getString(R.string.u173), "none")
        return ExecResult(true, out, "shizuku")
    }

    /**
     * 隐藏/恢复桌面图标：通过禁用/启用 activity-alias 的 LAUNCHER 入口实现（皆成同款，
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
     * 隐藏模式下的卸载保护：设备管理员激活时阻止卸载（与皆成孩子端同款，
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
