package com.maysk.camera.camera

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.maysk.camera.ai.FaceInfo
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** AR-маски (как в Snapchat): рисуются по точкам лица и поворачиваются вместе с головой. */
enum class ArMask(val title: String, val emoji: String) {
    NONE("Без маски", "🚫"),
    SUNGLASSES("Очки", "🕶"),
    NEON_GLASSES("Неон-очки", "✨"),
    CAT("Котик", "🐱"),
    DOG("Щенок", "🐶"),
    BUNNY("Зайка", "🐰"),
    CROWN("Корона", "👑"),
    FLOWERS("Венок", "🌸"),
    HEARTS("Сердечки", "💕"),
    HALO("Нимб", "😇"),
    DEVIL("Рожки", "😈"),
    MUSTACHE("Усы", "🥸"),
    BLUSH("Румянец", "☺"),
    PIRATE("Пират", "🏴‍☠️"),
    HERO("Супергерой", "🦸"),
    ROBOT("Робот", "🤖"),
    ASTRONAUT("Космонавт", "👨‍🚀"),
    MAKEUP("Макияж", "💄"),
    FOX("Лисёнок", "🦊"),
    CYBER("Киберпанк", "⚡"),
    HEART_EYES("Сердца вместо глаз", "💗"),
    RAINBOW("Радужный ореол", "🌈"),
    PANDA("Панда", "🐼"),
    TIGER("Тигр", "🐯"),
    BEAR("Медвежонок", "🐻"),
    GLAM_MAKEUP("Вечерний макияж", "💋"),
    FRECKLES("Веснушки", "✨"),
    WOLF("Волк", "🐺"),
    LION("Лев", "🦁"),
    DEER("Олень", "🦌"),
    RACCOON("Енот", "🦝"),
    UNICORN("Единорог", "🦄"),
    DRAGON("Дракон", "🐉"),
    OWL("Сова", "🦉"),
    BUTTERFLY("Бабочка", "🦋"),
}

object ArMasks {

    /** Лицо в пикселях холста. */
    private class FacePx(
        val cx: Float, val cy: Float,     // середина между глаз
        val d: Float,                     // расстояние между глазами
        val roll: Float,                  // наклон, градусы
        val w: Float, val h: Float,       // размер рамки лица
    )

    /**
     * Рисует маску для лица. Координаты лица нормализованы (0..1) в кадре размером [w]×[h]
     * с учётом смещения/масштаба кадра на экране: px = offX + x * w.
     */
    fun draw(c: Canvas, mask: ArMask, face: FaceInfo, w: Float, h: Float, offX: Float = 0f, offY: Float = 0f, mirror: Boolean = false, time: Float = 0f) {
        if (mask == ArMask.NONE) return
        val fp = toPx(face, w, h, offX, offY, mirror) ?: return
        c.save()
        c.translate(fp.cx, fp.cy)
        c.rotate(fp.roll)
        drawLocal(c, mask, fp, time)
        c.restore()
    }

    private fun toPx(f: FaceInfo, w: Float, h: Float, offX: Float, offY: Float, mirror: Boolean): FacePx? {
        fun px(p: PointF) = PointF(offX + (if (mirror) 1f - p.x else p.x) * w, offY + p.y * h)
        val box = f.box
        val bl = offX + (if (mirror) 1f - box.right else box.left) * w
        val bw = box.width() * w
        val bh = box.height() * h
        return if (f.eyes.size >= 2) {
            val e = f.eyes.map { px(it) }.sortedBy { it.x }
            val l = e[0]; val r = e[1]
            val d = hypot(r.x - l.x, r.y - l.y).coerceAtLeast(bw * 0.25f)
            val roll = Math.toDegrees(atan2((r.y - l.y).toDouble(), (r.x - l.x).toDouble())).toFloat()
            FacePx((l.x + r.x) / 2, (l.y + r.y) / 2, d, roll, bw, bh)
        } else {
            // без точек глаз — по рамке лица
            FacePx(bl + bw / 2, offY + box.top * h + bh * 0.4f, bw * 0.42f, 0f, bw, bh)
        }
    }

    private fun p(color: Int, style: Paint.Style = Paint.Style.FILL, stroke: Float = 0f) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; this.style = style; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    private fun drawLocal(c: Canvas, m: ArMask, f: FacePx, t: Float) {
        val d = f.d
        val top = -d * 1.25f            // макушка
        when (m) {
            ArMask.SUNGLASSES -> {
                val lens = p(Color.argb(235, 15, 15, 20))
                val rimP = p(Color.BLACK, Paint.Style.STROKE, d * 0.06f)
                for (sx in listOf(-0.5f, 0.5f)) {
                    val r = RectF(sx * d - d * 0.38f, -d * 0.24f, sx * d + d * 0.38f, d * 0.26f)
                    c.drawRoundRect(r, d * 0.14f, d * 0.14f, lens)
                    c.drawRoundRect(r, d * 0.14f, d * 0.14f, rimP)
                    val shine = p(Color.argb(90, 255, 255, 255))
                    c.drawOval(RectF(r.left + d * 0.08f, r.top + d * 0.05f, r.left + d * 0.28f, r.top + d * 0.14f), shine)
                }
                c.drawLine(-d * 0.12f, -d * 0.08f, d * 0.12f, -d * 0.08f, rimP)
                c.drawLine(-d * 0.88f, -d * 0.12f, -d * 1.05f, -d * 0.18f, rimP)
                c.drawLine(d * 0.88f, -d * 0.12f, d * 1.05f, -d * 0.18f, rimP)
            }
            ArMask.NEON_GLASSES -> {
                val hue = (t * 60f) % 360f
                val col = Color.HSVToColor(floatArrayOf(hue, 0.8f, 1f))
                val glow = p(col, Paint.Style.STROKE, d * 0.09f).apply { maskFilter = BlurMaskFilter(d * 0.12f, BlurMaskFilter.Blur.NORMAL) }
                val line = p(Color.WHITE, Paint.Style.STROKE, d * 0.035f)
                for (sx in listOf(-0.5f, 0.5f)) {
                    val r = RectF(sx * d - d * 0.36f, -d * 0.26f, sx * d + d * 0.36f, d * 0.24f)
                    c.drawRoundRect(r, d * 0.2f, d * 0.2f, glow); c.drawRoundRect(r, d * 0.2f, d * 0.2f, line)
                }
                c.drawLine(-d * 0.14f, -d * 0.05f, d * 0.14f, -d * 0.05f, line)
            }
            ArMask.CAT, ArMask.DOG, ArMask.BUNNY, ArMask.PANDA, ArMask.TIGER, ArMask.BEAR, ArMask.WOLF, ArMask.LION, ArMask.DEER, ArMask.RACCOON, ArMask.UNICORN, ArMask.DRAGON, ArMask.OWL, ArMask.BUTTERFLY -> animal(c, m, d, top)
            ArMask.CROWN -> {
                val gold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(0f, top - d * 0.9f, 0f, top, Color.parseColor("#FFE082"), Color.parseColor("#C79100"), Shader.TileMode.CLAMP)
                }
                val cw = d * 1.15f
                val path = Path().apply {
                    moveTo(-cw, top); lineTo(-cw, top - d * 0.5f); lineTo(-cw * 0.5f, top - d * 0.2f)
                    lineTo(0f, top - d * 0.8f); lineTo(cw * 0.5f, top - d * 0.2f); lineTo(cw, top - d * 0.5f); lineTo(cw, top); close()
                }
                c.drawPath(path, gold)
                c.drawPath(path, p(Color.parseColor("#8D6E00"), Paint.Style.STROKE, d * 0.03f))
                listOf(-cw * 0.6f to Color.parseColor("#E53935"), 0f to Color.parseColor("#1E88E5"), cw * 0.6f to Color.parseColor("#43A047")).forEach { (x, col) ->
                    c.drawCircle(x, top - d * 0.15f, d * 0.08f, p(col))
                }
            }
            ArMask.FLOWERS -> {
                val cols = listOf("#F48FB1", "#FFF176", "#CE93D8", "#FFAB91", "#F8BBD0").map { Color.parseColor(it) }
                for (i in 0 until 9) {
                    val a = Math.PI * (0.1 + 0.8 * i / 8.0)
                    val x = (-cos(a) * d * 1.15).toFloat(); val y = (top + d * 0.15f - sin(a).toFloat() * d * 0.35f)
                    flower(c, x, y, d * 0.17f, cols[i % cols.size])
                }
            }
            ArMask.HEARTS -> {
                for (i in 0 until 5) {
                    val a = t * 1.6f + i * (2 * Math.PI.toFloat() / 5)
                    val x = cos(a) * d * 1.1f; val y = top - d * 0.25f + sin(a) * d * 0.22f
                    heart(c, x, y, d * (0.16f + 0.04f * sin(a + 1f)), p(Color.parseColor("#FF2E63")))
                }
            }
            ArMask.HALO -> {
                val r = RectF(-d * 0.9f, top - d * 0.55f, d * 0.9f, top - d * 0.25f)
                c.drawOval(r, p(Color.parseColor("#FFD54F"), Paint.Style.STROKE, d * 0.14f).apply { maskFilter = BlurMaskFilter(d * 0.12f, BlurMaskFilter.Blur.NORMAL) })
                c.drawOval(r, p(Color.parseColor("#FFF59D"), Paint.Style.STROKE, d * 0.06f))
            }
            ArMask.DEVIL -> {
                val red = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(0f, top - d * 0.7f, 0f, top, Color.parseColor("#FF5252"), Color.parseColor("#B71C1C"), Shader.TileMode.CLAMP)
                }
                for (sx in listOf(-1f, 1f)) {
                    val path = Path().apply {
                        moveTo(sx * d * 0.45f, top + d * 0.05f)
                        quadTo(sx * d * 0.6f, top - d * 0.4f, sx * d * 0.95f, top - d * 0.75f)
                        quadTo(sx * d * 0.8f, top - d * 0.25f, sx * d * 0.85f, top + d * 0.05f)
                        close()
                    }
                    c.drawPath(path, red)
                }
            }
            ArMask.MUSTACHE -> {
                val y = d * 1.0f
                val path = Path().apply {
                    moveTo(0f, y - d * 0.05f)
                    cubicTo(d * 0.25f, y - d * 0.22f, d * 0.55f, y - d * 0.1f, d * 0.62f, y + d * 0.05f)
                    cubicTo(d * 0.45f, y - d * 0.02f, d * 0.25f, y + d * 0.1f, 0f, y + d * 0.04f)
                    cubicTo(-d * 0.25f, y + d * 0.1f, -d * 0.45f, y - d * 0.02f, -d * 0.62f, y + d * 0.05f)
                    cubicTo(-d * 0.55f, y - d * 0.1f, -d * 0.25f, y - d * 0.22f, 0f, y - d * 0.05f)
                    close()
                }
                c.drawPath(path, p(Color.parseColor("#3E2723")))
            }
            ArMask.BLUSH -> {
                for (sx in listOf(-1f, 1f)) {
                    val cx = sx * d * 0.7f; val cy = d * 0.6f
                    c.drawCircle(cx, cy, d * 0.24f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = RadialGradient(cx, cy, d * 0.24f, Color.argb(130, 255, 100, 140), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                    })
                }
                for (i in 0 until 4) {
                    val a = t * 2 + i
                    sparkle(c, sin(a) * d * 1.1f, top + d * 0.3f + cos(a * 1.3f) * d * 0.3f, d * 0.08f)
                }
            }
            ArMask.PIRATE -> {
                val black = p(Color.BLACK)
                c.drawLine(-d * 1.05f, -d * 0.55f, d * 1.05f, d * 0.35f, p(Color.BLACK, Paint.Style.STROKE, d * 0.05f))
                c.drawOval(RectF(d * 0.5f - d * 0.28f, -d * 0.24f, d * 0.5f + d * 0.28f, d * 0.26f), black)
                // треуголка
                val hat = Path().apply {
                    moveTo(-d * 1.3f, top + d * 0.15f); quadTo(0f, top - d * 1.2f, d * 1.3f, top + d * 0.15f)
                    quadTo(0f, top - d * 0.1f, -d * 1.3f, top + d * 0.15f); close()
                }
                c.drawPath(hat, p(Color.parseColor("#212121")))
                c.drawCircle(0f, top - d * 0.35f, d * 0.12f, p(Color.WHITE))
            }
            ArMask.HERO -> {
                val path = Path().apply {
                    moveTo(-d * 1.0f, -d * 0.15f)
                    quadTo(-d * 0.6f, -d * 0.5f, 0f, -d * 0.25f)
                    quadTo(d * 0.6f, -d * 0.5f, d * 1.0f, -d * 0.15f)
                    quadTo(d * 0.75f, d * 0.4f, d * 0.15f, d * 0.2f)
                    lineTo(0f, d * 0.1f); lineTo(-d * 0.15f, d * 0.2f)
                    quadTo(-d * 0.75f, d * 0.4f, -d * 1.0f, -d * 0.15f)
                    close()
                }
                c.drawPath(path, p(Color.parseColor("#D32F2F")))
                for (sx in listOf(-0.5f, 0.5f)) c.drawOval(RectF(sx * d - d * 0.2f, -d * 0.12f, sx * d + d * 0.2f, d * 0.1f), p(Color.WHITE))
            }
            ArMask.ROBOT -> {
                val visor = RectF(-d * 1.05f, -d * 0.25f, d * 1.05f, d * 0.22f)
                c.drawRoundRect(visor, d * 0.2f, d * 0.2f, p(Color.argb(220, 20, 30, 40)))
                val scan = ((t * 0.8f) % 1f) * 2 - 1
                c.drawRoundRect(visor, d * 0.2f, d * 0.2f, p(Color.parseColor("#00E5FF"), Paint.Style.STROKE, d * 0.05f).apply { maskFilter = BlurMaskFilter(d * 0.06f, BlurMaskFilter.Blur.NORMAL) })
                c.drawLine(scan * d * 0.9f, -d * 0.18f, scan * d * 0.9f, d * 0.15f, p(Color.parseColor("#FF2E63"), Paint.Style.STROKE, d * 0.08f))
                c.drawLine(0f, top, 0f, top - d * 0.5f, p(Color.parseColor("#90A4AE"), Paint.Style.STROKE, d * 0.05f))
                c.drawCircle(0f, top - d * 0.55f, d * 0.09f, p(if ((t * 2).toInt() % 2 == 0) Color.RED else Color.parseColor("#FFCDD2")))
            }
            ArMask.ASTRONAUT -> {
                val r = max(f.w, f.h) * 0.72f
                val cy = d * 0.35f
                c.drawCircle(0f, cy, r, p(Color.argb(40, 180, 220, 255)))
                c.drawCircle(0f, cy, r, p(Color.argb(230, 230, 236, 240), Paint.Style.STROKE, d * 0.22f))
                c.drawArc(RectF(-r * 0.75f, cy - r * 0.8f, r * 0.2f, cy), 200f, 60f, false, p(Color.argb(150, 255, 255, 255), Paint.Style.STROKE, d * 0.08f))
            }
            ArMask.MAKEUP, ArMask.GLAM_MAKEUP -> {
                val glam = m == ArMask.GLAM_MAKEUP
                val blushColor = if (glam) Color.argb(135, 235, 65, 125) else Color.argb(95, 255, 70, 125)
                val blush = p(blushColor)
                c.drawOval(RectF(-d * 0.92f, d * 0.35f, -d * 0.42f, d * 0.72f), blush)
                c.drawOval(RectF(d * 0.42f, d * 0.35f, d * 0.92f, d * 0.72f), blush)
                val lip = p(if (glam) Color.parseColor("#C2185B") else Color.argb(210, 220, 35, 95), Paint.Style.STROKE, d * if (glam) 0.13f else 0.09f)
                c.drawOval(RectF(-d * 0.27f, d * 1.05f, d * 0.27f, d * 1.34f), lip)
                if (glam) {
                    val liner = p(Color.BLACK, Paint.Style.STROKE, d * 0.045f)
                    for (sx in listOf(-1f, 1f)) {
                        c.drawLine(sx*d*0.18f, -d*0.04f, sx*d*0.48f, -d*0.12f, liner)
                        c.drawLine(sx*d*0.48f, -d*0.12f, sx*d*0.62f, -d*0.2f, liner)
                        sparkle(c, sx*d*0.72f, d*0.34f, d*0.07f)
                    }
                }
            }
            ArMask.FRECKLES -> {
                val freckle = p(Color.argb(190, 150, 75, 45))
                for (sx in listOf(-1f, 1f)) for (i in 0..4) {
                    val x = sx * (d * (0.38f + (i % 3) * 0.12f))
                    val y = d * (0.43f + (i / 3) * 0.13f)
                    c.drawCircle(x, y, d * 0.025f, freckle)
                }
            }
            ArMask.FOX -> {
                for (sx in listOf(-1f, 1f)) {
                    val ear = Path().apply { moveTo(sx*d*0.48f, top+d*0.12f); lineTo(sx*d*0.78f, top-d*0.85f); lineTo(sx*d*1.08f, top+d*0.25f); close() }
                    c.drawPath(ear, p(Color.parseColor("#E87524")))
                    val inner = Path().apply { moveTo(sx*d*0.65f, top+d*0.05f); lineTo(sx*d*0.78f, top-d*0.55f); lineTo(sx*d*0.94f, top+d*0.12f); close() }
                    c.drawPath(inner, p(Color.parseColor("#FFD4A3")))
                }
                c.drawOval(RectF(-d*0.18f, d*0.62f, d*0.18f, d*0.88f), p(Color.parseColor("#33231D")))
            }
            ArMask.CYBER -> {
                val neon = p(Color.parseColor("#00E5FF"), Paint.Style.STROKE, d*0.07f).apply { maskFilter = BlurMaskFilter(d*0.13f, BlurMaskFilter.Blur.NORMAL) }
                c.drawOval(RectF(-d*1.05f, -d*0.48f, d*1.05f, d*1.48f), neon)
                c.drawLine(-d*0.95f, d*0.45f, d*0.95f, d*0.45f, p(Color.parseColor("#FF2E93"), Paint.Style.STROKE, d*0.05f))
                c.drawCircle(0f, top-d*0.22f, d*0.12f, p(Color.parseColor("#B388FF")))
            }
            ArMask.HEART_EYES -> {
                for (sx in listOf(-0.5f, 0.5f)) heart(c, sx*d, -d*0.02f, d*0.23f, p(Color.parseColor("#FF4081")))
            }
            ArMask.RAINBOW -> {
                val colors = intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA)
                colors.forEachIndexed { i, color -> c.drawArc(RectF(-d*(1.05f+i*0.035f), top-d*0.9f+i*d*0.06f, d*(1.05f+i*0.035f), top+d*0.65f), 205f, 130f, false, p(color, Paint.Style.STROKE, d*0.045f)) }
            }
            ArMask.NONE -> Unit
        }
    }

    private fun animal(c: Canvas, m: ArMask, d: Float, top: Float) {
        when (m) {
            ArMask.PANDA -> {
                for (sx in listOf(-1f, 1f)) {
                    c.drawCircle(sx*d*0.72f, top+d*0.1f, d*0.27f, p(Color.BLACK))
                    c.drawOval(RectF(sx*d*0.5f-d*0.2f, -d*0.28f, sx*d*0.5f+d*0.2f, d*0.22f), p(Color.BLACK))
                }
                c.drawOval(RectF(-d*0.16f, d*0.68f, d*0.16f, d*0.88f), p(Color.BLACK))
            }
            ArMask.TIGER -> {
                for (sx in listOf(-1f, 1f)) {
                    val ear = Path().apply { moveTo(sx*d*0.48f, top+d*0.1f); lineTo(sx*d*0.78f, top-d*0.45f); lineTo(sx*d*1.05f, top+d*0.18f); close() }
                    c.drawPath(ear, p(Color.parseColor("#F57C00")))
                    for (i in 0..2) c.drawLine(sx*d*(0.42f+i*0.17f), d*0.25f, sx*d*(0.55f+i*0.16f), d*0.55f, p(Color.BLACK, Paint.Style.STROKE, d*0.045f))
                }
                c.drawOval(RectF(-d*0.15f, d*0.68f, d*0.15f, d*0.86f), p(Color.BLACK))
            }
            ArMask.BEAR -> {
                for (sx in listOf(-1f, 1f)) {
                    c.drawCircle(sx*d*0.78f, top+d*0.12f, d*0.28f, p(Color.parseColor("#795548")))
                    c.drawCircle(sx*d*0.78f, top+d*0.12f, d*0.15f, p(Color.parseColor("#D7A77D")))
                }
                c.drawOval(RectF(-d*0.18f, d*0.68f, d*0.18f, d*0.9f), p(Color.parseColor("#4E342E")))
            }
            ArMask.CAT -> {
                for (sx in listOf(-1f, 1f)) {
                    val outer = Path().apply { moveTo(sx * d * 0.35f, top + d * 0.1f); lineTo(sx * d * 0.85f, top - d * 0.75f); lineTo(sx * d * 1.1f, top + d * 0.25f); close() }
                    val inner = Path().apply { moveTo(sx * d * 0.5f, top + d * 0.05f); lineTo(sx * d * 0.83f, top - d * 0.5f); lineTo(sx * d * 0.98f, top + d * 0.15f); close() }
                    c.drawPath(outer, p(Color.parseColor("#5D4037"))); c.drawPath(inner, p(Color.parseColor("#F8BBD0")))
                }
                val nose = Path().apply { moveTo(-d * 0.12f, d * 0.72f); lineTo(d * 0.12f, d * 0.72f); lineTo(0f, d * 0.88f); close() }
                c.drawPath(nose, p(Color.parseColor("#F06292")))
                val wh = p(Color.argb(220, 40, 40, 40), Paint.Style.STROKE, d * 0.025f)
                for (sx in listOf(-1f, 1f)) for (k in -1..1) c.drawLine(sx * d * 0.25f, d * 0.9f + k * d * 0.06f, sx * d * 0.95f, d * 0.82f + k * d * 0.14f, wh)
            }
            ArMask.DOG -> {
                for (sx in listOf(-1f, 1f)) {
                    val ear = Path().apply {
                        moveTo(sx * d * 0.55f, top + d * 0.2f)
                        cubicTo(sx * d * 1.3f, top - d * 0.2f, sx * d * 1.45f, top + d * 0.9f, sx * d * 1.05f, top + d * 1.1f)
                        cubicTo(sx * d * 0.9f, top + d * 0.6f, sx * d * 0.75f, top + d * 0.4f, sx * d * 0.55f, top + d * 0.2f)
                        close()
                    }
                    c.drawPath(ear, p(Color.parseColor("#8D6E63")))
                }
                c.drawOval(RectF(-d * 0.22f, d * 0.6f, d * 0.22f, d * 0.88f), p(Color.parseColor("#212121")))
                c.drawOval(RectF(-d * 0.15f, d * 1.35f, d * 0.15f, d * 1.75f), p(Color.parseColor("#FF6F91")))
            }
            ArMask.BUNNY -> {
                for (sx in listOf(-1f, 1f)) {
                    c.save(); c.rotate(sx * 12f, sx * d * 0.4f, top)
                    c.drawOval(RectF(sx * d * 0.4f - d * 0.22f, top - d * 1.3f, sx * d * 0.4f + d * 0.22f, top + d * 0.1f), p(Color.WHITE))
                    c.drawOval(RectF(sx * d * 0.4f - d * 0.11f, top - d * 1.15f, sx * d * 0.4f + d * 0.11f, top - d * 0.05f), p(Color.parseColor("#F8BBD0")))
                    c.restore()
                }
                c.drawCircle(0f, d * 0.78f, d * 0.08f, p(Color.parseColor("#F48FB1")))
            }
            ArMask.WOLF, ArMask.LION, ArMask.DEER, ArMask.RACCOON, ArMask.UNICORN, ArMask.DRAGON, ArMask.OWL, ArMask.BUTTERFLY -> {
                val base = when (m) {
                    ArMask.WOLF -> Color.parseColor("#90A4AE")
                    ArMask.LION -> Color.parseColor("#D89B32")
                    ArMask.DEER -> Color.parseColor("#A66A3F")
                    ArMask.RACCOON -> Color.parseColor("#616A73")
                    ArMask.UNICORN -> Color.parseColor("#F2B6D0")
                    ArMask.DRAGON -> Color.parseColor("#3B9B72")
                    ArMask.OWL -> Color.parseColor("#A9784F")
                    else -> Color.parseColor("#8E72D8")
                }
                for (sx in listOf(-1f, 1f)) {
                    val ear = Path().apply {
                        moveTo(sx*d*0.42f, top+d*0.1f)
                        lineTo(sx*d*0.82f, top-d*(if (m == ArMask.DEER || m == ArMask.UNICORN) 0.95f else 0.62f))
                        lineTo(sx*d*1.05f, top+d*0.18f)
                        close()
                    }
                    c.drawPath(ear, p(base))
                    c.drawCircle(sx*d*0.73f, top+d*0.12f, d*0.12f, p(Color.argb(184, 255, 255, 255)))
                }
                if (m == ArMask.DEER) {
                    for (sx in listOf(-1f, 1f)) {
                        c.drawLine(sx*d*0.42f, top-d*0.05f, sx*d*0.62f, top-d*0.9f, p(base, Paint.Style.STROKE, d*0.07f))
                        c.drawLine(sx*d*0.62f, top-d*0.9f, sx*d*0.88f, top-d*1.05f, p(base, Paint.Style.STROKE, d*0.06f))
                    }
                }
                if (m == ArMask.UNICORN) {
                    val horn = Path().apply { moveTo(-d*0.13f, top); lineTo(0f, top-d*1.0f); lineTo(d*0.13f, top); close() }
                    c.drawPath(horn, p(Color.parseColor("#FFD54F")))
                }
                if (m == ArMask.BUTTERFLY) {
                    for (sx in listOf(-1f,1f)) {
                        c.drawOval(RectF(sx*d*0.4f-d*0.2f, top-d*0.35f, sx*d*1.05f+d*0.1f, top+d*0.35f), p(Color.parseColor("#BA68C8")))
                    }
                }
                c.drawOval(RectF(-d*0.18f, d*0.68f, d*0.18f, d*0.88f), p(if (m == ArMask.RACCOON) Color.BLACK else base))
            }
            else -> Unit
        }
    }

    private fun heart(c: Canvas, x: Float, y: Float, s: Float, paint: Paint) {
        val path = Path().apply {
            moveTo(x, y + s * 0.6f)
            cubicTo(x - s * 1.2f, y - s * 0.2f, x - s * 0.4f, y - s * 1.1f, x, y - s * 0.3f)
            cubicTo(x + s * 0.4f, y - s * 1.1f, x + s * 1.2f, y - s * 0.2f, x, y + s * 0.6f)
            close()
        }
        c.drawPath(path, paint)
    }

    private fun flower(c: Canvas, x: Float, y: Float, r: Float, col: Int) {
        val pp = p(col)
        for (i in 0 until 5) {
            val a = 2 * Math.PI * i / 5
            c.drawCircle(x + (cos(a) * r * 0.6).toFloat(), y + (sin(a) * r * 0.6).toFloat(), r * 0.5f, pp)
        }
        c.drawCircle(x, y, r * 0.35f, p(Color.parseColor("#FFEB3B")))
    }

    private fun sparkle(c: Canvas, x: Float, y: Float, s: Float) {
        val pp = p(Color.WHITE, Paint.Style.STROKE, s * 0.3f)
        c.drawLine(x - s, y, x + s, y, pp); c.drawLine(x, y - s, x, y + s, pp)
    }
}
