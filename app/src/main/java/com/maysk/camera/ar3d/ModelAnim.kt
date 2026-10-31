package com.maysk.camera.ar3d

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Процедурная анимация 3D-масок (без скелета в GLB).
 * Масштаб / подъём / лёгкий roll в локальных осях лица + fade по showWhen.
 */
object ModelAnim {

    enum class Style {
        NONE, BREATH, BOB, SWAY, PULSE, SPIN, MOUTH, BLINK, EAR_FLAP
    }

    data class State(
        val scale: Float = 1f,
        val liftY: Float = 0f,   // вдоль up лица, в face-units (доли scale)
        val roll: Float = 0f,    // рад, вокруг forward
        val yawLocal: Float = 0f,
        val fade: Float = 1f,
    )

    fun styleFor(modelId: String): Style {
        val id = modelId.lowercase()
        // явный суффикс/имя аним-варианта имеет приоритет
        return when {
            id.endsWith("_pulse") || id.contains("pulse") -> Style.PULSE
            id.endsWith("_spin") || id.contains("spin") -> Style.SPIN
            id.endsWith("_bob") || id.contains("jump") -> Style.BOB
            id.endsWith("_sway") || id.contains("sway") -> Style.SWAY
            id.endsWith("_mouth") || id.contains("roar") -> Style.MOUTH
            id.endsWith("_blink") -> Style.BLINK
            id.contains("crown") || id.contains("glasses") -> Style.BOB
            id.contains("fantasy") || id.contains("mask") -> Style.PULSE
            id.contains("owl") || id.contains("bird") -> Style.BLINK
            id.contains("bunny") || id.contains("deer") || id.contains("cat") ||
            id.contains("fox") || id.contains("wolf") || id.contains("dog") ||
            id.contains("tiger") || id.contains("lion") || id.contains("bear") ||
            id.contains("panda") || id.contains("raccoon") -> Style.EAR_FLAP
            else -> Style.BREATH
        }
    }

    fun styleFromCatalog(anim: String?): Style = when (anim?.lowercase()) {
        "bob" -> Style.BOB
        "sway" -> Style.SWAY
        "pulse" -> Style.PULSE
        "spin" -> Style.SPIN
        "mouth" -> Style.MOUTH
        "blink" -> Style.BLINK
        "ear_flap", "ear" -> Style.EAR_FLAP
        "none" -> Style.NONE
        "breath" -> Style.BREATH
        else -> Style.BREATH
    }

    fun evaluate(
        modelId: String,
        tSec: Float,
        pose: FacePose,
        styleOverride: Style? = null,
    ): State {
        val style = styleOverride ?: styleFor(modelId)
        val breath = 1f + 0.018f * sin(2.0 * PI * 0.28 * tSec).toFloat()
        val breathLift = 0.012f * sin(2.0 * PI * 0.28 * tSec).toFloat()
        val blink = FaceAnim.blinkAmount(tSec, pose.eyeOpen) // 0 open .. 1 closed

        return when (style) {
            Style.NONE -> State()
            Style.BREATH -> State(scale = breath, liftY = breathLift)
            Style.BOB -> {
                val bob = 0.035f * sin(2.0 * PI * 1.1 * tSec).toFloat()
                State(scale = breath, liftY = breathLift + bob)
            }
            Style.SWAY -> {
                val roll = 0.08f * sin(2.0 * PI * 0.55 * tSec).toFloat()
                State(scale = breath, liftY = breathLift, roll = roll)
            }
            Style.PULSE -> {
                val p = 1f + 0.04f * (0.5f + 0.5f * sin(2.0 * PI * 1.4 * tSec).toFloat())
                val a = 0.75f + 0.25f * (0.5f + 0.5f * sin(2.0 * PI * 1.4 * tSec).toFloat())
                State(scale = p, liftY = breathLift, fade = a)
            }
            Style.SPIN -> {
                val y = (tSec * 0.7f) % (2f * PI.toFloat())
                State(scale = breath, yawLocal = y, liftY = breathLift)
            }
            Style.MOUTH -> {
                // слегка «раскрывается» с ртом
                val s = 1f + 0.12f * pose.mouthOpen
                State(scale = s * breath, liftY = breathLift + 0.02f * pose.mouthOpen)
            }
            Style.BLINK -> {
                // сова/птица: лёгкое сжатие при моргании
                val sy = 1f - 0.12f * blink
                State(scale = breath * sy, liftY = breathLift)
            }
            Style.EAR_FLAP -> {
                // лёгкое покачивание + реакция на поворот головы
                val flap = 0.10f * sin(2.0 * PI * 0.9 * tSec).toFloat()
                val yawBias = pose.yaw * 0.15f
                State(
                    scale = breath,
                    liftY = breathLift,
                    roll = flap * 0.5f,
                    yawLocal = flap + yawBias,
                )
            }
        }
    }

    /**
     * Локальная анимация в пространстве лица: scale вокруг origin, lift вдоль up,
     * roll вокруг forward, yaw вокруг up. Результат — 4×4 column-major.
     */
    fun localMatrix(st: State): FloatArray {
        val cR = cos(st.roll.toDouble()).toFloat()
        val sR = sin(st.roll.toDouble()).toFloat()
        val cY = cos(st.yawLocal.toDouble()).toFloat()
        val sY = sin(st.yawLocal.toDouble()).toFloat()
        // R = Ry * Rz (yaw around Y, roll around Z) in local model space (X right, Y up, Z forward)
        // column-major 4x4 scale * R + translation (0, liftY, 0)
        val s = st.scale
        return floatArrayOf(
            s * cY * cR,  s * sR,          s * -sY * cR,  0f,
            s * -cY * sR, s * cR,          s * sY * sR,   0f,
            s * sY,       0f,              s * cY,        0f,
            0f,           st.liftY,        0f,            1f,
        )
    }

    /** modelMatrix * localAnim */
    fun applyToPoseMatrix(poseMatrix: FloatArray, st: State): FloatArray {
        return Mat4.multiply(poseMatrix, localMatrix(st))
    }
}
