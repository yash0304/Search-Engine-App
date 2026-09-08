package com.sarvam.voiceassistant

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.sarvam.voiceassistant.ui.ChatScreen
import com.sarvam.voiceassistant.ui.LockScreen
import com.sarvam.voiceassistant.ui.SarvamTheme

/**
 * A FragmentActivity rather than a plain ComponentActivity because androidx BiometricPrompt
 * requires one. It hosts Compose exactly the same way.
 */
class MainActivity : FragmentActivity() {

    private val viewModel: ChatViewModel by viewModels()
    private lateinit var appLock: AppLock

    private var locked by mutableStateOf(false)
    private var lockMessage by mutableStateOf<String?>(null)
    private var promptVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        appLock = AppLock(this)
        locked = shouldLock()

        setContent {
            SarvamTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    if (locked) {
                        LockScreen(message = lockMessage, onUnlock = ::promptUnlock)
                    } else {
                        ChatScreen(viewModel = viewModel)
                    }
                }
            }
        }
    }

    /** Only lock when the user wants it and the device can actually satisfy the prompt. */
    private fun shouldLock(): Boolean = viewModel.isLockEnabled() && appLock.isAvailable()

    override fun onStart() {
        super.onStart()
        if (locked && !promptVisible) promptUnlock()
    }

    private fun promptUnlock() {
        if (promptVisible) return
        promptVisible = true
        appLock.prompt(
            title = "Unlock Sarvam Voice",
            subtitle = "Protects the API key stored on this device",
            onSuccess = {
                promptVisible = false
                lockMessage = null
                locked = false
            },
            onFailure = { message ->
                promptVisible = false
                lockMessage = message
            },
        )
    }

    override fun onStop() {
        super.onStop()
        // A rotation also stops the activity. Cancelling there would kill an in-flight reply
        // and re-prompt for the lock, so treat a configuration change as staying in the app.
        if (isChangingConfigurations) return

        viewModel.cancelPipeline()
        if (shouldLock()) {
            locked = true
            lockMessage = null
        }
    }
}
