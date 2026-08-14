package com.strym.app.capture

/**
 * Stream-relative presentation timestamps for encoded video.
 *
 * An encoder's input surface carries no guaranteed time base: some HALs stamp
 * queued buffers (a sensor/boottime clock), others leave them at zero. This
 * rebases whatever the device produces into milliseconds since the session's
 * shared [SessionClock.originMs], anchoring the first frame to its wall-clock
 * delivery time rather than zero. Because the video track then starts at its
 * absolute delivery wall time (and audio's does too), the core never sees a
 * constant cross-track offset and stops rebasing. Falls back to the nominal
 * frame rate when nothing is stamped. Output is always monotonic — the core
 * relies on it.
 */
class VideoPts(
    private val fallbackFps: Double,
    private val clock: SessionClock,
) {

    private enum class Mode { UNKNOWN, CLOCK, FALLBACK }

    private var mode = Mode.UNKNOWN
    private var originUs = 0L
    private var anchorMs = 0L
    private var frames = 0L
    private var lastMs = -1L

    /** Timestamp in ms for an output buffer stamped [rawPtsUs] microseconds. */
    fun next(rawPtsUs: Long): Long {
        frames++
        if (mode == Mode.UNKNOWN) {
            if (rawPtsUs > 0) {
                mode = Mode.CLOCK
                originUs = rawPtsUs
            } else {
                mode = Mode.FALLBACK
            }
            anchorMs = clock.markFirstVideoFrame() - clock.originMs
        }
        val ms = when (mode) {
            Mode.CLOCK -> if (rawPtsUs > 0) anchorMs + (rawPtsUs - originUs) / 1_000L else lastMs
            Mode.FALLBACK -> anchorMs + ((frames - 1) * 1_000.0 / fallbackFps).toLong()
            Mode.UNKNOWN -> anchorMs
        }
        val monotonic = if (ms > lastMs) ms else lastMs
        lastMs = monotonic
        return monotonic
    }

    fun reset() {
        mode = Mode.UNKNOWN
        originUs = 0L
        anchorMs = 0L
        frames = 0
        lastMs = -1
    }
}
