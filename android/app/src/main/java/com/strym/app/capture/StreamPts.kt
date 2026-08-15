package com.strym.app.capture

/**
 * Rebases the audio track onto the session's shared [SessionClock.originMs].
 *
 * Audio's dts is the capture wall time the frame was recorded (minus the
 * shared origin) — the same base the video track uses, so the two tracks stay
 * time-aligned and the core does not see a cross-track offset. Output is
 * clamped monotonic; there is no per-track first-frame rebasing.
 */
class StreamPts(private val clock: SessionClock) {

    private var last = -1L

    /** Timestamp in ms for a frame captured at wall time [captureMs]. */
    fun next(captureMs: Long): Long {
        val rebased = captureMs - clock.originMs
        val monotonic = if (rebased > last) rebased else last
        last = monotonic
        return monotonic
    }

    fun reset() {
        last = -1L
    }
}
