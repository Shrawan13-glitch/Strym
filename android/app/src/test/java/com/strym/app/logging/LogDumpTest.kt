package com.strym.app.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogDumpTest {

    @Test
    fun formatsRecordsWithHeaderAndFooter() {
        val buffer = CoreLogBuffer(capacity = 10)
        buffer.append(
            LogRecord(
                timestampMs = 1_000L,
                level = "INFO",
                module = "rtmp",
                message = "connect ok",
            ),
        )

        val dump = LogDump.format(buffer)

        assertTrue(dump.startsWith("Strym log dump"))
        assertTrue(dump.trimEnd().endsWith("========================"))
        assertTrue(dump.contains("1000 [INFO] rtmp: connect ok"))
    }

    @Test
    fun formatsEmptyBuffer() {
        val dump = LogDump.format(CoreLogBuffer())
        assertEquals(
            "Strym log dump\n========================\n========================",
            dump.trim(),
        )
    }
}
