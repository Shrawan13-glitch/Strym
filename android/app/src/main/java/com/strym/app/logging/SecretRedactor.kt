package com.strym.app.logging

/**
 * Redacts secrets (stream keys) from log output. Thread-safe: secrets are
 * snapshotted on each [redact] call so readers never observe a half-applied
 * set of values.
 */
class SecretRedactor {

    private val secrets = ConcurrentSet<String>()

    /**
     * Track [secret] so future log records can mask it. Pass the live
     * settings value, which is a no-op for blank keys.
     */
    fun addSecret(secret: String) {
        val trimmed = secret.trim()
        if (trimmed.length >= MIN_SECRET_LENGTH) {
            secrets.add(trimmed)
        }
    }

    fun clearSecrets() = secrets.clear()

    /** Replace every tracked secret in [message] with [REDACTED_LABEL]. */
    fun redact(message: String): String {
        if (secrets.isEmpty()) return message
        val values = secrets.snapshot()
        var result = message
        for (value in values) {
            if (result.isEmpty()) break
            result = result.replace(value, REDACTED_LABEL)
        }
        return result
    }

    private class ConcurrentSet<T> {
        private val delegate = java.util.concurrent.ConcurrentHashMap.newKeySet<T>()
        fun add(value: T) = delegate.add(value)
        fun clear() = delegate.clear()
        fun isEmpty() = delegate.isEmpty()
        fun snapshot(): List<T> = delegate.toList()
    }

    companion object {
        const val REDACTED_LABEL = "[REDACTED]"

        /** Ignore values too short to matter (e.g. "x", blank keys). */
        const val MIN_SECRET_LENGTH = 4
    }
}
