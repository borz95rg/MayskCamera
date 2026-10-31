package com.maysk.camera.ar3d

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.floor

/**
 * Процедурная анимация лица для AR-оверлеев:
 *  - дыхание: мягкая пульсация масштаба (~0.25 Гц, ±1.5%)
 *  - моргание: быстрое сжатие по Y + лёгкое падение alpha каждые 2.8–4.5 с
 *
 * Не зависит от ML-открытости глаз (её в пайплайне нет) — выглядит естественно
 * на любых масках/шаблонах.
 */
object FaceAnim {
    /** Глобальный масштаб от дыхания: 1 ± amplitude. */
    fun breathScale(tSec: Float, amplitude: Float = 0.015f, hz: Float = 0.28f): Float {
        // два гармонических тона — менее «механично»
        val a = sin(2.0 * PI * hz * tSec)
        val b = sin(2.0 * PI * hz * 0.53 * tSec + 1.2)
        return (1.0 + amplitude * (0.72 * a + 0.28 * b)).toFloat()
    }

    /**
     * Смещение вверх для «верхних» кусочков (уши, корона, нимб) при вдохе.
     * В лицевых единицах, до умножения на scale позы.
     */
    fun breathLift(tSec: Float, amount: Float = 0.012f, hz: Float = 0.28f): Float {
        val a = sin(2.0 * PI * hz * tSec)
        return (amount * a).toFloat()
    }

    /**
     * Фаза моргания 0..1:
     *  0 — глаза открыты, 1 — полностью закрыты (в середине клипа).
     * Клип ~120 мс, интервал между морганиями псевдослучаен 2.6–4.8 с.
     */
    fun blinkAmount(tSec: Float): Float {
        // детерминированный «случайный» интервал по номеру цикла
        val cycle = 3.4 // средний период
        val phase = tSec / cycle
        val n = floor(phase)
        // jitter от номера цикла
        val jitter = (sin(n * 12.9898) * 43758.5453)
        val fracJ = (jitter - floor(jitter)).toFloat() // 0..1
        val period = 2.6 + fracJ * 2.2 // 2.6..4.8
        val local = ((tSec % period) / period).toFloat()
        // моргание в конце периода, длительность ~3.5% периода (~100–170 мс)
        val blinkStart = 0.965f
        if (local < blinkStart) return 0f
        val u = ((local - blinkStart) / (1f - blinkStart)).coerceIn(0f, 1f)
        // быстро вниз и вверх: sin(pi * u)
        return sin(PI * u).toFloat()
    }

    /** Роль кусочка по имени файла — чтобы моргать только «глазные» накладки. */
    enum class Role { TOP, EYE, CHEEK, OTHER }

    fun roleOf(key: String): Role {
        val k = key.lowercase()
        return when {
            k.contains("liner") || k.contains("glasses") || k.contains("visor") ||
            k.contains("freckles") && false -> Role.EYE
            k.contains("liner") || k.contains("glasses") || k.contains("visor") -> Role.EYE
            k.contains("cat_0") || k.contains("fox_0") || k.contains("bunny_0") ||
            k.contains("puppy_0") || k.contains("crown") || k.contains("halo") ||
            k.contains("devil") || k.contains("fire") || k.contains("flower") ||
            k.contains("ice") || k.contains("sparkles") || k.contains("butterfly") ||
            k.contains("hearts_0") -> Role.TOP
            k.contains("blush") || k.contains("hearts_cheeks") || k.contains("freckles") -> Role.CHEEK
            else -> Role.OTHER
        }
    }

    /**
     * Параметры анимации для одного спрайта на текущий момент.
     * @return Triple(scaleX, scaleY, alphaMul)
     */
    fun spriteAnim(key: String, tSec: Float): Triple<Float, Float, Float> {
        val breath = breathScale(tSec)
        val blink = blinkAmount(tSec)
        val role = roleOf(key)
        return when (role) {
            Role.EYE -> {
                // при моргании сжимаем по Y и слегка гасим
                val sy = 1f - 0.85f * blink
                val a = 1f - 0.55f * blink
                Triple(breath, breath * sy, a)
            }
            Role.TOP -> {
                // уши/корона дышат + лёгкий «подъём»
                Triple(breath, breath, 1f)
            }
            Role.CHEEK -> {
                // щёки чуть пульсируют вместе с дыханием
                Triple(breath, breath, 1f)
            }
            Role.OTHER -> Triple(breath, breath, 1f)
        }
    }

    fun topLift(key: String, tSec: Float): Float {
        return if (roleOf(key) == Role.TOP) breathLift(tSec) else 0f
    }
}
