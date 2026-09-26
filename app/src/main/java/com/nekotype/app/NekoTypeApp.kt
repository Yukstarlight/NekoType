package com.nekotype.app

import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import com.nekotype.app.overlay.FloatingButtonService
import com.nekotype.app.prefs.AppPrefs
import com.nekotype.app.util.TamperGuard

class NekoTypeApp : Application() {

    companion object {
        lateinit var instance: NekoTypeApp
            private set
    }

    /** 系统默认崩溃处理器（崩溃自启后继续走系统逻辑，不吞崩溃） */
    private val originalHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 启动次数 +1（进程启动即计一次），用于里程碑赞助提醒
        try { AppPrefs.launchCount = AppPrefs.launchCount + 1 } catch (_: Throwable) { }
        // 初始化 Shizuku 长连接通道（注册 Binder 监听 + 预绑定 UserService）
        com.nekotype.app.sys.SysPower.initShizukuChannel()
        // 防篡改检测：签名不匹配（重打包）或检测到 Hook 框架 → 标记，各入口拒绝运行
        val tampered = !TamperGuard.isSignatureValid(this) || TamperGuard.hasHookFramework()
        if (tampered != AppPrefs.tampered) {
            AppPrefs.tampered = tampered
        }
        // 崩溃自启（行为与样式开关控制）：进程崩溃时若服务在跑，
        // 用闹钟延时拉起服务，避免一次崩溃导致悬浮服务/隐藏模式永久失联
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                if (AppPrefs.crashRestartEnabled && AppPrefs.serviceEnabled) {
                    val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                    // 闹钟触发拉起 FGS（Android 12 豁免场景），华为拦截概率更低
                    val pi = if (android.os.Build.VERSION.SDK_INT >= 26) {
                        PendingIntent.getForegroundService(
                            this, 2002,
                            Intent(this, FloatingButtonService::class.java),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    } else {
                        PendingIntent.getService(
                            this, 2002,
                            Intent(this, FloatingButtonService::class.java),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    }
                    am.set(AlarmManager.RTC, System.currentTimeMillis() + 300, pi)
                }
            } catch (_: Throwable) { }
            // 继续走系统默认处理（不吞崩溃，正常退出）
            originalHandler?.uncaughtException(thread, throwable)
        }
        // 语言：AppCompat 在 Android 12 及以下不会自动记住选择，这里启动时重新应用
        applySavedLocale()
        // 应用主题（深色 / 浅色 / 跟随系统 / 星空）
        applyAppTheme()
        // 星空主题需要在每个 Activity 创建前 setTheme
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                try {
                    when (AppPrefs.themeMode) {
                        "star" -> activity.setTheme(R.style.Theme_NekoType_Star)
                        "cccp" -> activity.setTheme(R.style.Theme_NekoType_Cccp)
            "neko" -> activity.setTheme(R.style.Theme_NekoType_Neko)
                    }
                } catch (_: Throwable) { /* 主题应用失败不阻断页面 */ }
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * 启动时应用用户上次选择的语言。
     *
     * [AppCompatDelegate.setApplicationLocales] 在 Android 13+ 由系统持久化，
     * 但 Android 12 及以下需要自行存储；否则杀掉进程重开后语言会回到系统默认。
     * 与当前值相同时不重复设置，避免多余的 Activity 重建。
     */
    private fun applySavedLocale() {
        try {
            val tag = AppPrefs.appLangTag
            val list = if (tag.isEmpty()) {
                androidx.core.os.LocaleListCompat.getEmptyLocaleList()
            } else {
                androidx.core.os.LocaleListCompat.forLanguageTags(tag)
            }
            if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != list.toLanguageTags()) {
                AppCompatDelegate.setApplicationLocales(list)
            }
        } catch (_: Throwable) { /* 语言应用失败不阻断启动 */ }
    }

    /** 根据 themeMode 设置夜间模式（星空/猫娘主题走深色） */
    fun applyAppTheme() {
        val mode = when (AppPrefs.themeMode) {
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "star" -> AppCompatDelegate.MODE_NIGHT_YES
            "cccp" -> AppCompatDelegate.MODE_NIGHT_YES
            "neko" -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
