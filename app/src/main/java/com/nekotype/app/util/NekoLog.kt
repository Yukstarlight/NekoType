package com.nekotype.app.util

import android.content.Context
import com.nekotype.app.NekoTypeApp
import org.json.JSONArray
import org.json.JSONObject

/**
 * 简易日志系统：记录关键操作（服务启停、授权、变换发送、错误等），
 * 支持分级（信息/成功/警告/错误）与颜色显示，持久化到本地，设置页可查看/复制/清空。
 *
 * 性能优化（代码审查后）：
 * - 内存缓存：写入只改内存链表，不再每次全量读盘+解析 JSON；
 * - 异步批量落盘：修改后后台延迟 2s 合并写入（高频事件不再阻塞调用线程）；
 * - 读取/清空时若内存为空则从磁盘载入，保证 UI 一致。
 */
object NekoLog {

    const val INFO = 0
    const val OK = 1
    const val WARN = 2
    const val ERROR = 3
    const val NAV = 4      // 页面/导航事件（进入/退出页面、应用启停）
    const val ADJUST = 5   // 设置调整（外观/模式/按钮大小透明度/背景）
    const val RULE = 6     // 规则操作（新增/编辑/删除/开关/预设）

    const val MAX_ENTRIES = 300

    data class Entry(val time: Long, val level: Int, val msg: String) {
        fun timeText(): String {
            val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
            return fmt.format(java.util.Date(time))
        }
    }

    private val sp by lazy {
        NekoTypeApp.instance.getSharedPreferences("nekotype_log", Context.MODE_PRIVATE)
    }

    private val listeners = mutableListOf<() -> Unit>()

    /** 内存日志链表（写入只改内存；落盘延迟批量执行） */
    private val cache = mutableListOf<Entry>()

    /** 是否已安排落盘线程（合并高频写入） */
    private var persistPending = false

    @Synchronized
    fun add(level: Int, msg: String) {
        // 日志总开关：关闭时不记录
        if (!com.nekotype.app.prefs.AppPrefs.logEnabled) return
        val text = when (level) {
            OK -> "✓ $msg"
            WARN -> "Warning：$msg"
            ERROR -> "Error：$msg"
            else -> msg
        }
        cache.add(Entry(System.currentTimeMillis(), level, text))
        while (cache.size > MAX_ENTRIES) cache.removeAt(0)
        // 异步批量落盘（合并高频调用，不阻塞调用线程）
        if (!persistPending) {
            persistPending = true
            Thread {
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) { }
                synchronized(this) {
                    persistPending = false
                    persistLocked(cache.toList())
                }
            }.start()
        }
        listeners.forEach { runCatching { it() } }
    }

    fun info(msg: String) = add(INFO, msg)
    fun ok(msg: String) = add(OK, msg)
    fun warn(msg: String) = add(WARN, msg)
    fun error(msg: String) = add(ERROR, msg)
    fun nav(msg: String) = add(NAV, msg)
    fun adjust(msg: String) = add(ADJUST, msg)
    fun rule(msg: String) = add(RULE, msg)

    @Synchronized
    fun clear() {
        cache.clear()
        persistLocked(emptyList())
        listeners.forEach { runCatching { it() } }
    }

    /** 读取日志：优先内存缓存；为空时从磁盘载入（与最终落盘一致） */
    @Synchronized
    fun entries(): List<Entry> {
        if (cache.isEmpty()) {
            cache.addAll(loadFromDisk())
        }
        return cache.toList()
    }

    /** 强制立即落盘（进程退出等场景调用，保证不丢） */
    @Synchronized
    fun flush() {
        if (cache.isEmpty()) return
        persistLocked(cache.toList())
    }

    private fun loadFromDisk(): List<Entry> {
        val raw = sp.getString("log", "") ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                Entry(o.optLong("t"), o.optInt("l", INFO), o.optString("m", ""))
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun persistLocked(list: List<Entry>) {
        try {
            val arr = JSONArray()
            list.forEach { e ->
                arr.put(JSONObject().apply {
                    put("t", e.time)
                    put("l", e.level)
                    put("m", e.msg)
                })
            }
            sp.edit().putString("log", arr.toString()).apply()
        } catch (_: Throwable) { }
    }

    fun registerListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun unregisterListener(l: () -> Unit) {
        listeners.remove(l)
    }
}
