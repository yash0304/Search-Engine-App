package com.sarvam.voiceassistant.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.sarvam.voiceassistant.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    /** Called just before opening the file picker, so the app lock does not fire. */
    onOpeningOwnScreen: () -> Unit = {},
) {
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

    // Photos and PDFs for Document Intelligence. OpenDocument needs no storage permission.
    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::readDocument) }

    fun openDocumentPicker() {
        onOpeningOwnScreen()
        documentPicker.launch(arrayOf("application/pdf", "image/*"))
    }

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
            speech = SpeechSettings(
                streaming = state.streamingEnabled,
                autoStop = state.autoStopListening,
                sttMode = state.sttMode,
                sttModel = state.sttModel,
            ),
            speechDiagnostics = state.speechDiagnostics,
            onSave = { key, speaker, model, lock, webSearch, useLocation, speech ->
                viewModel.setSpeechOptions(speech.streaming, speech.autoStop, speech.sttMode, speech.sttModel)
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

    ChatContent(
        state = state,
        amplitude = amplitude,
        draft = draft,
        onDraftChange = { draft = it },
        onSend = {
            val language = Language.spokenOrDefault(state.inputLanguage)
            viewModel.sendText(draft, language)
            draft = ""
        },
        onMic = ::requestMic,
        onAttach = ::openDocumentPicker,
        onLanguageChange = viewModel::setInputLanguage,
        onAsk = { prompt -> viewModel.sendText(prompt, "en-IN") },
        onOpenSettings = { showSettings = true },
        onClearConversation = viewModel::clearConversation,
        snackbarHostState = snackbarHostState,
    )
}

/**
 * Everything the chat screen draws, driven only by [state]. Kept free of the ViewModel so the
 * screenshot test can render every screen state with sample data.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatContent(
    state: UiState,
    amplitude: Float,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onMic: () -> Unit,
    onAttach: () -> Unit,
    onLanguageChange: (String) -> Unit,
    onAsk: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onClearConversation: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    animateGreeting: Boolean = true,
) {
    val conversation = state.messages.isNotEmpty()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    if (conversation) {
                        Monogram(size = 32.dp, modifier = Modifier.padding(start = 12.dp))
                    }
                },
                title = {
                    if (conversation) {
                        Text("Boliyan", style = MaterialTheme.typography.titleLarge)
                    }
                },
                actions = {
                    if (conversation) {
                        IconButton(onClick = onClearConversation) {
                            Icon(Icons.Filled.Delete, contentDescription = "Clear conversation")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            InputBar(
                draft = draft,
                onDraftChange = onDraftChange,
                selectedLanguage = state.inputLanguage,
                onLanguageChange = onLanguageChange,
                stage = state.stage,
                searchQuery = state.searchQuery,
                amplitude = amplitude,
                enabled = state.hasApiKey,
                onSend = onSend,
                onMic = onMic,
                onAttach = onAttach,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (conversation) {
                ConversationList(state.messages)
            } else {
                WelcomeScreen(
                    hasApiKey = state.hasApiKey,
                    busy = state.isBusy,
                    onOpenSettings = onOpenSettings,
                    onAsk = onAsk,
                    onReadDocument = onAttach,
                    animateGreeting = animateGreeting,
                )
            }
        }
    }
}

// ── Welcome ─────────────────────────────────────────────────────────────

/** One thing the app can do, shown on the welcome screen as a tappable example. */
private data class Example(val icon: ImageVector, val title: String, val prompt: String)

private val examples = listOf(
    Example(Icons.Filled.WaterDrop, "Rain on your route", "Is it raining on the way to Vadodara?"),
    Example(Icons.Filled.Translate, "Translate anything", "How do you say good morning in Gujarati?"),
    Example(Icons.AutoMirrored.Filled.MenuBook, "Offline dictionary", "What is the meaning of ephemeral?"),
    Example(Icons.Filled.Newspaper, "Facts from the web", "Who won the latest cricket World Cup?"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WelcomeScreen(
    hasApiKey: Boolean,
    busy: Boolean,
    onOpenSettings: () -> Unit,
    onAsk: (String) -> Unit,
    onReadDocument: () -> Unit,
    animateGreeting: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CyclingGreeting(animate = animateGreeting)
        Spacer(Modifier.height(14.dp))
        Waveform(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(32.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Boliyan",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Speak in any Indian language. It answers out loud, in yours.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, bottom = 18.dp),
        )

        if (!hasApiKey) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("ONE STEP FIRST", style = overline)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Add your Sarvam API key. It is stored encrypted and never shown again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    TextButton(onClick = onOpenSettings, contentPadding = PaddingValues(0.dp)) {
                        Text("Open Settings")
                    }
                }
            }
            return@Column
        }

        Text("TRY ONE", style = overline, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))

        examples.chunked(2).forEachIndexed { row, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                pair.forEachIndexed { column, example ->
                    ExampleTile(
                        example = example,
                        accent = BoliyanColors.accents[(row * 2 + column) % BoliyanColors.accents.size],
                        enabled = !busy,
                        onClick = { onAsk(example.prompt) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // The document reader is the one example that is an action, not a question, so it
        // gets the ink card that reads as a button. On the dark theme's ink background plain
        // ink would vanish, so it lifts to a lighter indigo there.
        val cardInk = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            BoliyanColors.Ink
        }
        Card(
            onClick = onReadDocument,
            enabled = !busy,
            colors = CardDefaults.cardColors(
                containerColor = cardInk,
                contentColor = Color.White,
                disabledContainerColor = cardInk.copy(alpha = 0.5f),
            ),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .background(BoliyanColors.Marigold, CircleShape),
                ) {
                    Icon(Icons.Filled.Description, contentDescription = null, tint = BoliyanColors.Ink)
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Read a photo or PDF", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Receipts, letters, notices — then ask about it",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExampleTile(
    example: Example,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.large,
        modifier = modifier.height(124.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .background(accent.copy(alpha = 0.18f), CircleShape),
            ) {
                Icon(example.icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(example.title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "“${example.prompt}”",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

// ── Conversation ────────────────────────────────────────────────────────

@Composable
private fun ConversationList(messages: List<Message>) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(messages, key = { it.id }) { message -> MessageBubble(message) }
    }
}

@Composable
private fun MessageBubble(message: Message) {
    val fromUser = message.role == Role.USER

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!fromUser) {
            Monogram(size = 30.dp)
            Spacer(Modifier.width(8.dp))
        }

        Column(horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start) {
            Surface(
                // The tail corner points at whoever spoke.
                shape = if (fromUser) {
                    RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)
                } else {
                    RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp)
                },
                color = if (fromUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (fromUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            message.languageCode?.let { code ->
                Text(
                    text = languageLabel(code),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** "gu-IN" → "ગુજરાતી · GU": the language in its own script is friendlier than a code. */
private fun languageLabel(code: String): String {
    val native = Language.entries.firstOrNull { it.code == code }?.nativeLabel
    val short = code.substringBefore('-').uppercase()
    return if (native != null && native != short) "$native · $short" else short
}

// ── Input ───────────────────────────────────────────────────────────────

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
    onAttach: () -> Unit,
) {
    // The app draws edge to edge, so Android no longer shrinks the window for the keyboard;
    // without these the keyboard covered the text field. navigationBars keeps the bar clear
    // of the gesture area when the keyboard is closed.
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 10.dp)) {

            StatusLine(stage, searchQuery, amplitude)

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
                        shape = CircleShape,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onAttach, enabled = enabled && stage == Stage.IDLE) {
                    Icon(Icons.Filled.AttachFile, contentDescription = "Read a photo or PDF")
                }

                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type, or tap the mic…") },
                    enabled = enabled && stage == Stage.IDLE,
                    maxLines = 4,
                    shape = RoundedCornerShape(28.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                    trailingIcon = if (draft.isNotBlank()) {
                        {
                            IconButton(onClick = onSend, enabled = enabled && stage == Stage.IDLE) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    } else {
                        null
                    },
                )

                Spacer(Modifier.width(10.dp))

                MicButton(stage = stage, amplitude = amplitude, enabled = enabled, onClick = onMic)
            }
        }
    }
}

@Composable
private fun StatusLine(stage: Stage, searchQuery: String?, amplitude: Float) {
    val label = when (stage) {
        Stage.IDLE -> null
        Stage.RECORDING -> "Listening…"
        Stage.TRANSCRIBING -> "Understanding…"
        Stage.THINKING -> "Thinking…"
        Stage.SEARCHING -> searchQuery?.let { "Looking up $it…" } ?: "Looking it up…"
        Stage.SPEAKING -> "Speaking…"
        Stage.READING -> searchQuery?.let { "$it…" } ?: "Reading the document…"
    } ?: return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (stage == Stage.RECORDING) {
            // While you talk, the brand waveform moves with your voice.
            Waveform(
                level = amplitude,
                bars = 18,
                modifier = Modifier
                    .width(96.dp)
                    .height(22.dp),
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MicButton(stage: Stage, amplitude: Float, enabled: Boolean, onClick: () -> Unit) {
    val recording = stage == Stage.RECORDING
    // A halo that swells with the microphone level, so it is obvious it is hearing you.
    val halo by animateFloatAsState(
        // Capped so the halo stays inside the bar's 12 dp margin instead of clipping at the edge.
        targetValue = if (recording) 1.1f + amplitude * 0.25f else 1f,
        label = "micHalo",
    )
    val fill = if (recording) BoliyanColors.Vermilion else BoliyanColors.Marigold
    val haloColor = fill.copy(alpha = 0.28f)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(58.dp)
            .drawBehind {
                if (recording) drawCircle(haloColor, radius = size.minDimension / 2 * halo)
            }
            .clip(CircleShape)
            .background(if (enabled) fill else fill.copy(alpha = 0.4f))
            .border(2.dp, Color.White.copy(alpha = 0.35f), CircleShape),
    ) {
        IconButton(onClick = { if (enabled) onClick() }, modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = if (recording) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = if (recording) "Stop recording" else "Start recording",
                tint = if (recording) Color.White else BoliyanColors.Ink,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}
