package com.strym.app.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPtsTest {

    // SystemClock is unmocked in local unit tests (returns 0), so SessionClock's
    // originMs is 0: dts equals capture wall time, letting the tests pass
    // explicit wall values for delivery.

    private fun pts() = VideoPts(SessionClock())

    @Test
    fun cameraClockIsUsedWhenItSitsPlausiblyBehindTheWall() {
        val pts = pts()
        // First frame stamped 1000 ms, delivered 300 ms later (warm-up latency):
        // in range, so the camera clock is trusted.
        assertEquals(1_000L, pts.next(1_000_000L, 1_300L))
        assertEquals(1_500L, pts.next(1_500_000L, 1_800L))
        assertEquals(2_000L, pts.next(2_000_000L, 2_300L))
    }

    @Test
    fun unrelatedClockFallsBackToDeliveryMinusLatency() {
        val pts = pts()
        // An epoch-style timestamp is nowhere near the wall clock: ignored.
        assertEquals(400L, pts.next(1_700_000_000_000L, 500L))
        // Mode is locked; later stamps (even valid-looking ones) stay ignored.
        assertEquals(600L, pts.next(2_000_000_000_000L, 700L))
    }

    @Test
    fun encoderRelativeStampsNearZeroFallBackToDeliveryMinusLatency() {
        val pts = pts()
        // Stamps that begin near 0 are encoder-relative, not wall-synced.
        assertEquals(400L, pts.next(300_000L, 500L))
        assertEquals(500L, pts.next(600_000L, 600L))
    }

    @Test
    fun unstampedOutputFallsBackToDeliveryMinusLatency() {
        val pts = pts()
        assertEquals(400L, pts.next(0L, 500L))
        assertEquals(500L, pts.next(0L, 600L))
        assertEquals(500L, pts.next(0L, 590L)) // backwards → held
    }

    @Test
    fun cameraClockHoldsMonotonicWhenStampsDropToZero() {
        val pts = pts()
        pts.next(1_000_000L, 1_300L) // origin
        pts.next(2_000_000L, 2_300L) // 2000 ms
        assertEquals(2_000L, pts.next(0L, 2_600L))
        assertEquals(2_000L, pts.next(-5L, 2_700L))
    }

    @Test
    fun cameraClockClampsBackwardsJumps() {
        val pts = pts()
        pts.next(1_000_000L, 1_300L)
        assertEquals(2_000L, pts.next(2_000_000L, 2_300L))
        assertEquals(2_000L, pts.next(1_500_000L, 2_300L)) // backwards → held
    }

    @Test
    fun resetRestartsTheClock() {
        val pts = pts()
        pts.next(1_000_000L, 1_300L)
        pts.next(2_000_000L, 2_300L)
        pts.reset()
        assertEquals(9_000L, pts.next(9_000_000L, 9_300L))
        assertEquals(9_100L, pts.next(9_100_000L, 9_400L))
    }
}
