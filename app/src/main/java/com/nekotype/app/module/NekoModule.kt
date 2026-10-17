package com.nekotype.app.module

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup

/**
 * 模块入口接口：模块包（ZIP）里的 classes.dex 中，由 `module.json` 的 `entryClass`
 * 指定的类必须实现本接口，模块被加载时由宿主实例化并回调 [onLoad]。
 *
 * 模块与宿主同进程、同一条 ClassLoader 链（父加载器即宿主加载器），
 * 因此可以**直接引用宿主任何公开类**，也拥有与宿主相同的权限
 * （无障碍读取的内容、Shizuku 通道、设备管理员等）。
 *
 * 载入失败不会影响宿主：异常会被捕获并显示在「模块管理」里。
 */
interface NekoModule {
    fun onLoad(host: ModuleHost)
    fun onUnload() {}
}

/** 宿主能力句柄：注册钩子 + 模块私有存储 + 日志 */
interface ModuleHost {
    val context: Context
    val moduleId: String

    /** 模块私有 SharedPreferences（每个模块独立文件，卸载时随模块目录一起清掉） */
    val prefs: SharedPreferences

    fun log(msg: String)

    /** 文本变换：返回非 null 即由本模块接管（内置规则引擎不再执行）；按模块加载顺序询问 */
    fun addTextTransformer(f: (text: String, pkg: String) -> String?)

    /** 设置页注入一张卡片：返回要显示的 View */
    fun addSettingsCard(title: String, builder: (Context) -> View)

    /** 启动页装饰：容器为 decorView 的内容区，可加 View、跑动画 */
    fun addSplashDecorator(builder: (Activity, ViewGroup) -> Unit)

    /** 任意 Activity 创建后回调（主界面、设置页等）：想改哪就改哪，含隐藏底部导航、自绘替代导航栏 */
    fun addActivityHook(f: (Activity) -> Unit)

    /** 悬浮菜单项 */
    fun addMenuItem(label: String, onClick: () -> Unit)

    /** 隐藏内置功能入口，键见 [FeatureKeys] */
    fun hideFeature(key: String)

    /** 申请更长的启动页展示时间（300–10000ms，默认 1000ms） */
    fun splashDelay(ms: Long)
}

/** 内置可隐藏的功能入口键（模块用 [ModuleHost.hideFeature] 隐藏） */
object FeatureKeys {
    const val TERMINAL = "terminal"
    const val SPONSOR = "sponsor"
    const val IMPORT_EXPORT = "import_export"
    const val BLACKLIST = "blacklist"
    const val ABOUT = "about"
    const val MODULES = "modules"
}
