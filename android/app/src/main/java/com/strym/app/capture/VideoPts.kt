package com.strym.app.capture

/**
 * Stream-relative presentation timestamps for encoded video.
 *
 * An encoder's input surface has no guaranteed time base: most HALs stamp
 * queued buffers with the camera clock (boottime or elapsed realtime, both
 * wall-synced), some leave them at zero. This maps whatever the device
 * produces onto the session's shared [SessionClock.originMs] in *capture*
 * terms, so video dts lines up with audio's capture-time dts instead of
 * carrying a pipeline-latency offset:
 *
 * - When the first frame's timestamp sits behind the wall clock at delivery
 *   by a plausible capture-to-output latency (≤ [maxLatencyMs]), the track
 *   runs directly off the camera clock: `dts = cameraMs - originMs`.
 * - Otherwise the device stamps an unrelated clock (or nothing at all), and
 *   the track anchors to the wall clock at delivery minus a nominal
 *   capture latency (`deliveryWallMs - nominalLatencyMs - originMs`), so the
 *   encoder's warm-up delay on the first frame does not surface as a
 *   persistent offset that trips the core's rebase.
 *
 * Output is always monotonic — the core relies on it.
 */
class VideoPts(
    private val clock: SessionClock,
) {

    private enum class Mode { UNKNOWN, WALL, LATENCY }

    /** Largest camera→output latency for which a frame timestamp is treated
     *  as the wall-synced camera clock. Anything beyond that is an unrelated
     *  clock (e.g. epoch) and must not be trusted. */
    private val maxLatencyMs = 1_000L
    /** Steady-state camera → encoder output latency when the clock is unknown
     *  (never the one-time warm-up delay: every frame re-anchors to its own
     *  delivery, so warm-up cannot become a persistent offset). */
    private val nominalLatencyMs = 100L
    /** A wall-synced camera clock reads at least this on any real device at
     *  session start; filters out encoder-relative stamps that begin near 0. */
    private val minPlausibleCameraMs = 1_000L

    private var mode = Mode.UNKNOWN
    private var lastMs = -1L

    /**
     * Timestamp in ms for an output buffer the encoder stamped [rawPtsUs]
     * microseconds, delivered at wall time [deliveryWallMs].
     */
    fun next(rawPtsUs: Long, deliveryWallMs: Long): Long {
        if (mode == Mode.UNKNOWN) {
            val cameraMs = rawPtsUs / 1_000L
            val wallSynced = rawPtsUs > 0 &&
                cameraMs >= minPlausibleCameraMs &&
                (deliveryWallMs - cameraMs) in 0..maxLatencyMs
            mode = if (wallSynced) Mode.WALL else Mode.LATENCY
        }
        val ms = when (mode) {
            Mode.WALL -> if (rawPtsUs > 0) rawPtsUs / 1_000L - clock.originMs else lastMs
            Mode.LATENCY -> deliveryWallMs - nominalLatencyMs - clock.originMs
            Mode.UNKNOWN -> 0L
        }
        val monotonic = if (ms > lastMs) ms else lastMs
        lastMs = monotonic
        return monotonic
    }

    fun reset() {
        mode = Mode.UNKNOWN
        lastMs = -1L
    }
}
