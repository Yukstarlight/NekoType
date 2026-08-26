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
     * 通过 Shizuku UserService 执行命令（Messenger 方式）：
     * 绑定 NekoShellService（运行在 Shizuku 服务进程，shell 权限）→ 发送命令 → 等待回复 → 解绑。
     * 返回 null 表示绑定失败/超时/未授权。
     */
    private fun runShizukuCommand(cmd: String): String? {
        val app = NekoTypeApp.instance
        val args = Shizuku.UserServiceArgs(ComponentName(app, NekoShellService::class.java))
        val latch = CountDownLatch(1)
        var result: String? = null

        // 客户端 Handler：接收服务端返回的执行结果（回调在主线程）
        val clientHandler = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.what == NekoShellService.MSG_RESULT) {
                    result = msg.data.getString(NekoShellService.KEY_OUT)
                    latch.countDown()
                }
            }
        }
        val clientMessenger = Messenger(clientHandler)

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null) {
                    latch.countDown()
                    return
                }
                try {
                    val msg = Message.obtain(null, NekoShellService.MSG_EXEC)
                    msg.data = Bundle().apply { putString(NekoShellService.KEY_CMD, cmd) }
                    msg.replyTo = clientMessenger
                    Messenger(binder).send(msg)
                } catch (_: Throwable) {
                    latch.countDown()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                latch.countDown()
            }
        }

        try {
            Shizuku.bindUserService(args, connection)
            if (!latch.await(8, TimeUnit.SECONDS)) return null
            return result
        } catch (_: Throwable) {
            return null
        } finally {
            try {
                Shizuku.unbindUserService(args, connection, true)
            } catch (_: Throwable) { }
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
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "NekoType 需要设备管理员权限以获得更强的系统级能力（可选）。")
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

    /** 通过 Root/Shizuku 直接写入电池优化白名单，无需弹窗 */
    fun grantBatteryWhitelistPrivileged(): ExecResult =
        execShell("dumpsys deviceidle whitelist +${NekoTypeApp.instance.packageName}")

    // ---------- 通用命令执行 ----------

    /** 当前运行模式：basic / shizuku / root */
    fun currentMode(): String = AppPrefs.privilegeMode

    /**
     * 按用户选择的运行模式执行命令，只走所选通道（不再自动乱试）。
     * 请勿在主线程调用。
     */
    fun execShell(cmd: String): ExecResult {
        return when (AppPrefs.privilegeMode) {
            "root" -> rootExec(cmd) ?: ExecResult(false, "Root 通道不可用（未检测到 su / Magisk / KernelSU / APatch）", "none")
            "shizuku" -> shizukuExec(cmd) ?: ExecResult(false, "Shizuku 通道不可用（未运行或未授权）", "none")
            else -> ExecResult(false, "基础模式：不执行系统命令，仅使用悬浮窗 + 无障碍", "none")
        }
    }

    private fun rootExec(cmd: String): ExecResult? {
        return try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(10, TimeUnit.SECONDS)
            ExecResult(p.exitValue() == 0, out.trim(), "root")
        } catch (_: Throwable) {
            null
        }
    }

    private fun shizukuExec(cmd: String): ExecResult? {
        return try {
            if (!isShizukuAvailable() || !isShizukuPermissionGranted()) return null
            val out = runShizukuCommand(cmd) ?: return null
            ExecResult(true, out.trim(), "shizuku")
        } catch (_: Throwable) {
            null
        }
    }
}
