package com.nekotype.app.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * 防篡改防护：
 * 1. 签名校验 —— 重打包（改 smali/字节码后重新签名）签名必然变化，直接拦截；
 * 2. Hook 框架检测 —— 检测 Xposed / LSPosed 等运行时修改返回值的手段（提高门槛）。
 *
 * 说明：这是纯本地方案能做的最高防线；root + Hook 框架可绕过（物理极限），
 * 但能挡住绝大多数"反编译改字节码重打包"的破解。
 */
object TamperGuard {

    // 发布签名的 SHA-256（apksigner verify --print-certs，硬编码防重打包）
    private const val EXPECTED_SIGNATURE = "463ff68eeffe79d827a4600095e9dc4b6dccff7b2a4c80a852642496ef2b5334"

    /** 校验当前 APK 签名是否匹配发布签名（重打包后签名必然变化） */
    fun isSignatureValid(context: Context): Boolean {
        return try {
            val info = if (Build.VERSION.SDK_INT >= 28) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            }
            val certs = if (Build.VERSION.SDK_INT >= 28) {
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                info.signatures
            }
            val digest = certs?.firstOrNull()?.toByteArray()?.let { sha256Hex(it) }
            digest.equals(EXPECTED_SIGNATURE, ignoreCase = true)
        } catch (_: Throwable) {
            true // 获取失败不误伤（保守：不拦截）
        }
    }

    /**
     * 检测常见 Hook 框架（Xposed / LSPosed / Frida 等）痕迹
     */
    fun hasHookFramework(): Boolean {
        return try {
            // 1. Xposed 桥类加载（若被 Hook 通常存在）
            try {
                Class.forName("de.robv.android.xposed.XposedBridge")
                return true
            } catch (_: ClassNotFoundException) {
                // 未加载，继续检查
            }
            // 2. 常见框架路径
            val paths = listOf(
                "/system/framework/XposedBridge.jar",
                "/data/data/de.robv.android.xposed.installer",
                "/data/data/com.tsng.hidemyapplist"
            )
            if (paths.any { File(it).exists() }) return true

            // 3. Frida 检测（进程 / 内存映射）
            try {
                val ps = Runtime.getRuntime()
                    .exec(arrayOf("sh", "-c", "ps -A 2>/dev/null | grep -i frida"))
                    .inputStream.bufferedReader().readText()
                if (ps.isNotBlank()) return true
            } catch (_: Throwable) { }
            try {
                if (File("/data/local/tmp/frida-server").exists() ||
                    File("/data/local/tmp/re.frida.server").exists()
                ) return true
            } catch (_: Throwable) { }
            try {
                val maps = Runtime.getRuntime()
                    .exec(arrayOf("sh", "-c", "cat /proc/self/maps 2>/dev/null"))
                    .inputStream.bufferedReader().readText()
                if (maps.contains("frida") || maps.contains("gum-js") ||
                    maps.contains("libgadget") || maps.contains("re.frida")
                ) return true
            } catch (_: Throwable) { }
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun sha256Hex(bytes: ByteArray): String = try {
        val d = java.security.MessageDigest.getInstance("SHA-256")
        d.digest(bytes).joinToString("") { "%02x".format(it) }
    } catch (_: Throwable) {
        ""
    }
}
