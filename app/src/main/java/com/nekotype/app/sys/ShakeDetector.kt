package com.nekotype.app.sys

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * 摇一摇检测（加速度计）。
 *
 * 陀螺仪只测旋转，判定"抖动"用重力分量的快速变化更稳：
 * 低通估重力 → 线性加速度模长超过阈值 → 窗口内累计两次越过 → 触发。
 * 阈值对应模式页四档：轻摇 8 / 标准 14 / 用力 22 / 强力 32 (m/s²)。
 */
class ShakeDetector(
    ctx: Context,
    private val threshold: Float,
    private val onShake: () -> Unit
) : SensorEventListener {

    private val sm = ctx.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val gravity = FloatArray(3)
    private var crossings = 0
    private var windowStart = 0L
    private var lastTrigger = 0L

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
        val alpha = 0.8f
        for (i in 0..2) gravity[i] = alpha * gravity[i] + (1 - alpha) * event.values[i]
        val lx = event.values[0] - gravity[0]
        val ly = event.values[1] - gravity[1]
        val lz = event.values[2] - gravity[2]
        val mag = sqrt(lx * lx + ly * ly + lz * lz)
        if (mag < threshold) return

        val now = SystemClock.elapsedRealtime()
        if (now - windowStart > 600L) {
            crossings = 0
            windowStart = now
        }
        crossings++
        if (crossings >= 2 && now - lastTrigger > 1200L) {
            lastTrigger = now
            crossings = 0
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
