package com.strym.app.session

import android.os.SystemClock
import android.util.Log
import com.strym.app.capture.MediaIngest
import com.strym.app.settings.BroadcastSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.stream_ffi.RtmpDestination
import uniffi.stream_ffi.SessionConfig
import uniffi.stream_ffi.SessionState
import uniffi.stream_ffi.SessionStats
import uniffi.stream_ffi.StreamException
import uniffi.stream_ffi.StreamInfo
import uniffi.stream_ffi.StreamListener

private const val RECONNECT_MAX_ATTEMPTS = 8
private const val RECONNECT_INITIAL_DELAY_MS = 500L
private const val RECONNECT_MAX_DELAY_MS = 15_000L
private const val STALL_TIMEOUT_MS = 10_000L
private const val PUMP_INTERVAL_MS = 16L
private const val STATS_INTERVAL_MS = 1_000L

private const val TAG = "StreamController"

/**
 * Map the user's broadcast settings onto the frozen core config. Mirrors the
 * core's `default_session_config` with a finite reconnect budget, so
 * [StreamPhase.EXHAUSTED] is reachable and the UI owns the "try again" /
 * "give up" decision. [portrait] is the device's hold at go-live: it fixes
 * the encoded shape (and the GL pipeline's uprighting) for the broadcast.
 * When [actualWidth]/[actualHeight] are supplied (from EncoderCapabilities),
 * they override the preset so FLV onMetaData matches the real encoder output
 * — mismatched metadata is why YouTube shows "excellent" but never starts.
 */
fun buildSessionConfig(
    settings: BroadcastSettings,
    portrait: Boolean,
    actualWidth: Int? = null,
    actualHeight: Int? = null,
): SessionConfig {
    val destination = RtmpDestination(
        url = settings.serverUrl.trim(),
        app = settings.app.trim(),
        streamKey = settings.streamKey.trim(),
        timeoutMs = 0uL,
    )
    val (presetW, presetH) = settings.preset.outputSize(portrait)
    val outWidth = actualWidth ?: presetW
    val outHeight = actualHeight ?: presetH
    val stream = StreamInfo(
        width = outWidth.toUInt(),
        height = outHeight.toUInt(),
        framerate = settings.preset.framerate,
        videoBitrateBps = settings.videoBitrateBps.toUInt(),
        audioBitrateBps = BroadcastSettings.AUDIO_BITRATE_BPS.toUInt(),
        audioSampleRateHz = BroadcastSettings.AUDIO_SAMPLE_RATE_HZ.toUInt(),
    )
    return SessionConfig(
        destination = destination,
        stream = stream,
        latency = settings.latencyMode,
        reconnectMaxAttempts = RECONNECT_MAX_ATTEMPTS.toUInt(),
        reconnectInitialDelayMs = RECONNECT_INITIAL_DELAY_MS.toULong(),
        reconnectMaxDelayMs = RECONNECT_MAX_DELAY_MS.toULong(),
        stallTimeoutMs = STALL_TIMEOUT_MS.toULong(),
        pumpIntervalMs = PUMP_INTERVAL_MS.toULong(),
        statsIntervalMs = STATS_INTERVAL_MS.toULong(),
    )
}

/**
 * The single coordinator between the UI and one core session.
 *
 * Owns the reference clock ([nowMs], the timestamp base Phase C/D will feed
 * to `push_video`/`push_audio`), maps the core's worker-thread callbacks onto
 * one thread-safe [StateFlow], serializes lifecycle actions, and converts FFI
 * exceptions into user-readable messages.
 */
class StreamController(
    private val sessionFactory: SessionFactory,
    private val clockNanos: () -> Long = { SystemClock.elapsedRealtimeNanos() },
    private val resolveError: (UserError) -> String = { it.detail },
) : MediaIngest {

    private val controlMutex = Mutex()

    @Volatile
    private var gateway: SessionGateway? = null

    @Volatile
    private var originNanos: Long? = null

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    /** Milliseconds since the current session's clock origin.
     * 0 before the first [goLive]. */
    fun nowMs(): Long {
        val origin = originNanos ?: return 0L
        return (clockNanos() - origin) / 1_000_000L
    }

    private val listener = object : StreamListener {
        override fun onStateChanged(state: SessionState, detail: String?) {
            if (gateway == null) return
            val phase = state.toPhase()
            _uiState.update { current ->
                current.copy(
                    phase = phase,
                    errorMessage = when {
                        phase == StreamPhase.LIVE -> null
                        detail != null -> detail
                        else -> current.errorMessage
                    },
                )
            }
        }

        override fun onStats(stats: SessionStats) {
            if (gateway == null) return
            _uiState.update { it.copy(stats = stats.toSnapshot()) }
        }
    }

    /**
     * Create a session from [settings] and start it. Returns false (with the
     * reason in [uiState]) when the config or the start is rejected.
     * Pass [actualWidth]/[actualHeight] when EncoderCapabilities has already
     * clamped the preset to a supported size so metadata matches the wire.
     */
    suspend fun goLive(
        settings: BroadcastSettings,
        portrait: Boolean,
        actualWidth: Int? = null,
        actualHeight: Int? = null,
    ): Boolean = controlMutex.withLock {
            if (gateway != null) return false
            val config = buildSessionConfig(settings, portrait, actualWidth, actualHeight)
            _uiState.value = UiState(phase = StreamPhase.CONNECTING, hasSession = true)
            val created = try {
                sessionFactory.create(config, listener)
            } catch (e: StreamException) {
                _uiState.value = UiState(errorMessage = resolveError(e.toUserError()))
                return@withLock false
            }
            gateway = created
            originNanos = clockNanos()
            try {
                created.start()
            } catch (e: StreamException) {
                gateway = null
                withContext(Dispatchers.IO) { created.close() }
                _uiState.value = UiState(errorMessage = resolveError(e.toUserError()))
                return@withLock false
            }
            true
        }

    /**
     * Re-arm after [StreamPhase.EXHAUSTED], or relaunch a session whose
     * initial connect failed. No-op when no session exists.
     */
    suspend fun retry() = controlMutex.withLock {
        val current = gateway ?: return@withLock
        _uiState.update { it.copy(phase = StreamPhase.CONNECTING, errorMessage = null) }
        try {
            current.retry()
        } catch (e: StreamException) {
            _uiState.update {
                it.copy(phase = current.state().toPhase(), errorMessage = resolveError(e.toUserError()))
            }
        }
    }

    /** Stop the session (blocking join happens off the calling thread) and reset to idle. */
    suspend fun stopSession() = controlMutex.withLock {
        val current = gateway ?: return@withLock
        gateway = null
        originNanos = null
        withContext(Dispatchers.IO) { current.close() }
        _uiState.value = UiState()
    }

    /**
     * Media-ingest entry points fed by the capture pipelines ([MediaIngest]:
     * [com.strym.app.capture.CameraStreamer] and
     * [com.strym.app.capture.AudioRecorder]) from the encoder threads. All are
     * no-ops without a live session and never block — the core copies into its
     * bounded buffer and returns.
     */
    override fun configureCodecs(avcDecoderConfig: ByteArray?, audioSpecificConfig: ByteArray?) {
        val current = gateway ?: return
        try {
            current.configureCodecs(avcDecoderConfig, audioSpecificConfig)
        } catch (e: StreamException) {
            Log.w(TAG, "codec config rejected: ${resolveError(e.toUserError())}")
        }
    }

    override fun pushVideo(ptsMs: Long, isKeyframe: Boolean, annexB: ByteArray) {
        gateway?.pushVideo(ptsMs, isKeyframe, annexB)
    }

    override fun pushAudio(ptsMs: Long, aac: ByteArray) {
        gateway?.pushAudio(ptsMs, aac)
    }

    /** Surface a capture-pipeline failure as a user-readable message. */
    fun reportCaptureError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }
}
