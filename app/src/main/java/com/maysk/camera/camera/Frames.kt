package com.maysk.camera.camera

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min

/** Категории рамок. */
enum class FrameStyle(val title: String, val category: String) {
    POLAROID("Polaroid", "Polaroid"),
    INSTAGRAM("Белая рамка", "Instagram"),
    CINEMA("Кино 2.39", "Cinema"),
    MINIMAL("Минимал", "Minimal"),
    ROMANTIC("Романтика", "Romantic"),
    BABY("Малыш", "Baby"),
    TRAVEL("Путешествие", "Travel"),
    SUMMER("Лето", "Summer"),
    WINTER("Зима", "Winter"),
    HOLIDAY("Праздник", "Holiday"),
    BIRTHDAY("День рождения", "Birthday"),
    VINTAGE("Винтаж плёнка", "Vintage"),
    NEON("Неон", "Neon"),
    GRADIENT("Градиент", "Gradient"),
    LUXURY("Люкс золото", "Luxury"),
    AUTO("Авто", "Auto"),
}

/** Параметры рамки: всё настраивается — цвет, толщина, прозрачность, тень, скругление. */
data class FrameSpec(
    val style: FrameStyle,
    val color: Int = Color.WHITE,
    val thickness: Float = 0.5f,   // 0..1
    val opacity: Float = 1f,       // 0..1
    val radius: Float = 0f,        // 0..1 — скругление углов фото
    val shadow: Boolean = false,
    val caption: String = "",
)

object Frames {
    /** Палитра цветов рамок. */
    val palette = listOf(
        Color.WHITE, Color.BLACK, Color.parseColor("#FF2E63"), Color.parseColor("#F4E3C1"),
        Color.parseColor("#1E88E5"), Color.parseColor("#43A047"), Color.parseColor("#FFB300"),
        Color.parseColor("#8E24AA"), Color.parseColor("#D4AF37"), Color.parseColor("#90CAF9"),
    )

    /** Стиль по умолчанию — свой цвет и толщина. */
    fun default(style: FrameStyle): FrameSpec = when (style) {
        FrameStyle.POLAROID -> FrameSpec(style, Color.WHITE, 0.5f, shadow = true)
        FrameStyle.INSTAGRAM -> FrameSpec(style, Color.WHITE, 0.45f)
        FrameStyle.CINEMA -> FrameSpec(style, Color.BLACK, 0.5f)
        FrameStyle.MINIMAL -> FrameSpec(style, Color.WHITE, 0.25f, radius = 0.2f)
        FrameStyle.ROMANTIC -> FrameSpec(style, Color.parseColor("#F8BBD0"), 0.5f, radius = 0.5f, shadow = true)
        FrameStyle.BABY -> FrameSpec(style, Color.parseColor("#B3E5FC"), 0.55f, radius = 0.6f)
        FrameStyle.TRAVEL -> FrameSpec(style, Color.parseColor("#F4E3C1"), 0.5f, caption = "TRAVEL")
        FrameStyle.SUMMER -> FrameSpec(style, Color.parseColor("#FFB300"), 0.5f, radius = 0.3f)
        FrameStyle.WINTER -> FrameSpec(style, Color.parseColor("#E3F2FD"), 0.5f, radius = 0.3f)
        FrameStyle.HOLIDAY -> FrameSpec(style, Color.parseColor("#C62828"), 0.5f)
        FrameStyle.BIRTHDAY -> FrameSpec(style, Color.parseColor("#8E24AA"), 0.5f, radius = 0.4f, caption = "HAPPY BIRTHDAY")
        FrameStyle.VINTAGE -> FrameSpec(style, Color.parseColor("#1B1B1B"), 0.5f)
        FrameStyle.NEON -> FrameSpec(style, Color.parseColor("#FF2E63"), 0.4f, radius = 0.25f)
        FrameStyle.GRADIENT -> FrameSpec(style, Color.parseColor("#FF2E63"), 0.5f, radius = 0.25f)
        FrameStyle.LUXURY -> FrameSpec(style, Color.parseColor("#D4AF37"), 0.5f)
        FrameStyle.AUTO -> FrameSpec(style, Color.parseColor("#212121"), 0.45f, caption = "MAYSK")
    }

    fun apply(src: Bitmap, f: FrameSpec): Bitmap {
        val w = src.width; val h = src.height
        val base = min(w, h)
        val t = (base * (0.015f + f.thickness * 0.07f)).toInt().coerceAtLeast(2)
        val alpha = (f.opacity.coerceIn(0f, 1f) * 255).toInt()
        val col = (f.color and 0x00FFFFFF) or (alpha shl 24)
        return when (f.style) {
            FrameStyle.CINEMA -> cinema(src, f)
            FrameStyle.POLAROID -> padded(src, t, t, t, t * 4, col, f, bottomCaption = f.caption)
            FrameStyle.INSTAGRAM, FrameStyle.MINIMAL, FrameStyle.ROMANTIC, FrameStyle.BABY, FrameStyle.SUMMER,
            FrameStyle.WINTER, FrameStyle.HOLIDAY, FrameStyle.BIRTHDAY, FrameStyle.TRAVEL, FrameStyle.AUTO ->
                padded(src, t, t, t, if (f.caption.isNotBlank()) t * 3 else t, col, f, bottomCaption = f.caption)
            FrameStyle.VINTAGE -> film(src, t, col)
            FrameStyle.NEON -> neon(src, t, f)
            FrameStyle.GRADIENT -> gradient(src, t, f)
            FrameStyle.LUXURY -> luxury(src, t, f)
        }
    }

    /**
     * Рамка для живого превью: та же рамка, что будет на фото, но с прозрачным «окном»
     * на месте снимка — накладывается поверх кадра камеры до съёмки.
     */
    fun previewOverlay(w: Int, h: Int, f: FrameSpec): Bitmap {
        val key = Color.rgb(0, 255, 1)
        val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(key) }
        val out = apply(src, f).let { if (it.isMutable) it else it.copy(Bitmap.Config.ARGB_8888, true) }
        val px = IntArray(out.width * out.height)
        out.getPixels(px, 0, out.width, 0, 0, out.width, out.height)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            // ключевой зелёный → прозрачно; на сглаженных краях — частично
            if (g > 150 && r < 90 && b < 90) {
                val k = ((g - 150) / 105f).coerceIn(0f, 1f)
                val a = ((1f - k) * ((c ushr 24) and 255)).toInt()
                px[i] = if (a < 10) 0 else (a shl 24) or (c and 0x00FFFFFF)
            }
        }
        out.setPixels(px, 0, out.width, 0, 0, out.width, out.height)
        return out
    }

    private fun roundedPhoto(c: Canvas, src: Bitmap, dst: RectF, radiusPx: Float, shadow: Boolean) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (shadow) {
            val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(110, 0, 0, 0)
                maskFilter = BlurMaskFilter(radiusPx.coerceAtLeast(12f) + dst.width() * 0.01f, BlurMaskFilter.Blur.NORMAL)
            }
            val off = dst.width() * 0.006f
            c.drawRoundRect(RectF(dst.left + off, dst.top + off * 2, dst.right + off, dst.bottom + off * 2), radiusPx, radiusPx, sp)
        }
        if (radiusPx <= 0.5f) { c.drawBitmap(src, null, dst, p); return }
        val save = c.saveLayer(dst, null)
        c.drawRoundRect(dst, radiusPx, radiusPx, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        c.drawBitmap(src, null, dst, p)
        c.restoreToCount(save)
    }

    private fun padded(src: Bitmap, l: Int, tp: Int, r: Int, b: Int, col: Int, f: FrameSpec, bottomCaption: String): Bitmap {
        val out = Bitmap.createBitmap(src.width + l + r, src.height + tp + b, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(col)
        // мягкий узор для тематических рамок
        decorate(c, out, f)
        val dst = RectF(l.toFloat(), tp.toFloat(), (l + src.width).toFloat(), (tp + src.height).toFloat())
        roundedPhoto(c, src, dst, f.radius * min(src.width, src.height) * 0.08f, f.shadow)
        if (bottomCaption.isNotBlank()) {
            val tpaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (isLight(col)) Color.parseColor("#333333") else Color.WHITE
                textSize = b * 0.38f
                typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                textAlign = Paint.Align.CENTER
            }
            c.drawText(bottomCaption, out.width / 2f, tp + src.height + b * 0.65f, tpaint)
        }
        return out
    }

    private fun decorate(c: Canvas, out: Bitmap, f: FrameSpec) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val rnd = kotlin.random.Random(f.style.ordinal)
        val n = 26
        when (f.style) {
            FrameStyle.ROMANTIC -> { p.color = Color.argb(90, 255, 255, 255); repeat(n) { heart(c, rnd.nextFloat() * out.width, rnd.nextFloat() * out.height, out.width * 0.012f, p) } }
            FrameStyle.WINTER -> { p.color = Color.argb(160, 255, 255, 255); repeat(n * 2) { c.drawCircle(rnd.nextFloat() * out.width, rnd.nextFloat() * out.height, out.width * 0.004f, p) } }
            FrameStyle.HOLIDAY, FrameStyle.BIRTHDAY -> repeat(n * 2) {
                p.color = Frames.palette[rnd.nextInt(Frames.palette.size)]
                c.drawCircle(rnd.nextFloat() * out.width, rnd.nextFloat() * out.height, out.width * 0.005f, p)
            }
            FrameStyle.SUMMER -> { p.color = Color.argb(70, 255, 255, 255); repeat(n) { c.drawCircle(rnd.nextFloat() * out.width, rnd.nextFloat() * out.height, out.width * 0.01f, p) } }
            FrameStyle.BABY -> { p.color = Color.argb(120, 255, 255, 255); repeat(n) { star(c, rnd.nextFloat() * out.width, rnd.nextFloat() * out.height, out.width * 0.01f, p) } }
            else -> Unit
        }
    }

    private fun heart(c: Canvas, x: Float, y: Float, s: Float, p: Paint) {
        val path = Path().apply {
            moveTo(x, y + s * 0.6f)
            cubicTo(x - s * 1.2f, y - s * 0.2f, x - s * 0.4f, y - s * 1.1f, x, y - s * 0.3f)
            cubicTo(x + s * 0.4f, y - s * 1.1f, x + s * 1.2f, y - s * 0.2f, x, y + s * 0.6f)
            close()
        }
        c.drawPath(path, p)
    }

    private fun star(c: Canvas, x: Float, y: Float, s: Float, p: Paint) {
        val path = Path()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) s else s * 0.45f
            val a = Math.PI / 5 * i - Math.PI / 2
            val px = x + (r * kotlin.math.cos(a)).toFloat(); val py = y + (r * kotlin.math.sin(a)).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        c.drawPath(path, p)
    }

    private fun cinema(src: Bitmap, f: FrameSpec): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val target = 2.39f
        val p = Paint().apply { color = f.color or (0xFF shl 24) }
        if (src.width >= src.height) {
            val barH = ((src.height - src.width / target) / 2f).coerceAtLeast(src.height * 0.06f)
            c.drawRect(0f, 0f, src.width.toFloat(), barH, p)
            c.drawRect(0f, src.height - barH, src.width.toFloat(), src.height.toFloat(), p)
        } else {
            val barH = src.height * (0.08f + f.thickness * 0.1f)
            c.drawRect(0f, 0f, src.width.toFloat(), barH, p)
            c.drawRect(0f, src.height - barH, src.width.toFloat(), src.height.toFloat(), p)
        }
        return out
    }

    private fun film(src: Bitmap, t: Int, col: Int): Bitmap {
        val side = t * 3
        val out = Bitmap.createBitmap(src.width + side * 2, src.height + t * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(col)
        c.drawBitmap(src, side.toFloat(), t.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 240, 235, 220) }
        val hw = side * 0.45f; val hh = side * 0.32f
        var y = t.toFloat()
        while (y < out.height - hh) {
            c.drawRoundRect(RectF(side / 2f - hw / 2, y, side / 2f + hw / 2, y + hh), hh * 0.25f, hh * 0.25f, hole)
            c.drawRoundRect(RectF(out.width - side / 2f - hw / 2, y, out.width - side / 2f + hw / 2, y + hh), hh * 0.25f, hh * 0.25f, hole)
            y += hh * 2.2f
        }
        return out
    }

    private fun neon(src: Bitmap, t: Int, f: FrameSpec): Bitmap {
        val pad = t * 2
        val out = Bitmap.createBitmap(src.width + pad * 2, src.height + pad * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.parseColor("#0B0710"))
        val dst = RectF(pad.toFloat(), pad.toFloat(), (pad + src.width).toFloat(), (pad + src.height).toFloat())
        val r = f.radius * min(src.width, src.height) * 0.08f
        roundedPhoto(c, src, dst, r, false)
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = t * 0.5f; color = f.color
            maskFilter = BlurMaskFilter(t * 1.2f, BlurMaskFilter.Blur.NORMAL)
        }
        c.drawRoundRect(dst, r, r, glow)
        c.drawRoundRect(dst, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = t * 0.18f; color = Color.WHITE })
        return out
    }

    private fun gradient(src: Bitmap, t: Int, f: FrameSpec): Bitmap {
        val out = Bitmap.createBitmap(src.width + t * 2, src.height + t * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint().apply {
            shader = LinearGradient(0f, 0f, out.width.toFloat(), out.height.toFloat(),
                intArrayOf(f.color, Color.parseColor("#7C4DFF"), Color.parseColor("#00E5FF")), null, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, out.width.toFloat(), out.height.toFloat(), p)
        roundedPhoto(c, src, RectF(t.toFloat(), t.toFloat(), (t + src.width).toFloat(), (t + src.height).toFloat()),
            f.radius * min(src.width, src.height) * 0.08f, f.shadow)
        return out
    }

    private fun luxury(src: Bitmap, t: Int, f: FrameSpec): Bitmap {
        val out = Bitmap.createBitmap(src.width + t * 2, src.height + t * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.parseColor("#111111"))
        c.drawBitmap(src, t.toFloat(), t.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        val gold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            shader = LinearGradient(0f, 0f, out.width.toFloat(), out.height.toFloat(),
                intArrayOf(f.color, Color.parseColor("#FFF3B0"), f.color), null, Shader.TileMode.MIRROR)
        }
        gold.strokeWidth = t * 0.22f
        val i1 = t * 0.3f
        c.drawRect(i1, i1, out.width - i1, out.height - i1, gold)
        gold.strokeWidth = t * 0.08f
        val i2 = t * 0.72f
        c.drawRect(i2, i2, out.width - i2, out.height - i2, gold)
        return out
    }

    private fun isLight(c: Int): Boolean {
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        return r * 0.299 + g * 0.587 + b * 0.114 > 150
    }

    @Suppress("unused")
    private fun clampF(v: Float) = max(0f, min(1f, v))
}
