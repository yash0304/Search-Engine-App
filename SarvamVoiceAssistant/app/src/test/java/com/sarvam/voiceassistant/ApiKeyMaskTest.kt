package com.sarvam.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiKeyMaskTest {

    @Test
    fun `masks the middle of a realistic key`() {
        assertEquals("sk_••••••7f3a", KeyMask.mask("sk_1234567890abcdef7f3a"))
    }

    @Test
    fun `never reveals most of a short key`() {
        // Prefix plus suffix would expose nearly all of a short secret.
        assertEquals("••••••", KeyMask.mask("abc123"))
        assertEquals("••••••", KeyMask.mask("12345678"))
    }

    @Test
    fun `blank keys mask to nothing`() {
        assertEquals("", KeyMask.mask(""))
        assertEquals("", KeyMask.mask("   "))
    }

    @Test
    fun `mask never contains the middle of the key`() {
        val key = "sk_SUPERSECRETMIDDLE_9999"
        val masked = KeyMask.mask(key)
        assertFalse(masked.contains("SUPERSECRETMIDDLE"))
        assertTrue(masked.length < key.length)
    }
}
