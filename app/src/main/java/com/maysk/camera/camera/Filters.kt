package com.maysk.camera.camera

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import kotlin.math.max
import kotlin.math.pow

/** Категории фильтров как в дизайне. */
enum class FilterCategory(val title: String) {
    CINEMA("Cinema"), HOLLYWOOD("Hollywood"), PORTRAIT("Портрет"), WEDDING("Wedding"), TRAVEL("Travel"),
    FOOD("Еда"), NATURE("Природа"), STREET("Street"), LUXURY("Luxury"), VINTAGE("Винтаж"),
    BW("Ч/Б"), CYBERPUNK("Cyberpunk"), ANIME("Anime"), SOCIAL("Соцсети"), NIGHT("Ночь"), STYLE("Стиль")
}

/**
 * Тональная кривая фильтра: S-кривая контраста, гамма, подъём чёрного / срез белого
 * и раздельное тонирование теней и светов по каналам R,G,B (split toning).
 */
data class Tone(
    val s: Float = 0f, val gamma: Float = 1f, val lift: Float = 0f, val crush: Float = 0f,
    val shR: Float = 0f, val shG: Float = 0f, val shB: Float = 0f,
    val hiR: Float = 0f, val hiG: Float = 0f, val hiB: Float = 0f,
) {
    val isIdentity get() = this == NONE
    companion object { val NONE = Tone() }
}

/**
 * Фильтр — ручной рецепт. Каждый приём считается одинаково в GPU-шейдере (превью и видео)
 * и на CPU (фото), поэтому то, что видно до затвора, попадает в файл.
 *  - [matrix] — цветовая матрица 4x5 (насыщенность, баланс, ч/б-смесь каналов);
 *  - [tone] — тональная кривая и split toning (LUT 256×3);
 *  - [grain] — плёночное зерно; [leak] — тёплая засветка края;
 *  - [skinTone] — кожа теплее, фон чуть холоднее (портрет);
 *  - [warmSat] — насыщенность только тёплых цветов (еда);
 *  - [vignette], [fade].
 */
data class Filter(
    val id: String,
    val name: String,
    val category: FilterCategory?,
    val matrix: FloatArray,
    val vignette: Float = 0f,
    val fade: Float = 0f,
    val tone: Tone = Tone.NONE,
    val grain: Float = 0f,
    val leak: Float = 0f,
    val skinTone: Float = 0f,
    val warmSat: Float = 0f,
) {
    override fun equals(other: Any?) = other is Filter && other.id == id
    override fun hashCode() = id.hashCode()
}

object Filters {
    private fun cm(block: ColorMatrix.() -> Unit): FloatArray = ColorMatrix().apply(block).array.copyOf()

    private fun ColorMatrix.sat(s: Float) = postConcat(ColorMatrix().apply { setSaturation(s) })
    private fun ColorMatrix.contrast(c: Float) {
        val t = (1f - c) * 128f
        postConcat(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
    }
    private fun ColorMatrix.bright(b: Float) =
        postConcat(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, b, 0f, 1f, 0f, 0f, b, 0f, 0f, 1f, 0f, b, 0f, 0f, 0f, 1f, 0f)))
    private fun ColorMatrix.scale(r: Float, g: Float, b: Float) =
        postConcat(ColorMatrix().apply { setScale(r, g, b, 1f) })
    /** Ч/Б со своей смесью каналов (как цветные светофильтры на плёнке). */
    private fun ColorMatrix.mono(r: Float, g: Float, b: Float) {
        val s = r + g + b
        val a = r / s; val c = g / s; val d = b / s
        postConcat(ColorMatrix(floatArrayOf(a, c, d, 0f, 0f, a, c, d, 0f, 0f, a, c, d, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
    }

    private fun T(s: Float = 0f, g: Float = 1f, lift: Float = 0f, crush: Float = 0f,
                  sh: Triple<Float, Float, Float> = Z, hi: Triple<Float, Float, Float> = Z) =
        Tone(s, g, lift, crush, sh.first, sh.second, sh.third, hi.first, hi.second, hi.third)
    private val Z = Triple(0f, 0f, 0f)
    private fun c(r: Float, g: Float, b: Float) = Triple(r, g, b)

    val ORIGINAL = Filter("original", "Оригинал", null, cm { })

    /** 35 ручных фильтров + «Оригинал». Каждый — свой приём, без генератора вариаций. */
    val all: List<Filter> = listOf(
        ORIGINAL,
        // ---------- Кино ----------
        Filter("cinema", "Кино", FilterCategory.CINEMA, cm { sat(1.05f) }, vignette = 0.3f,
            tone = T(0.45f, sh = c(-0.08f, 0.03f, 0.13f), hi = c(0.1f, 0.03f, -0.1f))),           // тени бирюза, света оранжевые
        Filter("hollywood", "Блокбастер", FilterCategory.CINEMA, cm { sat(1.12f) }, vignette = 0.38f,
            tone = T(0.95f, crush = 0.02f, sh = c(-0.05f, 0.05f, 0.1f), hi = c(0.07f, 0.03f, -0.05f))),
        Filter("bleach", "Bleach bypass", FilterCategory.CINEMA, cm { sat(0.42f) }, vignette = 0.25f,
            tone = T(1.25f, g = 1.05f, crush = 0.02f, sh = c(0f, 0.01f, 0.02f))),                   // серебро поверх цвета
        Filter("warm_film", "Тёплая плёнка", FilterCategory.CINEMA, cm { sat(0.9f) }, grain = 0.35f,
            tone = T(0.2f, lift = 0.1f, crush = 0.04f, sh = c(0.03f, 0.02f, -0.02f), hi = c(0.07f, 0.03f, -0.08f))),
        Filter("cold_film", "Холодная плёнка", FilterCategory.CINEMA, cm { sat(0.78f) }, grain = 0.3f,
            tone = T(0.3f, lift = 0.06f, sh = c(-0.04f, 0.02f, 0.1f), hi = c(-0.02f, 0.01f, 0.04f))),
        // ---------- Винтаж ----------
        Filter("polaroid", "Полароид", FilterCategory.VINTAGE, cm { sat(0.8f); scale(1.02f, 1.03f, 0.96f) }, fade = 0.15f, vignette = 0.2f,
            tone = T(-0.15f, lift = 0.14f, crush = 0.08f, sh = c(-0.02f, 0.05f, 0.06f), hi = c(0.05f, 0.03f, -0.04f))),
        Filter("sepia", "Сепия", FilterCategory.VINTAGE, cm { mono(0.3f, 0.59f, 0.11f) }, vignette = 0.3f, grain = 0.2f,
            tone = T(0.3f, lift = 0.05f, sh = c(0.09f, 0.03f, -0.05f), hi = c(0.09f, 0.04f, -0.08f))),
        Filter("seventies", "70-е", FilterCategory.VINTAGE, cm { sat(0.85f); scale(1.04f, 1f, 0.92f) }, grain = 0.4f, leak = 0.5f,
            tone = T(0.25f, lift = 0.12f, crush = 0.05f, sh = c(0.02f, 0.04f, -0.02f), hi = c(0.06f, 0.03f, -0.09f))),
        // ---------- Ч/Б ----------
        Filter("bw", "Ч/Б серебро", FilterCategory.BW, cm { mono(0.25f, 0.6f, 0.15f) },
            tone = T(0.35f, lift = 0.04f, sh = c(-0.01f, 0f, 0.025f))),
        Filter("bw_hard", "Ч/Б контраст", FilterCategory.BW, cm { mono(0.6f, 0.35f, 0.05f) }, vignette = 0.4f, grain = 0.25f,
            tone = T(1.4f, g = 1.1f, crush = 0.03f)),
        Filter("bw_soft", "Ч/Б мягкий", FilterCategory.BW, cm { mono(0.3f, 0.5f, 0.2f) },
            tone = T(-0.4f, g = 0.92f, lift = 0.1f, crush = 0.06f)),
        // ---------- Портрет ----------
        Filter("natural_skin", "Портрет", FilterCategory.PORTRAIT, cm { sat(1.0f) }, skinTone = 1f, vignette = 0.28f,
            tone = T(0.3f, g = 0.95f, sh = c(-0.02f, 0f, 0.04f))),
        Filter("soft_portrait", "Нежный", FilterCategory.PORTRAIT, cm { sat(0.85f) }, skinTone = 0.5f,
            tone = T(-0.35f, g = 0.82f, lift = 0.05f, hi = c(0.04f, 0f, 0.03f))),
        Filter("golden", "Золотой час", FilterCategory.PORTRAIT, cm { sat(1.1f); scale(1.06f, 1f, 0.88f) }, leak = 0.3f, vignette = 0.2f,
            tone = T(0.25f, sh = c(0.04f, 0f, -0.03f), hi = c(0.1f, 0.05f, -0.1f))),
        // ---------- Еда ----------
        Filter("food", "Еда", FilterCategory.FOOD, cm { sat(1.05f) }, warmSat = 1f,
            tone = T(0.25f, g = 0.93f)),
        // ---------- Природа ----------
        Filter("nature", "Природа", FilterCategory.NATURE, cm { sat(1.2f); scale(0.97f, 1.06f, 0.98f) },
            tone = T(0.35f, sh = c(-0.02f, 0.02f, 0.06f), hi = c(0.03f, 0.03f, -0.03f))),
        Filter("sky", "Ясное небо", FilterCategory.NATURE, cm { sat(1.25f); scale(0.94f, 1f, 1.08f) },
            tone = T(0.3f, g = 0.95f, sh = c(-0.03f, 0.01f, 0.07f))),
        // ---------- Ночь ----------
        Filter("night", "Ночь", FilterCategory.NIGHT, cm { sat(0.95f) },
            tone = T(-0.15f, sh = c(0.1f, 0.1f, 0.12f), hi = c(-0.05f, -0.05f, -0.04f))),           // тени вверх, огни не выжигаем
        Filter("city_night", "Огни города", FilterCategory.NIGHT, cm { sat(1.25f) }, vignette = 0.3f,
            tone = T(0.5f, sh = c(-0.03f, 0.02f, 0.08f), hi = c(0.1f, 0.04f, -0.06f))),
        // ---------- Неон ----------
        Filter("neon", "Неон", FilterCategory.CYBERPUNK, cm { sat(1.35f) },
            tone = T(0.6f, sh = c(0.06f, -0.04f, 0.16f), hi = c(0.12f, -0.04f, 0.1f))),
        Filter("synthwave", "Синтвейв", FilterCategory.CYBERPUNK, cm { sat(1.3f) }, leak = 0.35f, vignette = 0.3f,
            tone = T(0.5f, sh = c(0.1f, -0.02f, 0.18f), hi = c(0.14f, 0.06f, -0.06f))),
        // ---------- Стиль ----------
        Filter("pastel", "Пастель", FilterCategory.STYLE, cm { sat(0.8f) },
            tone = T(-0.3f, g = 0.82f, lift = 0.08f, sh = c(0f, 0.03f, 0.07f), hi = c(0.04f, 0f, 0.05f))),
        Filter("matte", "Мат", FilterCategory.STYLE, cm { sat(0.85f) },
            tone = T(0.2f, lift = 0.14f, crush = 0.07f)),
        Filter("grain", "Зерно", FilterCategory.STYLE, cm { sat(0.9f) }, grain = 0.85f, vignette = 0.25f,
            tone = T(0.35f, lift = 0.03f)),
        Filter("leak", "Засветка", FilterCategory.STYLE, cm { sat(1.05f) }, leak = 1f,
            tone = T(0.2f, lift = 0.03f, hi = c(0.04f, 0.02f, -0.03f))),
        // ---------- Соцсети ----------
        Filter("vivid", "Яркий", FilterCategory.SOCIAL, cm { sat(1.45f) }, tone = T(0.45f)),
        Filter("studio", "Чистый", FilterCategory.SOCIAL, cm { sat(1.12f) }, tone = T(0.1f, g = 0.8f)),
        Filter("moody", "Moody", FilterCategory.SOCIAL, cm { sat(0.7f) }, vignette = 0.35f,
            tone = T(0.5f, g = 1.15f, lift = 0.06f, sh = c(0f, 0.03f, 0.05f), hi = c(0.03f, 0.02f, -0.02f))),
        Filter("retro_pop", "Ретро-поп", FilterCategory.SOCIAL, cm { sat(1.4f) }, grain = 0.2f,
            tone = T(0.5f, lift = 0.05f, sh = c(0.03f, 0f, 0.07f), hi = c(0.07f, 0.04f, -0.06f))),
        // ---------- Из пакета шаблонов (effects.json): «Лето» и «Винтаж» ----------
        Filter("summer", "Лето", FilterCategory.NATURE, cm { sat(1.18f); scale(1.04f, 1.01f, 0.95f) }, leak = 0.18f,
            tone = T(0.25f, g = 0.92f, sh = c(0f, 0.02f, 0.05f), hi = c(0.05f, 0.03f, -0.04f))),
        Filter("vintage", "Винтаж", FilterCategory.VINTAGE, cm { sat(0.78f); scale(1.06f, 1f, 0.9f) }, vignette = 0.35f, grain = 0.25f,
            tone = T(0.15f, lift = 0.08f, crush = 0.05f, sh = c(0.03f, 0.02f, -0.02f), hi = c(0.06f, 0.03f, -0.07f))),
        // ---------- Новые повседневные рецепты ----------
        Filter("soft_morning", "Утро", FilterCategory.PORTRAIT, cm { sat(0.92f); scale(1.03f, 1.01f, 0.98f) },
            tone = T(-0.25f, g = 0.92f, lift = 0.045f, hi = c(0.04f, 0.025f, 0.01f))),
        Filter("cream", "Кремовый", FilterCategory.WEDDING, cm { sat(0.82f); scale(1.04f, 1.01f, 0.94f) },
            tone = T(-0.2f, g = 0.94f, lift = 0.055f, sh = c(0.025f, 0.015f, -0.005f))),
        Filter("urban_clean", "Город", FilterCategory.STREET, cm { sat(1.08f) }, vignette = 0.12f,
            tone = T(0.35f, g = 0.98f, sh = c(-0.015f, 0.005f, 0.025f))),
        Filter("emerald", "Изумруд", FilterCategory.LUXURY, cm { sat(1.12f); scale(0.97f, 1.04f, 1.01f) }, vignette = 0.22f,
            tone = T(0.45f, sh = c(-0.025f, 0.035f, 0.015f), hi = c(0.025f, 0.035f, 0.01f))),
        Filter("ocean_air", "Морской воздух", FilterCategory.TRAVEL, cm { sat(1.12f); scale(0.96f, 1.02f, 1.08f) },
            tone = T(0.22f, sh = c(-0.015f, 0.02f, 0.06f), hi = c(-0.01f, 0.02f, 0.035f))),
        Filter("amber_evening", "Тёплый вечер", FilterCategory.VINTAGE, cm { sat(0.94f); scale(1.06f, 1.01f, 0.91f) }, grain = 0.12f, vignette = 0.18f,
            tone = T(0.28f, lift = 0.025f, sh = c(0.035f, 0.012f, -0.025f), hi = c(0.075f, 0.035f, -0.055f))),
        Filter("night_cinema", "Ночной кино", FilterCategory.NIGHT, cm { sat(0.9f); scale(1.02f, 1.01f, 1.04f) }, vignette = 0.24f,
            tone = T(0.32f, lift = 0.025f, sh = c(0.015f, 0.025f, 0.06f), hi = c(0.035f, 0.02f, -0.015f))),
    )

    fun search(query: String, category: FilterCategory?): List<Filter> {
        val q = query.trim().lowercase()
        return all.filter { f ->
            (category == null || f.category == category || f == ORIGINAL) &&
                (q.isEmpty() || f.name.lowercase().contains(q) || (f.category?.title?.lowercase()?.contains(q) == true))
        }
    }

    private val index: Map<String, Filter> by lazy { all.associateBy { it.id } }

    fun byId(id: String?): Filter = id?.let { index[it] } ?: ORIGINAL

    /** Смешивает матрицу фильтра с единичной по интенсивности 0..1. */
    fun blended(f: Filter, intensity: Float): FloatArray {
        val id = ColorMatrix().array
        return FloatArray(20) { i -> id[i] + (f.matrix[i] - id[i]) * intensity }
    }

    // ---------- тональная кривая ----------

    private val lutCache = HashMap<String, ByteArray>()

    /** LUT 256×3 (R, затем G, затем B) для тона фильтра. */
    fun lut(f: Filter): ByteArray = synchronized(lutCache) { lutCache.getOrPut(f.id) { buildLut(f.tone) } }

    fun buildLut(t: Tone): ByteArray {
        val out = ByteArray(768)
        val sh = floatArrayOf(t.shR, t.shG, t.shB)
        val hi = floatArrayOf(t.hiR, t.hiG, t.hiB)
        for (ch in 0 until 3) for (i in 0 until 256) {
            var x = i / 255f
            var s = t.s.coerceIn(-1f, 2f)
            while (s != 0f) {
                val step = s.coerceIn(-1f, 1f)
                val ss = x * x * (3f - 2f * x)
                x += step * (ss - x)
                s -= step
            }
            x = x.coerceIn(0f, 1f)
            if (t.gamma != 1f) x = x.toDouble().pow(t.gamma.toDouble()).toFloat()
            x += sh[ch] * (1f - x) * (1f - x) + hi[ch] * x * x
            x = t.lift + x * (1f - t.lift - t.crush)
            out[ch * 256 + i] = (x.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
        }
        return out
    }

    /** Нужен ли попиксельный проход (кожа, еда, засветка, зерно). */
    fun hasExtras(f: Filter) = f.grain > 0f || f.leak > 0f || f.skinTone > 0f || f.warmSat > 0f

    /**
     * Полное применение фильтра к фото — та же математика, что в шейдере:
     * матрица → тональная кривая → кожа/еда → fade → виньетка → засветка → зерно.
     * [pre] — дополнительная матрица ДО фильтра (ручные правки, общий мягкий контраст).
     */
    fun render(src: Bitmap, f: Filter, intensity: Float, pre: FloatArray? = null, vignette: Boolean = true): Bitmap {
        if (f == ORIGINAL && pre == null && !hasExtras(f)) return src
        var m = blended(f, intensity)
        if (pre != null) m = concat(pre, m)
        var out = com.maysk.camera.ai.ImageOps.colorMatrix(src, m)
        if (!f.tone.isIdentity && intensity > 0.01f) out = applyLut(out, lut(f), intensity)
        if (hasExtras(f) && intensity > 0.01f) out = applyExtras(out, f, intensity, preVignette = true)
        if (vignette) out = com.maysk.camera.ai.ImageOps.vignetteAndFade(out, f.vignette * intensity, f.fade * intensity)
        if (hasExtras(f) && intensity > 0.01f) out = applyExtras(out, f, intensity, preVignette = false)
        return out
    }

    private fun skinMask(r: Float, g: Float, b: Float): Float {
        val cb = 0.5f - 0.168736f * r - 0.331264f * g + 0.5f * b
        val cr = 0.5f + 0.5f * r - 0.418688f * g - 0.081312f * b
        fun ss(a: Float, bb: Float, x: Float): Float { val t = ((x - a) / (bb - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
        val m1 = ss(0.50f, 0.54f, cr) * (1f - ss(0.66f, 0.70f, cr))
        val m2 = ss(0.28f, 0.32f, cb) * (1f - ss(0.50f, 0.54f, cb))
        return m1 * m2
    }

    /** Попиксельные приёмы — точная копия шейдера (Shaders.FRAGMENT, раздел «5b»). */
    private fun applyExtras(src: Bitmap, f: Filter, k: Float, preVignette: Boolean): Bitmap {
        val w = src.width; val h = src.height
        val out = if (src.isMutable && src.config == Bitmap.Config.ARGB_8888) src else src.copy(Bitmap.Config.ARGB_8888, true)
        val row = IntArray(w)
        val aspect = w.toFloat() / h
        val gs = max(1f, kotlin.math.min(w, h) / 720f)   // зерно одинакового размера на любом разрешении
        for (y in 0 until h) {
            out.getPixels(row, 0, w, 0, y, w, 1)
            val vy = 1f - (y + 0.5f) / h     // как vPos.y в шейдере (0 — низ)
            for (x in 0 until w) {
                val p = row[x]
                var r = ((p shr 16) and 255) / 255f; var g = ((p shr 8) and 255) / 255f; var b = (p and 255) / 255f
                if (preVignette) {
                    if (f.skinTone > 0f) {
                        val s = skinMask(r, g, b) * f.skinTone * k; val o = f.skinTone * k - s
                        r += s * 0.05f - o * 0.02f; g += s * 0.015f; b += -s * 0.03f + o * 0.03f
                    }
                    if (f.warmSat > 0f) {
                        val l = 0.299f * r + 0.587f * g + 0.114f * b
                        val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
                        val warm = ((r - b) * 2.5f).coerceIn(0f, 1f) * ((mx - mn) * 4f).coerceIn(0f, 1f)
                        val amt = 1f + 0.6f * f.warmSat * k * warm
                        r = l + (r - l) * amt + 0.03f * f.warmSat * k * warm; g = l + (g - l) * amt; b = l + (b - l) * amt
                    }
                } else {
                    val vx = (x + 0.5f) / w
                    if (f.leak > 0f) {
                        val dx = vx * aspect; val dy = vy - 1f
                        val d1 = kotlin.math.sqrt(dx * dx + dy * dy)
                        val m = (1f - (d1 / 0.75f).coerceIn(0f, 1f)).let { it * it } * 0.9f +
                            ((vx - 0.82f) / 0.18f).coerceIn(0f, 1f).let { it * it } * 0.45f
                        val a = (m * f.leak * k).coerceIn(0f, 1f)
                        r = 1f - (1f - r) * (1f - 1.0f * a); g = 1f - (1f - g) * (1f - 0.55f * a); b = 1f - (1f - b) * (1f - 0.25f * a)
                    }
                    if (f.grain > 0f) {
                        var hh = ((x / gs).toInt() * 374761393 + (y / gs).toInt() * 668265263) xor 0x5bd1e995
                        hh = (hh xor (hh ushr 13)) * 1274126177
                        val n = ((hh ushr 8) and 0xFFFF) / 65535f - 0.5f
                        val l = 0.299f * r + 0.587f * g + 0.114f * b
                        val a = n * f.grain * k * 0.12f * (1f - 0.5f * l)
                        r += a; g += a; b += a
                    }
                }
                row[x] = (p and -0x1000000) or ((r.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 16) or
                    ((g.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 8) or (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        return out
    }

    fun applyLut(src: Bitmap, lut: ByteArray, mix: Float): Bitmap {
        val w = src.width; val h = src.height
        val out = if (src.isMutable && src.config == Bitmap.Config.ARGB_8888) src else src.copy(Bitmap.Config.ARGB_8888, true)
        val k = (mix.coerceIn(0f, 1f) * 256).toInt()
        val tr = IntArray(256); val tg = IntArray(256); val tb = IntArray(256)
        for (i in 0 until 256) {
            tr[i] = i + (((lut[i].toInt() and 255) - i) * k shr 8)
            tg[i] = i + (((lut[256 + i].toInt() and 255) - i) * k shr 8)
            tb[i] = i + (((lut[512 + i].toInt() and 255) - i) * k shr 8)
        }
        val row = IntArray(w)
        for (y in 0 until h) {
            out.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = row[x]
                row[x] = (p and -0x1000000) or (tr[(p shr 16) and 255] shl 16) or (tg[(p shr 8) and 255] shl 8) or tb[p and 255]
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        return out
    }

    /** Миниатюры фильтров, отрисованные настоящим конвейером. */
    private val thumbCache = object : android.util.LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun thumbnail(base: Bitmap, f: Filter): Bitmap {
        val key = "${System.identityHashCode(base)}:${f.id}"
        val hit = thumbCache.get(key)
        if (hit != null) return hit
        val r = render(base, f, 1f)
        thumbCache.put(key, r)
        return r
    }

    /** Матрица ручных правок: яркость (-1..1), контраст (-1..1), насыщенность (-1..1), теплота (-1..1). */
    fun adjustments(brightness: Float, contrast: Float, saturation: Float, warmth: Float): FloatArray = cm {
        sat(1f + saturation)
        contrast(1f + contrast * 0.6f)
        bright(brightness * 70f)
        scale(1f + warmth * 0.12f, 1f + warmth * 0.02f, 1f - warmth * 0.12f)
    }

    fun concat(a: FloatArray, b: FloatArray): FloatArray {
        val m = ColorMatrix(a); m.postConcat(ColorMatrix(b)); return m.array.copyOf()
    }
}

/** Эффекты (дизайн «Эффекты»). Работают в живом превью/видео (шейдер) и на фото (CPU). */
enum class Effect(val id: Int, val title: String, val forVideo: Boolean = true) {
    NONE(0, "Нет"),
    BG_BLUR(1, "Размытие фона"),
    BOKEH(2, "Боке"),
    LIGHT_RAYS(3, "Световые лучи"),
    GLITCH(4, "Глитч"),
    FILM(5, "Плёнка"),
    GRAIN(6, "Зерно"),
    RAINBOW(7, "Радуга"),
    RAIN(8, "Дождь"),
    SNOW(9, "Снег"),
    CONFETTI(10, "Конфетти"),
    STARS(11, "Звёзды"),
    LEAVES(12, "Листопад"),
    FIREWORKS(13, "Салют"),
    FIRE(14, "Огонь"),
    SMOKE(15, "Дым"),
}
