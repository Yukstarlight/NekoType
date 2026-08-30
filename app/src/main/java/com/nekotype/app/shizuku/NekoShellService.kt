package com.nekotype.app.shizuku

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlin.concurrent.thread

/**
 * Shizuku UserService：由 Shizuku 服务端以 shell 权限启动（manifest 中 process=":shizuku"），
 * 供应用进程通过 Shizuku 绑定后执行系统命令（如 dumpsys deviceidle whitelist）。
 *
 * 使用 Messenger（纯框架 API）而非 AIDL，规避 AIDL 增量依赖文件在中文路径下的编码问题，
 * 也让项目在任何路径下都能构建。
 */
class NekoShellService : Service() {

    companion object {
        const val MSG_EXEC = 1
        const val MSG_RESULT = 2
        const val KEY_CMD = "cmd"
        const val KEY_OUT = "out"
    }

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != MSG_EXEC) return
            val command = msg.data.getString(KEY_CMD) ?: return
            val replyTo = msg.replyTo ?: return
            // 命令在独立线程执行，避免阻塞 Shizuku 进程主线程
            thread {
                val result = runShell(command)
                try {
                    val reply = Message.obtain(null, MSG_RESULT)
                    reply.data = Bundle().apply { putString(KEY_OUT, result) }
                    replyTo.send(reply)
                } catch (_: Throwable) { }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = Messenger(handler).binder

    private fun runShell(command: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            p.waitFor()
            (out + err).trim()
        } catch (t: Throwable) {
            t.message ?: "unknown error"
        }
    }
}
