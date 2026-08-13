package com.strym.app.logging

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.stream_ffi.LogLevel

class StrymLogSinkTest {

    @Test
    fun buffersRedactedMessage() {
        val redactor = SecretRedactor().apply { addSecret("top-secret-key") }
        val buffer = CoreLogBuffer(capacity = 10)
        val sink = StrymLogSink(redactor, buffer, clockMs = { 123L })

        sink.onLog(LogLevel.INFO, "rtmp", "publishing with key top-secret-key")

        val record = buffer.snapshot().single()
        assertEquals(123L, record.timestampMs)
        assertEquals("INFO", record.level)
        assertEquals("rtmp", record.module)
        assertEquals("publishing with key [REDACTED]", record.message)
    }

    @Test
    fun keepsRecordsWithoutRedactionWhenNoSecrets() {
        val buffer = CoreLogBuffer(capacity = 10)
        val sink = StrymLogSink(SecretRedactor(), buffer)

        sink.onLog(LogLevel.WARN, "engine", "dropped frame")

        val record = buffer.snapshot().single()
        assertEquals("dropped frame", record.message)
        assertEquals("WARN", record.level)
    }
}
