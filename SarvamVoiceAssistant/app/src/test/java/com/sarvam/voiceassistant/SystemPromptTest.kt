package com.sarvam.voiceassistant

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model has no clock, so whatever the prompt says is what it will believe. Without the
 * injected date it answered with a date from its training data.
 */
class SystemPromptTest {

    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val moment = ZonedDateTime.of(2026, 8, 6, 14, 30, 0, 0, kolkata)

    @Test
    fun `states the date in an unambiguous form`() {
        val prompt = SystemPrompt.build(moment)
        // Spelled out, so "6/8" is never read as June 8th.
        assertTrue(prompt.contains("Thursday, 6 August 2026"))
    }

    @Test
    fun `includes local time and zone`() {
        val prompt = SystemPrompt.build(moment)
        assertTrue(prompt.contains("14:30"))
        assertTrue(prompt.contains("Asia/Kolkata"))
    }

    @Test
    fun `tells the model to trust the given date over its training`() {
        val prompt = SystemPrompt.build(moment).lowercase()
        assertTrue(prompt.contains("truth about today"))
        assertTrue(prompt.contains("training"))
    }

    @Test
    fun `says it can check weather`() {
        val prompt = SystemPrompt.build(moment).lowercase()
        assertTrue(prompt.contains("weather"))
        assertTrue(prompt.contains("rain"))
    }

    @Test
    fun `admits it cannot check road traffic`() {
        // There is no free source for Indian road incidents, so the model must not guess —
        // a wrong answer about a jam is worse than no answer to someone driving.
        val prompt = SystemPrompt.build(moment).lowercase()
        assertTrue(prompt.contains("cannot check live road traffic"))
        assertTrue(prompt.contains("never guess"))
    }

    @Test
    fun `asks for distances rather than vague answers`() {
        val prompt = SystemPrompt.build(moment)
        assertTrue(prompt.contains("distances"))
    }

    @Test
    fun `keeps the trilingual and brevity instructions`() {
        val prompt = SystemPrompt.build(moment)
        assertTrue(prompt.contains("Gujarati"))
        assertTrue(prompt.contains("SAME language"))
        assertTrue(prompt.contains("2-3 sentences"))
    }

    @Test
    fun `a different day produces a different prompt`() {
        val tomorrow = moment.plusDays(1)
        assertTrue(SystemPrompt.build(moment) != SystemPrompt.build(tomorrow))
    }

    @Test
    fun `midnight formats as zero padded`() {
        val midnight = ZonedDateTime.of(2026, 1, 1, 0, 5, 0, 0, kolkata)
        assertTrue(SystemPrompt.build(midnight).contains("00:05"))
    }
}
