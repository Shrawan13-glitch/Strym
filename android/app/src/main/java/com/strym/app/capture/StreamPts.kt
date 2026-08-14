package com.strym.app.capture

/**
 * Rebases the audio track onto the session's shared [SessionClock.originMs].
 *
 * Audio's `presentationTimeUs` values are always stamped by the recorder at
 * capture (wall clock), so each output buffer is simply delivery wall time
 * minus the shared origin — the same base the video track uses. Output is
 * clamped monotonic; there is no per-track first-frame rebasing, which is what
 * kept a constant offset between the tracks in the first place.
 */
class StreamPts(private val clock: SessionClock) {

    private var last = -1L

    /** Timestamp in ms for an output buffer stamped [rawMs] by the encoder. */
    fun next(rawMs: Long): Long {
        val rebased = rawMs - clock.originMs
        val monotonic = if (rebased > last) rebased else last
        last = monotonic
        return monotonic
    }

    fun reset() {
        last = -1L
    }
}
