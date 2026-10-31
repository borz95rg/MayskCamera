package com.maysk.camera.ai

import kotlin.math.atan2
import kotlin.math.hypot

/** Stable attachment frame derived from ML Kit's 468-point face mesh.
 * Coordinates are normalized to the analyzed image; z is retained in model units.
 * A GLB renderer can bind its local +X/+Y/+Z axes to this frame.
 */
data class FaceMeshAnchors(
    val centerX: Float,
    val centerY: Float,
    val centerZ: Float,
    val width: Float,
    val height: Float,
    val rollRadians: Float,
    val yawRadians: Float,
    val leftEye: FloatArray,
    val rightEye: FloatArray,
    val noseTip: FloatArray,
    val forehead: FloatArray,
    val chin: FloatArray,
)

object FaceMeshAnchorSolver {
    // Canonical MediaPipe/ML Kit face-mesh landmark indices.
    private const val LEFT_EYE_OUTER = 33
    private const val RIGHT_EYE_OUTER = 263
    private const val NOSE_TIP = 1
    private const val FOREHEAD = 10
    private const val CHIN = 152

    fun solve(mesh: FaceMeshInfo): FaceMeshAnchors? {
        val v = mesh.vertices
        fun point(i: Int): FloatArray? = v.getOrNull(i)?.takeIf { it.size >= 3 }
        val le = point(LEFT_EYE_OUTER) ?: return null
        val re = point(RIGHT_EYE_OUTER) ?: return null
        val nose = point(NOSE_TIP) ?: return null
        val forehead = point(FOREHEAD) ?: return null
        val chin = point(CHIN) ?: return null
        val dx = re[0] - le[0]
        val dy = re[1] - le[1]
        val eyeDistance = hypot(dx, dy).coerceAtLeast(0.001f)
        val dz = re[2] - le[2]
        val yaw = atan2(dz, eyeDistance)
        return FaceMeshAnchors(
            centerX = (le[0] + re[0]) * 0.5f,
            centerY = (le[1] + re[1]) * 0.5f,
            centerZ = (le[2] + re[2] + nose[2]) / 3f,
            width = eyeDistance * 2.25f,
            height = hypot(chin[0] - forehead[0], chin[1] - forehead[1]).coerceAtLeast(0.001f),
            rollRadians = atan2(dy, dx),
            yawRadians = yaw,
            leftEye = le,
            rightEye = re,
            noseTip = nose,
            forehead = forehead,
            chin = chin,
        )
    }
}
