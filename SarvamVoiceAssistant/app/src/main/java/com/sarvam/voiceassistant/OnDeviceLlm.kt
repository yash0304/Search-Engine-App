package com.sarvam.voiceassistant

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
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
 * Answers on the phone with Gemma 4 E2B through LiteRT-LM, from Google AI Edge.
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

            ready.createConversation(config).use { conversation ->
                val text = StringBuilder()
                try {
                    conversation.sendMessageAsync(prompt).collect { text.append(it.toString()) }
                } catch (e: CancellationException) {
                    runCatching { conversation.cancelProcess() }
                    throw e
                }
                OnDeviceModel.cleanReply(text.toString())
                    ?: throw SarvamException("The on-device model gave an empty answer. Try asking again.")
            }
        }
    }

    /** Frees the model's memory; it reloads on the next question. */
    fun release() {
        runCatching { engine?.close() }
        engine = null
        loadedFrom = null
        backendName = "not loaded"
    }

    /** The GPU is several times faster; the CPU is the fallback for phones whose GPU it cannot use. */
    private fun engineFor(model: File): Engine {
        engine?.takeIf { loadedFrom == model.path }?.let { return it }
        release()

        val attempts = listOf("GPU" to { Backend.GPU() }, "CPU" to { Backend.CPU() })
        var lastError: Throwable? = null
        for ((name, backend) in attempts) {
            try {
                val candidate = Engine(EngineConfig(modelPath = model.path, backend = backend(), cacheDir = cacheDir))
                candidate.initialize()
                engine = candidate
                loadedFrom = model.path
                backendName = name
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

    private companion object {
        const val TAG = "OnDeviceLlm"
    }
}
