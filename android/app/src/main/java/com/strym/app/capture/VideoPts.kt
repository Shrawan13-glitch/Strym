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
            // Log mode decision for diagnostics (only once per session)
            android.util.Log.i("VideoPts", "mode=$mode cameraMs=$cameraMs wallMs=$deliveryWallMs")
        }
        val ms = when (mode) {
            Mode.WALL -> {
                if (rawPtsUs <= 0) {
                    // Zero/invalid stamp after WALL lock: hold monotonic (tests expect this),
                    // but for the very first frame (lastMs==-1) fallback to wall to avoid -1.
                    if (lastMs < 0) deliveryWallMs - nominalLatencyMs - clock.originMs
                    else lastMs
                } else {
                    rawPtsUs / 1_000L - clock.originMs
                }
            }
            Mode.LATENCY -> deliveryWallMs - nominalLatencyMs - clock.originMs
            Mode.UNKNOWN -> 0L
        }
        // Ensure monotonic: small backward jitter (< maxLatency) is clamped, not rejected.
        // Also guard against huge forward jumps (>5s) which indicate clock reset — allow but log.
        val monotonic = if (ms > lastMs) {
            if (lastMs >= 0 && ms - lastMs > 5000) {
                android.util.Log.w("VideoPts", "large forward jump ${ms - lastMs}ms (dts=$ms last=$lastMs)")
            }
            ms
        } else {
            // Clamp small backward slips to monotonic; large backward would have triggered mode logic.
            if (lastMs - ms > maxLatencyMs) {
                android.util.Log.w("VideoPts", "clamping backward ${lastMs - ms}ms (dts=$ms last=$lastMs)")
            }
            lastMs
        }
        lastMs = monotonic
        return monotonic
    }

    fun reset() {
        mode = Mode.UNKNOWN
        lastMs = -1L
    }
}
