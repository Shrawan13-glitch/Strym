package com.strym.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import uniffi.stream_ffi.*

/**
 * Phase A smoke app: load the native library, build a session, and run the
 * lifecycle locally. This proves the .so and the generated bindings work on
 * device before any capture/encode code lands.
 */
class MainActivity : Activity() {

    private lateinit var session: StreamSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            session = buildSmokeSession()
            Log.i(TAG, "session created in state ${session.state()}")
            Toast.makeText(this, "Native stream-ffi loaded OK", Toast.LENGTH_SHORT).show()

            // Prove the lifecycle works end-to-end: start (connects to a dead
            // loopback → Idle + error), then stop cleanly after a moment.
            session.start()
            Handler(Looper.getMainLooper()).postDelayed({
                session.stop()
                Log.i(TAG, "final state ${session.state()}")
                Toast.makeText(this, "Smoke lifecycle OK", Toast.LENGTH_SHORT).show()
            }, 1500)
        } catch (e: Exception) {
            Log.e(TAG, "smoke init failed", e)
            Toast.makeText(this, "Native init failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildSmokeSession(): StreamSession {
        val dest = RtmpDestination(
            url = "rtmp://127.0.0.1:1935",
            app = "live",
            streamKey = "smoke",
            timeoutMs = 0uL,
        )
        val stream = StreamInfo(
            width = 640u,
            height = 360u,
            framerate = 30.0,
            videoBitrateBps = 900_000u,
            audioBitrateBps = 96_000u,
            audioSampleRateHz = 44_100u,
        )
        val config = defaultSessionConfig(dest, stream)
        return StreamSession(config, object : StreamListener {
            override fun onStateChanged(state: SessionState, detail: String?) {
                Log.i(TAG, "state -> $state detail=$detail")
            }
            override fun onStats(stats: SessionStats) {
                Log.d(TAG, "stats: state=${stats.state} pushed=${stats.pushed} dropped=${stats.dropped}")
            }
        })
    }

    override fun onDestroy() {
        if (::session.isInitialized) session.stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "StrymSmoke"
    }
}
