package com.maysk.camera.ar3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Перевод координат ML Kit → экран и поза головы. */
class FaceTransformTest {

    /**
     * Синтетическая сетка: ключевые точки лица.
     * [eyeOpenAmt] 0 = закрыты, 1 = открыты (зазор век).
     */
    private fun mesh(
        cx: Float = 0.5f, cy: Float = 0.45f, eye: Float = 0.2f,
        yaw: Float = 0f, roll: Float = 0f, mouth: Float = 0f,
        eyeOpenAmt: Float = 1f,
    ): List<FloatArray> {
        val v = MutableList(468) { floatArrayOf(cx, cy, 0f) }
        fun put(i: Int, x: Float, y: Float, z: Float) {
            val xr = x * cos(yaw) + z * sin(yaw); val zr = -x * sin(yaw) + z * cos(yaw)
            val xx = xr * cos(roll) - y * sin(roll); val yy = xr * sin(roll) + y * cos(roll)
            v[i] = floatArrayOf(cx + xx * eye, cy + yy * eye * 0.75f, zr * eye)
        }
        put(33, -0.5f, 0f, 0f); put(263, 0.5f, 0f, 0f)
        put(10, 0f, -0.8f, -0.1f); put(152, 0f, 1.3f, -0.05f)
        put(13, 0f, 0.85f, -0.3f); put(14, 0f, 0.85f + mouth, -0.3f)
        // веки: зазор пропорционален eyeOpenAmt
        val gap = 0.02f + 0.06f * eyeOpenAmt
        put(FaceTransform.LEFT_EYE_TOP, -0.35f, -gap, 0.02f)
        put(FaceTransform.LEFT_EYE_BOTTOM, -0.35f, gap, 0.02f)
        put(FaceTransform.RIGHT_EYE_TOP, 0.35f, -gap, 0.02f)
        put(FaceTransform.RIGHT_EYE_BOTTOM, 0.35f, gap, 0.02f)
        return v
    }

    @Test fun frontalBackCamera() {
        val map = ViewMapping(viewAspect = 0.75f, mirror = false)
        val p = assertNotNullPose(FaceTransform.pose(mesh(), map))
        assertEquals(1f, p.right[0], 1e-4f)
        assertEquals(-1f, p.up[1], 2e-3f)
        assertEquals(-1f, p.forward[2], 2e-3f)
        assertEquals(0.2f * 0.75f, p.scale, 1e-4f)
        assertEquals(0.5f * 0.75f, p.origin[0], 1e-4f)
        assertTrue("eyeOpen should be high when open", p.eyeOpen > 0.5f)
        assertTrue("faceRatio in sane range", p.faceRatio in 1.4f..3.0f)
    }

    @Test fun mirroredFrontCameraKeepsRightHandedPose() {
        val map = ViewMapping(viewAspect = 0.75f, mirror = true)
        val p = assertNotNullPose(FaceTransform.pose(mesh(cx = 0.3f), map))
        assertEquals(1f, p.right[0], 1e-4f)
        assertEquals(-1f, p.forward[2], 2e-3f)
        assertTrue(p.origin[0] > 0.375f)
        val c = FaceTransform.cross(p.right, p.up)
        for (k in 0 until 3) assertEquals(p.forward[k], c[k], 1e-4f)
    }

    @Test fun yawTurnsForwardSideways() {
        val p = assertNotNullPose(FaceTransform.pose(mesh(yaw = 0.5f), ViewMapping(0.75f, false)))
        assertTrue("нос должен смотреть вбок", Math.abs(p.forward[0]) > 0.3f)
        assertEquals(0f, FaceTransform.dot(p.right, p.forward), 1e-4f)
    }

    @Test fun rollFollowsHeadTilt() {
        val p = assertNotNullPose(FaceTransform.pose(mesh(roll = 0.3f), ViewMapping(0.75f, false)))
        assertEquals(sin(0.3f), p.right[1], 1e-3f)
    }

    @Test fun cropToTallScreen() {
        val a = 9f / 19.5f
        val out = FloatArray(3)
        ViewMapping(a, false).toView(0.5f, 0.5f, 0f, out)
        assertEquals(a / 2, out[0], 1e-5f)
        assertEquals(0.5f, out[1], 1e-5f)
        val o1 = FloatArray(3); val o2 = FloatArray(3)
        ViewMapping(a, false).toView(0.5f + 0.01f, 0.5f, 0f, o1)
        ViewMapping(a, false).toView(0.5f, 0.5f + 0.0075f, 0f, o2)
        assertEquals(o1[0] - a / 2, o2[1] - 0.5f, 1e-5f)
    }

    @Test fun mouthOpenDetected() {
        val closed = FaceTransform.pose(mesh(mouth = 0f), ViewMapping(0.75f, false))!!
        val open = FaceTransform.pose(mesh(mouth = 0.4f), ViewMapping(0.75f, false))!!
        assertEquals(0f, closed.mouthOpen, 1e-4f)
        assertTrue(open.mouthOpen > 0.5f)
    }

    @Test fun eyeOpenDetected() {
        val open = FaceTransform.pose(mesh(eyeOpenAmt = 1f), ViewMapping(0.75f, false))!!
        val shut = FaceTransform.pose(mesh(eyeOpenAmt = 0f), ViewMapping(0.75f, false))!!
        assertTrue("open eyes", open.eyeOpen > shut.eyeOpen)
        assertTrue(shut.eyeOpen < 0.45f)
    }

    @Test fun degenerateMeshRejected() {
        assertNull(FaceTransform.pose(List(468) { floatArrayOf(0.5f, 0.5f, 0f) }, ViewMapping(0.75f, false)))
        assertNull(FaceTransform.pose(emptyList(), ViewMapping(0.75f, false)))
    }

    @Test fun poseMatrixMatchesApply() {
        val p = FaceTransform.pose(mesh(yaw = 0.3f, roll = 0.2f), ViewMapping(0.75f, false))!!
        val m = p.matrix(); val a = FloatArray(3); val b = FloatArray(3)
        p.apply(0.3f, -0.5f, 0.4f, a, 0)
        Mat4.transformPoint(m, floatArrayOf(0.3f, -0.5f, 0.4f), 0, b, 0)
        for (k in 0 until 3) assertEquals(a[k], b[k], 1e-5f)
    }

    @Test fun trackerSmoothsHoldsAndForgets() {
        val map = ViewMapping(0.75f, false)
        val t = FaceTracker(holdMs = 400)
        val p0 = FaceTransform.pose(mesh(), map)!!
        val c = map.boxCenter(0.5f, 0.45f)
        assertNotNull(t.update(p0, c, 0.1f, 1000))
        val jitter = FaceTransform.pose(mesh(cx = 0.505f), map)!!
        val s = t.update(jitter, c, 0.1f, 1033)!!
        assertTrue(s.origin[0] < jitter.origin[0] && s.origin[0] > p0.origin[0])
        assertNotNull(t.update(null, null, 0f, 1200))
        assertNull(t.update(null, null, 0f, 1600))
    }

    @Test fun trackerFollowsBoxBetweenMeshes() {
        val map = ViewMapping(0.75f, false)
        val t = FaceTracker(minCutoff = 1000f)
        val p0 = FaceTransform.pose(mesh(), map)!!
        t.update(p0, map.boxCenter(0.5f, 0.45f), 0.1f, 1000)
        val moved = t.update(null, map.boxCenter(0.6f, 0.45f), 0.1f, 1033)!!
        assertEquals(p0.origin[0] + 0.1f * 0.75f, moved.origin[0], 2e-3f)
    }

    private fun assertNotNullPose(p: FacePose?): FacePose { assertNotNull(p); return p!! }
}
