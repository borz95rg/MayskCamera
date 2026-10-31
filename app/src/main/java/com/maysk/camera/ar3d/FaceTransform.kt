package com.maysk.camera.ar3d

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Перевод координат и поза головы.
 *
 * Цепочка координат:
 *  1. ML Kit Face Mesh: x,y — доли повёрнутого «вертикального» кадра анализа (0..1, y вниз),
 *     z — глубина в долях ширины кадра (меньше — ближе к камере).
 *  2. Экран превью («view»): кадр анализа 3:4 обрезается под экран (как FILL_CENTER),
 *     фронтальная камера зеркалится. Единица — высота экрана: x ∈ [0, aspect], y ∈ [0, 1] вниз,
 *     z — от камеры (в тех же единицах). Это изотропное пространство: 1 пиксель по x = 1 по y.
 *  3. Текстурные координаты превью для OpenGL: u = x/aspect, v = 1 − y (y вверх).
 *  4. Выходы CameraX (превью, видео): матрица prevToOut в рендерере — так маска попадает
 *     одинаково и на экран, и в MP4.
 */
data class ViewMapping(val viewAspect: Float, val mirror: Boolean, val frameAspect: Float = 0.75f) {
    private val fx = if (viewAspect < frameAspect) viewAspect / frameAspect else 1f
    private val fy = if (viewAspect > frameAspect) frameAspect / viewAspect else 1f

    /** Точка ML Kit (доли кадра) → экранные координаты view. */
    fun toView(nx: Float, ny: Float, nz: Float, out: FloatArray, o: Int = 0) {
        var x = (nx - (1 - fx) / 2) / fx * viewAspect
        if (mirror) x = viewAspect - x
        out[o] = x
        out[o + 1] = (ny - (1 - fy) / 2) / fy
        out[o + 2] = nz * viewAspect / fx
    }

    /** Прямоугольник лица (доли кадра) → центр и ширина в экранных единицах. */
    fun boxCenter(cx: Float, cy: Float): FloatArray = FloatArray(3).also { toView(cx, cy, 0f, it) }
    fun scaleX(w: Float): Float = w / fx * viewAspect
}

/**
 * Поза головы в экранных координатах: начало (между внешними уголками глаз),
 * оси лица и масштаб (1 единица модели = расстояние между внешними уголками глаз).
 * Точка модели m переводится так: S = origin + scale·(m.x·right + m.y·up + m.z·forward),
 * где forward направлен к камере (+Z glTF).
 */
class FacePose(
    val origin: FloatArray,   // xyz
    val right: FloatArray,    // единичный вектор
    val up: FloatArray,       // единичный, к макушке (на экране y уменьшается)
    val forward: FloatArray,  // единичный, из лица к камере
    val scale: Float,
    val mouthOpen: Float,     // 0..1
    /**
     * Отношение «лоб→подбородок / расстояние между внешними уголками глаз».
     * Взрослый ≈ 2.0–2.4, ребёнок ≈ 1.6–2.0.
     */
    val faceRatio: Float = 2.1f,
    /** 0 = глаза закрыты, 1 = открыты (по mesh век). */
    val eyeOpen: Float = 1f,
    /** Поворот головы влево/вправо ≈ -1..+1 (для occlusion ушей). */
    val yaw: Float = 0f,
) {
    fun apply(mx: Float, my: Float, mz: Float, out: FloatArray, o: Int = 0) {
        for (k in 0 until 3) out[o + k] = origin[k] + scale * (mx * right[k] + my * up[k] + mz * forward[k])
    }

    /** Матрица 4x4 (по столбцам): модель → экран view. */
    fun matrix(): FloatArray = floatArrayOf(
        right[0] * scale, right[1] * scale, right[2] * scale, 0f,
        up[0] * scale, up[1] * scale, up[2] * scale, 0f,
        forward[0] * scale, forward[1] * scale, forward[2] * scale, 0f,
        origin[0], origin[1], origin[2], 1f,
    )
}

object FaceTransform {
    // Канонические индексы точек Face Mesh (те же, что в MediaPipe)
    const val EYE_OUTER_A = 33      // внешний уголок одного глаза
    const val EYE_OUTER_B = 263     // внешний уголок другого глаза
    const val FOREHEAD = 10
    const val CHIN = 152
    const val LIP_UPPER = 13
    const val LIP_LOWER = 14
    // веки (MediaPipe Face Mesh)
    const val LEFT_EYE_TOP = 159
    const val LEFT_EYE_BOTTOM = 145
    const val RIGHT_EYE_TOP = 386
    const val RIGHT_EYE_BOTTOM = 374
    const val NOSE_TIP = 1

    /** Поза по 468 точкам; null, если точек нет или они вырождены. */
    fun pose(vertices: List<FloatArray>, map: ViewMapping): FacePose? {
        if (vertices.size < 468) return null
        val p = FloatArray(3 * 6)
        val ids = intArrayOf(EYE_OUTER_A, EYE_OUTER_B, FOREHEAD, CHIN, LIP_UPPER, LIP_LOWER)
        for ((k, id) in ids.withIndex()) {
            val v = vertices[id]
            if (v.size < 3) return null
            map.toView(v[0], v[1], v[2], p, k * 3)
        }
        val a = 0; val b = 3; val fh = 6; val ch = 9; val lu = 12; val ll = 15
        // ось «вправо на экране»: в зеркальном кадре уголки глаз меняются местами
        val s = if (map.mirror) -1f else 1f
        val right = floatArrayOf(s * (p[b] - p[a]), s * (p[b + 1] - p[a + 1]), s * (p[b + 2] - p[a + 2]))
        val scale = len(right)
        if (scale < 1e-4f) return null
        norm(right)
        // «вниз» по лицу: от лба к подбородку, ортогонализуем к right
        val down = floatArrayOf(p[ch] - p[fh], p[ch + 1] - p[fh + 1], p[ch + 2] - p[fh + 2])
        val d = dot(down, right)
        for (k in 0 until 3) down[k] -= d * right[k]
        if (len(down) < 1e-4f) return null
        norm(down)
        val into = cross(right, down)              // в экран (от камеры) для фронтального лица
        val up = floatArrayOf(-down[0], -down[1], -down[2])
        val forward = floatArrayOf(-into[0], -into[1], -into[2])
        val origin = floatArrayOf((p[a] + p[b]) / 2, (p[a + 1] + p[b + 1]) / 2, (p[a + 2] + p[b + 2]) / 2)
        val lip = sqrt(sq(p[lu] - p[ll]) + sq(p[lu + 1] - p[ll + 1]) + sq(p[lu + 2] - p[ll + 2])) / scale
        val mouth = ((lip - 0.06f) / 0.18f).coerceIn(0f, 1f)
        val faceH = sqrt(sq(p[ch] - p[fh]) + sq(p[ch + 1] - p[fh + 1]) + sq(p[ch + 2] - p[fh + 2])) / scale
        val ratio = faceH.coerceIn(1.4f, 3.0f)
        // открытость глаз по векам
        fun eyeGap(topId: Int, botId: Int): Float {
            if (vertices.size <= maxOf(topId, botId)) return 1f
            val t = vertices[topId]; val b = vertices[botId]
            if (t.size < 3 || b.size < 3) return 1f
            val g = sqrt(sq(t[0]-b[0]) + sq(t[1]-b[1]) + sq(t[2]-b[2])) / scale
            return ((g - 0.015f) / 0.055f).coerceIn(0f, 1f)
        }
        val eyeOpen = (eyeGap(LEFT_EYE_TOP, LEFT_EYE_BOTTOM) + eyeGap(RIGHT_EYE_TOP, RIGHT_EYE_BOTTOM)) * 0.5f
        // yaw: насколько forward смотрит вбок (проекция на right)
        val yaw = dot(forward, right).coerceIn(-1f, 1f)
        return FacePose(origin, right, up, forward, scale, mouth, ratio, eyeOpen, yaw)
    }

    /** Все 468 точек в экранных координатах (для «невидимой головы», закрывающей части маски за лицом). */
    fun meshToView(vertices: List<FloatArray>, map: ViewMapping): FloatArray {
        val out = FloatArray(vertices.size * 3)
        for (i in vertices.indices) { val v = vertices[i]; if (v.size >= 3) map.toView(v[0], v[1], v[2], out, i * 3) }
        return out
    }

    internal fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    internal fun len(a: FloatArray) = sqrt(dot(a, a))
    internal fun norm(a: FloatArray) { val l = len(a).coerceAtLeast(1e-9f); a[0] /= l; a[1] /= l; a[2] /= l }
    internal fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun sq(v: Float) = v * v
}

/**
 * Сглаживание позы и удержание при кратких потерях лица.
 * Фильтр One-Euro: при медленном движении сильное сглаживание (маска не дрожит),
 * при быстром — слабое (маска не отстаёт). Между редкими обновлениями сетки (~8 к/с)
 * позицию подтягивает быстрый детектор лица (рамка) — маска не «прыгает» шагами.
 */
class FaceTracker(
    private val minCutoff: Float = 1.2f,
    private val beta: Float = 0.6f,
    private val holdMs: Long = 450,
) {
    private var pose: FacePose? = null
    private var lastSeen = 0L
    private var lastT = 0L
    private val prev = FloatArray(13)
    private val dPrev = FloatArray(13)
    private var hasPrev = false
    // привязка к рамке лица на момент последней сетки
    private var meshBoxCenter: FloatArray? = null
    private var meshBoxWidth = 0f
    private var meshOrigin: FloatArray? = null
    private var meshScale = 0f

    fun reset() { pose = null; hasPrev = false; meshBoxCenter = null }

    /**
     * [mesh] — новая поза по сетке (или null, если сетка в этом кадре не обновлялась),
     * [boxCenter]/[boxWidth] — рамка лица быстрого детектора в экранных единицах (или null — лица нет).
     */
    fun update(mesh: FacePose?, boxCenter: FloatArray?, boxWidth: Float, nowMs: Long): FacePose? {
        if (boxCenter == null && mesh == null) {
            return if (nowMs - lastSeen <= holdMs) pose else { reset(); null }
        }
        lastSeen = nowMs
        var target: FacePose? = mesh
        if (mesh != null) {
            meshBoxCenter = boxCenter?.copyOf(); meshBoxWidth = boxWidth
            meshOrigin = mesh.origin.copyOf(); meshScale = mesh.scale
        } else {
            // между сетками: двигаем последнюю позу вслед за рамкой лица
            val last = pose ?: return null
            val mc = meshBoxCenter; val mo = meshOrigin
            if (mc != null && mo != null && boxCenter != null && meshBoxWidth > 1e-4f) {
                val k = (boxWidth / meshBoxWidth).coerceIn(0.6f, 1.6f)
                val o = floatArrayOf(mo[0] + (boxCenter[0] - mc[0]), mo[1] + (boxCenter[1] - mc[1]), mo[2])
                target = FacePose(o, last.right, last.up, last.forward, meshScale * k, last.mouthOpen, last.faceRatio, last.eyeOpen, last.yaw)
            } else target = last
        }
        val t = target ?: return pose
        val raw = floatArrayOf(
            t.origin[0], t.origin[1], t.origin[2],
            t.right[0], t.right[1], t.right[2],
            t.up[0], t.up[1], t.up[2],
            t.scale, t.mouthOpen, t.faceRatio, t.eyeOpen,
        )
        val dt = if (lastT == 0L) 1f / 30f else ((nowMs - lastT) / 1000f).coerceIn(1f / 120f, 0.5f)
        lastT = nowMs
        val out = FloatArray(13)
        for (i in 0 until 13) {
            if (!hasPrev) { out[i] = raw[i]; dPrev[i] = 0f; continue }
            val dx = (raw[i] - prev[i]) / dt
            val ad = alpha(dt, 1.0f)
            val edx = dPrev[i] + ad * (dx - dPrev[i])
            dPrev[i] = edx
            // для положения скорость считаем в долях масштаба лица — одинаково для близкого и далёкого лица
            val speed = if (i < 3) abs(edx) / prev[9].coerceAtLeast(1e-3f) else abs(edx)
            val cutoff = minCutoff + beta * speed
            out[i] = prev[i] + alpha(dt, cutoff) * (raw[i] - prev[i])
        }
        for (i in 0 until 13) prev[i] = out[i]
        hasPrev = true
        val right = floatArrayOf(out[3], out[4], out[5]); FaceTransform.norm(right)
        val up = floatArrayOf(out[6], out[7], out[8])
        val d = FaceTransform.dot(up, right)
        for (k in 0 until 3) up[k] -= d * right[k]
        FaceTransform.norm(up)
        // forward = right × up (правая тройка: вправо, вверх, к камере)
        val fwd = FaceTransform.cross(right, up)
        pose = FacePose(floatArrayOf(out[0], out[1], out[2]), right, up, fwd, out[9], out[10], out[11], out[12], t.yaw)
        return pose
    }

    private fun alpha(dt: Float, cutoff: Float): Float {
        val tau = 1f / (2f * Math.PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }

    @Suppress("unused")
    private fun expSmooth(a: Float, b: Float, k: Float) = a + (b - a) * (1f - exp(-k))
}
