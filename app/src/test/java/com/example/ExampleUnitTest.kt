package com.example

import com.example.data.network.SupabaseAndGatewayClient
import com.example.data.network.SupabaseAndGatewayClient.Companion.ConversationLanguage
import com.example.data.livedata.LiveIntentDetectionResult
import com.example.data.livedata.LiveDataIntelligenceService
import com.example.domain.models.AiErrorType
import com.example.domain.models.AiModelCatalog
import com.example.domain.models.AiProvider
import com.example.domain.models.AvailabilityStatus
import com.example.domain.models.ChatMessage
import com.example.domain.models.Conversation
import com.example.domain.models.ConversationTimeGroup
import com.example.domain.models.LiveDataCategory
import com.example.domain.models.MessageRole
import com.example.domain.models.MessageStatus
import com.example.domain.models.ImageAspectRatio
import com.example.domain.models.MessageAttachment
import com.example.domain.usecases.AuthValidationUseCase
import com.example.domain.usecases.ChatRequestRoute
import com.example.domain.usecases.ConversationContextManager
import com.example.domain.usecases.ConversationGroupingUseCase
import com.example.domain.usecases.ImageAttachmentProcessor
import com.example.domain.usecases.ImageGenerationIntentDetector
import com.example.ui.components.MarkdownBlock
import com.example.ui.components.MarkdownParser
import com.example.ui.components.SmartCopyBlockDetector
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ExampleUnitTest {

    // ========================================================================
    // 1. Apinex Bond API 12-Model Catalog, Prefix Hiding & Media Studio Tests
    // ========================================================================

    @Test
    fun `model catalog contains exclusively the 12 unique Apinex Bond API chat models without duplicates while hiding free prefix in UI`() {
        val chatModels = AiModelCatalog.INITIAL_DISCOVERY_MODELS
        assertEquals("Active chat model catalog must contain exactly 12 unique Apinex models", 12, chatModels.size)
        assertEquals(
            "Catalog must not contain duplicate model IDs (e.g. free/gemini-3.8-flash)",
            12,
            chatModels.map { it.providerModelId }.distinct().size
        )
        assertEquals(
            listOf(
                "free/muse-spark-1.3",
                "free/gemini-3.8-flash",
                "free/qwen-3.8-max",
                "free/kimi-k3",
                "free/deepseek-v4.1-flash",
                "free/glm-5.3",
                "free/glm-5.3-flash",
                "free/gpt-6-luna",
                "free/jev-1.13",
                "free/hy4",
                "free/mimo-v2.6-flash",
                "free/minimax-m3.1-flash"
            ),
            chatModels.map { it.providerModelId }
        )
        assertEquals("free/glm-5.3-flash", AiModelCatalog.DEFAULT_MODEL_ID)
        assertEquals("free/qwen-3.8-max", AiModelCatalog.DEFAULT_CODING_MODEL_ID)
        assertEquals("free/deepseek-v4.1-flash", AiModelCatalog.DEFAULT_REASONING_MODEL_ID)
        assertEquals("free/gemini-3.8-flash", AiModelCatalog.DEFAULT_VISION_MODEL_ID)
        assertEquals("free/gpt-6-luna", AiModelCatalog.VOICE_CHAT_LUNA_MODEL_ID)

        // Verify no user-facing display name contains "free/"
        chatModels.forEach { model ->
            assertFalse(
                "User-facing displayName must never expose 'free/' prefix: ${model.displayName}",
                model.displayName.contains("free/", ignoreCase = true)
            )
            val formatted = AiModelCatalog.formatUserFacingModelName(model.providerModelId, chatModels)
            assertFalse(
                "formatUserFacingModelName must strip 'free/' prefix: $formatted",
                formatted.contains("free/", ignoreCase = true)
            )
        }
        assertEquals("GPT 6 Luna", AiModelCatalog.formatUserFacingModelName("free/gpt-6-luna"))
        assertEquals("Custom Model 1.0", AiModelCatalog.formatUserFacingModelName("free/custom-model-1.0"))

        val unknownModel = AiModelCatalog.findById("unknown/model-id")
        assertEquals(AiProvider.APINEX, unknownModel.provider)
        assertEquals(AvailabilityStatus.UNAVAILABLE, unknownModel.availabilityStatus)

        // Verify Pollinations image and audio models are preserved intact for Media Studios
        val freeImageIds = AiModelCatalog.POLLINATIONS_FREE_IMAGE_MODELS.map { it.modelId }
        assertTrue(freeImageIds.contains("sana"))
        assertTrue(freeImageIds.contains("flux"))
        assertTrue(freeImageIds.contains("community/scriptsnsenses-sys/flux-2-dev-free"))
        assertEquals(6, AiModelCatalog.POLLINATIONS_AUDIO_VOICES.size)
    }

    @Test
    fun `mergeWithLiveProviderModels updates availability status for live Apinex models without adding extra models`() {
        val merged = AiModelCatalog.mergeWithLiveProviderModels(
            baseCatalog = AiModelCatalog.INITIAL_DISCOVERY_MODELS,
            liveModelIds = setOf("free/muse-spark-1.3", "free/glm-5.3-flash", "free/gpt-6-luna", "other/unlisted-model")
        )
        assertEquals(12, merged.size)
        assertEquals(AvailabilityStatus.AVAILABLE, merged.first { it.providerModelId == "free/muse-spark-1.3" }.availabilityStatus)
        assertEquals(AvailabilityStatus.AVAILABLE, merged.first { it.providerModelId == "free/glm-5.3-flash" }.availabilityStatus)
        assertEquals(AvailabilityStatus.AVAILABLE, merged.first { it.providerModelId == "free/gpt-6-luna" }.availabilityStatus)
        assertEquals(AvailabilityStatus.UNAVAILABLE, merged.first { it.providerModelId == "free/kimi-k3" }.availabilityStatus)
    }

    // ========================================================================
    // 2. Authentication & Supabase Key Sanitization Validation Tests
    // ========================================================================

    @Test
    fun `sanitizeSupabaseAnonKey deduplicates doubled 416-char JWT key and strips Bearer prefix`() {
        val singleJwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inh3a2Z5YW11bGhGcHRneXdsdmVmIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzU3MTg5MDgsImV4cCI6MjA5MTI5NDkwOH0.sampleSignaturePart1234567890abcdef"
        val doubledJwt = singleJwt + singleJwt

        val cleanedFromDoubled = SupabaseAndGatewayClient.sanitizeSupabaseAnonKey(doubledJwt)
        assertEquals("Doubled JWT must be deduplicated into single 3-part JWT", singleJwt, cleanedFromDoubled)
        assertEquals(2, cleanedFromDoubled.count { it == '.' })

        val cleanedFromBearer = SupabaseAndGatewayClient.sanitizeSupabaseAnonKey("  \"Bearer $singleJwt\" ")
        assertEquals(singleJwt, cleanedFromBearer)
    }

    @Test
    fun `signup validation rejects weak passwords, mismatched confirmation, and invalid emails`() {
        val badEmail = AuthValidationUseCase.validateSignUp("not-an-email", "Password123", "Password123", "Alex")
        assertFalse(badEmail.isValid)

        val shortPass = AuthValidationUseCase.validateSignUp("user@example.com", "Ab1", "Ab1", "Alex")
        assertFalse(shortPass.isValid)

        val noDigits = AuthValidationUseCase.validateSignUp("user@example.com", "PasswordOnly", "PasswordOnly", "Alex")
        assertFalse(noDigits.isValid)

        val mismatch = AuthValidationUseCase.validateSignUp("user@example.com", "Password123", "Password999", "Alex")
        assertFalse(mismatch.isValid)

        val valid = AuthValidationUseCase.validateSignUp("user@example.com", "Password123", "Password123", "Alex")
        assertTrue(valid.isValid)
    }

    // ========================================================================
    // 3. Gateway Error Normalization Tests
    // ========================================================================

    @Test
    fun `mapHttpErrorToGatewayException normalizes rate limits, timeouts, invalid credentials, unsupported models, and context length`() {
        val rateLimit = SupabaseAndGatewayClient.mapHttpErrorToGatewayException(
            429,
            """{"code":"RATE_LIMITED","message":"Rate limit reached"}""",
            ""
        )
        assertEquals(AiErrorType.RATE_LIMITED, rateLimit.errorType)
        assertTrue(rateLimit.canRetry)

        val timeout = SupabaseAndGatewayClient.mapHttpErrorToGatewayException(
            504,
            """{"error":{"code":"NETWORK_TIMEOUT","message":"Service timed out"}}""",
            ""
        )
        assertEquals(AiErrorType.NETWORK_TIMEOUT, timeout.errorType)
        assertTrue(timeout.canRetry)

        val unsupported = SupabaseAndGatewayClient.mapHttpErrorToGatewayException(
            404,
            """{"error":{"message":"model_not_found"}}""",
            ""
        )
        assertEquals(AiErrorType.UNSUPPORTED_MODEL, unsupported.errorType)
        assertFalse(unsupported.canRetry)

        val invalidCreds = SupabaseAndGatewayClient.mapHttpErrorToGatewayException(
            401,
            """{"code":"INVALID_CREDENTIALS","message":"Invalid API key"}""",
            ""
        )
        assertEquals(AiErrorType.UNAUTHORIZED, invalidCreds.errorType)
        assertFalse(invalidCreds.canRetry)

        val contextError = SupabaseAndGatewayClient.mapHttpErrorToGatewayException(
            400,
            """{"code":"CONTEXT_LENGTH_EXCEEDED","message":"maximum context length exceeded"}""",
            ""
        )
        assertEquals(AiErrorType.CONTEXT_LENGTH_EXCEEDED, contextError.errorType)
        assertFalse(contextError.canRetry)
    }

    // ========================================================================
    // 4. Timezone-Aware Conversation Grouping Tests
    // ========================================================================

    @Test
    fun `groupByLocalTime buckets conversations into Today, Yesterday, Previous 7 Days, and Older`() {
        val tz = TimeZone.getTimeZone("America/Los_Angeles")
        val nowCal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.OCTOBER, 29, 14, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nowMs = nowCal.timeInMillis
        val oneHourMs = 3_600_000L
        val oneDayMs = 24 * oneHourMs

        val conversations = listOf(
            Conversation("c_today", "u1", "Today Chat", "", nowMs - oneHourMs, nowMs - oneHourMs),
            Conversation("c_yesterday", "u1", "Yesterday Chat", "", nowMs - oneDayMs, nowMs - oneDayMs),
            Conversation("c_prev7", "u1", "3 Days Ago Chat", "", nowMs - 3 * oneDayMs, nowMs - 3 * oneDayMs),
            Conversation("c_older", "u1", "Last Month Chat", "", nowMs - 20 * oneDayMs, nowMs - 20 * oneDayMs)
        )

        val grouped = ConversationGroupingUseCase.groupByLocalTime(conversations, nowMs, tz)
        assertEquals(1, grouped[ConversationTimeGroup.TODAY]?.size)
        assertEquals(1, grouped[ConversationTimeGroup.YESTERDAY]?.size)
        assertEquals(1, grouped[ConversationTimeGroup.PREVIOUS_7_DAYS]?.size)
        assertEquals(1, grouped[ConversationTimeGroup.OLDER]?.size)
    }

    // ========================================================================
    // 5. Markdown Streaming Parser & <think> Stripping Tests
    // ========================================================================

    @Test
    fun `MarkdownParser handles incomplete fenced code blocks and stripThinkingBlocks removes internal reasoning tags`() {
        val partialStream = "# Heading\nHere is code:\n```kotlin\nfun main() {\n  println(\"Hi\")"
        val blocks = MarkdownParser.parseBlocks(partialStream)
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Heading)
        assertTrue(blocks[1] is MarkdownBlock.Paragraph)
        val codeBlock = blocks[2] as MarkdownBlock.CodeBlock
        assertEquals("kotlin", codeBlock.language)
        assertTrue("Incomplete code fence should be flagged as streaming", codeBlock.isIncompleteStream)

        val rawOutput = "<think>\nLet me analyze the NullPointerException in MainActivity.kt...\n</think>\nUse the safe call operator `?.` to prevent the crash."
        assertEquals(
            "Use the safe call operator `?.` to prevent the crash.",
            SupabaseAndGatewayClient.stripThinkingBlocks(rawOutput)
        )

        val inProgressOutput = "<think>\nStill thinking about the bug..."
        assertEquals("", SupabaseAndGatewayClient.stripThinkingBlocks(inProgressOutput))
    }

    // ========================================================================
    // 6. Security: APK Secret Exposure Check
    // ========================================================================

    @Test
    fun `BuildConfig strictly excludes server-side secrets`() {
        val fieldNames = BuildConfig::class.java.declaredFields.map { it.name }.toSet()
        val forbiddenSecrets = listOf(
            "APINEX_API_KEY",
            "APINEX_BASE_URL",
            "BREVO_API_KEY",
            "BREVO_SENDER_EMAIL",
            "POLLINATIONS_API_KEY",
            "SUPABASE_SERVICE_ROLE_KEY"
        )
        for (secret in forbiddenSecrets) {
            assertFalse(
                "$secret must NEVER be compiled into Android BuildConfig",
                fieldNames.contains(secret)
            )
        }
    }

    @Test
    fun `parseRetryAfterSeconds and normalizePollinationsFallbackModel handle HTTP 503 and 429 cooldowns and model IDs`() {
        assertEquals(38, SupabaseAndGatewayClient.parseRetryAfterSeconds("38", ""))
        assertEquals(45, SupabaseAndGatewayClient.parseRetryAfterSeconds(null, """{"retry_after": 45}"""))
        assertEquals(42, SupabaseAndGatewayClient.parseRetryAfterSeconds(null, "Free limit reached: wait 42 seconds"))
        assertEquals(null, SupabaseAndGatewayClient.parseRetryAfterSeconds(null, "Generic server error"))

        assertEquals("sana", SupabaseAndGatewayClient.normalizePollinationsFallbackModel("sana"))
        assertEquals("sana", SupabaseAndGatewayClient.normalizePollinationsFallbackModel("flux"))
        assertEquals("sana", SupabaseAndGatewayClient.normalizePollinationsFallbackModel("community/scriptsnsenses-sys/flux-2-dev-free"))
    }

    // ========================================================================
    // 7. Multilingual Detection (Hindi, Hinglish, English, Spanish, French) & Smarter AI Branding Tests
    // ========================================================================

    @Test
    fun `AiProvider displayName uses Smarter AI branding and never exposes Apinex in user-facing labels`() {
        assertEquals("Smarter AI", AiProvider.APINEX.displayName)
        AiModelCatalog.INITIAL_DISCOVERY_MODELS.forEach { model ->
            assertFalse("Model displayName must not contain Apinex", model.displayName.contains("Apinex", ignoreCase = true))
            assertFalse("Model description must not contain Apinex", model.description.contains("Apinex", ignoreCase = true))
            assertEquals("Smarter AI", model.provider.displayName)
        }
    }

    @Test
    fun `language detector accurately classifies Hindi Devanagari, Hinglish Roman, English, Spanish, French, and preserves language across follow-up messages`() {
        // Exact required test cases
        assertEquals(
            ConversationLanguage.HINDI_DEVANAGARI,
            SupabaseAndGatewayClient.detectMessageLanguagePreference("मुझे भारत के बारे में बताओ।")
        )
        assertEquals(
            ConversationLanguage.HINGLISH_ROMAN,
            SupabaseAndGatewayClient.detectMessageLanguagePreference("Bhai mujhe ek website banani hai.")
        )
        assertEquals(
            ConversationLanguage.ENGLISH,
            SupabaseAndGatewayClient.detectMessageLanguagePreference("Explain artificial intelligence.")
        )
        assertEquals(
            ConversationLanguage.SPANISH,
            SupabaseAndGatewayClient.detectMessageLanguagePreference("Explícame cómo funciona la inteligencia artificial.")
        )
        assertEquals(
            ConversationLanguage.FRENCH,
            SupabaseAndGatewayClient.detectMessageLanguagePreference("Explique-moi cette technologie.")
        )

        // Additional explicit & natural cases
        assertEquals(
            ConversationLanguage.HINDI_DEVANAGARI,
            SupabaseAndGatewayClient.detectMessageLanguagePreference("Explain binary search in Hindi")
        )
        assertEquals(
            ConversationLanguage.HINDI_DEVANAGARI,
            SupabaseAndGatewayClient.detectMessageLanguagePreference("मुझे Kotlin Coroutines के बारे में हिंदी में समझाओ")
        )

        assertEquals(
            ConversationLanguage.HINGLISH_ROMAN,
            SupabaseAndGatewayClient.detectMessageLanguagePreference("Please explain recursion in Hinglish")
        )
        assertEquals(
            ConversationLanguage.HINGLISH_ROMAN,
            SupabaseAndGatewayClient.detectMessageLanguagePreference("Bhai mujhe samjhao ki Jetpack Compose kaise kaam karta hai?")
        )

        assertEquals(
            ConversationLanguage.ENGLISH,
            SupabaseAndGatewayClient.detectMessageLanguagePreference("What is the difference between a process and a thread in Linux?")
        )

        val hindiConversation = listOf(
            ChatMessage("m1", "c1", "u1", MessageRole.USER, "मुझे recursion हिंदी में समझाओ", ""),
            ChatMessage("m2", "c1", "u1", MessageRole.ASSISTANT, "रिकर्शन (Recursion) एक ऐसी प्रक्रिया है जिसमें एक फ़ंक्शन खुद को कॉल करता है।", ""),
            ChatMessage("m3", "c1", "u1", MessageRole.USER, "Give 1 code example", "")
        )
        assertEquals(
            ConversationLanguage.HINDI_DEVANAGARI,
            SupabaseAndGatewayClient.resolveConversationLanguage(hindiConversation)
        )

        val hindiPrompt = SupabaseAndGatewayClient.buildSystemPromptForConversation(
            "",
            hindiConversation
        )
        assertTrue(hindiPrompt.contains("Devanagari"))
        assertTrue(hindiPrompt.contains("Do NOT translate programming keywords, code blocks, API names"))

        val spanishPrompt = SupabaseAndGatewayClient.buildSystemPromptForConversation(
            "",
            listOf(ChatMessage("m1", "c1", "u1", MessageRole.USER, "Explícame cómo funciona la inteligencia artificial.", ""))
        )
        assertTrue(spanishPrompt.contains("Spanish"))

        val frenchPrompt = SupabaseAndGatewayClient.buildSystemPromptForConversation(
            "",
            listOf(ChatMessage("m1", "c1", "u1", MessageRole.USER, "Explique-moi cette technologie.", ""))
        )
        assertTrue(frenchPrompt.contains("French"))

        val switchedToEnglish = hindiConversation + ChatMessage(
            "m4", "c1", "u1", MessageRole.USER, "Now please reply in English", ""
        )
        assertEquals(
            ConversationLanguage.ENGLISH,
            SupabaseAndGatewayClient.resolveConversationLanguage(switchedToEnglish)
        )
    }

    // ========================================================================
    // 8. History Trimming Tests
    // ========================================================================

    @Test
    fun `trimConversationHistory bounds context window and excludes error messages`() {
        val rawHistory = (1..16).map { idx ->
            ChatMessage(
                id = "m-$idx",
                conversationId = "c1",
                userId = "u1",
                role = if (idx % 2 == 1) MessageRole.USER else MessageRole.ASSISTANT,
                content = "Message number $idx",
                modelId = ""
            )
        } + ChatMessage(
            id = "m-err",
            conversationId = "c1",
            userId = "u1",
            role = MessageRole.ASSISTANT,
            content = "",
            modelId = "",
            status = MessageStatus.ERROR
        )

        val trimmed = SupabaseAndGatewayClient.trimConversationHistory(rawHistory, maxMessages = 10)
        assertEquals("History must be bounded to maxMessages (10)", 10, trimmed.size)
        assertFalse("Error messages must be excluded from upstream history", trimmed.any { it.status == MessageStatus.ERROR })
        assertEquals("m-7", trimmed.first().id)
        assertEquals("m-16", trimmed.last().id)
    }

    // ========================================================================
    // 9. Smart Copy Block Detection & Markdown Parser Tests (PART 1)
    // ========================================================================

    @Test
    fun `smart copy block detector identifies code scripts commands and excludes normal prose or Instagram captions`() {
        // 1. Python script
        val pythonCode = """
            import os
            for idx, filename in enumerate(os.listdir(".")):
                os.rename(filename, f"file_{idx}.txt")
        """.trimIndent()
        assertTrue("Python script must be copyable", SmartCopyBlockDetector.isStructuredCopyableContent("python", pythonCode))
        assertEquals("python", SmartCopyBlockDetector.inferLanguageLabel("python", pythonCode))

        // 2. JavaScript / TypeScript code
        val jsCode = """
            const fetchUsers = async () => {
                const res = await fetch('/api/users');
                return res.json();
            };
        """.trimIndent()
        assertTrue("JavaScript code must be copyable", SmartCopyBlockDetector.isStructuredCopyableContent("javascript", jsCode))

        // 3. Kotlin code
        val kotlinCode = """
            fun greet(name: String): String {
                return "Hello, ${'$'}name!"
            }
        """.trimIndent()
        assertTrue("Kotlin code must be copyable", SmartCopyBlockDetector.isStructuredCopyableContent("kotlin", kotlinCode))

        // 4. Terminal / Bash / PowerShell commands
        val bashRaw = "$ supabase functions deploy ai-chat --no-verify-jwt"
        assertTrue("Bash command must be copyable", SmartCopyBlockDetector.isStructuredCopyableContent("bash", bashRaw))
        assertEquals(
            "Clean copyable content must strip shell prompt prefix and fences",
            "supabase functions deploy ai-chat --no-verify-jwt",
            SmartCopyBlockDetector.cleanCopyableContent("bash", bashRaw)
        )

        val psRaw = "PS> Get-Process | Where-Object { \$_.CPU -gt 10 }"
        assertTrue("PowerShell command must be copyable", SmartCopyBlockDetector.isStructuredCopyableContent("powershell", psRaw))
        assertEquals(
            "Get-Process | Where-Object { \$_.CPU -gt 10 }",
            SmartCopyBlockDetector.cleanCopyableContent("powershell", psRaw)
        )

        // 5. SQL, JSON, and YAML
        assertTrue(SmartCopyBlockDetector.isStructuredCopyableContent("sql", "SELECT id, email FROM users WHERE active = true;"))
        assertTrue(SmartCopyBlockDetector.isStructuredCopyableContent("json", "{\n  \"name\": \"smarter-ai\",\n  \"version\": \"1.0.0\"\n}"))
        assertTrue(SmartCopyBlockDetector.isStructuredCopyableContent("yaml", "services:\n  api:\n    image: postgres:16"))

        // 6. Normal paragraph response & Instagram caption must NOT be treated as copyable code blocks
        val normalExplanatory = "JavaScript is a high-level, dynamic programming language widely used for interactive web pages."
        assertFalse(
            "Normal explanatory text must NOT show a code copy button",
            SmartCopyBlockDetector.isStructuredCopyableContent("", normalExplanatory)
        )
        val normalBlocks = MarkdownParser.parseBlocks(normalExplanatory)
        assertTrue("Normal explanatory text must parse only as Paragraph", normalBlocks.all { it is MarkdownBlock.Paragraph })

        val instagramCaption = "Chasing golden hour vibes and endless horizons ✨🌅 #SunsetLovers #TravelGram #GoodVibesOnly"
        assertFalse(
            "Instagram caption must NOT show a code copy button",
            SmartCopyBlockDetector.isStructuredCopyableContent("", instagramCaption)
        )
        val captionBlocks = MarkdownParser.parseBlocks(instagramCaption)
        assertFalse("Instagram caption must not produce any CodeBlock", captionBlocks.any { it is MarkdownBlock.CodeBlock })
    }

    @Test
    fun `markdown parser supports multiple independent code blocks multi-file splitting and unfenced terminal commands`() {
        // Multiple code blocks in one response
        val multiBlockResponse = """
            Here is the Python script and the terminal command to run it:

            ```python
            def main():
                print("Renamer ready")
            ```

            Run it from your terminal:

            ```bash
            python3 renamer.py --dry-run
            ```
        """.trimIndent()
        val parsedMulti = MarkdownParser.parseBlocks(multiBlockResponse)
        val codeBlocks = parsedMulti.filterIsInstance<MarkdownBlock.CodeBlock>()
        assertEquals("Each code block must be parsed as its own independent block", 2, codeBlocks.size)
        assertEquals("python", codeBlocks[0].language)
        assertEquals("def main():\n    print(\"Renamer ready\")", codeBlocks[0].code)
        assertEquals("bash", codeBlocks[1].language)
        assertEquals("python3 renamer.py --dry-run", codeBlocks[1].code)

        // Multi-file script inside a single fence is split into independent CodeBlocks
        val multiFileResponse = """
            ```python
            # File: app.py
            from helper import run_task
            run_task()
            # File: helper.py
            def run_task():
                print("Task executed")
            ```
        """.trimIndent()
        val splitFiles = MarkdownParser.parseBlocks(multiFileResponse).filterIsInstance<MarkdownBlock.CodeBlock>()
        assertEquals("Multi-file script must split into separate copyable blocks", 2, splitFiles.size)
        assertTrue(splitFiles[0].code.contains("from helper import run_task"))
        assertTrue(splitFiles[1].code.contains("def run_task():"))

        // Unfenced terminal command detection
        val unfencedCommandResponse = """
            To deploy your Supabase function, run:
            supabase functions deploy ai-chat
        """.trimIndent()
        val unfencedBlocks = MarkdownParser.parseBlocks(unfencedCommandResponse)
        val commandBlock = unfencedBlocks.filterIsInstance<MarkdownBlock.CodeBlock>().firstOrNull()
        assertTrue("Unfenced terminal command should be detected as a CodeBlock", commandBlock != null)
        assertEquals("supabase functions deploy ai-chat", commandBlock!!.code)
    }

    // ========================================================================
    // 10. Automatic Chat Image Generation Intent Detection Tests (PART 2 & 5)
    // ========================================================================

    @Test
    fun `image generation intent detector accurately routes image creation vs normal chat vs image analysis`() {
        // Positive image generation requests (English, Hindi, Hinglish, custom resolution)
        val dragonRoute = ImageGenerationIntentDetector.detectRoute("Generate an image of a dragon.")
        assertTrue("Expected ImageGeneration route for dragon prompt", dragonRoute is ChatRequestRoute.ImageGeneration)

        val cityRoute = ImageGenerationIntentDetector.detectRoute("Generate an image of a futuristic city.")
        assertTrue(cityRoute is ChatRequestRoute.ImageGeneration)
        assertTrue((cityRoute as ChatRequestRoute.ImageGeneration).params.extractedPrompt.contains("futuristic city", ignoreCase = true))

        val diwaliRoute = ImageGenerationIntentDetector.detectRoute("Create a Diwali poster.")
        assertTrue(diwaliRoute is ChatRequestRoute.ImageGeneration)

        val hinglishCarRoute = ImageGenerationIntentDetector.detectRoute("Bhai ek realistic car image banao.")
        assertTrue("Expected ImageGeneration route for Hinglish car request", hinglishCarRoute is ChatRequestRoute.ImageGeneration)

        val hinglishSunsetRoute = ImageGenerationIntentDetector.detectRoute("Bhai ek sunset photo banao.")
        assertTrue("Expected ImageGeneration route for Hinglish sunset request", hinglishSunsetRoute is ChatRequestRoute.ImageGeneration)

        val landscapeRoute = ImageGenerationIntentDetector.detectRoute("Draw a beautiful landscape.")
        assertTrue(landscapeRoute is ChatRequestRoute.ImageGeneration)

        val productAdRoute = ImageGenerationIntentDetector.detectRoute("Generate a product advertisement.")
        assertTrue(productAdRoute is ChatRequestRoute.ImageGeneration)

        val customResRoute = ImageGenerationIntentDetector.detectRoute("Create an image in 1080x1350 resolution.")
        assertTrue(customResRoute is ChatRequestRoute.ImageGeneration)
        val customGen = (customResRoute as ChatRequestRoute.ImageGeneration).params
        assertEquals(1080, customGen.width)
        assertEquals(1350, customGen.height)
        assertEquals("1080×1350", customGen.aspectRatioLabel)

        // Negative / false-positive avoidance cases
        val explainHowWorks = ImageGenerationIntentDetector.detectRoute("Explain how image generation works.")
        assertTrue(
            "'Explain how image generation works.' must route to NormalChat",
            explainHowWorks is ChatRequestRoute.NormalChat
        )

        val analyzeTextOnly = ImageGenerationIntentDetector.detectRoute("Analyze this image.")
        assertFalse(
            "'Analyze this image.' must NEVER trigger image generation",
            analyzeTextOnly is ChatRequestRoute.ImageGeneration
        )

        val analyzeWithAttachment = ImageGenerationIntentDetector.detectRoute(
            "Analyze this image.",
            hasImageAttachments = true
        )
        assertTrue(
            "'Analyze this image.' with attachment must route to ImageAnalysis",
            analyzeWithAttachment is ChatRequestRoute.ImageAnalysis
        )

        val pythonScriptRoute = ImageGenerationIntentDetector.detectRoute("Write a Python script.")
        assertTrue(
            "'Write a Python script.' must route to NormalChat",
            pythonScriptRoute is ChatRequestRoute.NormalChat
        )

        val instagramCaptionRoute = ImageGenerationIntentDetector.detectRoute("Write an Instagram caption.")
        assertTrue(
            "'Write an Instagram caption.' must route to NormalChat",
            instagramCaptionRoute is ChatRequestRoute.NormalChat
        )

        val promptOnlyRoute = ImageGenerationIntentDetector.detectRoute("Write a Midjourney prompt for a cyberpunk samurai.")
        assertTrue(
            "Asking for an image prompt only must route to NormalChat",
            promptOnlyRoute is ChatRequestRoute.NormalChat
        )
    }

    // ========================================================================
    // 11. PART 4 — Long Conversation Intelligence & Context Compaction Tests
    // ========================================================================

    @Test
    fun `conversationContextManager compacts long 25-turn conversations while preserving user instructions, architecture decisions, and recent turns`() {
        ConversationContextManager.clearAllSummaries()
        val conversationId = "conv-long-test"
        val messages = mutableListOf<ChatMessage>()

        // Turn 1: User sets an explicit instruction and architectural decision
        messages.add(
            ChatMessage(
                id = "m-1",
                conversationId = conversationId,
                userId = "u1",
                role = MessageRole.USER,
                content = "Always prefer Kotlin Coroutines and MVVM architecture for our payment service. Keep responses concise.",
                modelId = "free/glm-5.3-flash"
            )
        )
        messages.add(
            ChatMessage(
                id = "m-2",
                conversationId = conversationId,
                userId = "u1",
                role = MessageRole.ASSISTANT,
                content = "Understood. We will use Kotlin Coroutines with StateFlow and MVVM architecture for the payment service.",
                modelId = "free/glm-5.3-flash"
            )
        )

        // Add 22 intermediate turns including an image attachment and a pending task
        for (i in 2..23) {
            val attachments = if (i == 5) {
                listOf(
                    MessageAttachment(
                        id = "att-diag",
                        uriString = "content://media/diagram.png",
                        mimeType = "image/png",
                        fileName = "checkout_architecture.png"
                    )
                )
            } else {
                emptyList()
            }
            messages.add(
                ChatMessage(
                    id = "m-u-$i",
                    conversationId = conversationId,
                    userId = "u1",
                    role = MessageRole.USER,
                    content = if (i == 10) "TODO: Next step we need to add webhook signature verification for Stripe."
                    else "Intermediate discussion turn $i about database schema and retry policies.",
                    modelId = "free/glm-5.3-flash",
                    attachments = attachments
                )
            )
            messages.add(
                ChatMessage(
                    id = "m-a-$i",
                    conversationId = conversationId,
                    userId = "u1",
                    role = MessageRole.ASSISTANT,
                    content = "Here is the detailed analysis for turn $i covering database indexing and idempotency keys.",
                    modelId = "free/glm-5.3-flash"
                )
            )
        }

        // Latest turn asking about earlier payment architecture decision
        messages.add(
            ChatMessage(
                id = "m-u-24",
                conversationId = conversationId,
                userId = "u1",
                role = MessageRole.USER,
                content = "Remind me what architecture we chose for the payment service and what our pending TODO is?",
                modelId = "free/glm-5.3-flash"
            )
        )

        val window = ConversationContextManager.buildOptimizedContext(
            conversationId = conversationId,
            fullHistory = messages
        )

        assertTrue("Expected compacted context window for 49 messages", window.wasCompacted)
        assertNotNull("Expected rolling summary block", window.summaryBlock)
        assertTrue(
            "Rolling summary must preserve user instructions/preferences",
            window.summaryBlock!!.contains("Kotlin Coroutines", ignoreCase = true)
        )
        assertTrue(
            "Rolling summary must preserve attachment reference",
            window.summaryBlock!!.contains("checkout_architecture.png", ignoreCase = true)
        )
        assertTrue(
            "Selected messages must be significantly smaller than full 49-message history",
            window.selectedMessages.size <= 16
        )
        assertEquals("Latest message must be preserved at end of context window", "m-u-24", window.selectedMessages.last().id)

        // Verify aggressive recovery mode further trims context if upstream overflows
        val aggressiveWindow = ConversationContextManager.buildOptimizedContext(
            conversationId = conversationId,
            fullHistory = messages,
            aggressiveRecovery = true
        )
        assertTrue(aggressiveWindow.selectedMessages.size <= 6)
    }

    // ========================================================================
    // 12. PART 5 — Image Attachment MIME Detection & Vision Capability Tests
    // ========================================================================

    @Test
    fun `imageAttachmentProcessor detects MIME types from magic bytes and validates vision model support`() {
        // PNG magic bytes
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D)
        assertEquals("image/png", ImageAttachmentProcessor.detectMimeType(pngBytes))

        // JPEG magic bytes
        val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01)
        assertEquals("image/jpeg", ImageAttachmentProcessor.detectMimeType(jpegBytes))

        // WebP magic bytes ("RIFF....WEBP")
        val webpBytes = byteArrayOf(
            0x52, 0x49, 0x46, 0x46,
            0x24, 0x00, 0x00, 0x00,
            0x57, 0x45, 0x42, 0x50
        )
        assertEquals("image/webp", ImageAttachmentProcessor.detectMimeType(webpBytes))

        // GIF magic bytes ("GIF89a")
        val gifBytes = "GIF89a123456".toByteArray()
        assertEquals("image/gif", ImageAttachmentProcessor.detectMimeType(gifBytes))

        // Vision model capability checks across all 12 Apinex models
        val glm53Check = ImageAttachmentProcessor.checkModelVisionSupport("free/glm-5.3")
        assertFalse("free/glm-5.3 does not support vision", glm53Check.isSupported)
        assertTrue(glm53Check.errorMessage!!.contains("GLM 5.3"))
        assertTrue(glm53Check.recommendedVisionModels.any { it.providerModelId == "free/gemini-3.8-flash" })

        val jevCheck = ImageAttachmentProcessor.checkModelVisionSupport("free/jev-1.13")
        assertFalse("free/jev-1.13 does not support vision", jevCheck.isSupported)

        val geminiCheck = ImageAttachmentProcessor.checkModelVisionSupport("free/gemini-3.8-flash")
        assertTrue("free/gemini-3.8-flash supports vision", geminiCheck.isSupported)

        val lunaCheck = ImageAttachmentProcessor.checkModelVisionSupport("free/gpt-6-luna")
        assertTrue("free/gpt-6-luna supports vision", lunaCheck.isSupported)
    }

    // ========================================================================
    // 13. PARTS 6–11 — Live Real-Time Data Intelligence, Activity Indicators,
    //     Chart Series & Currency Conversion Tests
    // ========================================================================

    @Test
    fun `liveDataIntelligenceService accurately detects live market intents, activity indicators, ambiguity, and parses live provider responses`() = runBlocking {
        val service = LiveDataIntelligenceService(
            httpExecutor = { request ->
                val url = request.url.toString()
                val bodyJson = when {
                    url.contains("api.frankfurter.app/latest") -> {
                        """{"amount":1.0,"base":"USD","date":"2026-11-04","rates":{"INR":84.50,"EUR":0.92,"GBP":0.78}}"""
                    }
                    url.contains("api.frankfurter.app/") && url.contains("..") -> {
                        """{"amount":1.0,"base":"USD","start_date":"2026-10-05","end_date":"2026-11-04","rates":{"2026-10-10":{"INR":84.10},"2026-10-20":{"INR":84.25},"2026-10-30":{"INR":84.40},"2026-11-04":{"INR":84.50}}}"""
                    }
                    url.contains("api.gold-api.com/price/") || url.contains("data-asg.goldprice.org") -> {
                        """{"price":2745.50,"updatedAt":"2026-11-04T12:00:00Z","ts":1762290000000,"items":[{"curr":"USD","xauPrice":2745.50,"xagPrice":32.40}]}"""
                    }
                    url.contains("api.binance.com/api/v3/ticker/24hr") -> {
                        """{"symbol":"BTCUSDT","lastPrice":"89420.50","priceChange":"1850.00","priceChangePercent":"2.11","highPrice":"90100.00","lowPrice":"87200.00","volume":"34120.5"}"""
                    }
                    url.contains("api.binance.com/api/v3/klines") -> {
                        """[[1762200000000,"87500.0","88200.0","87400.0","88100.0","1200"],[1762230000000,"88100.0","89000.0","88000.0","88850.0","1400"],[1762260000000,"88850.0","90100.0","88700.0","89420.50","1650"]]"""
                    }
                    else -> """{}"""
                }
                okhttp3.Response.Builder()
                    .request(request)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .addHeader("Content-Type", "application/json")
                    .body(bodyJson.toResponseBody("application/json".toMediaType()))
                    .build()
            }
        )

        // 1. Gold rate in India today -> Precious Metals + "Checking live gold rates..."
        val goldIntent = service.detectLiveIntent("What is the gold rate in India today?")
        assertTrue(goldIntent is LiveIntentDetectionResult.Detected)
        val goldFetch = goldIntent as LiveIntentDetectionResult.Detected
        assertEquals(LiveDataCategory.PRECIOUS_METALS, goldFetch.category)
        assertEquals("Checking live gold rates...", goldFetch.activityStatusMessage)

        val goldOutcome = service.executeLiveFetch(goldFetch)
        assertNotNull("Expected live gold market payload", goldOutcome.payload)
        assertNotNull("Expected precious metal breakdown (24K/22K/1g/10g/oz)", goldOutcome.payload!!.preciousMetalBreakdown)
        assertTrue("Prompt context must include international spot vs Indian retail note", goldOutcome.promptContextBlock.contains("Indian", ignoreCase = true))

        // 2. Silver price per gram -> "Fetching current silver prices..."
        val silverIntent = service.detectLiveIntent("Tell me the silver price per gram.")
        assertTrue(silverIntent is LiveIntentDetectionResult.Detected)
        assertEquals("Fetching current silver prices...", (silverIntent as LiveIntentDetectionResult.Detected).activityStatusMessage)

        // 3. Bitcoin price right now -> Crypto + chart points
        val btcIntent = service.detectLiveIntent("Show me Bitcoin price right now with a chart.")
        assertTrue(btcIntent is LiveIntentDetectionResult.Detected)
        val btcFetch = btcIntent as LiveIntentDetectionResult.Detected
        assertEquals(LiveDataCategory.CRYPTOCURRENCY, btcFetch.category)
        assertEquals("Checking Bitcoin market data...", btcFetch.activityStatusMessage)

        val btcOutcome = service.executeLiveFetch(btcFetch)
        assertNotNull(btcOutcome.payload)
        assertEquals(89420.50, btcOutcome.payload!!.currentValue!!, 0.01)
        assertTrue("Expected verified chart points from Binance klines", btcOutcome.payload!!.historicalPoints.size >= 3)

        // 4. USD to INR exchange rate -> Forex + 30D chart points
        val fxIntent = service.detectLiveIntent("What is the USD to INR rate today?")
        assertTrue(fxIntent is LiveIntentDetectionResult.Detected)
        val fxFetch = fxIntent as LiveIntentDetectionResult.Detected
        assertEquals(LiveDataCategory.FOREIGN_EXCHANGE, fxFetch.category)
        assertEquals("Fetching live USD/INR exchange rates...", fxFetch.activityStatusMessage)

        val fxOutcome = service.executeLiveFetch(fxFetch)
        assertNotNull(fxOutcome.payload)
        assertEquals(84.50, fxOutcome.payload!!.currentValue!!, 0.01)
        assertTrue("Expected historical forex chart series", fxOutcome.payload!!.historicalPoints.size >= 2)

        // 5. Currency conversion -> "Convert 500 USD into INR"
        val convIntent = service.detectLiveIntent("Convert 500 USD into INR.")
        assertTrue(convIntent is LiveIntentDetectionResult.Detected)
        val convFetch = convIntent as LiveIntentDetectionResult.Detected
        assertEquals(LiveDataCategory.CURRENCY_CONVERSION, convFetch.category)
        assertEquals("Converting currency using the latest available rate...", convFetch.activityStatusMessage)

        val convOutcome = service.executeLiveFetch(convFetch)
        assertNotNull(convOutcome.payload)
        assertNotNull(convOutcome.payload!!.currencyConversion)
        assertEquals(42250.0, convOutcome.payload!!.currencyConversion!!.convertedAmount, 0.01)

        // 6. Ambiguous prompt -> Clarification needed
        val ambiguous = service.detectLiveIntent("Check the price")
        assertTrue("Ambiguous 'Check the price' must request clarification", ambiguous is LiveIntentDetectionResult.ClarificationNeeded)

        // 7. Static historical / coding prompt -> NotLiveData
        val staticPrompt = service.detectLiveIntent("Write a Kotlin function to sort a list of integers.")
        assertTrue(staticPrompt is LiveIntentDetectionResult.None)
    }
}

