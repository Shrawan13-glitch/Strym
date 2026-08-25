package com.strym.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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

    // --- computePreviewTransform --------------------------------------------

    @Test
    fun portraitRotatesTheLandscapeBufferUpright() {
        // Rear sensor mounted 90°; device upright (display rotation 0°).
        // 720x1280 footprint in 1080x2400 view → fill scale 1.875 (fills height).
        val transform = computePreviewTransform(90, 0, 1280, 720, 1080, 2400)
        assertEquals(90, transform.rotationDegrees)
        assertEquals(1.875f, transform.scale, 1e-5f)
    }

    @Test
    fun landscapeNeedsNoRotation() {
        // 1280x720 footprint in 2400x1080 view → fill scale 1.875 (fills width).
        val transform = computePreviewTransform(90, 90, 1280, 720, 2400, 1080)
        assertEquals(0, transform.rotationDegrees)
        assertEquals(1.875f, transform.scale, 1e-5f)
    }

    @Test
    fun reversePortraitRotatesOneEighty() {
        val transform = computePreviewTransform(90, 180, 1280, 720, 1080, 2400)
        assertEquals(270, transform.rotationDegrees)
        assertEquals(1.875f, transform.scale, 1e-5f)
    }

    @Test
    fun fillScaleUsesTheBindingAxis() {
        // Buffer already matches the view's aspect: uniform 1:1.
        val transform = computePreviewTransform(0, 0, 1280, 720, 1280, 720)
        assertEquals(0, transform.rotationDegrees)
        assertEquals(1f, transform.scale, 1e-5f)
    }

    @Test
    fun degenerateSizesFallBackToIdentity() {
        val transform = computePreviewTransform(90, 0, 0, 720, 1080, 2400)
        assertEquals(PreviewTransform(0, 1f), transform)
    }

    // --- largestCrop ---------------------------------------------------------

    @Test
    fun sensorCropMatchesTheStreamAspect() {
        // 4:3 sensor array → largest centered 16:9 region.
        val crop = largestCrop(4032, 3024, 16f / 9f)
        assertEquals(CropRect(0, 378, 4032, 2268), crop)
    }

    @Test
    fun fullFrameWhenAspectsAlreadyMatch() {
        assertEquals(CropRect(0, 0, 1920, 1080), largestCrop(1920, 1080, 16f / 9f))
    }

    @Test
    fun degenerateAspectReturnsTheWholeArray() {
        assertEquals(CropRect(0, 0, 1000, 500), largestCrop(1000, 500, 0f))
    }
}
