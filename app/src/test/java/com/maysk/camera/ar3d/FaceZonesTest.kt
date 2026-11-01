package com.maysk.camera.ar3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Синтетическое лицо: проверяем, что зоны лежат там, где должны (эталон — тот же алгоритм на Python). */
class FaceZonesTest {
    private val cx = 0.5f
    private val cy = 0.45f

    private fun face(mouthOpen: Boolean = true): MutableList<FloatArray> {
        val m = MutableList(468) { floatArrayOf(0.5f, 0.5f, 0f) }
        fun ellipse(idx: IntArray, x: Float, y: Float, rx: Float, ry: Float, start: Double = 0.0) {
            for ((k, i) in idx.withIndex()) {
                val a = 2 * PI * k / idx.size + start
                m[i] = floatArrayOf(x + rx * cos(a).toFloat(), y + ry * sin(a).toFloat(), 0f)
            }
        }
        ellipse(FaceZones.OVAL, cx, cy, 0.22f, 0.19f, -PI / 2)
        ellipse(FaceZones.LIP_OUT, cx, cy + 0.10f, 0.07f, 0.040f)
        ellipse(FaceZones.LIP_IN, cx, cy + 0.10f, 0.05f, if (mouthOpen) 0.022f else 0.001f)
        ellipse(FaceZones.EYE_A, cx - 0.08f, cy - 0.04f, 0.035f, 0.012f, PI)
        ellipse(FaceZones.EYE_B, cx + 0.08f, cy - 0.04f, 0.035f, 0.012f)
        ellipse(FaceZones.BROW_A, cx - 0.08f, cy - 0.085f, 0.04f, 0.008f)
        ellipse(FaceZones.BROW_B, cx + 0.08f, cy - 0.085f, 0.04f, 0.008f)
        for ((i, idx) in FaceZones.LID_A.withIndex()) {
            val t = i / (FaceZones.LID_A.size - 1f)
            m[idx] = floatArrayOf(cx - 0.115f + 0.07f * t, cy - 0.04f - 0.012f * sin(PI.toFloat() * t), 0f)
        }
        for ((i, idx) in FaceZones.LID_B.withIndex()) {
            val t = i / (FaceZones.LID_B.size - 1f)
            m[idx] = floatArrayOf(cx + 0.115f - 0.07f * t, cy - 0.04f - 0.012f * sin(PI.toFloat() * t), 0f)
        }
        m[10] = floatArrayOf(cx, cy - 0.19f, 0f); m[152] = floatArrayOf(cx, cy + 0.19f, 0f)
        m[50] = floatArrayOf(cx - 0.12f, cy + 0.02f, 0f); m[205] = floatArrayOf(cx - 0.12f, cy + 0.04f, 0f)
        m[280] = floatArrayOf(cx + 0.12f, cy + 0.02f, 0f); m[425] = floatArrayOf(cx + 0.12f, cy + 0.04f, 0f)
        return m
    }

    private fun at(buf: ByteArray, x: Float, y: Float, ch: Int): Int =
        buf[((y * FaceZones.H).toInt() * FaceZones.W + (x * FaceZones.W).toInt()) * 4 + ch].toInt() and 0xFF

    @Test fun skinIsOnCheeksAndForeheadButNotOnEyesBrowsLips() {
        val b = FaceZones.build(listOf(face()), 0).rgba
        assertTrue(at(b, cx - 0.12f, cy + 0.03f, 0) > 200)
        assertTrue(at(b, cx, cy - 0.13f, 0) > 200)
        assertTrue(at(b, cx - 0.08f, cy - 0.04f, 0) < 40)
        assertTrue(at(b, cx - 0.08f, cy - 0.085f, 0) < 90)
        assertTrue(at(b, cx + 0.05f, cy + 0.10f, 0) < 40)
        assertEquals(0, at(b, 0.05f, 0.05f, 0))
    }

    @Test fun lipsAreARingAndMouthInteriorIsWhiteZone() {
        val b = FaceZones.build(listOf(face()), 0).rgba
        assertTrue(at(b, cx + 0.06f, cy + 0.10f, 1) > 200)
        assertTrue(at(b, cx, cy + 0.10f, 1) < 40)
        assertTrue(at(b, cx, cy + 0.10f, 3) > 150)     // зубы
        assertTrue(at(b, cx - 0.08f, cy - 0.04f, 3) > 150) // белок глаза
    }

    @Test fun eyeshadowSitsAboveEyeOnly() {
        val b = FaceZones.build(listOf(face()), 0).rgba
        val above = listOf(0.002f, 0.006f, 0.010f, 0.014f).maxOf { at(b, cx - 0.08f, cy - 0.047f - it, 2) }
        assertTrue(above > 150)
        assertEquals(0, at(b, cx - 0.12f, cy + 0.03f, 2))
    }

    @Test fun anchorsOnlyForPrimaryFace() {
        val r = FaceZones.build(listOf(face()), 0)
        val a = r.anchors!!
        assertEquals(cx, a.mouthX, 0.01f)
        assertEquals(cy + 0.10f, a.mouthY, 0.02f)
        assertTrue(a.radius > 0.03f && a.radius < 0.10f)
        assertTrue(FaceZones.build(listOf(face()), 5).anchors == null)
    }

    @Test fun shortMeshIsIgnored() {
        val r = FaceZones.build(listOf(List(100) { floatArrayOf(0.5f, 0.5f, 0f) }), 0)
        assertTrue(r.rgba.all { it.toInt() == 0 })
        assertTrue(r.anchors == null)
    }
}
