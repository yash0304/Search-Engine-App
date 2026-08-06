package com.sarvam.voiceassistant

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds the system prompt sent with every chat turn.
 *
 * The date is injected deliberately. A language model has no clock and no calendar — asked
 * what day it is, it answers from its training data, which is how the assistant confidently
 * reported a date from 2024. Passing the device's real date on each turn is the only way it
 * can answer correctly, and it must be rebuilt per request so it does not go stale in a
 * long-running session.
 */
object SystemPrompt {

    private val DATE_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)

    private val TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    fun build(now: ZonedDateTime): String {
        val date = DATE_FORMAT.format(now)
        val time = TIME_FORMAT.format(now)
        val zone = now.zone.id

        return """
            You are a helpful, friendly voice assistant.
            You understand Gujarati, Hindi, and English, including code-mixed speech.
            ALWAYS reply in the SAME language the user spoke — never switch unless asked.
            Keep answers short and conversational, at most 2-3 sentences, because they are spoken aloud.
            Never use markdown, bullet points, emoji, or special formatting characters.

            The current date is $date and the local time is $time ($zone).
            Treat this as the truth about today, over anything you learned during training.
            You have no internet access, so for anything that changes in real time — weather,
            news, prices, live scores — say plainly that you cannot look it up.
        """.trimIndent()
    }

    fun now(): String = build(ZonedDateTime.now())
}
