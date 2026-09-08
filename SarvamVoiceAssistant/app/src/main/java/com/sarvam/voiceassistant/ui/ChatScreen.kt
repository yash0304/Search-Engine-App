package com.sarvam.voiceassistant.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.sarvam.voiceassistant.AppLock
import com.sarvam.voiceassistant.ChatViewModel
import com.sarvam.voiceassistant.Language
import com.sarvam.voiceassistant.LocationProvider
import com.sarvam.voiceassistant.Message
import com.sarvam.voiceassistant.Role
import com.sarvam.voiceassistant.Stage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val state by viewModel.uiState.collectAsState()
    val amplitude by viewModel.amplitude.collectAsState()
    val context = LocalContext.current

    val snackbarHostState = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }

    // Offering the lock toggle on a device with no screen lock would produce a switch that
    // silently does nothing, so ask the platform first.
    val lockAvailable = remember(context) {
        (context as? FragmentActivity)?.let { AppLock(it).isAvailable() } ?: false
    }

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* Denied simply means location questions ask for a place name. */ }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.onMicTapped()
    }

    fun requestMic() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.onMicTapped() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Surface pipeline errors as a snackbar rather than a toast that outlives the screen.
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    // Open settings automatically on a fresh install so the app is never a dead end.
    LaunchedEffect(state.hasApiKey) {
        if (!state.hasApiKey) showSettings = true
    }

    if (showSettings) {
        // Re-check the model list each time Settings opens, so a newly added or retired
        // model shows up without restarting the app.
        LaunchedEffect(Unit) {
            viewModel.refreshModels()
            viewModel.refreshDictionaryStatus()
        }

        SettingsDialog(
            hasSavedKey = state.hasApiKey,
            maskedKey = viewModel.maskedKey(),
            initialSpeaker = state.speaker,
            initialModel = state.chatModel,
            availableModels = state.availableModels,
            loadingModels = state.loadingModels,
            lockEnabled = state.lockEnabled,
            lockAvailable = lockAvailable,
            webSearchEnabled = state.webSearchEnabled,
            locationEnabled = state.locationEnabled,
            dictionaryStatus = state.dictionaryStatus,
            onRebuildDictionary = viewModel::rebuildDictionary,
            onSave = { key, speaker, model, lock, webSearch, useLocation ->
                viewModel.saveApiKey(key) // Blank keeps the stored key.
                viewModel.setSpeaker(speaker)
                viewModel.setChatModel(model)
                viewModel.setLockEnabled(lock)
                viewModel.setWebSearchEnabled(webSearch)
                viewModel.setLocationEnabled(useLocation)
                // Ask for the permission at the moment it is switched on, so the prompt has
                // obvious context rather than appearing at launch.
                if (useLocation && !viewModel.hasLocationPermission()) {
                    locationPermission.launch(LocationProvider.PERMISSIONS)
                }
                showSettings = false
            },
            onClearKey = {
                viewModel.clearApiKey()
                showSettings = false
            },
            onDismiss = { showSettings = false },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Sarvam Voice") },
                actions = {
                    if (state.messages.isNotEmpty()) {
                        IconButton(onClick = viewModel::clearConversation) {
                            Icon(Icons.Filled.Delete, contentDescription = "Clear conversation")
                        }
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            InputBar(
                draft = draft,
                onDraftChange = { draft = it },
                selectedLanguage = state.inputLanguage,
                onLanguageChange = viewModel::setInputLanguage,
                stage = state.stage,
                searchQuery = state.searchQuery,
                amplitude = amplitude,
                enabled = state.hasApiKey,
                onSend = {
                    val language = Language.spokenOrDefault(state.inputLanguage)
                    viewModel.sendText(draft, language)
                    draft = ""
                },
                onMic = ::requestMic,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.messages.isEmpty()) {
                EmptyState(hasApiKey = state.hasApiKey, onOpenSettings = { showSettings = true })
            } else {
                ConversationList(state.messages)
            }
        }
    }
}

@Composable
private fun ConversationList(messages: List<Message>) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(messages, key = { it.id }) { message -> MessageBubble(message) }
    }
}

@Composable
private fun MessageBubble(message: Message) {
    val fromUser = message.role == Role.USER
    val bubbleColor = if (fromUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (fromUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = bubbleColor),
            modifier = Modifier.fillMaxWidth(0.85f),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = if (fromUser) "You" else "Assistant",
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(4.dp))
                Text(text = message.text, color = textColor, style = MaterialTheme.typography.bodyLarge)
                message.languageCode?.let { code ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = code,
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.55f),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(hasApiKey: Boolean, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Mic,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(16.dp))
        if (hasApiKey) {
            Text(
                text = "Tap the microphone and speak in Gujarati, Hindi, or English.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "The assistant replies in the same language and reads it aloud.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        } else {
            Text(
                text = "Add your Sarvam API key to get started.",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onOpenSettings) { Text("Open Settings") }
        }
    }
}

@Composable
private fun InputBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    selectedLanguage: String,
    onLanguageChange: (String) -> Unit,
    stage: Stage,
    searchQuery: String?,
    amplitude: Float,
    enabled: Boolean,
    onSend: () -> Unit,
    onMic: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {

            StatusLine(stage, searchQuery)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Language.entries.forEach { language ->
                    FilterChip(
                        selected = selectedLanguage == language.code,
                        onClick = { onLanguageChange(language.code) },
                        label = { Text(language.nativeLabel) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a message…") },
                    enabled = enabled && stage == Stage.IDLE,
                    maxLines = 4,
                )

                Spacer(Modifier.width(8.dp))

                IconButton(
                    onClick = onSend,
                    enabled = enabled && stage == Stage.IDLE && draft.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }

                Spacer(Modifier.width(4.dp))

                MicButton(stage = stage, amplitude = amplitude, enabled = enabled, onClick = onMic)
            }
        }
    }
}

@Composable
private fun StatusLine(stage: Stage, searchQuery: String?) {
    val label = when (stage) {
        Stage.IDLE -> null
        Stage.RECORDING -> "Listening…"
        Stage.TRANSCRIBING -> "Transcribing…"
        Stage.THINKING -> "Thinking…"
        Stage.SEARCHING -> searchQuery?.let { "Searching for \"$it\"…" } ?: "Searching…"
        Stage.SPEAKING -> "Speaking…"
    }

    if (label != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun MicButton(stage: Stage, amplitude: Float, enabled: Boolean, onClick: () -> Unit) {
    val recording = stage == Stage.RECORDING
    // Pulse the button with the live microphone level so it's obvious recording is working.
    val scale by animateFloatAsState(
        targetValue = if (recording) 1f + amplitude * 0.25f else 1f,
        label = "micScale",
    )

    val container = if (recording) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }

    FloatingActionButton(
        onClick = { if (enabled) onClick() },
        containerColor = container,
        modifier = Modifier.graphicsLayer(scaleX = scale, scaleY = scale),
    ) {
        Icon(
            imageVector = if (recording) Icons.Filled.Stop else Icons.Filled.Mic,
            contentDescription = if (recording) "Stop recording" else "Start recording",
        )
    }
}
