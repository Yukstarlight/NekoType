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
 * - MSG_BATTERY_WHITELIST  免电白名单（写死 dumpsys deviceidle whitelist + 本包名）
 * - MSG_INJECT_SELECT_ALL  全选（写死 input keycombination 113 29）
 * - MSG_INJECT_TEXT        注入文本（text 参数经严格 ASCII+长度校验后插入固定模板）
 * - MSG_HIDE_SELF          隐藏/恢复自身（pm hide/unhide + 本包名，hidden 为布尔）
 */
class NekoShellService : Service() {

    companion object {
        // 固定动作类型
        const val MSG_BATTERY_WHITELIST = 1
        const val MSG_INJECT_SELECT_ALL = 2
        const val MSG_INJECT_TEXT = 3
        const val MSG_HIDE_SELF = 4

        // 结果
        const val MSG_RESULT = 100
        const val KEY_OK = "ok"
        const val KEY_OUT = "out"

        // 注入文本的硬限制：仅可打印 ASCII，长度上限防滥用
        private const val MAX_INJECT_LEN = 2000
    }

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            // 结果一律回给发送方（replyTo），动作在独立线程执行
            val replyTo = msg.replyTo ?: return
            thread {
                val (ok, out) = when (msg.what) {
                    MSG_BATTERY_WHITELIST -> runBatteryWhitelist()
                    MSG_INJECT_SELECT_ALL -> runShell("input keycombination 113 29")
                    MSG_INJECT_TEXT -> runInjectText(msg.data?.getString("text"))
                    MSG_HIDE_SELF -> runHideSelf(msg.data?.getBoolean("hidden") ?: true)
                    else -> false to "unknown action"
                }
                try {
                    val reply = Message.obtain(null, MSG_RESULT)
                    reply.data = Bundle().apply {
                        putBoolean(KEY_OK, ok)
                        putString(KEY_OUT, out)
                    }
                    replyTo.send(reply)
                } catch (_: Throwable) { }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = Messenger(handler).binder

    // ---------- 固定动作（命令全部写死，参数受限插值） ----------

    private fun runBatteryWhitelist(): Pair<Boolean, String> {
        val pkg = NekoTypeApp.instance.packageName
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
        val pkg = NekoTypeApp.instance.packageName
        if (!pkg.matches(Regex("[a-zA-Z0-9._]+"))) return false to "bad pkg"
        val verb = if (hidden) "hide" else "unhide"
        return runShell("pm $verb $pkg")
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
