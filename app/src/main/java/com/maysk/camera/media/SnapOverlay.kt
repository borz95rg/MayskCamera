package com.maysk.camera.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Raster overlay renderer shared by photo export and future video-frame compositor. */
object SnapOverlay {
    fun render(source: Bitmap, caption: String, timestamp: Long, sticker: String = "", strokes: List<List<android.graphics.PointF>> = emptyList()) : Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val pad = out.width * 0.045f
        val textSize = (out.width * 0.052f).coerceIn(28f, 88f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; this.textSize = textSize; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setShadowLayer(textSize * 0.22f, 0f, textSize * 0.08f, Color.BLACK)
        }
        val stamp = SimpleDateFormat("dd.MM.yyyy  HH:mm", Locale.getDefault()).format(Date(timestamp))
        val drawingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.CYAN; style = Paint.Style.STROKE; strokeWidth = (out.width * 0.009f).coerceAtLeast(5f)
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            setShadowLayer(strokeWidth * 1.8f, 0f, 0f, Color.MAGENTA)
        }
        strokes.forEach { points ->
            if (points.size >= 2) {
                val path = android.graphics.Path().apply {
                    moveTo(points.first().x * out.width, points.first().y * out.height)
                    points.drop(1).forEach { lineTo(it.x * out.width, it.y * out.height) }
                }
                canvas.drawPath(path, drawingPaint)
            }
        }
        if (caption.isNotBlank()) canvas.drawText(caption.take(80), pad, out.height - pad - textSize * 1.25f, paint)
        paint.textSize = textSize * 0.48f
        canvas.drawText(stamp, pad, out.height - pad, paint)
        if (sticker.isNotBlank()) { paint.textSize = textSize * 1.5f; canvas.drawText(sticker.take(4), out.width - pad - paint.measureText(sticker.take(4)), pad + textSize * 1.5f, paint) }
        return out
    }
}
