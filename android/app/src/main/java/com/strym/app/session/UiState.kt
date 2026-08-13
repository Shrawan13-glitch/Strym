package com.strym.app.session

import androidx.annotation.StringRes
import com.strym.app.R
import uniffi.stream_ffi.SessionState
import uniffi.stream_ffi.SessionStats
import uniffi.stream_ffi.StreamException

enum class StreamPhase {
    IDLE,
    CONNECTING,
    LIVE,
    RECONNECTING,
    EXHAUSTED,
    STOPPED,
}

fun SessionState.toPhase(): StreamPhase = when (this) {
    SessionState.IDLE -> StreamPhase.IDLE
    SessionState.CONNECTING -> StreamPhase.CONNECTING
    SessionState.LIVE -> StreamPhase.LIVE
    SessionState.RECONNECTING -> StreamPhase.RECONNECTING
    SessionState.EXHAUSTED -> StreamPhase.EXHAUSTED
    SessionState.STOPPED -> StreamPhase.STOPPED
}

data class StatsSnapshot(
    val bitrateOutBps: Double,
    val throughputBps: Double,
    val dropRatio: Double,
    val bufferLagMs: Long,
    val rttMs: Double?,
    val reconnects: Long,
    val uptimeMs: Long,
)

fun SessionStats.toSnapshot(): StatsSnapshot = StatsSnapshot(
    bitrateOutBps = bitrateOutBps,
    throughputBps = throughputBps,
    dropRatio = dropRatio,
    bufferLagMs = bufferLagMs,
    rttMs = rttMs,
    reconnects = reconnects.toLong(),
    uptimeMs = uptimeMs.toLong(),
)

data class UiState(
    val phase: StreamPhase = StreamPhase.IDLE,
    val hasSession: Boolean = false,
    val stats: StatsSnapshot? = null,
    val errorMessage: String? = null,
) {
    val failedConnect: Boolean
        get() = hasSession && phase == StreamPhase.IDLE && errorMessage != null

    val canControl: Boolean
        get() = phase == StreamPhase.CONNECTING ||
            phase == StreamPhase.LIVE ||
            phase == StreamPhase.RECONNECTING ||
            phase == StreamPhase.EXHAUSTED
}

fun StreamException.toUserError(): UserError = when (this) {
    is StreamException.InvalidConfig -> UserError(R.string.error_invalid_config, message)
    is StreamException.InvalidState -> UserError(R.string.error_invalid_state, message)
    is StreamException.Engine -> UserError(R.string.error_engine, message)
}

/**
 * A user-facing error: the documented string resource for the error class
 * plus the core's free-form detail. Resolved to text by the
 * [StreamController]'s injected resolver (production: Context.getString;
 * tests: a plain lambda), so no string lives in code.
 */
data class UserError(@StringRes val stringRes: Int, val detail: String)
