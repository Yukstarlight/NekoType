package com.nekotype.app.shizuku

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import android.view.KeyEvent
import kotlin.concurrent.thread

/**
 * Shizuku UserService：由 Shizuku 服务端以 shell 权限拉起并实例化（进程名由
 * UserServiceArgs.processNameSuffix(":shizuku") 决定，manifest 不需要 android:process）。
 *
 * 安全设计（v2.6.4 重构，响应安全审计）：
 * 本服务【不接受任意 shell 命令】。对外只暴露一组【固定动作】消息类型，
 * 命令模板集中在 [FixedCommands]（两条特权通道共用同一份），参数（文本/包名/tag）
 * 只作为受限插值，绝不拼接任意 shell 字符串。
 * → 不存在 "onBind 返回 Messenger + handleMessage sh -c 任意命令" 的通道。
 *
 * 动作清单见 [FixedCommands.build]：
 * - MSG_BATTERY_WHITELIST / MSG_INJECT_SELECT_ALL / MSG_INJECT_TEXT / MSG_HIDE_SELF
 * - MSG_GRANT_OVERLAY / MSG_GRANT_ACCESSIBILITY / MSG_GRANT_DEVICE_ADMIN
 * - MSG_CLIPBOARD_SET / MSG_LOGCAT_DUMP / MSG_LOGCAT_CRASH
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
        // 开发者模式：抓取系统日志（logcat 主缓冲 / 崩溃缓冲；tag 为受限参数）
        const val MSG_LOGCAT_DUMP = 9
        const val MSG_LOGCAT_CRASH = 10
        // 按键注入（Unicode 级真打字）：经 InputManager 注入 KeyEvent，
        // 对微信/Termux 等非原生输入框同样有效（它们必须接受键盘通道）
        const val MSG_INJECT_KEYS = 11
        // 光标移到末尾（CTRL+END 组合键，盲操作追加后缀前用）
        const val MSG_INJECT_MOVE_END = 12

        // 结果
        const val MSG_RESULT = 100
        const val KEY_OK = "ok"
        const val KEY_OUT = "out"

        /** logcat 输出上限（防止回包过大） */
        private const val MAX_LOGCAT_CHARS = 300_000

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
                    val cmd = FixedCommands.build(action, msg.data, packageName)
                    val r: Pair<Boolean, String> = when {
                        action == MSG_INJECT_KEYS -> injectKeys(
                            msg.data?.getString("text").orEmpty()
                        )
                        cmd != null -> runShell(cmd)
                        // 悬浮窗授权：部分 ROM 只认数字 op（24），名称 op 失败时兜底一次
                        action == MSG_GRANT_OVERLAY ->
                            FixedCommands.overlayFallback(packageName)?.let { runShell(it) }
                                ?: (false to "bad pkg")
                        else -> false to "invalid action or params"
                    }
                    ok = r.first
                    out = if (action == MSG_LOGCAT_DUMP || action == MSG_LOGCAT_CRASH) {
                        if (r.second.length > MAX_LOGCAT_CHARS) {
                            r.second.takeLast(MAX_LOGCAT_CHARS) + "\n……(已截断，仅显示末尾 30 万字符)"
                        } else r.second
                    } else r.second
                } catch (t: Throwable) {
                    // 【关键】任何异常都必须回给调用方，否则调用方只能干等超时（旧版就是这样静默失败的）
                    ok = false
                    out = t.javaClass.simpleName + ": " + (t.message ?: "")
                    android.util.Log.e(TAG, "action $action 执行异常", t)
                }
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

    /** 执行固定命令（命令由 [FixedCommands] 生成，本方法只负责跑） */
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

    /**
     * 按键注入（Unicode 级真打字）：把任意文本（中文/颜文字/Emoji）拆成
     * ACTION_MULTIPLE KeyEvent 逐块注入当前焦点窗口的 InputConnection。
     *
     * 为什么不用 `input text`：那条命令只收可打印 ASCII，中文会直接报错。
     * 为什么反射：`InputManager.injectInputEvent` 是 @hide API，公开 SDK 编译不过；
     * 本进程由 Shizuku 以 shell(2000) 权限拉起，运行时不受 hidden API 限制 → 反射调用。
     * 效果等效外接键盘打字，微信 / Termux 等非原生输入框必须接受键盘通道，因此同样写得进去。
     * 注入按每 16 个码点一块（含代理对，Emoji 不拆坏），块间留 20ms 防丢。
     */
    private fun injectKeys(text: String): Pair<Boolean, String> {
        return try {
            if (text.isEmpty()) return false to "empty text"
            if (text.length > FixedCommands.MAX_INJECT_LEN) return false to "text too long"
            val imClass = Class.forName("android.hardware.input.InputManager")
            val im = imClass.getMethod("getInstance").invoke(null)
            val inject = imClass.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            val cps = text.codePoints().toArray()
            var i = 0
            var sent = 0
            while (i < cps.size) {
                val end = minOf(i + 16, cps.size)
                val sb = StringBuilder()
                for (j in i until end) sb.appendCodePoint(cps[j])
                val ev = KeyEvent(
                    SystemClock.uptimeMillis(),
                    sb.toString(),
                    -1,
                    KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
                )
                val ok = inject.invoke(im, ev, 0) as Boolean
                if (!ok) break
                sent += end - i
                i = end
                if (i < cps.size) Thread.sleep(20)
            }
            (sent >= cps.size) to "injected=$sent/${cps.size}"
        } catch (t: Throwable) {
            false to (t.javaClass.simpleName + ": " + (t.message ?: ""))
        }
    }
}
