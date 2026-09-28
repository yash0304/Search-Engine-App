package com.sarvam.voiceassistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The phone's own speech recognition and voices, for when Sarvam cannot be reached. Quality
 * depends on the phone: Google's offline packs cover Hindi, Gujarati and English, and can be
 * added under Settings → System → Languages → On-device speech recognition.
 */
class DeviceSpeech(context: Context) {

    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady: CompletableDeferred<Boolean>? = null

    private val _level = MutableStateFlow(0f)

    /** Microphone level from 0 to 1 while listening, for the same animation as Sarvam turns. */
    val level: StateFlow<Float> = _level.asStateFlow()

    fun canListen(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    /**
     * Listens until the speaker pauses, or [stopListening] is called.
     *
     * @param languageCode such as "hi-IN"; the recogniser's own default when AUTO.
     */
    suspend fun listen(languageCode: String): String = withContext(Dispatchers.Main) {
        if (!canListen()) throw SarvamException("This phone has no speech recognition available offline.")

        suspendCancellableCoroutine { continuation ->
            val speech = SpeechRecognizer.createSpeechRecognizer(appContext)
            recognizer = speech

            fun done() {
                _level.value = 0f
                speech.destroy()
                if (recognizer === speech) recognizer = null
            }

            speech.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    done()
                    if (!continuation.isActive) return
                    if (text.isNullOrBlank()) {
                        continuation.resumeWithException(SarvamException("Nothing was recognised. Try speaking a little louder."))
                    } else {
                        continuation.resume(text)
                    }
                }

                override fun onError(error: Int) {
                    done()
                    if (continuation.isActive) continuation.resumeWithException(SarvamException(errorMessage(error)))
                }

                override fun onRmsChanged(rmsdB: Float) {
                    // Roughly -2 dB (silence) to 10 dB (speech) on most phones.
                    _level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                }

                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                if (languageCode != Language.AUTO.code) putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
            }
            speech.startListening(intent)

            continuation.invokeOnCancellation {
                // Cancellation can arrive on any thread; SpeechRecognizer must be used on main.
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runCatching { speech.cancel() }
                    done()
                }
            }
        }
    }

    /** Ends listening now; the words heard so far are still returned. */
    fun stopListening() {
        android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { recognizer?.stopListening() } }
    }

    /** Speaks [text] and suspends until it has finished. */
    suspend fun speak(text: String, languageCode: String) {
        val engine = engine() ?: throw SarvamException("This phone has no text-to-speech voice installed.")

        val wanted = Locale.forLanguageTag(languageCode)
        val result = engine.setLanguage(wanted)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "No phone voice for $languageCode; using English")
            engine.setLanguage(Locale.forLanguageTag("en-IN"))
        }

        val id = UUID.randomUUID().toString()
        suspendCancellableCoroutine { continuation ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id && continuation.isActive) continuation.resume(Unit)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id && continuation.isActive) continuation.resume(Unit)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    if (utteranceId == id && continuation.isActive) continuation.resume(Unit)
                }
            })
            if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
                continuation.resume(Unit)
            }
            continuation.invokeOnCancellation { engine.stop() }
        }
    }

    fun stopSpeaking() {
        runCatching { tts?.stop() }
    }

    fun close() {
        runCatching { recognizer?.destroy() }
        runCatching { tts?.shutdown() }
        recognizer = null
        tts = null
    }

    /** The phone's text-to-speech engine, started once; null if the phone has none. */
    private suspend fun engine(): TextToSpeech? {
        val pending = ttsReady ?: CompletableDeferred<Boolean>().also { ready ->
            ttsReady = ready
            withContext(Dispatchers.Main) {
                tts = TextToSpeech(appContext) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
            }
        }
        return if (pending.await()) tts else null
    }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            "Nothing was recognised. Try speaking a little louder."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Boliyan needs microphone permission."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
            "The phone's speech recognition needs its offline language pack. Add it under " +
                "Settings → System → Languages → On-device speech recognition, or type instead."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition is busy. Try again in a moment."
        else -> "The phone's speech recognition failed (code $error). Try typing instead."
    }

    private companion object {
        const val TAG = "DeviceSpeech"
    }
}
