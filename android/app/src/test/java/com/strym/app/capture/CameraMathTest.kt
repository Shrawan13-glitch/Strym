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

    // --- glFillCropTransform -------------------------------------------------

    @Test
    fun identityWhenBufferMatchesViewExactly() {
        val m = glFillCropTransform(0, 1280, 720, 1280, 720)
        // Buffer top-left → NDC (-1, 1); bottom-right → (1, -1).
        assertEquals(-1f, applyTransform(m, 0f, 0f).first, 1e-4f)
        assertEquals(1f, applyTransform(m, 0f, 0f).second, 1e-4f)
        assertEquals(1f, applyTransform(m, 1280f, 720f).first, 1e-4f)
        assertEquals(-1f, applyTransform(m, 1280f, 720f).second, 1e-4f)
    }

    @Test
    fun portraitUprightsTheLandscapeBufferAndCoversTheView() {
        // Rear sensor mounted 90°, device upright: the 1920x1080 frame must
        // be rotated a quarter turn and fill a tall 1080x2400 window.
        val m = glFillCropTransform(90, 1920, 1080, 1080, 2400)
        // The buffer center lands on the view center.
        assertEquals(0f, applyTransform(m, 960f, 540f).first, 1e-4f)
        assertEquals(0f, applyTransform(m, 960f, 540f).second, 1e-4f)
        // Clockwise quarter turn: the buffer's top-left corner ends up past
        // the right screen edge and flush with the top.
        val (tlX, tlY) = applyTransform(m, 0f, 0f)
        assertTrue("TL x $tlX should overflow right", tlX > 1f)
        assertEquals(1f, tlY, 1e-3f)
        // Full coverage: every view corner is inside the rotated, scaled frame.
        assertCoversView(m, 1080, 2400)
    }

    @Test
    fun landscapeHoldsNeedNoRotation() {
        val m = glFillCropTransform(0, 1280, 720, 2400, 1350)
        assertCoversView(m, 1280, 720)
        // No distortion: one pixel of frame maps to the same distance on
        // every axis of the view.
        assertUniformScale(m, viewWidth = 2400, viewHeight = 1350)
    }

    @Test
    fun reversePortraitRotatesOneEighty() {
        val m = glFillCropTransform(180, 1920, 1080, 1080, 2400)
        // Point reflection: the buffer's top-left lands past the right edge,
        // flush with the bottom.
        val (x, y) = applyTransform(m, 0f, 0f)
        assertTrue("TL x $x should overflow right", x > 1f)
        assertEquals(-1f, y, 1e-3f)
        assertCoversView(m, 1920, 1080)
    }

    @Test
    fun arbitraryAnglesSnapToQuarterTurns() {
        val snapped = glFillCropTransform(270, 1920, 1080, 1080, 2400)
        val exact = glFillCropTransform(-90, 1920, 1080, 1080, 2400)
        assertTrue(snapped.contentEquals(exact))
    }

    @Test
    fun degenerateSizesFallBackToIdentity() {
        val m = glFillCropTransform(90, 0, 720, 1080, 2400)
        val (x, y) = applyTransform(m, 123f, 456f)
        assertEquals(123f, x, 1e-6f)
        assertEquals(456f, y, 1e-6f)
    }

    /** Every corner of the view (in NDC) must be covered by the mapped frame. */
    private fun assertCoversView(m: FloatArray, bufferWidth: Int, bufferHeight: Int) {
        // For axis-aligned 90° mappings it suffices that the transformed
        // buffer quad's bounds contain the whole [-1,1]² view rect.
        val corners = listOf(
            applyTransform(m, 0f, 0f),
            applyTransform(m, bufferWidth.toFloat(), 0f),
            applyTransform(m, 0f, bufferHeight.toFloat()),
            applyTransform(m, bufferWidth.toFloat(), bufferHeight.toFloat()),
        )
        val xs = corners.map { it.first }
        val ys = corners.map { it.second }
        assertTrue("x bounds [$xs] must span the view", xs.min()!! <= -1f + 1e-3 && xs.max()!! >= 1f - 1e-3)
        assertTrue("y bounds [$ys] must span the view", ys.min()!! <= -1f + 1e-3 && ys.max()!! >= 1f - 1e-3)
    }

    /** NDC axes differ in scale (vw ≠ vh), so compare in pixel units. */
    private fun assertUniformScale(m: FloatArray, viewWidth: Int, viewHeight: Int) {
        val scaleX = abs(m[0]) * viewWidth / 2f
        val scaleY = abs(m[5]) * viewHeight / 2f
        assertEquals(scaleX, scaleY, 1e-4f)
        // Axis-aligned: no cross-axis leakage.
        assertTrue(abs(m[4]) * viewWidth / 2f < 1e-6)
        assertTrue(abs(m[1]) * viewHeight / 2f < 1e-6)
    }
}
