package com.strym.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamAspectTest {

    @Test
    fun outputSizesFollowTheShortSide() {
        assertEquals(1280 to 720, StreamAspect.LANDSCAPE_16_9.outputSize(720))
        assertEquals(1920 to 1080, StreamAspect.LANDSCAPE_16_9.outputSize(1080))
        assertEquals(720 to 1280, StreamAspect.PORTRAIT_9_16.outputSize(720))
        assertEquals(1080 to 1920, StreamAspect.PORTRAIT_9_16.outputSize(1080))
        assertEquals(960 to 720, StreamAspect.CLASSIC_4_3.outputSize(720))
        assertEquals(1440 to 1080, StreamAspect.CLASSIC_4_3.outputSize(1080))
        assertEquals(720 to 720, StreamAspect.SQUARE_1_1.outputSize(720))
        assertEquals(1080 to 1080, StreamAspect.SQUARE_1_1.outputSize(1080))
    }

    @Test
    fun outputSizesAreAlwaysEven() {
        StreamAspect.entries.forEach { aspect ->
            val (width, height) = aspect.outputSize(721)
            assertEquals(aspect.label, 0, width % 2)
            assertEquals(aspect.label, 0, height % 2)
        }
    }
}
