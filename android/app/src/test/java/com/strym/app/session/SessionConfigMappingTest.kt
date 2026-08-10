package com.strym.app.session

import com.strym.app.settings.BroadcastSettings
import com.strym.app.settings.VideoPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.stream_ffi.LatencyMode
import uniffi.stream_ffi.SessionState
import uniffi.stream_ffi.StreamException

class SessionConfigMappingTest {

    @Test
    fun everySessionStateMapsToAPhase() {
        assertEquals(StreamPhase.IDLE, SessionState.IDLE.toPhase())
        assertEquals(StreamPhase.CONNECTING, SessionState.CONNECTING.toPhase())
        assertEquals(StreamPhase.LIVE, SessionState.LIVE.toPhase())
        assertEquals(StreamPhase.RECONNECTING, SessionState.RECONNECTING.toPhase())
        assertEquals(StreamPhase.EXHAUSTED, SessionState.EXHAUSTED.toPhase())
        assertEquals(StreamPhase.STOPPED, SessionState.STOPPED.toPhase())
    }

    @Test
    fun configCarriesDestinationStreamAndTuning() {
        val settings = BroadcastSettings(
            serverUrl = " rtmp://ingest.example.tv:1936 ",
            app = " live ",
            streamKey = " secret ",
            preset = VideoPreset.P1080_30,
            videoBitrateBps = 6_000_000,
            latencyMode = LatencyMode.AGGRESSIVE,
        )

        val config = buildSessionConfig(settings)

        assertEquals("rtmp://ingest.example.tv:1936", config.destination.url)
        assertEquals("live", config.destination.app)
        assertEquals("secret", config.destination.streamKey)
        assertEquals(1920u, config.stream.width)
        assertEquals(1080u, config.stream.height)
        assertEquals(30.0, config.stream.framerate, 0.001)
        assertEquals(6_000_000u, config.stream.videoBitrateBps)
        assertEquals(128_000u, config.stream.audioBitrateBps)
        assertEquals(48_000u, config.stream.audioSampleRateHz)
        assertEquals(LatencyMode.AGGRESSIVE, config.latency)
        // Finite budget: the UI owns the "try again / give up" decision.
        assertEquals(8u, config.reconnectMaxAttempts)
        assertTrue(config.reconnectMaxDelayMs > config.reconnectInitialDelayMs)
        assertEquals(1_000uL, config.statsIntervalMs)
        assertEquals(16uL, config.pumpIntervalMs)
    }

    @Test
    fun exceptionMessagesAreUserReadable() {
        assertTrue(
            StreamException.InvalidConfig("bad url").toUserMessage().contains("bad url"),
        )
        assertTrue(
            StreamException.InvalidState("already running").toUserMessage()
                .contains("already running"),
        )
        assertTrue(
            StreamException.Engine("codec rejected").toUserMessage().contains("codec rejected"),
        )
    }

    @Test
    fun canStartRequiresEndpointAndKey() {
        val base = BroadcastSettings(
            serverUrl = "rtmp://ingest.example.tv",
            app = "live",
            streamKey = "secret",
        )
        assertTrue(base.canStart)
        assertFalse(base.copy(serverUrl = "").canStart)
        assertFalse(base.copy(serverUrl = "https://example.tv").canStart)
        assertFalse(base.copy(serverUrl = "rtmp://").canStart)
        assertFalse(base.copy(app = "  ").canStart)
        assertFalse(base.copy(streamKey = "").canStart)
    }

    @Test
    fun failedConnectOnlyWithSessionAndError() {
        assertFalse(UiState().failedConnect)
        assertFalse(UiState(phase = StreamPhase.IDLE, hasSession = true).failedConnect)
        assertTrue(
            UiState(phase = StreamPhase.IDLE, hasSession = true, errorMessage = "x").failedConnect,
        )
        assertFalse(
            UiState(phase = StreamPhase.LIVE, hasSession = true, errorMessage = "x").failedConnect,
        )
    }
}
