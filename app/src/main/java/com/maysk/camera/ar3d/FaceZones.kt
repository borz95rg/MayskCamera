package com.maysk.camera.ar3d

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Зоны лица для макияжа и ретуши: растр по 468 точкам сетки ML Kit (топология MediaPipe).
 * Текстура RGBA W×H в координатах кадра анализа (x вправо, y вниз, 0..1), как маска человека:
 *  R — кожа (овал лица без глаз, бровей и губ), G — губы (кольцо между внешним и внутренним контуром),
 *  B — зона теней над глазом, A — «белое» внутри глаз и рта (белки и зубы; различаются в шейдере по якорям).
 * Чистый Kotlin без Android API — проверяется юнит-тестами на JVM.
 */
object FaceZones {
    const val W = 192
    const val H = 256

    val OVAL = intArrayOf(10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377, 152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109)
    val LIP_OUT = intArrayOf(61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291, 375, 321, 405, 314, 17, 84, 181, 91, 146)
    val LIP_IN = intArrayOf(78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308, 324, 318, 402, 317, 14, 87, 178, 88, 95)
    val EYE_A = intArrayOf(33, 246, 161, 160, 159, 158, 157, 173, 133, 155, 154, 153, 145, 144, 163, 7)
    val EYE_B = intArrayOf(263, 466, 388, 387, 386, 385, 384, 398, 362, 382, 381, 380, 374, 373, 390, 249)
    val LID_A = intArrayOf(33, 246, 161, 160, 159, 158, 157, 173, 133)
    val LID_B = intArrayOf(263, 466, 388, 387, 386, 385, 384, 398, 362)
    val BROW_A = intArrayOf(46, 53, 52, 65, 55, 107, 66, 105, 63, 70)
    val BROW_B = intArrayOf(276, 283, 282, 295, 285, 336, 296, 334, 293, 300)

    /** Опорные точки главного лица в координатах кадра анализа (для блуша и различения глаз/зубов в шейдере). */
    class Anchors(
        val mouthX: Float, val mouthY: Float,
        val eyeAX: Float, val eyeAY: Float,
        val eyeBX: Float, val eyeBY: Float,
        val cheekAX: Float, val cheekAY: Float,
        val cheekBX: Float, val cheekBY: Float,
        /** Радиус румян по высоте кадра. */
        val radius: Float,
    )

    class Result(val rgba: ByteArray, val anchors: Anchors?)

    /** [meshes] — вершины каждого лица (минимум 468 точек x, y в долях кадра); [primary] — индекс главного лица. */
    fun build(meshes: List<List<FloatArray>>, primary: Int): Result {
        val buf = ByteArray(W * H * 4)
        var anchors: Anchors? = null
        for ((mi, m) in meshes.withIndex()) {
            if (m.size < 468) continue
            fill(buf, 0, poly(m, OVAL), 255)
            for (loop in arrayOf(EYE_A, EYE_B)) fill(buf, 0, poly(m, loop, 1.4f), 0)
            for (loop in arrayOf(BROW_A, BROW_B)) fill(buf, 0, poly(m, loop, 1.3f), 0)
            fill(buf, 0, poly(m, LIP_OUT, 1.12f), 0)
            fill(buf, 1, poly(m, LIP_OUT), 255)
            fill(buf, 1, poly(m, LIP_IN), 0)
            // направление «вверх по лицу» в пикселях растра
            var ux = (m[10][0] - m[152][0]) * W
            var uy = (m[10][1] - m[152][1]) * H
            val ul = max(hypot(ux, uy), 1e-3f)
            ux /= ul; uy /= ul
            for (lid in arrayOf(LID_A, LID_B)) fill(buf, 2, eyelid(m, lid, ux, uy), 255)
            if (mi == primary) {
                for (loop in arrayOf(EYE_A, EYE_B)) fill(buf, 3, poly(m, loop, 0.9f), 255)
                fill(buf, 3, poly(m, LIP_IN, 0.92f), 255)
                val fh = hypot((m[10][0] - m[152][0]) * 0.75f, m[10][1] - m[152][1])
                anchors = Anchors(
                    mid(m, 13, 14, 0), mid(m, 13, 14, 1),
                    mid(m, 33, 133, 0), mid(m, 33, 133, 1),
                    mid(m, 263, 362, 0), mid(m, 263, 362, 1),
                    (mid(m, 50, 205, 0)), (mid(m, 50, 205, 1)),
                    (mid(m, 280, 425, 0)), (mid(m, 280, 425, 1)),
                    fh * 0.15f,
                )
            }
        }
        blur(buf, 0, 1, 2)   // кожа: узкие вырезы (брови, веки) не должны затекать
        blur(buf, 1, 1, 1)   // губы: мягкий, но чёткий край
        blur(buf, 2, 1, 2)   // тени: мягче
        blur(buf, 3, 1, 1)
        return Result(buf, anchors)
    }

    private fun mid(m: List<FloatArray>, a: Int, b: Int, axis: Int) = (m[a][axis] + m[b][axis]) * 0.5f

    private fun poly(m: List<FloatArray>, idx: IntArray, k: Float = 1f): FloatArray {
        val n = idx.size
        val p = FloatArray(n * 2)
        var cx = 0f; var cy = 0f
        for (i in 0 until n) {
            val v = m[idx[i]]
            p[i * 2] = v[0] * W; p[i * 2 + 1] = v[1] * H
            cx += p[i * 2]; cy += p[i * 2 + 1]
        }
        if (k != 1f) {
            cx /= n; cy /= n
            for (i in 0 until n) {
                p[i * 2] = cx + (p[i * 2] - cx) * k
                p[i * 2 + 1] = cy + (p[i * 2 + 1] - cy) * k
            }
        }
        return p
    }

    /** Полумесяц над верхним веком: контур века и он же, поднятый вверх по лицу (выше всего в середине). */
    private fun eyelid(m: List<FloatArray>, lid: IntArray, ux: Float, uy: Float): FloatArray {
        val p = poly(m, lid)
        val n = lid.size
        val ew = hypot(p[0] - p[(n - 1) * 2], p[1] - p[(n - 1) * 2 + 1])
        val out = FloatArray(n * 4)
        for (i in 0 until n) {
            out[i * 2] = p[i * 2]; out[i * 2 + 1] = p[i * 2 + 1]
            val t = i.toFloat() / (n - 1)
            val lift = ew * 0.42f * sin(Math.PI.toFloat() * t)
            val j = 2 * n - 1 - i   // поднятые точки идут в обратном порядке
            out[j * 2] = p[i * 2] + ux * lift
            out[j * 2 + 1] = p[i * 2 + 1] + uy * lift
        }
        return out
    }

    /** Заливка многоугольника (чётно-нечётное правило) значением [value] в канал [ch]. */
    private fun fill(buf: ByteArray, ch: Int, p: FloatArray, value: Int) {
        val n = p.size / 2
        if (n < 3) return
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until n) { minY = min(minY, p[i * 2 + 1]); maxY = max(maxY, p[i * 2 + 1]) }
        if (minY.isNaN() || maxY.isNaN()) return
        val y0 = max(0, floor(minY - 0.5f).toInt())
        val y1 = min(H - 1, ceil(maxY - 0.5f).toInt())
        val xs = FloatArray(n)
        val v = value.toByte()
        for (row in y0..y1) {
            val yc = row + 0.5f
            var c = 0
            for (i in 0 until n) {
                val j = if (i + 1 == n) 0 else i + 1
                val ya = p[i * 2 + 1]; val yb = p[j * 2 + 1]
                if ((ya <= yc && yc < yb) || (yb <= yc && yc < ya)) {
                    xs[c++] = p[i * 2] + (yc - ya) / (yb - ya) * (p[j * 2] - p[i * 2])
                }
            }
            java.util.Arrays.sort(xs, 0, c)
            var k = 0
            while (k + 1 < c) {
                val a = max(0, ceil(xs[k] - 0.5f).toInt())
                val b = min(W - 1, ceil(xs[k + 1] - 0.5f).toInt() - 1)
                for (x in a..b) buf[(row * W + x) * 4 + ch] = v
                k += 2
            }
        }
    }

    /** Блочное размытие одного канала (радиус [r], [passes] проходов). */
    private fun blur(buf: ByteArray, ch: Int, r: Int, passes: Int) {
        val tmp = IntArray(W * H)
        repeat(passes) {
            for (y in 0 until H) for (x in 0 until W) {
                var s = 0; var c = 0
                for (dx in -r..r) {
                    val xx = x + dx
                    if (xx in 0 until W) { s += buf[(y * W + xx) * 4 + ch].toInt() and 0xFF; c++ }
                }
                tmp[y * W + x] = s / c
            }
            for (y in 0 until H) for (x in 0 until W) {
                var s = 0; var c = 0
                for (dy in -r..r) {
                    val yy = y + dy
                    if (yy in 0 until H) { s += tmp[yy * W + x]; c++ }
                }
                buf[(y * W + x) * 4 + ch] = (s / c).toByte()
            }
        }
    }
}
