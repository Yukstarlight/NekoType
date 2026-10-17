package com.nekotype.app.module

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup

/** 已加载模块注册的钩子汇总。宿主各处调用这里；没有模块时开销近似一次空列表判断。 */
object ModuleHooks {

    data class SettingsCard(val moduleId: String, val title: String, val builder: (Context) -> View)
    data class MenuItem(val moduleId: String, val label: String, val onClick: () -> Unit)

    private val textTransformers = mutableListOf<Pair<String, (String, String) -> String?>>()
    private val settingsCards = mutableListOf<SettingsCard>()
    private val splashDecorators = mutableListOf<Pair<String, (Activity, ViewGroup) -> Unit>>()
    private val activityHooks = mutableListOf<Pair<String, (Activity) -> Unit>>()
    private val menuItems = mutableListOf<MenuItem>()
    private val hiddenFeatures = mutableSetOf<String>()

    @Volatile
    var splashDelayMs: Long = 1000L
        private set

    val hasHooks: Boolean
        get() = synchronized(this) {
            textTransformers.isNotEmpty() || settingsCards.isNotEmpty() ||
                    splashDecorators.isNotEmpty() || activityHooks.isNotEmpty() || menuItems.isNotEmpty()
        }

    val hasTextTransformers: Boolean get() = synchronized(this) { textTransformers.isNotEmpty() }

    @Synchronized
    fun addTextTransformer(moduleId: String, f: (String, String) -> String?) {
        textTransformers += moduleId to f
    }

    @Synchronized
    fun addSettingsCard(moduleId: String, title: String, builder: (Context) -> View) {
        settingsCards += SettingsCard(moduleId, title, builder)
    }

    @Synchronized
    fun addSplashDecorator(moduleId: String, f: (Activity, ViewGroup) -> Unit) {
        splashDecorators += moduleId to f
    }

    @Synchronized
    fun addActivityHook(moduleId: String, f: (Activity) -> Unit) {
        activityHooks += moduleId to f
    }

    @Synchronized
    fun addMenuItem(moduleId: String, label: String, onClick: () -> Unit) {
        menuItems += MenuItem(moduleId, label, onClick)
    }

    @Synchronized
    fun hideFeature(key: String) {
        hiddenFeatures += key
    }

    @Synchronized
    fun isFeatureHidden(key: String): Boolean = key in hiddenFeatures

    /** 依次询问模块：第一个返回非 null 的接管文本变换 */
    fun transformText(text: String, pkg: String): String? {
        val snapshot = synchronized(this) { textTransformers.toList() }
        for ((_, f) in snapshot) {
            try {
                f(text, pkg)?.let { return it }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    fun settingsCards(): List<SettingsCard> = synchronized(this) { settingsCards.toList() }

    fun menuItems(): List<MenuItem> = synchronized(this) { menuItems.toList() }

    fun dispatchActivity(activity: Activity) {
        val snapshot = synchronized(this) { activityHooks.toList() }
        for ((_, f) in snapshot) {
            try {
                f(activity)
            } catch (_: Throwable) {
            }
        }
    }

    fun dispatchSplash(activity: Activity, root: ViewGroup) {
        val snapshot = synchronized(this) { splashDecorators.toList() }
        for ((_, f) in snapshot) {
            try {
                f(activity, root)
            } catch (_: Throwable) {
            }
        }
    }

    @Synchronized
    fun splashDelay(value: Long) {
        if (value in 300L..10_000L) splashDelayMs = value
    }

    @Synchronized
    fun clearModule(moduleId: String) {
        textTransformers.removeAll { it.first == moduleId }
        settingsCards.removeAll { it.moduleId == moduleId }
        splashDecorators.removeAll { it.first == moduleId }
        activityHooks.removeAll { it.first == moduleId }
        menuItems.removeAll { it.moduleId == moduleId }
    }

    @Synchronized
    fun clearAll() {
        textTransformers.clear()
        settingsCards.clear()
        splashDecorators.clear()
        activityHooks.clear()
        menuItems.clear()
        hiddenFeatures.clear()
        splashDelayMs = 1000L
    }
}
