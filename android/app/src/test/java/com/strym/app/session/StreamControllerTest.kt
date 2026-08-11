package com.strym.app.session

import com.strym.app.settings.BroadcastSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.stream_ffi.SessionConfig
import uniffi.stream_ffi.SessionState
import uniffi.stream_ffi.SessionStats
import uniffi.stream_ffi.StreamException
import uniffi.stream_ffi.StreamListener

private val TEST_SETTINGS = BroadcastSettings(
    serverUrl = "rtmp://ingest.example.tv",
    app = "live",
    streamKey = "secret-key",
)

private class FakeGateway : SessionGateway {
    var startCalls = 0
    var retryCalls = 0
    var closed = false
    var startException: StreamException? = null
    var stateValue: SessionState = SessionState.IDLE
    val codecConfigs = mutableListOf<Pair<ByteArray?, ByteArray?>>()
    var configureException: StreamException? = null
    val pushedFrames = mutableListOf<Triple<Long, Boolean, Int>>()
    val pushedAudio = mutableListOf<Pair<Long, Int>>()

    override fun start() {
        startCalls++
        startException?.let { throw it }
    }

    override fun retry() {
        retryCalls++
    }

    override fun state(): SessionState = stateValue

    override fun lastError(): String? = null

    override fun configureCodecs(avcDecoderConfig: ByteArray?, audioSpecificConfig: ByteArray?) {
        configureException?.let { throw it }
        codecConfigs.add(avcDecoderConfig to audioSpecificConfig)
    }

    override fun pushVideo(ptsMs: Long, isKeyframe: Boolean, annexB: ByteArray) {
        pushedFrames.add(Triple(ptsMs, isKeyframe, annexB.size))
    }

    override fun pushAudio(ptsMs: Long, data: ByteArray) {
        pushedAudio.add(ptsMs to data.size)
    }

    override fun close() {
        closed = true
    }
}

private class FakeSessionFactory : SessionFactory {
    var createException: StreamException? = null
    var nextStartException: StreamException? = null
    val created = mutableListOf<FakeGateway>()
    var lastListener: StreamListener? = null
    var lastConfig: SessionConfig? = null

    override fun create(config: SessionConfig, listener: StreamListener): SessionGateway {
        createException?.let { throw it }
        lastConfig = config
        lastListener = listener
        return FakeGateway().also {
            it.startException = nextStartException
            created.add(it)
        }
    }
}

private fun testStats(
    state: SessionState = SessionState.LIVE,
    bitrateOutBps: Double = 1_200_000.0,
    uptimeMs: kotlin.ULong = 5_000uL,
): SessionStats = SessionStats(
    state = state,
    pushed = 10uL,
    muxed = 9uL,
    dropped = 1uL,
    bufferedPackets = 2uL,
    bufferLagMs = 120L,
    mediaBytes = 1_000uL,
    wireBytes = 1_100uL,
    bitrateOutBps = bitrateOutBps,
    throughputBps = 1_150_000.0,
    dropRatio = 0.1,
    rttMs = 42.0,
    reconnects = 0uL,
    reconnectAttempts = 0uL,
    uptimeMs = uptimeMs,
)

class StreamControllerTest {

    private var nanos = 1_000_000_000L

    private fun controller(factory: FakeSessionFactory) = StreamController(factory) { nanos }

    @Test
    fun goLiveTransitionsConnectingThenLive() = runTest {
        val factory = FakeSessionFactory()
        val controller = controller(factory)

        assertTrue(controller.goLive(TEST_SETTINGS))
        val connecting = controller.uiState.value
        assertEquals(StreamPhase.CONNECTING, connecting.phase)
        assertTrue(connecting.hasSession)
        assertEquals(1, factory.created.single().startCalls)

        factory.lastListener!!.onStateChanged(SessionState.LIVE, null)
        val live = controller.uiState.value
        assertEquals(StreamPhase.LIVE, live.phase)
        assertNull(live.errorMessage)
    }

    @Test
    fun invalidConfigSurfacesUserMessageWithoutSession() = runTest {
        val factory = FakeSessionFactory()
        factory.createException = StreamException.InvalidConfig("app must not be empty")
        val controller = controller(factory)

        assertFalse(controller.goLive(TEST_SETTINGS))
        val state = controller.uiState.value
        assertEquals(StreamPhase.IDLE, state.phase)
        assertFalse(state.hasSession)
        assertTrue(state.errorMessage!!.contains("app must not be empty"))
    }

    @Test
    fun startFailureCleansUpAndShowsMessage() = runTest {
        val factory = FakeSessionFactory()
        factory.nextStartException = StreamException.InvalidState("session already running")
        val controller = controller(factory)

        assertFalse(controller.goLive(TEST_SETTINGS))
        assertTrue(factory.created.single().closed)
        val state = controller.uiState.value
        assertFalse(state.hasSession)
        assertTrue(state.errorMessage!!.contains("session already running"))
    }

    @Test
    fun failedInitialConnectShowsErrorAndRetryRelaunches() = runTest {
        val factory = FakeSessionFactory()
        val controller = controller(factory)
        assertTrue(controller.goLive(TEST_SETTINGS))

        factory.lastListener!!.onStateChanged(
            SessionState.IDLE,
            "connect failed: connection refused",
        )
        val failed = controller.uiState.value
        assertTrue(failed.failedConnect)
        assertEquals("connect failed: connection refused", failed.errorMessage)

        controller.retry()
        assertEquals(1, factory.created.single().retryCalls)
        assertEquals(StreamPhase.CONNECTING, controller.uiState.value.phase)
        assertNull(controller.uiState.value.errorMessage)
    }

    @Test
    fun reconnectingShowsDetailAndLiveClearsIt() = runTest {
        val factory = FakeSessionFactory()
        val controller = controller(factory)
        assertTrue(controller.goLive(TEST_SETTINGS))
        val listener = factory.lastListener!!

        listener.onStateChanged(SessionState.LIVE, null)
        listener.onStateChanged(SessionState.RECONNECTING, "connection reset")
        val reconnecting = controller.uiState.value
        assertEquals(StreamPhase.RECONNECTING, reconnecting.phase)
        assertEquals("connection reset", reconnecting.errorMessage)
        assertTrue(reconnecting.canControl)

        listener.onStateChanged(SessionState.LIVE, null)
        assertNull(controller.uiState.value.errorMessage)
    }

    @Test
    fun exhaustedOffersRetryAndGiveUp() = runTest {
        val factory = FakeSessionFactory()
        val controller = controller(factory)
        assertTrue(controller.goLive(TEST_SETTINGS))
        val listener = factory.lastListener!!

        listener.onStateChanged(SessionState.LIVE, null)
        listener.onStateChanged(SessionState.RECONNECTING, "connection reset")
        listener.onStateChanged(SessionState.EXHAUSTED, "reconnect budget exhausted")
        val exhausted = controller.uiState.value
        assertEquals(StreamPhase.EXHAUSTED, exhausted.phase)
        assertEquals("reconnect budget exhausted", exhausted.errorMessage)

        controller.retry()
        assertEquals(1, factory.created.single().retryCalls)
        assertEquals(StreamPhase.CONNECTING, controller.uiState.value.phase)

        // "Give up" stops the session and resets to a fresh idle.
        controller.stopSession()
        assertTrue(factory.created.single().closed)
        assertEquals(UiState(), controller.uiState.value)
    }

    @Test
    fun statsCallbackPublishesSnapshot() = runTest {
        val factory = FakeSessionFactory()
        val controller = controller(factory)
        assertTrue(controller.goLive(TEST_SETTINGS))

        factory.lastListener!!.onStats(testStats(bitrateOutBps = 2_400_000.0))
        val stats = controller.uiState.value.stats
        assertEquals(2_400_000.0, stats!!.bitrateOutBps, 0.001)
        assertEquals(0.1, stats.dropRatio, 0.001)
        assertEquals(42.0, stats.rttMs!!, 0.001)
        assertEquals(5_000L, stats.uptimeMs)
    }

    @Test
    fun stopSessionClosesGatewayAndIgnoresLateCallbacks() = runTest {
        val factory = FakeSessionFactory()
        val controller = controller(factory)
        assertTrue(controller.goLive(TEST_SETTINGS))
        val listener = factory.lastListener!!
        listener.onStateChanged(SessionState.LIVE, null)

        controller.stopSession()
        assertEquals(UiState(), controller.uiState.value)

        // The core sends STOPPED while winding down; after teardown it must
        // not resurrect UI state.
        listener.onStateChanged(SessionState.STOPPED, null)
        listener.onStats(testStats(state = SessionState.STOPPED))
        assertEquals(UiState(), controller.uiState.value)
    }

    @Test
    fun goLiveWhileActiveIsRejected() = runTest {
        val factory = FakeSessionFactory()
        val controller = controller(factory)
        assertTrue(controller.goLive(TEST_SETTINGS))
        assertFalse(controller.goLive(TEST_SETTINGS))
        assertEquals(1, factory.created.size)
    }

    @Test
    fun nowMsMeasuresFromSessionOrigin() = runTest {
        val factory = FakeSessionFactory()
        val controller = controller(factory)
        assertEquals(0L, controller.nowMs())

        nanos = 3_000_000_000L
        assertTrue(controller.goLive(TEST_SETTINGS))
        nanos = 4_500_000_000L
        assertEquals(1_500L, controller.nowMs())
    }

    @Test
    fun retryWithoutSessionIsANoOp() = runTest {
        val controller = controller(FakeSessionFactory())
        controller.retry()
        assertEquals(UiState(), controller.uiState.value)
    }

    @Test
    fun pushVideoForwardsFramesToTheActiveSession() = runTest {
        val factory = FakeSessionFactory()
        val controller = controller(factory)
        assertTrue(controller.goLive(TEST_SETTINGS))
        val gateway = factory.created.single()

        controller.pushVideo(0, true, byteArrayOf(0, 0, 0, 1, 0x65))
        controller.pushVideo(33, false, byteArrayOf(0, 0, 0, 1, 0x41))
        assertEquals(listOf(Triple(0L, true, 5), Triple(33L, false, 5)), gateway.pushedFrames)
    }

    @Test
    fun pushVideoWithoutSessionIsANoOp() = runTest {
        val controller = controller(FakeSessionFactory())
        controller.pushVideo(0, true, byteArrayOf(0, 0, 0, 1, 0x65))
        assertEquals(UiState(), controller.uiState.value)
    }

    @Test
    fun pushVideoStopsAfterStopSession() = runTest {
        val factory = FakeSessionFactory()
        val controller = controller(factory)
        assertTrue(controller.goLive(TEST_SETTINGS))
        val gateway = factory.created.single()

        controller.stopSession()
        controller.pushVideo(0, true, byteArrayOf(0, 0, 0, 1, 0x65))
        assertTrue(gateway.pushedFrames.isEmpty())
    }

    @Test
    fun pushAudioForwardsFramesToTheActiveSession() = runTest {
        val factory = FakeSessionFactory()
        val controller = controller(factory)
        assertTrue(controller.goLive(TEST_SETTINGS))
        val gateway = factory.created.single()

        controller.pushAudio(21, byteArrayOf(1, 2, 3))
        controller.pushAudio(42, byteArrayOf(4, 5, 6, 7))
        assertEquals(listOf(21L to 3, 42L to 4), gateway.pushedAudio)
    }

    @Test
    fun pushAudioWithoutSessionIsANoOp() = runTest {
        val controller = controller(FakeSessionFactory())
        controller.pushAudio(21, byteArrayOf(1, 2, 3))
        assertEquals(UiState(), controller.uiState.value)
    }

    @Test
    fun pushAudioStopsAfterStopSession() = runTest {
        val factory = FakeSessionFactory()
        val controller = controller(factory)
        assertTrue(controller.goLive(TEST_SETTINGS))
        val gateway = factory.created.single()

        controller.stopSession()
        controller.pushAudio(21, byteArrayOf(1, 2, 3))
        assertTrue(gateway.pushedAudio.isEmpty())
    }

    @Test
    fun configureCodecsPassesThroughAndSwallowsRejection() = runTest {
        val factory = FakeSessionFactory()
        val controller = controller(factory)
        assertTrue(controller.goLive(TEST_SETTINGS))
        val gateway = factory.created.single()

        val avc = byteArrayOf(0x01, 0x64, 0x00, 0x1F)
        controller.configureCodecs(avc, null)
        assertEquals(1, gateway.codecConfigs.size)
        assertEquals(avc.toList(), gateway.codecConfigs.single().first!!.toList())

        val asc = byteArrayOf(0x12, 0x10)
        controller.configureCodecs(null, asc)
        assertEquals(2, gateway.codecConfigs.size)
        assertEquals(asc.toList(), gateway.codecConfigs.last().second!!.toList())

        // A rejection (e.g. wrong session state) must not escape to the
        // encoder thread and must not append a config.
        gateway.configureException = StreamException.InvalidState("not ready")
        controller.configureCodecs(avc, null)
        assertEquals(2, gateway.codecConfigs.size)
    }

    @Test
    fun reportCaptureErrorSurfacesTheMessage() = runTest {
        val controller = controller(FakeSessionFactory())
        controller.reportCaptureError("No H.264 encoder available on this device")
        val state = controller.uiState.value
        assertEquals(StreamPhase.IDLE, state.phase)
        assertEquals("No H.264 encoder available on this device", state.errorMessage)
    }
}
