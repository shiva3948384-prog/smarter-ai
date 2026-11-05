package com.example.ui.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.network.SupabaseAndGatewayClient
import com.example.domain.models.AiModelCatalog
import com.example.domain.models.ChatMessage
import com.example.domain.models.MessageRole
import com.example.domain.models.MessageStatus
import com.example.domain.models.RequestLifecycleState
import com.example.domain.models.StreamChunk
import com.example.domain.repositories.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class VoiceSessionStage(val statusLabel: String) {
    IDLE("Tap the microphone to talk with GPT 6 Luna"),
    LISTENING("Listening..."),
    THINKING("GPT 6 Luna is thinking..."),
    SPEAKING("GPT 6 Luna is speaking..."),
    MUTED("Microphone muted"),
    ERROR("Voice session issue")
}

data class VoiceTurnTranscript(
    val id: String = UUID.randomUUID().toString(),
    val userTranscript: String,
    val lunaResponse: String,
    val timestampEpochMs: Long = System.currentTimeMillis()
)

data class LunaVoiceChatUiState(
    val dedicatedModelId: String = AiModelCatalog.VOICE_CHAT_LUNA_MODEL_ID,
    val displayModelName: String = AiModelCatalog.getDisplayName(AiModelCatalog.VOICE_CHAT_LUNA_MODEL_ID),
    val stage: VoiceSessionStage = VoiceSessionStage.IDLE,
    val requestLifecycleState: RequestLifecycleState = RequestLifecycleState.IDLE,
    val activeRequestId: String? = null,
    val isMicrophoneListening: Boolean = false,
    val isMuted: Boolean = false,
    val isTtsSpeaking: Boolean = false,
    val liveUserTranscript: String = "",
    val partialLunaResponse: String = "",
    val latestLunaResponse: String = "",
    val turns: List<VoiceTurnTranscript> = emptyList(),
    val errorMessage: String? = null,
    val rmsDbLevel: Float = 0f
)

/**
 * Dedicated ViewModel for GPT 6 Luna Voice Chat (`free/gpt-6-luna`).
 * Always routes requests exclusively through `free/gpt-6-luna` via Apinex,
 * completely independent of whichever model is selected in normal text chat.
 */
class LunaVoiceChatViewModel(
    private val authRepository: AuthRepository,
    private val networkClient: SupabaseAndGatewayClient
) : ViewModel() {

    companion object {
        const val DEDICATED_VOICE_MODEL_ID = AiModelCatalog.VOICE_CHAT_LUNA_MODEL_ID

        fun provideFactory(
            authRepository: AuthRepository,
            networkClient: SupabaseAndGatewayClient
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return LunaVoiceChatViewModel(
                    authRepository = authRepository,
                    networkClient = networkClient
                ) as T
            }
        }
    }

    private val _uiState = MutableStateFlow(LunaVoiceChatUiState())
    val uiState: StateFlow<LunaVoiceChatUiState> = _uiState.asStateFlow()

    private val voiceConversationId = "luna_voice_${UUID.randomUUID()}"
    private val voiceHistory = mutableListOf<ChatMessage>()
    private var activeLunaRequestJob: Job? = null

    // Callback invoked when a complete or sentence-level response is ready to be spoken via Android TTS
    var onSpeakTextRequested: ((String) -> Unit)? = null
    var onStopTtsRequested: (() -> Unit)? = null

    fun onStartListening() {
        if (_uiState.value.isMuted) {
            _uiState.update {
                it.copy(
                    stage = VoiceSessionStage.MUTED,
                    errorMessage = "Unmute the microphone to speak."
                )
            }
            return
        }
        // Barge-in / interrupt: if Luna is currently speaking or thinking, cancel immediately
        interruptCurrentResponse()

        _uiState.update {
            it.copy(
                stage = VoiceSessionStage.LISTENING,
                isMicrophoneListening = true,
                isTtsSpeaking = false,
                liveUserTranscript = "",
                errorMessage = null
            )
        }
    }

    fun onPartialSpeechRecognized(partialText: String) {
        if (_uiState.value.isMuted) return
        _uiState.update {
            it.copy(
                stage = VoiceSessionStage.LISTENING,
                isMicrophoneListening = true,
                liveUserTranscript = partialText
            )
        }
    }

    fun onRmsChanged(rmsDb: Float) {
        val normalized = ((rmsDb + 2f) / 12f).coerceIn(0.05f, 1.0f)
        _uiState.update { it.copy(rmsDbLevel = normalized) }
    }

    fun onStopListening() {
        _uiState.update {
            it.copy(
                isMicrophoneListening = false,
                rmsDbLevel = 0f,
                stage = if (it.stage == VoiceSessionStage.LISTENING) VoiceSessionStage.IDLE else it.stage
            )
        }
    }

    fun toggleMute() {
        val nextMuted = !_uiState.value.isMuted
        if (nextMuted) {
            onStopTtsRequested?.invoke()
            activeLunaRequestJob?.cancel()
            activeLunaRequestJob = null
        }
        _uiState.update {
            it.copy(
                isMuted = nextMuted,
                isMicrophoneListening = if (nextMuted) false else it.isMicrophoneListening,
                isTtsSpeaking = if (nextMuted) false else it.isTtsSpeaking,
                stage = if (nextMuted) VoiceSessionStage.MUTED else VoiceSessionStage.IDLE,
                errorMessage = null
            )
        }
    }

    /**
     * Interrupts any active TTS speech or pending `free/gpt-6-luna` stream (barge-in support).
     */
    fun interruptCurrentResponse() {
        activeLunaRequestJob?.cancel()
        activeLunaRequestJob = null
        onStopTtsRequested?.invoke()
        _uiState.update {
            it.copy(
                isTtsSpeaking = false,
                requestLifecycleState = if (it.requestLifecycleState.isActive) {
                    RequestLifecycleState.CANCELLED
                } else {
                    it.requestLifecycleState
                },
                activeRequestId = null,
                stage = if (it.isMuted) VoiceSessionStage.MUTED else VoiceSessionStage.IDLE
            )
        }
    }

    /**
     * Submits recognized user speech (or typed voice prompt) exclusively to `free/gpt-6-luna`.
     */
    fun submitSpokenPrompt(spokenText: String) {
        val cleaned = spokenText.trim()
        if (cleaned.isEmpty()) {
            _uiState.update {
                it.copy(
                    isMicrophoneListening = false,
                    stage = if (it.isMuted) VoiceSessionStage.MUTED else VoiceSessionStage.IDLE
                )
            }
            return
        }

        // Cancel any previous in-flight request or TTS playback
        activeLunaRequestJob?.cancel()
        onStopTtsRequested?.invoke()

        val requestId = UUID.randomUUID().toString()
        val userId = authRepository.sessionFlow.value?.user?.id ?: "voice_user"
        val accessToken = authRepository.sessionFlow.value?.accessToken.orEmpty()
        val nowMs = System.currentTimeMillis()

        // Add concise voice instruction on first turn so GPT 6 Luna speaks naturally and conversationally
        val promptWithVoiceGuidance = if (voiceHistory.isEmpty()) {
            "[Voice Conversation Mode: Respond naturally, warmly, and concisely for spoken playback without markdown tables or long code blocks unless asked.]\n$cleaned"
        } else {
            cleaned
        }

        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            conversationId = voiceConversationId,
            userId = userId,
            role = MessageRole.USER,
            content = promptWithVoiceGuidance,
            modelId = DEDICATED_VOICE_MODEL_ID,
            status = MessageStatus.COMPLETED,
            clientRequestId = requestId,
            createdAtEpochMs = nowMs,
            updatedAtEpochMs = nowMs
        )
        voiceHistory.add(userMsg)

        _uiState.update {
            it.copy(
                dedicatedModelId = DEDICATED_VOICE_MODEL_ID,
                displayModelName = AiModelCatalog.getDisplayName(DEDICATED_VOICE_MODEL_ID),
                stage = VoiceSessionStage.THINKING,
                requestLifecycleState = RequestLifecycleState.SENDING,
                activeRequestId = requestId,
                isMicrophoneListening = false,
                isTtsSpeaking = false,
                liveUserTranscript = cleaned,
                partialLunaResponse = "",
                errorMessage = null,
                rmsDbLevel = 0f
            )
        }

        activeLunaRequestJob = viewModelScope.launch {
            val responseBuilder = StringBuilder()
            try {
                networkClient.streamChatCompletion(
                    accessToken = accessToken,
                    conversationId = voiceConversationId,
                    requestedModelId = DEDICATED_VOICE_MODEL_ID, // Strictly locked to free/gpt-6-luna
                    fallbackModelId = null,
                    allowFallback = false,
                    history = voiceHistory.toList(),
                    streamEnabled = true,
                    clientRequestId = requestId
                ).collect { chunk ->
                    when (chunk) {
                        is StreamChunk.Meta -> {
                            _uiState.update { current ->
                                if (current.activeRequestId == requestId) {
                                    current.copy(
                                        stage = VoiceSessionStage.THINKING,
                                        requestLifecycleState = RequestLifecycleState.SENDING
                                    )
                                } else current
                            }
                        }
                        is StreamChunk.ReasoningDelta -> {
                            _uiState.update { current ->
                                if (current.activeRequestId == requestId) {
                                    current.copy(
                                        stage = VoiceSessionStage.THINKING,
                                        requestLifecycleState = RequestLifecycleState.PROCESSING
                                    )
                                } else current
                            }
                        }
                        is StreamChunk.Delta -> {
                            responseBuilder.append(chunk.textDelta)
                            val partial = responseBuilder.toString()
                            _uiState.update { current ->
                                if (current.activeRequestId == requestId) {
                                    current.copy(
                                        stage = VoiceSessionStage.SPEAKING,
                                        requestLifecycleState = RequestLifecycleState.STREAMING,
                                        partialLunaResponse = partial,
                                        latestLunaResponse = partial
                                    )
                                } else current
                            }
                        }
                        is StreamChunk.Completed -> {
                            val finalResponse = chunk.fullText.ifBlank { responseBuilder.toString() }.trim()
                            val assistantMsg = ChatMessage(
                                id = UUID.randomUUID().toString(),
                                conversationId = voiceConversationId,
                                userId = userId,
                                role = MessageRole.ASSISTANT,
                                content = finalResponse,
                                modelId = DEDICATED_VOICE_MODEL_ID,
                                status = MessageStatus.COMPLETED,
                                clientRequestId = "${requestId}_luna",
                                createdAtEpochMs = System.currentTimeMillis(),
                                updatedAtEpochMs = System.currentTimeMillis()
                            )
                            voiceHistory.add(assistantMsg)

                            val newTurn = VoiceTurnTranscript(
                                userTranscript = cleaned,
                                lunaResponse = finalResponse
                            )
                            _uiState.update { current ->
                                current.copy(
                                    stage = if (current.isMuted) VoiceSessionStage.MUTED else VoiceSessionStage.SPEAKING,
                                    requestLifecycleState = RequestLifecycleState.COMPLETED,
                                    activeRequestId = null,
                                    isTtsSpeaking = !current.isMuted && finalResponse.isNotBlank(),
                                    partialLunaResponse = "",
                                    latestLunaResponse = finalResponse,
                                    turns = current.turns + newTurn,
                                    errorMessage = null
                                )
                            }
                            if (!_uiState.value.isMuted && finalResponse.isNotBlank()) {
                                onSpeakTextRequested?.invoke(stripMarkdownForSpeech(finalResponse))
                            }
                        }
                        is StreamChunk.Error -> {
                            val errMsg = chunk.exception.userFriendlyMessage
                            _uiState.update { current ->
                                current.copy(
                                    stage = VoiceSessionStage.ERROR,
                                    requestLifecycleState = RequestLifecycleState.FAILED,
                                    activeRequestId = null,
                                    isTtsSpeaking = false,
                                    partialLunaResponse = "",
                                    errorMessage = errMsg
                                )
                            }
                        }
                    }
                }
            } catch (ce: CancellationException) {
                _uiState.update { current ->
                    current.copy(
                        requestLifecycleState = RequestLifecycleState.CANCELLED,
                        activeRequestId = null,
                        isTtsSpeaking = false,
                        partialLunaResponse = "",
                        stage = if (current.isMuted) VoiceSessionStage.MUTED else VoiceSessionStage.IDLE
                    )
                }
                throw ce
            } catch (t: Throwable) {
                _uiState.update { current ->
                    current.copy(
                        stage = VoiceSessionStage.ERROR,
                        requestLifecycleState = RequestLifecycleState.FAILED,
                        activeRequestId = null,
                        isTtsSpeaking = false,
                        partialLunaResponse = "",
                        errorMessage = t.message ?: "Voice response failed. Tap the microphone to try again."
                    )
                }
            } finally {
                if (_uiState.value.activeRequestId == requestId) {
                    _uiState.update { current ->
                        current.copy(
                            activeRequestId = null,
                            requestLifecycleState = if (current.requestLifecycleState.isActive) {
                                RequestLifecycleState.IDLE
                            } else {
                                current.requestLifecycleState
                            }
                        )
                    }
                }
            }
        }
    }

    fun onTtsPlaybackCompleted() {
        _uiState.update { current ->
            current.copy(
                isTtsSpeaking = false,
                stage = if (current.isMuted) VoiceSessionStage.MUTED else VoiceSessionStage.IDLE
            )
        }
    }

    fun onSpeechRecognitionError(friendlyError: String) {
        _uiState.update { current ->
            current.copy(
                isMicrophoneListening = false,
                rmsDbLevel = 0f,
                stage = VoiceSessionStage.ERROR,
                errorMessage = friendlyError
            )
        }
    }

    /**
     * Stops all active microphone recording, speech recognition, pending `free/gpt-6-luna`
     * streaming requests, and TTS playback when ending or exiting Voice Chat.
     */
    fun endVoiceSession() {
        activeLunaRequestJob?.cancel()
        activeLunaRequestJob = null
        onStopTtsRequested?.invoke()
        _uiState.update {
            it.copy(
                stage = VoiceSessionStage.IDLE,
                requestLifecycleState = RequestLifecycleState.IDLE,
                activeRequestId = null,
                isMicrophoneListening = false,
                isTtsSpeaking = false,
                liveUserTranscript = "",
                partialLunaResponse = "",
                errorMessage = null,
                rmsDbLevel = 0f
            )
        }
    }

    override fun onCleared() {
        endVoiceSession()
        super.onCleared()
    }

    private fun stripMarkdownForSpeech(markdown: String): String {
        return markdown
            .replace(Regex("""```[\s\S]*?```"""), " Code block omitted for voice playback. ")
            .replace(Regex("""[*_`#~>|]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
