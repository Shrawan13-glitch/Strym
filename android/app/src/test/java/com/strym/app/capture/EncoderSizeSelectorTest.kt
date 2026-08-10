package com.strym.app.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class EncoderSizeSelectorTest {

    private val widths = EncoderSizeSelector.Range(128, 1920)
    private val heights = EncoderSizeSelector.Range(96, 1080)

    @Test
    fun presetWithinRangeIsKept() {
        assertEquals(1280 to 720, EncoderSizeSelector.choose(1280, 720, widths, heights))
        assertEquals(1920 to 1080, EncoderSizeSelector.choose(1920, 1080, widths, heights))
    }

    @Test
    fun oversizedPresetClampsToMaximum() {
        assertEquals(1920 to 1080, EncoderSizeSelector.choose(3840, 2160, widths, heights))
    }

    @Test
    fun undersizedPresetClampsToMinimum() {
        assertEquals(128 to 96, EncoderSizeSelector.choose(64, 48, widths, heights))
    }

    @Test
    fun oddSizesFloorToEven() {
        val oddMax = EncoderSizeSelector.Range(121, 1921)
        val oddMaxH = EncoderSizeSelector.Range(97, 1081)
        assertEquals(1280 to 720, EncoderSizeSelector.choose(1281, 721, oddMax, oddMaxH))
    }

    @Test
    fun oddMinimumIsKeptWhenFloorWouldUndershoot() {
        val oddMin = EncoderSizeSelector.Range(121, 1920)
        assertEquals(121, EncoderSizeSelector.choose(121, 720, oddMin, heights).first)
    }
}
