package com.strym.app.capture

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared wall-clock origin for a session's tracks.
 *
 * Both [VideoPts] and [StreamPts] rebase onto this same origin (fixed when the
 * session goes live), so video and audio share one timeline instead of each
 * anchoring to its own first frame — that per-track rebasing left a constant
 * offset between the tracks, which the core read as a persistent >100 ms slip
 * and kept "rebase"-ing. [markFirstVideoFrame] additionally pins the wall time
 * the first video frame is delivered, so the video track can express its dts
 * in delivery-wall terms (first frame's absolute delivery time plus
 * camera-relative frame spacing) and stay time-aligned with audio's delivery.
 */
class SessionClock {

    /** Wall ms (elapsedRealtime) at session start; the shared dts origin. */
    val originMs: Long = SystemClock.elapsedRealtimeNanos() / 1_000_000L

    private val firstVideoFrameMs = AtomicLong(-1L)

    /** Wall ms of the first delivered video frame, pinned once. */
    fun markFirstVideoFrame(): Long {
        val nowMs = SystemClock.elapsedRealtimeNanos() / 1_000_000L
        firstVideoFrameMs.compareAndSet(-1L, nowMs)
        return firstVideoFrameMs.get()
    }
}