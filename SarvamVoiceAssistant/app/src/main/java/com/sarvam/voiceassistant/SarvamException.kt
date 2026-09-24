package com.sarvam.voiceassistant

/** An API failure with a message that is safe to show in the UI. */
class SarvamException(message: String, cause: Throwable? = null) : Exception(message, cause)
