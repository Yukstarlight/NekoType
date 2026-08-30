/*
 * NekoType
 *
 * BSD 2-Clause License
 *
 * Copyright (c) 2026, Yukstarlight
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.nekotype.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.ContextCompat
import com.nekotype.app.R
import com.nekotype.app.prefs.AppPrefs
import java.io.File
import kotlin.math.max

/**
 * 自定义背景工具：按用户选择的图片设置页面背景，采用【自适应】（cover 缩放居中裁切，
 * 不拉伸变形）。本地存储，采样压缩防 OOM，带缓存（路径不变不重复解码）。
 */
object BgUtils {

    private var lastPath: String? = null
    private var lastBitmap: Bitmap? = null

    fun apply(view: View) {
        val path = AppPrefs.customBackgroundPath
        if (path != lastPath) {
            lastBitmap?.let { if (!it.isRecycled) it.recycle() }
            lastBitmap = decode(path)
            lastPath = path
        }
        view.background = when {
            lastBitmap != null -> CoverDrawable(lastBitmap!!)
            // 无自定义背景时按主题选背景色（星空模式用深蓝紫夜空渐变）
            AppPrefs.themeMode == "star" -> ContextCompat.getDrawable(view.context, R.drawable.bg_gradient_star)
            else -> ContextCompat.getDrawable(view.context, R.drawable.bg_gradient_app)
        }
    }

    private fun decode(path: String): Bitmap? {
        val f = File(path)
        if (path.isEmpty() || !f.exists()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            val max = 2048
            while (bounds.outWidth / sample > max || bounds.outHeight / sample > max) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(path, opts)
        } catch (_: Throwable) {
            null
        }
    }

    /** 自适应背景：cover 缩放（居中裁切），不拉伸 */
    private class CoverDrawable(private val bmp: Bitmap) : Drawable() {
        private val src = Rect()
        private val dst = Rect()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty || bmp.isRecycled) return
            val scale = max(
                b.width().toFloat() / bmp.width,
                b.height().toFloat() / bmp.height
            )
            val w = (bmp.width * scale).toInt()
            val h = (bmp.height * scale).toInt()
            src.set(0, 0, bmp.width, bmp.height)
            dst.set(b.centerX() - w / 2, b.centerY() - h / 2, b.centerX() + w / 2, b.centerY() + h / 2)
            canvas.drawBitmap(bmp, src, dst, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }

        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
