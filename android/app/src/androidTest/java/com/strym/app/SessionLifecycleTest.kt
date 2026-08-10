package com.strym.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.stream_ffi.LatencyMode
import uniffi.stream_ffi.RtmpDestination
import uniffi.stream_ffi.SessionConfig
import uniffi.stream_ffi.SessionState
import uniffi.stream_ffi.SessionStats
import uniffi.stream_ffi.StreamException
import uniffi.stream_ffi.StreamInfo
import uniffi.stream_ffi.StreamListener
import uniffi.stream_ffi.StreamSession
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs the committed bindings against the shipped `.so` on a real device:
 * one session per stream, eager config validation, reconnect exhaustion,
 * retry, and a clean stop. Deferred from Phase A, where only CI-safe smoke
 * tests were possible.
 */
@RunWith(AndroidJUnit4::class)
class SessionLifecycleTest {

    private class RecordingListener : StreamListener {
        val states = CopyOnWriteArrayList<SessionState>()
        val statsCount = AtomicInteger(0)

        override fun onStateChanged(state: SessionState, detail: String?) {
            states.add(state)
        }

        override fun onStats(stats: SessionStats) {
            statsCount.incrementAndGet()
        }
    }

    /** Port 9 (discard) on loopback: connection refused, fails fast. */
    private fun deadEndpointConfig() = SessionConfig(
        destination = RtmpDestination(
            url = "rtmp://127.0.0.1:9",
            app = "live",
            streamKey = "device-test",
            timeoutMs = 2_000uL,
        ),
        stream = StreamInfo(
            width = 640u,
            height = 360u,
            framerate = 30.0,
            videoBitrateBps = 900_000u,
            audioBitrateBps = 96_000u,
            audioSampleRateHz = 44_100u,
        ),
        latency = LatencyMode.BALANCED,
        reconnectMaxAttempts = 1u,
        reconnectInitialDelayMs = 200uL,
        reconnectMaxDelayMs = 500uL,
        stallTimeoutMs = 2_000uL,
        pumpIntervalMs = 16uL,
        statsIntervalMs = 200uL,
    )

    @Test
    fun sessionRunsFullLifecycleAgainstDeadEndpoint() {
        val listener = RecordingListener()
        val session = StreamSession(deadEndpointConfig(), listener)
        assertEquals(SessionState.IDLE, session.state())

        // start → CONNECTING → failed initial connect back to IDLE.
        session.start()
        assertTrue("never reached CONNECTING", waitFor { listener.states.contains(SessionState.CONNECTING) })
        assertTrue("connect failure never reported", waitFor { session.state() == SessionState.IDLE })
        assertNotNull("last_error missing after failed connect", session.lastError())
        assertTrue("no stats tick during the attempt", waitFor { listener.statsCount.get() > 0 })

        // retry re-dials the dead endpoint and fails again.
        session.retry()
        assertTrue(
            "retry never re-dialed",
            waitFor { listener.states.count { it == SessionState.CONNECTING } >= 2 },
        )
        assertTrue("second attempt never failed", waitFor { session.state() == SessionState.IDLE })

        // Graceful stop is terminal.
        session.stop()
        assertEquals(SessionState.STOPPED, session.state())
        session.destroy()
    }

    @Test
    fun invalidConfigIsRejectedEagerly() {
        val config = deadEndpointConfig()
        config.destination.url = "http://127.0.0.1:9"
        try {
            StreamSession(config, RecordingListener())
            fail("constructor must reject a non-rtmp URL")
        } catch (expected: StreamException.InvalidConfig) {
            // expected
        }
    }

    private fun waitFor(timeoutMs: Long = 15_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }
}
