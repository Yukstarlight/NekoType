package com.nekotype.app.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.nekotype.app.R
import com.nekotype.app.databinding.ActivityHomeBinding
import com.nekotype.app.overlay.FloatingButtonService
import com.nekotype.app.prefs.AppPrefs
import com.nekotype.app.sys.SysPower
import com.nekotype.app.util.BgUtils
import com.nekotype.app.util.NekoLog
import com.nekotype.app.util.ThemeHelper

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
    }

    private lateinit var binding: ActivityHomeBinding

    private val homeFragment by lazy { HomeFragment() }
    private val rulesFragment by lazy { RulesFragment() }
    private val modeFragment by lazy { ModeFragment() }
    private val settingsFragment by lazy { SettingsFragment() }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 主题：super 前设置夜间模式
        when (AppPrefs.themeMode) {
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "star" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
        ThemeHelper.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        BgUtils.apply(binding.root)
        NekoLog.nav("应用启动（重构版底部导航）")

        // 默认显示首页
        if (savedInstanceState == null) {
            switchFragment(homeFragment)
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    switchFragment(homeFragment)
                    NekoLog.nav("底部导航：首页")
                    true
                }
                R.id.nav_rules -> {
                    switchFragment(rulesFragment)
                    NekoLog.nav("底部导航：规则")
                    true
                }
                R.id.nav_mode -> {
                    switchFragment(modeFragment)
                    NekoLog.nav("底部导航：模式")
                    true
                }
                R.id.nav_settings -> {
                    switchFragment(settingsFragment)
                    NekoLog.nav("底部导航：设置")
                    true
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        BgUtils.apply(binding.root)
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
                if (AppPrefs.hiddenModeEnabled) {
                    AppPrefs.hiddenModeEnabled = false
                    SysPower.setHiddenMode(false)
                    SysPower.setUninstallBlockedByAdmin(false)
                    NekoLog.adjust("隐藏模式已关闭，桌面图标已恢复")
                }
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
        val et = EditText(this).apply {
            hint = getString(R.string.u75)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 8)
            addView(et)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(getString(R.string.u71), null)
            .setNegativeButton(getString(R.string.u72), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (AppPrefs.verifyLockPassword(et.text.toString())) {
                    dialog.dismiss()
                    onOk()
                } else {
                    toast(getString(R.string.u25))
                    et.text.clear()
                }
            }
        }
        dialog.show()
    }

    private fun switchFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commitAllowingStateLoss()
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
}
