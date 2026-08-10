package com.strym.app.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPtsTest {

    @Test
    fun firstFrameIsAlwaysZero() {
        assertEquals(0L, VideoPts(30.0).next(123_456L))
        assertEquals(0L, VideoPts(30.0).next(0L))
    }

    @Test
    fun clockModeRebasesToStreamTime() {
        val pts = VideoPts(30.0)
        pts.next(1_000_000L) // origin
        assertEquals(0L, pts.next(1_000_000L))
        assertEquals(500L, pts.next(1_500_000L))
        assertEquals(1_000L, pts.next(2_000_000L))
    }

    @Test
    fun fallbackModeUsesNominalRateWhenUnstamped() {
        val pts = VideoPts(30.0)
        assertEquals(0L, pts.next(0L))
        assertEquals(33L, pts.next(0L))
        assertEquals(66L, pts.next(0L))
        assertEquals(100L, pts.next(0L))
    }

    @Test
    fun clockModeHoldsMonotonicWhenStampsDropToZero() {
        val pts = VideoPts(30.0)
        pts.next(1_000_000L)
        pts.next(2_000_000L) // 1000 ms
        assertEquals(1_000L, pts.next(0L))
        assertEquals(1_000L, pts.next(-5L))
    }

    @Test
    fun clockModeClampsBackwardsJumps() {
        val pts = VideoPts(30.0)
        pts.next(1_000_000L)
        assertEquals(1_000L, pts.next(2_000_000L))
        assertEquals(1_000L, pts.next(1_500_000L)) // backwards → held
    }

    @Test
    fun fallbackStaysMonotonicAtLowRates() {
        val pts = VideoPts(1.0)
        assertEquals(0L, pts.next(0L))
        assertEquals(1_000L, pts.next(0L))
        assertEquals(2_000L, pts.next(0L))
    }

    @Test
    fun resetRestartsTheClock() {
        val pts = VideoPts(30.0)
        pts.next(1_000_000L)
        pts.next(2_000_000L)
        pts.reset()
        assertEquals(0L, pts.next(9_000_000L))
        assertEquals(100L, pts.next(9_100_000L))
    }
}
