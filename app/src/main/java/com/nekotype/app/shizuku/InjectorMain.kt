package com.nekotype.app.shizuku

import android.os.SystemClock
import android.view.KeyEvent

/**
 * newProcess 通道的 Unicode 注入器：由 Shizuku 服务端以 shell 权限
 * `CLASSPATH=<apk> app_process / com.nekotype.app.shizuku.InjectorMain <base64>` 拉起。
 * 与系统 `input` 命令同机制（/system/bin/input 本身就是 app_process 包装），
 * 因此不依赖 UserService 能否被服务端实例化（Stellar 上 Messenger 类起不来）。
 *
 * 文本经 base64 传入（字母数字+/= 单引号内 shell 安全），stdout 输出 OK/FAIL 供调用方判定。
 */
object InjectorMain {

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            var b64 = ""
            var i = 0
            while (i < args.size - 1) {
                if (args[i] == "--b64") b64 = args[i + 1]
                i++
            }
            if (b64.isEmpty()) {
                println("FAIL empty args")
                return
            }
            val text = String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
            if (text.isEmpty()) {
                println("FAIL empty text")
                return
            }
            if (text.length > FixedCommands.MAX_INJECT_LEN) {
                println("FAIL text too long")
                return
            }
            val imClass = Class.forName("android.hardware.input.InputManager")
            val im = imClass.getMethod("getInstance").invoke(null)
            val inject = imClass.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            val cps = text.codePoints().toArray()
            var idx = 0
            var sent = 0
            var mode = -1
            while (idx < cps.size) {
                val end = minOf(idx + 16, cps.size)
                val sb = StringBuilder()
                for (j in idx until end) sb.appendCodePoint(cps[j])
                val ev = KeyEvent(
                    SystemClock.uptimeMillis(),
                    sb.toString(),
                    -1,
                    KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
                )
                // 首块探测可用模式：系统 input 命令用 MODE_INJECT_EXTERNAL_EVENTS(2)，
                // 传 0(ASYNC) 在新版本 Android 上会被直接拒绝（inject rejected at 0 的元凶）
                val ok = if (mode >= 0) {
                    inject.invoke(im, ev, mode) as Boolean
                } else {
                    var hit = false
                    for (m in intArrayOf(2, 1, 0)) {
                        if (inject.invoke(im, ev, m) as Boolean) {
                            mode = m
                            hit = true
                            break
                        }
                    }
                    hit
                }
                if (!ok) {
                    println("FAIL inject rejected at $idx")
                    return
                }
                sent += end - idx
                idx = end
                if (idx < cps.size) Thread.sleep(20)
            }
            println("OK injected=$sent/${cps.size}")
        } catch (t: Throwable) {
            println("FAIL ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
