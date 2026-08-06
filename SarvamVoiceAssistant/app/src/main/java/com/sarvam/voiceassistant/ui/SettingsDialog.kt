package com.sarvam.voiceassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.sarvam.voiceassistant.Voices

private const val AUTOMATIC = ""

/**
 * @param maskedKey a redacted stand-in for the saved key. The real key is never passed in,
 *   so it cannot be read off this screen.
 * @param onSave receives a blank [apiKey] when the user did not type a new one, meaning
 *   "keep the stored key".
 */
@Composable
fun SettingsDialog(
    hasSavedKey: Boolean,
    maskedKey: String,
    initialSpeaker: String,
    initialModel: String,
    availableModels: List<String>,
    loadingModels: Boolean,
    lockEnabled: Boolean,
    lockAvailable: Boolean,
    onSave: (apiKey: String, speaker: String, model: String, lock: Boolean) -> Unit,
    onClearKey: () -> Unit,
    onDismiss: () -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }
    var speaker by remember { mutableStateOf(initialSpeaker) }
    var model by remember { mutableStateOf(initialModel) }
    var lock by remember { mutableStateOf(lockEnabled) }
    var keyVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("API key", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (hasSavedKey) {
                        "Saved as $maskedKey. It is stored encrypted on this device and is " +
                            "never shown in full. Type a new key only if you want to replace it."
                    } else {
                        "Paste your Sarvam API key. It is stored encrypted on this device and " +
                            "never leaves it except to call the Sarvam API."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (hasSavedKey) "Replace key (optional)" else "Sarvam API key") },
                    singleLine = true,
                    // Only ever reveals what the user just typed, never the stored key.
                    visualTransformation = if (keyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                imageVector = if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (keyVisible) "Hide typing" else "Show typing",
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

                if (hasSavedKey) {
                    TextButton(onClick = onClearKey) { Text("Remove saved key") }
                }

                Spacer(Modifier.height(16.dp))
                ToggleRow(
                    title = "Require unlock",
                    hint = if (lockAvailable) {
                        "Ask for fingerprint, face or screen lock before opening the app."
                    } else {
                        "Unavailable — set a screen lock on this device first."
                    },
                    checked = lock && lockAvailable,
                    enabled = lockAvailable,
                    onCheckedChange = { lock = it },
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
                onClick = { onSave(apiKey.trim(), speaker, model, lock) },
                // With no key saved yet, one must be entered before anything can work.
                enabled = hasSavedKey || apiKey.isNotBlank(),
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
private fun ToggleRow(
    title: String,
    hint: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.fillMaxWidth(0.75f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
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
