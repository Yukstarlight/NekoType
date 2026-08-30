package com.nekotype.app.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * 设备管理员接收器（可选能力）。
 * 激活后可获得额外的系统级能力（配合 Dhizuku 可共享设备所有者权限）。
 */
class NekoTypeDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Toast.makeText(context, "NekoType 设备管理员已激活", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Toast.makeText(context, "NekoType 设备管理员已停用", Toast.LENGTH_SHORT).show()
    }
}
