package com.sarvam.voiceassistant

import java.util.concurrent.atomic.AtomicLong

enum class Role { USER, ASSISTANT }

private val messageIds = AtomicLong(0)

data class Message(
    val role: Role,
    val text: String,
    val languageCode: String? = null,
    /** When it was sent; it disappears [ConversationStore.LIFETIME_MS] after this. */
    val sentAt: Long = System.currentTimeMillis(),
    /** For list keys only — not saved, so reloaded messages simply get new ones. */
    val id: Long = messageIds.incrementAndGet(),
)
