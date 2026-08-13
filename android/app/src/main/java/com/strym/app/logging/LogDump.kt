package com.strym.app.logging

/**
 * Formats a [CoreLogBuffer] snapshot into a shareable, human-readable dump.
 * Messages are already redacted at ingestion time, so the dump is safe to
 * send out of the app.
 */
object LogDump {

    fun format(buffer: CoreLogBuffer): String = buildString {
        appendLine("Strym log dump")
        appendLine("========================")
        for (record in buffer.snapshot()) {
            appendLine("${record.timestampMs} [${record.level}] ${record.module}: ${record.message}")
        }
        appendLine("========================")
    }
}
