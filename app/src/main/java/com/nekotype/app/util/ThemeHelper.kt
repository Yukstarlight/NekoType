package com.nekotype.app.util

import android.app.Activity
import androidx.appcompat.app.AppCompatDelegate
import com.nekotype.app.R
import com.nekotype.app.prefs.AppPrefs

/**
 * 主题辅助：在 Activity.super.onCreate() 之前调用 apply()，
 * 确保星空主题的 windowBackground / colorPrimary 等属性在视图创建前生效。
 * 同时统一设置夜间模式，避免猫娘主题在系统深色模式下文字变白。
 */
object ThemeHelper {
    fun apply(activity: Activity) {
        // 统一设置夜间模式：猫娘主题是浅色，强制浅色模式；星空/深色用深色模式
        when (AppPrefs.themeMode) {
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "star" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            "cccp" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            "neko" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
        when (AppPrefs.themeMode) {
            "star" -> activity.setTheme(R.style.Theme_NekoType_Star)
            "cccp" -> activity.setTheme(R.style.Theme_NekoType_Cccp)
            "neko" -> activity.setTheme(R.style.Theme_NekoType_Neko)
        }
    }
}
