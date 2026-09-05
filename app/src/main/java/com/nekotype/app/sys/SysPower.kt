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

    /**
     * 通过 Shizuku UserService 执行【固定动作】（Messenger 方式，安全审计后重构）：
     * 不再传输任意 shell 命令，只发送固定动作类型 + 受限参数，
     * 由 NekoShellService 内部执行写死的命令。
     * 返回 null 表示绑定失败/超时/未授权。
     */
    private fun runShizukuAction(action: Int, data: Bundle? = null): String? {
        val app = NekoTypeApp.instance
        val args = Shizuku.UserServiceArgs(ComponentName(app, NekoShellService::class.java))
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

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null) { latch.countDown(); return }
                try {
                    val msg = Message.obtain(null, action)
                    msg.data = data ?: Bundle()
                    msg.replyTo = clientMessenger
                    Messenger(binder).send(msg)
                } catch (_: Throwable) {
                    latch.countDown()
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) { latch.countDown() }
        }

        try {
            Shizuku.bindUserService(args, connection)
            if (!latch.await(8, TimeUnit.SECONDS)) return null
            return if (ok) result ?: "" else null
        } catch (_: Throwable) {
            return null
        } finally {
            try { Shizuku.unbindUserService(args, connection, true) } catch (_: Throwable) { }
        }
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

    /** 诊断：执行 id 确认特权通道（固定动作专用，仅供状态页显示通道信息） */
    fun execIdForStatus(): ExecResult {
        // 状态页仅需确认通道可达，用免电白名单动作探测即可（不做任意命令）
        val ok = shizukuAction(NekoShellService.MSG_BATTERY_WHITELIST) != null
        return ExecResult(ok, if (ok) "shell" else "", "shizuku")
    }

    /** Shizuku 通道当前是否可用（静默修改的前提） */
    fun privilegedChannelReady(): Boolean = isShizukuAvailable() && isShizukuPermissionGranted()

    private fun shizukuAction(action: Int, data: Bundle? = null): String? {
        return try {
            if (!isShizukuAvailable() || !isShizukuPermissionGranted()) return null
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
     * 先全选（CTRL+A，Android 10+）再 input text 替换全文。
     * 全程无弹窗、无剪贴板提示。仅支持 ASCII；中文/长文本请走无障碍通道。
     * 安全：走固定动作（MSG_INJECT_SELECT_ALL / MSG_INJECT_TEXT），服务端白名单命令。
     */
    fun shizukuInjectText(text: String): ExecResult {
        if (!isInjectionSafe(text)) return ExecResult(false, "not injectable", "none")
        // 1. 全选（固定动作）
        shizukuAction(NekoShellService.MSG_INJECT_SELECT_ALL)
        // 2. 注入文本（固定动作，text 受限参数）
        val out = shizukuAction(NekoShellService.MSG_INJECT_TEXT, Bundle().apply { putString("text", text) })
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
