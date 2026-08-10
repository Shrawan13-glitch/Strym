package com.strym.app.session

import uniffi.stream_ffi.SessionConfig
import uniffi.stream_ffi.SessionState
import uniffi.stream_ffi.StreamListener
import uniffi.stream_ffi.StreamSession

/**
 * The slice of [StreamSession] the [StreamController] drives. A seam so the
 * controller's state machine runs in JVM unit tests without loading the
 * native library; production wires [RealSessionFactory].
 */
interface SessionGateway : AutoCloseable {
    fun start()

    fun retry()

    fun state(): SessionState

    fun lastError(): String?

    override fun close()
}

fun interface SessionFactory {
    fun create(config: SessionConfig, listener: StreamListener): SessionGateway
}

private class RealSessionGateway(private val session: StreamSession) : SessionGateway {
    override fun start() = session.start()

    override fun retry() = session.retry()

    override fun state(): SessionState = session.state()

    override fun lastError(): String? = session.lastError()

    override fun close() {
        session.stop()
        session.destroy()
    }
}

object RealSessionFactory : SessionFactory {
    override fun create(config: SessionConfig, listener: StreamListener): SessionGateway =
        RealSessionGateway(StreamSession(config, listener))
}
