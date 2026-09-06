package com.nekotype.app.util

import android.app.Activity
import com.nekotype.app.R
import com.nekotype.app.prefs.AppPrefs

/**
 * 主题辅助：在 Activity.super.onCreate() 之前调用 apply()，
 * 确保星空主题的 windowBackground / colorPrimary 等属性在视图创建前生效。
 */
object ThemeHelper {
    fun apply(activity: Activity) {
        if (AppPrefs.themeMode == "star") {
            activity.setTheme(R.style.Theme_NekoType_Star)
        }
    }
}
