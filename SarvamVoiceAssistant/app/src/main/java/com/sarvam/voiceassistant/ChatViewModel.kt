package com.sarvam.voiceassistant

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicLong

enum class Role { USER, ASSISTANT }

private val messageIds = AtomicLong(0)

data class Message(
    val role: Role,
    val text: String,
    val languageCode: String? = null,
    val id: Long = messageIds.incrementAndGet(),
)

/** What the pipeline is currently doing — drives the status line and the mic button. */
enum class Stage { IDLE, RECORDING, TRANSCRIBING, THINKING, SPEAKING }

data class UiState(
    val messages: List<Message> = emptyList(),
    val stage: Stage = Stage.IDLE,
    val error: String? = null,
    val hasApiKey: Boolean = false,
    val speaker: String = "",
    val inputLanguage: String = Language.AUTO.code,
    /** Empty means the model is discovered automatically. */
    val chatModel: String = "",
    /** Chat models reported by /v1/models; empty until discovery runs or if it fails. */
    val availableModels: List<String> = emptyList(),
    val loadingModels: Boolean = false,
    val lockEnabled: Boolean = true,
) {
    val isBusy: Boolean get() = stage != Stage.IDLE
}

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val store = ApiKeyStore(application)
    private val recorder = AudioRecorder(application.cacheDir)
    private val player = AudioPlayer()

    private var client: SarvamClient? = null
    private var pipeline: Job? = null

    private val _uiState = MutableStateFlow(
        UiState(
            hasApiKey = store.hasApiKey(),
            speaker = store.preferredSpeaker,
            inputLanguage = store.inputLanguage,
            chatModel = store.chatModel,
            lockEnabled = store.lockEnabled,
        ),
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Microphone level, forwarded for the recording animation. */
    val amplitude: StateFlow<Float> = recorder.amplitude

    init {
        if (store.hasApiKey()) {
            client = newClient(store.apiKey)
            refreshModels()
        }
    }

    private fun newClient(key: String) = SarvamClient(key).apply {
        preferredChatModel = store.chatModel.ifBlank { null }
    }

    companion object {
        private const val TAG = "ChatViewModel"

        /** The speech-to-text endpoint accepts up to 30 seconds of audio. */
        private const val MAX_RECORD_MS = 25_000L
    }

    // ── Settings ─────────────────────────────────────────────────────────

    /**
     * A blank [key] means "leave the stored key alone" — Settings shows only a masked
     * placeholder, so an empty field is the normal case when changing other settings.
     */
    fun saveApiKey(key: String) {
        if (key.isBlank()) return

        store.apiKey = key
        client = newClient(key)
        _uiState.update { it.copy(hasApiKey = true, error = null) }
        refreshModels()
    }

    fun clearApiKey() {
        store.apiKey = ""
        client = null
        _uiState.update { it.copy(hasApiKey = false, availableModels = emptyList()) }
    }

    /**
     * A masked stand-in such as `sk_••••••7f3a`. The real key is deliberately never exposed
     * to the UI layer, so it cannot be read off the screen or captured in a screenshot.
     */
    fun maskedKey(): String = store.maskedKey()

    fun isLockEnabled(): Boolean = store.lockEnabled

    fun setLockEnabled(enabled: Boolean) {
        store.lockEnabled = enabled
        _uiState.update { it.copy(lockEnabled = enabled) }
    }

    /**
     * Ask the API which chat models this key can use. Silent on failure — the client still
     * falls back and self-corrects on the next request, so a failed listing is not worth
     * interrupting the user over.
     */
    fun refreshModels() {
        val api = client ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(loadingModels = true) }
            val models = runCatching { api.listChatModels() }.getOrDefault(emptyList())
            _uiState.update { it.copy(availableModels = models, loadingModels = false) }
        }
    }

    /** Empty string means "discover automatically". */
    fun setChatModel(model: String) {
        store.chatModel = model
        client?.apply {
            preferredChatModel = model.ifBlank { null }
            invalidateModelCache()
        }
        _uiState.update { it.copy(chatModel = model) }
    }

    fun setSpeaker(speaker: String) {
        store.preferredSpeaker = speaker
        _uiState.update { it.copy(speaker = speaker) }
    }

    fun setInputLanguage(code: String) {
        store.inputLanguage = code
        _uiState.update { it.copy(inputLanguage = code) }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    fun clearConversation() {
        cancelPipeline()
        client?.resetConversation()
        _uiState.update { it.copy(messages = emptyList(), error = null) }
    }

    // ── Voice pipeline ───────────────────────────────────────────────────

    /** Tap to start recording; tap again to stop and send. */
    fun onMicTapped() {
        when (_uiState.value.stage) {
            Stage.RECORDING -> recorder.requestStop()
            Stage.IDLE -> startVoiceTurn()
            else -> Unit // Busy transcribing/thinking/speaking; ignore.
        }
    }

    private fun startVoiceTurn() {
        val api = client ?: run {
            showError("Add your Sarvam API key in Settings first.")
            return
        }

        // Set synchronously: launch{} does not run until the dispatcher schedules it, so a
        // fast second tap would otherwise still see IDLE and open a second AudioRecord.
        setStage(Stage.RECORDING)

        pipeline = viewModelScope.launch {
            try {
                val audio = recorder.record(MAX_RECORD_MS)

                setStage(Stage.TRANSCRIBING)
                val transcription = api.transcribe(audio, _uiState.value.inputLanguage)
                addMessage(Message(Role.USER, transcription.transcript, transcription.languageCode))

                respondTo(api, transcription.transcript, transcription.languageCode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                if (_uiState.value.stage != Stage.IDLE) setStage(Stage.IDLE)
            }
        }
    }

    /** Send a typed message in an explicitly chosen language. */
    fun sendText(text: String, languageCode: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _uiState.value.isBusy) return

        val api = client ?: run {
            showError("Add your Sarvam API key in Settings first.")
            return
        }

        // Same reasoning as startVoiceTurn: claim the busy state before suspending.
        setStage(Stage.THINKING)

        pipeline = viewModelScope.launch {
            try {
                addMessage(Message(Role.USER, trimmed, languageCode))
                respondTo(api, trimmed, languageCode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                if (_uiState.value.stage != Stage.IDLE) setStage(Stage.IDLE)
            }
        }
    }

    /** Shared tail of both paths: ask the model, then speak the reply. */
    private suspend fun respondTo(api: SarvamClient, prompt: String, languageCode: String) {
        setStage(Stage.THINKING)
        val reply = api.chat(prompt)
        addMessage(Message(Role.ASSISTANT, reply, languageCode))

        setStage(Stage.SPEAKING)
        val speaker = _uiState.value.speaker.ifBlank { Voices.defaultSpeakerFor(languageCode) }
        val audioBytes = api.synthesize(reply, languageCode, speaker)

        val file = File(getApplication<Application>().cacheDir, "reply.wav")
        file.writeBytes(audioBytes)
        player.play(file) // Suspends until the reply has actually finished playing.

        setStage(Stage.IDLE)
    }

    fun cancelPipeline() {
        recorder.requestStop()
        player.stop()
        pipeline?.cancel()
        pipeline = null
        setStage(Stage.IDLE)
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun fail(e: Exception) {
        Log.e(TAG, "Pipeline failed", e)
        showError(
            when (e) {
                is SarvamException -> e.message ?: "Something went wrong."
                is IllegalStateException -> e.message ?: "The microphone is unavailable."
                else -> "Something went wrong: ${e.message ?: e::class.java.simpleName}"
            },
        )
    }

    private fun showError(message: String) = _uiState.update { it.copy(error = message, stage = Stage.IDLE) }

    private fun setStage(stage: Stage) = _uiState.update { it.copy(stage = stage) }

    private fun addMessage(message: Message) =
        _uiState.update { it.copy(messages = it.messages + message) }

    override fun onCleared() {
        super.onCleared()
        recorder.requestStop()
        player.stop()
    }
}
