package com.sarvam.voiceassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.sarvam.voiceassistant.ui.ChatScreen
import com.sarvam.voiceassistant.ui.SarvamTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            SarvamTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ChatScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Don't keep the mic open or talk over the user after they leave the app.
        viewModel.cancelPipeline()
    }
}
