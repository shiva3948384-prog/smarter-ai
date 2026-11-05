package com.example.ui.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LunaVoiceChatScreen(
    viewModel: LunaVoiceChatViewModel,
    onExitVoiceChat: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var quickVoiceInput by remember { mutableStateOf("") }

    // Manage Android SpeechRecognizer and TextToSpeech lifecycle with guaranteed cleanup on exit
    val speechRecognizerRef = remember { mutableStateOf<SpeechRecognizer?>(null) }
    val ttsRef = remember { mutableStateOf<TextToSpeech?>(null) }
    var isTtsInitialized by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val ttsInstance = runCatching {
            var instance: TextToSpeech? = null
            instance = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isTtsInitialized = true
                    runCatching {
                        instance?.language = Locale.US
                        instance?.setSpeechRate(1.03f)
                    }
                }
            }
            instance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    viewModel.onTtsPlaybackCompleted()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    viewModel.onTtsPlaybackCompleted()
                }
            })
            instance
        }.getOrNull()
        ttsRef.value = ttsInstance

        viewModel.onSpeakTextRequested = { textToSpeak ->
            val tts = ttsRef.value
            if (tts != null && isTtsInitialized) {
                runCatching {
                    val params = Bundle()
                    tts.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, params, "luna_utt_${System.currentTimeMillis()}")
                }
            } else {
                // If TTS engine is unavailable (e.g. headless JVM test), mark playback completed cleanly
                viewModel.onTtsPlaybackCompleted()
            }
        }

        viewModel.onStopTtsRequested = {
            runCatching { ttsRef.value?.stop() }
            runCatching { speechRecognizerRef.value?.cancel() }
        }

        onDispose {
            viewModel.onSpeakTextRequested = null
            viewModel.onStopTtsRequested = null
            viewModel.endVoiceSession()
            runCatching {
                speechRecognizerRef.value?.stopListening()
                speechRecognizerRef.value?.cancel()
                speechRecognizerRef.value?.destroy()
                speechRecognizerRef.value = null
            }
            runCatching {
                ttsRef.value?.stop()
                ttsRef.value?.shutdown()
                ttsRef.value = null
            }
        }
    }

    fun startAndroidSpeechRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            viewModel.onStartListening()
            return
        }
        runCatching {
            val recognizer = speechRecognizerRef.value ?: SpeechRecognizer.createSpeechRecognizer(context).also {
                speechRecognizerRef.value = it
            }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    viewModel.onStartListening()
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {
                    viewModel.onRmsChanged(rmsdB)
                }

                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    viewModel.onStopListening()
                }

                override fun onError(error: Int) {
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Tap the microphone or use a voice prompt below."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required for live voice recognition."
                        else -> "Microphone input finished. Tap the microphone or pick a prompt below."
                    }
                    viewModel.onSpeechRecognitionError(msg)
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spoken = matches?.firstOrNull()?.trim().orEmpty()
                    if (spoken.isNotBlank()) {
                        viewModel.submitSpokenPrompt(spoken)
                    } else {
                        viewModel.onStopListening()
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partial = matches?.firstOrNull()?.trim().orEmpty()
                    if (partial.isNotBlank()) {
                        viewModel.onPartialSpeechRecognized(partial)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            viewModel.onStartListening()
            recognizer.startListening(intent)
        }.onFailure {
            viewModel.onStartListening()
        }
    }

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startAndroidSpeechRecognition()
        } else {
            viewModel.onSpeechRecognitionError(
                "Microphone permission was denied. You can still talk with GPT 6 Luna using the voice prompt bar below."
            )
        }
    }

    fun handleMicButtonClick() {
        if (uiState.isMicrophoneListening) {
            runCatching { speechRecognizerRef.value?.stopListening() }
            viewModel.onStopListening()
            return
        }
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPerm) {
            startAndroidSpeechRecognition()
        } else {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    BackHandler {
        viewModel.endVoiceSession()
        onExitVoiceChat()
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("luna_voice_chat_screen"),
        color = Color(0xFF070B14)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = {
                        viewModel.endVoiceSession()
                        onExitVoiceChat()
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("luna_voice_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Text Chat",
                        tint = Color.White
                    )
                }

                // Clean model pill showing "GPT 6 Luna" without "free/" prefix
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Color(0xFF131C31),
                    modifier = Modifier
                        .border(1.dp, Color(0xFF2B3B63), RoundedCornerShape(999.dp))
                        .testTag("luna_voice_model_badge")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.RecordVoiceOver,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${uiState.displayModelName} • Voice AI",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }

                IconButton(
                    onClick = { viewModel.toggleMute() },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("luna_voice_top_mute_button")
                ) {
                    Icon(
                        imageVector = if (uiState.isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = if (uiState.isMuted) "Unmute Voice" else "Mute Voice",
                        tint = if (uiState.isMuted) Color(0xFFF87171) else Color(0xFF94A3B8)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Scrollable Center Content: Animated Voice Orb + Transcripts
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .widthIn(max = 600.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                LunaAnimatedVoiceOrb(
                    stage = uiState.stage,
                    rmsLevel = uiState.rmsDbLevel,
                    modifier = Modifier
                        .size(190.dp)
                        .testTag("luna_voice_orb")
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Stage Indicator Label
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = when (uiState.stage) {
                        VoiceSessionStage.LISTENING -> Color(0xFF0EA5E9).copy(alpha = 0.18f)
                        VoiceSessionStage.THINKING -> Color(0xFFA855F7).copy(alpha = 0.18f)
                        VoiceSessionStage.SPEAKING -> Color(0xFF10B981).copy(alpha = 0.18f)
                        VoiceSessionStage.MUTED -> Color(0xFFEF4444).copy(alpha = 0.18f)
                        VoiceSessionStage.ERROR -> Color(0xFFF59E0B).copy(alpha = 0.18f)
                        VoiceSessionStage.IDLE -> Color(0xFF1E293B)
                    },
                    modifier = Modifier.testTag("luna_voice_stage_badge")
                ) {
                    Text(
                        text = uiState.stage.statusLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = when (uiState.stage) {
                            VoiceSessionStage.LISTENING -> Color(0xFF38BDF8)
                            VoiceSessionStage.THINKING -> Color(0xFFD8B4FE)
                            VoiceSessionStage.SPEAKING -> Color(0xFF34D399)
                            VoiceSessionStage.MUTED -> Color(0xFFFCA5A5)
                            VoiceSessionStage.ERROR -> Color(0xFFFDE68A)
                            VoiceSessionStage.IDLE -> Color(0xFFCBD5E1)
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Live User Transcript Card
                if (uiState.liveUserTranscript.isNotBlank()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("luna_voice_user_transcript_card"),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF111827))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "You",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF38BDF8),
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = uiState.liveUserTranscript,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                // Active / Latest GPT 6 Luna Response Card
                val displayedLunaText = uiState.partialLunaResponse.ifBlank { uiState.latestLunaResponse }
                if (displayedLunaText.isNotBlank()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 1.dp,
                                color = Color(0xFF1E3A8A),
                                shape = RoundedCornerShape(18.dp)
                            )
                            .testTag("luna_voice_response_card"),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = uiState.displayModelName,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color(0xFF34D399),
                                    fontWeight = FontWeight.Bold
                                )
                                if (uiState.stage == VoiceSessionStage.SPEAKING || uiState.isTtsSpeaking) {
                                    AssistChip(
                                        onClick = { viewModel.interruptCurrentResponse() },
                                        label = { Text("Interrupt", style = MaterialTheme.typography.labelSmall) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Stop,
                                                contentDescription = "Interrupt speech",
                                                modifier = Modifier.size(14.dp)
                                            )
                                        },
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = Color(0xFF1E293B),
                                            labelColor = Color.White,
                                            leadingIconContentColor = Color(0xFFF87171)
                                        ),
                                        modifier = Modifier.testTag("luna_voice_interrupt_button")
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = displayedLunaText,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color(0xFFF1F5F9)
                            )
                        }
                    }
                } else if (uiState.turns.isEmpty()) {
                    Text(
                        text = "Speak naturally or tap a starter topic below. GPT 6 Luna responds in real time with spoken voice and live transcript.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF94A3B8),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val starters = listOf(
                            "Explain quantum computing simply",
                            "Give me a 1-minute morning motivation",
                            "Practice a software engineering interview"
                        )
                        starters.forEachIndexed { idx, starter ->
                            AssistChip(
                                onClick = { viewModel.submitSpokenPrompt(starter) },
                                label = {
                                    Text(
                                        text = starter,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = Color(0xFF15203B),
                                    labelColor = Color(0xFFE2E8F0)
                                ),
                                modifier = Modifier.testTag("luna_voice_starter_$idx")
                            )
                        }
                    }
                }

                if (!uiState.errorMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = uiState.errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFBBF24),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .testTag("luna_voice_error_text")
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Quick Voice Prompt Input (allows typing or simulated speech input in emulators/noisy rooms)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = quickVoiceInput,
                    onValueChange = { quickVoiceInput = it },
                    placeholder = {
                        Text(
                            text = "Or type to speak with GPT 6 Luna...",
                            color = Color(0xFF64748B)
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Color(0xFF111827),
                        unfocusedContainerColor = Color(0xFF111827),
                        focusedBorderColor = Color(0xFF38BDF8),
                        unfocusedBorderColor = Color(0xFF1E293B)
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (quickVoiceInput.isNotBlank()) {
                                val text = quickVoiceInput
                                quickVoiceInput = ""
                                viewModel.submitSpokenPrompt(text)
                            }
                        }
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("luna_voice_text_input")
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilledIconButton(
                    onClick = {
                        if (quickVoiceInput.isNotBlank()) {
                            val text = quickVoiceInput
                            quickVoiceInput = ""
                            viewModel.submitSpokenPrompt(text)
                        }
                    },
                    enabled = quickVoiceInput.isNotBlank(),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color(0xFF0284C7),
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("luna_voice_send_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send to GPT 6 Luna"
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Primary Voice Call Controls Row: Mute | Primary Mic Orb | End Voice Session
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp)
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mute / Unmute Button
                FilledIconButton(
                    onClick = { viewModel.toggleMute() },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (uiState.isMuted) Color(0xFF7F1D1D) else Color(0xFF1E293B),
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .size(56.dp)
                        .testTag("luna_voice_mute_button")
                ) {
                    Icon(
                        imageVector = if (uiState.isMuted) Icons.Default.MicOff else Icons.Default.VolumeUp,
                        contentDescription = if (uiState.isMuted) "Unmute Microphone" else "Mute Microphone"
                    )
                }

                // Primary Microphone Listen / Stop Button
                FilledIconButton(
                    onClick = { handleMicButtonClick() },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = when {
                            uiState.isMicrophoneListening -> Color(0xFF0EA5E9)
                            uiState.stage == VoiceSessionStage.SPEAKING -> Color(0xFF10B981)
                            else -> Color(0xFF2563EB)
                        },
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .size(74.dp)
                        .clip(CircleShape)
                        .testTag("luna_voice_mic_button")
                ) {
                    Icon(
                        imageVector = if (uiState.isMicrophoneListening) Icons.Default.GraphicEq else Icons.Default.Mic,
                        contentDescription = if (uiState.isMicrophoneListening) "Stop Listening" else "Start Listening",
                        modifier = Modifier.size(34.dp)
                    )
                }

                // End Call / Return to Text Chat Button
                FilledIconButton(
                    onClick = {
                        viewModel.endVoiceSession()
                        onExitVoiceChat()
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color(0xFFDC2626),
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .size(56.dp)
                        .testTag("luna_voice_end_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = "End Voice Chat"
                    )
                }
            }
        }
    }
}

@Composable
private fun LunaAnimatedVoiceOrb(
    stage: VoiceSessionStage,
    rmsLevel: Float,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "luna_orb_transition")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (stage) {
                    VoiceSessionStage.LISTENING -> 650
                    VoiceSessionStage.THINKING -> 520
                    VoiceSessionStage.SPEAKING -> 480
                    else -> 1600
                },
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "luna_orb_pulse"
    )
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.283185f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "luna_orb_phase"
    )

    val primaryColor = when (stage) {
        VoiceSessionStage.LISTENING -> Color(0xFF38BDF8)
        VoiceSessionStage.THINKING -> Color(0xFFA855F7)
        VoiceSessionStage.SPEAKING -> Color(0xFF10B981)
        VoiceSessionStage.MUTED -> Color(0xFFEF4444)
        VoiceSessionStage.ERROR -> Color(0xFFF59E0B)
        VoiceSessionStage.IDLE -> Color(0xFF3B82F6)
    }

    val secondaryColor = when (stage) {
        VoiceSessionStage.LISTENING -> Color(0xFF0284C7)
        VoiceSessionStage.THINKING -> Color(0xFF6366F1)
        VoiceSessionStage.SPEAKING -> Color(0xFF059669)
        VoiceSessionStage.MUTED -> Color(0xFF991B1B)
        VoiceSessionStage.ERROR -> Color(0xFFD97706)
        VoiceSessionStage.IDLE -> Color(0xFF1D4ED8)
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.minDimension * 0.28f
            val dynamicBoost = 1f + (rmsLevel.coerceIn(0f, 1f) * 0.25f)
            val activeRadius = baseRadius * pulse * dynamicBoost

            // Outer glowing halo rings
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.28f),
                        secondaryColor.copy(alpha = 0.08f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = activeRadius * 1.65f
                ),
                radius = activeRadius * 1.65f,
                center = center
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.45f),
                        secondaryColor.copy(alpha = 0.15f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = activeRadius * 1.25f
                ),
                radius = activeRadius * 1.25f,
                center = center
            )

            // Core glowing orb
            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(primaryColor, secondaryColor),
                    start = Offset(center.x - activeRadius, center.y - activeRadius),
                    end = Offset(center.x + activeRadius, center.y + activeRadius)
                ),
                radius = activeRadius,
                center = center
            )

            // Dynamic waveform bars inside the orb
            val barCount = 7
            val spacing = (activeRadius * 1.1f) / barCount
            val startX = center.x - ((barCount - 1) * spacing / 2f)
            for (i in 0 until barCount) {
                val x = startX + i * spacing
                val activityMultiplier = when (stage) {
                    VoiceSessionStage.LISTENING,
                    VoiceSessionStage.SPEAKING -> 0.55f + 0.45f * sin(wavePhase + i * 0.85f)
                    VoiceSessionStage.THINKING -> 0.35f + 0.30f * cos(wavePhase + i * 0.9f)
                    else -> 0.18f
                }
                val barHalfHeight = (activeRadius * 0.48f * kotlin.math.abs(activityMultiplier)).coerceAtLeast(6f)
                drawLine(
                    color = Color.White.copy(alpha = 0.92f),
                    start = Offset(x, center.y - barHalfHeight),
                    end = Offset(x, center.y + barHalfHeight),
                    strokeWidth = 6f,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}
