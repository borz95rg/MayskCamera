package com.maysk.camera.media

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Склейка панорамы без чёрных полос и «ступенек».
 *
 * 1. Каждый кадр проецируется на цилиндр — при повороте камеры кадры тогда сдвигаются
 *    ровно по горизонтали, без «изгиба» на стыках.
 * 2. Сдвиг между соседними кадрами берётся из гироскопа и уточняется поиском совпадения
 *    по контурам (не зависит от разницы яркости).
 * 3. Яркость кадров выравнивается по общей зоне.
 * 4. От каждого кадра берётся только центральная часть (края с виньеткой отбрасываются),
 *    шов сглаживается только внутри реального перекрытия.
 *
 * Работает на массивах ARGB, чтобы алгоритм можно было проверить вне Android.
 */
object PanoramaStitcher {

    class Img(val w: Int, val h: Int, val px: IntArray)

    /** Порог несовпадения контуров (отношение ошибки к средней силе контуров). */
    var maxMismatch = 1.15f
    /** Причина последней неудачи (для журнала). */
    var lastError: String? = null
    /** Отношения ошибки по парам (для журнала и настройки порога). */
    val lastRatios = ArrayList<Float>()

    private class Warped(val w: Int, val h: Int, val px: IntArray, val valid: BooleanArray)

    /**
     * [yaws] — угол поворота (рад) в момент каждого кадра, может быть null.
     * [tanHalfFov] — tan половины горизонтального угла обзора кадра.
     */
    fun stitch(frames: List<Img>, yaws: FloatArray?, tanHalfFov: Float): Img? {
        lastError = null; lastRatios.clear()
        if (frames.size < 2) return frames.firstOrNull()
        val w = frames[0].w; val h = frames[0].h
        if (frames.any { it.w != w || it.h != h }) return null
        val f = (w / 2f) / tanHalfFov.coerceIn(0.2f, 2f)
        val warped = frames.map { cylindrical(it, f) }

        // ---------- попарные сдвиги ----------
        val sc = 256f / w
        val sw = 256; val sh = max(16, (h * sc).roundToInt())
        val small = warped.map { edges(downscale(it, sw, sh), sw, sh) }
        val smallValid = warped.map { downValid(it, sw, sh) }
        val shifts = ArrayList<Pair<Float, Float>>()
        var sign = 0
        for (i in 1 until warped.size) {
            val pred = yaws?.let { abs(it[i] - it[i - 1]) * f }
            val r = bestShift(small[i - 1], smallValid[i - 1], small[i], smallValid[i], sw, sh, pred?.let { it * sc }, sign)
                ?: return null
            if (sign == 0) sign = if (r.first >= 0) 1 else -1
            shifts += (r.first / sc) to (r.second / sc)
        }

        // ---------- уточнение на большем масштабе ----------
        val sc2 = min(1f, 640f / w)
        val mw = (w * sc2).roundToInt(); val mh = (h * sc2).roundToInt()
        val mid = warped.map { edges(downscale(it, mw, mh), mw, mh) }
        val midValid = warped.map { downValid(it, mw, mh) }
        val rots = FloatArray(warped.size)   // поворот кадра i относительно i-1 (рад)
        for (i in shifts.indices) {
            val (dx, dy) = shifts[i]
            var r = refine(mid[i], midValid[i], mid[i + 1], midValid[i + 1], mw, mh, dx * sc2, dy * sc2, 3, 0f)
            // поиск небольшого поворота (наклон телефона): −2°…+2°
            var bestTh = 0f
            var bestC = cost(mid[i], midValid[i], mid[i + 1], midValid[i + 1], mw, mh, r.first.toInt(), r.second.toInt(), 1, 0f)
            var th = -0.035f
            while (th <= 0.0351f) {
                if (abs(th) > 1e-4f) {
                    val rr = refine(mid[i], midValid[i], mid[i + 1], midValid[i + 1], mw, mh, r.first, r.second, 2, th)
                    val c = cost(mid[i], midValid[i], mid[i + 1], midValid[i + 1], mw, mh, rr.first.toInt(), rr.second.toInt(), 1, th)
                    if (c < bestC) { bestC = c; bestTh = th; r = rr }
                }
                th += 0.00875f
            }
            // проверка качества: если контуры в зоне перекрытия не совпали — честный отказ, а не «рваная» полоса
            val norm = meanEdge(mid[i], midValid[i], mw, mh)
            lastRatios += if (norm > 0f) bestC / norm else -1f
            if (bestC == Float.MAX_VALUE || norm <= 0f || bestC / norm > maxMismatch) { lastError = "mismatch ${bestC / norm}"; return null }
            shifts[i] = (r.first / sc2) to (r.second / sc2)
            rots[i + 1] = bestTh
        }
        val thetas = FloatArray(warped.size)
        for (i in 1 until warped.size) thetas[i] = thetas[i - 1] + rots[i]

        // ---------- позиции ----------
        val xs = FloatArray(warped.size); val ys = FloatArray(warped.size)
        for (i in 1 until warped.size) { xs[i] = xs[i - 1] + shifts[i - 1].first; ys[i] = ys[i - 1] + shifts[i - 1].second }

        // ---------- выравнивание яркости ----------
        // экспозиция и баланс белого — по каждому каналу отдельно, по зоне перекрытия
        val gains = Array(warped.size) { FloatArray(3) { 1f } }
        for (i in 1 until warped.size) {
            val m = overlapMeans(warped[i - 1], warped[i], shifts[i - 1].first.roundToInt(), shifts[i - 1].second.roundToInt())
            for (ch in 0 until 3) {
                val g = if (m != null && m[3 + ch] > 1f) (m[ch] / m[3 + ch]).coerceIn(0.7f, 1.45f) else 1f
                gains[i][ch] = gains[i - 1][ch] * g
            }
        }
        for (ch in 0 until 3) {
            val gm = Math.exp(gains.map { Math.log(it[ch].toDouble()) }.average()).toFloat()
            for (i in gains.indices) gains[i][ch] /= gm
        }

        // ---------- швы ----------
        val order = xs.indices.sortedBy { xs[it] }
        val n = order.size
        val left = FloatArray(n); val right = FloatArray(n); val feather = FloatArray(n + 1)
        val trim = w * 0.07f
        for (k in 0 until n) {
            val i = order[k]
            left[k] = if (k == 0) xs[i] + trim else (xs[order[k - 1]] + xs[i]) / 2f + w / 2f
            right[k] = if (k == n - 1) xs[i] + w - trim else (xs[i] + xs[order[k + 1]]) / 2f + w / 2f
        }
        for (k in 1 until n) {
            val overlap = xs[order[k - 1]] + w - xs[order[k]]
            feather[k] = (min(w * 0.07f, overlap / 3f)).coerceAtLeast(2f)
        }

        // ---------- рамка результата (без пустых краёв) ----------
        val x0 = left[0]; val x1 = right[n - 1]
        val minY = ys.min()
        var top = 0f; var bottom = Float.MAX_VALUE
        val cy = h / 2f; val cx = w / 2f
        for (k in 0 until n) {
            val i = order[k]
            val usedL = left[k] - feather[k] - xs[i]; val usedR = right[k] + feather[k + 1] - xs[i]
            val th = max(abs(usedL - cx), abs(usedR - cx)) / f
            val band = cy * (1f - cos(th.toDouble()).toFloat()) + 2f
            top = max(top, ys[i] - minY + band)
            bottom = min(bottom, ys[i] - minY + h - band)
        }
        val outW = (x1 - x0).toInt(); val outH = (bottom - top).toInt()
        if (outW < w || outH < h / 2) return null

        // ---------- сборка: смешивание в двух полосах частот ----------
        // Низкие частоты (цвет, свет) — плавно по всей зоне перекрытия, высокие (детали) — узким швом.
        // Так нет ни видимой «ступеньки» яркости, ни двойных контуров.
        val lows = warped.map { lowPass(it, max(4, w / 30)) }
        val narrow = max(2f, w / 160f)
        val out = IntArray(outW * outH)
        val wL = FloatArray(n); val wN = FloatArray(n)
        val cosT = FloatArray(n) { cos(thetas[order[it]].toDouble()).toFloat() }
        val sinT = FloatArray(n) { kotlin.math.sin(thetas[order[it]].toDouble()).toFloat() }
        for (ox in 0 until outW) {
            val gx = x0 + ox
            var any = false
            for (k in 0 until n) {
                val fl = feather[k]; val fr = feather[k + 1]
                val a = if (k == 0) (if (gx >= left[0]) 1f else 0f) else ((gx - (left[k] - fl)) / (2 * fl)).coerceIn(0f, 1f)
                val b = if (k == n - 1) (if (gx <= right[k]) 1f else 0f) else (((right[k] + fr) - gx) / (2 * fr)).coerceIn(0f, 1f)
                wL[k] = min(a, b); if (wL[k] > 0f) any = true
                val an = if (k == 0) a else ((gx - (left[k] - narrow)) / (2 * narrow)).coerceIn(0f, 1f)
                val bn = if (k == n - 1) b else (((right[k] + narrow) - gx) / (2 * narrow)).coerceIn(0f, 1f)
                wN[k] = min(an, bn)
            }
            if (!any) continue
            for (oy in 0 until outH) {
                var lr = 0f; var lg = 0f; var lb = 0f; var lw = 0f
                var hr = 0f; var hg = 0f; var hb = 0f; var hw = 0f
                for (k in 0 until n) {
                    val wl = wL[k]; val wn = wN[k]
                    if (wl <= 0f && wn <= 0f) continue
                    val i = order[k]; val img = warped[i]
                    // глобальная точка → локальные координаты кадра (сдвиг + поворот вокруг центра)
                    val px = gx - xs[i] - cx; val py = oy + top + minY - ys[i] - cy
                    val fx = (cosT[k] * px - sinT[k] * py + cx).roundToInt()
                    val fy = (sinT[k] * px + cosT[k] * py + cy).roundToInt()
                    if (fx < 0 || fy < 0 || fx >= w || fy >= h) continue
                    val idx = fy * w + fx
                    if (!img.valid[idx]) continue
                    val c = img.px[idx]; val lo = lows[i][idx]; val gn = gains[i]
                    val cr = ((c shr 16) and 255) * gn[0]; val cg = ((c shr 8) and 255) * gn[1]; val cb = (c and 255) * gn[2]
                    val r0 = ((lo shr 16) and 255) * gn[0]; val g0 = ((lo shr 8) and 255) * gn[1]; val b0 = (lo and 255) * gn[2]
                    if (wl > 0f) { lr += r0 * wl; lg += g0 * wl; lb += b0 * wl; lw += wl }
                    if (wn > 0f) { hr += (cr - r0) * wn; hg += (cg - g0) * wn; hb += (cb - b0) * wn; hw += wn }
                }
                if (lw > 0f) {
                    val r = lr / lw + (if (hw > 0f) hr / hw else 0f)
                    val g = lg / lw + (if (hw > 0f) hg / hw else 0f)
                    val bl = lb / lw + (if (hw > 0f) hb / hw else 0f)
                    out[oy * outW + ox] = (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or bl.toInt().coerceIn(0, 255)
                }
            }
        }
        return Img(outW, outH, out)
    }

    // ------------------------------------------------------------------

    private fun cylindrical(src: Img, f: Float): Warped {
        val w = src.w; val h = src.h
        val cx = (w - 1) / 2f; val cy = (h - 1) / 2f
        val out = IntArray(w * h); val valid = BooleanArray(w * h)
        val sx = FloatArray(w); val cosT = FloatArray(w)
        for (x in 0 until w) {
            val th = (x - cx) / f
            sx[x] = cx + f * tan(th.toDouble()).toFloat()
            cosT[x] = cos(th.toDouble()).toFloat()
        }
        val p = src.px
        for (y in 0 until h) for (x in 0 until w) {
            val fx = sx[x]; val fy = cy + (y - cy) / cosT[x]
            if (fx < 0f || fy < 0f || fx > w - 1.001f || fy > h - 1.001f) continue
            val ix = fx.toInt(); val iy = fy.toInt(); val ax = fx - ix; val ay = fy - iy
            val i0 = iy * w + ix
            val c00 = p[i0]; val c10 = p[i0 + 1]; val c01 = p[i0 + w]; val c11 = p[i0 + w + 1]
            fun ch(s: Int): Int {
                val v = ((c00 shr s) and 255) * (1 - ax) * (1 - ay) + ((c10 shr s) and 255) * ax * (1 - ay) +
                    ((c01 shr s) and 255) * (1 - ax) * ay + ((c11 shr s) and 255) * ax * ay
                return (v + 0.5f).toInt().coerceIn(0, 255)
            }
            out[y * w + x] = (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            valid[y * w + x] = true
        }
        return Warped(w, h, out, valid)
    }

    private fun luma(c: Int) = (((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 150 + (c and 255) * 29) shr 8

    private fun downscale(img: Warped, tw: Int, th: Int): FloatArray {
        val out = FloatArray(tw * th)
        val fx = img.w.toFloat() / tw; val fy = img.h.toFloat() / th
        for (y in 0 until th) for (x in 0 until tw) {
            var s = 0f; var n = 0
            val x0 = (x * fx).toInt(); val x1 = max(x0 + 1, ((x + 1) * fx).toInt()).coerceAtMost(img.w)
            val y0 = (y * fy).toInt(); val y1 = max(y0 + 1, ((y + 1) * fy).toInt()).coerceAtMost(img.h)
            var yy = y0
            while (yy < y1) { var xx = x0; while (xx < x1) { val i = yy * img.w + xx; if (img.valid[i]) { s += luma(img.px[i]); n++ }; xx += 1 }; yy += 1 }
            out[y * tw + x] = if (n > 0) s / n else -1f
        }
        return out
    }

    private fun downValid(img: Warped, tw: Int, th: Int): BooleanArray {
        val out = BooleanArray(tw * th)
        val fx = img.w.toFloat() / tw; val fy = img.h.toFloat() / th
        for (y in 0 until th) for (x in 0 until tw) {
            // допустимо, только если все четыре угла ячейки валидны (края цилиндра не участвуют)
            val xa = (x * fx).toInt().coerceIn(0, img.w - 1); val xb = (((x + 1) * fx).toInt() - 1).coerceIn(0, img.w - 1)
            val ya = (y * fy).toInt().coerceIn(0, img.h - 1); val yb = (((y + 1) * fy).toInt() - 1).coerceIn(0, img.h - 1)
            out[y * tw + x] = img.valid[ya * img.w + xa] && img.valid[ya * img.w + xb] && img.valid[yb * img.w + xa] && img.valid[yb * img.w + xb]
        }
        return out
    }

    /** Карта контуров (градиент яркости) — устойчива к разнице экспозиции между кадрами. */
    private fun edges(l: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (l[i - 1] < 0 || l[i + 1] < 0 || l[i - w] < 0 || l[i + w] < 0) { out[i] = -1f; continue }
            val gx = l[i + 1] - l[i - 1]; val gy = l[i + w] - l[i - w]
            out[i] = sqrt(gx * gx + gy * gy)
        }
        for (x in 0 until w) { out[x] = -1f; out[(h - 1) * w + x] = -1f }
        for (y in 0 until h) { out[y * w] = -1f; out[y * w + w - 1] = -1f }
        return out
    }

    /** Стоимость совпадения b, сдвинутого на (dx, dy) относительно a: b(x,y) ≈ a(x+dx, y+dy). */
    private fun cost(a: FloatArray, va: BooleanArray, b: FloatArray, vb: BooleanArray, w: Int, h: Int, dx: Int, dy: Int, step: Int, th: Float = 0f): Float {
        var s = 0f; var n = 0
        val ct = cos(th.toDouble()).toFloat(); val st = kotlin.math.sin(th.toDouble()).toFloat()
        val cx = w / 2f; val cy = h / 2f
        var y = 1
        while (y < h - 1) {
            val ay = y + dy
            if (ay in 1 until h - 1) {
                var x = 1
                while (x < w - 1) {
                    val ax = x + dx
                    // точка кадра b с учётом поворота кадра относительно предыдущего
                    val bx = if (th == 0f) x else (ct * (x - cx) - st * (y - cy) + cx).roundToInt()
                    val by = if (th == 0f) y else (st * (x - cx) + ct * (y - cy) + cy).roundToInt()
                    if (ax in 1 until w - 1 && bx in 1 until w - 1 && by in 1 until h - 1) {
                        val ib = by * w + bx; val ia = ay * w + ax
                        val bv = b[ib]; val av = a[ia]
                        if (bv >= 0f && av >= 0f && va[ia] && vb[ib]) { s += abs(av - bv); n++ }
                    }
                    x += step
                }
            }
            y += step
        }
        val minN = (w * h) / (step * step * 12)
        return if (n < minN) Float.MAX_VALUE else s / n
    }

    private fun bestShift(a: FloatArray, va: BooleanArray, b: FloatArray, vb: BooleanArray, w: Int, h: Int, pred: Float?, sign: Int): Pair<Float, Float>? {
        val maxDy = max(2, h / 18)
        val (lo, hi) = if (pred != null && pred > w * 0.06f) (pred * 0.45f).toInt().coerceAtLeast(w / 14) to (pred * 1.8f).toInt().coerceAtMost(w * 9 / 10)
        else (w / 14) to (w * 9 / 10)
        val signs = if (sign == 0) listOf(1, -1) else listOf(sign)
        var best = Float.MAX_VALUE; var bx = 0; var by = 0
        for (sg in signs) for (m in lo..hi step 2) for (dy in -maxDy..maxDy step 2) {
            var c = cost(a, va, b, vb, w, h, sg * m, dy, 3)
            if (c == Float.MAX_VALUE) continue
            // мягкое предпочтение сдвигу по гироскопу
            if (pred != null && pred > w * 0.06f) c *= 1f + 0.12f * abs(m - pred) / pred
            c *= 1f + 0.01f * abs(dy)
            if (c < best) { best = c; bx = sg * m; by = dy }
        }
        if (best == Float.MAX_VALUE) return null
        return refine(a, va, b, vb, w, h, bx.toFloat(), by.toFloat(), 2)
    }

    private fun refine(a: FloatArray, va: BooleanArray, b: FloatArray, vb: BooleanArray, w: Int, h: Int, dx: Float, dy: Float, r: Int, th: Float = 0f): Pair<Float, Float> {
        val cx = dx.roundToInt(); val cyy = dy.roundToInt()
        var best = Float.MAX_VALUE; var bx = cx; var by = cyy
        for (x in cx - r..cx + r) for (y in cyy - r..cyy + r) {
            val c = cost(a, va, b, vb, w, h, x, y, if (th == 0f) 1 else 2, th)
            if (c < best) { best = c; bx = x; by = y }
        }
        return bx.toFloat() to by.toFloat()
    }

    /** Средние R,G,B общей зоны в кадре a (0..2) и в кадре b (3..5) — для выравнивания экспозиции и ББ. */
    private fun overlapMeans(a: Warped, b: Warped, dx: Int, dy: Int): FloatArray? {
        val sum = DoubleArray(6); var n = 0
        val w = a.w; val h = a.h
        var y = 0
        while (y < h) {
            val ay = y + dy
            if (ay in 0 until h) {
                var x = 0
                while (x < w) {
                    val ax = x + dx
                    if (ax in 0 until w) {
                        val ib = y * w + x; val ia = ay * w + ax
                        if (a.valid[ia] && b.valid[ib]) {
                            val ca = a.px[ia]; val cb = b.px[ib]
                            sum[0] += (ca shr 16) and 255; sum[1] += (ca shr 8) and 255; sum[2] += ca and 255
                            sum[3] += (cb shr 16) and 255; sum[4] += (cb shr 8) and 255; sum[5] += cb and 255
                            n++
                        }
                    }
                    x += 4
                }
            }
            y += 4
        }
        if (n < 200) return null
        return FloatArray(6) { (sum[it] / n).toFloat() }
    }

    /** Средняя сила контуров кадра — масштаб для проверки качества совпадения. */
    private fun meanEdge(e: FloatArray, v: BooleanArray, w: Int, h: Int): Float {
        var s = 0.0; var n = 0
        for (i in e.indices) if (e[i] >= 0f && v[i]) { s += e[i]; n++ }
        return if (n > 0) (s / n).toFloat() else 0f
    }

    /** Низкие частоты кадра: коробочное размытие с учётом допустимых пикселей (края цилиндра не темнеют). */
    private fun lowPass(img: Warped, r: Int): IntArray {
        val w = img.w; val h = img.h
        val ch = Array(3) { FloatArray(w * h) }; val cnt = FloatArray(w * h)
        for (i in 0 until w * h) if (img.valid[i]) {
            val c = img.px[i]
            ch[0][i] = ((c shr 16) and 255).toFloat(); ch[1][i] = ((c shr 8) and 255).toFloat(); ch[2][i] = (c and 255).toFloat(); cnt[i] = 1f
        }
        val arrs = arrayOf(ch[0], ch[1], ch[2], cnt)
        val tmp = FloatArray(max(w, h))
        for (a in arrs) {
            // по строкам
            for (y in 0 until h) {
                val o = y * w
                var acc = 0f
                for (x in -r..r) acc += a[o + x.coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    tmp[x] = acc
                    acc += a[o + (x + r + 1).coerceAtMost(w - 1)] - a[o + (x - r).coerceAtLeast(0)]
                }
                System.arraycopy(tmp, 0, a, o, w)
            }
            // по столбцам
            for (x in 0 until w) {
                var acc = 0f
                for (y in -r..r) acc += a[y.coerceIn(0, h - 1) * w + x]
                for (y in 0 until h) {
                    tmp[y] = acc
                    acc += a[(y + r + 1).coerceAtMost(h - 1) * w + x] - a[(y - r).coerceAtLeast(0) * w + x]
                }
                for (y in 0 until h) a[y * w + x] = tmp[y]
            }
        }
        val out = IntArray(w * h)
        for (i in 0 until w * h) {
            val n = cnt[i]
            if (n > 0.5f) out[i] = (0xFF shl 24) or ((ch[0][i] / n).toInt().coerceIn(0, 255) shl 16) or ((ch[1][i] / n).toInt().coerceIn(0, 255) shl 8) or (ch[2][i] / n).toInt().coerceIn(0, 255)
        }
        return out
    }
}
