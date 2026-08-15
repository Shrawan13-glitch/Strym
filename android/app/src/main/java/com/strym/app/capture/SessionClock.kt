package com.strym.app.capture

import android.os.SystemClock

/**
 * Shared wall-clock origin for a session's tracks.
 *
 * Both the video and audio tracks express their dts as capture wall time minus
 * [originMs], so they share one timeline: video runs off the camera clock when
 * it is wall-synced, audio is stamped with the wall clock at capture. The core
 * then sees no cross-track offset and stops "rebase"-ing.
 */
class SessionClock {

    /** Wall ms (elapsedRealtime) at session start; the shared dts origin. */
    val originMs: Long = SystemClock.elapsedRealtimeNanos() / 1_000_000L
}
