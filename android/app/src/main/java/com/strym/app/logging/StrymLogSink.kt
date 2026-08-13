package com.strym.app.logging

import android.util.Log
import uniffi.stream_ffi.LogLevel
import uniffi.stream_ffi.LogSink

/**
 * Routes the core's structured log records to logcat (debug) and keeps a
 * bounded, secret-redacted ring buffer for "report an issue" exports.
 *
 * Redaction happens here so neither the buffer nor the logcat line can leak a
 * stream key; secrets are registered on the [SecretRedactor] before a session
 * starts (see [com.strym.app.service.StreamService]).
 */
class StrymLogSink(
    private val redactor: SecretRedactor,
    private val buffer: CoreLogBuffer,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : LogSink {

    override fun onLog(level: LogLevel, module: String, message: String) {
        val clean = redactor.redact(message)
        buffer.append(
            LogRecord(
                timestampMs = clockMs(),
                level = level.name,
                module = module,
                message = clean,
            ),
        )
        val priority = level.toPriority()
        if (priority != NO_LOGCAT_PRIORITY) {
            Log.println(priority, CORE_TAG, "$module: $clean")
        }
    }

    private fun LogLevel.toPriority(): Int = when (this) {
        LogLevel.ERROR -> Log.ERROR
        LogLevel.WARN -> Log.WARN
        LogLevel.INFO -> Log.INFO
        LogLevel.DEBUG -> Log.DEBUG
        LogLevel.TRACE -> NO_LOGCAT_PRIORITY
    }

    companion object {
        private const val CORE_TAG = "strym-core"

        /** TRACE is too chatty for logcat; keep it only in the buffer. */
        private const val NO_LOGCAT_PRIORITY = -1
    }
}
