package com.maysk.camera.ar3d

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.floor

/**
 * Анимация лица: дыхание + моргание.
 * Моргание предпочитает реальное [eyeOpen] из mesh; если недоступно — синтетика.
 */
object FaceAnim {
    fun breathScale(tSec: Float, amplitude: Float = 0.015f, hz: Float = 0.28f): Float {
        val a = sin(2.0 * PI * hz * tSec)
        val b = sin(2.0 * PI * hz * 0.53 * tSec + 1.2)
        return (1.0 + amplitude * (0.72 * a + 0.28 * b)).toFloat()
    }

    fun breathLift(tSec: Float, amount: Float = 0.012f, hz: Float = 0.28f): Float {
        return (amount * sin(2.0 * PI * hz * tSec)).toFloat()
    }

    /** Синтетическое моргание (fallback). */
    fun blinkAmountSynthetic(tSec: Float): Float {
        val cycle = 3.4
        val n = floor(tSec / cycle)
        val jitter = (sin(n * 12.9898) * 43758.5453)
        val fracJ = (jitter - floor(jitter)).toFloat()
        val period = 2.6 + fracJ * 2.2
        val local = ((tSec % period) / period).toFloat()
        val blinkStart = 0.965f
        if (local < blinkStart) return 0f
        val u = ((local - blinkStart) / (1f - blinkStart)).coerceIn(0f, 1f)
        return sin(PI * u).toFloat()
    }

    /**
     * 0 = открыто, 1 = закрыто.
     * [eyeOpen] из mesh: 1 открыт, 0 закрыт → blink = 1 - eyeOpen.
     * Если eyeOpen ≈ всегда 1 (нет данных), мешаем с синтетикой слабо.
     */
    fun blinkAmount(tSec: Float, eyeOpen: Float = -1f): Float {
        if (eyeOpen in 0f..1f) {
            // лёгкая кривая: полностью закрыто только при eyeOpen < 0.25
            val closed = (1f - eyeOpen).coerceIn(0f, 1f)
            return (closed * closed * (3f - 2f * closed)) // smoothstep-ish stronger near closed
        }
        return blinkAmountSynthetic(tSec)
    }

    enum class Role { TOP, EYE, CHEEK, EAR_L, EAR_R, OTHER }

    fun roleOf(key: String): Role {
        val k = key.lowercase()
        return when {
            k.contains("_l.") || k.contains("_l_") || k.endsWith("_l") || k.contains("ear_l") || k.contains("left") -> Role.EAR_L
            k.contains("_r.") || k.contains("_r_") || k.endsWith("_r") || k.contains("ear_r") || k.contains("right") -> Role.EAR_R
            k.contains("liner") || k.contains("glasses") || k.contains("visor") ||
            k.contains("glam") || k.contains("heart_eyes") || k.contains("eyeshadow") ||
            k.contains("eyelashes") -> Role.EYE
            k.contains("cat_0") || k.contains("fox_0") || k.contains("bunny_0") ||
            k.contains("puppy_0") || k.contains("tiger_0") || k.contains("wolf_0") ||
            k.contains("deer") || k.contains("unicorn") || k.contains("crown") ||
            k.contains("halo") || k.contains("devil") || k.contains("fire") ||
            k.contains("flower") || k.contains("ice") || k.contains("rainbow") ||
            k.contains("butterfly") || k.contains("sparkles") || k.contains("hearts_0") -> Role.TOP
            k.contains("blush") || k.contains("hearts_cheeks") || k.contains("freckles") ||
            k.contains("nose_blush") || k.contains("pearls") || k.contains("flower_cheek") -> Role.CHEEK
            else -> Role.OTHER
        }
    }

    /** scaleX, scaleY, alphaMul */
    fun spriteAnim(key: String, tSec: Float, eyeOpen: Float = -1f, yaw: Float = 0f): Triple<Float, Float, Float> {
        val breath = breathScale(tSec)
        val blink = blinkAmount(tSec, eyeOpen)
        val role = roleOf(key)
        return when (role) {
            Role.EYE -> {
                val sy = 1f - 0.88f * blink
                val a = 1f - 0.6f * blink
                Triple(breath, breath * sy, a)
            }
            Role.EAR_L -> {
                // при повороте вправо (yaw>0) левое ухо уходит за голову
                val hide = ((yaw - 0.15f) / 0.55f).coerceIn(0f, 1f)
                Triple(breath, breath, 1f - 0.85f * hide)
            }
            Role.EAR_R -> {
                val hide = ((-yaw - 0.15f) / 0.55f).coerceIn(0f, 1f)
                Triple(breath, breath, 1f - 0.85f * hide)
            }
            Role.TOP, Role.CHEEK, Role.OTHER -> Triple(breath, breath, 1f)
        }
    }

    fun topLift(key: String, tSec: Float): Float {
        val r = roleOf(key)
        return if (r == Role.TOP || r == Role.EAR_L || r == Role.EAR_R) breathLift(tSec) else 0f
    }
}
