package com.strym.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CameraMathTest {

    // --- stQuirkCompensationDegrees -------------------------------------------

    @Test
    fun standardFlipStNeedsNoCompensation() {
        // The Android-standard camera ST: (s,t) -> (s, 1−t), a pure v-flip.
        val st = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, -1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 1f, 0f, 1f,
        )
        assertEquals(0, stQuirkCompensationDegrees(st))
    }

    @Test
    fun identityStNeedsNoCompensation() {
        val st = FloatArray(16)
        st[0] = 1f; st[5] = 1f; st[10] = 1f; st[15] = 1f
        assertEquals(0, stQuirkCompensationDegrees(st))
    }

    @Test
    fun transposingStGetsHalfTurnCompensation() {
        // Dumped on a CPH2613IN (OnePlus): (s,t) -> (1−t, 1−s). Its HAL
        // pre-rotates buffer content, so the standard formula overshoots.
        val st = floatArrayOf(
            0f, -1f, 0f, 0f,
            -1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f,
            1f, 1f, 0f, 1f,
        )
        assertEquals(180, stQuirkCompensationDegrees(st))
    }

    @Test
    fun degenerateStFallsBackToNoCompensation() {
        assertEquals(0, stQuirkCompensationDegrees(FloatArray(16)))
    }

    // --- glFillCropTransform (input: clip-space quad, ±1) --------------------

    @Test
    fun identityWhenBufferMatchesViewExactly() {
        val m = glFillCropTransform(0, 1280, 720, 1280, 720)
        // Quad top-left (−1, +1 in NDC) stays put; bottom-right too.
        assertEquals(-1f, applyTransform(m, -1f, -1f).first, 1e-4f)
        assertEquals(1f, applyTransform(m, -1f, -1f).second, 1e-4f)
        assertEquals(1f, applyTransform(m, 1f, 1f).first, 1e-4f)
        assertEquals(-1f, applyTransform(m, 1f, 1f).second, 1e-4f)
    }

    @Test
    fun portraitUprightsTheLandscapeBufferAndCoversTheView() {
        // Rear sensor mounted 90°, device upright: the 1920x1080 frame must
        // be rotated a quarter turn and fill a tall 1080x2412 window.
        val m = glFillCropTransform(90, 1920, 1080, 1080, 2412)
        // The frame center lands on the view center.
        assertEquals(0f, applyTransform(m, 0f, 0f).first, 1e-4f)
        assertEquals(0f, applyTransform(m, 0f, 0f).second, 1e-4f)
        // Clockwise quarter turn: the buffer's top-left corner (quad −1,−1)
        // ends up past the right screen edge and flush with the top.
        val (tlX, tlY) = applyTransform(m, -1f, -1f)
        assertTrue("TL x $tlX should overflow right", tlX > 1f)
        assertEquals(1f, tlY, 1e-3f)
        // Full coverage: the rotated frame spans the whole view.
        assertCoversView(m)
    }

    @Test
    fun landscapeHoldsNeedNoRotation() {
        val m = glFillCropTransform(0, 1280, 720, 2400, 1350)
        assertCoversView(m)
        // No distortion: view pixels per buffer pixel match on both axes.
        assertUniformScale(m, bufferWidth = 1280, bufferHeight = 720, viewWidth = 2400, viewHeight = 1350)
    }

    @Test
    fun reversePortraitRotatesOneEighty() {
        val m = glFillCropTransform(180, 1920, 1080, 1080, 2412)
        // Point reflection: the buffer's top-left lands past the right edge,
        // flush with the bottom.
        val (x, y) = applyTransform(m, -1f, -1f)
        assertTrue("TL x $x should overflow right", x > 1f)
        assertEquals(-1f, y, 1e-3f)
        assertCoversView(m)
    }

    @Test
    fun arbitraryAnglesSnapToQuarterTurns() {
        val snapped = glFillCropTransform(270, 1920, 1080, 1080, 2412)
        val exact = glFillCropTransform(-90, 1920, 1080, 1080, 2412)
        assertTrue(snapped.contentEquals(exact))
    }

    @Test
    fun degenerateSizesFallBackToIdentity() {
        val m = glFillCropTransform(90, 0, 720, 1080, 2412)
        val (x, y) = applyTransform(m, -0.25f, 0.75f)
        assertEquals(-0.25f, x, 1e-6f)
        assertEquals(0.75f, y, 1e-6f)
    }

    /** The transformed quad must span the whole [-1,1]² view rect in NDC. */
    private fun assertCoversView(m: FloatArray) {
        val corners = listOf(
            applyTransform(m, -1f, -1f),
            applyTransform(m, 1f, -1f),
            applyTransform(m, -1f, 1f),
            applyTransform(m, 1f, 1f),
        )
        val xs = corners.map { it.first }
        val ys = corners.map { it.second }
        assertTrue("x bounds $xs must span the view", xs.min()!! <= -1f + 1e-3 && xs.max()!! >= 1f - 1e-3)
        assertTrue("y bounds $ys must span the view", ys.min()!! <= -1f + 1e-3 && ys.max()!! >= 1f - 1e-3)
    }

    /** View pixels per buffer pixel must match on both axes (no distortion). */
    private fun assertUniformScale(m: FloatArray, bufferWidth: Int, bufferHeight: Int, viewWidth: Int, viewHeight: Int) {
        val scaleX = abs(m[0]) * viewWidth / bufferWidth
        val scaleY = abs(m[5]) * viewHeight / bufferHeight
        assertEquals(scaleX, scaleY, 1e-4f)
        // Axis-aligned: no cross-axis leakage.
        assertTrue(abs(m[4]) * viewWidth / bufferHeight < 1e-6)
        assertTrue(abs(m[1]) * viewHeight / bufferWidth < 1e-6)
    }
}
