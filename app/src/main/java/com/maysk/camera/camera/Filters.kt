package com.maysk.camera.camera

import android.graphics.ColorMatrix

/** Категории фильтров как в дизайне. */
enum class FilterCategory(val title: String) { CINEMA("Кино"), PORTRAIT("Портрет"), NATURE("Природа"), STYLE("Стиль"), SOCIAL("Соцсети") }

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

    val all: List<Filter> = listOf(
        ORIGINAL,
        // Кино
        Filter("cinema", "Кино", FilterCategory.CINEMA, cm { contrast(1.15f); sat(0.85f); scale(0.95f, 1.0f, 1.08f); lift(6f, 4f, 0f) }, vignette = 0.35f),
        Filter("hollywood", "Hollywood", FilterCategory.CINEMA, cm { contrast(1.2f); sat(1.05f); scale(1.06f, 0.98f, 0.92f); lift(-4f, 2f, 10f) }, vignette = 0.3f),
        Filter("warm_film", "Warm Film", FilterCategory.CINEMA, cm { contrast(1.05f); sat(0.9f); scale(1.08f, 1.0f, 0.86f); lift(10f, 6f, 0f) }, fade = 0.12f, vignette = 0.2f),
        Filter("cold_film", "Cold Film", FilterCategory.CINEMA, cm { contrast(1.08f); sat(0.8f); scale(0.9f, 1.0f, 1.12f); lift(0f, 4f, 10f) }, fade = 0.1f),
        Filter("vintage", "Винтаж", FilterCategory.CINEMA, cm { sat(0.6f); contrast(0.9f); scale(1.1f, 1.0f, 0.8f); lift(20f, 12f, 0f) }, fade = 0.2f, vignette = 0.45f),
        Filter("bw", "Ч/Б", FilterCategory.STYLE, cm { sat(0f); contrast(1.2f) }, vignette = 0.2f),
        Filter("night", "Ночной", FilterCategory.STYLE, cm { bright(10f); sat(0.9f); scale(0.92f, 0.98f, 1.12f); contrast(1.1f) }),
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
        Filter("food", "Еда", FilterCategory.NATURE, cm { sat(1.3f); scale(1.06f, 1.02f, 0.94f); contrast(1.08f); bright(6f) }),
        // Соцсети
        Filter("instagram", "Instagram", FilterCategory.SOCIAL, cm { sat(1.15f); contrast(1.08f); scale(1.04f, 1.0f, 0.96f) }, fade = 0.05f),
        Filter("tiktok", "TikTok", FilterCategory.SOCIAL, cm { sat(1.25f); contrast(1.15f); scale(0.98f, 1.0f, 1.06f) }),
        Filter("youtube", "YouTube", FilterCategory.SOCIAL, cm { sat(1.1f); contrast(1.12f); bright(4f) }),
        Filter("shorts", "Shorts", FilterCategory.SOCIAL, cm { sat(1.2f); contrast(1.18f); scale(1.05f, 1.0f, 0.95f) }, vignette = 0.15f),
    )

    fun byId(id: String?): Filter = all.firstOrNull { it.id == id } ?: ORIGINAL

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
}
