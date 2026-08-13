package com.strym.app.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactorTest {

    @Test
    fun redactsTrackedSecretEverywhereItAppears() {
        val redactor = SecretRedactor()
        redactor.addSecret("abc-secret-123")

        val message = "publishing to rtmp://x with key abc-secret-123 and again abc-secret-123"
        assertEquals(
            "publishing to rtmp://x with key [REDACTED] and again [REDACTED]",
            redactor.redact(message),
        )
    }

    @Test
    fun redactsWhenSecretIsSubstringOfOtherContent() {
        val redactor = SecretRedactor()
        redactor.addSecret("cdef")
        assertEquals("ab[REDACTED]gh", redactor.redact("abcdefgh"))
    }

    @Test
    fun ignoresShortOrBlankSecrets() {
        val redactor = SecretRedactor()
        redactor.addSecret("")
        redactor.addSecret("  ")
        redactor.addSecret("x")
        assertEquals("raw message", redactor.redact("raw message"))
    }

    @Test
    fun noSecretsMeansNoChange() {
        val redactor = SecretRedactor()
        assertEquals("hello world", redactor.redact("hello world"))
    }

    @Test
    fun clearSecretsStopsRedaction() {
        val redactor = SecretRedactor()
        redactor.addSecret("key12345")
        assertNotEquals("key key12345", redactor.redact("key key12345"))
        redactor.clearSecrets()
        assertEquals("key key12345", redactor.redact("key key12345"))
    }

    @Test
    fun multipleSecretsAllRedacted() {
        val redactor = SecretRedactor()
        redactor.addSecret("first-secret")
        redactor.addSecret("second-secret")
        assertEquals(
            "[REDACTED] and [REDACTED]",
            redactor.redact("first-secret and second-secret"),
        )
        assertFalse(redactor.redact("first-secret").contains("first-secret"))
        assertTrue(redactor.redact("nothing").isNotEmpty())
    }
}
