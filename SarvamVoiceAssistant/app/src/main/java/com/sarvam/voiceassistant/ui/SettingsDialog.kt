package com.sarvam.voiceassistant.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.sarvam.voiceassistant.Voices

private const val AUTOMATIC = ""

@Composable
fun SettingsDialog(
    initialApiKey: String,
    initialSpeaker: String,
    initialModel: String,
    availableModels: List<String>,
    loadingModels: Boolean,
    onSave: (apiKey: String, speaker: String, model: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var apiKey by remember { mutableStateOf(initialApiKey) }
    var speaker by remember { mutableStateOf(initialSpeaker) }
    var model by remember { mutableStateOf(initialModel) }
    var keyVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "Paste your Sarvam API key. It is stored encrypted on this device and " +
                        "never leaves it except to call the Sarvam API.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Sarvam API key") },
                    singleLine = true,
                    visualTransformation = if (keyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                imageVector = if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (keyVisible) "Hide key" else "Show key",
                            )
                        }
                    },
                )

                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Get a key at dashboard.sarvam.ai",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(20.dp))
                SettingSection(
                    title = "Chat model",
                    hint = when {
                        loadingModels -> "Checking which models your key can use…"
                        availableModels.isEmpty() ->
                            "Could not list models. \"Automatic\" still works — the app picks one " +
                                "and corrects itself if the API rejects it."
                        else -> "\"Automatic\" tracks whatever your key supports, so a model being " +
                            "retired will not break the app."
                    },
                    selected = model,
                    options = availableModels,
                    automaticLabel = "Automatic (recommended)",
                    onSelect = { model = it },
                )

                Spacer(Modifier.height(20.dp))
                SettingSection(
                    title = "Voice",
                    hint = "\"Automatic\" picks a voice to suit the detected language.",
                    selected = speaker,
                    options = Voices.ALL,
                    automaticLabel = "Automatic",
                    onSelect = { speaker = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(apiKey.trim(), speaker, model) },
                enabled = apiKey.isNotBlank(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SettingSection(
    title: String,
    hint: String,
    selected: String,
    options: List<String>,
    automaticLabel: String,
    onSelect: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Text(title, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        text = hint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    Box {
        OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
            Text(if (selected == AUTOMATIC) automaticLabel else selected)
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            modifier = Modifier.heightIn(max = 320.dp),
        ) {
            DropdownMenuItem(
                text = { Text(automaticLabel) },
                onClick = {
                    onSelect(AUTOMATIC)
                    menuOpen = false
                },
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        menuOpen = false
                    },
                )
            }
        }
    }
}
