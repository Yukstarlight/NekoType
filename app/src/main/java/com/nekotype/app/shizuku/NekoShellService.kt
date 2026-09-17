package com.nekotype.app.shizuku

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.nekotype.app.NekoTypeApp
import kotlin.concurrent.thread

/**
 * Shizuku UserService：由 Shizuku 服务端以 shell 权限启动（manifest 中 process=":shizuku"）。
 *
 * 安全设计（v2.6.4 重构，响应安全审计）：
 * 本服务【不接受任意 shell 命令】。对外只暴露一组【固定动作】消息类型，
 * 每个动作在服务端内部执行写死的系统命令；参数（文本/包名等）仅作为
 * 白名单命令模板的受限插值，绝不拼接到任意 shell 字符串上。
 * → 不存在 "onBind 返回 Messenger + handleMessage sh -c 任意命令" 的通道。
 *
 * 动作清单：
 * - MSG_BATTERY_WHITELIST   免电白名单（写死 dumpsys deviceidle whitelist + 本包名）
 * - MSG_INJECT_SELECT_ALL   全选（写死 input keycombination 113 29）
 * - MSG_INJECT_TEXT         注入文本（text 参数经严格 ASCII+长度校验后插入固定模板）
 * - MSG_HIDE_SELF           隐藏/恢复自身（pm hide/unhide + 本包名，hidden 为布尔）
 * - MSG_GRANT_OVERLAY       授予悬浮窗权限（appops set android:system_alert_window allow）
 * - MSG_GRANT_ACCESSIBILITY 开启无障碍服务（settings put secure enabled_accessibility_services）
 * - MSG_GRANT_DEVICE_ADMIN  激活设备管理员（dpm set-active-admin）
 * - MSG_CLIPBOARD_SET       写入系统剪贴板（cmd clipboard set，支持中文/Unicode）
 */
class NekoShellService : Service() {

    companion object {
        // 固定动作类型
        const val MSG_BATTERY_WHITELIST = 1
        const val MSG_INJECT_SELECT_ALL = 2
        const val MSG_INJECT_TEXT = 3
        const val MSG_HIDE_SELF = 4
        const val MSG_GRANT_OVERLAY = 5
        const val MSG_GRANT_ACCESSIBILITY = 6
        const val MSG_GRANT_DEVICE_ADMIN = 7
        const val MSG_CLIPBOARD_SET = 8

        // 结果
        const val MSG_RESULT = 100
        const val KEY_OK = "ok"
        const val KEY_OUT = "out"

        // 注入文本的硬限制：仅可打印 ASCII，长度上限防滥用
        private const val MAX_INJECT_LEN = 2000

        // 本应用无障碍服务 & 设备管理员的完整类名（写死，不接受外部传入）
        private const val ACCESSIBILITY_SERVICE = "com.nekotype.app.accessibility.NekoTypeAccessibilityService"
        private const val DEVICE_ADMIN_RECEIVER = "com.nekotype.app.admin.NekoTypeDeviceAdminReceiver"

        private const val TAG = "NekoShell"
    }

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            // 结果一律回给发送方（replyTo），动作在独立线程执行
            val replyTo = msg.replyTo ?: return
            val action = msg.what
            android.util.Log.e(TAG, "action=$action fromUid=${msg.sendingUid}")
            thread {
                var ok: Boolean
                var out: String
                try {
                    val r = when (action) {
                        MSG_BATTERY_WHITELIST -> runBatteryWhitelist()
                        MSG_INJECT_SELECT_ALL -> runShell("input keycombination 113 29")
                        MSG_INJECT_TEXT -> runInjectText(msg.data?.getString("text"))
                        MSG_HIDE_SELF -> runHideSelf(msg.data?.getBoolean("hidden") ?: true)
                        MSG_GRANT_OVERLAY -> runGrantOverlay()
                        MSG_GRANT_ACCESSIBILITY -> runGrantAccessibility()
                        MSG_GRANT_DEVICE_ADMIN -> runGrantDeviceAdmin()
                        MSG_CLIPBOARD_SET -> runClipboardSet(msg.data?.getString("text"))
                        else -> false to "unknown action"
                    }
                    ok = r.first
                    out = r.second
                } catch (t: Throwable) {
                    // 【关键】任何异常都必须回给调用方，否则调用方只能干等超时（旧版就是这样静默失败的）
                    ok = false
                    out = t.javaClass.simpleName + ": " + (t.message ?: "")
                    android.util.Log.e(TAG, "action $action 执行异常", t)
                }
                android.util.Log.e(TAG, "action=$action ok=$ok out=${out.take(200)}")
                try {
                    val reply = Message.obtain(null, MSG_RESULT)
                    reply.data = Bundle().apply {
                        putBoolean(KEY_OK, ok)
                        putString(KEY_OUT, out)
                    }
                    replyTo.send(reply)
                } catch (t: Throwable) {
                    android.util.Log.e(TAG, "回包失败", t)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // UserService 由 Shizuku 服务端实例化时 uid 为 shell(2000)/root(0)，
        // 且 Application（NekoTypeApp）不会初始化 —— 所以本服务内一律用 Service 自身的 packageName
        android.util.Log.e(
            TAG,
            "created uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()} pkg=$packageName"
        )
    }

    override fun onBind(intent: Intent?): IBinder = Messenger(handler).binder

    // ---------- 固定动作（命令全部写死，参数受限插值） ----------

    private fun runBatteryWhitelist(): Pair<Boolean, String> {
        val pkg = packageName
        // 包名来自系统自身，安全；仍校验格式兜底
        if (!pkg.matches(Regex("[a-zA-Z0-9._]+"))) return false to "bad pkg"
        return runShell("dumpsys deviceidle whitelist +$pkg")
    }

    private fun runInjectText(text: String?): Pair<Boolean, String> {
        // 严格校验：仅可打印 ASCII（0x20-0x7E），禁止单引号/控制字符，长度受限
        if (text == null || text.isEmpty() || text.length > MAX_INJECT_LEN) return false to "bad text"
        if (!text.all { it.code in 0x20..0x7E && it != '\'' }) return false to "bad text"
        return runShell("input text '$text'")
    }

    private fun runHideSelf(hidden: Boolean): Pair<Boolean, String> {
        val pkg = packageName
        if (!pkg.matches(Regex("[a-zA-Z0-9._]+"))) return false to "bad pkg"
        val verb = if (hidden) "hide" else "unhide"
        return runShell("pm $verb $pkg")
    }

    /**
     * 写入系统剪贴板（支持中文/Unicode）：通过 cmd clipboard set 命令。
     * shell 安全：单引号包裹 + 内部单引号转义为 '\''，杜绝命令注入。
     */
    private fun runClipboardSet(text: String?): Pair<Boolean, String> {
        if (text == null) return false to "null text"
        if (text.length > 50000) return false to "text too long"
        val escaped = text.replace("'", "'\\''")
        return runShell("cmd clipboard set '$escaped'")
    }

    // ---------- 一键授权：悬浮窗 / 无障碍 / 设备管理员 ----------

    /** 授予「显示在应用上层」权限：通过 appops 直接允许 SYSTEM_ALERT_WINDOW */
    private fun runGrantOverlay(): Pair<Boolean, String> {
        val pkg = packageName
        if (!pkg.matches(Regex("[a-zA-Z0-9._]+"))) return false to "bad pkg"
        // appops set <pkg> android:system_alert_window allow
        // 部分 ROM 识别 op 名，部分只认数字 24；两种都试，取成功结果
        val r1 = runShell("appops set $pkg android:system_alert_window allow")
        if (r1.first) return r1
        val r2 = runShell("appops set $pkg 24 allow")
        return if (r2.first) r2 else (false to (r1.second.ifEmpty { r2.second }))
    }

    /** 开启无障碍服务：写入 Settings.Secure，保留已有无障碍服务不覆盖 */
    private fun runGrantAccessibility(): Pair<Boolean, String> {
        val pkg = packageName
        if (!pkg.matches(Regex("[a-zA-Z0-9._]+"))) return false to "bad pkg"
        val target = "$pkg/$ACCESSIBILITY_SERVICE"
        // shell 脚本：读取当前已启用列表，若已包含目标则跳过，否则追加；最后开启总开关
        val script = """
            current=`settings get secure enabled_accessibility_services`
            target='$target'
            case ":${'$'}current:" in
              *":${'$'}target:"*) already=1 ;;
              *) already=0 ;;
            esac
            if [ "${'$'}already" = "0" ]; then
              if [ "${'$'}current" = "null" ] || [ -z "${'$'}current" ]; then
                settings put secure enabled_accessibility_services "${'$'}target"
              else
                settings put secure enabled_accessibility_services "${'$'}current:${'$'}target"
              fi
            fi
            settings put secure accessibility_enabled 1
            echo "enabled_accessibility_services=`settings get secure enabled_accessibility_services`"
            echo "accessibility_enabled=`settings get secure accessibility_enabled`"
        """.trimIndent()
        return runShell(script)
    }

    /** 激活设备管理员：dpm set-active-admin（shell 权限可直接执行） */
    private fun runGrantDeviceAdmin(): Pair<Boolean, String> {
        val pkg = packageName
        if (!pkg.matches(Regex("[a-zA-Z0-9._]+"))) return false to "bad pkg"
        val cmp = "$pkg/$DEVICE_ADMIN_RECEIVER"
        return runShell("dpm set-active-admin $cmp")
    }

    // ---------- 执行 ----------

    private fun runShell(cmd: String): Pair<Boolean, String> {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            p.waitFor()
            val combined = (out + err).trim()
            (p.exitValue() == 0) to combined
        } catch (t: Throwable) {
            false to (t.message ?: "unknown error")
        }
    }
}
