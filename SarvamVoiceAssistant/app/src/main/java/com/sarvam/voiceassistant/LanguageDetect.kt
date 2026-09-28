package com.sarvam.voiceassistant

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.text.languagedetector.LanguageDetector

/**
 * On-device language detection with MediaPipe, from Google AI Edge. Free, offline, and a
 * 315 KB model bundled in assets.
 *
 * It fills the gap the script check in [ReplyLanguage] cannot: text in English letters. That
 * could be English or Hindi typed in Roman letters, and guessing wrong puts an English reply
 * in a Hindi voice.
 */
class LanguageDetect(context: Context) {

    private val appContext = context.applicationContext

    private val detector: LanguageDetector? by lazy {
        runCatching { LanguageDetector.createFromFile(appContext, ASSET) }
            .onFailure { Log.w(TAG, "Language detector unavailable; falling back to script checks", it) }
            .getOrNull()
    }

    /** @return an app language code such as "hi-IN", or null when unsure or on any failure. */
    fun detect(text: String): String? {
        if (text.isBlank()) return null
        val best = runCatching {
            detector?.detect(text)?.languagesAndScores()?.maxByOrNull { it.probability() }
        }.getOrNull() ?: return null
        return DetectedLanguage.toAppCode(best.languageCode(), best.probability())
    }

    fun close() {
        runCatching { detector?.close() }
    }

    private companion object {
        const val TAG = "LanguageDetect"
        const val ASSET = "language_detector.tflite"
    }
}
