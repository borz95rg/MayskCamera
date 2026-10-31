package com.maysk.camera.ar3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MeshCalibrationTest {
    /** Синтетическая сетка: глаза, нос, рот в «правильном» кадре (доли 0..1). */
    private fun mesh(dx: Float, s: Float): List<FloatArray> {
        val v = Array(468) { floatArrayOf(0.5f, 0.5f, 0f) }
        fun put(i: Int, x: Float, y: Float) { v[i] = floatArrayOf(s * x + dx, s * y, 0.01f) }
        for (i in MeshCalibration.EYE_A) put(i, 0.42f, 0.40f)
        for (i in MeshCalibration.EYE_B) put(i, 0.58f, 0.40f)
        put(33, 0.38f, 0.40f); put(263, 0.62f, 0.40f)
        put(133, 0.46f, 0.40f); put(362, 0.54f, 0.40f)
        put(MeshCalibration.NOSE_BASE, 0.50f, 0.50f)
        put(MeshCalibration.MOUTH_BOTTOM, 0.50f, 0.60f)
        put(MeshCalibration.MOUTH_A, 0.45f, 0.57f); put(MeshCalibration.MOUTH_B, 0.55f, 0.57f)
        return v.toList()
    }

    private val truth = MeshCalibration.Landmarks(
        eyes = listOf(floatArrayOf(0.58f, 0.40f), floatArrayOf(0.42f, 0.40f)),   // порядок «левый/правый» не важен
        nose = floatArrayOf(0.50f, 0.50f),
        mouthBottom = floatArrayOf(0.50f, 0.60f),
        mouthCorners = listOf(floatArrayOf(0.55f, 0.57f), floatArrayOf(0.45f, 0.57f)),
        boxCenter = floatArrayOf(0.5f, 0.5f), boxWidth = 0.3f,
    )

    @Test fun shiftedMeshIsMovedOntoDetectorLandmarks() {
        // как на устройстве: сетка сдвинута по x на +0.25 при верных y и масштабе
        val out = MeshCalibration.calibrate(mesh(0.25f, 1f), truth)
        assertNotNull(out)
        val eyeA = out!![159]   // верхнее веко = центр глаза по x
        assertEquals(0.42f, eyeA[0], 1e-3f)
        assertEquals(0.40f, eyeA[1], 1e-3f)
        assertEquals(0.50f, out[MeshCalibration.NOSE_BASE][0], 1e-3f)
    }

    @Test fun scaleIsRecovered() {
        val f = MeshCalibration.fit(mesh(0.1f, 0.8f), truth)!!
        assertEquals(1.25f, f.s, 1e-3f)
        assertEquals(0f, f.residual, 1e-4f)
        // z масштабируется тем же коэффициентом
        val out = MeshCalibration.apply(mesh(0.1f, 0.8f), f)
        assertEquals(0.0125f, out[MeshCalibration.NOSE_BASE][2], 1e-5f)
    }

    @Test fun alreadyAlignedMeshIsUnchanged() {
        val f = MeshCalibration.fit(mesh(0f, 1f), truth)!!
        assertEquals(1f, f.s, 1e-4f); assertEquals(0f, f.tx, 1e-4f); assertEquals(0f, f.ty, 1e-4f)
    }

    @Test fun boxFallbackWithoutLandmarks() {
        val lm = MeshCalibration.Landmarks(eyes = emptyList(), boxCenter = floatArrayOf(0.3f, 0.5f), boxWidth = 0.24f)
        val out = MeshCalibration.calibrate(mesh(0.2f, 1f), lm)
        assertNotNull(out)
        // центр сетки уходит в центр рамки
        var minX = 9f; var maxX = -9f
        for (v in out!!) { minX = minOf(minX, v[0]); maxX = maxOf(maxX, v[0]) }
        assertEquals(0.3f, (minX + maxX) / 2, 1e-3f)
    }

    @Test fun wrongFaceIsRejected() {
        // ориентиры другого лица, у которого рот выше глаз — подобие не сходится
        val other = MeshCalibration.Landmarks(
            eyes = listOf(floatArrayOf(0.1f, 0.9f), floatArrayOf(0.15f, 0.2f)),
            nose = floatArrayOf(0.9f, 0.1f), mouthBottom = floatArrayOf(0.12f, 0.05f),
        )
        assertNull(MeshCalibration.calibrate(mesh(0f, 1f), other))
    }
}
