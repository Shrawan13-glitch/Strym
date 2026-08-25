package com.strym.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CameraMathTest {

    // --- choosePreviewSize --------------------------------------------------

    @Test
    fun picksLargestExactAspectMatchWithinBudget() {
        val sizes = listOf(
            640 to 480,
            1920 to 1080,
            1280 to 720,
        )
        assertEquals(1920 to 1080, choosePreviewSize(sizes, 16f / 9f))
    }

    @Test
    fun maxWidthClampsToTheBestSupportedCandidate() {
        val sizes = listOf(
            640 to 480,
            854 to 480,
            1280 to 720,
        )
        assertEquals(854 to 480, choosePreviewSize(sizes, 16f / 9f, maxWidth = 1000))
    }

    @Test
    fun fallsBackToClosestAspectWhenNothingIsInRange() {
        val sizes = listOf(320 to 240, 640 to 360)
        assertEquals(640 to 360, choosePreviewSize(sizes, 16f / 9f, maxWidth = 400))
    }

    @Test
    fun emptyOrDegenerateInputsReturnNull() {
        assertNull(choosePreviewSize(emptyList(), 16f / 9f))
        assertNull(choosePreviewSize(listOf(1280 to 720), 0f))
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

    // --- glBufferSamplingTransform (input: clip-space quad, ±1) --------------

    @Test
    fun samplingMatchesGeometryForIdentity() {
        // No rotation, equal dims: screen bottom-left samples buffer bottom-left.
        val m = glBufferSamplingTransform(0, 1280, 720, 1280, 720)
        assertEquals(0f, applyTransform(m, -1f, -1f).first, 1e-5f)
        assertEquals(1f, applyTransform(m, -1f, -1f).second, 1e-5f)
        assertEquals(1f, applyTransform(m, 1f, 1f).first, 1e-5f)
        assertEquals(0f, applyTransform(m, 1f, 1f).second, 1e-5f)
        assertEquals(0.5f, applyTransform(m, 0f, 0f).first, 1e-5f)
        assertEquals(0.5f, applyTransform(m, 0f, 0f).second, 1e-5f)
    }

    @Test
    fun samplingRotatesAgainstTheGeometry() {
        // Portrait hold, sensor 90: the quad's corners land at rotated screen
        // positions, so their sample points must come from the correspondingly
        // rotated buffer corners (screen top-right ← buffer top-right).
        val m = glBufferSamplingTransform(90, 1920, 1080, 1080, 2412)
        val (x, y) = applyTransform(m, 1f, -1f)
        assertEquals(1.0f, x, 1e-2f)
        assertEquals(0.102f, y, 1e-2f)
        // Center always samples the buffer center.
        assertEquals(0.5f, applyTransform(m, 0f, 0f).first, 1e-5f)
        assertEquals(0.5f, applyTransform(m, 0f, 0f).second, 1e-5f)
    }

    @Test
    fun degenerateSamplingFallsBackToIdentity() {
        val m = glBufferSamplingTransform(90, 0, 720, 1080, 2412)
        assertEquals(-0.25f, applyTransform(m, -0.25f, 0.75f).first, 1e-6f)
        assertEquals(0.75f, applyTransform(m, -0.25f, 0.75f).second, 1e-6f)
    }

    // --- invertRigidTransform -------------------------------------------------

    @Test
    fun rigidInverseUndoesTheTransform() {
        // The camera ST observed on device: (s,t) = (1−v, 1−u).
        val st = floatArrayOf(
            0f, -1f, 0f, 0f,
            -1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f,
            1f, 1f, 0f, 1f,
        )
        val inv = invertRigidTransform(st)
        for (uv in listOf(0f to 0f, 1f to 0f, 0.3f to 0.7f, 1f to 1f)) {
            val through = applyTransform(st, uv.first, uv.second)
            val back = applyTransform(inv, through.first, through.second)
            assertEquals(uv.first, back.first, 1e-5f)
            assertEquals(uv.second, back.second, 1e-5f)
        }
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
