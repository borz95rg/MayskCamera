package com.maysk.camera.camera

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Рамки. Каждая рамка рисуется ПОВЕРХ кадра (кадр всегда занимает весь экран),
 * поэтому одна и та же рамка одинаково выглядит в живом превью, на фото и в записи видео.
 * [photoOnly] — рамки, которым нужен сам снимок (размытый фон): в видео не доступны.
 */
enum class FrameStyle(val title: String, val category: String, val photoOnly: Boolean = false) {
    // Классика
    WHITE("Белая тонкая", "Классика"),
    POLAROID("Polaroid", "Классика"),
    ROUNDED("Минимал", "Классика"),
    DOUBLE_LINE("Двойная линия", "Классика"),
    DOUBLE_GOLD("Двойная золотая", "Люкс"),
    GALLERY("Галерея", "Классика"),
    BLUR_FILL("Размытый фон", "Классика", photoOnly = true),
    // Кино и плёнка
    CINEMA("Кино 2.39", "Кино"),
    FILM_STRIP("Плёнка 35 мм", "Кино"),
    VIEWFINDER("Видоискатель REC", "Кино"),
    DATE_STAMP("Дата на плёнке", "Кино"),
    VINTAGE("Старое фото", "Кино"),
    // Соцсети и журнал
    SOCIAL_POST("Соцпост", "Соцсети"),
    SIGNATURE("Подпись камеры", "Соцсети"),
    COMIC("Комикс", "Соцсети"),
    CYBER("Кибер HUD", "Соцсети"),
    // Бумага и творчество
    POSTCARD("Открытка", "Творчество"),
    TAPE("Скотч", "Творчество"),
    TORN("Рваная бумага", "Творчество"),
    SCALLOP("Фигурный край", "Творчество"),
    // Настроение
    FLORAL("Цветы", "Настроение"),
    HEARTS("Романтика", "Настроение"),
    STARS("Звёзды", "Настроение"),
    NEON("Неон", "Настроение"),
    GRADIENT("Градиент", "Настроение"),
    // Праздники и сезоны
    BIRTHDAY("День рождения", "Праздники"),
    NEW_YEAR("Новый год", "Праздники"),
    WINTER("Зима", "Праздники"),
    SUMMER("Лето", "Праздники"),
    TRAVEL("Путешествие", "Праздники"),
    // Люкс
    GOLD("Золото", "Люкс"),
    BLACK_GOLD("Чёрное золото", "Люкс"),
    AURA("Аура", "Настроение"),
    BOTANICAL("Ботаника", "Настроение"),
    LUXE_CORNERS("Уголки люкс", "Люкс"),
    CLEAN_STORY("Чистая история", "Соцсети"),
    /** Картинка-рамка из пакета шаблонов (FrameSpec.image). В списке стилей не показывается. */
    IMAGE("Шаблон", "Шаблоны"),
    ;

    companion object {
        val categories: List<String> get() = entries.filter { it != IMAGE }.map { it.category }.distinct()
    }
}

/** Параметры рамки: цвет, толщина, прозрачность, тень, скругление, подпись. */
data class FrameSpec(
    val style: FrameStyle,
    val color: Int = Color.WHITE,
    val thickness: Float = 0.5f,   // 0..1
    val opacity: Float = 1f,       // 0..1
    val radius: Float = 0f,        // 0..1 — скругление «окна»
    val shadow: Boolean = false,
    val caption: String = "",
    /** Для стиля IMAGE: путь к PNG в assets и режим ("stretch" — по краю кадра, "corner" — в угол). */
    val image: String? = null,
    val imageMode: String = "stretch",
)

object Frames {
    /** Загрузчик картинок для рамок-шаблонов (ставится приложением; null — картинки недоступны). */
    @Volatile var imageLoader: ((String) -> Bitmap?)? = null

    /** Палитра цветов рамок. */
    val palette = listOf(
        Color.WHITE, Color.BLACK, Color.parseColor("#FF2E63"), Color.parseColor("#F4E3C1"),
        Color.parseColor("#1E88E5"), Color.parseColor("#43A047"), Color.parseColor("#FFB300"),
        Color.parseColor("#8E24AA"), Color.parseColor("#D4AF37"), Color.parseColor("#90CAF9"),
        Color.parseColor("#F8BBD0"), Color.parseColor("#263238"),
    )

    private fun col(s: String) = Color.parseColor(s)

    /** Стиль по умолчанию — свой цвет, толщина и подпись. */
    fun default(style: FrameStyle): FrameSpec = when (style) {
        FrameStyle.WHITE -> FrameSpec(style, Color.WHITE, 0.15f)
        FrameStyle.POLAROID -> FrameSpec(style, col("#FBFAF6"), 0.45f, shadow = true, caption = "лучший день ♥")
        FrameStyle.ROUNDED -> FrameSpec(style, Color.WHITE, 0.35f, radius = 0.6f, shadow = true)
        FrameStyle.DOUBLE_LINE -> FrameSpec(style, Color.WHITE, 0.4f)
        FrameStyle.DOUBLE_GOLD -> FrameSpec(style, col("#D4AF37"), 0.4f)
        FrameStyle.GALLERY -> FrameSpec(style, col("#111111"), 0.6f, caption = "MAYSK · 2026")
        FrameStyle.BLUR_FILL -> FrameSpec(style, Color.WHITE, 0.45f, radius = 0.35f, shadow = true)
        FrameStyle.CINEMA -> FrameSpec(style, Color.BLACK, 0.5f, caption = "A MAYSK FILM")
        FrameStyle.FILM_STRIP -> FrameSpec(style, col("#0E0E0E"), 0.5f, caption = "MAYSK 400")
        FrameStyle.VIEWFINDER -> FrameSpec(style, Color.WHITE, 0.4f)
        FrameStyle.DATE_STAMP -> FrameSpec(style, col("#FF8A1E"), 0.4f)
        FrameStyle.VINTAGE -> FrameSpec(style, col("#EFE6D2"), 0.45f, radius = 0.15f)
        FrameStyle.SOCIAL_POST -> FrameSpec(style, Color.WHITE, 0.5f, caption = "maysk.camera")
        FrameStyle.SIGNATURE -> FrameSpec(style, Color.WHITE, 0.4f, caption = "MAYSK CAMERA")
        FrameStyle.COMIC -> FrameSpec(style, col("#FFD600"), 0.5f, caption = "WOW!")
        FrameStyle.CYBER -> FrameSpec(style, col("#00E5FF"), 0.4f)
        FrameStyle.POSTCARD -> FrameSpec(style, col("#F6EBD9"), 0.45f, caption = "Привет из путешествия!")
        FrameStyle.TAPE -> FrameSpec(style, Color.WHITE, 0.3f, shadow = true)
        FrameStyle.TORN -> FrameSpec(style, Color.WHITE, 0.45f)
        FrameStyle.SCALLOP -> FrameSpec(style, col("#F8BBD0"), 0.45f)
        FrameStyle.FLORAL -> FrameSpec(style, col("#F48FB1"), 0.5f)
        FrameStyle.HEARTS -> FrameSpec(style, col("#FF2E63"), 0.45f, radius = 0.4f)
        FrameStyle.STARS -> FrameSpec(style, col("#FFE082"), 0.45f)
        FrameStyle.NEON -> FrameSpec(style, col("#FF2E63"), 0.4f, radius = 0.3f)
        FrameStyle.GRADIENT -> FrameSpec(style, col("#FF2E63"), 0.45f, radius = 0.3f)
        FrameStyle.BIRTHDAY -> FrameSpec(style, col("#8E24AA"), 0.5f, caption = "С днём рождения!")
        FrameStyle.NEW_YEAR -> FrameSpec(style, col("#C62828"), 0.5f, caption = "С Новым годом!")
        FrameStyle.WINTER -> FrameSpec(style, col("#E3F2FD"), 0.5f)
        FrameStyle.SUMMER -> FrameSpec(style, col("#FFB300"), 0.5f, caption = "summer vibes")
        FrameStyle.TRAVEL -> FrameSpec(style, col("#F4E3C1"), 0.45f, caption = "TRAVEL")
        FrameStyle.GOLD -> FrameSpec(style, col("#D4AF37"), 0.5f)
        FrameStyle.BLACK_GOLD -> FrameSpec(style, col("#D4AF37"), 0.55f, caption = "LUXURY")
        FrameStyle.IMAGE -> FrameSpec(style, Color.WHITE, 0.5f)
        FrameStyle.AURA -> FrameSpec(style, col("#C77DFF"), 0.42f)
        FrameStyle.BOTANICAL -> FrameSpec(style, col("#7C9A72"), 0.42f)
        FrameStyle.LUXE_CORNERS -> FrameSpec(style, col("#D4AF37"), 0.4f)
        FrameStyle.CLEAN_STORY -> FrameSpec(style, Color.WHITE, 0.3f, caption = "maysk.camera")
    }

    // ------------------------------------------------------------------ публичный API

    /** Рамка на готовом фото (тот же рисунок, что и в живом превью). */
    fun apply(src: Bitmap, f: FrameSpec): Bitmap {
        if (f.style == FrameStyle.BLUR_FILL) return blurFill(src, f)
        val out = if (src.isMutable && src.config == Bitmap.Config.ARGB_8888) src else src.copy(Bitmap.Config.ARGB_8888, true)
        draw(Canvas(out), out.width, out.height, f)
        return out
    }

    /** Прозрачный слой рамки для живого превью и для записи видео. */
    fun previewOverlay(w: Int, h: Int, f: FrameSpec): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        if (f.style == FrameStyle.BLUR_FILL) {
            // в превью — матовая полупрозрачная подложка вместо размытия
            val g = geo(w, h, f)
            band(c, w, h, g.t, g.t, g.t, g.t, paint(Color.argb(150, 235, 235, 240)), g.r)
        } else draw(c, w, h, f)
        return out
    }

    /** Миниатюра рамки на примере-фото (для выбора по картинкам). */
    fun thumbnail(sample: Bitmap, f: FrameSpec): Bitmap = apply(sample.copy(Bitmap.Config.ARGB_8888, true), f)

    // ------------------------------------------------------------------ рисование

    private class Geo(val s: Float, val t: Float, val r: Float)

    private fun geo(w: Int, h: Int, f: FrameSpec): Geo {
        val s = min(w, h).toFloat()
        return Geo(s, s * (0.02f + f.thickness * 0.06f), f.radius * s * 0.08f)
    }

    private fun withAlpha(c: Int, a: Float) = (c and 0x00FFFFFF) or (((Color.alpha(c) * a).toInt().coerceIn(0, 255)) shl 24)

    private fun paint(color: Int, style: Paint.Style = Paint.Style.FILL, stroke: Float = 0f) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; this.style = style; strokeWidth = stroke }

    private fun text(color: Int, size: Float, face: Typeface = Typeface.DEFAULT_BOLD, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = size; typeface = face; textAlign = align }

    private val serif get() = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    private val serifItalic get() = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    private val script get() = Typeface.create("cursive", Typeface.BOLD)
    private val mono get() = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    /** Полоса рамки между краем кадра и (скруглённым) «окном». */
    private fun band(c: Canvas, w: Int, h: Int, l: Float, t: Float, r: Float, b: Float, p: Paint, radius: Float = 0f) {
        val path = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW)
            addRoundRect(RectF(l, t, w - r, h - b), radius, radius, Path.Direction.CW)
        }
        c.drawPath(path, p)
    }

    /** Мягкая тень внутрь «окна» — фото выглядит вложенным в паспарту. */
    private fun innerShadow(c: Canvas, rect: RectF, radius: Float, strength: Float) {
        val p = paint(Color.argb((90 * strength).toInt(), 0, 0, 0), Paint.Style.STROKE, rect.width() * 0.012f)
        p.maskFilter = BlurMaskFilter(rect.width() * 0.012f, BlurMaskFilter.Blur.NORMAL)
        c.drawRoundRect(rect, radius, radius, p)
    }

    fun draw(c: Canvas, w: Int, h: Int, f: FrameSpec) {
        val g = geo(w, h, f)
        val a = f.opacity.coerceIn(0f, 1f)
        val color = withAlpha(f.color, a)
        val W = w.toFloat(); val H = h.toFloat()
        val s = g.s; val t = g.t
        when (f.style) {
            FrameStyle.WHITE -> {
                band(c, w, h, t, t, t, t, paint(color), g.r)
                if (f.shadow) innerShadow(c, RectF(t, t, W - t, H - t), g.r, a)
            }
            FrameStyle.POLAROID -> {
                val bottom = t * 4.2f
                band(c, w, h, t, t, t, bottom, paint(color), g.r)
                innerShadow(c, RectF(t, t, W - t, H - bottom), g.r, a * 0.8f)
                if (f.caption.isNotBlank()) {
                    val tp = text(withAlpha(col("#2B2B2B"), a), bottom * 0.36f, script, Paint.Align.CENTER)
                    c.drawText(f.caption, W / 2f, H - bottom * 0.38f, tp)
                }
            }
            FrameStyle.ROUNDED -> {
                val r = max(g.r, s * 0.05f)
                band(c, w, h, t, t, t, t, paint(color), r)
                innerShadow(c, RectF(t, t, W - t, H - t), r, a)
            }
            FrameStyle.DOUBLE_GOLD -> {
                // две золотые линии с градиентом «металла» и тонкая тень — только по краю
                val m = s * 0.03f
                val gold = paint(0, Paint.Style.STROKE, s * 0.008f)
                gold.shader = LinearGradient(0f, 0f, W, H, intArrayOf(col("#8C6B1F"), col("#F7E7A1"), col("#B8892B"), col("#FFF2B2"), col("#8C6B1F")), null, Shader.TileMode.CLAMP)
                gold.alpha = (255 * a).toInt()
                gold.setShadowLayer(s * 0.004f, 0f, s * 0.002f, Color.argb((120 * a).toInt(), 0, 0, 0))
                c.drawRect(RectF(m, m, W - m, H - m), gold)
                val thin = Paint(gold).apply { strokeWidth = s * 0.0035f }
                val m2 = m + s * 0.02f
                c.drawRect(RectF(m2, m2, W - m2, H - m2), thin)
                listOf(m to m, W - m to m, m to H - m, W - m to H - m).forEach { (x, y) ->
                    ornament(c, x, y, s * 0.018f, paint(withAlpha(col("#D4AF37"), a)))
                }
            }
            FrameStyle.DOUBLE_LINE -> {
                val m = s * 0.035f
                c.drawRect(RectF(m, m, W - m, H - m), paint(color, Paint.Style.STROKE, s * 0.006f))
                val m2 = m + s * 0.018f
                c.drawRect(RectF(m2, m2, W - m2, H - m2), paint(color, Paint.Style.STROKE, s * 0.0025f))
                // уголки-ромбы
                val d = s * 0.012f
                listOf(m to m, W - m to m, m to H - m, W - m to H - m).forEach { (x, y) ->
                    val p = Path().apply { moveTo(x, y - d); lineTo(x + d, y); lineTo(x, y + d); lineTo(x - d, y); close() }
                    c.drawPath(p, paint(color))
                }
            }
            FrameStyle.GALLERY -> {
                val side = t * 1.6f; val bottom = t * 3.2f
                band(c, w, h, side, side, side, bottom, paint(color))
                val line = paint(withAlpha(if (isLight(f.color)) Color.BLACK else Color.WHITE, a * 0.7f), Paint.Style.STROKE, s * 0.003f)
                c.drawRect(RectF(side - s * 0.008f, side - s * 0.008f, W - side + s * 0.008f, H - bottom + s * 0.008f), line)
                if (f.caption.isNotBlank()) {
                    val tp = text(withAlpha(if (isLight(f.color)) col("#222222") else Color.WHITE, a), bottom * 0.22f, serif, Paint.Align.CENTER)
                    tp.letterSpacing = 0.3f
                    c.drawText(f.caption, W / 2f, H - bottom * 0.42f, tp)
                }
            }
            FrameStyle.BLUR_FILL -> band(c, w, h, t, t, t, t, paint(Color.argb(150, 235, 235, 240)), g.r)
            FrameStyle.CINEMA -> {
                val target = 2.39f
                // на вертикальном кадре полосы ограничены, иначе от фото остаётся узкая щель
                val bar = if (W / H < target) ((H - W / target) / 2f).coerceIn(0f, max(H * 0.13f, (H - W / 1.85f) / 2f).coerceAtMost(H * 0.2f)) else 0f
                val p = paint(color)
                c.drawRect(0f, 0f, W, bar, p); c.drawRect(0f, H - bar, W, H, p)
                if (bar > s * 0.05f) {
                    val tp = text(withAlpha(Color.WHITE, a * 0.85f), min(bar * 0.16f, s * 0.04f), serif, Paint.Align.CENTER)
                    tp.letterSpacing = 0.45f
                    if (f.caption.isNotBlank()) c.drawText(f.caption, W / 2f, H - bar / 2f + tp.textSize * 0.35f, tp)
                    if (f.caption.isNotBlank()) {
                        val sp = text(withAlpha(Color.WHITE, a * 0.55f), tp.textSize * 0.6f, mono, Paint.Align.CENTER)
                        c.drawText("SCENE 01   TAKE 03   ${today()}", W / 2f, bar / 2f + sp.textSize * 0.35f, sp)
                    }
                }
            }
            FrameStyle.FILM_STRIP -> {
                val side = s * 0.11f
                val p = paint(color)
                c.drawRect(0f, 0f, side, H, p); c.drawRect(W - side, 0f, W, H, p)
                val hole = paint(withAlpha(col("#F2EDE4"), a * 0.92f))
                val hw = side * 0.34f; val hh = side * 0.24f; val step = hh * 2.1f
                var y = step * 0.4f
                while (y < H) {
                    c.drawRoundRect(RectF(side * 0.33f - hw / 2, y, side * 0.33f + hw / 2, y + hh), hh * 0.25f, hh * 0.25f, hole)
                    c.drawRoundRect(RectF(W - side * 0.33f - hw / 2, y, W - side * 0.33f + hw / 2, y + hh), hh * 0.25f, hh * 0.25f, hole)
                    y += step
                }
                // оранжевая маркировка плёнки по краю
                val tp = text(withAlpha(col("#FF9F1C"), a), side * 0.16f, mono, Paint.Align.CENTER)
                var k = 0
                var ty = H * 0.1f
                while (ty < H) {
                    c.save(); c.rotate(90f, side * 0.78f, ty)
                    c.drawText(if (k % 2 == 0) "${f.caption.ifBlank { "MAYSK 400" }}  ▸ ${12 + k}" else "◂ ${12 + k}A", side * 0.78f, ty + tp.textSize * 0.35f, tp)
                    c.restore()
                    ty += H * 0.22f; k++
                }
            }
            FrameStyle.VIEWFINDER -> {
                val m = s * 0.06f; val L = s * 0.1f; val sw = s * 0.006f
                val p = paint(withAlpha(f.color, a * 0.95f), Paint.Style.STROKE, sw).apply { strokeCap = Paint.Cap.ROUND }
                // уголки
                c.drawLine(m, m, m + L, m, p); c.drawLine(m, m, m, m + L, p)
                c.drawLine(W - m, m, W - m - L, m, p); c.drawLine(W - m, m, W - m, m + L, p)
                c.drawLine(m, H - m, m + L, H - m, p); c.drawLine(m, H - m, m, H - m - L, p)
                c.drawLine(W - m, H - m, W - m - L, H - m, p); c.drawLine(W - m, H - m, W - m, H - m - L, p)
                val thin = paint(withAlpha(f.color, a * 0.7f), Paint.Style.STROKE, sw * 0.6f)
                // REC
                val ts = s * 0.04f
                c.drawCircle(m + L * 0.35f, m + L * 0.75f, ts * 0.32f, paint(withAlpha(col("#FF1744"), a)))
                c.drawText("REC", m + L * 0.35f + ts * 0.6f, m + L * 0.75f + ts * 0.36f, text(withAlpha(f.color, a), ts, mono))
                val tc = text(withAlpha(f.color, a), ts * 0.8f, mono, Paint.Align.RIGHT)
                c.drawText("00:00:24:12", W - m - L * 0.2f, m + L * 0.75f + ts * 0.3f, tc)
                // батарея
                val bx = W - m - L * 0.2f - s * 0.07f; val by = H - m - L * 0.75f
                c.drawRoundRect(RectF(bx, by, bx + s * 0.06f, by + s * 0.03f), s * 0.005f, s * 0.005f, thin)
                c.drawRect(RectF(bx + s * 0.006f, by + s * 0.006f, bx + s * 0.045f, by + s * 0.024f), paint(withAlpha(f.color, a * 0.9f)))
                c.drawText("4K 60  ISO 100", m + L * 0.2f, H - m - L * 0.5f, text(withAlpha(f.color, a), ts * 0.7f, mono))
            }
            FrameStyle.DATE_STAMP -> {
                val ts = s * 0.065f
                val tp = text(withAlpha(f.color, a), ts, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD), Paint.Align.RIGHT)
                tp.letterSpacing = 0.08f
                val glow = Paint(tp).apply { maskFilter = BlurMaskFilter(ts * 0.25f, BlurMaskFilter.Blur.NORMAL); this.color = withAlpha(f.color, a * 0.8f) }
                val d = today("''yy  M  d")
                c.drawText(d, W - s * 0.06f, H - s * 0.07f, glow)
                c.drawText(d, W - s * 0.06f, H - s * 0.07f, tp)
                // лёгкая плёночная виньетка
                vignette(c, W, H, 0.35f * a)
            }
            FrameStyle.VINTAGE -> {
                vignette(c, W, H, 0.6f * a)
                // потёртости
                val rnd = kotlin.random.Random(7)
                val sp = paint(Color.argb((70 * a).toInt(), 255, 248, 230), Paint.Style.STROKE, s * 0.0015f)
                repeat(14) {
                    val x = if (rnd.nextBoolean()) rnd.nextFloat() * s * 0.15f else W - rnd.nextFloat() * s * 0.15f; val y = rnd.nextFloat() * H
                    c.drawLine(x, y, x + (rnd.nextFloat() - 0.5f) * s * 0.03f, y + rnd.nextFloat() * s * 0.25f, sp)
                }
                val dust = paint(Color.argb((90 * a).toInt(), 30, 20, 10))
                repeat(40) { c.drawCircle(if (rnd.nextBoolean()) rnd.nextFloat() * s * 0.15f else W - rnd.nextFloat() * s * 0.15f, rnd.nextFloat() * H, s * 0.0025f * (0.5f + rnd.nextFloat()), dust) }
                band(c, w, h, t, t, t, t * 1.3f, paint(color), max(g.r, s * 0.02f))
            }
            FrameStyle.SOCIAL_POST -> {
                val top = s * 0.13f; val bottom = s * 0.2f
                band(c, w, h, 0f, top, 0f, bottom, paint(color))
                val dark = if (isLight(f.color)) col("#1A1A1A") else Color.WHITE
                val ink = withAlpha(dark, a)
                // аватар с градиентным кольцом
                val ar = top * 0.3f; val ax = s * 0.06f + ar; val ay = top / 2
                val ring = paint(0, Paint.Style.STROKE, ar * 0.22f)
                ring.shader = LinearGradient(ax - ar, ay + ar, ax + ar, ay - ar, intArrayOf(col("#FEDA75"), col("#FA7E1E"), col("#D62976"), col("#962FBF")), null, Shader.TileMode.CLAMP)
                ring.alpha = (255 * a).toInt()
                c.drawCircle(ax, ay, ar, ring)
                c.drawCircle(ax, ay, ar * 0.72f, paint(withAlpha(col("#FF2E63"), a)))
                c.drawText("M", ax, ay + ar * 0.3f, text(withAlpha(Color.WHITE, a), ar * 0.85f, Typeface.DEFAULT_BOLD, Paint.Align.CENTER))
                c.drawText(f.caption.ifBlank { "maysk.camera" }, ax + ar * 1.6f, ay + top * 0.1f, text(ink, top * 0.24f))
                c.drawText("•••", W - s * 0.06f, ay + top * 0.08f, text(ink, top * 0.24f, Typeface.DEFAULT_BOLD, Paint.Align.RIGHT))
                // иконки: сердце, комментарий, отправить, закладка
                val iy = H - bottom + bottom * 0.3f; val isz = bottom * 0.15f
                heart(c, s * 0.06f + isz, iy, isz, paint(withAlpha(col("#FF3040"), a)))
                val stroke = paint(ink, Paint.Style.STROKE, isz * 0.18f).apply { strokeJoin = Paint.Join.ROUND }
                bubble(c, s * 0.06f + isz * 3.6f, iy, isz, stroke)
                plane(c, s * 0.06f + isz * 6.2f, iy, isz, stroke)
                bookmark(c, W - s * 0.06f - isz, iy, isz, stroke)
                c.drawText("Нравится: 1 024", s * 0.06f, iy + bottom * 0.32f, text(ink, bottom * 0.13f))
                c.drawText("вчера", s * 0.06f, iy + bottom * 0.55f, text(withAlpha(dark, a * 0.55f), bottom * 0.1f, Typeface.DEFAULT))
            }
            FrameStyle.SIGNATURE -> {
                val bottom = s * 0.16f
                band(c, w, h, 0f, 0f, 0f, bottom, paint(color))
                val ink = withAlpha(if (isLight(f.color)) col("#111111") else Color.WHITE, a)
                val sub = withAlpha(if (isLight(f.color)) col("#777777") else col("#BBBBBB"), a)
                val y = H - bottom / 2
                c.drawText(f.caption.ifBlank { "MAYSK CAMERA" }, s * 0.06f, y - bottom * 0.02f, text(ink, bottom * 0.2f))
                c.drawText(today("d MMM yyyy, HH:mm"), s * 0.06f, y + bottom * 0.2f, text(sub, bottom * 0.12f, Typeface.DEFAULT))
                c.drawText("24mm  f/1.8  1/120s  ISO 100", W - s * 0.06f, y + bottom * 0.06f, text(ink, bottom * 0.13f, Typeface.DEFAULT_BOLD, Paint.Align.RIGHT))
                // логотип-кольцо
                val lx = W / 2 + s * 0.02f
                c.drawLine(lx + s * 0.06f, y - bottom * 0.2f, lx + s * 0.06f, y + bottom * 0.2f, paint(sub, Paint.Style.STROKE, s * 0.002f))
                c.drawCircle(lx, y, bottom * 0.16f, paint(withAlpha(col("#FF2E63"), a), Paint.Style.STROKE, bottom * 0.05f))
                c.drawCircle(lx, y, bottom * 0.05f, paint(withAlpha(col("#FF2E63"), a)))
            }
            FrameStyle.COMIC -> {
                val sw = s * 0.022f
                c.drawRect(RectF(sw / 2, sw / 2, W - sw / 2, H - sw / 2), paint(withAlpha(Color.BLACK, a), Paint.Style.STROKE, sw))
                // полутон по углам
                val dot = paint(withAlpha(f.color, a))
                val step = s * 0.028f
                for (corner in 0 until 2) {
                    val ox = if (corner == 0) 0f else W; val oy = if (corner == 0) H else 0f
                    var yy = 0f
                    while (yy < s * 0.35f) {
                        var xx = 0f
                        while (xx < s * 0.35f) {
                            val dist = (xx + yy) / (s * 0.35f)
                            val r = step * 0.45f * (1f - dist)
                            if (r > 0.5f) c.drawCircle(if (corner == 0) ox + xx + sw else ox - xx - sw, if (corner == 0) oy - yy - sw else oy + yy + sw, r, dot)
                            xx += step
                        }
                        yy += step
                    }
                }
                // «взрыв» с надписью
                val cx = W - s * 0.16f; val cy = s * 0.13f; val R = s * 0.1f
                val burst = Path()
                for (i in 0 until 24) {
                    val ang = i * PI.toFloat() * 2f / 24
                    val rr = if (i % 2 == 0) R else R * 0.72f
                    val x = cx + cos(ang) * rr * 1.25f; val y = cy + sin(ang) * rr
                    if (i == 0) burst.moveTo(x, y) else burst.lineTo(x, y)
                }
                burst.close()
                c.drawPath(burst, paint(withAlpha(f.color, a)))
                c.drawPath(burst, paint(withAlpha(Color.BLACK, a), Paint.Style.STROKE, s * 0.008f))
                val tp = text(withAlpha(col("#D50000"), a), R * 0.5f, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC), Paint.Align.CENTER)
                c.drawText(f.caption.ifBlank { "WOW!" }, cx, cy + tp.textSize * 0.35f, tp)
            }
            FrameStyle.CYBER -> {
                val m = s * 0.045f; val L = s * 0.14f
                val glow = paint(withAlpha(f.color, a * 0.6f), Paint.Style.STROKE, s * 0.012f).apply { maskFilter = BlurMaskFilter(s * 0.01f, BlurMaskFilter.Blur.NORMAL) }
                val line = paint(withAlpha(f.color, a), Paint.Style.STROKE, s * 0.004f)
                val mag = paint(withAlpha(col("#FF00E5"), a), Paint.Style.STROKE, s * 0.004f)
                for (p in listOf(glow, line)) {
                    val path = Path().apply {
                        moveTo(m, m + L); lineTo(m, m + L * 0.25f); lineTo(m + L * 0.25f, m); lineTo(m + L, m)
                        moveTo(W - m - L, H - m); lineTo(W - m - L * 0.25f, H - m); lineTo(W - m, H - m - L * 0.25f); lineTo(W - m, H - m - L)
                    }
                    c.drawPath(path, p)
                }
                c.drawLine(W - m - L, m, W - m, m, mag); c.drawLine(W - m, m, W - m, m + L * 0.5f, mag)
                c.drawLine(m, H - m, m + L * 0.5f, H - m, mag); c.drawLine(m, H - m, m, H - m - L, mag)
                // сканлайны по краям
                val scan = paint(withAlpha(f.color, a * 0.12f))
                var y = 0f
                while (y < H) { c.drawRect(0f, y, s * 0.03f, y + s * 0.003f, scan); c.drawRect(W - s * 0.03f, y, W, y + s * 0.003f, scan); y += s * 0.012f }
                val tp = text(withAlpha(f.color, a), s * 0.03f, mono)
                c.drawText("SYS.REC ▮ 0x2A", m + L * 0.3f, m + s * 0.06f, tp)
                c.drawText("LAT 43.24 · LON 76.95", m + s * 0.02f, H - m - s * 0.03f, tp.apply { textSize = s * 0.022f })
                val tr = text(withAlpha(col("#FF00E5"), a), s * 0.026f, mono, Paint.Align.RIGHT)
                c.drawText("AI·TRACK ${today("HH:mm")}", W - m - s * 0.02f, m + L * 0.5f + s * 0.04f, tr)
            }
            FrameStyle.POSTCARD -> {
                band(c, w, h, t, t, t, t * 2.6f, paint(color))
                // марка
                val sw2 = s * 0.17f; val sh2 = s * 0.2f
                val sx = W - t - sw2 - s * 0.03f; val sy = t + s * 0.03f
                perforated(c, RectF(sx, sy, sx + sw2, sy + sh2), s * 0.008f, paint(withAlpha(Color.WHITE, a)))
                val inner = RectF(sx + s * 0.015f, sy + s * 0.015f, sx + sw2 - s * 0.015f, sy + sh2 - s * 0.015f)
                val ip = paint(0); ip.shader = LinearGradient(0f, inner.top, 0f, inner.bottom, intArrayOf(col("#4FC3F7"), col("#FFB74D")), null, Shader.TileMode.CLAMP); ip.alpha = (255 * a).toInt()
                c.drawRect(inner, ip)
                c.drawCircle(inner.centerX(), inner.centerY() + inner.height() * 0.05f, inner.width() * 0.18f, paint(withAlpha(col("#FFF59D"), a)))
                val hills = Path().apply { moveTo(inner.left, inner.bottom); lineTo(inner.left, inner.bottom - inner.height() * 0.25f); quadTo(inner.centerX(), inner.bottom - inner.height() * 0.5f, inner.right, inner.bottom - inner.height() * 0.2f); lineTo(inner.right, inner.bottom); close() }
                c.drawPath(hills, paint(withAlpha(col("#2E7D32"), a)))
                c.drawText("25", inner.right - s * 0.005f, inner.top + s * 0.03f, text(withAlpha(Color.WHITE, a), s * 0.025f, Typeface.DEFAULT_BOLD, Paint.Align.RIGHT))
                // штемпель
                val pm = paint(withAlpha(col("#37474F"), a * 0.7f), Paint.Style.STROKE, s * 0.003f)
                val pcx = sx - s * 0.02f; val pcy = sy + sh2 * 0.75f
                c.drawCircle(pcx, pcy, s * 0.07f, pm); c.drawCircle(pcx, pcy, s * 0.055f, pm)
                for (i in 0 until 4) { val yy = pcy - s * 0.03f + i * s * 0.02f; wave(c, pcx + s * 0.075f, yy, s * 0.16f, s * 0.006f, pm) }
                c.drawText(today("dd.MM.yy"), pcx, pcy + s * 0.008f, text(withAlpha(col("#37474F"), a * 0.8f), s * 0.022f, mono, Paint.Align.CENTER))
                if (f.caption.isNotBlank()) c.drawText(f.caption, W / 2, H - t * 0.85f, text(withAlpha(col("#5D4037"), a), t * 1.0f, script, Paint.Align.CENTER))
            }
            FrameStyle.TAPE -> {
                band(c, w, h, t, t, t, t, paint(color))
                if (f.shadow) innerShadow(c, RectF(t, t, W - t, H - t), 0f, a)
                val tapeW = s * 0.28f; val tapeH = s * 0.075f
                val colors = listOf(col("#F8BBD0"), col("#B2EBF2"), col("#FFF59D"), col("#C5E1A5"))
                val spots = listOf(Triple(t * 1.2f, t * 1.2f, -40f), Triple(W - t * 1.2f, t * 1.2f, 40f), Triple(W - t * 1.2f, H - t * 1.2f, -40f), Triple(t * 1.2f, H - t * 1.2f, 40f))
                spots.forEachIndexed { i, (x, y, ang) ->
                    c.save(); c.rotate(ang, x, y)
                    val r = RectF(x - tapeW / 2, y - tapeH / 2, x + tapeW / 2, y + tapeH / 2)
                    c.drawRect(r, paint(withAlpha(colors[i], a * 0.78f)))
                    val edge = Path()
                    var xx = r.left
                    edge.moveTo(r.left, r.top)
                    while (xx < r.right) { edge.lineTo(xx + tapeH * 0.1f, r.top - tapeH * 0.06f); xx += tapeH * 0.2f; edge.lineTo(xx, r.top) }
                    c.drawPath(edge, paint(withAlpha(colors[i], a * 0.78f)))
                    val stripe = paint(withAlpha(Color.WHITE, a * 0.25f), Paint.Style.STROKE, tapeH * 0.08f)
                    c.drawLine(r.left, r.centerY(), r.right, r.centerY(), stripe)
                    c.restore()
                }
            }
            FrameStyle.TORN -> {
                val rnd = kotlin.random.Random(11)
                val path = Path().apply { fillType = Path.FillType.EVEN_ODD; addRect(0f, 0f, W, H, Path.Direction.CW) }
                val inner = Path()
                val seg = s * 0.02f
                fun j() = t * (0.75f + rnd.nextFloat() * 0.6f)
                var x = 0f
                inner.moveTo(j(), j())
                while (x < W) { inner.lineTo(x, j()); x += seg * (0.5f + rnd.nextFloat()) }
                var y = 0f
                while (y < H) { inner.lineTo(W - j(), y); y += seg * (0.5f + rnd.nextFloat()) }
                x = W
                while (x > 0) { inner.lineTo(x, H - j()); x -= seg * (0.5f + rnd.nextFloat()) }
                y = H
                while (y > 0) { inner.lineTo(j(), y); y -= seg * (0.5f + rnd.nextFloat()) }
                inner.close()
                path.addPath(inner)
                val sh = paint(Color.argb((70 * a).toInt(), 0, 0, 0)).apply { maskFilter = BlurMaskFilter(s * 0.008f, BlurMaskFilter.Blur.NORMAL) }
                c.save(); c.translate(s * 0.003f, s * 0.004f); c.drawPath(path, sh); c.restore()
                c.drawPath(path, paint(color))
                // волокна бумаги
                c.drawPath(inner, paint(withAlpha(col("#E0DCD3"), a), Paint.Style.STROKE, s * 0.003f))
            }
            FrameStyle.SCALLOP -> {
                val r = t * 0.55f
                val inner = RectF(t + r, t + r, W - t - r, H - t - r)
                val win = Path().apply { addRect(inner, Path.Direction.CW) }
                // полукруглые «зубчики» внутрь окна
                var x = inner.left
                while (x <= inner.right) { win.addCircle(x, inner.top, r, Path.Direction.CW); win.addCircle(x, inner.bottom, r, Path.Direction.CW); x += r * 2 }
                var y = inner.top
                while (y <= inner.bottom) { win.addCircle(inner.left, y, r, Path.Direction.CW); win.addCircle(inner.right, y, r, Path.Direction.CW); y += r * 2 }
                // окно = объединение; рисуем фон, затем «вырезаем» окно через слой
                val save = c.saveLayer(0f, 0f, W, H, null)
                c.drawRect(0f, 0f, W, H, paint(color))
                val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) }
                c.drawPath(win, clear)
                c.restoreToCount(save)
                val dots = paint(withAlpha(Color.WHITE, a * 0.7f))
                var dx = t * 0.5f
                while (dx < W) { c.drawCircle(dx, t * 0.45f, s * 0.004f, dots); c.drawCircle(dx, H - t * 0.45f, s * 0.004f, dots); dx += s * 0.03f }
            }
            FrameStyle.FLORAL -> {
                val rnd = kotlin.random.Random(5)
                val petals = listOf(f.color, col("#FFFFFF"), col("#FFCC80"), col("#CE93D8"), col("#F06292"))
                fun cluster(cx: Float, cy: Float, n: Int, spread: Float) {
                    repeat(n) {
                        val ang = rnd.nextFloat() * 6.28f; val d = rnd.nextFloat() * spread
                        leaf(c, cx + cos(ang) * d * 1.1f, cy + sin(ang) * d * 1.1f, s * 0.05f, rnd.nextFloat() * 360f, paint(withAlpha(col(if (rnd.nextBoolean()) "#66BB6A" else "#43A047"), a)))
                    }
                    repeat(n) {
                        val ang = rnd.nextFloat() * 6.28f; val d = rnd.nextFloat() * spread
                        flower(c, cx + cos(ang) * d, cy + sin(ang) * d, s * (0.025f + rnd.nextFloat() * 0.025f), withAlpha(petals[rnd.nextInt(petals.size)], a), a)
                    }
                }
                cluster(0f, 0f, 9, s * 0.2f)
                cluster(W, H, 9, s * 0.2f)
                cluster(W, 0f, 3, s * 0.08f)
                cluster(0f, H, 3, s * 0.08f)
            }
            FrameStyle.HEARTS -> {
                band(c, w, h, t, t, t, t, paint(color), max(g.r, s * 0.03f))
                val hp = paint(withAlpha(Color.WHITE, a * 0.85f))
                val n = 9
                for (i in 0 until n) {
                    val k = (i + 0.5f) / n
                    heart(c, W * k, t / 2, t * 0.28f, hp); heart(c, W * k, H - t / 2, t * 0.28f, hp)
                }
                val m = ((H - 2 * t) / (W / n)).toInt()
                for (i in 0 until m) { val y = t + (i + 0.5f) * (H - 2 * t) / m; heart(c, t / 2, y, t * 0.28f, hp); heart(c, W - t / 2, y, t * 0.28f, hp) }
                heart(c, W - t * 2.6f, H - t * 2.6f, s * 0.07f, paint(withAlpha(f.color, a)))
            }
            FrameStyle.STARS -> {
                val rnd = kotlin.random.Random(9)
                val sp = paint(withAlpha(f.color, a))
                val gl = paint(withAlpha(f.color, a * 0.5f)).apply { maskFilter = BlurMaskFilter(s * 0.015f, BlurMaskFilter.Blur.NORMAL) }
                repeat(46) {
                    // ближе к краям
                    val edge = rnd.nextInt(4)
                    val d = rnd.nextFloat().let { it * it } * s * 0.16f
                    val (x, y) = when (edge) { 0 -> rnd.nextFloat() * W to d; 1 -> rnd.nextFloat() * W to H - d; 2 -> d to rnd.nextFloat() * H; else -> W - d to rnd.nextFloat() * H }
                    val size = s * (0.008f + rnd.nextFloat() * 0.025f)
                    sparkle(c, x, y, size * 1.3f, gl); sparkle(c, x, y, size, sp)
                }
            }
            FrameStyle.NEON -> {
                val m = t * 1.2f
                val r = max(g.r, s * 0.04f)
                val rect = RectF(m, m, W - m, H - m)
                val second = col("#00E5FF")
                for ((cc, inset) in listOf(f.color to 0f, second to s * 0.022f)) {
                    val rr = RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
                    c.drawRoundRect(rr, r, r, paint(withAlpha(cc, a * 0.55f), Paint.Style.STROKE, s * 0.03f).apply { maskFilter = BlurMaskFilter(s * 0.025f, BlurMaskFilter.Blur.NORMAL) })
                    c.drawRoundRect(rr, r, r, paint(withAlpha(cc, a), Paint.Style.STROKE, s * 0.008f))
                    c.drawRoundRect(rr, r, r, paint(withAlpha(Color.WHITE, a * 0.8f), Paint.Style.STROKE, s * 0.0025f))
                }
            }
            FrameStyle.GRADIENT -> {
                val p = paint(0)
                p.shader = LinearGradient(0f, 0f, W, H, intArrayOf(f.color, col("#8E24AA"), col("#FF9800")), null, Shader.TileMode.CLAMP)
                p.alpha = (255 * a).toInt()
                band(c, w, h, t, t, t, t, p, max(g.r, s * 0.03f))
                innerShadow(c, RectF(t, t, W - t, H - t), max(g.r, s * 0.03f), a)
            }
            FrameStyle.BIRTHDAY -> {
                // гирлянда флажков
                val rnd = kotlin.random.Random(2)
                val flagCols = listOf(col("#FF2E63"), col("#FFB300"), col("#29B6F6"), col("#66BB6A"), col("#AB47BC"))
                val sag = s * 0.07f; val top = s * 0.03f
                val n = 9; val fw = W / n
                val rope = paint(withAlpha(Color.WHITE, a), Paint.Style.STROKE, s * 0.003f)
                val ropePath = Path().apply { moveTo(0f, top); quadTo(W / 2, top + sag * 2, W, top) }
                c.drawPath(ropePath, rope)
                for (i in 0 until n) {
                    val x0 = i * fw + fw * 0.08f; val x1 = (i + 1) * fw - fw * 0.08f
                    fun yAt(x: Float): Float { val u = x / W; return top + sag * 2 * 2 * u * (1 - u) }
                    val pth = Path().apply { moveTo(x0, yAt(x0)); lineTo(x1, yAt(x1)); lineTo((x0 + x1) / 2, (yAt(x0) + yAt(x1)) / 2 + fw * 0.95f); close() }
                    c.drawPath(pth, paint(withAlpha(flagCols[i % flagCols.size], a)))
                }
                repeat(60) {
                    val x = rnd.nextFloat() * W
                    val y = if (rnd.nextBoolean()) rnd.nextFloat() * s * 0.2f else H - rnd.nextFloat() * s * 0.16f
                    c.save(); c.rotate(rnd.nextFloat() * 180f, x, y)
                    c.drawRect(x, y, x + s * 0.012f, y + s * 0.006f, paint(withAlpha(flagCols[rnd.nextInt(flagCols.size)], a)))
                    c.restore()
                }
                if (f.caption.isNotBlank()) {
                    val tp = text(withAlpha(Color.WHITE, a), s * 0.085f, script, Paint.Align.CENTER)
                    tp.setShadowLayer(s * 0.012f, 0f, s * 0.004f, withAlpha(f.color, a))
                    c.drawText(f.caption, W / 2, H - s * 0.08f, tp)
                }
            }
            FrameStyle.NEW_YEAR -> {
                val bulbs = listOf(col("#FF1744"), col("#FFEA00"), col("#00E676"), col("#2979FF"), col("#FF9100"))
                val wire = paint(withAlpha(col("#1B5E20"), a), Paint.Style.STROKE, s * 0.004f)
                val sag = s * 0.05f; val n = 12; val seg = W / 3
                for (k in 0 until 3) {
                    val x0 = k * seg
                    c.drawPath(Path().apply { moveTo(x0, s * 0.02f); quadTo(x0 + seg / 2, s * 0.02f + sag * 2, x0 + seg, s * 0.02f) }, wire)
                }
                for (i in 0 until n) {
                    val x = (i + 0.5f) * W / n
                    val u = (x % seg) / seg
                    val y = s * 0.02f + sag * 4 * u * (1 - u) + s * 0.02f
                    val cc = bulbs[i % bulbs.size]
                    c.drawCircle(x, y, s * 0.03f, paint(withAlpha(cc, a * 0.45f)).apply { maskFilter = BlurMaskFilter(s * 0.025f, BlurMaskFilter.Blur.NORMAL) })
                    c.drawOval(RectF(x - s * 0.011f, y - s * 0.016f, x + s * 0.011f, y + s * 0.016f), paint(withAlpha(cc, a)))
                    c.drawCircle(x - s * 0.004f, y - s * 0.006f, s * 0.004f, paint(withAlpha(Color.WHITE, a * 0.8f)))
                }
                snow(c, W, H, s, a, 70, kotlin.random.Random(4))
                if (f.caption.isNotBlank()) {
                    val tp = text(withAlpha(Color.WHITE, a), s * 0.08f, script, Paint.Align.CENTER)
                    tp.setShadowLayer(s * 0.012f, 0f, s * 0.004f, withAlpha(f.color, a))
                    c.drawText(f.caption, W / 2, H - s * 0.08f, tp)
                }
            }
            FrameStyle.WINTER -> {
                val p = paint(0)
                p.shader = LinearGradient(0f, 0f, 0f, s * 0.25f, intArrayOf(withAlpha(f.color, a * 0.85f), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, W, s * 0.25f, p)
                val rnd = kotlin.random.Random(8)
                val fl = paint(withAlpha(Color.WHITE, a), Paint.Style.STROKE, s * 0.004f).apply { strokeCap = Paint.Cap.ROUND }
                repeat(16) {
                    val edge = rnd.nextInt(4)
                    val d = rnd.nextFloat() * s * 0.12f + s * 0.03f
                    val (x, y) = when (edge) { 0 -> rnd.nextFloat() * W to d; 1 -> rnd.nextFloat() * W to H - d; 2 -> d to rnd.nextFloat() * H; else -> W - d to rnd.nextFloat() * H }
                    snowflake(c, x, y, s * (0.02f + rnd.nextFloat() * 0.03f), fl)
                }
                snow(c, W, H, s, a, 50, rnd)
                // «иней» снизу
                val ice = Path().apply {
                    moveTo(0f, H); var x = 0f
                    while (x <= W) { lineTo(x, H - s * (0.02f + rnd.nextFloat() * 0.03f)); x += s * 0.03f }
                    lineTo(W, H); close()
                }
                c.drawPath(ice, paint(withAlpha(Color.WHITE, a * 0.9f)))
            }
            FrameStyle.SUMMER -> {
                // солнце
                val cx = W - s * 0.12f; val cy = s * 0.12f
                c.drawCircle(cx, cy, s * 0.13f, paint(withAlpha(f.color, a * 0.35f)).apply { maskFilter = BlurMaskFilter(s * 0.05f, BlurMaskFilter.Blur.NORMAL) })
                val ray = paint(withAlpha(f.color, a), Paint.Style.STROKE, s * 0.008f).apply { strokeCap = Paint.Cap.ROUND }
                for (i in 0 until 12) { val ang = i * PI.toFloat() / 6; c.drawLine(cx + cos(ang) * s * 0.07f, cy + sin(ang) * s * 0.07f, cx + cos(ang) * s * 0.1f, cy + sin(ang) * s * 0.1f, ray) }
                c.drawCircle(cx, cy, s * 0.055f, paint(withAlpha(f.color, a)))
                // волны
                val waveCols = listOf(col("#4FC3F7"), col("#29B6F6"), col("#0288D1"))
                waveCols.forEachIndexed { i, wc ->
                    val base = H - s * (0.1f - i * 0.03f)
                    val pth = Path().apply {
                        moveTo(0f, H); lineTo(0f, base)
                        var x = 0f; val wl = s * 0.16f
                        while (x < W) { quadTo(x + wl / 4, base - s * 0.025f, x + wl / 2, base); quadTo(x + wl * 3 / 4, base + s * 0.025f, x + wl, base); x += wl }
                        lineTo(W, H); close()
                    }
                    c.drawPath(pth, paint(withAlpha(wc, a * 0.92f)))
                }
                if (f.caption.isNotBlank()) {
                    val tp = text(withAlpha(Color.WHITE, a), s * 0.075f, script, Paint.Align.LEFT)
                    tp.setShadowLayer(s * 0.01f, 0f, s * 0.004f, Color.argb((120 * a).toInt(), 0, 0, 0))
                    c.drawText(f.caption, s * 0.06f, s * 0.12f, tp)
                }
            }
            FrameStyle.TRAVEL -> {
                band(c, w, h, t * 0.6f, t * 0.6f, t * 0.6f, t * 0.6f, paint(color))
                val ink = withAlpha(Color.WHITE, a * 0.92f)
                val st = paint(ink, Paint.Style.STROKE, s * 0.005f).apply { setShadowLayer(s * 0.006f, 0f, 0f, Color.argb((150 * a).toInt(), 0, 0, 0)) }
                // штамп-кольцо
                val cx = s * 0.2f; val cy = H - s * 0.22f
                c.drawCircle(cx, cy, s * 0.11f, st); c.drawCircle(cx, cy, s * 0.085f, st.apply { strokeWidth = s * 0.0025f })
                val shadowed = { p: Paint -> p.apply { setShadowLayer(s * 0.006f, 0f, 0f, Color.argb((150 * a).toInt(), 0, 0, 0)) } }
                c.drawText(f.caption.ifBlank { "TRAVEL" }, cx, cy - s * 0.005f, shadowed(text(ink, s * 0.035f, Typeface.DEFAULT_BOLD, Paint.Align.CENTER)))
                c.drawText(today("dd MMM yyyy").uppercase(), cx, cy + s * 0.035f, shadowed(text(ink, s * 0.02f, mono, Paint.Align.CENTER)))
                // пунктир маршрута с самолётиком
                val dash = paint(withAlpha(Color.WHITE, a), Paint.Style.STROKE, s * 0.005f).apply { pathEffect = DashPathEffect(floatArrayOf(s * 0.02f, s * 0.015f), 0f) }
                dash.setShadowLayer(s * 0.004f, 0f, 0f, Color.argb((120 * a).toInt(), 0, 0, 0))
                val route = Path().apply { moveTo(s * 0.12f, s * 0.13f); cubicTo(W * 0.4f, s * 0.03f, W * 0.6f, s * 0.15f, W - s * 0.18f, s * 0.1f) }
                c.drawPath(route, dash)
                c.save(); c.rotate(-30f, W - s * 0.16f, s * 0.11f)
                planeSolid(c, W - s * 0.16f, s * 0.11f, s * 0.05f, paint(withAlpha(Color.WHITE, a)))
                c.restore()
                // пин
                val px = s * 0.12f; val py = s * 0.13f
                c.drawCircle(px, py - s * 0.025f, s * 0.022f, paint(withAlpha(col("#FF2E63"), a)))
                c.drawPath(Path().apply { moveTo(px - s * 0.019f, py - s * 0.018f); lineTo(px, py + s * 0.012f); lineTo(px + s * 0.019f, py - s * 0.018f); close() }, paint(withAlpha(col("#FF2E63"), a)))
                c.drawCircle(px, py - s * 0.025f, s * 0.008f, paint(withAlpha(Color.WHITE, a)))
            }
            FrameStyle.AURA -> {
                val m = s * 0.045f
                val glow = paint(withAlpha(f.color, a * 0.55f), Paint.Style.STROKE, s * 0.012f)
                glow.maskFilter = BlurMaskFilter(s * 0.018f, BlurMaskFilter.Blur.NORMAL)
                c.drawRoundRect(RectF(m, m, W - m, H - m), s * 0.035f, s * 0.035f, glow)
                c.drawRoundRect(RectF(m, m, W - m, H - m), s * 0.035f, s * 0.035f, paint(color, Paint.Style.STROKE, s * 0.003f))
                listOf(m to m, W - m to m, m to H - m, W - m to H - m).forEach { (x, y) -> sparkle(c, x, y, s * 0.018f, paint(withAlpha(Color.WHITE, a))) }
            }
            FrameStyle.BOTANICAL -> {
                val ink = paint(withAlpha(f.color, a), Paint.Style.STROKE, s * 0.0035f)
                val edge = s * 0.075f
                for (side in 0..1) {
                    val x = if (side == 0) edge * 0.35f else W - edge * 0.35f
                    for (i in 0..5) {
                        val y = H * (0.12f + i * 0.15f)
                        val direction = if (side == 0) 1f else -1f
                        leaf(c, x, y, s * 0.025f, direction * (if (i % 2 == 0) -35f else 35f), paint(withAlpha(f.color, a * 0.85f)))
                    }
                }
                c.drawRoundRect(RectF(edge, edge, W - edge, H - edge), s * 0.02f, s * 0.02f, ink)
            }
            FrameStyle.LUXE_CORNERS -> {
                val m = s * 0.055f; val len = s * 0.11f
                val gp = paint(color, Paint.Style.STROKE, s * 0.006f)
                gp.shader = LinearGradient(0f, 0f, W, H, intArrayOf(col("#8C6B1F"), col("#FFF0A8"), col("#B8892B")), null, Shader.TileMode.CLAMP)
                val corners = listOf((m to m) to (1f to 1f), (W-m to m) to (-1f to 1f), (m to H-m) to (1f to -1f), (W-m to H-m) to (-1f to -1f))
                for ((point, dir) in corners) {
                    val (x, y) = point; val (dx, dy) = dir
                    c.drawLine(x, y, x + dx * len, y, gp); c.drawLine(x, y, x, y + dy * len, gp)
                    c.drawCircle(x, y, s * 0.008f, paint(withAlpha(col("#F7E7A1"), a)))
                }
            }
            FrameStyle.CLEAN_STORY -> {
                val m = s * 0.045f
                c.drawRoundRect(RectF(m, m, W-m, H-m), s * 0.018f, s * 0.018f, paint(color, Paint.Style.STROKE, s * 0.003f))
                if (f.caption.isNotBlank()) {
                    val tp = text(withAlpha(f.color, a * 0.9f), s * 0.035f, Typeface.create("sans-serif-medium", Typeface.NORMAL), Paint.Align.CENTER)
                    c.drawText(f.caption, W/2f, H - m * 0.45f, tp)
                }
                sparkle(c, W - m * 1.25f, m * 1.25f, s * 0.014f, paint(withAlpha(f.color, a)))
            }
            FrameStyle.GOLD -> {
                val gp = paint(0)
                gp.shader = LinearGradient(0f, 0f, W, H, intArrayOf(col("#8C6B1F"), col("#F7E7A1"), col("#B8892B"), col("#FFF2B2"), col("#8C6B1F")), null, Shader.TileMode.CLAMP)
                gp.alpha = (255 * a).toInt()
                band(c, w, h, t, t, t, t, gp)
                val dark = paint(withAlpha(col("#5D4512"), a), Paint.Style.STROKE, s * 0.003f)
                c.drawRect(RectF(t * 0.3f, t * 0.3f, W - t * 0.3f, H - t * 0.3f), dark)
                c.drawRect(RectF(t, t, W - t, H - t), dark)
                innerShadow(c, RectF(t, t, W - t, H - t), 0f, a)
                listOf(t * 0.65f to t * 0.65f, W - t * 0.65f to t * 0.65f, t * 0.65f to H - t * 0.65f, W - t * 0.65f to H - t * 0.65f).forEach { (x, y) ->
                    ornament(c, x, y, t * 0.45f, paint(withAlpha(col("#5D4512"), a)))
                }
            }
            FrameStyle.IMAGE -> {
                val bmp = f.image?.let { imageLoader?.invoke(it) }
                if (bmp != null) {
                    val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = (255 * a).toInt() }
                    if (f.imageMode == "corner") {
                        val bw = s * 0.2f; val bh = bw * bmp.height / bmp.width
                        c.drawBitmap(bmp, null, RectF(s * 0.05f, H - s * 0.05f - bh, s * 0.05f + bw, H - s * 0.05f), p)
                    } else {
                        val m = s * 0.025f
                        c.drawBitmap(bmp, null, RectF(m, m, W - m, H - m), p)
                    }
                }
            }
            FrameStyle.BLACK_GOLD -> {
                band(c, w, h, t, t, t, t * 2.2f, paint(withAlpha(col("#0B0B0B"), a)))
                val gold = paint(0, Paint.Style.STROKE, s * 0.004f)
                gold.shader = LinearGradient(0f, 0f, W, 0f, intArrayOf(col("#B8892B"), col("#F7E7A1"), col("#B8892B")), null, Shader.TileMode.CLAMP)
                gold.alpha = (255 * a).toInt()
                c.drawRect(RectF(t * 0.45f, t * 0.45f, W - t * 0.45f, H - t * 1.7f), gold)
                if (f.caption.isNotBlank()) {
                    val tp = text(0, t * 0.75f, serif, Paint.Align.CENTER)
                    tp.shader = gold.shader; tp.alpha = (255 * a).toInt(); tp.letterSpacing = 0.5f
                    c.drawText(f.caption, W / 2, H - t * 0.8f, tp)
                }
                ornament(c, W / 2, t * 0.45f, t * 0.3f, paint(withAlpha(f.color, a)))
            }
        }
    }

    // ------------------------------------------------------------------ размытый фон (только фото)

    private fun blurFill(src: Bitmap, f: FrameSpec): Bitmap {
        val w = src.width; val h = src.height
        val g = geo(w, h, f)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        // фон: сильно уменьшенный и растянутый снимок + затемнение
        val tiny = Bitmap.createScaledBitmap(src, max(4, w / 40), max(4, h / 40), true)
        val bg = Bitmap.createScaledBitmap(tiny, max(8, w / 8), max(8, h / 8), true)
        c.drawBitmap(bg, null, RectF(-w * 0.05f, -h * 0.05f, w * 1.05f, h * 1.05f), Paint(Paint.FILTER_BITMAP_FLAG))
        c.drawColor(Color.argb(40, 0, 0, 0))
        val m = g.t * 1.6f
        val scale = min((w - 2 * m) / w, (h - 2 * m) / h)
        val dw = w * scale; val dh = h * scale
        val dst = RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2)
        val r = max(g.r, g.s * 0.03f)
        if (f.shadow) {
            val sp = paint(Color.argb(120, 0, 0, 0)).apply { maskFilter = BlurMaskFilter(g.s * 0.03f, BlurMaskFilter.Blur.NORMAL) }
            c.drawRoundRect(RectF(dst.left, dst.top + g.s * 0.012f, dst.right, dst.bottom + g.s * 0.012f), r, r, sp)
        }
        val save = c.saveLayer(dst, null)
        c.drawRoundRect(dst, r, r, paint(Color.WHITE))
        c.drawBitmap(src, null, dst, Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN) })
        c.restoreToCount(save)
        return out
    }

    // ------------------------------------------------------------------ примитивы

    private fun today(pattern: String = "dd.MM.yyyy"): String =
        runCatching { java.text.SimpleDateFormat(pattern, java.util.Locale("ru")).format(java.util.Date()) }.getOrDefault("")

    private fun vignette(c: Canvas, W: Float, H: Float, strength: Float) {
        val r = kotlin.math.sqrt(W * W + H * H) / 2f
        val p = Paint().apply {
            shader = RadialGradient(W / 2, H / 2, r, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb((strength * 230).toInt().coerceIn(0, 255), 20, 10, 0)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, W, H, p)
    }

    private fun heart(c: Canvas, x: Float, y: Float, s: Float, p: Paint) {
        val path = Path().apply {
            moveTo(x, y + s * 0.9f)
            cubicTo(x - s * 1.6f, y - s * 0.1f, x - s * 0.7f, y - s * 1.2f, x, y - s * 0.35f)
            cubicTo(x + s * 0.7f, y - s * 1.2f, x + s * 1.6f, y - s * 0.1f, x, y + s * 0.9f)
            close()
        }
        c.drawPath(path, p)
    }

    private fun bubble(c: Canvas, x: Float, y: Float, s: Float, p: Paint) {
        c.drawCircle(x, y, s * 0.85f, p)
        c.drawLine(x - s * 0.55f, y + s * 0.65f, x - s * 0.95f, y + s * 1.0f, p)
    }

    private fun plane(c: Canvas, x: Float, y: Float, s: Float, p: Paint) {
        val path = Path().apply { moveTo(x - s, y - s * 0.2f); lineTo(x + s, y - s * 0.9f); lineTo(x + s * 0.2f, y + s * 0.9f); lineTo(x - s * 0.1f, y + s * 0.1f); close(); moveTo(x - s * 0.1f, y + s * 0.1f); lineTo(x + s, y - s * 0.9f) }
        c.drawPath(path, p)
    }

    private fun planeSolid(c: Canvas, x: Float, y: Float, s: Float, p: Paint) {
        val path = Path().apply {
            moveTo(x + s, y); lineTo(x - s * 0.2f, y - s * 0.15f); lineTo(x - s * 0.5f, y - s * 0.8f); lineTo(x - s * 0.7f, y - s * 0.8f)
            lineTo(x - s * 0.45f, y - s * 0.15f); lineTo(x - s * 0.85f, y - s * 0.12f); lineTo(x - s, y - s * 0.4f); lineTo(x - s * 1.1f, y - s * 0.4f)
            lineTo(x - s * 1.0f, y); lineTo(x - s * 1.1f, y + s * 0.4f); lineTo(x - s, y + s * 0.4f); lineTo(x - s * 0.85f, y + s * 0.12f)
            lineTo(x - s * 0.45f, y + s * 0.15f); lineTo(x - s * 0.7f, y + s * 0.8f); lineTo(x - s * 0.5f, y + s * 0.8f); lineTo(x - s * 0.2f, y + s * 0.15f); close()
        }
        c.drawPath(path, p)
    }

    private fun bookmark(c: Canvas, x: Float, y: Float, s: Float, p: Paint) {
        val path = Path().apply { moveTo(x - s * 0.6f, y - s); lineTo(x + s * 0.6f, y - s); lineTo(x + s * 0.6f, y + s); lineTo(x, y + s * 0.45f); lineTo(x - s * 0.6f, y + s); close() }
        c.drawPath(path, p)
    }

    private fun sparkle(c: Canvas, x: Float, y: Float, s: Float, p: Paint) {
        val path = Path().apply {
            moveTo(x, y - s); quadTo(x, y, x + s, y); quadTo(x, y, x, y + s); quadTo(x, y, x - s, y); quadTo(x, y, x, y - s); close()
        }
        c.drawPath(path, p)
    }

    private fun flower(c: Canvas, x: Float, y: Float, r: Float, color: Int, a: Float) {
        val p = paint(color)
        for (i in 0 until 5) {
            val ang = i * 2f * PI.toFloat() / 5
            c.drawCircle(x + cos(ang) * r * 0.6f, y + sin(ang) * r * 0.6f, r * 0.5f, p)
        }
        c.drawCircle(x, y, r * 0.32f, paint(withAlpha(Color.parseColor("#FFD54F"), a)))
        c.drawCircle(x, y, r * 0.32f, paint(withAlpha(Color.parseColor("#F9A825"), a), Paint.Style.STROKE, r * 0.06f))
    }

    private fun leaf(c: Canvas, x: Float, y: Float, s: Float, ang: Float, p: Paint) {
        c.save(); c.rotate(ang, x, y)
        val path = Path().apply { moveTo(x, y); quadTo(x + s * 0.5f, y - s * 0.35f, x + s, y); quadTo(x + s * 0.5f, y + s * 0.35f, x, y); close() }
        c.drawPath(path, p)
        c.restore()
    }

    private fun snowflake(c: Canvas, x: Float, y: Float, r: Float, p: Paint) {
        for (i in 0 until 6) {
            val ang = i * PI.toFloat() / 3
            val ex = x + cos(ang) * r; val ey = y + sin(ang) * r
            c.drawLine(x, y, ex, ey, p)
            val mx = x + cos(ang) * r * 0.6f; val my = y + sin(ang) * r * 0.6f
            for (d in listOf(-0.6f, 0.6f)) c.drawLine(mx, my, mx + cos(ang + d) * r * 0.3f, my + sin(ang + d) * r * 0.3f, p)
        }
    }

    private fun snow(c: Canvas, W: Float, H: Float, s: Float, a: Float, n: Int, rnd: kotlin.random.Random) {
        val p = paint(Color.argb((200 * a).toInt(), 255, 255, 255))
        // декор только на полях: снежинки в полосе у краёв, не на лицах
        repeat(n) {
            val d = rnd.nextFloat() * s * 0.14f
            val (x, y) = when (rnd.nextInt(4)) { 0 -> rnd.nextFloat() * W to d; 1 -> rnd.nextFloat() * W to H - d; 2 -> d to rnd.nextFloat() * H; else -> W - d to rnd.nextFloat() * H }
            c.drawCircle(x, y, s * (0.002f + rnd.nextFloat() * 0.005f), p)
        }
    }

    private fun ornament(c: Canvas, x: Float, y: Float, s: Float, p: Paint) {
        val path = Path().apply { moveTo(x, y - s); lineTo(x + s * 0.45f, y); lineTo(x, y + s); lineTo(x - s * 0.45f, y); close() }
        c.drawPath(path, p)
        c.drawCircle(x - s * 0.8f, y, s * 0.18f, p); c.drawCircle(x + s * 0.8f, y, s * 0.18f, p)
    }

    private fun wave(c: Canvas, x: Float, y: Float, len: Float, amp: Float, p: Paint) {
        val path = Path().apply {
            moveTo(x, y); var xx = x; val step = len / 6
            var up = true
            while (xx < x + len) { quadTo(xx + step / 2, if (up) y - amp else y + amp, xx + step, y); xx += step; up = !up }
        }
        c.drawPath(path, p)
    }

    /** Почтовая марка с перфорацией. */
    private fun perforated(c: Canvas, r: RectF, hole: Float, p: Paint) {
        val save = c.saveLayer(r.left - hole, r.top - hole, r.right + hole, r.bottom + hole, null)
        c.drawRect(r, p)
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) }
        var x = r.left
        while (x <= r.right) { c.drawCircle(x, r.top, hole, clear); c.drawCircle(x, r.bottom, hole, clear); x += hole * 2.6f }
        var y = r.top
        while (y <= r.bottom) { c.drawCircle(r.left, y, hole, clear); c.drawCircle(r.right, y, hole, clear); y += hole * 2.6f }
        c.restoreToCount(save)
    }

    private fun isLight(c: Int): Boolean {
        val l = 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
        return l > 150f
    }
}
