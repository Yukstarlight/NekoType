package com.nekotype.app.shizuku

import android.os.Bundle

/**
 * 固定动作 → 写死的 shell 命令（**唯一来源**）。
 *
 * 为什么单独抽出来：现在有两条特权通道
 * 1. UserService 通道：命令在 Shizuku 拉起的 `:shizuku` 进程里执行（[NekoShellService]）
 * 2. newProcess 直连通道（降级）：客户端经 AIDL 直接让 Shizuku 服务端起进程执行（SysPower.execRemote）
 *
 * 两条通道都只能从这里取命令 → 无论走哪条，客户端都**无法传入任意命令**，
 * 只能传「固定动作号 + 受限参数」，安全模型完全不变。
 */
object FixedCommands {

    /** 注入文本的硬限制：仅可打印 ASCII，长度上限防滥用 */
    const val MAX_INJECT_LEN = 2000

    /** 剪贴板写入长度上限 */
    private const val MAX_CLIPBOARD_LEN = 50000

    /** 本应用无障碍服务 & 设备管理员的完整类名（写死，不接受外部传入） */
    const val ACCESSIBILITY_SERVICE = "com.google.android.accessibility.selecttospeak.SelectToSpeakService"
    const val DEVICE_ADMIN_RECEIVER = "com.nekotype.app.admin.NekoTypeDeviceAdminReceiver"

    private fun validPkg(pkg: String): Boolean =
        pkg.isNotEmpty() && pkg.matches(Regex("[a-zA-Z0-9._]+"))

    /**
     * 构造动作对应的固定命令；返回 null 表示参数非法或不支持的动作。
     * @param pkg 本应用包名（UserService 内用 Service.packageName，客户端用 packageName）
     */
    fun build(action: Int, data: Bundle?, pkg: String): String? = when (action) {
        NekoShellService.MSG_BATTERY_WHITELIST ->
            if (validPkg(pkg)) "dumpsys deviceidle whitelist +$pkg" else null

        NekoShellService.MSG_INJECT_SELECT_ALL -> "input keycombination 113 29"

        NekoShellService.MSG_INJECT_TEXT -> {
            val text = data?.getString("text")
            when {
                text == null || text.isEmpty() || text.length > MAX_INJECT_LEN -> null
                // 仅可打印 ASCII，禁止单引号/控制字符
                !text.all { it.code in 0x20..0x7E && it != '\'' } -> null
                else -> "input text '$text'"
            }
        }

        // 按键注入（Unicode 级真打字）：
        // - ASCII：newProcess 通道直接 `input text`
        // - 非 ASCII（中文/颜文字/Emoji）：`input text` 不支持，改走 app_process
        //   拉起本 APK 内的 InjectorMain（与系统 input 命令同机制，shell 权限反射注入）；
        //   仅在「微信适配（实验）」开启时提供 apk 路径（客户端侧），关闭时保持旧行为
        NekoShellService.MSG_INJECT_KEYS -> {
            val text = data?.getString("text")
            val apk = data?.getString("apk").orEmpty()
            when {
                text == null || text.isEmpty() || text.length > MAX_INJECT_LEN -> null
                text.all { it.code in 0x20..0x7E && it != '\'' } -> "input text '$text'"
                apk.isEmpty() -> null
                else -> {
                    val b64 = android.util.Base64.encodeToString(
                        text.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
                    )
                    "CLASSPATH='$apk' app_process / com.nekotype.app.shizuku.InjectorMain --b64 '$b64'"
                }
            }
        }

        // 光标移到末尾：CTRL(113) + END(123)
        NekoShellService.MSG_INJECT_MOVE_END -> "input keycombination 113 123"

        NekoShellService.MSG_HIDE_SELF -> {
            val verb = if (data?.getBoolean("hidden") ?: true) "hide" else "unhide"
            if (validPkg(pkg)) "pm $verb $pkg" else null
        }

        NekoShellService.MSG_GRANT_OVERLAY ->
            if (validPkg(pkg)) "appops set $pkg android:system_alert_window allow" else null

        NekoShellService.MSG_GRANT_ALL -> grantAllScript(pkg)

        NekoShellService.MSG_GRANT_ACCESSIBILITY ->
            if (validPkg(pkg)) accessibilityScript(pkg) else null

        NekoShellService.MSG_GRANT_DEVICE_ADMIN ->
            if (validPkg(pkg)) "dpm set-active-admin $pkg/$DEVICE_ADMIN_RECEIVER" else null

        NekoShellService.MSG_CLIPBOARD_SET -> {
            val text = data?.getString("text")
            if (text == null || text.length > MAX_CLIPBOARD_LEN) null
            else "cmd clipboard set '" + text.replace("'", "'\''") + "'"
        }

        NekoShellService.MSG_LOGCAT_DUMP -> logcatCmd(data?.getString("tag"), crash = false)
        NekoShellService.MSG_LOGCAT_CRASH -> logcatCmd(data?.getString("tag"), crash = true)

        else -> null
    }

    /**
     * 一键授权合并脚本：一个 sh 进程跑完四项，每一项都在脚本内**回读真实状态**后再输出。
     * 输出格式：ACCESSIBILITY=ok|fail、ADMIN=ok|fail、BATTERY=ok|fail、OVERLAY=ok|fail
     * 目的：只起一次 newProcess（省掉 3 次冷启动）+ 不信退出码、只信回读。
     */
    fun grantAllScript(pkg: String): String? {
        if (!validPkg(pkg)) return null
        val acc = "$pkg/$ACCESSIBILITY_SERVICE"
        val adm = "$pkg/$DEVICE_ADMIN_RECEIVER"
        return listOf(
            "cur=\$(settings get secure enabled_accessibility_services)",
            "acc='$acc'",
            "case \":\$cur:\" in *\":\$acc:\"*) ;; *) if [ \"\$cur\" = \"null\" ] || [ -z \"\$cur\" ]; then settings put secure enabled_accessibility_services \"\$acc\"; else settings put secure enabled_accessibility_services \"\$cur:\$acc\"; fi ;; esac",
            "settings put secure accessibility_enabled 1",
            "now=\$(settings get secure enabled_accessibility_services)",
            "en=\$(settings get secure accessibility_enabled)",
            "case \":\$now:\" in *\":\$acc:\"*) if [ \"\$en\" = \"1\" ]; then echo ACCESSIBILITY=ok; else echo ACCESSIBILITY=fail_en; fi ;; *) echo ACCESSIBILITY=fail_list ;; esac",
            "dpm set-active-admin $adm >/dev/null 2>&1",
            "if dumpsys device_policy 2>/dev/null | grep -q \"$adm\"; then echo ADMIN=ok; else echo ADMIN=fail; fi",
            "cmd deviceidle whitelist +$pkg >/dev/null 2>&1",
            "dumpsys deviceidle whitelist +$pkg >/dev/null 2>&1",
            "if dumpsys deviceidle whitelist 2>/dev/null | grep -q \"$pkg\"; then echo BATTERY=ok; else echo BATTERY=fail; fi",
            "appops set $pkg SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1",
            "op=\$(appops get $pkg SYSTEM_ALERT_WINDOW 2>/dev/null)",
            "case \"\$op\" in *allow*) echo OVERLAY=ok ;; *) appops set $pkg 24 allow >/dev/null 2>&1; echo OVERLAY=fail ;; esac",
            "exit 0"
        ).joinToString("; ")
    }

    /** 悬浮窗授权兜底命令：部分 ROM 只认数字 op（24 = SYSTEM_ALERT_WINDOW） */
    fun overlayFallback(pkg: String): String? =
        if (validPkg(pkg)) "appops set $pkg 24 allow" else null

    /**
     * 抓取系统日志（logcat）。命令模板写死，tag 是唯一受限参数：
     * 仅允许字母数字下划线点横线、长度 ≤32，为空则不过滤。
     */
    private fun logcatCmd(tag: String?, crash: Boolean): String? {
        val buffer = if (crash) "-b crash " else ""
        val t = tag?.trim().orEmpty()
        val filter = when {
            t.isEmpty() -> ""
            t.matches(Regex("[A-Za-z0-9_.\\\\-]{1,32}")) -> "$t:V *:S"
            else -> return null
        }
        return if (filter.isEmpty()) "logcat $buffer-d -t 3000"
        else "logcat $buffer-d -t 3000 $filter"
    }

    /** 开启无障碍服务：读取当前列表，已包含则跳过，否则追加；最后打开总开关（不覆盖其他无障碍服务） */
    private fun accessibilityScript(pkg: String): String {
        val target = "$pkg/$ACCESSIBILITY_SERVICE"
        return """
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
    }
}
