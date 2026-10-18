package com.nekotype.app.sys

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.math.sqrt

/**
 * 摇一摇检测（加速度计）。
 *
 * 参数与算法对齐参照应用「游龙安全护盾」(com.youlong.hd / ProtectService) 的实测数值：
 *  - 度量：相邻两帧 |Δax|、|Δay|、|Δaz| 的模长（帧间差分，天然不含重力，不需要低通）
 *  - 阈值：轻摇 5.0 / 标准 10.0 / 用力 13.5 (m/s²)（三档；轻摇按用户反馈下调）
 *  - 命中：窗口 1500ms 内累计命中 3 / 3 / 4 次才触发 —— 档位差异不只靠阈值，也靠命中数
 *  - 冷却：触发后 6000ms 内不再触发
 *  - 触发时振动 100ms
 */
class ShakeDetector(
    ctx: Context,
    level: Int,
    private val onShake: () -> Unit
) : SensorEventListener {

    private val appCtx = ctx.applicationContext
    private val sm = appCtx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val idx = level.coerceIn(0, 2)
    private val threshold = THRESHOLDS[idx]
    private val hitsNeeded = HITS[idx]

    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var hits = 0
    private var lastHitTs = 0L
    private var lastTriggerTs = 0L

    fun start() {
        sensor?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        try {
            sm?.unregisterListener(this)
        } catch (_: Throwable) {
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val ax = event.values[0]
        val ay = event.values[1]
        val az = event.values[2]
        val dx = ax - lastX
        val dy = ay - lastY
        val dz = az - lastZ
        lastX = ax
        lastY = ay
        lastZ = az

        val delta = sqrt(dx * dx + dy * dy + dz * dz)
        if (delta < threshold) return

        val now = SystemClock.elapsedRealtime()
        hits = if (now - lastHitTs < WINDOW_MS) hits + 1 else 1
        lastHitTs = now
        if (hits < hitsNeeded) return
        hits = 0
        if (now - lastTriggerTs < COOLDOWN_MS) return
        lastTriggerTs = now
        vibrate()
        onShake()
    }

    private fun vibrate() {
        try {
            val v = appCtx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(100)
            }
        } catch (_: Throwable) {
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        /** 参照应用 SHAKE_THRESHOLDS */
        private val THRESHOLDS = floatArrayOf(5.0f, 10.0f, 13.5f)
        /** 参照应用 SHAKE_HITS_TABLE */
        private val HITS = intArrayOf(3, 3, 4)
        /** 参照应用 SHAKE_WINDOW */
        private const val WINDOW_MS = 1500L
        /** 参照应用 SHAKE_RESCUE_COOLDOWN_MS */
        private const val COOLDOWN_MS = 6000L
    }
}
