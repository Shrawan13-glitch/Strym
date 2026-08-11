package com.strym.app.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamPtsTest {

    @Test
    fun firstFrameIsZeroAndLaterFramesRebaseAgainstIt() {
        val pts = StreamPts()
        assertEquals(0L, pts.next(500L))
        assertEquals(20L, pts.next(520L))
        assertEquals(40L, pts.next(540L))
    }

    @Test
    fun clampsBackwardsJumps() {
        val pts = StreamPts()
        pts.next(500L)
        assertEquals(20L, pts.next(520L))
        assertEquals(20L, pts.next(510L))
    }

    @Test
    fun rebasesWhenTheEncoderSkipsAhead() {
        val pts = StreamPts()
        pts.next(500L)
        assertEquals(100L, pts.next(600L))
    }

    @Test
    fun resetRestartsTheClock() {
        val pts = StreamPts()
        pts.next(500L)
        pts.next(520L)
        pts.reset()
        assertEquals(0L, pts.next(900L))
    }
}
