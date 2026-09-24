package com.sarvam.voiceassistant

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sarvam.voiceassistant.ui.ChatContent
import com.sarvam.voiceassistant.ui.LockScreen
import com.sarvam.voiceassistant.ui.SarvamTheme
import com.sarvam.voiceassistant.ui.SettingsDialog
import com.sarvam.voiceassistant.ui.SpeechSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Renders each screen with sample data and saves it, so the design can be reviewed from CI
 * without a phone. CI prints the images into the job log and uploads them as an artifact.
 *
 * These never fail on how a screen looks — only on whether it renders at all.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val conversation = listOf(
        Message(Role.USER, "શું વડોદરા જતાં રસ્તામાં વરસાદ છે?", "gu-IN"),
        Message(
            Role.ASSISTANT,
            "હા — આણંદ પાસે, લગભગ 35 કિમી આગળ, મધ્યમ વરસાદ છે. વડોદરા પહેલાં બંધ થઈ જશે.",
            "gu-IN",
        ),
        Message(Role.USER, "How do you say good morning in Gujarati?", "en-IN"),
        Message(Role.ASSISTANT, "સુપ્રભાત — pronounced \"suprabhaat\".", "gu-IN"),
    )

    @Test
    fun welcomeLight() = shoot("1_welcome_light") { chat(UiState(hasApiKey = true)) }

    @Test
    fun welcomeDark() = shoot("2_welcome_dark", dark = true) { chat(UiState(hasApiKey = true)) }

    @Test
    fun welcomeNoKey() = shoot("3_welcome_no_key") { chat(UiState(hasApiKey = false)) }

    @Test
    fun conversationLight() = shoot("4_chat_light") { chat(UiState(hasApiKey = true, messages = conversation)) }

    @Test
    fun conversationDark() = shoot("5_chat_dark", dark = true) {
        chat(UiState(hasApiKey = true, messages = conversation))
    }

    @Test
    fun listening() = shoot("6_listening") {
        chat(UiState(hasApiKey = true, messages = conversation.take(2), stage = Stage.RECORDING), amplitude = 0.7f)
    }

    @Test
    fun lock() = shoot("7_lock") { LockScreen(message = null, onUnlock = {}) }

    @Test
    fun settings() {
        compose.setContent {
            SarvamTheme(darkTheme = false) {
                SettingsDialog(
                    hasSavedKey = true,
                    maskedKey = "sk_••••••7f3a",
                    initialSpeaker = "",
                    initialModel = "",
                    availableModels = listOf("sarvam-105b", "sarvam-30b"),
                    loadingModels = false,
                    lockEnabled = true,
                    lockAvailable = true,
                    webSearchEnabled = true,
                    locationEnabled = true,
                    dictionaryStatus = "Ready — 207,235 entries",
                    onRebuildDictionary = {},
                    speech = SpeechSettings(streaming = true, autoStop = true, sttMode = "transcribe", sttModel = "saaras:v3"),
                    speechDiagnostics = "Last turn — heard: streamed; spoke: streamed",
                    onSave = { _, _, _, _, _, _, _ -> },
                    onClearKey = {},
                    onDismiss = {},
                )
            }
        }
        compose.waitForIdle()
        // The dialog is its own window, so capture it rather than the (empty) root.
        runCatching { save("8_settings", compose.onNode(isDialog()).captureToImage().asAndroidBitmap()) }
            .onFailure { Log.w(TAG, "Could not capture the settings dialog", it) }
    }

    @Composable
    private fun chat(state: UiState, amplitude: Float = 0f) {
        ChatContent(
            state = state,
            amplitude = amplitude,
            draft = "",
            onDraftChange = {},
            onSend = {},
            onMic = {},
            onAttach = {},
            onLanguageChange = {},
            onAsk = {},
            onOpenSettings = {},
            onClearConversation = {},
            animateGreeting = false,
        )
    }

    private fun shoot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            SarvamTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        compose.waitForIdle()
        save(name, compose.onRoot().captureToImage().asAndroidBitmap())
    }

    /** Scaled down and JPEG-compressed so all of them fit comfortably in a CI log. */
    private fun save(name: String, bitmap: Bitmap) {
        val width = 540
        val scaled = Bitmap.createScaledBitmap(bitmap, width, bitmap.height * width / bitmap.width, true)
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "screens").apply { mkdirs() }
        File(dir, "$name.jpg").outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        Log.i(TAG, "Saved $name (${bitmap.width}x${bitmap.height})")
    }

    private companion object {
        const val TAG = "ScreenshotTest"
    }
}
