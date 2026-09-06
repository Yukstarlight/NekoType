package com.nekotype.app.ui

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.nekotype.app.R
import com.nekotype.app.databinding.FragmentModeBinding
import com.nekotype.app.overlay.FloatingButtonService
import com.nekotype.app.prefs.AppPrefs
import com.nekotype.app.sys.SysPower
import com.nekotype.app.util.BgUtils
import com.nekotype.app.util.NekoLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 模式页：三种运行模式互斥选择
 * - 悬浮球模式：forceKeyboard = false（默认）
 * - 篡改键盘模式：forceKeyboard = true, punctTrigger = false
 * - 断句追加模式：forceKeyboard = true, punctTrigger = true
 *
 * 同时包含安全与保活选项：密码锁定、隐藏模式、心跳保活、崩溃自启。
 * 从 MainActivity 迁移而来，保留全部功能。
 */
class ModeFragment : Fragment() {

    private var _binding: FragmentModeBinding? = null
    private val binding get() = _binding!!

    /** 程序化恢复开关状态时置位，避免触发弹窗 */
    private var restoringSwitch = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentModeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        BgUtils.apply(binding.root)

        // 模式选择
        binding.btnSelectFab.setOnClickListener { selectFabMode() }
        binding.btnSelectForce.setOnClickListener { selectForceKeyboardMode() }
        binding.btnSelectPunct.setOnClickListener { selectPunctAppendMode() }
        binding.cardModeFab.setOnClickListener { selectFabMode() }
        binding.cardModeForce.setOnClickListener { selectForceKeyboardMode() }
        binding.cardModePunct.setOnClickListener { selectPunctAppendMode() }

        // 安全选项
        binding.swLock.setOnCheckedChangeListener { _, v ->
            if (restoringSwitch) return@setOnCheckedChangeListener
            if (v) onLockEnableRequested() else onLockDisableRequested()
        }
        binding.swHiddenMode.setOnCheckedChangeListener { _, v ->
            if (restoringSwitch) return@setOnCheckedChangeListener
            if (v) {
                if (!AppPrefs.lockEnabled) {
                    toast(getString(R.string.u46))
                    restoreHiddenSwitch()
                    return@setOnCheckedChangeListener
                }
                showVerifyLockPasswordDialog(
                    getString(R.string.u65),
                    onOk = { applyHiddenMode(true) },
                    onCancel = { restoreHiddenSwitch() }
                )
            } else {
                showVerifyLockPasswordDialog(
                    getString(R.string.u66),
                    onOk = { applyHiddenMode(false) },
                    onCancel = { restoreHiddenSwitch() }
                )
            }
        }
        binding.swHeartbeat.setOnCheckedChangeListener { _, v ->
            if (restoringSwitch) return@setOnCheckedChangeListener
            if (!v && AppPrefs.lockEnabled) {
                showVerifyLockPasswordDialog(
                    getString(R.string.u67),
                    onOk = { applyHeartbeat(false) },
                    onCancel = { restoreHeartbeatSwitch() }
                )
                return@setOnCheckedChangeListener
            }
            applyHeartbeat(v)
        }
        binding.swCrashRestart.setOnCheckedChangeListener { _, v ->
            AppPrefs.crashRestartEnabled = v
            NekoLog.adjust(if (v) "开启崩溃自启" else "关闭崩溃自启")
        }

        refreshModeUI()
    }

    override fun onResume() {
        super.onResume()
        BgUtils.apply(binding.root)
        refreshModeUI()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ---------- 模式切换 ----------

    private fun currentModeName(): String = when {
        !AppPrefs.forceKeyboardEnabled -> getString(R.string.mode_fab)
        AppPrefs.punctTriggerEnabled -> getString(R.string.mode_punct_append)
        else -> getString(R.string.mode_force_keyboard)
    }

    private fun refreshModeUI() {
        binding.tvCurrentMode.text = currentModeName()

        val isFab = !AppPrefs.forceKeyboardEnabled
        val isForce = AppPrefs.forceKeyboardEnabled && !AppPrefs.punctTriggerEnabled
        val isPunct = AppPrefs.forceKeyboardEnabled && AppPrefs.punctTriggerEnabled

        binding.cardModeFab.strokeColor = if (isFab)
            resources.getColor(R.color.md_theme_primary, null)
        else
            resources.getColor(R.color.divider, null)
        binding.cardModeForce.strokeColor = if (isForce)
            resources.getColor(R.color.md_theme_primary, null)
        else
            resources.getColor(R.color.divider, null)
        binding.cardModePunct.strokeColor = if (isPunct)
            resources.getColor(R.color.md_theme_primary, null)
        else
            resources.getColor(R.color.divider, null)

        binding.btnSelectFab.text = if (isFab) "✓" else getString(R.string.u71)
        binding.btnSelectForce.text = if (isForce) "✓" else getString(R.string.u71)
        binding.btnSelectPunct.text = if (isPunct) "✓" else getString(R.string.u71)

        restoringSwitch = true
        binding.swLock.isChecked = AppPrefs.lockEnabled
        binding.swHiddenMode.isChecked = AppPrefs.hiddenModeEnabled
        binding.swHeartbeat.isChecked = AppPrefs.heartbeatEnabled
        binding.swCrashRestart.isChecked = AppPrefs.crashRestartEnabled
        restoringSwitch = false
    }

    /** 悬浮球模式 */
    private fun selectFabMode() {
        if (!AppPrefs.forceKeyboardEnabled) {
            toast(getString(R.string.mode_applied, getString(R.string.mode_fab)))
            return
        }
        // 密码锁定时关闭强制篡改需验证
        if (AppPrefs.lockEnabled) {
            showVerifyLockPasswordDialog(
                getString(R.string.u64),
                onOk = { doSelectFabMode() },
                onCancel = { }
            )
            return
        }
        doSelectFabMode()
    }

    private fun doSelectFabMode() {
        AppPrefs.forceKeyboardEnabled = false
        AppPrefs.punctTriggerEnabled = false
        FloatingButtonService.reload()
        NekoLog.adjust("切换到悬浮球模式")
        toast(getString(R.string.mode_fab_restored))
        refreshModeUI()
    }

    /** 篡改键盘模式 */
    private fun selectForceKeyboardMode() {
        if (AppPrefs.forceKeyboardEnabled && !AppPrefs.punctTriggerEnabled) {
            toast(getString(R.string.mode_applied, getString(R.string.mode_force_keyboard)))
            return
        }
        // 密码锁定时开启强制篡改需验证
        if (AppPrefs.lockEnabled) {
            showVerifyLockPasswordDialog(
                getString(R.string.u63),
                onOk = { showForceDisclaimer(false) },
                onCancel = { }
            )
            return
        }
        showForceDisclaimer(false)
    }

    /** 断句追加模式 */
    private fun selectPunctAppendMode() {
        if (AppPrefs.forceKeyboardEnabled && AppPrefs.punctTriggerEnabled) {
            toast(getString(R.string.mode_applied, getString(R.string.mode_punct_append)))
            return
        }
        if (AppPrefs.lockEnabled) {
            showVerifyLockPasswordDialog(
                getString(R.string.u63),
                onOk = { showForceDisclaimer(true) },
                onCancel = { }
            )
            return
        }
        showForceDisclaimer(true)
    }

    /** 强制篡改免责声明（punctMode=true 则断句追加模式） */
    private fun showForceDisclaimer(punctMode: Boolean) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.mode_force_disclaimer_title))
            .setMessage(getString(R.string.mode_force_disclaimer))
            .setPositiveButton(getString(R.string.mode_agree)) { _, _ ->
                AppPrefs.forceKeyboardEnabled = true
                AppPrefs.punctTriggerEnabled = punctMode
                if (AppPrefs.serviceEnabled) {
                    FloatingButtonService.reload()
                }
                val modeName = if (punctMode) getString(R.string.mode_punct_append) else getString(R.string.mode_force_keyboard)
                NekoLog.adjust("切换到$modeName")
                toast(if (AppPrefs.serviceEnabled) getString(R.string.mode_force_enabled) else getString(R.string.mode_force_pending))
                refreshModeUI()
            }
            .setNegativeButton(getString(R.string.u72), null)
            .show()
    }

    // ---------- 密码锁定 ----------

    private fun onLockEnableRequested() {
        if (AppPrefs.hasLockPassword()) {
            showVerifyLockPasswordDialog(getString(R.string.u69)) { enableLock() }
        } else {
            showSetLockPasswordDialog()
        }
    }

    private fun onLockDisableRequested() {
        showVerifyLockPasswordDialog(getString(R.string.u70)) { disableLock() }
    }

    private fun enableLock() {
        AppPrefs.lockEnabled = true
        AppPrefs.autoStartEnabled = true
        NekoLog.adjust("密码锁定已开启（开机自启已联动开启）")
        toast(getString(R.string.u59))
        refreshModeUI()
        askHiddenMode()
    }

    private fun askHiddenMode() {
        if (AppPrefs.hiddenModeEnabled) return
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.u10))
            .setMessage(getString(R.string.u114))
            .setPositiveButton(getString(R.string.u112)) { _, _ -> applyHiddenMode(true) }
            .setNegativeButton(getString(R.string.u113), null)
            .show()
    }

    private fun applyHiddenMode(enabled: Boolean) {
        val ctx = context ?: return
        if (enabled) {
            if (!AppPrefs.serviceEnabled || !android.provider.Settings.canDrawOverlays(ctx)) {
                toast(getString(R.string.u2))
                restoreHiddenSwitch()
                return
            }
            if (!SysPower.isDeviceAdminActive()) {
                toast(getString(R.string.u54))
                restoreHiddenSwitch()
                return
            }
        }
        AppPrefs.hiddenModeEnabled = enabled
        SysPower.setUninstallBlockedByAdmin(enabled)
        if (enabled) {
            if (!AppPrefs.heartbeatEnabled) {
                AppPrefs.heartbeatEnabled = true
                FloatingButtonService.start(ctx)
            }
            if (SysPower.privilegedChannelReady()) {
                lifecycleScope.launch {
                    val r = withContext(Dispatchers.IO) { SysPower.shizukuHideSelf(true) }
                    if (!r.success) SysPower.setHiddenMode(true)
                }
            } else {
                SysPower.setHiddenMode(true)
            }
            toast(getString(R.string.u50))
        } else {
            if (SysPower.privilegedChannelReady()) {
                lifecycleScope.launch { withContext(Dispatchers.IO) { SysPower.shizukuHideSelf(false) } }
            } else {
                SysPower.setHiddenMode(false)
            }
            toast(getString(R.string.u51))
        }
        refreshModeUI()
    }

    private fun disableLock() {
        AppPrefs.lockEnabled = false
        if (AppPrefs.hiddenModeEnabled) {
            AppPrefs.hiddenModeEnabled = false
            SysPower.setHiddenMode(false)
            SysPower.setUninstallBlockedByAdmin(false)
            toast(getString(R.string.u51))
        }
        toast(getString(R.string.u34))
        refreshModeUI()
    }

    private fun showSetLockPasswordDialog() {
        val et = EditText(requireContext()).apply {
            hint = getString(R.string.u73)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val et2 = EditText(requireContext()).apply {
            hint = getString(R.string.u74)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 8)
            addView(et)
            addView(et2)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.u18))
            .setMessage(getString(R.string.u12))
            .setView(box)
            .setPositiveButton(getString(R.string.u71)) { _, _ ->
                val p1 = et.text.toString()
                val p2 = et2.text.toString()
                if (p1.isEmpty()) { toast(getString(R.string.u7)); restoreLockSwitch(); return@setPositiveButton }
                if (p1 != p2) { toast(getString(R.string.u11)); restoreLockSwitch(); return@setPositiveButton }
                AppPrefs.setLockPassword(p1)
                enableLock()
            }
            .setNegativeButton(getString(R.string.u72)) { _, _ -> restoreLockSwitch() }
            .setOnCancelListener { restoreLockSwitch() }
            .show()
    }

    private fun showVerifyLockPasswordDialog(
        title: String,
        onCancel: (() -> Unit)? = null,
        onOk: () -> Unit
    ) {
        val et = EditText(requireContext()).apply {
            hint = getString(R.string.u75)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 8)
            addView(et)
        }
        val cancelAction = { (onCancel ?: { }).invoke() }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(box)
            .setPositiveButton(getString(R.string.u71), null)
            .setNegativeButton(getString(R.string.u72)) { _, _ -> cancelAction() }
            .setOnCancelListener { cancelAction() }
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

    private fun applyHeartbeat(enabled: Boolean) {
        AppPrefs.heartbeatEnabled = enabled
        NekoLog.adjust(if (enabled) "开启心跳保活" else "关闭心跳保活")
        val ctx = context
        if (enabled && AppPrefs.serviceEnabled && ctx != null) {
            FloatingButtonService.start(ctx)
        } else if (!enabled && ctx != null) {
            FloatingButtonService.cancelHeartbeatGlobal(ctx)
        }
    }

    // ---------- 恢复开关 ----------

    private fun restoreLockSwitch() {
        restoringSwitch = true
        binding.swLock.isChecked = AppPrefs.lockEnabled
        restoringSwitch = false
    }

    private fun restoreHiddenSwitch() {
        restoringSwitch = true
        binding.swHiddenMode.isChecked = AppPrefs.hiddenModeEnabled
        restoringSwitch = false
    }

    private fun restoreHeartbeatSwitch() {
        restoringSwitch = true
        binding.swHeartbeat.isChecked = AppPrefs.heartbeatEnabled
        restoringSwitch = false
    }

    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}
