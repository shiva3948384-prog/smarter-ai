package com.example.domain.models

data class UserProfile(
    val id: String,
    val email: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val isEmailVerified: Boolean = true,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMs: Long,
    val user: UserProfile,
    val hasValidCloudJwt: Boolean = false
)

/**
 * Active 6-digit email OTP verification challenge state during signup.
 */
data class OtpChallengeState(
    val email: String,
    val displayName: String = "",
    val userId: String? = null,
    val expiresAtEpochMs: Long,
    val resendAvailableAtEpochMs: Long,
    val maxAttempts: Int = 5,
    val remainingAttempts: Int = 5,
    val notice: String? = null
)

/**
 * Internal AI provider enum.
 * - APINEX: Primary and only chat AI provider (https://api.apinex.bond/v1), presented as Smarter AI.
 * - POLLINATIONS: Reserved strictly for Image Studio and Voice/Audio generation.
 */
enum class AiProvider(val displayName: String) {
    APINEX("Smarter AI"),
    POLLINATIONS("Media Studio")
}

enum class ModelCategory(val displayName: String) {
    RECOMMENDED("Recommended"),
    GENERAL_CHAT("General Chat"),
    CODING("Coding & Debug"),
    REASONING("Reasoning"),
    MULTIMODAL_VISION("Vision & Image Analysis"),
    CREATIVE_WRITING("Creative")
}

enum class ModelCapability(val label: String) {
    STREAMING("Streaming"),
    VISION("Vision"),
    REASONING("Reasoning"),
    CODE_GENERATION("Code"),
    LONG_CONTEXT("Long Context"),
    FAST_INFERENCE("Fast"),
    MULTILINGUAL("Multilingual"),
    TOOL_CALLING("Tools"),
    VOICE_CHAT("Voice Chat")
}

enum class AvailabilityStatus(val label: String) {
    AVAILABLE("Available"),
    UNVERIFIED("Ready"),
    UNAVAILABLE("Unavailable")
}

/**
 * Model metadata definition separating internal [providerModelId] from user-facing [displayName]
 * and [capabilities].
 */
data class AiModelDefinition(
    val providerModelId: String,
    val displayName: String,
    val provider: AiProvider = AiProvider.APINEX,
    val category: ModelCategory,
    val description: String,
    val capabilities: Set<ModelCapability>,
    val supportsStreaming: Boolean = true,
    val supportsVision: Boolean = false,
    val supportsTools: Boolean = true,
    val isRecommended: Boolean = false,
    val availabilityStatus: AvailabilityStatus = AvailabilityStatus.AVAILABLE,
    val contextWindowTokens: Int? = 1_000_000
) {
    val supportsVisionInput: Boolean
        get() = supportsVision
}

data class ImageModelSpec(
    val modelId: String,
    val displayName: String,
    val description: String,
    val requiresApiKeyHeader: Boolean = false
)

enum class ImageAspectRatio(
    val label: String,
    val subtitle: String,
    val width: Int,
    val height: Int
) {
    SQUARE_1_1("1:1", "1024×1024", 1024, 1024),
    PORTRAIT_3_4("3:4", "768×1024", 768, 1024),
    LANDSCAPE_4_3("4:3", "1024×768", 1024, 768),
    WIDESCREEN_16_9("16:9", "1280×720", 1280, 720)
}

data class GeneratedImageResult(
    val id: String,
    val prompt: String,
    val modelId: String,
    val width: Int,
    val height: Int,
    val seed: Long,
    val imageBytes: ByteArray,
    val mimeType: String = "image/jpeg",
    val imageUrl: String = "",
    val localFilePath: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

enum class ImageGenerationStage(
    val displayTitle: String,
    val statusDetail: String,
    val stepIndex: Int
) {
    PREPARING_PROMPT("Preparing Prompt", "Analyzing prompt and configuring dimensions…", 1),
    CONNECTING_TO_MODEL("Connecting to Image Model", "Connecting to Pollinations image synthesis engine…", 2),
    GENERATING_IMAGE("Generating Image", "Rendering artwork with Pollinations AI…", 3),
    RECEIVING_IMAGE("Receiving Image", "Downloading generated image bytes…", 4),
    PROCESSING_FINAL_IMAGE("Processing Final Image", "Validating and decoding final image…", 5),
    COMPLETED("Completed", "Image received and verified (100%)", 6),
    FAILED("Generation Failed", "Could not generate image", 0),
    CANCELLED("Cancelled", "Image generation cancelled by user", 0);

    val displayLabel: String
        get() = displayTitle
}

data class ActiveImageGenerationSession(
    val sessionId: String,
    val conversationId: String? = null,
    val originalUserPrompt: String,
    val extractedPrompt: String,
    val modelId: String,
    val modelDisplayName: String = "Smarter Flux Image",
    val aspectRatio: ImageAspectRatio = ImageAspectRatio.SQUARE_1_1,
    val width: Int = 1024,
    val height: Int = 1024,
    val aspectRatioLabel: String = "${width}×${height}",
    val seed: Long = 42L,
    val stage: ImageGenerationStage = ImageGenerationStage.PREPARING_PROMPT,
    val isScreenVisible: Boolean = true,
    val startedAtEpochMs: Long = System.currentTimeMillis(),
    val elapsedTimeMs: Long = 0L,
    val progressPercentage: Int? = null,
    val result: GeneratedImageResult? = null,
    val persistedMessageId: String? = null,
    val errorMessage: String? = null,
    val canRetry: Boolean = false
) {
    val isGenerating: Boolean
        get() = stage != ImageGenerationStage.COMPLETED &&
            stage != ImageGenerationStage.FAILED &&
            stage != ImageGenerationStage.CANCELLED
}

data class SpeechVoiceSpec(
    val voiceId: String,
    val displayName: String,
    val toneDescription: String
)

data class GeneratedSpeechResult(
    val id: String,
    val text: String,
    val voiceId: String,
    val audioBytes: ByteArray?,
    val usedDeviceFallbackTts: Boolean = false,
    val statusNotice: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

enum class MessageRole(val apiValue: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant");

    companion object {
        fun fromApiValue(value: String): MessageRole {
            return entries.firstOrNull { it.apiValue.equals(value, ignoreCase = true) } ?: USER
        }
    }
}

enum class MessageStatus(val dbValue: String) {
    SENDING("sending"),
    STREAMING("streaming"),
    COMPLETED("completed"),
    INTERRUPTED("interrupted"),
    ERROR("error");

    companion object {
        fun fromDbValue(value: String): MessageStatus {
            return entries.firstOrNull { it.dbValue.equals(value, ignoreCase = true) } ?: COMPLETED
        }
    }
}

data class MessageAttachment(
    val id: String,
    val uriString: String,
    val mimeType: String = "image/jpeg",
    val fileName: String = "image.jpg",
    val base64DataUrl: String? = null
)

data class ChatMessage(
    val id: String,
    val conversationId: String,
    val userId: String,
    val role: MessageRole,
    val content: String,
    val modelId: String,
    val requestedModelId: String? = null,
    val fallbackUsed: Boolean = false,
    val status: MessageStatus = MessageStatus.COMPLETED,
    val errorMessage: String? = null,
    val errorCode: String? = null,
    val attachments: List<MessageAttachment> = emptyList(),
    val clientRequestId: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

data class Conversation(
    val id: String,
    val userId: String,
    val title: String,
    val selectedModelId: String,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
    val archivedAtEpochMs: Long? = null
)

enum class ConversationTimeGroup(val displayTitle: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    PREVIOUS_7_DAYS("Previous 7 Days"),
    OLDER("Older");

    val label: String
        get() = displayTitle
}

data class ConversationSearchResult(
    val conversation: Conversation,
    val snippet: String,
    val matchedInMessage: Boolean
)

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark")
}

data class AppPreferences(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val defaultModelId: String = AiModelCatalog.DEFAULT_MODEL_ID,
    val fallbackModelId: String? = null,
    val allowModelFallback: Boolean = false,
    val enterToSend: Boolean = false,
    val reducedAnimations: Boolean = false,
    val streamResponses: Boolean = true,
    val renderMarkdown: Boolean = true
)

enum class AiErrorType {
    RATE_LIMITED,
    UNAUTHORIZED,
    UNSUPPORTED_MODEL,
    CONTEXT_LENGTH_EXCEEDED,
    NETWORK_TIMEOUT,
    SERVER_ERROR,
    UNKNOWN
}

class AiGatewayException(
    val errorType: AiErrorType,
    val userFriendlyMessage: String,
    val requestedModelId: String? = null,
    val statusCode: Int? = null,
    val canRetry: Boolean = true
) : Exception(userFriendlyMessage)

enum class RequestLifecycleState {
    IDLE,
    PREPARING,
    SENDING,
    STREAMING,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED || this == TIMED_OUT

    val isActive: Boolean
        get() = this == PREPARING || this == SENDING || this == STREAMING || this == PROCESSING
}

enum class GenerationState {
    IDLE,
    REQUEST_SENDING,
    WAITING_FOR_FIRST_TOKEN,
    STREAMING,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class ImageAnalysisState {
    IDLE,
    IMAGE_ANALYSIS_STARTED,
    IMAGE_ANALYSIS_COMPLETED,
    RESPONSE_GENERATION,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class LiveDataCategory(val displayTitle: String) {
    PRECIOUS_METALS("Precious Metals"),
    CRYPTOCURRENCY("Cryptocurrency"),
    FOREIGN_EXCHANGE("Foreign Exchange"),
    CURRENCY_CONVERSION("Currency Conversion"),
    STOCKS_AND_INDICES("Stocks & Indices"),
    COMMODITIES("Commodities"),
    CURRENT_INFORMATION("Current Information")
}

enum class LiveDataFreshness(val badgeLabel: String) {
    LIVE("LIVE"),
    REFERENCE_DAILY("REFERENCE RATE"),
    DELAYED("DELAYED"),
    CACHED("CACHED"),
    UNAVAILABLE("UNAVAILABLE")
}

data class ChartDataPoint(
    val timestampEpochMs: Long,
    val label: String,
    val value: Double
)

data class PreciousMetalBreakdown(
    val metalName: String,
    val spotPricePerTroyOunceUsd: Double,
    val usdToLocalRate: Double? = null,
    val localCurrencyCode: String = "INR",
    val price24KPerGramLocal: Double? = null,
    val price24KPer10GramsLocal: Double? = null,
    val price22KPerGramLocal: Double? = null,
    val price22KPer10GramsLocal: Double? = null,
    val price24KPerGramUsd: Double,
    val price22KPerGramUsd: Double,
    val marketTypeNotice: String
)

data class CurrencyConversionDetail(
    val sourceAmount: Double,
    val fromCurrency: String,
    val toCurrency: String,
    val exchangeRate: Double,
    val convertedAmount: Double,
    val spreadDisclaimer: String = "Indicative mid-market rate. Actual bank, card, or payment-provider conversion rates may differ due to transaction fees and spreads."
)

data class LiveMarketDataPayload(
    val id: String,
    val category: LiveDataCategory,
    val title: String,
    val symbolOrPair: String,
    val currentValue: Double?,
    val formattedPrimaryValue: String,
    val currencyOrUnit: String,
    val change24hPercent: Double? = null,
    val high24h: Double? = null,
    val low24h: Double? = null,
    val volume24h: Double? = null,
    val marketCap: Double? = null,
    val providerName: String,
    val sourceAttributionUrl: String = "",
    val providerTimestampEpochMs: Long,
    val formattedTimestamp: String,
    val freshness: LiveDataFreshness,
    val timeframeLabel: String = "24H",
    val historicalPoints: List<ChartDataPoint> = emptyList(),
    val historicalUnavailableReason: String? = null,
    val preciousMetalBreakdown: PreciousMetalBreakdown? = null,
    val currencyConversion: CurrencyConversionDetail? = null,
    val verifiedContextForAiPrompt: String = "",
    val errorOrLimitationNotice: String? = null
)

/**
 * Captures the 11 independent lifecycle stage timestamps (in epoch ms) and computed latencies
 * for every chat completion and image analysis request.
 */
data class RequestTimingMetrics(
    val modelId: String = "",
    val hasImageAttachment: Boolean = false,
    val sendPressedEpochMs: Long = 0L,
    val androidRequestStartedEpochMs: Long = 0L,
    val requestReachesSupabaseEpochMs: Long = 0L,
    val supabaseUpstreamStartedEpochMs: Long = 0L,
    val apinexHeadersReceivedEpochMs: Long = 0L,
    val firstSseEventEpochMs: Long = 0L,
    val firstUsableTokenEpochMs: Long = 0L,
    val finalTokenEpochMs: Long = 0L,
    val doneEventEpochMs: Long = 0L,
    val dbPersistedEpochMs: Long = 0L,
    val uiRenderedEpochMs: Long = 0L,
    val finishReason: String? = null,
    val sawDoneEvent: Boolean = false,
    val sseEventCount: Int = 0,
    val characterCount: Int = 0
) {
    private val baseStartMs: Long
        get() = if (sendPressedEpochMs > 0L) sendPressedEpochMs else androidRequestStartedEpochMs

    /** Time to first response byte / SSE event from user pressing Send */
    val ttfbMs: Long
        get() = if (firstSseEventEpochMs > 0L && baseStartMs > 0L) {
            (firstSseEventEpochMs - baseStartMs).coerceAtLeast(0L)
        } else 0L

    /** Time to first usable visible token from user pressing Send */
    val ttftMs: Long
        get() = if (firstUsableTokenEpochMs > 0L && baseStartMs > 0L) {
            (firstUsableTokenEpochMs - baseStartMs).coerceAtLeast(0L)
        } else 0L

    /** Total streaming generation time from first SSE event to stream completion */
    val totalGenerationMs: Long
        get() {
            val endMs = if (doneEventEpochMs > 0L) doneEventEpochMs else finalTokenEpochMs
            val startMs = if (firstSseEventEpochMs > 0L) firstSseEventEpochMs else androidRequestStartedEpochMs
            return if (endMs > 0L && startMs > 0L) (endMs - startMs).coerceAtLeast(0L) else 0L
        }

    /** Database persistence duration after stream completion */
    val dbPersistenceMs: Long
        get() {
            val streamEnd = if (doneEventEpochMs > 0L) doneEventEpochMs else finalTokenEpochMs
            return if (dbPersistedEpochMs > 0L && streamEnd > 0L) {
                (dbPersistedEpochMs - streamEnd).coerceAtLeast(0L)
            } else 0L
        }

    /** Total end-to-end request time from Send pressed to final UI render */
    val totalRequestMs: Long
        get() {
            val endMs = when {
                uiRenderedEpochMs > 0L -> uiRenderedEpochMs
                dbPersistedEpochMs > 0L -> dbPersistedEpochMs
                doneEventEpochMs > 0L -> doneEventEpochMs
                else -> finalTokenEpochMs
            }
            return if (endMs > 0L && baseStartMs > 0L) (endMs - baseStartMs).coerceAtLeast(0L) else 0L
        }
}

sealed class StreamChunk {
    data class Meta(
        val activeModelId: String,
        val requestedModelId: String,
        val fallbackUsed: Boolean,
        val timingMetrics: RequestTimingMetrics? = null
    ) : StreamChunk()

    data class ReasoningDelta(
        val reasoningDelta: String,
        val activeModelId: String
    ) : StreamChunk()

    data class Delta(
        val textDelta: String,
        val activeModelId: String,
        val fallbackUsed: Boolean = false
    ) : StreamChunk()

    data class Completed(
        val fullText: String,
        val activeModelId: String,
        val fallbackUsed: Boolean = false,
        val finishReason: String? = "stop",
        val timingMetrics: RequestTimingMetrics? = null
    ) : StreamChunk()

    data class Error(
        val exception: AiGatewayException,
        val partialText: String = "",
        val timingMetrics: RequestTimingMetrics? = null
    ) : StreamChunk()
}

