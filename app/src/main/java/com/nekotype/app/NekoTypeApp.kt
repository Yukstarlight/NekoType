package com.nekotype.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.nekotype.app.prefs.AppPrefs

class NekoTypeApp : Application() {

    companion object {
        lateinit var instance: NekoTypeApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 应用主题（深色 / 浅色 / 跟随系统）
        val mode = when (AppPrefs.themeMode) {
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
