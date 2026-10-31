package com.maysk.camera.camera

import android.graphics.ColorMatrix

/** Категории фильтров как в дизайне. */
enum class FilterCategory(val title: String) {
    CINEMA("Cinema"), HOLLYWOOD("Hollywood"), PORTRAIT("Портрет"), WEDDING("Wedding"), TRAVEL("Travel"),
    FOOD("Еда"), NATURE("Природа"), STREET("Street"), LUXURY("Luxury"), VINTAGE("Винтаж"),
    BW("Ч/Б"), CYBERPUNK("Cyberpunk"), ANIME("Anime"), SOCIAL("Соцсети"), NIGHT("Ночь"), STYLE("Стиль")
}

/**
 * Цветовой фильтр. [matrix] — матрица 4x5 в формате android.graphics.ColorMatrix
 * (смещения в диапазоне 0..255). Одна и та же матрица используется
 * в GPU-шейдере (живое превью и видео) и в CPU-обработке фото — результат совпадает.
 */
data class Filter(
    val id: String,
    val name: String,
    val category: FilterCategory?,
    val matrix: FloatArray,
    val vignette: Float = 0f,
    val fade: Float = 0f,
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
    private fun ColorMatrix.lift(r: Float, g: Float, b: Float) =
        postConcat(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, r, 0f, 1f, 0f, 0f, g, 0f, 0f, 1f, 0f, b, 0f, 0f, 0f, 1f, 0f)))

    val ORIGINAL = Filter("original", "Оригинал", null, cm { })

    /** Рецепт «образа» для генерации семейства фильтров. */
    private data class Look(
        val name: String, val cat: FilterCategory,
        val temp: Float = 0f, val tint: Float = 0f, val sat: Float = 1f, val contrast: Float = 1f,
        val bright: Float = 0f, val fade: Float = 0f, val vignette: Float = 0f,
        val teal: Float = 0f, val mono: Boolean = false, val lift: Float = 0f,
    )

    /** Вариации каждого образа → 5 фильтров на образ. */
    private val variants = listOf("Soft", "Classic", "Strong", "Warm", "Cool")

    private fun build(l: Look, v: Int): Filter {
        val k = when (v) { 0 -> 0.55f; 2 -> 1.45f; else -> 1f }
        val temp = l.temp * k + when (v) { 3 -> 0.12f; 4 -> -0.12f; else -> 0f }
        val m = cm {
            if (l.mono) sat(0f) else sat(1f + (l.sat - 1f) * k)
            contrast(1f + (l.contrast - 1f) * k)
            bright(l.bright * k)
            scale(1f + temp * 0.14f, 1f + l.tint * 0.08f * k, 1f - temp * 0.14f)
            if (l.teal != 0f) {
                // кино-«тил-оранж»: синее в тени/зелени, тёплое в коже
                postConcat(ColorMatrix(floatArrayOf(
                    1f + 0.06f * l.teal * k, 0f, -0.04f * l.teal * k, 0f, 0f,
                    0f, 1f, 0.05f * l.teal * k, 0f, 0f,
                    -0.06f * l.teal * k, 0.06f * l.teal * k, 1f + 0.05f * l.teal * k, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            if (l.lift != 0f) lift(l.lift * k, l.lift * k, l.lift * k)
        }
        val id = l.name.lowercase().replace(' ', '_') + "_" + variants[v].lowercase()
        return Filter(id, "${l.name} ${variants[v]}", l.cat, m, vignette = (l.vignette * k).coerceAtMost(0.7f), fade = (l.fade * k).coerceAtMost(0.4f))
    }

    private val looks = listOf(
        // Cinema
        Look("Teal Orange", FilterCategory.CINEMA, temp = 0.1f, sat = 1.05f, contrast = 1.15f, teal = 1f, vignette = 0.3f),
        Look("Noir Cine", FilterCategory.CINEMA, sat = 0.6f, contrast = 1.25f, temp = -0.05f, vignette = 0.45f),
        Look("Blockbuster", FilterCategory.CINEMA, sat = 1.1f, contrast = 1.2f, teal = 0.7f, bright = -4f, vignette = 0.35f),
        Look("Indie Film", FilterCategory.CINEMA, sat = 0.85f, contrast = 0.95f, fade = 0.15f, temp = 0.05f),
        // Hollywood
        Look("Golden Age", FilterCategory.HOLLYWOOD, temp = 0.2f, sat = 0.95f, contrast = 1.1f, vignette = 0.3f),
        Look("Action", FilterCategory.HOLLYWOOD, teal = 1.2f, contrast = 1.25f, sat = 1.0f, vignette = 0.25f),
        Look("Drama", FilterCategory.HOLLYWOOD, sat = 0.75f, contrast = 1.18f, temp = -0.08f, vignette = 0.4f),
        Look("Sci-Fi", FilterCategory.HOLLYWOOD, temp = -0.25f, tint = 0.3f, contrast = 1.15f, sat = 0.9f),
        // Portrait
        Look("Porcelain", FilterCategory.PORTRAIT, bright = 10f, contrast = 0.92f, sat = 0.9f, temp = 0.03f, fade = 0.05f),
        Look("Peach", FilterCategory.PORTRAIT, temp = 0.12f, tint = -0.1f, sat = 1.05f, bright = 6f),
        Look("Studio", FilterCategory.PORTRAIT, contrast = 1.1f, bright = 8f, sat = 0.95f),
        Look("Rose", FilterCategory.PORTRAIT, tint = -0.25f, temp = 0.06f, sat = 1.02f, bright = 5f),
        // Wedding
        Look("Ivory", FilterCategory.WEDDING, bright = 12f, contrast = 0.9f, sat = 0.85f, temp = 0.06f, fade = 0.08f),
        Look("Bloom", FilterCategory.WEDDING, bright = 14f, contrast = 0.85f, sat = 0.95f, tint = -0.12f, fade = 0.1f),
        Look("Champagne", FilterCategory.WEDDING, temp = 0.15f, sat = 0.9f, bright = 8f, vignette = 0.15f),
        Look("Pure White", FilterCategory.WEDDING, bright = 16f, contrast = 0.92f, sat = 0.8f, temp = -0.04f),
        // Travel
        Look("Wanderlust", FilterCategory.TRAVEL, sat = 1.25f, contrast = 1.08f, temp = 0.05f),
        Look("Ocean", FilterCategory.TRAVEL, temp = -0.15f, sat = 1.2f, contrast = 1.05f),
        Look("Desert", FilterCategory.TRAVEL, temp = 0.25f, sat = 1.05f, contrast = 1.1f, fade = 0.05f),
        Look("Alpine", FilterCategory.TRAVEL, temp = -0.08f, sat = 1.1f, contrast = 1.12f, bright = 4f),
        // Food
        Look("Fresh", FilterCategory.FOOD, sat = 1.3f, bright = 8f, contrast = 1.05f, temp = 0.04f),
        Look("Bakery", FilterCategory.FOOD, temp = 0.2f, sat = 1.15f, bright = 6f),
        Look("Juicy", FilterCategory.FOOD, sat = 1.45f, contrast = 1.1f, tint = -0.05f),
        Look("Cafe", FilterCategory.FOOD, temp = 0.12f, sat = 0.95f, fade = 0.1f, vignette = 0.2f),
        // Nature
        Look("Forest", FilterCategory.NATURE, sat = 1.2f, tint = 0.2f, contrast = 1.08f),
        Look("Spring", FilterCategory.NATURE, sat = 1.25f, bright = 8f, tint = 0.1f),
        Look("Autumn", FilterCategory.NATURE, temp = 0.28f, sat = 1.15f, contrast = 1.08f),
        Look("Sky Blue", FilterCategory.NATURE, temp = -0.18f, sat = 1.2f, bright = 4f),
        // Street
        Look("Urban", FilterCategory.STREET, sat = 0.8f, contrast = 1.2f, temp = -0.06f, vignette = 0.3f),
        Look("Concrete", FilterCategory.STREET, sat = 0.65f, contrast = 1.15f, fade = 0.08f),
        Look("Neon Street", FilterCategory.STREET, sat = 1.3f, contrast = 1.2f, teal = 0.8f, temp = -0.1f),
        Look("Grit", FilterCategory.STREET, sat = 0.75f, contrast = 1.3f, vignette = 0.45f, bright = -6f),
        // Luxury
        Look("Gold", FilterCategory.LUXURY, temp = 0.22f, contrast = 1.15f, sat = 1.0f, vignette = 0.35f),
        Look("Velvet", FilterCategory.LUXURY, tint = -0.2f, contrast = 1.18f, sat = 1.05f, vignette = 0.35f, bright = -5f),
        Look("Marble", FilterCategory.LUXURY, sat = 0.8f, contrast = 1.05f, bright = 8f, temp = -0.03f),
        Look("Onyx", FilterCategory.LUXURY, sat = 0.7f, contrast = 1.3f, bright = -8f, vignette = 0.5f),
        // Vintage
        Look("Kodak 70s", FilterCategory.VINTAGE, temp = 0.2f, sat = 0.85f, fade = 0.2f, lift = 10f, vignette = 0.3f),
        Look("Polaroid", FilterCategory.VINTAGE, temp = 0.1f, tint = 0.1f, sat = 0.8f, fade = 0.25f, lift = 14f),
        Look("Sepia", FilterCategory.VINTAGE, temp = 0.4f, sat = 0.35f, contrast = 1.05f, fade = 0.12f),
        Look("Faded", FilterCategory.VINTAGE, sat = 0.7f, contrast = 0.85f, fade = 0.3f, lift = 12f),
        // B&W
        Look("Mono", FilterCategory.BW, mono = true, contrast = 1.1f),
        Look("High Key", FilterCategory.BW, mono = true, contrast = 1.0f, bright = 16f),
        Look("Low Key", FilterCategory.BW, mono = true, contrast = 1.35f, bright = -10f, vignette = 0.4f),
        Look("Silver", FilterCategory.BW, mono = true, contrast = 1.18f, fade = 0.1f, temp = -0.1f),
        // Cyberpunk
        Look("Neon", FilterCategory.CYBERPUNK, tint = -0.4f, temp = -0.2f, sat = 1.4f, contrast = 1.2f),
        Look("Night City", FilterCategory.CYBERPUNK, temp = -0.3f, tint = -0.25f, sat = 1.3f, contrast = 1.25f, vignette = 0.35f),
        Look("Synthwave", FilterCategory.CYBERPUNK, tint = -0.5f, temp = 0.1f, sat = 1.35f, contrast = 1.15f),
        Look("Matrix", FilterCategory.CYBERPUNK, tint = 0.6f, temp = -0.1f, sat = 0.9f, contrast = 1.25f, vignette = 0.3f),
        // Anime
        Look("Pastel", FilterCategory.ANIME, sat = 1.15f, bright = 14f, contrast = 0.9f, tint = -0.08f),
        Look("Ghibli Sky", FilterCategory.ANIME, temp = -0.1f, sat = 1.35f, bright = 10f, contrast = 0.95f),
        Look("Sakura", FilterCategory.ANIME, tint = -0.3f, temp = 0.05f, sat = 1.2f, bright = 12f),
        Look("Cel Vivid", FilterCategory.ANIME, sat = 1.5f, contrast = 1.2f, bright = 6f),
        // Соцсети
        Look("Reels", FilterCategory.SOCIAL, sat = 1.2f, contrast = 1.12f, temp = 0.05f),
        Look("Clean Feed", FilterCategory.SOCIAL, bright = 8f, sat = 1.05f, contrast = 1.02f),
        Look("Moody Feed", FilterCategory.SOCIAL, sat = 0.8f, contrast = 1.1f, fade = 0.1f, temp = -0.05f),
        Look("Snap", FilterCategory.SOCIAL, sat = 1.25f, bright = 6f, tint = -0.05f),
        // Ночь
        Look("Night Pop", FilterCategory.NIGHT, bright = 12f, sat = 1.15f, contrast = 1.1f, temp = -0.1f),
        Look("Moonlight", FilterCategory.NIGHT, temp = -0.3f, sat = 0.8f, bright = 6f),
        Look("City Lights", FilterCategory.NIGHT, temp = 0.15f, sat = 1.2f, contrast = 1.15f, vignette = 0.3f),
        Look("Starlight", FilterCategory.NIGHT, temp = -0.2f, tint = -0.1f, contrast = 1.2f, bright = 4f),
        // Стиль
        Look("Matte", FilterCategory.STYLE, sat = 0.9f, contrast = 0.9f, fade = 0.2f, lift = 8f),
        Look("Chrome", FilterCategory.STYLE, sat = 1.1f, contrast = 1.25f, temp = -0.05f),
        Look("Dream", FilterCategory.STYLE, bright = 12f, sat = 1.05f, fade = 0.15f, tint = -0.1f),
        Look("Retro Pop", FilterCategory.STYLE, sat = 1.35f, contrast = 1.15f, temp = 0.1f, vignette = 0.2f),
    )

    /** Сгенерированные фильтры: 64 образа × 5 вариаций = 320. */
    private val generated: List<Filter> by lazy { looks.flatMap { l -> (0 until 5).map { v -> build(l, v) } } }

    private val curated: List<Filter> = listOf(
        ORIGINAL,
        // Кино
        Filter("cinema", "Кино", FilterCategory.CINEMA, cm { contrast(1.15f); sat(0.85f); scale(0.95f, 1.0f, 1.08f); lift(6f, 4f, 0f) }, vignette = 0.35f),
        Filter("hollywood", "Hollywood", FilterCategory.HOLLYWOOD, cm { contrast(1.2f); sat(1.05f); scale(1.06f, 0.98f, 0.92f); lift(-4f, 2f, 10f) }, vignette = 0.3f),
        Filter("warm_film", "Warm Film", FilterCategory.CINEMA, cm { contrast(1.05f); sat(0.9f); scale(1.08f, 1.0f, 0.86f); lift(10f, 6f, 0f) }, fade = 0.12f, vignette = 0.2f),
        Filter("cold_film", "Cold Film", FilterCategory.CINEMA, cm { contrast(1.08f); sat(0.8f); scale(0.9f, 1.0f, 1.12f); lift(0f, 4f, 10f) }, fade = 0.1f),
        Filter("vintage", "Винтаж", FilterCategory.VINTAGE, cm { sat(0.6f); contrast(0.9f); scale(1.1f, 1.0f, 0.8f); lift(20f, 12f, 0f) }, fade = 0.2f, vignette = 0.45f),
        Filter("bw", "Ч/Б", FilterCategory.BW, cm { sat(0f); contrast(1.2f) }, vignette = 0.2f),
        Filter("night", "Ночной", FilterCategory.NIGHT, cm { bright(10f); sat(0.9f); scale(0.92f, 0.98f, 1.12f); contrast(1.1f) }),
        // Портрет
        Filter("soft_portrait", "Soft Portrait", FilterCategory.PORTRAIT, cm { contrast(0.92f); sat(0.95f); scale(1.04f, 1.0f, 0.98f); bright(8f) }, fade = 0.06f),
        Filter("natural_skin", "Natural Skin", FilterCategory.PORTRAIT, cm { sat(1.02f); scale(1.03f, 1.0f, 0.97f); bright(4f) }),
        Filter("studio", "Studio Light", FilterCategory.PORTRAIT, cm { contrast(1.1f); bright(10f); sat(0.95f) }),
        Filter("golden", "Golden Hour", FilterCategory.PORTRAIT, cm { sat(1.1f); scale(1.12f, 1.02f, 0.82f); lift(12f, 6f, 0f) }, vignette = 0.25f),
        // Природа
        Filter("vivid", "Яркий", FilterCategory.NATURE, cm { sat(1.35f); contrast(1.1f) }),
        Filter("warm", "Тёплый", FilterCategory.NATURE, cm { scale(1.1f, 1.02f, 0.88f); sat(1.05f) }),
        Filter("cold", "Холодный", FilterCategory.NATURE, cm { scale(0.9f, 1.0f, 1.12f); sat(1.0f) }),
        Filter("nature", "Природа", FilterCategory.NATURE, cm { sat(1.25f); scale(0.97f, 1.08f, 0.98f); contrast(1.06f) }),
        Filter("sunset", "Закат", FilterCategory.NATURE, cm { sat(1.2f); scale(1.15f, 0.95f, 0.85f); lift(8f, 0f, 6f) }, vignette = 0.3f),
        Filter("food", "Еда", FilterCategory.FOOD, cm { sat(1.3f); scale(1.06f, 1.02f, 0.94f); contrast(1.08f); bright(6f) }),
        // Соцсети
        Filter("instagram", "Instagram", FilterCategory.SOCIAL, cm { sat(1.15f); contrast(1.08f); scale(1.04f, 1.0f, 0.96f) }, fade = 0.05f),
        Filter("tiktok", "TikTok", FilterCategory.SOCIAL, cm { sat(1.25f); contrast(1.15f); scale(0.98f, 1.0f, 1.06f) }),
        Filter("youtube", "YouTube", FilterCategory.SOCIAL, cm { sat(1.1f); contrast(1.12f); bright(4f) }),
        Filter("shorts", "Shorts", FilterCategory.SOCIAL, cm { sat(1.2f); contrast(1.18f); scale(1.05f, 1.0f, 0.95f) }, vignette = 0.15f),
    )

    /** Все фильтры: авторские + сгенерированные (300+). */
    val all: List<Filter> by lazy { curated + generated }

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
