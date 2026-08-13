package com.strym.app.logging

import java.util.concurrent.atomic.AtomicReference

/**
 * A bounded, thread-safe ring buffer of recent core log records, used for
 * "report an issue" exports. Oldest records are evicted once [capacity] is
 * reached.
 */
class CoreLogBuffer(private val capacity: Int = DEFAULT_CAPACITY) {

    private val entries = ArrayDeque<LogRecord>()

    @Synchronized
    fun append(record: LogRecord) {
        while (entries.size >= capacity) {
            entries.removeFirst()
        }
        entries.addLast(record)
    }

    @Synchronized
    fun snapshot(): List<LogRecord> = entries.toList()

    companion object {
        const val DEFAULT_CAPACITY = 200
    }
}

/**
 * One structured log record, sanitized for export: the message has already
 * been through [SecretRedactor.redact].
 */
data class LogRecord(
    val timestampMs: Long,
    val level: String,
    val module: String,
    val message: String,
)
