package com.maysk.camera.ar3d

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Привязка сетки лица (468 точек ML Kit Face Mesh) к ориентирам быстрого детектора лиц.
 *
 * На устройстве выяснилось: точки сетки приходят со сдвигом по горизонтали относительно
 * кадра, в котором детектор лиц отдаёт рамку и глаза (рамка лица на экране стоит верно,
 * а маски «уезжали» влево на ~40 % ширины экрана при правильных высоте и масштабе).
 * Поэтому сетку подгоняем подобием (сдвиг + единый масштаб, без поворота — кадр тот же)
 * по парам «точка сетки ↔ ориентир детектора»: глаза, основание носа, уголки и низ рта.
 * Форма и поворот головы остаются от сетки, а положение на экране — от детектора.
 */
object MeshCalibration {
    /** Центры глаз в сетке: внешний, внутренний уголки, верхнее и нижнее веко. */
    val EYE_A = intArrayOf(33, 133, 159, 145)
    val EYE_B = intArrayOf(362, 263, 386, 374)
    const val NOSE_BASE = 2
    const val MOUTH_BOTTOM = 17
    const val MOUTH_A = 61
    const val MOUTH_B = 291

    /** Ориентиры детектора (доли кадра, null — нет). Глаза/уголки рта сопоставляются по x — не важно, «левый» это или «правый». */
    class Landmarks(
        val eyes: List<FloatArray>,          // 0..2 точки [x, y]
        val nose: FloatArray? = null,
        val mouthBottom: FloatArray? = null,
        val mouthCorners: List<FloatArray> = emptyList(),
        val boxCenter: FloatArray? = null,
        val boxWidth: Float = 0f,
    )

    /** Результат: x' = s·x + tx, y' = s·y + ty, z' = s·z. */
    data class Fit(val s: Float, val tx: Float, val ty: Float, val pairs: Int, val residual: Float)

    fun fit(vertices: List<FloatArray>, lm: Landmarks): Fit? {
        if (vertices.size < 468) return null
        val src = ArrayList<FloatArray>(); val dst = ArrayList<FloatArray>()
        fun avg(ids: IntArray): FloatArray {
            var x = 0f; var y = 0f
            for (i in ids) { x += vertices[i][0]; y += vertices[i][1] }
            return floatArrayOf(x / ids.size, y / ids.size)
        }
        fun pt(i: Int) = floatArrayOf(vertices[i][0], vertices[i][1])
        fun pairByX(a: FloatArray, b: FloatArray, d: List<FloatArray>) {
            if (d.size < 2) return
            val (m0, m1) = if (a[0] <= b[0]) a to b else b to a
            val (d0, d1) = if (d[0][0] <= d[1][0]) d[0] to d[1] else d[1] to d[0]
            src += m0; dst += d0; src += m1; dst += d1
        }
        pairByX(avg(EYE_A), avg(EYE_B), lm.eyes)
        lm.nose?.let { src += pt(NOSE_BASE); dst += it }
        lm.mouthBottom?.let { src += pt(MOUTH_BOTTOM); dst += it }
        pairByX(pt(MOUTH_A), pt(MOUTH_B), lm.mouthCorners)

        if (src.size < 2) {
            // ориентиров нет — только сдвиг по центру рамки
            val bc = lm.boxCenter ?: return null
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (v in vertices) { minX = minOf(minX, v[0]); maxX = maxOf(maxX, v[0]); minY = minOf(minY, v[1]); maxY = maxOf(maxY, v[1]) }
            val s = if (lm.boxWidth > 1e-4f && maxX - minX > 1e-4f) (lm.boxWidth / (maxX - minX)).coerceIn(0.6f, 1.6f) else 1f
            val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2
            return Fit(s, bc[0] - s * cx, bc[1] - s * cy, 0, 0f)
        }
        val n = src.size
        var mx = 0f; var my = 0f; var dx = 0f; var dy = 0f
        for (i in 0 until n) { mx += src[i][0]; my += src[i][1]; dx += dst[i][0]; dy += dst[i][1] }
        mx /= n; my /= n; dx /= n; dy /= n
        var num = 0f; var den = 0f
        for (i in 0 until n) {
            val ax = src[i][0] - mx; val ay = src[i][1] - my
            num += ax * (dst[i][0] - dx) + ay * (dst[i][1] - dy)
            den += ax * ax + ay * ay
        }
        val s = (if (den > 1e-8f) num / den else 1f).coerceIn(0.6f, 1.6f)
        val tx = dx - s * mx; val ty = dy - s * my
        var res = 0f
        for (i in 0 until n) {
            val ex = s * src[i][0] + tx - dst[i][0]; val ey = s * src[i][1] + ty - dst[i][1]
            res += sqrt(ex * ex + ey * ey)
        }
        return Fit(s, tx, ty, n, res / n)
    }

    /** Применить подгонку ко всем точкам (новый список). */
    fun apply(vertices: List<FloatArray>, f: Fit): List<FloatArray> =
        vertices.map { v -> floatArrayOf(f.s * v[0] + f.tx, f.s * v[1] + f.ty, f.s * v.getOrElse(2) { 0f }) }

    /** Подогнать, если это разумно: при сильном расхождении (не то лицо) — null. */
    fun calibrate(vertices: List<FloatArray>, lm: Landmarks): List<FloatArray>? {
        val f = fit(vertices, lm) ?: return null
        // расстояние между глазами по детектору — мерило «разумности»
        val eyeDist = if (lm.eyes.size >= 2) abs(lm.eyes[0][0] - lm.eyes[1][0]) + abs(lm.eyes[0][1] - lm.eyes[1][1]) else lm.boxWidth * 0.4f
        if (f.pairs >= 3 && eyeDist > 1e-4f && f.residual > eyeDist * 0.6f) return null
        return apply(vertices, f)
    }
}
