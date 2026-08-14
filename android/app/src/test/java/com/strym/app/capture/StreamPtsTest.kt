package com.strym.app.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamPtsTest {

    // SystemClock is unmocked in local unit tests (returns 0), so originMs is 0
    // and the rebased dts equals the raw delivery wall ms.

    @Test
    fun dtsIsDeliveryWallTimeMinusSharedOrigin() {
        val pts = StreamPts(SessionClock())
        assertEquals(500L, pts.next(500L))
        assertEquals(520L, pts.next(520L))
        assertEquals(540L, pts.next(540L))
    }

    @Test
    fun clampsBackwardsJumps() {
        val pts = StreamPts(SessionClock())
        pts.next(500L)
        assertEquals(520L, pts.next(520L))
        assertEquals(520L, pts.next(510L))
    }

    @Test
    fun tracksDeliveryTimeWhenTheEncoderSkipsAhead() {
        val pts = StreamPts(SessionClock())
        pts.next(500L)
        assertEquals(600L, pts.next(600L))
    }

    @Test
    fun resetRestartsTheClock() {
        val pts = StreamPts(SessionClock())
        pts.next(500L)
        pts.next(520L)
        pts.reset()
        assertEquals(900L, pts.next(900L))
    }
}
