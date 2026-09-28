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
import com.sarvam.voiceassistant.AnswerSource
import com.sarvam.voiceassistant.BuildConfig
import com.sarvam.voiceassistant.ModelStatus
import com.sarvam.voiceassistant.OnDeviceModel
import com.sarvam.voiceassistant.SpeechOptions
import com.sarvam.voiceassistant.Voices

private const val AUTOMATIC = ""

/** The speech choices Settings edits as one unit. */
data class SpeechSettings(
    val streaming: Boolean,
    val autoStop: Boolean,
    val sttMode: String,
    val sttModel: String,
)

/** Where answers come from, and the on-device model's state. */
data class OfflineSettings(
    val answerSource: AnswerSource,
    val model: ModelStatus,
    val hasToken: Boolean,
    val lowMemory: Boolean,
)

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
    webSearchEnabled: Boolean,
    locationEnabled: Boolean,
    dictionaryStatus: String,
    onRebuildDictionary: () -> Unit,
    speech: SpeechSettings,
    speechDiagnostics: String,
    offline: OfflineSettings,
    onDownloadModel: (token: String) -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteModel: () -> Unit,
    onClearToken: () -> Unit,
    onSave: (
        apiKey: String,
        speaker: String,
        model: String,
        lock: Boolean,
        webSearch: Boolean,
        location: Boolean,
        speech: SpeechSettings,
        answerSource: AnswerSource,
    ) -> Unit,
    onClearKey: () -> Unit,
    onDismiss: () -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }
    var speaker by remember { mutableStateOf(initialSpeaker) }
    var model by remember { mutableStateOf(initialModel) }
    var lock by remember { mutableStateOf(lockEnabled) }
    var webSearch by remember { mutableStateOf(webSearchEnabled) }
    var useLocation by remember { mutableStateOf(locationEnabled) }
    var keyVisible by remember { mutableStateOf(false) }
    var speechChoice by remember { mutableStateOf(speech) }
    var source by remember { mutableStateOf(offline.answerSource) }
    var token by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("API KEY", style = overline)
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

                Spacer(Modifier.height(20.dp))
                Text("ANSWERS", style = overline)
                Spacer(Modifier.height(8.dp))
                ChoiceSection(
                    title = "Answers from",
                    hint = source.hint,
                    selected = source.value,
                    options = AnswerSource.entries.map { it.value to it.label },
                    onSelect = { source = AnswerSource.from(it) },
                )
                Spacer(Modifier.height(12.dp))
                OnDeviceModelSection(
                    offline = offline,
                    token = token,
                    onTokenChange = { token = it },
                    onDownload = { onDownloadModel(token.trim()) },
                    onCancel = onCancelDownload,
                    onDelete = onDeleteModel,
                    onClearToken = onClearToken,
                )

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

                Spacer(Modifier.height(16.dp))
                ToggleRow(
                    title = "Look things up",
                    hint = "Let the assistant search the web when asked about recent events. " +
                        "Adds a pause while it searches.",
                    checked = webSearch,
                    enabled = true,
                    onCheckedChange = { webSearch = it },
                )

                Spacer(Modifier.height(16.dp))
                ToggleRow(
                    title = "Use my location",
                    hint = "Lets you ask \"is it raining here\" without naming a place. " +
                        "Used only while the app is open.",
                    checked = useLocation,
                    enabled = true,
                    onCheckedChange = { useLocation = it },
                )

                Spacer(Modifier.height(20.dp))
                Text("SPEECH", style = overline)
                Spacer(Modifier.height(8.dp))
                ToggleRow(
                    title = "Streaming speech",
                    hint = "Replies start playing after the first phrase, and your words are " +
                        "transcribed while you talk. Falls back automatically if it fails.",
                    checked = speechChoice.streaming,
                    enabled = true,
                    onCheckedChange = { speechChoice = speechChoice.copy(streaming = it) },
                )
                Spacer(Modifier.height(12.dp))
                ToggleRow(
                    title = "Stop listening when I pause",
                    hint = "Ends your turn when you stop talking, without a second tap. " +
                        "Needs streaming speech.",
                    checked = speechChoice.autoStop && speechChoice.streaming,
                    enabled = speechChoice.streaming,
                    onCheckedChange = { speechChoice = speechChoice.copy(autoStop = it) },
                )
                Spacer(Modifier.height(12.dp))
                ChoiceSection(
                    title = "What to do with what I say",
                    hint = SpeechOptions.MODES.first { it.value == speechChoice.sttMode }.example,
                    selected = speechChoice.sttMode,
                    options = SpeechOptions.MODES.map { it.value to it.label },
                    onSelect = { speechChoice = speechChoice.copy(sttMode = it) },
                )
                Spacer(Modifier.height(12.dp))
                ChoiceSection(
                    title = "Speech recognition model",
                    hint = "saaras:v4 is Sarvam's newest; v3 is the proven default.",
                    selected = speechChoice.sttModel,
                    options = SpeechOptions.STT_MODELS.map { it to it },
                    onSelect = { speechChoice = speechChoice.copy(sttModel = it) },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = speechDiagnostics,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(20.dp))
                Text("OFFLINE DICTIONARY", style = overline)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = dictionaryStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // "Unavailable" here is the honest answer when the database will not open,
                // and is what should be reported rather than "that word does not exist".
                if (!dictionaryStatus.startsWith("Ready")) {
                    TextButton(onClick = onRebuildDictionary) { Text("Rebuild dictionary") }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "App build ${BuildConfig.GIT_COMMIT}",
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
                onClick = { onSave(apiKey.trim(), speaker, model, lock, webSearch, useLocation, speechChoice, source) },
                // Something has to be able to answer: a Sarvam key, or the on-device model.
                enabled = hasSavedKey || apiKey.isNotBlank() || offline.model.phase != ModelStatus.Phase.ABSENT,
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
private fun OnDeviceModelSection(
    offline: OfflineSettings,
    token: String,
    onTokenChange: (String) -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onClearToken: () -> Unit,
) {
    val model = offline.model
    Text("On-device model", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        text = "${OnDeviceModel.NAME} by Google, run with LiteRT-LM. ${OnDeviceModel.sizeLabel()} download, " +
            "once. Best on phones with ${OnDeviceModel.MIN_DEVICE_MEMORY_GB} GB of memory or more.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (offline.lowMemory) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = "This phone has less than ${OnDeviceModel.MIN_DEVICE_MEMORY_GB} GB of memory; the model may be slow or fail to load.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Spacer(Modifier.height(6.dp))
    Text(
        text = model.describe(),
        style = MaterialTheme.typography.bodyMedium,
        color = if (model.problem != null && model.phase == ModelStatus.Phase.ABSENT) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    )
    Spacer(Modifier.height(8.dp))

    when (model.phase) {
        ModelStatus.Phase.ABSENT -> {
            OutlinedTextField(
                value = token,
                onValueChange = onTokenChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (offline.hasToken) "Replace Hugging Face token" else "Hugging Face token (only if asked)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            if (offline.hasToken) {
                TextButton(onClick = onClearToken) { Text("Remove saved token") }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                Text("Download (${OnDeviceModel.sizeLabel()})")
            }
        }
        ModelStatus.Phase.DOWNLOADING ->
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel download") }
        ModelStatus.Phase.READY ->
            TextButton(onClick = onDelete) { Text("Delete model (frees ${OnDeviceModel.sizeLabel()})") }
    }
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

/** A dropdown over fixed labelled values, with no "Automatic" entry. */
@Composable
private fun ChoiceSection(
    title: String,
    hint: String,
    selected: String,
    options: List<Pair<String, String>>,
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
            Text(options.firstOrNull { it.first == selected }?.second ?: selected)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(value)
                        menuOpen = false
                    },
                )
            }
        }
    }
}
