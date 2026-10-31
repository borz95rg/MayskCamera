package com.maysk.camera.ar3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAnimTest {
    private val pose = FacePose(
        origin = floatArrayOf(0.4f, 0.5f, 0f),
        right = floatArrayOf(1f, 0f, 0f),
        up = floatArrayOf(0f, -1f, 0f),
        forward = floatArrayOf(0f, 0f, -1f),
        scale = 0.2f,
        mouthOpen = 0.5f,
        faceRatio = 2.1f,
        eyeOpen = 1f,
        yaw = 0.2f,
    )

    @Test fun stylesResolvedFromId() {
        assertEquals(ModelAnim.Style.PULSE, ModelAnim.styleFor("cat_pulse"))
        assertEquals(ModelAnim.Style.SPIN, ModelAnim.styleFor("fantasy_spin"))
        assertEquals(ModelAnim.Style.MOUTH, ModelAnim.styleFor("lion_roar"))
        assertEquals(ModelAnim.Style.EAR_FLAP, ModelAnim.styleFor("fox"))
        assertEquals(ModelAnim.Style.BOB, ModelAnim.styleFor("crown"))
    }

    @Test fun catalogAnimMapping() {
        assertEquals(ModelAnim.Style.SWAY, ModelAnim.styleFromCatalog("sway"))
        assertEquals(ModelAnim.Style.BREATH, ModelAnim.styleFromCatalog(null))
    }

    @Test fun evaluateProducesStableMatrix() {
        val st = ModelAnim.evaluate("bunny_bob", 1.25f, pose)
        assertTrue(st.scale > 0.5f && st.scale < 1.5f)
        val m = ModelAnim.applyToPoseMatrix(pose.matrix(), st)
        assertEquals(16, m.size)
        // last row of column-major affine should stay 0,0,0,1
        assertEquals(0f, m[3], 1e-5f)
        assertEquals(0f, m[7], 1e-5f)
        assertEquals(0f, m[11], 1e-5f)
        assertEquals(1f, m[15], 1e-5f)
    }

    @Test fun mouthStyleGrowsWithMouthOpen() {
        val closed = pose.copyMouth(0f)
        val open = pose.copyMouth(1f)
        val a = ModelAnim.evaluate("dog_mouth", 0f, closed)
        val b = ModelAnim.evaluate("dog_mouth", 0f, open)
        assertTrue(b.scale > a.scale)
    }

    private fun FacePose.copyMouth(m: Float) = FacePose(
        origin, right, up, forward, scale, m, faceRatio, eyeOpen, yaw,
    )
}
