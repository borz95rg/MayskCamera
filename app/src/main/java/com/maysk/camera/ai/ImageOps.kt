package com.maysk.camera.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.maysk.camera.camera.Effect
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/** Набор быстрых операций над изображениями (CPU). Все функции возвращают новый Bitmap. */
object ImageOps {

    fun ensureMutable(src: Bitmap): Bitmap =
        if (src.isMutable && src.config == Bitmap.Config.ARGB_8888) src else src.copy(Bitmap.Config.ARGB_8888, true)

    fun limitSize(src: Bitmap, maxSide: Int): Bitmap {
        val m = max(src.width, src.height)
        if (m <= maxSide) return src
        val s = maxSide.toFloat() / m
        return Bitmap.createScaledBitmap(src, (src.width * s).roundToInt(), (src.height * s).roundToInt(), true)
    }

    fun rotate(src: Bitmap, degrees: Int, mirror: Boolean = false): Bitmap {
        if (degrees % 360 == 0 && !mirror) return src
        val m = Matrix()
        m.postRotate(degrees.toFloat())
        if (mirror) m.postScale(-1f, 1f)
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun crop(src: Bitmap, rect: RectF): Bitmap {
        val r = Rect(
            (rect.left * src.width).roundToInt().coerceIn(0, src.width - 1),
            (rect.top * src.height).roundToInt().coerceIn(0, src.height - 1),
            (rect.right * src.width).roundToInt().coerceIn(1, src.width),
            (rect.bottom * src.height).roundToInt().coerceIn(1, src.height)
        )
        if (r.width() <= 1 || r.height() <= 1) return src
        return Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
    }

    /** Цветовая матрица (формат android.graphics.ColorMatrix) — аппаратно через Canvas. */
    fun colorMatrix(src: Bitmap, matrix: FloatArray): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(matrix)) }
        Canvas(out).drawBitmap(src, 0f, 0f, p)
        return out
    }

    fun vignetteAndFade(src: Bitmap, vignette: Float, fade: Float): Bitmap {
        if (vignette <= 0.01f && fade <= 0.01f) return src
        var out = src
        if (fade > 0.01f) {
            val k = 1f - fade * 0.25f
            val o = fade * 0.1f * 255f
            out = colorMatrix(out, floatArrayOf(k, 0f, 0f, 0f, o, 0f, k, 0f, 0f, o, 0f, 0f, k, 0f, o, 0f, 0f, 0f, 1f, 0f))
        }
        if (vignette > 0.01f) {
            out = ensureMutable(out)
            val c = Canvas(out)
            val r = sqrt((out.width * out.width + out.height * out.height).toFloat()) / 2f
            val p = Paint().apply {
                shader = RadialGradient(
                    out.width / 2f, out.height / 2f, r,
                    intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb((vignette * 200).roundToInt().coerceIn(0, 255), 0, 0, 0)),
                    floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
                )
            }
            c.drawRect(0f, 0f, out.width.toFloat(), out.height.toFloat(), p)
        }
        return out
    }

    /**
     * Быстрое гладкое размытие: ступенчатое уменьшение в 2 раза и такое же ступенчатое
     * увеличение — без «квадратиков», которые даёт резкое масштабирование.
     */
    fun fastBlur(src: Bitmap, factor: Int): Bitmap {
        var f = max(2, factor)
        var cur = src
        val sizes = ArrayList<Pair<Int, Int>>()
        while (f >= 2 && cur.width > 8 && cur.height > 8) {
            sizes += cur.width to cur.height
            cur = Bitmap.createScaledBitmap(cur, max(1, cur.width / 2), max(1, cur.height / 2), true)
            f /= 2
        }
        // лёгкое дополнительное сглаживание на маленьком размере
        val w = cur.width; val h = cur.height
        val px = IntArray(w * h).also { cur.getPixels(it, 0, w, 0, 0, w, h) }
        cur = Bitmap.createBitmap(boxBlur(px, w, h, 1), w, h, Bitmap.Config.ARGB_8888)
        for (i in sizes.indices.reversed()) {
            val (tw, th) = sizes[i]
            cur = Bitmap.createScaledBitmap(cur, tw, th, true)
        }
        return cur
    }

    /** Box-blur по пикселям (сепарабельный, O(n)). */
    fun boxBlur(px: IntArray, w: Int, h: Int, r: Int): IntArray {
        if (r < 1) return px.copyOf()
        val tmp = IntArray(px.size)
        val out = IntArray(px.size)
        val div = 2 * r + 1
        for (y in 0 until h) {
            var sr = 0; var sg = 0; var sb = 0
            val row = y * w
            for (i in -r..r) {
                val c = px[row + i.coerceIn(0, w - 1)]
                sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255
            }
            for (x in 0 until w) {
                tmp[row + x] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val a = px[row + (x - r).coerceIn(0, w - 1)]
                val b = px[row + (x + r + 1).coerceIn(0, w - 1)]
                sr += ((b shr 16) and 255) - ((a shr 16) and 255)
                sg += ((b shr 8) and 255) - ((a shr 8) and 255)
                sb += (b and 255) - (a and 255)
            }
        }
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val c = tmp[i.coerceIn(0, h - 1) * w + x]
                sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255
            }
            for (y in 0 until h) {
                out[y * w + x] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val a = tmp[(y - r).coerceIn(0, h - 1) * w + x]
                val b = tmp[(y + r + 1).coerceIn(0, h - 1) * w + x]
                sr += ((b shr 16) and 255) - ((a shr 16) and 255)
                sg += ((b shr 8) and 255) - ((a shr 8) and 255)
                sb += (b and 255) - (a and 255)
            }
        }
        return out
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
    private fun fromPixels(px: IntArray, w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(px, 0, w, 0, 0, w, h) }

    private inline fun clamp255(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v
    private inline fun clamp255(v: Float) = if (v < 0f) 0 else if (v > 255f) 255 else v.toInt()

    /** Резкость (unsharp mask). amount 0..2 */
    fun sharpen(src: Bitmap, amount: Float, radius: Int = 1): Bitmap {
        if (amount <= 0.01f) return src
        val w = src.width; val h = src.height
        val px = pixels(src)
        val bl = boxBlur(px, w, h, radius)
        for (i in px.indices) {
            val c = px[i]; val b = bl[i]
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val bb = c and 255
            val nr = clamp255(r + ((r - ((b shr 16) and 255)) * amount).toInt())
            val ng = clamp255(g + ((g - ((b shr 8) and 255)) * amount).toInt())
            val nb = clamp255(bb + ((bb - (b and 255)) * amount).toInt())
            px[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
        return fromPixels(px, w, h)
    }

    /**
     * AI Super Detail: «умная» резкость. Усиливает детали двух масштабов (мелкие и средние)
     * только по яркости (без цветного шума), не трогает ровные области (шум не растёт)
     * и ограничивает выброс на контрастных краях (нет белых ореолов).
     */
    fun smartSharpen(src: Bitmap, amount: Float): Bitmap {
        if (amount <= 0.01f) return src
        val w = src.width; val h = src.height
        val px = pixels(src)
        val b1 = boxBlur(px, w, h, 1)
        val b2 = boxBlur(px, w, h, 2)
        val limit = 18f + 22f * amount
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            val l = r * 0.299f + g * 0.587f + b * 0.114f
            val c1 = b1[i]; val c2 = b2[i]
            val l1 = ((c1 shr 16) and 255) * 0.299f + ((c1 shr 8) and 255) * 0.587f + (c1 and 255) * 0.114f
            val l2 = ((c2 shr 16) and 255) * 0.299f + ((c2 shr 8) and 255) * 0.587f + (c2 and 255) * 0.114f
            val fine = l - l1
            val mid = l - l2
            val texture = abs(mid)
            // порог шума: мелкие колебания (шум сенсора) не усиливаем
            val gate = ((texture - 2.5f) / 6f).coerceIn(0f, 1f)
            var d = (fine * 0.9f + mid * 0.6f) * amount * gate
            if (d > limit) d = limit else if (d < -limit) d = -limit
            // в светах и тенях мягче, чтобы не было «хруста»
            val tone = 1f - abs(l / 255f - 0.5f) * 0.8f
            d *= tone
            px[i] = (0xFF shl 24) or (clamp255(r + d) shl 16) or (clamp255(g + d) shl 8) or clamp255(b + d)
        }
        return fromPixels(px, w, h)
    }

    /** Шумоподавление с сохранением краёв: смешиваем с размытием там, где разница мала. */
    fun denoise(src: Bitmap, strength: Float): Bitmap {
        if (strength <= 0.01f) return src
        val w = src.width; val h = src.height
        val px = pixels(src)
        val r = max(1, (min(w, h) / 700f * (1 + strength * 2)).roundToInt())
        val bl = boxBlur(px, w, h, r)
        val thr = 10f + 30f * strength
        for (i in px.indices) {
            val c = px[i]; val b = bl[i]
            val dr = ((c shr 16) and 255) - ((b shr 16) and 255)
            val dg = ((c shr 8) and 255) - ((b shr 8) and 255)
            val db = (c and 255) - (b and 255)
            val d = (abs(dr) + abs(dg) + abs(db)) / 3f
            val wgt = strength * exp(-(d / thr).pow(2))
            val nr = ((c shr 16) and 255) - dr * wgt
            val ng = ((c shr 8) and 255) - dg * wgt
            val nb = (c and 255) - db * wgt
            px[i] = (0xFF shl 24) or (clamp255(nr) shl 16) or (clamp255(ng) shl 8) or clamp255(nb)
        }
        return fromPixels(px, w, h)
    }

    /** Мягкая маска кожи (YCbCr) + отсечение тёмных пикселей (волосы, брови, тени). */
    private fun isSkin(r: Int, g: Int, b: Int): Float {
        val y = 0.299f * r + 0.587f * g + 0.114f * b
        if (y < 75f || y > 250f) return 0f
        val cb = 128 - 0.168736f * r - 0.331264f * g + 0.5f * b
        val cr = 128 + 0.5f * r - 0.418688f * g - 0.081312f * b
        if (cr < 132f || cr > 176f || cb < 78f || cb > 130f) return 0f
        val ecr = 1f - abs(cr - 152f) / 24f
        val ecb = 1f - abs(cb - 104f) / 26f
        val lumaFade = ((y - 75f) / 30f).coerceIn(0f, 1f)
        return (min(ecr, ecb) * 2f).coerceIn(0f, 1f) * lumaFade
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    /**
     * AI-ретушь кожи по принципу частотного разложения:
     * убираем средние частоты (пятна, неровности), но возвращаем мелкую текстуру (поры),
     * поэтому кожа не становится «пластиковой». Работает только внутри найденных лиц
     * с мягким переходом на границе — без контуров и пятен на волосах.
     */
    fun skinSmooth(src: Bitmap, strength: Float, faces: List<RectF>, brighten: Float = 0f): Bitmap {
        if ((strength <= 0.01f && brighten <= 0.01f) || faces.isEmpty()) return src
        val w = src.width; val h = src.height
        val px = pixels(src)
        val faceW = faces.maxOf { it.width() }
        val r = max(2, (faceW * w / 60f).roundToInt())
        val low = boxBlur(boxBlur(px, w, h, r), w, h, r)
        val fineBase = boxBlur(px, w, h, 1)
        val st = strength.coerceIn(0f, 1f) * 0.7f
        for (f in faces) {
            val area = RectF(f.left - f.width() * 0.15f, f.top - f.height() * 0.2f, f.right + f.width() * 0.15f, f.bottom + f.height() * 0.1f)
            val x0 = (area.left * w).roundToInt().coerceIn(0, w); val x1 = (area.right * w).roundToInt().coerceIn(0, w)
            val y0 = (area.top * h).roundToInt().coerceIn(0, h); val y1 = (area.bottom * h).roundToInt().coerceIn(0, h)
            val cx = (x0 + x1) / 2f; val cy = (y0 + y1) / 2f
            val rx = max(1f, (x1 - x0) / 2f); val ry = max(1f, (y1 - y0) / 2f)
            for (y in y0 until y1) for (x in x0 until x1) {
                val ex = (x - cx) / rx; val ey = (y - cy) / ry
                val dist = sqrt(ex * ex + ey * ey)
                val feather = 1f - smoothstep(0.7f, 1f, dist)
                if (feather <= 0f) continue
                val i = y * w + x
                val c = px[i]
                val cr = (c shr 16) and 255; val cg = (c shr 8) and 255; val cb = c and 255
                val sk = isSkin(cr, cg, cb)
                if (sk <= 0f) continue
                val lo = low[i]; val fb = fineBase[i]
                // текстура (мелкие детали) сохраняется на 85%
                val tr = (cr - ((fb shr 16) and 255)) * 0.85f
                val tg = (cg - ((fb shr 8) and 255)) * 0.85f
                val tb = (cb - (fb and 255)) * 0.85f
                val sr = ((lo shr 16) and 255) + tr
                val sg = ((lo shr 8) and 255) + tg
                val sb = (lo and 255) + tb
                // не трогаем резкие края (брови, губы, контур)
                val d = (abs(cr - ((lo shr 16) and 255)) + abs(cg - ((lo shr 8) and 255)) + abs(cb - (lo and 255))) / 3f
                val edgeKeep = exp(-(d / 28f).pow(2))
                val k = (st * sk * feather * edgeKeep).coerceIn(0f, 0.75f)
                val lift = brighten.coerceIn(0f, 1f) * sk * feather * 12f
                px[i] = (0xFF shl 24) or (clamp255(cr + (sr - cr) * k + lift) shl 16) or
                    (clamp255(cg + (sg - cg) * k + lift) shl 8) or clamp255(cb + (sb - cb) * k + lift * 0.9f)
            }
        }
        return fromPixels(px, w, h)
    }

    /**
     * Чёткость (clarity): локальный контраст средних тонов с большим радиусом.
     * Добавляет объём и «детальность» без пересвета и шума, в отличие от резкости.
     */
    fun clarity(src: Bitmap, amount: Float): Bitmap {
        if (amount <= 0.01f) return src
        val w = src.width; val h = src.height
        val px = pixels(src)
        val base = pixels(fastBlur(src, max(6, min(w, h) / 90)))
        for (i in px.indices) {
            val c = px[i]; val b = base[i]
            val cr = (c shr 16) and 255; val cg = (c shr 8) and 255; val cb = c and 255
            val l = (cr * 0.299f + cg * 0.587f + cb * 0.114f) / 255f
            val bl = (((b shr 16) and 255) * 0.299f + ((b shr 8) and 255) * 0.587f + (b and 255) * 0.114f) / 255f
            val mid = 1f - abs(2f * l - 1f)          // сильнее в средних тонах, мягче в тенях/светах
            val delta = (l - bl) * amount * mid * 255f * 0.9f
            px[i] = (0xFF shl 24) or (clamp255(cr + delta) shl 16) or (clamp255(cg + delta) shl 8) or clamp255(cb + delta)
        }
        return fromPixels(px, w, h)
    }

    /** Оценка резкости кадра (дисперсия лапласиана) — для выбора лучшего кадра серии. */
    fun sharpnessScore(b: Bitmap): Double {
        val s = limitSize(b, 480)
        val w = s.width; val h = s.height
        val px = pixels(s)
        fun l(i: Int) = (px[i] shr 8) and 255
        var sum = 0.0; var sq = 0.0; var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val lap = (4 * l(i) - l(i - 1) - l(i + 1) - l(i - w) - l(i + w)).toDouble()
            sum += lap; sq += lap * lap; n++
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return sq / n - mean * mean
    }

    /**
     * Умная серия: берём самый резкий кадр как опорный и складываем с ним только
     * кадры, которые хорошо совпали (без смаза). Так шум уходит, а детали остаются.
     */
    fun smartStack(frames: List<Bitmap>): Bitmap {
        if (frames.size == 1) return frames[0]
        val scores = frames.map { sharpnessScore(it) }
        val best = scores.indices.maxByOrNull { scores[it] } ?: 0
        val ref = frames[best]
        val good = mutableListOf(ref)
        frames.forEachIndexed { i, f ->
            if (i == best) return@forEachIndexed
            if (scores[i] < scores[best] * 0.75) return@forEachIndexed   // смазанный кадр — пропускаем
            val (dx, dy) = estimateShift(ref, f)
            if (abs(dx) < ref.width / 40 && abs(dy) < ref.height / 40) good += f
        }
        return if (good.size == 1) ref else stackFrames(good)
    }

    /** Глаза: осветление, резкость и снижение красноты вокруг точек глаз. */
    fun enhanceEyes(src: Bitmap, eyes: List<PointF>, radiusNorm: Float, amount: Float): Bitmap {
        if (eyes.isEmpty() || amount <= 0.01f) return src
        val w = src.width; val h = src.height
        val px = pixels(src)
        val bl = boxBlur(px, w, h, 1)
        val rad = max(4f, radiusNorm * w)
        for (e in eyes) {
            val ex = e.x * w; val ey = e.y * h
            val x0 = (ex - rad).toInt().coerceIn(0, w - 1); val x1 = (ex + rad).toInt().coerceIn(0, w - 1)
            val y0 = (ey - rad).toInt().coerceIn(0, h - 1); val y1 = (ey + rad).toInt().coerceIn(0, h - 1)
            for (y in y0..y1) for (x in x0..x1) {
                val dx = (x - ex) / rad; val dy = (y - ey) / rad
                val fall = (1f - (dx * dx + dy * dy)).coerceIn(0f, 1f)
                if (fall <= 0f) continue
                val i = y * w + x
                val c = px[i]; val b = bl[i]
                var r = ((c shr 16) and 255).toFloat(); var g = ((c shr 8) and 255).toFloat(); var bb = (c and 255).toFloat()
                // резкость
                r += (r - ((b shr 16) and 255)) * amount * 0.5f * fall
                g += (g - ((b shr 8) and 255)) * amount * 0.5f * fall
                bb += (bb - (b and 255)) * amount * 0.5f * fall
                // убрать красноту белков
                val redness = r - (g + bb) / 2f
                if (redness > 20f && r > 120f) r -= redness * 0.5f * amount * fall
                // яркость
                val lift = 9f * amount * fall
                px[i] = (0xFF shl 24) or (clamp255(r + lift) shl 16) or (clamp255(g + lift) shl 8) or clamp255(bb + lift)
            }
        }
        return fromPixels(px, w, h)
    }

    /** «Лицо»: мягкая коррекция овала (сужение нижней части) через сетку drawBitmapMesh. */
    fun slimFace(src: Bitmap, face: RectF, amount: Float): Bitmap {
        if (amount <= 0.01f) return src
        val mw = 40; val mh = 40
        val verts = FloatArray((mw + 1) * (mh + 1) * 2)
        val fx = face.centerX() * src.width
        val fy0 = (face.top + face.height() * 0.45f) * src.height
        val fy1 = face.bottom * src.height
        val fw = face.width() * src.width
        var k = 0
        for (j in 0..mh) {
            val y = src.height * j / mh.toFloat()
            for (i in 0..mw) {
                var x = src.width * i / mw.toFloat()
                if (y in fy0..fy1 + fw * 0.1f) {
                    val dx = x - fx
                    val nd = abs(dx) / (fw * 0.75f)
                    if (nd < 1f) {
                        val vy = ((y - fy0) / (fy1 - fy0)).coerceIn(0f, 1f)
                        val shift = dx * 0.12f * amount * (1f - nd) * vy
                        x -= shift
                    }
                }
                verts[k++] = x; verts[k++] = y
            }
        }
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmapMesh(src, mw, mh, verts, 0, null, 0, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Статистика для AI-анализа. */
    data class Stats(
        val mean: Float, val p2: Float, val p98: Float, val shadows: Float, val highlights: Float,
        val saturation: Float, val rAvg: Float, val gAvg: Float, val bAvg: Float, val histogram: FloatArray
    )

    fun stats(src: Bitmap): Stats {
        val small = limitSize(src, 256)
        val px = pixels(small)
        val hist = IntArray(256)
        var sr = 0L; var sg = 0L; var sb = 0L; var sat = 0f
        for (c in px) {
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            val l = (r * 299 + g * 587 + b * 114) / 1000
            hist[l]++
            sr += r; sg += g; sb += b
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            sat += if (mx == 0) 0f else (mx - mn) / mx.toFloat()
        }
        val n = px.size.toFloat()
        var acc = 0; var p2 = 0; var p98 = 255
        var found2 = false
        for (i in 0..255) {
            acc += hist[i]
            if (!found2 && acc >= n * 0.02f) { p2 = i; found2 = true }
            if (acc >= n * 0.98f) { p98 = i; break }
        }
        var mean = 0f
        for (i in 0..255) mean += i * hist[i]
        val shadows = (0..25).sumOf { hist[it] } / n
        val highlights = (240..255).sumOf { hist[it] } / n
        val hist64 = FloatArray(64)
        for (i in 0..255) hist64[i / 4] += hist[i] / n
        return Stats(mean / n / 255f, p2 / 255f, p98 / 255f, shadows, highlights, sat / n,
            sr / n / 255f, sg / n / 255f, sb / n / 255f, hist64)
    }

    /** Автоуровни + баланс белого «серый мир». strength 0..1 */
    fun autoLevelsMatrix(st: Stats, strength: Float = 1f, wb: Boolean = true): FloatArray {
        val lo = st.p2 * 255f * 0.8f
        val hi = max(lo + 40f, st.p98 * 255f + (255f - st.p98 * 255f) * 0.3f)
        val scale = 255f / (hi - lo)
        val avg = (st.rAvg + st.gAvg + st.bAvg) / 3f
        val kr = if (wb && st.rAvg > 0.02f) (avg / st.rAvg).coerceIn(0.85f, 1.15f) else 1f
        val kg = if (wb && st.gAvg > 0.02f) (avg / st.gAvg).coerceIn(0.85f, 1.15f) else 1f
        val kb = if (wb && st.bAvg > 0.02f) (avg / st.bAvg).coerceIn(0.85f, 1.15f) else 1f
        fun mix(v: Float, id: Float) = id + (v - id) * strength
        val sr = mix(scale * kr, 1f); val sg = mix(scale * kg, 1f); val sb = mix(scale * kb, 1f)
        val o = mix(-lo * scale, 0f)
        return floatArrayOf(sr, 0f, 0f, 0f, o, 0f, sg, 0f, 0f, o, 0f, 0f, sb, 0f, o, 0f, 0f, 0f, 1f, 0f)
    }

    /**
     * Локальное тональное отображение (HDR-эффект из одного кадра):
     * поднимаем тени и прижимаем света относительно размытой «базы».
     */
    fun localToneMap(src: Bitmap, shadows: Float, highlights: Float, detail: Float = 0.3f): Bitmap {
        if (shadows <= 0.01f && highlights <= 0.01f) return src
        val w = src.width; val h = src.height
        val px = pixels(src)
        val base = pixels(fastBlur(src, max(8, min(w, h) / 60)))
        for (i in px.indices) {
            val c = px[i]; val b = base[i]
            val bl = (((b shr 16) and 255) * 0.299f + ((b shr 8) and 255) * 0.587f + (b and 255) * 0.114f) / 255f
            val cl = (((c shr 16) and 255) * 0.299f + ((c shr 8) and 255) * 0.587f + (c and 255) * 0.114f) / 255f
            // Без ореолов: тени поднимаем по max(пиксель, окрестность) — светлый объект рядом с тёмным
            // фоном не «светится»; света прижимаем по min(...) — тёмное рядом со светлым не темнеет.
            val lb = max(bl, cl)
            val hb = min(bl, cl)
            var gain = 1f
            if (lb < 0.5f) gain += shadows * (0.5f - lb) * 2.0f
            if (hb > 0.5f) gain -= highlights * (hb - 0.5f) * 0.9f
            val det = 1f + detail * (cl - bl).coerceIn(-0.15f, 0.15f) * 1.5f
            val g = (gain * det).coerceIn(0.5f, 2.6f)
            px[i] = (0xFF shl 24) or (clamp255(((c shr 16) and 255) * g) shl 16) or
                (clamp255(((c shr 8) and 255) * g) shl 8) or clamp255((c and 255) * g)
        }
        return fromPixels(px, w, h)
    }

    /** Экспозиционная фьюжн-склейка (HDR из брекетинга). */
    fun exposureFusion(frames: List<Bitmap>): Bitmap {
        val w = frames[0].width; val h = frames[0].height
        val arrs = frames.map { pixels(if (it.width == w && it.height == h) it else Bitmap.createScaledBitmap(it, w, h, true)) }
        val out = IntArray(w * h)
        for (i in out.indices) {
            var r = 0f; var g = 0f; var b = 0f; var ws = 0f
            for (a in arrs) {
                val c = a[i]
                val cr = (c shr 16) and 255; val cg = (c shr 8) and 255; val cb = c and 255
                val l = (cr * 0.299f + cg * 0.587f + cb * 0.114f) / 255f
                val wgt = exp(-((l - 0.5f) * (l - 0.5f)) / 0.08f) + 1e-4f
                r += cr * wgt; g += cg * wgt; b += cb * wgt; ws += wgt
            }
            out[i] = (0xFF shl 24) or (clamp255(r / ws) shl 16) or (clamp255(g / ws) shl 8) or clamp255(b / ws)
        }
        return fromPixels(out, w, h)
    }

    /** Поиск сдвига кадра (выравнивание) по уменьшенной яркости, ±range пикселей. */
    fun estimateShift(ref: Bitmap, frame: Bitmap, range: Int = 12): Pair<Int, Int> {
        val sw = 320
        val s = sw.toFloat() / ref.width
        val sh = max(1, (ref.height * s).roundToInt())
        val a = pixels(Bitmap.createScaledBitmap(ref, sw, sh, true))
        val b = pixels(Bitmap.createScaledBitmap(frame, sw, sh, true))
        fun l(c: Int) = ((c shr 16) and 255) + ((c shr 8) and 255) * 2 + (c and 255)
        var best = Long.MAX_VALUE; var bx = 0; var by = 0
        for (dy in -range..range step 2) for (dx in -range..range step 2) {
            var sum = 0L
            var y = range
            while (y < sh - range) {
                var x = range
                while (x < sw - range) {
                    sum += abs(l(a[y * sw + x]) - l(b[(y + dy) * sw + x + dx]))
                    x += 3
                }
                y += 3
            }
            if (sum < best) { best = sum; bx = dx; by = dy }
        }
        return Pair((bx / s).roundToInt(), (by / s).roundToInt())
    }

    /**
     * Многокадровое шумоподавление: выравнивание + усреднение с отбраковкой.
     * Пиксели, сильно отличающиеся от опорного кадра (движение людей, машин), не смешиваются —
     * поэтому нет «призраков», а шум уходит.
     */
    fun stackFrames(frames: List<Bitmap>): Bitmap {
        val ref = frames[0]
        val w = ref.width; val h = ref.height
        val sr = IntArray(w * h); val sg = IntArray(w * h); val sb = IntArray(w * h); val cnt = ShortArray(w * h)
        val row = IntArray(w)
        val refRow = IntArray(w)
        for ((idx, f) in frames.withIndex()) {
            val fr = if (f.width == w && f.height == h) f else Bitmap.createScaledBitmap(f, w, h, true)
            val (dx, dy) = if (idx == 0) Pair(0, 0) else estimateShift(ref, fr)
            for (y in 0 until h) {
                val sy = y + dy
                if (sy < 0 || sy >= h) continue
                fr.getPixels(row, 0, w, 0, sy, w, 1)
                if (idx > 0) ref.getPixels(refRow, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    val sx = x + dx
                    if (sx < 0 || sx >= w) continue
                    val c = row[sx]
                    val cr = (c shr 16) and 255; val cg = (c shr 8) and 255; val cb = c and 255
                    if (idx > 0) {
                        val q = refRow[x]
                        val diff = abs(cr - ((q shr 16) and 255)) + abs(cg - ((q shr 8) and 255)) + abs(cb - (q and 255))
                        if (diff > 60) continue   // движение — не смешиваем
                    }
                    val i = y * w + x
                    sr[i] += cr; sg[i] += cg; sb[i] += cb
                    cnt[i]++
                }
            }
        }
        val out = IntArray(w * h)
        for (i in out.indices) {
            val n = max(1, cnt[i].toInt())
            out[i] = (0xFF shl 24) or ((sr[i] / n) shl 16) or ((sg[i] / n) shl 8) or (sb[i] / n)
        }
        return fromPixels(out, w, h)
    }

    /** Ночная обработка одного (уже сложенного) кадра. */
    fun nightProcess(src: Bitmap): Bitmap {
        val st = stats(src)
        // ночью «серый мир» уводит цвет в фиолетовый от ламп — баланс белого не трогаем
        var out = colorMatrix(src, autoLevelsMatrix(st, 0.5f, wb = false))
        val lift = ((0.3f - st.mean) * 2f).coerceIn(0f, 0.6f)
        out = localToneMap(out, 0.3f + lift, 0.45f, 0.1f)
        out = denoise(out, 0.35f)
        return smartSharpen(out, 0.25f)
    }

    /** Коррекция дисторсии широкоугольника (бочка) через сетку. k > 0 — выпрямление. */
    fun undistort(src: Bitmap, k: Float): Bitmap {
        if (k <= 0.001f) return src
        val mw = 32; val mh = 32
        val verts = FloatArray((mw + 1) * (mh + 1) * 2)
        val cx = src.width / 2f; val cy = src.height / 2f
        val norm = sqrt(cx * cx + cy * cy)
        var idx = 0
        for (j in 0..mh) for (i in 0..mw) {
            val x = src.width * i / mw.toFloat(); val y = src.height * j / mh.toFloat()
            val dx = (x - cx) / norm; val dy = (y - cy) / norm
            val r2 = dx * dx + dy * dy
            val f = 1f + k * r2
            verts[idx++] = cx + dx * norm * f
            verts[idx++] = cy + dy * norm * f
        }
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val scale = 1f + k * 0.5f
        c.scale(1f / scale, 1f / scale, cx, cy)
        c.drawBitmapMesh(src, mw, mh, verts, 0, null, 0, Paint(Paint.FILTER_BITMAP_FLAG))
        // обрезаем пустые края
        val m = (1f - 1f / scale) / 2f
        return crop(out, RectF(m, m, 1f - m, 1f - m))
    }

    /** Обрезает прозрачные/пустые края (например, у панорамы), чтобы не было чёрных полос. */
    fun autoCropEmpty(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = pixels(src)
        fun opaque(x: Int, y: Int) = ((px[y * w + x] ushr 24) and 255) > 250
        val step = max(1, w / 400)
        var top = 0; var bottom = h - 1
        // верх/низ: строка годится, если в ней почти нет пустых пикселей
        fun rowOk(y: Int): Boolean { var bad = 0; var n = 0; var x = 0; while (x < w) { if (!opaque(x, y)) bad++; n++; x += step }; return bad <= n / 200 }
        while (top < h / 2 && !rowOk(top)) top++
        while (bottom > h / 2 && !rowOk(bottom)) bottom--
        fun colOk(x: Int): Boolean { var bad = 0; var n = 0; var y = top; while (y <= bottom) { if (!opaque(x, y)) bad++; n++; y += step }; return bad <= n / 200 }
        var left = 0; var right = w - 1
        while (left < w / 2 && !colOk(left)) left++
        while (right > w / 2 && !colOk(right)) right--
        if (right - left < w / 3 || bottom - top < h / 3) return src
        return Bitmap.createBitmap(src, left, top, right - left + 1, bottom - top + 1)
    }

    /**
     * Удаление объектов (AI-заливка по образцам, как «Точечное восстановление»).
     *
     * 1) Вокруг закрашенной области берём фрагмент кадра и уменьшаем его.
     * 2) Слой за слоем «снаружи внутрь» для каждого пикселя дыры ищем похожий по окрестности
     *    участок настоящего фона (асфальт, стена, трава) — с учётом соседей, чтобы текстура
     *    шла непрерывно, — и запоминаем, ОТКУДА взять пиксель.
     * 3) На полном разрешении копируем пиксели из найденных мест — текстура остаётся резкой.
     */
    fun inpaint(src: Bitmap, mask: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val m = if (mask.width == w && mask.height == h) mask else Bitmap.createScaledBitmap(mask, w, h, false)
        val mp = pixels(m)
        var hole = BooleanArray(w * h) { ((mp[it] ushr 24) and 255) > 40 }
        // расширяем дыру на пару пикселей — убираем контур объекта
        hole = dilate(hole, w, h, max(2, min(w, h) / 400))
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) if (hole[y * w + x]) {
            if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        if (maxX < 0) return src
        val bw = maxX - minX + 1; val bh = maxY - minY + 1
        val pad = (max(bw, bh) * 1.2f).roundToInt().coerceAtLeast(24)
        val rx0 = max(0, minX - pad); val ry0 = max(0, minY - pad)
        val rx1 = min(w - 1, maxX + pad); val ry1 = min(h - 1, maxY + pad)
        val rw = rx1 - rx0 + 1; val rh = ry1 - ry0 + 1

        // уменьшенная копия области
        val scale = max(1f, max(rw, rh) / 360f)
        val sw = max(8, (rw / scale).roundToInt()); val sh = max(8, (rh / scale).roundToInt())
        val region = Bitmap.createBitmap(src, rx0, ry0, rw, rh)
        val small = pixels(Bitmap.createScaledBitmap(region, sw, sh, true))
        val holeS = BooleanArray(sw * sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            // пиксель маленькой копии — дыра, если в его области есть хоть одна точка дыры
            val fx0 = rx0 + (x * scale).toInt(); val fy0 = ry0 + (y * scale).toInt()
            val fx1 = min(rx1, rx0 + ((x + 1) * scale).toInt()); val fy1 = min(ry1, ry0 + ((y + 1) * scale).toInt())
            var any = false
            var yy = fy0
            while (yy <= fy1 && !any) { var xx = fx0; while (xx <= fx1) { if (hole[yy * w + xx]) { any = true; break }; xx++ }; yy++ }
            holeS[y * sw + x] = any
        }
        val origHole = holeS.copyOf()
        val known = BooleanArray(sw * sh) { !holeS[it] }
        val srcIdx = IntArray(sw * sh) { if (known[it]) it else -1 }
        val sr = IntArray(sw * sh) { (small[it] shr 16) and 255 }
        val sg = IntArray(sw * sh) { (small[it] shr 8) and 255 }
        val sb = IntArray(sw * sh) { small[it] and 255 }
        val candidatesPool = (0 until sw * sh).filter { !origHole[it] }.toIntArray()
        if (candidatesPool.isEmpty()) return src
        val rnd = Random(1234)
        val half = 3

        // limit — текущая лучшая цена: если сумма уже больше, дальше не считаем
        fun cost(p: Int, c: Int, limit: Long): Long {
            val px = p % sw; val py = p / sw; val cx = c % sw; val cy = c / sw
            var sum = 0L; var n = 0
            for (oy in -half..half) {
                val qy = py + oy; val ty = cy + oy
                if (qy < 0 || qy >= sh || ty < 0 || ty >= sh) continue
                for (ox in -half..half) {
                    val qx = px + ox; val tx = cx + ox
                    if (qx < 0 || qx >= sw || tx < 0 || tx >= sw) continue
                    val q = qy * sw + qx; val t = ty * sw + tx
                    if (!known[q] || origHole[t]) continue
                    val dr = sr[q] - sr[t]; val dg = sg[q] - sg[t]; val db = sb[q] - sb[t]
                    sum += (dr * dr + dg * dg + db * db).toLong(); n++
                    if (sum > limit) return Long.MAX_VALUE
                }
            }
            if (n < 4) return Long.MAX_VALUE
            return sum * 49 / n
        }

        // послойная заливка «снаружи внутрь»
        var remaining = holeS.count { it }
        var guard = 0
        while (remaining > 0 && guard++ < 4000) {
            val front = ArrayList<Int>()
            for (i in 0 until sw * sh) {
                if (known[i]) continue
                val x = i % sw; val y = i / sw
                if ((x > 0 && known[i - 1]) || (x < sw - 1 && known[i + 1]) || (y > 0 && known[i - sw]) || (y < sh - 1 && known[i + sw])) front.add(i)
            }
            if (front.isEmpty()) break
            front.shuffle(rnd)
            for (p in front) {
                val px = p % sw; val py = p / sw
                var best = -1; var bestCost = Long.MAX_VALUE
                // propagated = кандидат продолжает текстуру соседа — даём ему бонус 15% (цельные куски, без «квадратов»)
                fun tryC(c: Int, propagated: Boolean = false) {
                    if (c < 0 || c >= sw * sh || origHole[c]) return
                    var cc = cost(p, c, bestCost)
                    if (cc == Long.MAX_VALUE) return
                    if (propagated) cc = cc * 85 / 100
                    if (cc < bestCost) { bestCost = cc; best = c }
                }
                // продолжение текстуры соседей
                for (o in intArrayOf(-1, 1, -sw, sw, -sw - 1, -sw + 1, sw - 1, sw + 1)) {
                    val q = p + o
                    if (q < 0 || q >= sw * sh || !known[q]) continue
                    val qx = q % sw
                    if (kotlin.math.abs(qx - px) > 1) continue
                    val s0 = srcIdx[q]
                    if (s0 >= 0) {
                        val cx = s0 % sw + (px - qx); val cy = s0 / sw + (py - q / sw)
                        if (cx in 0 until sw && cy in 0 until sh) tryC(cy * sw + cx, propagated = true)
                    }
                }
                // случайный поиск по фону (ближе — чаще)
                repeat(36) {
                    val c = if (it < 24) {
                        val r = (4 + rnd.nextInt(max(5, max(sw, sh) / 2)))
                        val cx = (px + rnd.nextInt(-r, r + 1)).coerceIn(0, sw - 1)
                        val cy = (py + rnd.nextInt(-r, r + 1)).coerceIn(0, sh - 1)
                        cy * sw + cx
                    } else candidatesPool[rnd.nextInt(candidatesPool.size)]
                    tryC(c)
                }
                if (best < 0) best = candidatesPool[rnd.nextInt(candidatesPool.size)]
                sr[p] = sr[best]; sg[p] = sg[best]; sb[p] = sb[best]
                srcIdx[p] = srcIdx[best].takeIf { it >= 0 } ?: best
                known[p] = true
                remaining--
            }
        }

        // уточнение (как PatchMatch/EM): заново подбираем источник для каждой точки по целым патчам
        // и усредняем перекрывающиеся патчи — вместо «кусков» получается цельная, связная текстура
        refineFill(sr, sg, sb, small, origHole, srcIdx, sw, sh, 5, rnd)

        // перенос на полное разрешение: копируем пиксели из найденных мест фона
        val px = pixels(src)
        val out = px.copyOf()
        // каждый пиксель смешиваем из 4 соседних «источников» (как голосование в PatchMatch) — швов нет
        for (y in max(0, minY)..maxY) for (x in max(0, minX)..maxX) {
            val i = y * w + x
            if (!hole[i]) continue
            val gx = (x - rx0) / scale - 0.5f; val gy = (y - ry0) / scale - 0.5f
            val x0 = kotlin.math.floor(gx).toInt(); val y0 = kotlin.math.floor(gy).toInt()
            val tx = gx - x0; val ty = gy - y0
            var ar = 0f; var ag = 0f; var ab = 0f; var ws = 0f
            for (k in 0 until 4) {
                val ix0 = if (k == 1 || k == 3) x0 + 1 else x0
                val iy0 = if (k >= 2) y0 + 1 else y0
                val wt = (if (k == 1 || k == 3) tx else 1 - tx) * (if (k >= 2) ty else 1 - ty)
                if (wt <= 0f) continue
                val ix = ix0.coerceIn(0, sw - 1); val iy = iy0.coerceIn(0, sh - 1)
                val sp = iy * sw + ix
                val s0 = srcIdx[sp]
                var cr = sr[sp].toFloat(); var cg = sg[sp].toFloat(); var cb = sb[sp].toFloat()
                if (s0 >= 0) {
                    val fx = x + ((s0 % sw - ix) * scale).roundToInt()
                    val fy = y + ((s0 / sw - iy) * scale).roundToInt()
                    if (fx in 0 until w && fy in 0 until h && !hole[fy * w + fx]) {
                        val c = px[fy * w + fx]
                        cr = ((c shr 16) and 255).toFloat(); cg = ((c shr 8) and 255).toFloat(); cb = (c and 255).toFloat()
                    }
                }
                ar += cr * wt; ag += cg * wt; ab += cb * wt; ws += wt
            }
            if (ws > 0f) out[i] = (0xFF shl 24) or (clamp255(ar / ws) shl 16) or (clamp255(ag / ws) shl 8) or clamp255(ab / ws)
        }
        // бесшовная коррекция яркости/цвета по краю (мембрана, как в Poisson-смешивании)
        membraneBlend(px, out, hole, w, h, minX, minY, maxX, maxY)

        // мягкий шов по краю заливки
        val edge = max(1, min(w, h) / 700)
        val bl = boxBlur(out, w, h, edge)
        for (y in max(0, minY - edge)..min(h - 1, maxY + edge)) for (x in max(0, minX - edge)..min(w - 1, maxX + edge)) {
            val i = y * w + x
            var nearEdge = false
            if (hole[i]) {
                if ((x > 0 && !hole[i - 1]) || (x < w - 1 && !hole[i + 1]) || (y > 0 && !hole[i - w]) || (y < h - 1 && !hole[i + w])) nearEdge = true
            }
            if (nearEdge) out[i] = bl[i]
        }
        return fromPixels(out, w, h)
    }

    private fun refineFill(sr: IntArray, sg: IntArray, sb: IntArray, orig: IntArray, hole: BooleanArray, srcIdx: IntArray, sw: Int, sh: Int, iters: Int, rnd: Random) {
        val half = 3
        val n = sw * sh
        val oR = IntArray(n) { (orig[it] shr 16) and 255 }; val og = IntArray(n) { (orig[it] shr 8) and 255 }; val ob = IntArray(n) { orig[it] and 255 }
        val valid = BooleanArray(n)
        for (y in half until sh - half) for (x in half until sw - half) {
            var ok = true
            loop@ for (oy in -half..half) for (ox in -half..half) if (hole[(y + oy) * sw + x + ox]) { ok = false; break@loop }
            valid[y * sw + x] = ok
        }
        val vlist = (0 until n).filter { valid[it] }.toIntArray()
        if (vlist.isEmpty()) return
        val holes = (0 until n).filter { hole[it] }.toIntArray()
        if (holes.isEmpty()) return
        val nnf = IntArray(n) { -1 }
        val cost = DoubleArray(n) { 1e18 }
        fun pc(p: Int, c: Int): Double {
            val px = p % sw; val py = p / sw; val cx = c % sw; val cy = c / sw
            var sum = 0.0; var cnt = 0
            for (oy in -half..half) {
                val qy = py + oy; if (qy < 0 || qy >= sh) continue
                val ty = cy + oy
                for (ox in -half..half) {
                    val qx = px + ox; if (qx < 0 || qx >= sw) continue
                    val q = qy * sw + qx; val t = ty * sw + cx + ox
                    val dr = sr[q] - oR[t]; val dg = sg[q] - og[t]; val db = sb[q] - ob[t]
                    sum += (dr * dr + dg * dg + db * db).toDouble(); cnt++
                }
            }
            return sum / max(cnt, 1)
        }
        for (p in holes) {
            var c = srcIdx[p]
            if (c < 0 || !valid[c]) c = vlist[rnd.nextInt(vlist.size)]
            nnf[p] = c
        }
        val nr = IntArray(n); val ng = IntArray(n); val nb = IntArray(n)
        for (it in 0 until iters) {
            for (p in holes) cost[p] = pc(p, nnf[p])
            val d = if (it % 2 == 0) 1 else -1
            for (k in holes.indices) {
                val p = if (d == 1) holes[k] else holes[holes.size - 1 - k]
                val px = p % sw; val py = p / sw
                // распространение от соседей
                for (dir in 0 until 2) {
                    val q = if (dir == 0) p - d else p - d * sw
                    if (q < 0 || q >= n || !hole[q]) continue
                    if (kotlin.math.abs(q % sw - px) > 1) continue
                    val c = nnf[q]
                    val cx = c % sw + (if (dir == 0) d else 0); val cy = c / sw + (if (dir == 1) d else 0)
                    if (cx in 0 until sw && cy in 0 until sh) {
                        val cc = cy * sw + cx
                        if (valid[cc]) { val v = pc(p, cc); if (v < cost[p]) { cost[p] = v; nnf[p] = cc } }
                    }
                }
                // случайный поиск с уменьшающимся радиусом
                var r = max(sw, sh)
                while (r >= 1) {
                    val c = nnf[p]
                    val cx = (c % sw + rnd.nextInt(-r, r + 1)).coerceIn(0, sw - 1)
                    val cy = (c / sw + rnd.nextInt(-r, r + 1)).coerceIn(0, sh - 1)
                    val cc = cy * sw + cx
                    if (valid[cc]) { val v = pc(p, cc); if (v < cost[p]) { cost[p] = v; nnf[p] = cc } }
                    r /= 2
                }
            }
            // голосование перекрывающихся патчей
            System.arraycopy(sr, 0, nr, 0, n); System.arraycopy(sg, 0, ng, 0, n); System.arraycopy(sb, 0, nb, 0, n)
            for (p in holes) {
                val px = p % sw; val py = p / sw
                var ar = 0.0; var ag = 0.0; var ab = 0.0; var ws = 0.0
                for (oy in -half..half) {
                    val qy = py + oy; if (qy < 0 || qy >= sh) continue
                    for (ox in -half..half) {
                        val qx = px + ox; if (qx < 0 || qx >= sw) continue
                        val q = qy * sw + qx
                        if (!hole[q]) continue
                        val t = nnf[q] - oy * sw - ox
                        if (t < 0 || t >= n) continue
                        val wt = max(kotlin.math.exp(-cost[q] / 1800.0), 1e-4)
                        ar += oR[t] * wt; ag += og[t] * wt; ab += ob[t] * wt; ws += wt
                    }
                }
                if (ws > 0) { nr[p] = (ar / ws + 0.5).toInt(); ng[p] = (ag / ws + 0.5).toInt(); nb[p] = (ab / ws + 0.5).toInt() }
            }
            System.arraycopy(nr, 0, sr, 0, n); System.arraycopy(ng, 0, sg, 0, n); System.arraycopy(nb, 0, sb, 0, n)
        }
        for (p in holes) srcIdx[p] = nnf[p]
    }

    /**
     * Мембранная (Poisson-подобная) коррекция: разница между заливкой и настоящим фоном на краю
     * дыры плавно «растекается» внутрь — граница заливки становится невидимой.
     */
    private fun membraneBlend(orig: IntArray, out: IntArray, hole: BooleanArray, w: Int, h: Int, minX: Int, minY: Int, maxX: Int, maxY: Int) {
        val x0 = max(0, minX - 2); val y0 = max(0, minY - 2); val x1 = min(w - 1, maxX + 2); val y1 = min(h - 1, maxY + 2)
        val rw = x1 - x0 + 1; val rh = y1 - y0 + 1
        val sc = max(1, max(rw, rh) / 160)
        val gw = (rw + sc - 1) / sc; val gh = (rh + sc - 1) / sc
        val acc = Array(3) { DoubleArray(gw * gh) }; val cnt = IntArray(gw * gh); val inside = BooleanArray(gw * gh)
        fun ch(c: Int, k: Int) = (c shr (16 - 8 * k)) and 255
        for (y in y0..y1) for (x in x0..x1) {
            val i = y * w + x
            if (!hole[i]) continue
            val g = ((y - y0) / sc) * gw + (x - x0) / sc
            inside[g] = true
            for (d in 0 until 4) {
                val xx = x + (if (d == 0) 1 else if (d == 1) -1 else 0); val yy = y + (if (d == 2) 1 else if (d == 3) -1 else 0)
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                val j = yy * w + xx
                if (hole[j]) continue
                for (k in 0 until 3) acc[k][g] += (ch(orig[j], k) - ch(out[i], k)).toDouble()
                cnt[g]++
            }
        }
        // сетка с рамкой из нулей; точки границы зафиксированы
        val W = gw + 2; val H = gh + 2
        val m = Array(3) { DoubleArray(W * H) }; val fixed = BooleanArray(W * H) { true }
        for (y in 0 until gh) for (x in 0 until gw) {
            val g = y * gw + x; val G = (y + 1) * W + x + 1
            if (cnt[g] > 0) for (k in 0 until 3) m[k][G] = acc[k][g] / cnt[g]
            else if (inside[g]) fixed[G] = false
        }
        val tmp = DoubleArray(W * H)
        for (k in 0 until 3) {
            val a = m[k]
            repeat(600) {
                System.arraycopy(a, 0, tmp, 0, a.size)
                for (y in 1 until H - 1) for (x in 1 until W - 1) {
                    val G = y * W + x
                    if (!fixed[G]) a[G] = 0.25 * (tmp[G - 1] + tmp[G + 1] + tmp[G - W] + tmp[G + W])
                }
            }
        }
        // билинейно на полное разрешение и прибавляем к заливке
        for (y in y0..y1) for (x in x0..x1) {
            val i = y * w + x
            if (!hole[i]) continue
            val gx = (x - x0 + 0.5f) / sc - 0.5f + 1f; val gy = (y - y0 + 0.5f) / sc - 0.5f + 1f
            val ix = gx.toInt().coerceIn(0, W - 2); val iy = gy.toInt().coerceIn(0, H - 2)
            val tx = (gx - ix).coerceIn(0f, 1f); val ty = (gy - iy).coerceIn(0f, 1f)
            val c = out[i]
            var res = 0xFF shl 24
            for (k in 0 until 3) {
                val a = m[k]
                val v = a[iy * W + ix] * (1 - tx) * (1 - ty) + a[iy * W + ix + 1] * tx * (1 - ty) +
                    a[(iy + 1) * W + ix] * (1 - tx) * ty + a[(iy + 1) * W + ix + 1] * tx * ty
                res = res or (((ch(c, k) + v).toInt().coerceIn(0, 255)) shl (16 - 8 * k))
            }
            out[i] = res
        }
    }

    private fun dilate(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        var cur = m
        repeat(r) {
            val nx = cur.copyOf()
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                if (cur[i]) continue
                if ((x > 0 && cur[i - 1]) || (x < w - 1 && cur[i + 1]) || (y > 0 && cur[i - w]) || (y < h - 1 && cur[i + w])) nx[i] = true
            }
            cur = nx
        }
        return cur
    }

    /** Мягкое размытие краёв кадра (когда человека в кадре нет). */
    fun radialFocusBlur(src: Bitmap, strength: Float): Bitmap {
        val w = src.width; val h = src.height
        val blurred = pixels(fastBlur(src, (4 + strength * 8).roundToInt()))
        val px = pixels(src)
        val cx = w / 2f; val cy = h / 2f
        val rmax = sqrt(cx * cx + cy * cy)
        for (y in 0 until h) for (x in 0 until w) {
            val d = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) / rmax
            val a = (((d - 0.35f) / 0.45f).coerceIn(0f, 1f)) * strength
            if (a < 0.02f) continue
            val i = y * w + x
            val c = px[i]; val b = blurred[i]
            px[i] = (0xFF shl 24) or
                (clamp255(((c shr 16) and 255) * (1 - a) + ((b shr 16) and 255) * a) shl 16) or
                (clamp255(((c shr 8) and 255) * (1 - a) + ((b shr 8) and 255) * a) shl 8) or
                clamp255((c and 255) * (1 - a) + (b and 255) * a)
        }
        return fromPixels(px, w, h)
    }

    /** Восстановление старых фото: убрать желтизну, шум, вернуть контраст и цвет. */
    fun restoreOldPhoto(src: Bitmap): Bitmap {
        var out = denoise(src, 0.8f)
        val st = stats(out)
        out = colorMatrix(out, autoLevelsMatrix(st, 1f, wb = true))
        out = colorMatrix(out, ColorMatrix().apply { setSaturation(if (st.saturation < 0.08f) 1f else 1.25f) }.array)
        out = localToneMap(out, 0.3f, 0.3f, 0.4f)
        return smartSharpen(out, 0.7f)
    }

    /**
     * Проверка маски человека: размываем фон только если человек действительно в кадре
     * и занимает разумную часть (5–85%). Иначе (документ, пейзаж, ложное срабатывание) — не трогаем.
     */
    fun personMaskUsable(mask: FloatArray): Boolean {
        if (mask.isEmpty()) return false
        var fg = 0
        for (v in mask) if (v > 0.6f) fg++
        val part = fg.toFloat() / mask.size
        return part in 0.05f..0.85f
    }

    /** Размытие фона по маске человека (0..1). strength 0..1 — мягкое, как у оптики. */
    fun blurBackground(src: Bitmap, mask: FloatArray, mw: Int, mh: Int, strength: Float): Bitmap {
        if (!personMaskUsable(mask)) return src
        val w = src.width; val h = src.height
        // сглаживаем края маски, чтобы не было резкой «вырезки»
        val maskBmp = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
        val mp = IntArray(mw * mh) { i ->
            val v = ((mask[i] - 0.35f) / 0.4f).coerceIn(0f, 1f)
            val a = (v * 255).toInt()
            (0xFF shl 24) or (a shl 16) or (a shl 8) or a
        }
        maskBmp.setPixels(mp, 0, mw, 0, 0, mw, mh)
        val soft = pixels(Bitmap.createScaledBitmap(fastBlur(maskBmp, 3), w, h, true))
        val blurred = pixels(fastBlur(src, (4 + strength.coerceIn(0f, 1f) * 8).roundToInt()))
        val px = pixels(src)
        for (i in px.indices) {
            val fg = ((soft[i] shr 8) and 255) / 255f
            val a = (1f - fg) * strength.coerceIn(0.3f, 1f)
            if (a < 0.02f) continue
            val c = px[i]; val bb = blurred[i]
            val nr = ((c shr 16) and 255) * (1 - a) + ((bb shr 16) and 255) * a
            val ng = ((c shr 8) and 255) * (1 - a) + ((bb shr 8) and 255) * a
            val nb = (c and 255) * (1 - a) + (bb and 255) * a
            px[i] = (0xFF shl 24) or (clamp255(nr) shl 16) or (clamp255(ng) shl 8) or clamp255(nb)
        }
        return fromPixels(px, w, h)
    }

    /** Варианты нового фона. */
    enum class Backdrop(val title: String, val colors: IntArray) {
        BLUR("Размытие", intArrayOf()),
        WHITE("Белый", intArrayOf(Color.WHITE, Color.WHITE)),
        BLACK("Чёрный", intArrayOf(Color.BLACK, Color.BLACK)),
        STUDIO("Студия", intArrayOf(Color.parseColor("#9E9E9E"), Color.parseColor("#424242"))),
        SUNSET("Закат", intArrayOf(Color.parseColor("#FF7E5F"), Color.parseColor("#FEB47B"))),
        OCEAN("Океан", intArrayOf(Color.parseColor("#2193B0"), Color.parseColor("#6DD5ED"))),
        NEON("Неон", intArrayOf(Color.parseColor("#FF2E63"), Color.parseColor("#7C4DFF"))),
        MINT("Мята", intArrayOf(Color.parseColor("#B2F5EA"), Color.parseColor("#E6FFFA"))),
    }

    /** Замена фона за человеком (маска ML Kit) на размытие, цвет или градиент. */
    fun replaceBackground(src: Bitmap, mask: FloatArray, mw: Int, mh: Int, backdrop: Backdrop): Bitmap {
        if (!personMaskUsable(mask)) return src
        if (backdrop == Backdrop.BLUR) return blurBackground(src, mask, mw, mh, 0.9f)
        val w = src.width; val h = src.height
        val bg = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bg).drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), backdrop.colors, null, Shader.TileMode.CLAMP)
        })
        val maskBmp = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
        maskBmp.setPixels(IntArray(mw * mh) { i ->
            val a = (((mask[i] - 0.3f) / 0.45f).coerceIn(0f, 1f) * 255).toInt()
            (0xFF shl 24) or (a shl 16) or (a shl 8) or a
        }, 0, mw, 0, 0, mw, mh)
        val soft = pixels(Bitmap.createScaledBitmap(fastBlur(maskBmp, 2), w, h, true))
        val px = pixels(src); val bp = pixels(bg)
        for (i in px.indices) {
            val fg = ((soft[i] shr 8) and 255) / 255f
            val c = px[i]; val b = bp[i]
            px[i] = (0xFF shl 24) or
                (clamp255(((c shr 16) and 255) * fg + ((b shr 16) and 255) * (1 - fg)) shl 16) or
                (clamp255(((c shr 8) and 255) * fg + ((b shr 8) and 255) * (1 - fg)) shl 8) or
                clamp255((c and 255) * fg + (b and 255) * (1 - fg))
        }
        return fromPixels(px, w, h)
    }

    /** Пресеты неба. */
    enum class Sky(val title: String, val top: Int, val bottom: Int, val stars: Boolean = false, val clouds: Boolean = false) {
        BLUE("Голубое", Color.parseColor("#1E6FD9"), Color.parseColor("#9FD3FF"), clouds = true),
        SUNSET("Закат", Color.parseColor("#3B2C6E"), Color.parseColor("#FF8A50")),
        GOLDEN("Золотой час", Color.parseColor("#F6A04D"), Color.parseColor("#FFE3A3")),
        PINK("Розовое", Color.parseColor("#B06AB3"), Color.parseColor("#FFC3D8")),
        NIGHT("Звёздное", Color.parseColor("#05081C"), Color.parseColor("#1B2A5C"), stars = true),
        DRAMA("Драма", Color.parseColor("#2C3E50"), Color.parseColor("#8E9EAB"), clouds = true),
    }

    /**
     * Замена неба: находим небо (светлые/голубые однородные области, связанные с верхним краем кадра),
     * плавно заменяем на выбранное и слегка подкрашиваем остальное в тон нового неба.
     * Возвращает null, если неба в кадре нет.
     */
    fun replaceSky(src: Bitmap, sky: Sky): Bitmap? {
        val sw = 256
        val sh = max(16, (src.height * sw.toFloat() / src.width).roundToInt())
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val sp = pixels(small)
        val blur = boxBlur(sp, sw, sh, 2)
        val cand = BooleanArray(sw * sh)
        for (i in sp.indices) {
            val c = sp[i]
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            val l = (r * 0.299f + g * 0.587f + b * 0.114f) / 255f
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            val sat = if (mx == 0) 0f else (mx - mn) / mx.toFloat()
            val bl = blur[i]
            val texture = (abs(r - ((bl shr 16) and 255)) + abs(g - ((bl shr 8) and 255)) + abs(b - (bl and 255))) / 3f
            val blueSky = b > r + 8 && b >= g - 5 && l > 0.35f
            val whiteSky = l > 0.72f && sat < 0.22f
            val sunsetSky = r > 150 && l > 0.45f && sat > 0.25f && i / sw < sh / 2
            cand[i] = (blueSky || whiteSky || sunsetSky) && texture < 9f
        }
        // заливка от верхнего края: небо должно касаться верха кадра
        val sky01 = BooleanArray(sw * sh)
        val stack = ArrayDeque<Int>()
        for (x in 0 until sw) if (cand[x]) { sky01[x] = true; stack.add(x) }
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            val x = i % sw; val y = i / sw
            val nb = intArrayOf(if (x > 0) i - 1 else -1, if (x < sw - 1) i + 1 else -1, if (y > 0) i - sw else -1, if (y < sh - 1) i + sw else -1)
            for (j in nb) if (j >= 0 && !sky01[j] && cand[j]) { sky01[j] = true; stack.add(j) }
        }
        val count = sky01.count { it }
        if (count < sw * sh / 25) return null
        val maskSmall = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        maskSmall.setPixels(IntArray(sw * sh) { if (sky01[it]) -1 else (0xFF shl 24) }, 0, sw, 0, 0, sw, sh)
        val w = src.width; val h = src.height
        val soft = pixels(Bitmap.createScaledBitmap(fastBlur(maskSmall, 2), w, h, true))
        // нижняя граница неба — чтобы градиент растянуть по высоте неба
        var horizon = 0
        for (y in 0 until sh) { var any = false; for (x in 0 until sw) if (sky01[y * sw + x]) { any = true; break }; if (any) horizon = y }
        val horizonPx = ((horizon + 1) * h / sh).coerceAtLeast(h / 4)
        val newSky = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val sc = Canvas(newSky)
        sc.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 0f, horizonPx.toFloat(), sky.top, sky.bottom, Shader.TileMode.CLAMP)
        })
        val rnd = Random(7)
        if (sky.stars) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            repeat(260) { p.alpha = 120 + rnd.nextInt(135); sc.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * horizonPx, w * (0.0008f + rnd.nextFloat() * 0.0018f), p) }
        }
        if (sky.clouds) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            repeat(14) {
                val cx = rnd.nextFloat() * w; val cy = rnd.nextFloat() * horizonPx * 0.8f; val r = w * (0.08f + rnd.nextFloat() * 0.12f)
                p.shader = RadialGradient(cx, cy, r, intArrayOf(Color.argb(170, 255, 255, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                sc.drawOval(RectF(cx - r * 1.6f, cy - r * 0.6f, cx + r * 1.6f, cy + r * 0.6f), p)
            }
        }
        val px = pixels(src); val np = pixels(newSky)
        val tr = Color.red(sky.bottom); val tg = Color.green(sky.bottom); val tb = Color.blue(sky.bottom)
        for (i in px.indices) {
            val a = ((soft[i] shr 8) and 255) / 255f
            val c = px[i]; val n = np[i]
            var r = ((c shr 16) and 255).toFloat(); var g = ((c shr 8) and 255).toFloat(); var b = (c and 255).toFloat()
            // лёгкая подкраска земли в тон неба (8%)
            r += (tr - r) * 0.08f * (1 - a); g += (tg - g) * 0.08f * (1 - a); b += (tb - b) * 0.08f * (1 - a)
            if (sky == Sky.NIGHT) { val k = 1f - 0.45f * (1 - a); r *= k; g *= k; b *= k }
            px[i] = (0xFF shl 24) or
                (clamp255(r * (1 - a) + ((n shr 16) and 255) * a) shl 16) or
                (clamp255(g * (1 - a) + ((n shr 8) and 255) * a) shl 8) or
                clamp255(b * (1 - a) + (n and 255) * a)
        }
        return fromPixels(px, w, h)
    }

    /** Надписи и стикеры (эмодзи) поверх фото. Координаты нормализованы. */
    data class TextOverlay(
        val id: Long, val text: String, val x: Float, val y: Float, val size: Float,
        val color: Int = Color.WHITE, val emoji: Boolean = false, val style: Int = 0,
    )

    fun drawOverlays(src: Bitmap, items: List<TextOverlay>): Bitmap {
        if (items.isEmpty()) return src
        val out = ensureMutable(src.copy(Bitmap.Config.ARGB_8888, true))
        val c = Canvas(out)
        for (it in items) {
            val ts = it.size * out.width
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = ts; textAlign = Paint.Align.CENTER; color = it.color
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            }
            val x = it.x * out.width
            val y = it.y * out.height - (p.descent() + p.ascent()) / 2
            if (!it.emoji) when (it.style) {
                1 -> { // плашка
                    val tw = p.measureText(it.text)
                    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 0, 0, 0) }
                    c.drawRoundRect(RectF(x - tw / 2 - ts * 0.35f, it.y * out.height - ts * 0.75f, x + tw / 2 + ts * 0.35f, it.y * out.height + ts * 0.75f), ts * 0.3f, ts * 0.3f, bg)
                }
                2 -> { // обводка
                    val st = Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = ts * 0.12f; color = Color.BLACK }
                    c.drawText(it.text, x, y, st)
                }
                else -> p.setShadowLayer(ts * 0.12f, 0f, ts * 0.05f, Color.argb(160, 0, 0, 0))
            }
            c.drawText(it.text, x, y, p)
        }
        return out
    }

    /** Эффекты на фото (аналог шейдера). */
    fun applyEffect(src: Bitmap, effect: Effect, seed: Long = System.currentTimeMillis()): Bitmap {
        if (effect == Effect.NONE || effect == Effect.BG_BLUR) return src
        val rnd = Random(seed)
        val w = src.width; val h = src.height
        val out = ensureMutable(src.copy(Bitmap.Config.ARGB_8888, true))
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        when (effect) {
            Effect.GRAIN, Effect.FILM -> {
                val px = pixels(out)
                val amp = if (effect == Effect.FILM) 18 else 30
                for (i in px.indices) {
                    val n = rnd.nextInt(amp * 2 + 1) - amp
                    val col = px[i]
                    var r = ((col shr 16) and 255) + n; var g = ((col shr 8) and 255) + n; var b = (col and 255) + n
                    if (effect == Effect.FILM) { r = (r * 1.03f + 8).toInt(); b = (b * 0.9f + 8).toInt(); g += 6 }
                    px[i] = (0xFF shl 24) or (clamp255(r) shl 16) or (clamp255(g) shl 8) or clamp255(b)
                }
                val res = fromPixels(px, w, h)
                return if (effect == Effect.FILM) vignetteAndFade(res, 0.5f, 0.15f) else res
            }
            Effect.GLITCH -> {
                val px = pixels(out); val dst = px.copyOf()
                val shift = max(2, w / 120)
                for (y in 0 until h) {
                    val band = if (rnd.nextFloat() < 0.02f) rnd.nextInt(w / 20 + 1) else 0
                    for (x in 0 until w) {
                        val rx = (x + shift + band).coerceIn(0, w - 1); val bx = (x - shift + band).coerceIn(0, w - 1)
                        val g = px[y * w + (x + band).coerceIn(0, w - 1)]
                        dst[y * w + x] = (0xFF shl 24) or (px[y * w + rx] and 0xFF0000) or (g and 0x00FF00) or (px[y * w + bx] and 0xFF)
                    }
                }
                return fromPixels(dst, w, h)
            }
            Effect.RAINBOW -> {
                p.shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
                    intArrayOf(0x55FF0000, 0x55FFA500, 0x55FFFF00, 0x5500FF00, 0x550000FF, 0x558B00FF),
                    null, Shader.TileMode.CLAMP)
                p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN)
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            }
            Effect.RAIN -> {
                c.drawColor(0x22000814)
                p.color = 0x99D0DCF0.toInt(); p.strokeWidth = max(1f, w / 900f)
                repeat((w * h / 6000).coerceAtMost(3000)) {
                    val x = rnd.nextFloat() * w; val y = rnd.nextFloat() * h; val len = h * (0.02f + rnd.nextFloat() * 0.04f)
                    c.drawLine(x, y, x - len * 0.15f, y + len, p)
                }
            }
            Effect.SNOW -> {
                p.color = Color.WHITE
                repeat((w * h / 9000).coerceAtMost(2500)) {
                    val r = w * (0.0015f + rnd.nextFloat() * 0.004f)
                    p.alpha = 150 + rnd.nextInt(105)
                    c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, r, p)
                }
            }
            Effect.BOKEH -> {
                repeat(28) {
                    val r = w * (0.03f + rnd.nextFloat() * 0.05f)
                    val x = rnd.nextFloat() * w; val y = rnd.nextFloat() * h
                    val col = if (rnd.nextBoolean()) Color.argb(70, 255, 190, 110) else Color.argb(60, 255, 110, 150)
                    p.shader = RadialGradient(x, y, r, intArrayOf(col, col, Color.TRANSPARENT), floatArrayOf(0f, 0.75f, 1f), Shader.TileMode.CLAMP)
                    c.drawCircle(x, y, r, p)
                }
            }
            Effect.LIGHT_RAYS -> {
                val ox = -w * 0.1f; val oy = -h * 0.05f
                p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN)
                for (i in 0 until 9) {
                    val ang = Math.toRadians(20.0 + i * 7.5 + rnd.nextDouble() * 3)
                    val len = max(w, h) * 1.3f
                    val path = android.graphics.Path().apply {
                        moveTo(ox, oy)
                        lineTo(ox + (len * kotlin.math.cos(ang - 0.02)).toFloat(), oy + (len * kotlin.math.sin(ang - 0.02)).toFloat())
                        lineTo(ox + (len * kotlin.math.cos(ang + 0.02)).toFloat(), oy + (len * kotlin.math.sin(ang + 0.02)).toFloat())
                        close()
                    }
                    p.shader = RadialGradient(ox, oy, len, intArrayOf(0x66FFE0A8, 0x00FFE0A8), null, Shader.TileMode.CLAMP)
                    c.drawPath(path, p)
                }
            }
            Effect.CONFETTI -> repeat(160) {
                p.color = android.graphics.Color.HSVToColor(floatArrayOf(rnd.nextFloat() * 360f, 0.8f, 1f))
                val cx = rnd.nextFloat() * w; val cy = rnd.nextFloat() * h; val s = w * 0.008f
                c.save(); c.rotate(rnd.nextFloat() * 360f, cx, cy)
                c.drawRect(cx - s, cy - s * 0.5f, cx + s, cy + s * 0.5f, p)
                c.restore()
            }
            Effect.STARS -> {
                p.color = Color.argb(230, 255, 245, 210)
                repeat(70) {
                    val x = rnd.nextFloat() * w; val y = rnd.nextFloat() * h; val s = w * (0.004f + rnd.nextFloat() * 0.01f)
                    p.strokeWidth = s * 0.25f
                    c.drawLine(x - s, y, x + s, y, p); c.drawLine(x, y - s, x, y + s, p)
                    c.drawCircle(x, y, s * 0.25f, p)
                }
            }
            Effect.LEAVES -> repeat(45) {
                p.color = if (rnd.nextBoolean()) Color.argb(230, 230, 115, 25) else Color.argb(230, 200, 50, 25)
                val x = rnd.nextFloat() * w; val y = rnd.nextFloat() * h; val s = w * 0.015f
                c.save(); c.rotate(rnd.nextFloat() * 360f, x, y)
                c.drawOval(RectF(x - s, y - s * 0.45f, x + s, y + s * 0.45f), p)
                c.restore()
            }
            Effect.FIREWORKS -> repeat(3) {
                val cx = w * (0.2f + rnd.nextFloat() * 0.6f); val cy = h * (0.1f + rnd.nextFloat() * 0.3f)
                val r = w * (0.12f + rnd.nextFloat() * 0.1f)
                p.color = android.graphics.Color.HSVToColor(floatArrayOf(rnd.nextFloat() * 360f, 0.7f, 1f))
                p.strokeWidth = w * 0.003f
                for (k in 0 until 36) {
                    val a = Math.toRadians(k * 10.0)
                    c.drawLine(cx + (kotlin.math.cos(a) * r * 0.3).toFloat(), cy + (kotlin.math.sin(a) * r * 0.3).toFloat(),
                        cx + (kotlin.math.cos(a) * r).toFloat(), cy + (kotlin.math.sin(a) * r).toFloat(), p)
                }
            }
            Effect.FIRE -> {
                p.shader = android.graphics.LinearGradient(0f, h.toFloat(), 0f, h * 0.6f,
                    intArrayOf(Color.argb(200, 255, 120, 10), Color.argb(90, 255, 200, 60), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN)
                c.drawRect(0f, h * 0.6f, w.toFloat(), h.toFloat(), p)
            }
            Effect.SMOKE -> {
                p.shader = android.graphics.LinearGradient(0f, h.toFloat(), 0f, h * 0.3f,
                    intArrayOf(Color.argb(150, 220, 220, 220), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                c.drawRect(0f, h * 0.3f, w.toFloat(), h.toFloat(), p)
            }
            else -> Unit
        }
        return out
    }
}
