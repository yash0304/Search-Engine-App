package com.sarvam.voiceassistant

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZonedDateTime
import com.google.ai.edge.litertlm.Message as LlmMessage

/**
 * Answers on the phone with Gemma 4 E2B through LiteRT-LM, from Google AI Edge. The same
 * model also hears speech and reads images, which is how voice and documents work offline.
 *
 * The engine takes several seconds and about 3 GB of memory to load, so it is loaded on the
 * first offline question and kept until the app closes or [release] is called. Each question
 * gets a fresh conversation seeded with the chat's recent turns, so switching chats — or
 * between Sarvam and the phone mid-chat — never mixes memories.
 */
class OnDeviceLlm(context: Context) {

    private val cacheDir = context.applicationContext.cacheDir.path
    private val lock = Mutex()
    private var engine: Engine? = null
    private var loadedFrom: String? = null

    /** Which hardware ended up running the model, for the diagnostics line. */
    var backendName: String = "not loaded"
        private set

    /** Whether the image and audio parts loaded; a last-resort text-only load leaves them out. */
    private var multimodal = false

    /**
     * @param history the chat so far, without [prompt].
     * @param documents documents shared in this chat, as (name, text).
     */
    suspend fun reply(
        model: File,
        history: List<Message>,
        prompt: String,
        documents: List<Pair<String, String>>,
    ): String = withContext(Dispatchers.Default) {
        lock.withLock {
            val ready = engineFor(model)
            val config = ConversationConfig(
                systemInstruction = Contents.of(OnDeviceModel.systemInstruction(ZonedDateTime.now(), documents)),
                initialMessages = OnDeviceModel.history(history).map {
                    if (it.role == Role.USER) LlmMessage.user(it.text) else LlmMessage.model(it.text)
                },
                samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.8),
                maxOutputToken = OnDeviceModel.MAX_OUTPUT_TOKENS,
                // Thinking would double the wait for a spoken two-sentence answer.
                thinkingConfig = ThinkingConfig(enableThinking = false),
            )

            val text = run(ready, config, Contents.of(prompt))
            OnDeviceModel.cleanReply(text)
                ?: throw SarvamException("The on-device model gave an empty answer. Try asking again.")
        }
    }

    /** What was said in [wav] (16 kHz mono WAV), in the speaker's own script; null if nothing. */
    suspend fun transcribe(model: File, wav: ByteArray): String? = withContext(Dispatchers.Default) {
        lock.withLock {
            val ready = multimodalEngine(model, "hear speech")
            OnDeviceModel.cleanTranscript(
                run(ready, exactConfig(), Contents.of(Content.AudioBytes(wav), Content.Text(OnDeviceModel.TRANSCRIBE_PROMPT))),
            )
        }
    }

    /** The text on one page image (PNG), or a description if it has little text. */
    suspend fun readPage(model: File, png: ByteArray): String = withContext(Dispatchers.Default) {
        lock.withLock {
            val ready = multimodalEngine(model, "read images")
            OnDeviceModel.cleanReply(
                run(ready, exactConfig(), Contents.of(Content.ImageBytes(png), Content.Text(OnDeviceModel.READ_PAGE_PROMPT))),
            ).orEmpty()
        }
    }

    private fun multimodalEngine(model: File, what: String): Engine {
        val ready = engineFor(model)
        if (!multimodal) {
            throw SarvamException("This phone could load the on-device model for chat, but not the part that can $what.")
        }
        return ready
    }

    /** Copying, not creating: always the likeliest word, and room for a full page of text. */
    private fun exactConfig() = ConversationConfig(
        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
        maxOutputToken = 2_048,
        thinkingConfig = ThinkingConfig(enableThinking = false),
    )

    private suspend fun run(engine: Engine, config: ConversationConfig, contents: Contents): String =
        engine.createConversation(config).use { conversation ->
            val text = StringBuilder()
            try {
                conversation.sendMessageAsync(contents).collect { text.append(it.toString()) }
            } catch (e: CancellationException) {
                runCatching { conversation.cancelProcess() }
                throw e
            }
            text.toString()
        }

    /** Frees the model's memory; it reloads on the next question. */
    fun release() {
        runCatching { engine?.close() }
        engine = null
        loadedFrom = null
        multimodal = false
        backendName = "not loaded"
    }

    /** The GPU is several times faster; the CPU is the fallback for phones whose GPU it cannot use. */
    private fun engineFor(model: File): Engine {
        engine?.takeIf { loadedFrom == model.path }?.let { return it }
        release()

        // Images and speech need their own encoders loaded; a phone that cannot manage those
        // still gets text chat from the last attempt.
        val attempts = listOf(
            Attempt("GPU", multimodal = true) { EngineConfig(model.path, Backend.GPU(), Backend.GPU(), Backend.CPU(), maxNumImages = 1, cacheDir = cacheDir) },
            // Google's Gallery app runs Gemma's image encoder on the GPU and its audio on the CPU.
            Attempt("CPU", multimodal = true) { EngineConfig(model.path, Backend.CPU(), Backend.GPU(), Backend.CPU(), maxNumImages = 1, cacheDir = cacheDir) },
            Attempt("CPU, text only", multimodal = false) { EngineConfig(model.path, Backend.CPU(), cacheDir = cacheDir) },
        )
        var lastError: Throwable? = null
        for ((name, withMedia, config) in attempts) {
            try {
                val candidate = Engine(config())
                candidate.initialize()
                engine = candidate
                loadedFrom = model.path
                backendName = name
                multimodal = withMedia
                Log.i(TAG, "Loaded ${OnDeviceModel.NAME} on $name")
                return candidate
            } catch (e: Throwable) {
                if (e is OutOfMemoryError) throw SarvamException("Not enough free memory for the on-device model. Close other apps and try again.", e)
                Log.w(TAG, "Could not load the model on $name", e)
                lastError = e
            }
        }
        throw SarvamException(
            "The on-device model could not be loaded (${lastError?.message ?: "unknown error"}). " +
                "Try deleting and downloading it again.",
            lastError,
        )
    }

    private data class Attempt(val name: String, val multimodal: Boolean, val config: () -> EngineConfig)

    private companion object {
        const val TAG = "OnDeviceLlm"
    }
}
