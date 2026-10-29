package com.nekotype.app.ui

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.nekotype.app.R
import com.nekotype.app.prefs.AppPrefs

/**
 * 密码锁定下的设置访问控制：
 * 开启密码锁定后，进入任何设置类页面（规则 / 模式 / 设置 / 模块 / 应用名单 / 预设映射）
 * 都必须先输入正确密码；解锁状态保持 5 分钟，应用退到后台即失效。
 */
object LockGate {

    private const val UNLOCK_WINDOW_MS = 5 * 60 * 1000L

    private var unlockedAt = 0L

    fun isUnlocked(): Boolean =
        !AppPrefs.lockEnabled || System.currentTimeMillis() - unlockedAt < UNLOCK_WINDOW_MS

    fun unlock() {
        unlockedAt = System.currentTimeMillis()
    }

    fun onAppBackground() {
        unlockedAt = 0L
    }

    /**
     * 需要密码才能继续：已解锁直接执行；未解锁弹密码框。
     * @param onCancel 取消时的动作（例如关闭当前页面）
     */
    fun require(activity: Activity, onOk: () -> Unit, onCancel: (() -> Unit)? = null) {
        if (isUnlocked()) {
            onOk()
            return
        }
        val inPwd = NekoDialog.input(activity, activity.getString(R.string.u75), password = true)
        val box = NekoDialog.column(activity, inPwd)
        val dialog = NekoDialog.builder(activity)
            .setTitle(activity.getString(R.string.u65))
            .setView(box)
            .setPositiveButton(activity.getString(R.string.u71), null)
            .setNegativeButton(activity.getString(R.string.u72), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (AppPrefs.verifyLockPassword(NekoDialog.textOf(inPwd))) {
                    dialog.dismiss()
                    unlock()
                    onOk()
                } else {
                    android.widget.Toast.makeText(
                        activity, activity.getString(R.string.u25), android.widget.Toast.LENGTH_SHORT
                    ).show()
                    inPwd.editText?.text?.clear()
                }
            }
        }
        dialog.setOnCancelListener { onCancel?.invoke() }
        dialog.show()
    }
}
