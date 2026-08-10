package com.strym.app.ui.live

import org.junit.Assert.assertEquals
import org.junit.Test

class StatFormatTest {

    @Test
    fun bitrateFormatting() {
        assertEquals("0 bps", formatBitrate(0.0))
        assertEquals("800 bps", formatBitrate(800.0))
        assertEquals("900 kbps", formatBitrate(900_000.0))
        assertEquals("1.23 Mbps", formatBitrate(1_234_567.0))
        assertEquals("8.00 Mbps", formatBitrate(8_000_000.0))
    }

    @Test
    fun dropRatioFormatting() {
        assertEquals("0.0%", formatDropRatio(0.0))
        assertEquals("0.3%", formatDropRatio(0.003))
        assertEquals("12.5%", formatDropRatio(0.125))
    }

    @Test
    fun lagFormatting() {
        assertEquals("0 ms", formatLagMs(-5))
        assertEquals("850 ms", formatLagMs(850))
        assertEquals("1.5 s", formatLagMs(1_500))
    }

    @Test
    fun rttFormatting() {
        assertEquals("–", formatRtt(null))
        assertEquals("42 ms", formatRtt(42.0))
    }

    @Test
    fun uptimeFormatting() {
        assertEquals("0:00", formatUptime(0))
        assertEquals("0:59", formatUptime(59_000))
        assertEquals("12:34", formatUptime(754_000))
        assertEquals("1:02:03", formatUptime(3_723_000))
    }
}
