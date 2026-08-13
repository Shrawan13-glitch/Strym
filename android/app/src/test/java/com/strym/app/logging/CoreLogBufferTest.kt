package com.strym.app.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLogBufferTest {

    private fun record(id: Long) = LogRecord(
        timestampMs = id,
        level = "INFO",
        module = "rtmp",
        message = "msg-$id",
    )

    @Test
    fun appendsInOrder() {
        val buffer = CoreLogBuffer(capacity = 5)
        buffer.append(record(1))
        buffer.append(record(2))

        assertEquals(listOf(1L, 2L), buffer.snapshot().map { it.timestampMs })
    }

    @Test
    fun evictsOldestBeyondCapacity() {
        val buffer = CoreLogBuffer(capacity = 3)
        repeat(5) { buffer.append(record(it.toLong())) }

        assertEquals(listOf(2L, 3L, 4L), buffer.snapshot().map { it.timestampMs })
    }

    @Test
    fun snapshotIsIndependentCopy() {
        val buffer = CoreLogBuffer(capacity = 2)
        buffer.append(record(1))
        buffer.append(record(2))
        buffer.snapshot()
        buffer.append(record(3))

        assertEquals(2, buffer.snapshot().size)
    }

    @Test
    fun emptyBufferSnapshotsEmpty() {
        assertTrue(CoreLogBuffer().snapshot().isEmpty())
    }
}
