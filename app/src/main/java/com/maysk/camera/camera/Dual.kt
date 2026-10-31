package com.maysk.camera.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.maysk.camera.DualLayout
import kotlin.math.max
import kotlin.math.min

/** Склейка двойного снимка (основная + фронтальная камера). */
object Dual {

    /** Центрированная обрезка под нужное соотношение сторон. */
    private fun centerCrop(src: Bitmap, aspect: Float): Rect {
        val a = src.width.toFloat() / src.height
        return if (a > aspect) {
            val w = (src.height * aspect).toInt(); val x = (src.width - w) / 2
            Rect(x, 0, x + w, src.height)
        } else {
            val h = (src.width / aspect).toInt(); val y = (src.height - h) / 2
            Rect(0, y, src.width, y + h)
        }
    }

    fun compose(main: Bitmap, selfie: Bitmap, layout: DualLayout): Bitmap {
        val w = main.width; val h = main.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        when (layout) {
            DualLayout.PIP, DualLayout.ROUND -> {
                c.drawBitmap(main, 0f, 0f, paint)
                val round = layout == DualLayout.ROUND
                val pw = w * (if (round) 0.34f else 0.3f)
                val ph = if (round) pw else pw * 4f / 3f
                val m = w * 0.04f
                val dst = RectF(m, m, m + pw, m + ph)
                val src = centerCrop(selfie, pw / ph)
                val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 0, 0, 0) }
                val r = if (round) pw / 2 else pw * 0.08f
                c.drawRoundRect(RectF(dst.left + 6, dst.top + 10, dst.right + 6, dst.bottom + 10), r, r, shadow)
                val save = c.save()
                c.clipPath(Path().apply { addRoundRect(dst, r, r, Path.Direction.CW) })
                c.drawBitmap(selfie, src, dst, paint)
                c.restoreToCount(save)
                c.drawRoundRect(dst, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = max(4f, w * 0.006f); color = Color.WHITE
                })
            }
            DualLayout.SPLIT_V -> {
                val half = h / 2
                c.drawBitmap(main, centerCrop(main, w.toFloat() / half), Rect(0, 0, w, half), paint)
                c.drawBitmap(selfie, centerCrop(selfie, w.toFloat() / (h - half)), Rect(0, half, w, h), paint)
                c.drawRect(0f, half - 3f, w.toFloat(), half + 3f, Paint().apply { color = Color.WHITE })
            }
            DualLayout.SPLIT_H -> {
                val half = w / 2
                c.drawBitmap(main, centerCrop(main, half.toFloat() / h), Rect(0, 0, half, h), paint)
                c.drawBitmap(selfie, centerCrop(selfie, (w - half).toFloat() / h), Rect(half, 0, w, h), paint)
                c.drawRect(half - 3f, 0f, half + 3f, h.toFloat(), Paint().apply { color = Color.WHITE })
            }
        }
        return out
    }

    @Suppress("unused")
    private fun clamp(v: Float) = max(0f, min(1f, v))
}
