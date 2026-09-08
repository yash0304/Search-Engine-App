package com.sarvam.voiceassistant

/**
 * Redacts the API key for display. Kept free of Android types so it is unit testable —
 * a mistake here leaks the secret this app is meant to protect.
 */
object KeyMask {

    fun mask(key: String): String {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return ""
        // With only a few characters, a prefix plus suffix would give away most of the key.
        if (trimmed.length <= 8) return "••••••"
        return "${trimmed.take(3)}••••••${trimmed.takeLast(4)}"
    }
}
