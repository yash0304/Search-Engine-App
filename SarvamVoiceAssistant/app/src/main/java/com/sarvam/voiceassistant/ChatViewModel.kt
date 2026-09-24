package com.sarvam.voiceassistant

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the pipeline is currently doing — drives the status line and the mic button. */
enum class Stage { IDLE, RECORDING, TRANSCRIBING, THINKING, SEARCHING, SPEAKING, READING }

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
    val webSearchEnabled: Boolean = true,
    val locationEnabled: Boolean = true,
    /** The query the model is currently looking up, for the status line. */
    val searchQuery: String? = null,
    /** Human-readable state of the offline dictionary, shown in Settings. */
    val dictionaryStatus: String = "Checking…",
    val streamingEnabled: Boolean = true,
    val autoStopListening: Boolean = true,
    val sttMode: String = SpeechOptions.DEFAULT_MODE,
    val sttModel: String = SpeechOptions.DEFAULT_STT_MODEL,
    /**
     * How the last turn was actually heard and spoken — streamed or standard, and why. The
     * streaming protocol was built from Sarvam's SDK but never heard live before release, so
     * this is what shows whether it works on a real phone.
     */
    val speechDiagnostics: String = "No voice turn yet.",
    /**
     * Whether the conversation is on screen. Back returns to the welcome page without losing
     * the chat, which stays available from there until its messages expire.
     */
    val viewingConversation: Boolean = false,
    /** When the oldest message disappears, for the "disappears in 5h" note; null if none. */
    val nextExpiry: Long? = null,
) {
    val isBusy: Boolean get() = stage != Stage.IDLE
}

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val store = ApiKeyStore(application)
    private val recorder = AudioRecorder(application.cacheDir)
    private val player = AudioPlayer()
    private val location = LocationProvider(application)
    private val dictionary = OfflineDictionary(application)

    private var client: SarvamClient? = null
    private var pipeline: Job? = null

    /** The chat, kept on the phone for 24 hours; see [ConversationStore]. */
    private val conversations = ConversationStore(File(application.filesDir, CONVERSATION_FILE))
    private val saved = conversations.load()

    /** Documents shared into this conversation, which expire with it. */
    private val sharedDocuments = saved.documents.toMutableList()

    private val _uiState = MutableStateFlow(
        UiState(
            messages = saved.messages,
            nextExpiry = saved.nextExpiry(),
            hasApiKey = store.hasApiKey(),
            speaker = store.preferredSpeaker,
            inputLanguage = store.inputLanguage,
            chatModel = store.chatModel,
            lockEnabled = store.lockEnabled,
            webSearchEnabled = store.webSearchEnabled,
            locationEnabled = store.locationEnabled,
            streamingEnabled = store.streamingEnabled,
            autoStopListening = store.autoStopListening,
            sttMode = store.sttMode,
            sttModel = store.sttModel,
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
        // Messages expire while the app is open too, not only between launches.
        viewModelScope.launch {
            while (true) {
                delay(EXPIRY_CHECK_MS)
                dropExpired()
            }
        }
    }

    private fun newClient(key: String) = SarvamClient(key).apply {
        preferredChatModel = store.chatModel.ifBlank { null }
        webSearchEnabled = store.webSearchEnabled
        // Resolved lazily per tool call, so a denied permission simply yields null and the
        // model asks the user to name a place instead.
        locationSource = { if (store.locationEnabled) location.current() else null }
        dictionarySource = { word -> dictionary.lookup(word) }
        // Picks up where the saved conversation left off, so a reopened app still has context.
        restoreConversation(_uiState.value.messages, sharedDocuments.map { it.name to it.text })
    }

    companion object {
        private const val TAG = "ChatViewModel"

        /** The speech-to-text endpoint accepts up to 30 seconds of audio. */
        private const val MAX_RECORD_MS = 25_000L

        private const val CONVERSATION_FILE = "conversation.json"
        private const val EXPIRY_CHECK_MS = 60_000L
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

    fun setLocationEnabled(enabled: Boolean) {
        store.locationEnabled = enabled
        _uiState.update { it.copy(locationEnabled = enabled) }
    }

    /** Reports whether the offline dictionary actually loaded, and why not if it did not. */
    fun refreshDictionaryStatus() {
        viewModelScope.launch {
            val text = when (val status = dictionary.status()) {
                is OfflineDictionary.Status.Ready ->
                    "Ready — ${"%,d".format(status.senseCount)} entries"
                is OfflineDictionary.Status.Unavailable ->
                    "Unavailable — ${status.reason}"
            }
            _uiState.update { it.copy(dictionaryStatus = text) }
        }
    }

    /** Deletes and re-expands the dictionary, for a first attempt that failed part-way. */
    fun rebuildDictionary() {
        viewModelScope.launch {
            _uiState.update { it.copy(dictionaryStatus = "Rebuilding…") }
            val text = when (val status = dictionary.rebuild()) {
                is OfflineDictionary.Status.Ready ->
                    "Ready — ${"%,d".format(status.senseCount)} entries"
                is OfflineDictionary.Status.Unavailable ->
                    "Still unavailable — ${status.reason}"
            }
            _uiState.update { it.copy(dictionaryStatus = text) }
        }
    }

    fun setSpeechOptions(streaming: Boolean, autoStop: Boolean, sttMode: String, sttModel: String) {
        store.streamingEnabled = streaming
        store.autoStopListening = autoStop
        store.sttMode = sttMode
        store.sttModel = sttModel
        _uiState.update {
            it.copy(
                streamingEnabled = store.streamingEnabled,
                autoStopListening = store.autoStopListening,
                sttMode = store.sttMode,
                sttModel = store.sttModel,
            )
        }
    }

    /** True when the OS has already granted a location permission. */
    fun hasLocationPermission(): Boolean = location.hasPermission()

    fun setWebSearchEnabled(enabled: Boolean) {
        store.webSearchEnabled = enabled
        client?.webSearchEnabled = enabled
        _uiState.update { it.copy(webSearchEnabled = enabled) }
    }

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

    /** The trash button: deletes the conversation now, rather than waiting out the 24 hours. */
    fun clearConversation() {
        cancelPipeline()
        client?.resetConversation()
        sharedDocuments.clear()
        conversations.clear()
        _uiState.update {
            it.copy(messages = emptyList(), error = null, viewingConversation = false, nextExpiry = null)
        }
    }

    /** Back from the chat goes to the welcome page; "Continue" there comes back. */
    fun showConversation(visible: Boolean) {
        _uiState.update { it.copy(viewingConversation = visible && it.messages.isNotEmpty()) }
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
            val state = _uiState.value
            // Opened before recording so words are transcribed while you are still talking.
            val live = if (state.streamingEnabled) {
                runCatching {
                    api.streaming.openTranscription(
                        languageCode = state.inputLanguage,
                        model = state.sttModel,
                        mode = state.sttMode,
                        onSpeechEnded = { if (_uiState.value.autoStopListening) recorder.requestStop() },
                    )
                }.getOrNull()
            } else {
                null
            }

            try {
                val audio = recorder.record(MAX_RECORD_MS) { pcm, length -> live?.send(pcm, length) }

                setStage(Stage.TRANSCRIBING)
                val streamed = live?.finish()
                val heard = if (streamed != null) "streamed" else {
                    if (live != null) "standard (stream: ${live.failureReason() ?: "no transcript"})" else "standard"
                }
                val transcription = streamed ?: api.transcribe(audio, state.inputLanguage, state.sttModel, state.sttMode)
                // In translate mode the text is English whatever was spoken; tagging it with the
                // spoken language would have the English reply read by, say, a Hindi voice.
                val textLanguage = if (state.sttMode == "translate") "en-IN" else transcription.languageCode
                addMessage(Message(Role.USER, transcription.transcript, textLanguage))
                noteSpeech(heard = heard)

                respondTo(api, transcription.transcript, textLanguage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                live?.cancel()
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
        val reply = api.chat(prompt) { query ->
            _uiState.update { it.copy(stage = Stage.SEARCHING, searchQuery = query) }
        }
        _uiState.update { it.copy(searchQuery = null) }
        addMessage(Message(Role.ASSISTANT, reply, languageCode))

        setStage(Stage.SPEAKING)
        // Speak in the language the reply is written in, which is not always the question's.
        val spokenLanguage = ReplyLanguage.detect(reply, languageCode)
        val speaker = _uiState.value.speaker.ifBlank { Voices.defaultSpeakerFor(spokenLanguage) }

        if (_uiState.value.streamingEnabled) {
            try {
                api.speakStreaming(reply, spokenLanguage, speaker, PcmPlayer())
                noteSpeech(spoke = "streamed")
                setStage(Stage.IDLE)
                return
            } catch (e: StreamingException) {
                Log.w(TAG, "Streaming speech failed", e)
                if (e.audioStarted) {
                    // Half the reply was heard; starting again from the top would be worse.
                    noteSpeech(spoke = "streamed, cut off (${e.message})")
                    setStage(Stage.IDLE)
                    return
                }
                noteSpeech(spoke = "standard (stream: ${e.message})")
            }
        } else {
            noteSpeech(spoke = "standard")
        }

        val audioBytes = api.synthesize(reply, spokenLanguage, speaker)
        val file = File(getApplication<Application>().cacheDir, "reply.wav")
        file.writeBytes(audioBytes)
        player.play(file) // Suspends until the reply has actually finished playing.

        setStage(Stage.IDLE)
    }

    private var lastHeard = "—"
    private var lastSpoke = "—"

    private fun noteSpeech(heard: String? = null, spoke: String? = null) {
        heard?.let { lastHeard = it }
        spoke?.let { lastSpoke = it }
        _uiState.update { it.copy(speechDiagnostics = "Last turn — heard: $lastHeard; spoke: $lastSpoke") }
    }

    // ── Documents ────────────────────────────────────────────────────────

    /** Reads a PDF or photo with Document Intelligence and adds it to the conversation. */
    fun readDocument(uri: Uri) {
        if (_uiState.value.isBusy) return
        val api = client ?: run {
            showError("Add your Sarvam API key in Settings first.")
            return
        }

        setStage(Stage.READING)
        pipeline = viewModelScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) { DocumentInput.prepare(getApplication<Application>(), uri) }
                val language = _uiState.value.inputLanguage.takeIf { it != Language.AUTO.code } ?: "en-IN"

                val text = api.documents.read(prepared.pdf, language) { step ->
                    _uiState.update { it.copy(searchQuery = "$step ${prepared.name}") }
                }
                api.attachDocument(prepared.name, text)
                sharedDocuments.add(ConversationStore.SavedDocument(prepared.name, text, System.currentTimeMillis()))
                _uiState.update { it.copy(searchQuery = null) }

                val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
                addMessage(
                    Message(
                        Role.ASSISTANT,
                        "I've read \"${prepared.name}\" ($words words). Ask me anything about it — " +
                            "to summarise it, translate it, or find something in it.",
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                _uiState.update { it.copy(searchQuery = null) }
                if (_uiState.value.stage != Stage.IDLE) setStage(Stage.IDLE)
            }
        }
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

    private fun addMessage(message: Message) {
        _uiState.update { it.copy(messages = it.messages + message, viewingConversation = true) }
        persist()
    }

    /** Saves the conversation off the main thread; the store writes atomically. */
    private fun persist() {
        val snapshot = ConversationStore.Snapshot(_uiState.value.messages, sharedDocuments.toList())
        _uiState.update { it.copy(nextExpiry = snapshot.nextExpiry()) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { conversations.save(snapshot) }.onFailure { Log.w(TAG, "Could not save the conversation", it) }
        }
    }

    /** Drops whatever has passed its 24 hours, from the screen and from the model's memory. */
    private fun dropExpired() {
        val current = ConversationStore.Snapshot(_uiState.value.messages, sharedDocuments.toList())
        val kept = conversations.prune(current)
        if (kept.messages.size == current.messages.size && kept.documents.size == current.documents.size) return

        sharedDocuments.clear()
        sharedDocuments.addAll(kept.documents)
        client?.restoreConversation(kept.messages, kept.documents.map { it.name to it.text })
        _uiState.update {
            it.copy(
                messages = kept.messages,
                nextExpiry = kept.nextExpiry(),
                viewingConversation = it.viewingConversation && kept.messages.isNotEmpty(),
            )
        }
        persist()
    }

    override fun onCleared() {
        super.onCleared()
        recorder.requestStop()
        player.stop()
        dictionary.close()
    }
}
