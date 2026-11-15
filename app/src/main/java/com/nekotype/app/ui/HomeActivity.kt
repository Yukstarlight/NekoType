package com.nekotype.app.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.nekotype.app.R
import com.nekotype.app.databinding.ActivityHomeBinding
import com.nekotype.app.overlay.FloatingButtonService
import com.nekotype.app.prefs.AppPrefs
import com.nekotype.app.sys.SysPower
import kotlin.math.abs
import com.nekotype.app.util.BgUtils
import com.nekotype.app.util.NekoLang
import com.nekotype.app.util.NekoLog
import com.nekotype.app.util.ThemeHelper
import com.nekotype.app.util.setTextSizeDimen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面（重构版）：底部导航栏 + 四个 Fragment
 * - 首页：服务状态 + 权限引导 + 状态总览
 * - 规则：规则预设（带加号）+ 规则列表 + 行为样式 + 效果预览
 * - 模式：篡改键盘模式 / 断句追加模式 / 悬浮球模式
 * - 设置：外观 / 悬浮按钮 / 高级 / 数据 / 关于
 *
 * 保留原有 MainActivity / SettingsActivity 不删减，新入口通过 activity-alias 指向此处。
 */
class HomeActivity : AppCompatActivity() {

    companion object {
        /** 外部停止请求标记（通知栏/磁贴触发，密码锁定验证用） */
        const val EXTRA_STOP_REQUEST = "nekotype_stop_request"

        /** 打开应用自动 Shizuku 授权时的权限请求码 */
        private const val REQ_AUTO_SHIZUKU = 1002

        /** 竞品包名：检测到就劝卸载 */
        private const val RIVAL_PKG_MAOMAO = "com.example.u7e5f3218e9"

        /** NyaNote 的实际包名（实测 love.miao.yun）；另保留应用名匹配兜底 */
        private const val RIVAL_PKG_NYANOTE = "love.miao.yun"
        private const val RIVAL_LABEL_NYANOTE = "NyaNote"

        /** 外部打开指定底部导航 tab（悬浮球快捷菜单「回到应用 / 规则 / 设置」用） */
        const val EXTRA_TAB = "nekotype_tab"
        const val TAB_HOME = "home"
        const val TAB_RULES = "rules"
        const val TAB_MODE = "mode"
        const val TAB_SETTINGS = "settings"
    }

    private lateinit var binding: ActivityHomeBinding

    private val homeFragment by lazy { HomeFragment() }
    private val rulesFragment by lazy { RulesFragment() }
    private val modeFragment by lazy { ModeFragment() }
    private val settingsFragment by lazy { SettingsFragment() }
    private var currentFragment: Fragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // 主题：super 前设置夜间模式
        when (AppPrefs.themeMode) {
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "star" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            "cccp" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            "neko" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
        ThemeHelper.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        BgUtils.apply(binding.root)
        NekoLog.nav("应用启动（重构版底部导航）")

        // 防篡改：签名被改 / Hook 框架 → 拒绝运行并提示（实时校验，不信可被改写的 prefs 标记）
        if (AppPrefs.tampered || !com.nekotype.app.util.TamperGuard.isSignatureValid(this)) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.u36))
                .setMessage(getString(R.string.u45))
                .setPositiveButton(getString(R.string.u62)) { _, _ -> finish() }
                .setCancelable(false)
                .show()
            return
        }

        // 模块钩子：布局完成后回调，模块可替换/改造界面（如隐藏底部导航、自绘导航栏）
        binding.root.post {
            try { com.nekotype.app.module.ModuleHooks.dispatchActivity(this) } catch (_: Throwable) { }
        }

        // 默认显示首页
        if (savedInstanceState == null) {
            switchFragment(homeFragment)
        }

        // 预加载其他 Fragment：首屏显示后延迟创建，避免首次切换卡顿
        binding.root.postDelayed({
            preloadFragments()
        }, 300)

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    switchFragment(homeFragment)
                    NekoLog.nav("底部导航：首页")
                    true
                }
                R.id.nav_rules -> {
                    if (LockGate.isUnlocked()) {
                        switchFragment(rulesFragment)
                        NekoLog.nav("底部导航：规则")
                        true
                    } else {
                        LockGate.require(this, { binding.bottomNav.selectedItemId = item.itemId })
                        false
                    }
                }
                R.id.nav_mode -> {
                    if (LockGate.isUnlocked()) {
                        switchFragment(modeFragment)
                        NekoLog.nav("底部导航：模式")
                        true
                    } else {
                        LockGate.require(this, { binding.bottomNav.selectedItemId = item.itemId })
                        false
                    }
                }
                R.id.nav_settings -> {
                    if (LockGate.isUnlocked()) {
                        switchFragment(settingsFragment)
                        NekoLog.nav("底部导航：设置")
                        true
                    } else {
                        LockGate.require(this, { binding.bottomNav.selectedItemId = item.itemId })
                        false
                    }
                }
                R.id.nav_terminal -> {
                    startActivity(Intent(this, TerminalActivity::class.java))
                    NekoLog.nav("底部导航：终端")
                    // 不切换选中项，返回后保持原 tab
                    false
                }
                else -> false
            }
        }

        // 悬浮球快捷菜单「回到应用」可能带 EXTRA_TAB 指定目标 tab
        if (savedInstanceState == null) applyTabFromIntent(intent)

        // 打开应用自动执行（设置里两个开关，各自独立）
        if (savedInstanceState == null) applyLaunchAutomation()

        // 启动弹窗统一排队：免责声明 → 竞品提示 → 赞助提醒，同一时间只显示一个
        enqueueStartupPrompts()
    }

    // ---------- 启动弹窗队列（免责声明 / 竞品提示 / 赞助提醒互不重叠） ----------

    /** 当前版本名（如 3.0.0）：竞品提示按它记忆，只在版本变化（升级）时再提示一次 */
    private val appVersionName: String by lazy {
        try {
            packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }

    private val promptQueue = java.util.ArrayDeque<() -> Unit>()
    private var promptActive = false

    private fun enqueuePrompt(block: () -> Unit) {
        promptQueue.addLast(block)
        pumpPrompts()
    }

    private fun pumpPrompts() {
        if (promptActive) return
        val next = promptQueue.pollFirst() ?: return
        promptActive = true
        try {
            next()
        } catch (t: Throwable) {
            NekoLog.error("启动弹窗失败：${t.javaClass.simpleName}: ${t.message}")
            finishPrompt()
        }
    }

    private fun finishPrompt() {
        promptActive = false
        pumpPrompts()
    }

    private fun enqueueStartupPrompts() {
        if (!AppPrefs.disclaimerAccepted) {
            enqueuePrompt { showDisclaimerIfNeeded() }
            return
        }
        lifecycleScope.launch {
            val rivals = withContext(Dispatchers.IO) { findRivalApps() }
            for ((pkg, label) in rivals) {
                if (AppPrefs.isRivalPromptShown(pkg, appVersionName)) continue
                enqueuePrompt { showRivalPrompt(pkg, label) }
            }
            enqueuePrompt { showSponsorPrompt() }
        }
    }

    /** 已安装的竞品（包名 + 显示名）：固定包名精确匹配，NyaNote 按应用名匹配 */
    private fun findRivalApps(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        try {
            val pm = packageManager
            for (pkg in listOf(RIVAL_PKG_MAOMAO, RIVAL_PKG_NYANOTE)) {
                if (isInstalled(pkg)) out += pkg to labelOf(pkg)
            }
            val hint = RIVAL_LABEL_NYANOTE.replace(" ", "")
            for (ai in pm.getInstalledApplications(0)) {
                if (ai.packageName == packageName) continue
                if (out.any { it.first == ai.packageName }) continue
                val label = try { pm.getApplicationLabel(ai).toString() } catch (_: Throwable) { "" }
                if (label.replace(" ", "").equals(hint, ignoreCase = true)) {
                    out += ai.packageName to label
                }
            }
        } catch (_: Throwable) { }
        return out
    }

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: Throwable) {
        false
    }

    private fun labelOf(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) {
        pkg
    }

    /** 竞品提示：标题用应用显示名，正文同一套猫娘语气；只提醒一次 */
    private fun showRivalPrompt(pkg: String, label: String) {
        AppPrefs.markRivalPromptShown(pkg, appVersionName)
        NekoLog.info("检测到竞品「$label」($pkg)，提示卸载")
        NekoDialog.builder(this)
            .setTitle(label)
            .setMessage(getString(R.string.rival_msg))
            .setPositiveButton(getString(R.string.rival_uninstall)) { _, _ -> openAppInfo(pkg) }
            .setNegativeButton(getString(R.string.rival_skip), null)
            .setOnDismissListener { finishPrompt() }
            .show()
    }

    private fun openAppInfo(pkg: String) {
        try {
            startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:$pkg"))
            )
        } catch (t: Throwable) {
            NekoLog.error("打开应用信息失败：${t.javaClass.simpleName}")
            toast(getString(R.string.op_failed))
        }
    }

    private fun showSponsorPrompt() {
        if (com.nekotype.app.module.ModuleHooks.isFeatureHidden(com.nekotype.app.module.FeatureKeys.SPONSOR)) {
            finishPrompt()
            return
        }
        val shown = com.nekotype.app.util.SponsorDialog.maybeShow(this) { finishPrompt() }
        if (!shown) finishPrompt()
    }

    /**
     * 打开应用自动化（Shizuku 授权）：
     * 自动 Shizuku 授权所有权限：后台跑一遍 无障碍→设备管理员→免电→悬浮窗，
     * 需 Shizuku 已运行；尚未授权时先请求一次权限（下次打开再自动授权）。
     */
    private fun applyLaunchAutomation() {
        // 自动 Shizuku 授权所有权限
        if (!AppPrefs.autoShizukuGrantOnLaunch) return
        try {
            if (!SysPower.isShizukuAvailable()) {
                NekoLog.warn("自动 Shizuku 授权跳过：Shizuku 未运行")
                return
            }
            if (!SysPower.isShizukuPermissionGranted()) {
                NekoLog.warn("自动 Shizuku 授权：尚未授权，正在请求权限（请在弹窗点允许）")
                try { SysPower.requestShizukuPermission(REQ_AUTO_SHIZUKU) } catch (_: Throwable) { }
                return
            }
            lifecycleScope.launch {
                val steps = withContext(Dispatchers.IO) {
                    try { SysPower.grantAllPermissions() } catch (t: Throwable) { emptyList() }
                }
                val okCount = steps.count { it.success }
                NekoLog.ok("自动 Shizuku 授权完成：成功 $okCount/${steps.size} 项")
                for (s in steps) {
                    if (!s.success) NekoLog.warn("自动授权失败：${s.name}（${s.output.take(60)}）")
                }
            }
        } catch (t: Throwable) {
            NekoLog.warn("自动 Shizuku 授权异常：${t.javaClass.simpleName}: ${t.message}")
        }
    }
    /** 根据外部 Intent 的 EXTRA_TAB 切换到对应底部导航 tab */
    private fun applyTabFromIntent(intent: Intent?) {
        val tab = intent?.getStringExtra(EXTRA_TAB) ?: return
        val target = when (tab) {
            TAB_RULES -> R.id.nav_rules
            TAB_MODE -> R.id.nav_mode
            TAB_SETTINGS -> R.id.nav_settings
            else -> R.id.nav_home
        }
        try {
            val protectedTab = tab == TAB_RULES || tab == TAB_MODE || tab == TAB_SETTINGS
            if (protectedTab && !LockGate.isUnlocked()) {
                // 外部 Intent 指定受保护页：先过密码门，通过后再切
                LockGate.require(this, { binding.bottomNav.selectedItemId = target })
                return
            }
            if (binding.bottomNav.selectedItemId != target) {
                binding.bottomNav.selectedItemId = target
            } else {
                switchFragment(when (tab) {
                    TAB_RULES -> rulesFragment
                    TAB_MODE -> modeFragment
                    TAB_SETTINGS -> settingsFragment
                    else -> homeFragment
                })
            }
        } catch (_: Throwable) { }
    }

    /** 首次进入免责声明：同意按钮需等待 10 秒倒计时；不同意直接退出应用 */
    private fun showDisclaimerIfNeeded() {
        if (AppPrefs.disclaimerAccepted) return
        // 正文较长 → 套滚动 + Material 副标题样式，避免在小屏上被截断
        val tvMsg = android.widget.TextView(this).apply {
            text = getString(R.string.i47)
            setTextSizeDimen(R.dimen.ts_13)
            // 取主题的 colorOnSurfaceVariant（不依赖 ThemeHelper，避免主题差异导致取不到色）
            val tv = android.util.TypedValue()
            val ok = theme.resolveAttribute(
                com.google.android.material.R.attr.colorOnSurfaceVariant, tv, true
            )
            setTextColor(
                if (ok && tv.resourceId != 0) androidx.core.content.ContextCompat.getColor(this@HomeActivity, tv.resourceId)
                else tv.data
            )
            setLineSpacing(0f, 1.35f)
        }
        // 正文下方放三份法律文档入口（隐私政策 / 第三方信息共享清单 / 开源许可）
        val divider = { android.view.View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1
            )
            setBackgroundColor(0x1F888888)
        } }
        val content = NekoDialog.scroll(
            NekoDialog.column(
                this,
                tvMsg,
                divider(),
                NekoDialog.row(this, getString(R.string.i55)) { showLegalDoc(getString(R.string.i55), LegalDocs.privacy()) },
                divider(),
                NekoDialog.row(this, getString(R.string.i56)) { showLegalDoc(getString(R.string.i56), LegalDocs.thirdParty()) },
                divider(),
                NekoDialog.row(this, getString(R.string.i57)) { showLegalDoc(getString(R.string.i57), LegalDocs.licenses()) }
            )
        )
        val dialog = NekoDialog.builder(this)
            .setTitle(getString(R.string.i46))
            .setView(content)
            .setPositiveButton(getString(R.string.i50, 10)) { _, _ ->
                AppPrefs.disclaimerAccepted = true
                NekoLog.adjust("用户已同意免责声明")
            }
            .setNegativeButton(getString(R.string.i49)) { _, _ ->
                NekoLog.warn("用户拒绝免责声明，退出应用")
                finishAffinity()
                android.os.Process.killProcess(android.os.Process.myPid())
            }
            .setCancelable(false)
            .create()
        dialog.setOnDismissListener {
            // 同意后继续弹后续公告；拒绝会直接结束进程
            if (AppPrefs.disclaimerAccepted) finishPrompt()
        }
        dialog.setOnShowListener {
            val btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.isEnabled = false
            object : android.os.CountDownTimer(10_000L, 1_000L) {
                override fun onTick(ms: Long) {
                    btn.text = getString(R.string.i50, (ms / 1000L).toInt())
                }
                override fun onFinish() {
                    btn.isEnabled = true
                    btn.text = getString(R.string.i48)
                }
            }.start()
        }
        dialog.show()
    }

    /** 法律文档展示（隐私政策 / 第三方清单 / 开源许可） */
    private fun showLegalDoc(title: String, body: String) = NekoDialog.showDoc(this, title, body)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 快捷菜单「回到应用」可能带 EXTRA_TAB：复用同一实例时也要切 tab
        applyTabFromIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        // 退到后台即上锁，防止他人接管屏幕后继续改设置
        LockGate.onAppBackground()
    }

    override fun onResume() {
        super.onResume()
        // 退过后台上锁：受保护的 tab 不能原样停留，退回首页重新走密码门
        if (!LockGate.isUnlocked() && binding.bottomNav.selectedItemId in
            setOf(R.id.nav_rules, R.id.nav_mode, R.id.nav_settings)
        ) {
            binding.bottomNav.selectedItemId = R.id.nav_home
        }
        BgUtils.apply(binding.root)
        NekoLang.apply(binding.root)
        applyNekoBottomNav()
        // Shizuku 通道前台补绑：应用启动时 binder 可能还没送达（ContentProvider 先于 Application 初始化），
        // 回到前台时再确保一次绑定；已连接/绑定中会直接返回，开销可忽略
        try { com.nekotype.app.sys.SysPower.bindShellService() } catch (_: Throwable) { }
        // 外部停止请求（通知栏/磁贴）：密码锁定验证后才允许停止
        if (intent?.getBooleanExtra(EXTRA_STOP_REQUEST, false) == true) {
            intent?.removeExtra(EXTRA_STOP_REQUEST)
            handleExternalStopRequest()
        }
    }

    /** 通知栏/磁贴发起的停止请求：锁定开启时验证密码；隐藏模式下验证通过后恢复图标 */
    private fun handleExternalStopRequest() {
        if (AppPrefs.lockEnabled) {
            NekoLog.info("外部停止请求：需要验证密码")
            showVerifyLockPasswordDialog(getString(R.string.u68)) {
                AppPrefs.serviceEnabled = false
                FloatingButtonService.stop(this)
                toast(getString(R.string.u17))
            }
        } else {
            AppPrefs.serviceEnabled = false
            FloatingButtonService.stop(this)
        }
    }

    private fun showVerifyLockPasswordDialog(title: String, onOk: () -> Unit) {
        // Material 密码输入框
        val inPwd = NekoDialog.input(this, getString(R.string.u75), password = true)
        val box = NekoDialog.column(this, inPwd)
        val dialog = NekoDialog.builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(getString(R.string.u71), null)
            .setNegativeButton(getString(R.string.u72), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (AppPrefs.verifyLockPassword(NekoDialog.textOf(inPwd))) {
                    dialog.dismiss()
                    onOk()
                } else {
                    toast(getString(R.string.u25))
                    inPwd.editText?.text?.clear()
                }
            }
        }
        dialog.show()
    }

    private fun switchFragment(fragment: Fragment) {
        if (currentFragment === fragment) return
        try {
            val transaction = supportFragmentManager.beginTransaction()
            // iOS 风格切换：弹性缩放 + 淡入
            transaction.setCustomAnimations(R.anim.fragment_enter, R.anim.fragment_exit)
            // 隐藏当前
            currentFragment?.let { transaction.hide(it) }
            // 显示目标（已添加则 show，未添加则 add）
            if (fragment.isAdded) {
                transaction.show(fragment)
            } else {
                transaction.add(R.id.fragment_container, fragment)
            }
            transaction.commitAllowingStateLoss()
            // 同步执行：布局/加载异常在此暴露并被捕获，避免异步闪退
            supportFragmentManager.executePendingTransactions()
            currentFragment = fragment
        } catch (_: Throwable) {
            // Fragment 切换失败：回退到首页，绝不闪退
            NekoLog.error("Fragment 切换失败，回退首页")
            try {
                currentFragment = homeFragment
                val t = supportFragmentManager.beginTransaction()
                listOf(rulesFragment, modeFragment, settingsFragment).forEach { f ->
                    if (f.isAdded) t.hide(f)
                }
                if (homeFragment.isAdded) t.show(homeFragment) else t.add(R.id.fragment_container, homeFragment)
                t.commitAllowingStateLoss()
            } catch (_: Throwable) { }
        }
        // 切换时刷新背景（hide/show 不触发 onResume，需手动刷新，确保清除壁纸后立刻生效）
        fragment.view?.let { BgUtils.apply(it) }
        // 动画期间启用硬件加速层，保证 60fps 流畅；动画结束后释放
        fragment.view?.let { view ->
            view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            view.postDelayed({
                view.setLayerType(View.LAYER_TYPE_NONE, null)
            }, 300)
        }
    }

    /** 预加载其他 Fragment：提前 add 并 hide，后续切换只需 show，消除首次切换卡顿 */
    private fun preloadFragments() {
        try {
            val transaction = supportFragmentManager.beginTransaction()
            listOf(rulesFragment, modeFragment, settingsFragment).forEach { f ->
                if (!f.isAdded) {
                    transaction.add(R.id.fragment_container, f).hide(f)
                }
            }
            transaction.commitAllowingStateLoss()
            // 同步执行：让 Fragment 的 onCreateView/onViewCreated 在当前线程执行，
            // 这样外层 try-catch 才能真正捕获生命周期内的崩溃，避免异步闪退
            supportFragmentManager.executePendingTransactions()
        } catch (e: Throwable) {
            NekoLog.error("预加载 Fragment 失败：${e.javaClass.simpleName}: ${e.message}")
            // 预加载失败不影响首页使用，后续切换时再按需创建
        }
    }

    /** 供 Fragment 调用：跳转到指定 tab */
    fun navigateTo(tabId: Int) {
        binding.bottomNav.selectedItemId = tabId
    }

    /** 供 Fragment 调用：打开旧版设置页（保留入口） */
    fun openLegacySettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    /** 供 Fragment 调用：打开旧版主页（保留入口） */
    fun openLegacyMain() {
        startActivity(Intent(this, MainActivity::class.java))
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** 猫娘主题：底部导航栏换成猫咪头像 + 文字加喵~ */
    private fun applyNekoBottomNav() {
        try {
        val menu = binding.bottomNav.menu
        val isNekoTheme = AppPrefs.themeMode == "neko"
        if (isNekoTheme) {
            // 猫娘主题：彩色猫咪头像，取消统一着色
            binding.bottomNav.itemIconTintList = null
            menu.findItem(R.id.nav_home)?.setIcon(R.drawable.ic_cat_home)
            menu.findItem(R.id.nav_rules)?.setIcon(R.drawable.ic_cat_rules)
            menu.findItem(R.id.nav_mode)?.setIcon(R.drawable.ic_cat_mode)
            menu.findItem(R.id.nav_settings)?.setIcon(R.drawable.ic_cat_settings)
            menu.findItem(R.id.nav_terminal)?.setIcon(R.drawable.ic_cat_terminal)
        } else {
            // 其他主题：恢复默认图标和着色
            binding.bottomNav.itemIconTintList = ContextCompat.getColorStateList(this, R.color.bottom_nav_color)
            menu.findItem(R.id.nav_home)?.setIcon(R.drawable.ic_nav_home)
            menu.findItem(R.id.nav_rules)?.setIcon(R.drawable.ic_nav_rules)
            menu.findItem(R.id.nav_mode)?.setIcon(R.drawable.ic_nav_mode)
            menu.findItem(R.id.nav_settings)?.setIcon(R.drawable.ic_nav_settings)
            menu.findItem(R.id.nav_terminal)?.setIcon(R.drawable.ic_nav_terminal)
        }
        // 猫娘语言模式：文字加喵~
        if (NekoLang.enabled) {
            for (i in 0 until menu.size()) {
                val item = menu.getItem(i)
                val title = item.title?.toString() ?: continue
                if (!title.endsWith("喵~")) {
                    item.title = title + "喵~"
                }
            }
        }
        } catch (_: Throwable) {
            // 兜底：任何图标/着色异常都回退默认图标与着色，绝不因主题切换闪退
            try {
                binding.bottomNav.itemIconTintList =
                    ContextCompat.getColorStateList(this, R.color.bottom_nav_color)
                val menu = binding.bottomNav.menu
                menu.findItem(R.id.nav_home)?.setIcon(R.drawable.ic_nav_home)
                menu.findItem(R.id.nav_rules)?.setIcon(R.drawable.ic_nav_rules)
                menu.findItem(R.id.nav_mode)?.setIcon(R.drawable.ic_nav_mode)
                menu.findItem(R.id.nav_settings)?.setIcon(R.drawable.ic_nav_settings)
                menu.findItem(R.id.nav_terminal)?.setIcon(R.drawable.ic_nav_terminal)
            } catch (_: Throwable) { }
        }
    }
}
