package com.maysk.camera.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/** ML Kit person segmentation compositor for still images. Live preview/video wiring is device/GPU dependent. */
object BackgroundComposer {
    suspend fun replaceStill(foreground: Bitmap, background: Bitmap, edgeSoftness: Float = 0.12f): Bitmap? = withContext(Dispatchers.Default) {
        val segmentation = Vision.personMask(foreground) ?: return@withContext null
        val (mask, mw, mh) = segmentation
        val w = foreground.width; val h = foreground.height
        val bg = Bitmap.createScaledBitmap(background, w, h, true)
        val fgPixels = IntArray(w * h); foreground.getPixels(fgPixels, 0, w, 0, 0, w, h)
        val bgPixels = IntArray(w * h); bg.getPixels(bgPixels, 0, w, 0, 0, w, h)
        val outPixels = IntArray(w * h)
        val softness = edgeSoftness.coerceIn(0.02f, 0.4f)
        for (y in 0 until h) for (x in 0 until w) {
            val mx = (x.toFloat() / max(1, w - 1) * (mw - 1)).toInt().coerceIn(0, mw - 1)
            val my = (y.toFloat() / max(1, h - 1) * (mh - 1)).toInt().coerceIn(0, mh - 1)
            val p = mask[my * mw + mx].coerceIn(0f, 1f)
            val a = ((p - (0.5f - softness)) / (2f * softness)).coerceIn(0f, 1f)
            val f = fgPixels[y * w + x]; val b = bgPixels[y * w + x]
            fun ch(shift: Int) = ((((f shr shift) and 255) * a + ((b shr shift) and 255) * (1f - a)).toInt().coerceIn(0, 255))
            outPixels[y * w + x] = Color.argb(255, ch(16), ch(8), ch(0))
        }
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(outPixels, 0, w, 0, 0, w, h) }
    }
}
