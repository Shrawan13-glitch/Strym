package com.strym.app.capture

/**
 * Stream-relative presentation timestamps for encoded video.
 *
 * An encoder's input surface carries no guaranteed time base: some HALs
 * stamp queued buffers (a sensor/boottime clock), others leave them at
 * zero. This rebases whatever the device produces into milliseconds since
 * the first frame, and falls back to the nominal frame rate when nothing
 * is stamped. Output is always monotonic — the core relies on it.
 */
class VideoPts(private val fallbackFps: Double) {

    private enum class Mode { UNKNOWN, CLOCK, FALLBACK }

    private var mode = Mode.UNKNOWN
    private var originUs = 0L
    private var frames = 0L
    private var lastMs = -1L

    /** Timestamp in ms for an output buffer stamped [rawPtsUs] microseconds. */
    fun next(rawPtsUs: Long): Long {
        frames++
        val ms = when (mode) {
            Mode.UNKNOWN -> {
                if (rawPtsUs > 0) {
                    mode = Mode.CLOCK
                    originUs = rawPtsUs
                } else {
                    mode = Mode.FALLBACK
                }
                0L
            }

            Mode.CLOCK -> if (rawPtsUs > 0) (rawPtsUs - originUs) / 1_000L else lastMs

            Mode.FALLBACK -> ((frames - 1) * 1_000.0 / fallbackFps).toLong()
        }
        val monotonic = if (ms > lastMs) ms else lastMs
        lastMs = monotonic
        return monotonic
    }

    fun reset() {
        mode = Mode.UNKNOWN
        frames = 0
        lastMs = -1
    }
}
