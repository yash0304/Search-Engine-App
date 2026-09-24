package com.sarvam.voiceassistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Shown instead of the conversation until the device credential check passes. The
 * conversation is never composed while locked, so nothing sensitive is on screen — which
 * also means it stays hidden in the app switcher.
 *
 * Always ink, in light and dark themes alike: this is the app's front door.
 */
@Composable
fun LockScreen(message: String?, onUnlock: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BoliyanColors.Ink, BoliyanColors.InkDeep)))
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Monogram(size = 104.dp)
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Boliyan",
            style = MaterialTheme.typography.displaySmall,
            color = Color.White,
        )
        Spacer(Modifier.height(20.dp))
        Waveform(
            modifier = Modifier
                .fillMaxWidth(0.55f)
                .height(28.dp),
        )
        Spacer(Modifier.height(28.dp))
        Text(
            text = message ?: "Locked to protect the API key saved on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onUnlock,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = BoliyanColors.Marigold,
                contentColor = BoliyanColors.Ink,
            ),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp),
        ) {
            Icon(Icons.Filled.Fingerprint, contentDescription = null)
            Spacer(Modifier.padding(start = 8.dp))
            Text("Unlock", style = MaterialTheme.typography.titleMedium)
        }
    }
}
