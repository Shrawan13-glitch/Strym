package com.strym.app.capture

/**
 * Rebases an encoder's raw timestamps onto a stream-relative base, like
 * [VideoPts] does for video but for audio's byte-buffer output, whose
 * `presentationTimeUs` values are always stamped by the recorder at capture
 * time. The first frame becomes 0 (matching the video track's own origin, so
 * the core's first-packet normalization sees a small, constant A/V skew rather
 * than a session-clock offset) and output is clamped monotonic.
 */
class StreamPts {

    private var origin = Long.MAX_VALUE
    private var last = -1L

    /** Timestamp in ms for an output buffer stamped [rawMs] by the encoder. */
    fun next(rawMs: Long): Long {
        if (rawMs < origin) origin = rawMs
        val rebased = rawMs - origin
        val monotonic = if (rebased > last) rebased else last
        last = monotonic
        return monotonic
    }

    fun reset() {
        origin = Long.MAX_VALUE
        last = -1L
    }
}
