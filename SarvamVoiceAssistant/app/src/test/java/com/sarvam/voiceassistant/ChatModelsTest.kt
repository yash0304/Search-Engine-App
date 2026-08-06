package com.sarvam.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatModelsTest {

    // ── Identifying chat models ──────────────────────────────────────────

    @Test
    fun `accepts chat model ids`() {
        assertTrue(ChatModels.looksLikeChat("sarvam-105b"))
        assertTrue(ChatModels.looksLikeChat("sarvam-30b"))
        assertTrue(ChatModels.looksLikeChat("sarvam-next-2027"))
    }

    @Test
    fun `rejects speech and translation models`() {
        assertFalse(ChatModels.looksLikeChat("saaras:v3"))
        assertFalse(ChatModels.looksLikeChat("bulbul:v3"))
        assertFalse(ChatModels.looksLikeChat("mayura:v1"))
        assertFalse(ChatModels.looksLikeChat("sarvam-translate"))
    }

    @Test
    fun `rejects blank and the literal string null`() {
        // Android's optString yields "null" for a JSON null; it must never become a model id.
        assertFalse(ChatModels.looksLikeChat("null"))
        assertFalse(ChatModels.looksLikeChat(""))
        assertFalse(ChatModels.looksLikeChat("   "))
    }

    // ── Ranking and picking ──────────────────────────────────────────────

    @Test
    fun `ranking puts preferred models first and drops non-chat ones`() {
        val ranked = ChatModels.rank(listOf("bulbul:v3", "sarvam-30b", "zzz-model", "sarvam-105b"))
        assertEquals(listOf("sarvam-105b", "sarvam-30b", "zzz-model"), ranked)
    }

    @Test
    fun `picks the most preferred model available`() {
        assertEquals("sarvam-105b", ChatModels.pick(listOf("sarvam-30b", "sarvam-105b")))
        assertEquals("sarvam-30b", ChatModels.pick(listOf("sarvam-30b")))
    }

    @Test
    fun `picks an unknown model rather than nothing`() {
        // The point of runtime discovery: a model we have never heard of must still be used.
        assertEquals("sarvam-future", ChatModels.pick(listOf("sarvam-future")))
    }

    @Test
    fun `falls back when nothing is available`() {
        assertEquals(ChatModels.FALLBACK, ChatModels.pick(emptyList()))
    }

    // ── Recovering from a deprecation error ──────────────────────────────

    @Test
    fun `extracts the replacement model from a deprecation error`() {
        val body = """{"error":"Model sarvam-30b is deprecated, please use sarvam-105b instead."}"""
        assertEquals("sarvam-105b", ChatModels.suggestedFrom(body, tried = "sarvam-30b"))
    }

    @Test
    fun `handles the real world sarvam-m to sarvam-30b message`() {
        val body = "sarvam-m has been deprecated. Migrate to sarvam-30b or sarvam-105b."
        assertEquals("sarvam-30b", ChatModels.suggestedFrom(body, tried = "sarvam-m"))
    }

    @Test
    fun `strips trailing punctuation from the suggestion`() {
        val body = "Use sarvam-105b."
        assertEquals("sarvam-105b", ChatModels.suggestedFrom(body, tried = "sarvam-30b"))
    }

    @Test
    fun `returns null when the error only names the model we already tried`() {
        val body = """{"error":"sarvam-30b is deprecated"}"""
        assertNull(ChatModels.suggestedFrom(body, tried = "sarvam-30b"))
    }

    @Test
    fun `returns null when no model is named`() {
        assertNull(ChatModels.suggestedFrom("""{"error":"rate limited"}""", tried = "sarvam-105b"))
    }

    @Test
    fun `never suggests a speech model`() {
        val body = "sarvam-30b retired; saaras:v3 unaffected"
        assertNull(ChatModels.suggestedFrom(body, tried = "sarvam-30b"))
    }

    // ── Thinking removal ─────────────────────────────────────────────────

    @Test
    fun `strips inlined think blocks`() {
        val text = "<think>The user greeted me, so I greet back.</think>Hello, how are you?"
        assertEquals("Hello, how are you?", ChatModels.stripThinking(text))
    }

    @Test
    fun `strips think blocks spanning newlines`() {
        val text = "<think>line one\nline two</think>\nNamaste!"
        assertEquals("Namaste!", ChatModels.stripThinking(text))
    }

    @Test
    fun `leaves ordinary replies untouched`() {
        assertEquals("કેમ છો?", ChatModels.stripThinking("કેમ છો?"))
    }
}
