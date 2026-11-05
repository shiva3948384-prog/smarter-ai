package com.example.data.livedata

import com.example.domain.models.ChartDataPoint
import com.example.domain.models.CurrencyConversionDetail
import com.example.domain.models.LiveDataCategory
import com.example.domain.models.LiveDataFreshness
import com.example.domain.models.LiveMarketDataPayload
import com.example.domain.models.PreciousMetalBreakdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

sealed class LiveIntentDetectionResult {
    companion object {
        val NotLiveQuery: LiveIntentDetectionResult = None
    }

    data object None : LiveIntentDetectionResult()

    data class ClarificationNeeded(
        val category: LiveDataCategory,
        val clarificationPrompt: String
    ) : LiveIntentDetectionResult() {
        val clarificationMessage: String
            get() = clarificationPrompt
    }

    data class Detected(
        val category: LiveDataCategory,
        val subType: String,
        val primarySymbol: String,
        val secondarySymbol: String = "USD",
        val amount: Double? = null,
        val locationOrQuery: String? = null,
        val initialActivityIndicator: String
    ) : LiveIntentDetectionResult() {
        val activityStatusMessage: String
            get() = initialActivityIndicator
    }
}

typealias LiveDataIntentResult = LiveIntentDetectionResult

sealed class LiveDataFetchOutcome {
    abstract val payload: LiveMarketDataPayload?
    abstract val promptContextBlock: String

    data class Success(
        override val payload: LiveMarketDataPayload
    ) : LiveDataFetchOutcome() {
        override val promptContextBlock: String
            get() = payload.verifiedContextForAiPrompt
    }

    data class Failure(
        val userFriendlyReason: String,
        override val payload: LiveMarketDataPayload? = null
    ) : LiveDataFetchOutcome() {
        override val promptContextBlock: String
            get() = payload?.verifiedContextForAiPrompt ?: userFriendlyReason
    }
}

/**
 * Thread-safe registry mapping message IDs and client request IDs to verified [LiveMarketDataPayload]
 * objects so [ChatScreen] can render interactive market charts and conversion cards alongside
 * the AI's streamed explanation.
 */
object LiveMarketDataRegistry {
    private val byMessageId = ConcurrentHashMap<String, LiveMarketDataPayload>()
    private val byClientRequestId = ConcurrentHashMap<String, LiveMarketDataPayload>()

    fun putForRequest(clientRequestId: String, payload: LiveMarketDataPayload) {
        if (clientRequestId.isNotBlank()) {
            byClientRequestId[clientRequestId] = payload
        }
    }

    fun bindToMessageId(messageId: String, clientRequestId: String?, payload: LiveMarketDataPayload? = null) {
        val resolved = payload ?: clientRequestId?.let { byClientRequestId[it] } ?: return
        if (messageId.isNotBlank()) {
            byMessageId[messageId] = resolved
        }
    }

    fun put(messageId: String, payload: LiveMarketDataPayload) {
        if (messageId.isNotBlank()) {
            byMessageId[messageId] = payload
            byClientRequestId[messageId] = payload
        }
    }

    operator fun get(messageId: String): LiveMarketDataPayload? {
        return getForMessage(messageId, messageId)
    }

    fun getForMessage(messageId: String, clientRequestId: String? = null): LiveMarketDataPayload? {
        if (messageId.isNotBlank()) {
            byMessageId[messageId]?.let { return it }
        }
        if (!clientRequestId.isNullOrBlank()) {
            val baseReqId = clientRequestId.removeSuffix("_assistant")
            byClientRequestId[clientRequestId]?.let { return it }
            byClientRequestId[baseReqId]?.let { return it }
        }
        return null
    }

    fun clearAll() {
        byMessageId.clear()
        byClientRequestId.clear()
    }
}

/**
 * Modular Live Real-Time Data Intelligence Pipeline (PARTS 6, 7, 8, 9, 10 & 11):
 *
 * User Request
 *   → Live Intent Detection
 *   → Data Category Identification
 *   → Provider Selection
 *   → External API Request
 *   → Data Validation
 *   → Normalization
 *   → Timestamp and Source Verification
 *   → Apinex AI Reasoning
 *   → Final Answer and Visualization
 */
open class LiveDataIntelligenceService(
    private val customHttpExecutor: ((Request) -> okhttp3.Response)? = null,
    httpExecutor: ((Request) -> okhttp3.Response)? = null,
    private val currentTimeProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val effectiveHttpExecutor: ((Request) -> okhttp3.Response)? = customHttpExecutor ?: httpExecutor

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private data class CacheEntry(
        val payload: LiveMarketDataPayload,
        val expiresAtEpochMs: Long
    )

    private val responseCache = ConcurrentHashMap<String, CacheEntry>()
    private val lastProviderCallAtMs = ConcurrentHashMap<String, Long>()

    protected open fun executeHttpRequest(request: Request): okhttp3.Response {
        return effectiveHttpExecutor?.invoke(request) ?: httpClient.newCall(request).execute()
    }

    // ========================================================================
    // 1. Live Intent Detection (PART 8 & PART 9)
    // ========================================================================

    fun detectLiveIntent(userMessage: String): LiveIntentDetectionResult {
        val trimmed = userMessage.trim()
        if (trimmed.isEmpty()) return LiveIntentDetectionResult.None
        val lower = trimmed.lowercase(Locale.ROOT)

        // Do not trigger live lookups for code generation or purely conceptual/educational questions
        val isConceptualQuestion = (
            lower.startsWith("what is cryptocurrency") ||
                lower.startsWith("what is bitcoin and how") ||
                lower.startsWith("explain how ") ||
                lower.startsWith("explain what ") ||
                lower.startsWith("how does currency conversion work") ||
                lower.startsWith("how does the stock market work") ||
                lower.startsWith("write a ") ||
                lower.startsWith("create a python") ||
                lower.startsWith("write code") ||
                lower.startsWith("implement ")
            ) && !containsLiveTimeSignal(lower)

        if (isConceptualQuestion) {
            return LiveIntentDetectionResult.None
        }

        // D. Currency Conversion: e.g. "Convert 500 USD into INR", "100 USD to EUR today"
        val conversionRegex = Regex(
            """\b(?:convert|change|exchange)?\s*(\d+(?:[.,]\d+)?)\s*([a-zA-Z]{3})\s+(?:into|to|in)\s+([a-zA-Z]{3})\b""",
            RegexOption.IGNORE_CASE
        )
        val convMatch = conversionRegex.find(trimmed)
        if (convMatch != null) {
            val rawAmt = convMatch.groupValues[1].replace(",", "").toDoubleOrNull()
            val fromCurr = convMatch.groupValues[2].uppercase(Locale.ROOT)
            val toCurr = convMatch.groupValues[3].uppercase(Locale.ROOT)
            if (rawAmt != null && rawAmt > 0 && fromCurr in SUPPORTED_FIAT_CODES && toCurr in SUPPORTED_FIAT_CODES) {
                return LiveIntentDetectionResult.Detected(
                    category = LiveDataCategory.CURRENCY_CONVERSION,
                    subType = "CONVERT_${fromCurr}_$toCurr",
                    primarySymbol = fromCurr,
                    secondarySymbol = toCurr,
                    amount = rawAmt,
                    initialActivityIndicator = "Converting currency using the latest available rate..."
                )
            }
        }

        // C. Foreign Exchange (FX Pairs): e.g. "USD/INR", "EUR/INR", "GBP/INR", "USD/EUR", "today's USD to INR rate"
        val fxSlashRegex = Regex("""\b([a-zA-Z]{3})\s*/\s*([a-zA-Z]{3})\b""")
        val fxWordsRegex = Regex("""\b([a-zA-Z]{3})\s+(?:to|in|vs)\s+([a-zA-Z]{3})\b""", RegexOption.IGNORE_CASE)
        val fxMatch = fxSlashRegex.find(trimmed) ?: fxWordsRegex.find(trimmed)
        if (fxMatch != null) {
            val base = fxMatch.groupValues[1].uppercase(Locale.ROOT)
            val target = fxMatch.groupValues[2].uppercase(Locale.ROOT)
            if (base in SUPPORTED_FIAT_CODES && target in SUPPORTED_FIAT_CODES && base != target) {
                if (containsLiveTimeSignal(lower) || lower.contains("rate") || fxSlashRegex.containsMatchIn(trimmed)) {
                    return LiveIntentDetectionResult.Detected(
                        category = LiveDataCategory.FOREIGN_EXCHANGE,
                        subType = "${base}_$target",
                        primarySymbol = base,
                        secondarySymbol = target,
                        initialActivityIndicator = "Fetching live $base/$target exchange rates..."
                    )
                }
            }
        }

        // A. Precious Metals: Gold & Silver
        val mentionsGold = Regex("""\b(gold|xau|24k|22k|sona)\b""").containsMatchIn(lower)
        val mentionsSilver = Regex("""\b(silver|xag|chandi)\b""").containsMatchIn(lower)
        val mentionsPlatinum = Regex("""\b(platinum|xpt)\b""").containsMatchIn(lower)
        if ((mentionsGold || mentionsSilver || mentionsPlatinum) &&
            (containsLiveTimeSignal(lower) || lower.contains("rate") || lower.contains("price") || lower.contains("per gram") || lower.contains("10g"))
        ) {
            val requestedCurrency = detectRequestedFiatCurrency(lower, defaultCurrency = "INR")
            return when {
                mentionsSilver && !mentionsGold -> LiveIntentDetectionResult.Detected(
                    category = LiveDataCategory.PRECIOUS_METALS,
                    subType = "SILVER",
                    primarySymbol = "XAG",
                    secondarySymbol = requestedCurrency,
                    initialActivityIndicator = "Fetching current silver prices..."
                )
                mentionsPlatinum && !mentionsGold -> LiveIntentDetectionResult.Detected(
                    category = LiveDataCategory.PRECIOUS_METALS,
                    subType = "PLATINUM",
                    primarySymbol = "XPT",
                    secondarySymbol = requestedCurrency,
                    initialActivityIndicator = "Checking live precious metal rates..."
                )
                else -> LiveIntentDetectionResult.Detected(
                    category = LiveDataCategory.PRECIOUS_METALS,
                    subType = "GOLD",
                    primarySymbol = "XAU",
                    secondarySymbol = requestedCurrency,
                    initialActivityIndicator = "Checking live gold rates..."
                )
            }
        }

        // B. Cryptocurrencies: Bitcoin, Ethereum, Solana, BNB, XRP, Dogecoin, Cardano, etc.
        val detectedCrypto = detectCryptoSymbol(lower)
        if (detectedCrypto != null &&
            (containsLiveTimeSignal(lower) || lower.contains("price") || lower.contains("market") || lower.contains("chart") || lower.contains("rate") || lower.contains("worth"))
        ) {
            val (symbol, displayName) = detectedCrypto
            val quoteCurrency = detectRequestedFiatCurrency(lower, defaultCurrency = "USD")
            return LiveIntentDetectionResult.Detected(
                category = LiveDataCategory.CRYPTOCURRENCY,
                subType = symbol,
                primarySymbol = symbol,
                secondarySymbol = quoteCurrency,
                initialActivityIndicator = "Checking $displayName market data..."
            )
        }

        // E. Stocks & Indices
        if (lower.contains("stock price") || lower.contains("share price") || lower.contains("market index") ||
            (lower.contains("stock") && containsLiveTimeSignal(lower))
        ) {
            val detectedTicker = detectStockTicker(trimmed, lower)
            if (detectedTicker == null) {
                return LiveIntentDetectionResult.ClarificationNeeded(
                    category = LiveDataCategory.STOCKS_AND_INDICES,
                    clarificationPrompt = "Which stock or market index would you like me to check? Please specify a company name or ticker symbol (for example: **AAPL** for Apple, **NVDA** for NVIDIA, **MSFT** for Microsoft, **TSLA** for Tesla, or **S&P 500**)."
                )
            }
            return LiveIntentDetectionResult.Detected(
                category = LiveDataCategory.STOCKS_AND_INDICES,
                subType = detectedTicker.first,
                primarySymbol = detectedTicker.first,
                secondarySymbol = "USD",
                locationOrQuery = detectedTicker.second,
                initialActivityIndicator = "Fetching current stock prices..."
            )
        }

        // F. Commodities (Crude Oil, Brent Oil, Natural Gas)
        if ((lower.contains("crude oil") || lower.contains("brent oil") || lower.contains("wti oil") || lower.contains("natural gas")) &&
            (containsLiveTimeSignal(lower) || lower.contains("price") || lower.contains("rate"))
        ) {
            val isGas = lower.contains("natural gas")
            return LiveIntentDetectionResult.Detected(
                category = LiveDataCategory.COMMODITIES,
                subType = if (isGas) "NATURAL_GAS" else "CRUDE_OIL",
                primarySymbol = if (isGas) "NG=F" else "CL=F",
                secondarySymbol = "USD",
                locationOrQuery = if (isGas) "Natural Gas (Henry Hub)" else "WTI Crude Oil",
                initialActivityIndicator = "Searching for the latest information..."
            )
        }

        // G. Current Information: Weather
        val weatherMatch = Regex("""\bweather\s+(?:in|for|at)\s+([a-zA-Z\s,]{2,35})""", RegexOption.IGNORE_CASE).find(trimmed)
        if (weatherMatch != null || (lower.contains("weather") && containsLiveTimeSignal(lower))) {
            val city = weatherMatch?.groupValues?.getOrNull(1)?.trim()
                ?.replace(Regex("""\b(today|right now|currently|now|live)\b""", RegexOption.IGNORE_CASE), "")
                ?.trim()
                ?.takeIf { it.length >= 2 }
            if (city != null) {
                return LiveIntentDetectionResult.Detected(
                    category = LiveDataCategory.CURRENT_INFORMATION,
                    subType = "WEATHER",
                    primarySymbol = city,
                    secondarySymbol = "CELSIUS",
                    locationOrQuery = city,
                    initialActivityIndicator = "Searching for the latest information..."
                )
            }
        }

        return LiveIntentDetectionResult.None
    }

    // ========================================================================
    // 2. Live Data Retrieval, Validation & Normalization (PARTS 6, 7, 8, 10, 11)
    // ========================================================================

    open suspend fun fetchVerifiedLiveData(
        intent: LiveIntentDetectionResult.Detected,
        forceRefresh: Boolean = false,
        onActivityStageChanged: ((String) -> Unit)? = null
    ): LiveDataFetchOutcome = withContext(Dispatchers.IO) {
        onActivityStageChanged?.invoke(intent.initialActivityIndicator)

        val cacheKey = "${intent.category}_${intent.primarySymbol}_${intent.secondarySymbol}_${intent.amount ?: ""}"
        val nowMs = currentTimeProvider()

        if (!forceRefresh) {
            responseCache[cacheKey]?.let { entry ->
                if (nowMs < entry.expiresAtEpochMs) {
                    val cachedPayload = entry.payload.copy(
                        freshness = if (entry.payload.freshness == LiveDataFreshness.LIVE) {
                            LiveDataFreshness.CACHED
                        } else {
                            entry.payload.freshness
                        }
                    )
                    return@withContext LiveDataFetchOutcome.Success(cachedPayload)
                }
            }
        }

        // Respect provider rate limits (minimum 1.5s spacing per cache key)
        val lastCall = lastProviderCallAtMs[cacheKey] ?: 0L
        if (!forceRefresh && nowMs - lastCall < 1_500L) {
            responseCache[cacheKey]?.let { return@withContext LiveDataFetchOutcome.Success(it.payload) }
        }
        lastProviderCallAtMs[cacheKey] = nowMs

        val resultPayload = when (intent.category) {
            LiveDataCategory.PRECIOUS_METALS -> fetchPreciousMetalsData(intent, onActivityStageChanged)
            LiveDataCategory.CRYPTOCURRENCY -> fetchCryptocurrencyData(intent, onActivityStageChanged)
            LiveDataCategory.FOREIGN_EXCHANGE -> fetchForeignExchangeData(intent, onActivityStageChanged)
            LiveDataCategory.CURRENCY_CONVERSION -> fetchCurrencyConversionData(intent, onActivityStageChanged)
            LiveDataCategory.STOCKS_AND_INDICES -> fetchStockOrIndexData(intent, onActivityStageChanged)
            LiveDataCategory.COMMODITIES -> fetchCommodityData(intent, onActivityStageChanged)
            LiveDataCategory.CURRENT_INFORMATION -> fetchWeatherOrCurrentInfo(intent, onActivityStageChanged)
        }

        if (resultPayload.freshness != LiveDataFreshness.UNAVAILABLE && resultPayload.currentValue != null) {
            val ttlMs = when (intent.category) {
                LiveDataCategory.CRYPTOCURRENCY -> 15_000L // 15s freshness for crypto
                LiveDataCategory.PRECIOUS_METALS -> 30_000L // 30s freshness for metals
                LiveDataCategory.FOREIGN_EXCHANGE, LiveDataCategory.CURRENCY_CONVERSION -> 60_000L // 60s for FX
                else -> 45_000L
            }
            responseCache[cacheKey] = CacheEntry(
                payload = resultPayload,
                expiresAtEpochMs = currentTimeProvider() + ttlMs
            )
            LiveDataFetchOutcome.Success(resultPayload)
        } else {
            LiveDataFetchOutcome.Failure(
                userFriendlyReason = resultPayload.errorOrLimitationNotice
                    ?: "Live market data provider is temporarily unavailable.",
                payload = resultPayload
            )
        }
    }

    suspend fun executeLiveFetch(intent: LiveIntentDetectionResult.Detected): LiveDataFetchOutcome {
        return fetchVerifiedLiveData(intent = intent, forceRefresh = true)
    }

    // ------------------------------------------------------------------------
    // A. Precious Metals Provider (Gold XAU, Silver XAG, Platinum XPT)
    // ------------------------------------------------------------------------

    private fun fetchPreciousMetalsData(
        intent: LiveIntentDetectionResult.Detected,
        onStageChanged: ((String) -> Unit)?
    ): LiveMarketDataPayload {
        val symbol = intent.primarySymbol.uppercase(Locale.ROOT) // XAU, XAG, XPT
        val metalName = when (symbol) {
            "XAG" -> "Silver"
            "XPT" -> "Platinum"
            else -> "Gold"
        }
        val targetCurrency = intent.secondarySymbol.uppercase(Locale.ROOT).ifBlank { "INR" }

        return try {
            val spotUrl = "https://api.gold-api.com/price/$symbol"
            val req = Request.Builder()
                .url(spotUrl)
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "SmarterAI-LiveData/1.0")
                .get()
                .build()

            var spotPriceUsdPerTroyOz: Double? = null
            var providerUpdatedEpochMs = currentTimeProvider()

            executeHttpRequest(req).use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val directPrice = json.optDouble("price", Double.NaN)
                    if (!directPrice.isNaN() && directPrice > 0.0) {
                        spotPriceUsdPerTroyOz = directPrice
                    } else {
                        val firstItem = json.optJSONArray("items")?.optJSONObject(0)
                        val itemPrice = if (symbol == "XAG") {
                            firstItem?.optDouble("xagPrice", Double.NaN) ?: Double.NaN
                        } else {
                            firstItem?.optDouble("xauPrice", Double.NaN) ?: Double.NaN
                        }
                        if (!itemPrice.isNaN() && itemPrice > 0.0) {
                            spotPriceUsdPerTroyOz = itemPrice
                        }
                    }
                    val updatedStr = json.optString("updatedAt", "")
                    parseIsoTimestampEpochMs(updatedStr)?.let { providerUpdatedEpochMs = it }
                    val tsMs = json.optLong("ts", 0L)
                    if (tsMs > 0L) providerUpdatedEpochMs = tsMs
                }
            }

            if (spotPriceUsdPerTroyOz == null) {
                return buildUnavailablePayload(
                    category = LiveDataCategory.PRECIOUS_METALS,
                    title = "Live $metalName Rate ($symbol)",
                    symbolOrPair = "$symbol/$targetCurrency",
                    providerName = "Gold-API Spot Feed",
                    reason = "Live $metalName spot provider is temporarily unreachable or returned an invalid response. No fabricated price is shown."
                )
            }

            val spotUsd = spotPriceUsdPerTroyOz!!
            var usdToLocalRate: Double? = null
            var fxProviderLabel = ""
            if (targetCurrency != "USD") {
                val fxPair = fetchLatestFxRateInternal("USD", targetCurrency)
                if (fxPair != null && fxPair.first > 0.0) {
                    usdToLocalRate = fxPair.first
                    fxProviderLabel = " + ${fxPair.second} (USD/$targetCurrency)"
                }
            }

            val troyOzGrams = 31.1034768
            val price24KPerGramUsd = spotUsd / troyOzGrams
            val price22KPerGramUsd = price24KPerGramUsd * (22.0 / 24.0)

            val price24KPerGramLocal = usdToLocalRate?.let { price24KPerGramUsd * it }
            val price24KPer10GramsLocal = price24KPerGramLocal?.let { it * 10.0 }
            val price22KPerGramLocal = usdToLocalRate?.let { price22KPerGramUsd * it }
            val price22KPer10GramsLocal = price22KPerGramLocal?.let { it * 10.0 }

            val breakdown = PreciousMetalBreakdown(
                metalName = metalName,
                spotPricePerTroyOunceUsd = spotUsd,
                usdToLocalRate = usdToLocalRate,
                localCurrencyCode = targetCurrency,
                price24KPerGramLocal = price24KPerGramLocal,
                price24KPer10GramsLocal = price24KPer10GramsLocal,
                price22KPerGramLocal = price22KPerGramLocal,
                price22KPer10GramsLocal = price22KPer10GramsLocal,
                price24KPerGramUsd = price24KPerGramUsd,
                price22KPerGramUsd = price22KPerGramUsd,
                marketTypeNotice = "International Spot Bullion Rate ($symbol/USD converted via live USD/$targetCurrency mid-market FX). Note: Local Indian retail jeweler prices additionally include customs duty, 3% GST, and local bullion/making charges."
            )

            val primaryFormatted = if (targetCurrency != "USD" && price24KPer10GramsLocal != null && symbol == "XAU") {
                "${formatCurrencyValue(price24KPer10GramsLocal, targetCurrency)} / 10g (24K Spot)"
            } else if (targetCurrency != "USD" && price24KPerGramLocal != null) {
                "${formatCurrencyValue(price24KPerGramLocal, targetCurrency)} / g"
            } else {
                "${formatCurrencyValue(spotUsd, "USD")} / troy oz"
            }

            val tsFormatted = formatTimestampUtcAndLocal(providerUpdatedEpochMs)
            val providerAttribution = "Gold-API International Spot ($symbol/USD)$fxProviderLabel"

            val aiContext = buildString {
                appendLine("[VERIFIED LIVE PRECIOUS METALS DATA — DO NOT INVENT NUMBERS]")
                appendLine("Asset: $metalName ($symbol)")
                appendLine("Source / Provider: $providerAttribution")
                appendLine("Provider Timestamp: $tsFormatted")
                appendLine("Data Status: LIVE INTERNATIONAL SPOT BULLION")
                appendLine("International Spot ($symbol/USD): ${formatCurrencyValue(spotUsd, "USD")} per troy ounce (31.1035 g)")
                appendLine("International 24K (99.9%) Spot per 1 gram (USD): ${formatCurrencyValue(price24KPerGramUsd, "USD")}")
                if (symbol == "XAU") {
                    appendLine("International 22K (91.6%) Spot per 1 gram (USD): ${formatCurrencyValue(price22KPerGramUsd, "USD")}")
                }
                if (usdToLocalRate != null && price24KPerGramLocal != null && price24KPer10GramsLocal != null) {
                    appendLine("Live USD/$targetCurrency Exchange Rate Used: ${formatDecimal(usdToLocalRate, 4)}")
                    appendLine("Indicative $targetCurrency 24K (99.9%) Spot Equivalent:")
                    appendLine("  • Per 1 gram (24K): ${formatCurrencyValue(price24KPerGramLocal, targetCurrency)}")
                    appendLine("  • Per 10 grams (24K): ${formatCurrencyValue(price24KPer10GramsLocal, targetCurrency)}")
                    if (symbol == "XAU" && price22KPerGramLocal != null && price22KPer10GramsLocal != null) {
                        appendLine("Indicative $targetCurrency 22K (91.6%) Spot Equivalent:")
                        appendLine("  • Per 1 gram (22K): ${formatCurrencyValue(price22KPerGramLocal, targetCurrency)}")
                        appendLine("  • Per 10 grams (22K): ${formatCurrencyValue(price22KPer10GramsLocal, targetCurrency)}")
                    }
                }
                appendLine("Important Distinction: Clearly distinguish this international spot bullion rate from local Indian retail jewelry store rates (which include import duty, 3% GST, and local making charges).")
            }.trim()

            LiveMarketDataPayload(
                id = UUID.randomUUID().toString(),
                category = LiveDataCategory.PRECIOUS_METALS,
                title = "Live $metalName Rate (24K & 22K)",
                symbolOrPair = "$symbol/$targetCurrency",
                currentValue = price24KPer10GramsLocal ?: spotUsd,
                formattedPrimaryValue = primaryFormatted,
                currencyOrUnit = if (price24KPer10GramsLocal != null) "$targetCurrency (per 1g & 10g)" else "USD / troy oz",
                providerName = providerAttribution,
                sourceAttributionUrl = "https://api.gold-api.com",
                providerTimestampEpochMs = providerUpdatedEpochMs,
                formattedTimestamp = tsFormatted,
                freshness = LiveDataFreshness.LIVE,
                timeframeLabel = "Spot",
                historicalPoints = emptyList(),
                historicalUnavailableReason = "Live spot price verified. Historical time-series chart points are not provided by the free spot endpoint and are never fabricated.",
                preciousMetalBreakdown = breakdown,
                verifiedContextForAiPrompt = aiContext
            )
        } catch (e: Exception) {
            buildUnavailablePayload(
                category = LiveDataCategory.PRECIOUS_METALS,
                title = "Live $metalName Rate ($symbol)",
                symbolOrPair = "$symbol/$targetCurrency",
                providerName = "Gold-API Spot Feed",
                reason = "Unable to retrieve live $metalName prices right now (${e.message ?: "network error"}). Please try again shortly."
            )
        }
    }

    // ------------------------------------------------------------------------
    // B. Cryptocurrency Provider (Binance 24hr Ticker + Hourly Klines Chart)
    // ------------------------------------------------------------------------

    private fun fetchCryptocurrencyData(
        intent: LiveIntentDetectionResult.Detected,
        onStageChanged: ((String) -> Unit)?
    ): LiveMarketDataPayload {
        val symbol = intent.primarySymbol.uppercase(Locale.ROOT) // BTC, ETH, SOL, etc.
        val displayCurrency = intent.secondarySymbol.uppercase(Locale.ROOT).ifBlank { "USD" }
        val binancePair = "${symbol}USDT"

        return try {
            val tickerUrl = "https://api.binance.com/api/v3/ticker/24hr?symbol=$binancePair"
            val tickerReq = Request.Builder()
                .url(tickerUrl)
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "SmarterAI-LiveData/1.0")
                .get()
                .build()

            var lastPriceUsd: Double? = null
            var change24hPercent: Double? = null
            var high24hUsd: Double? = null
            var low24hUsd: Double? = null
            var volumeBase: Double? = null
            var quoteVolumeUsd: Double? = null
            var closeTimeMs = currentTimeProvider()

            executeHttpRequest(tickerReq).use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string().orEmpty())
                    val p = json.optString("lastPrice", "").toDoubleOrNull()
                    if (p != null && p > 0.0) {
                        lastPriceUsd = p
                        change24hPercent = json.optString("priceChangePercent", "").toDoubleOrNull()
                        high24hUsd = json.optString("highPrice", "").toDoubleOrNull()
                        low24hUsd = json.optString("lowPrice", "").toDoubleOrNull()
                        volumeBase = json.optString("volume", "").toDoubleOrNull()
                        quoteVolumeUsd = json.optString("quoteVolume", "").toDoubleOrNull()
                        val ct = json.optLong("closeTime", 0L)
                        if (ct > 0L) closeTimeMs = ct
                    }
                }
            }

            if (lastPriceUsd == null) {
                return buildUnavailablePayload(
                    category = LiveDataCategory.CRYPTOCURRENCY,
                    title = "$symbol Live Market Data",
                    symbolOrPair = "$symbol/$displayCurrency",
                    providerName = "Binance Spot Market API",
                    reason = "Could not fetch live market data for $symbol from Binance. Please verify your connection and try again."
                )
            }

            onStageChanged?.invoke("Preparing your live market chart...")

            // Fetch 24 hourly klines from Binance for the historical time-series chart
            val klinesUrl = "https://api.binance.com/api/v3/klines?symbol=$binancePair&interval=1h&limit=24"
            val klinesReq = Request.Builder()
                .url(klinesUrl)
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "SmarterAI-LiveData/1.0")
                .get()
                .build()

            val chartPointsUsd = mutableListOf<ChartDataPoint>()
            val hourFmt = SimpleDateFormat("HH:mm", Locale.US)

            runCatching {
                executeHttpRequest(klinesReq).use { resp ->
                    if (resp.isSuccessful) {
                        val arr = JSONArray(resp.body?.string().orEmpty())
                        for (i in 0 until arr.length()) {
                            val candle = arr.optJSONArray(i) ?: continue
                            val openTime = candle.optLong(0, 0L)
                            val closePrice = candle.optString(4, "").toDoubleOrNull() ?: continue
                            val closeTime = candle.optLong(6, openTime)
                            if (closePrice > 0.0) {
                                chartPointsUsd.add(
                                    ChartDataPoint(
                                        timestampEpochMs = closeTime,
                                        label = hourFmt.format(Date(closeTime)),
                                        value = closePrice
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Optional currency conversion if user requested INR/EUR/GBP
            var fxMultiplier = 1.0
            var resolvedCurrency = "USD"
            if (displayCurrency != "USD" && displayCurrency != "USDT") {
                val fxPair = fetchLatestFxRateInternal("USD", displayCurrency)
                if (fxPair != null && fxPair.first > 0.0) {
                    fxMultiplier = fxPair.first
                    resolvedCurrency = displayCurrency
                }
            }

            val displayPrice = lastPriceUsd!! * fxMultiplier
            val displayHigh = high24hUsd?.let { it * fxMultiplier }
            val displayLow = low24hUsd?.let { it * fxMultiplier }
            val displayQuoteVol = quoteVolumeUsd?.let { it * fxMultiplier }
            val scaledChartPoints = chartPointsUsd.map { pt ->
                pt.copy(value = pt.value * fxMultiplier)
            }

            val tsFormatted = formatTimestampUtcAndLocal(closeTimeMs)
            val providerName = if (resolvedCurrency == "USD") {
                "Binance Spot API ($binancePair)"
            } else {
                "Binance Spot API ($binancePair) + ECB FX (USD/$resolvedCurrency)"
            }

            val aiContext = buildString {
                appendLine("[VERIFIED LIVE CRYPTOCURRENCY MARKET DATA — DO NOT INVENT NUMBERS]")
                appendLine("Asset: $symbol ($symbol/$resolvedCurrency)")
                appendLine("Provider: $providerName")
                appendLine("Timestamp: $tsFormatted")
                appendLine("Data Status: LIVE REAL-TIME SPOT")
                appendLine("Current Price: ${formatCurrencyValue(displayPrice, resolvedCurrency)} (USD Spot: ${formatCurrencyValue(lastPriceUsd!!, "USD")})")
                change24hPercent?.let {
                    val sign = if (it >= 0) "+" else ""
                    appendLine("24-Hour Change: $sign${formatDecimal(it, 2)}%")
                }
                displayHigh?.let { appendLine("24-Hour High: ${formatCurrencyValue(it, resolvedCurrency)}") }
                displayLow?.let { appendLine("24-Hour Low: ${formatCurrencyValue(it, resolvedCurrency)}") }
                volumeBase?.let { appendLine("24-Hour Trading Volume ($symbol): ${formatDecimal(it, 2)} $symbol") }
                displayQuoteVol?.let { appendLine("24-Hour Quote Volume ($resolvedCurrency): ${formatCurrencyValue(it, resolvedCurrency)}") }
                if (scaledChartPoints.size >= 2) {
                    appendLine("Historical Chart Data: 24-hour hourly series (${scaledChartPoints.size} verified candles) rendered in the interactive chart card.")
                }
            }.trim()

            LiveMarketDataPayload(
                id = UUID.randomUUID().toString(),
                category = LiveDataCategory.CRYPTOCURRENCY,
                title = "$symbol Live Market Price",
                symbolOrPair = "$symbol/$resolvedCurrency",
                currentValue = displayPrice,
                formattedPrimaryValue = formatCurrencyValue(displayPrice, resolvedCurrency),
                currencyOrUnit = resolvedCurrency,
                change24hPercent = change24hPercent,
                high24h = displayHigh,
                low24h = displayLow,
                volume24h = displayQuoteVol ?: volumeBase,
                providerName = providerName,
                sourceAttributionUrl = "https://www.binance.com/en/trade/${symbol}_USDT",
                providerTimestampEpochMs = closeTimeMs,
                formattedTimestamp = tsFormatted,
                freshness = LiveDataFreshness.LIVE,
                timeframeLabel = "24H (1H Candles)",
                historicalPoints = scaledChartPoints,
                historicalUnavailableReason = if (scaledChartPoints.size < 2) "Historical hourly series unavailable for this pair." else null,
                verifiedContextForAiPrompt = aiContext
            )
        } catch (e: Exception) {
            buildUnavailablePayload(
                category = LiveDataCategory.CRYPTOCURRENCY,
                title = "$symbol Live Market Data",
                symbolOrPair = "$symbol/$displayCurrency",
                providerName = "Binance Spot Market API",
                reason = "Failed to fetch live $symbol market data (${e.message ?: "network error"})."
            )
        }
    }

    // ------------------------------------------------------------------------
    // C & D. Foreign Exchange & Currency Conversion Provider (Frankfurter / Open ER-API)
    // ------------------------------------------------------------------------

    private fun fetchForeignExchangeData(
        intent: LiveIntentDetectionResult.Detected,
        onStageChanged: ((String) -> Unit)?
    ): LiveMarketDataPayload {
        val base = intent.primarySymbol.uppercase(Locale.ROOT)
        val target = intent.secondarySymbol.uppercase(Locale.ROOT)
        return fetchFxOrConversionInternal(
            base = base,
            target = target,
            amountToConvert = null,
            category = LiveDataCategory.FOREIGN_EXCHANGE,
            onStageChanged = onStageChanged
        )
    }

    private fun fetchCurrencyConversionData(
        intent: LiveIntentDetectionResult.Detected,
        onStageChanged: ((String) -> Unit)?
    ): LiveMarketDataPayload {
        val base = intent.primarySymbol.uppercase(Locale.ROOT)
        val target = intent.secondarySymbol.uppercase(Locale.ROOT)
        val amount = intent.amount ?: 1.0
        return fetchFxOrConversionInternal(
            base = base,
            target = target,
            amountToConvert = amount,
            category = LiveDataCategory.CURRENCY_CONVERSION,
            onStageChanged = onStageChanged
        )
    }

    private fun fetchFxOrConversionInternal(
        base: String,
        target: String,
        amountToConvert: Double?,
        category: LiveDataCategory,
        onStageChanged: ((String) -> Unit)?
    ): LiveMarketDataPayload {
        return try {
            val latestPair = fetchLatestFxRateInternal(base, target)
                ?: return buildUnavailablePayload(
                    category = category,
                    title = "$base/$target Exchange Rate",
                    symbolOrPair = "$base/$target",
                    providerName = "Frankfurter (ECB) / Open ExchangeRate API",
                    reason = "Unable to retrieve a verified live exchange rate for $base/$target right now."
                )

            val rate = latestPair.first
            val providerName = latestPair.second
            val timestampMs = latestPair.third

            onStageChanged?.invoke("Preparing your live market chart...")
            val historyPoints = fetchFxHistoricalSeriesInternal(base, target, days = 30)

            val changePct = if (historyPoints.size >= 2) {
                val prev = historyPoints[historyPoints.size - 2].value
                if (prev > 0.0) ((rate - prev) / prev) * 100.0 else null
            } else null

            val conversionDetail = amountToConvert?.let { amt ->
                CurrencyConversionDetail(
                    sourceAmount = amt,
                    fromCurrency = base,
                    toCurrency = target,
                    exchangeRate = rate,
                    convertedAmount = amt * rate
                )
            }

            val tsFormatted = formatTimestampUtcAndLocal(timestampMs)
            val primaryDisplay = if (conversionDetail != null) {
                "${formatCurrencyValue(conversionDetail.convertedAmount, target)} (${formatDecimal(conversionDetail.sourceAmount, 2)} $base)"
            } else {
                "1 $base = ${formatDecimal(rate, 4)} $target"
            }

            val aiContext = buildString {
                appendLine("[VERIFIED LIVE FOREIGN EXCHANGE DATA — DO NOT INVENT NUMBERS]")
                appendLine("Currency Pair: $base/$target")
                appendLine("Provider / Source: $providerName")
                appendLine("Provider Timestamp: $tsFormatted")
                appendLine("Exchange Rate: 1 $base = ${formatDecimal(rate, 6)} $target")
                appendLine("Inverse Rate: 1 $target = ${formatDecimal(if (rate > 0) 1.0 / rate else 0.0, 6)} $base")
                if (conversionDetail != null) {
                    appendLine("Amount to Convert: ${formatCurrencyValue(conversionDetail.sourceAmount, base)}")
                    appendLine("Converted Amount: ${formatCurrencyValue(conversionDetail.convertedAmount, target)}")
                    appendLine("Mandatory Disclaimer: ${conversionDetail.spreadDisclaimer}")
                }
                if (historyPoints.size >= 2) {
                    appendLine("30-Day Range: Low ${formatDecimal(historyPoints.minOf { it.value }, 4)} $target — High ${formatDecimal(historyPoints.maxOf { it.value }, 4)} $target (${historyPoints.size} daily data points)")
                }
            }.trim()

            LiveMarketDataPayload(
                id = UUID.randomUUID().toString(),
                category = category,
                title = if (conversionDetail != null) "Currency Conversion ($base → $target)" else "$base/$target Live Exchange Rate",
                symbolOrPair = "$base/$target",
                currentValue = conversionDetail?.convertedAmount ?: rate,
                formattedPrimaryValue = primaryDisplay,
                currencyOrUnit = target,
                change24hPercent = changePct,
                high24h = historyPoints.maxOfOrNull { it.value },
                low24h = historyPoints.minOfOrNull { it.value },
                providerName = providerName,
                sourceAttributionUrl = "https://www.frankfurter.app",
                providerTimestampEpochMs = timestampMs,
                formattedTimestamp = tsFormatted,
                freshness = LiveDataFreshness.REFERENCE_DAILY,
                timeframeLabel = if (historyPoints.size >= 2) "30D Daily" else "Latest",
                historicalPoints = historyPoints,
                historicalUnavailableReason = if (historyPoints.size < 2) "Historical daily time-series is unavailable for this currency pair." else null,
                currencyConversion = conversionDetail,
                verifiedContextForAiPrompt = aiContext
            )
        } catch (e: Exception) {
            buildUnavailablePayload(
                category = category,
                title = "$base/$target Exchange Rate",
                symbolOrPair = "$base/$target",
                providerName = "Frankfurter (ECB) / Open ExchangeRate API",
                reason = "Unable to retrieve $base/$target exchange rate (${e.message ?: "network error"})."
            )
        }
    }

    /**
     * Returns Triple(rate, providerName, timestampEpochMs) or null.
     */
    private fun fetchLatestFxRateInternal(base: String, target: String): Triple<Double, String, Long>? {
        if (base == target) return Triple(1.0, "Identity Rate", currentTimeProvider())

        // 1. Primary: Frankfurter (European Central Bank Reference Rates)
        runCatching {
            val url = "https://api.frankfurter.app/latest?from=$base&to=$target"
            val req = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "SmarterAI-LiveData/1.0")
                .get()
                .build()
            executeHttpRequest(req).use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string().orEmpty())
                    val rates = json.optJSONObject("rates")
                    val rate = rates?.optDouble(target, Double.NaN) ?: Double.NaN
                    if (!rate.isNaN() && rate > 0.0) {
                        val dateStr = json.optString("date", "")
                        val ts = parseDateOnlyEpochMs(dateStr) ?: currentTimeProvider()
                        return Triple(rate, "Frankfurter (European Central Bank Reference)", ts)
                    }
                }
            }
        }

        // 2. Fallback: Open ExchangeRate API
        runCatching {
            val url = "https://open.er-api.com/v6/latest/$base"
            val req = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "SmarterAI-LiveData/1.0")
                .get()
                .build()
            executeHttpRequest(req).use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string().orEmpty())
                    val rates = json.optJSONObject("rates")
                    val rate = rates?.optDouble(target, Double.NaN) ?: Double.NaN
                    if (!rate.isNaN() && rate > 0.0) {
                        val unixSec = json.optLong("time_last_update_unix", 0L)
                        val ts = if (unixSec > 0L) unixSec * 1000L else currentTimeProvider()
                        return Triple(rate, "Open ExchangeRate API", ts)
                    }
                }
            }
        }

        return null
    }

    private fun fetchFxHistoricalSeriesInternal(base: String, target: String, days: Int = 30): List<ChartDataPoint> {
        return runCatching {
            val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val labelFmt = SimpleDateFormat("MMM d", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val endCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = currentTimeProvider()
            }
            val startCal = (endCal.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, -days)
            }
            val startStr = dateFmt.format(startCal.time)
            val endStr = dateFmt.format(endCal.time)

            val url = "https://api.frankfurter.app/$startStr..$endStr?from=$base&to=$target"
            val req = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "SmarterAI-LiveData/1.0")
                .get()
                .build()

            executeHttpRequest(req).use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val json = JSONObject(resp.body?.string().orEmpty())
                val ratesObj = json.optJSONObject("rates") ?: return emptyList()
                val dateKeys = mutableListOf<String>()
                val iter = ratesObj.keys()
                while (iter.hasNext()) {
                    dateKeys.add(iter.next())
                }
                dateKeys.sort()
                buildList {
                    for (dateKey in dateKeys) {
                        val dayObj = ratesObj.optJSONObject(dateKey) ?: continue
                        val value = dayObj.optDouble(target, Double.NaN)
                        if (!value.isNaN() && value > 0.0) {
                            val epoch = parseDateOnlyEpochMs(dateKey) ?: currentTimeProvider()
                            add(
                                ChartDataPoint(
                                    timestampEpochMs = epoch,
                                    label = labelFmt.format(Date(epoch)),
                                    value = value
                                )
                            )
                        }
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------------------
    // E & F. Stocks, Indices & Commodities Provider
    // ------------------------------------------------------------------------

    private fun fetchStockOrIndexData(
        intent: LiveIntentDetectionResult.Detected,
        onStageChanged: ((String) -> Unit)?
    ): LiveMarketDataPayload {
        return fetchYahooChartQuoteInternal(
            symbol = intent.primarySymbol,
            displayName = intent.locationOrQuery ?: intent.primarySymbol,
            category = LiveDataCategory.STOCKS_AND_INDICES,
            onStageChanged = onStageChanged
        )
    }

    private fun fetchCommodityData(
        intent: LiveIntentDetectionResult.Detected,
        onStageChanged: ((String) -> Unit)?
    ): LiveMarketDataPayload {
        return fetchYahooChartQuoteInternal(
            symbol = intent.primarySymbol,
            displayName = intent.locationOrQuery ?: intent.primarySymbol,
            category = LiveDataCategory.COMMODITIES,
            onStageChanged = onStageChanged
        )
    }

    private fun fetchYahooChartQuoteInternal(
        symbol: String,
        displayName: String,
        category: LiveDataCategory,
        onStageChanged: ((String) -> Unit)?
    ): LiveMarketDataPayload {
        return try {
            val encodedSymbol = URLEncoder.encode(symbol, "UTF-8")
            val url = "https://query1.finance.yahoo.com/v8/finance/chart/$encodedSymbol?interval=1d&range=1mo"
            val req = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) SmarterAI/2.0")
                .get()
                .build()

            executeHttpRequest(req).use { resp ->
                if (!resp.isSuccessful) {
                    return buildUnavailablePayload(
                        category = category,
                        title = "$displayName ($symbol)",
                        symbolOrPair = symbol,
                        providerName = "Stock & Commodity Market Feed",
                        reason = "Live equity/commodity feed returned HTTP ${resp.code}. Real-time exchange quotes for $symbol require a configured market data API key (such as Finnhub or Alpha Vantage in Supabase secrets). No fabricated price is shown."
                    )
                }

                val root = JSONObject(resp.body?.string().orEmpty())
                val resultArr = root.optJSONObject("chart")?.optJSONArray("result")
                val firstResult = resultArr?.optJSONObject(0)
                val meta = firstResult?.optJSONObject("meta")
                val regPrice = meta?.optDouble("regularMarketPrice", Double.NaN) ?: Double.NaN
                if (regPrice.isNaN() || regPrice <= 0.0) {
                    return buildUnavailablePayload(
                        category = category,
                        title = "$displayName ($symbol)",
                        symbolOrPair = symbol,
                        providerName = "Market Data Feed",
                        reason = "No verified market quote was returned for '$symbol'. Configure a dedicated stock market API key (Finnhub / Alpha Vantage) for guaranteed equity coverage."
                    )
                }

                onStageChanged?.invoke("Preparing your live market chart...")

                val prevClose = meta?.optDouble("chartPreviousClose", Double.NaN)
                    ?.takeIf { !it.isNaN() && it > 0.0 }
                    ?: meta?.optDouble("previousClose", Double.NaN)?.takeIf { !it.isNaN() && it > 0.0 }
                val currency = meta?.optString("currency", "USD")?.ifBlank { "USD" } ?: "USD"
                val marketTimeSec = meta?.optLong("regularMarketTime", 0L) ?: 0L
                val marketTimeMs = if (marketTimeSec > 0L) marketTimeSec * 1000L else currentTimeProvider()
                val changePct = if (prevClose != null && prevClose > 0.0) {
                    ((regPrice - prevClose) / prevClose) * 100.0
                } else null

                val timestamps = firstResult?.optJSONArray("timestamp")
                val quoteObj = firstResult?.optJSONObject("indicators")
                    ?.optJSONArray("quote")
                    ?.optJSONObject(0)
                val closes = quoteObj?.optJSONArray("close")

                val dateLabelFmt = SimpleDateFormat("MMM d", Locale.US)
                val points = mutableListOf<ChartDataPoint>()
                if (timestamps != null && closes != null) {
                    val count = minOf(timestamps.length(), closes.length())
                    for (i in 0 until count) {
                        val tsSec = timestamps.optLong(i, 0L)
                        val closeVal = closes.optDouble(i, Double.NaN)
                        if (tsSec > 0L && !closeVal.isNaN() && closeVal > 0.0) {
                            val tsMs = tsSec * 1000L
                            points.add(
                                ChartDataPoint(
                                    timestampEpochMs = tsMs,
                                    label = dateLabelFmt.format(Date(tsMs)),
                                    value = closeVal
                                )
                            )
                        }
                    }
                }

                val tsFormatted = formatTimestampUtcAndLocal(marketTimeMs)
                val providerLabel = "Yahoo Finance Market Quote ($symbol)"

                val aiContext = buildString {
                    appendLine("[VERIFIED MARKET QUOTE DATA — DO NOT INVENT NUMBERS]")
                    appendLine("Instrument: $displayName ($symbol)")
                    appendLine("Provider: $providerLabel")
                    appendLine("Quote Timestamp: $tsFormatted")
                    appendLine("Latest Market Price: ${formatCurrencyValue(regPrice, currency)}")
                    prevClose?.let { appendLine("Previous Close: ${formatCurrencyValue(it, currency)}") }
                    changePct?.let {
                        val sign = if (it >= 0) "+" else ""
                        appendLine("Change vs Previous Close: $sign${formatDecimal(it, 2)}%")
                    }
                    if (points.size >= 2) {
                        appendLine("1-Month Historical Series: ${points.size} daily closes rendered in the interactive chart.")
                    }
                }.trim()

                LiveMarketDataPayload(
                    id = UUID.randomUUID().toString(),
                    category = category,
                    title = "$displayName ($symbol)",
                    symbolOrPair = "$symbol ($currency)",
                    currentValue = regPrice,
                    formattedPrimaryValue = formatCurrencyValue(regPrice, currency),
                    currencyOrUnit = currency,
                    change24hPercent = changePct,
                    high24h = points.maxOfOrNull { it.value },
                    low24h = points.minOfOrNull { it.value },
                    providerName = providerLabel,
                    sourceAttributionUrl = "https://finance.yahoo.com/quote/$encodedSymbol",
                    providerTimestampEpochMs = marketTimeMs,
                    formattedTimestamp = tsFormatted,
                    freshness = LiveDataFreshness.DELAYED,
                    timeframeLabel = "1M Daily",
                    historicalPoints = points,
                    historicalUnavailableReason = if (points.size < 2) "Historical chart series unavailable." else null,
                    verifiedContextForAiPrompt = aiContext
                )
            }
        } catch (e: Exception) {
            buildUnavailablePayload(
                category = category,
                title = "$displayName ($symbol)",
                symbolOrPair = symbol,
                providerName = "Market Data Provider",
                reason = "Could not retrieve verified quote for $symbol (${e.message ?: "network error"}). Configure FINNHUB_API_KEY or ALPHA_VANTAGE_API_KEY in server secrets for dedicated stock coverage."
            )
        }
    }

    // ------------------------------------------------------------------------
    // G. Current Information: Weather via Open-Meteo (Keyless Public API)
    // ------------------------------------------------------------------------

    private fun fetchWeatherOrCurrentInfo(
        intent: LiveIntentDetectionResult.Detected,
        onStageChanged: ((String) -> Unit)?
    ): LiveMarketDataPayload {
        val city = intent.locationOrQuery ?: intent.primarySymbol
        return try {
            val encodedCity = URLEncoder.encode(city, "UTF-8")
            val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=$encodedCity&count=1&language=en&format=json"
            val geoReq = Request.Builder()
                .url(geoUrl)
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "SmarterAI-LiveData/1.0")
                .get()
                .build()

            var lat = Double.NaN
            var lon = Double.NaN
            var resolvedName = city

            executeHttpRequest(geoReq).use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string().orEmpty())
                    val first = json.optJSONArray("results")?.optJSONObject(0)
                    if (first != null) {
                        lat = first.optDouble("latitude", Double.NaN)
                        lon = first.optDouble("longitude", Double.NaN)
                        val name = first.optString("name", city)
                        val country = first.optString("country", "")
                        resolvedName = if (country.isNotBlank()) "$name, $country" else name
                    }
                }
            }

            if (lat.isNaN() || lon.isNaN()) {
                return buildUnavailablePayload(
                    category = LiveDataCategory.CURRENT_INFORMATION,
                    title = "Live Weather ($city)",
                    symbolOrPair = city,
                    providerName = "Open-Meteo Weather API",
                    reason = "Could not resolve coordinates for '$city'."
                )
            }

            onStageChanged?.invoke("Preparing your live market chart...")

            val weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,wind_speed_10m&hourly=temperature_2m&forecast_days=1"
            val weatherReq = Request.Builder()
                .url(weatherUrl)
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "SmarterAI-LiveData/1.0")
                .get()
                .build()

            executeHttpRequest(weatherReq).use { resp ->
                if (!resp.isSuccessful) {
                    return buildUnavailablePayload(
                        category = LiveDataCategory.CURRENT_INFORMATION,
                        title = "Live Weather ($resolvedName)",
                        symbolOrPair = resolvedName,
                        providerName = "Open-Meteo Weather API",
                        reason = "Weather service returned HTTP ${resp.code}."
                    )
                }

                val json = JSONObject(resp.body?.string().orEmpty())
                val currentObj = json.optJSONObject("current")
                val tempC = currentObj?.optDouble("temperature_2m", Double.NaN) ?: Double.NaN
                val humidity = currentObj?.optDouble("relative_humidity_2m", Double.NaN)
                val windKmh = currentObj?.optDouble("wind_speed_10m", Double.NaN)
                if (tempC.isNaN()) {
                    return buildUnavailablePayload(
                        category = LiveDataCategory.CURRENT_INFORMATION,
                        title = "Live Weather ($resolvedName)",
                        symbolOrPair = resolvedName,
                        providerName = "Open-Meteo Weather API",
                        reason = "Weather observation payload was incomplete."
                    )
                }

                val hourlyObj = json.optJSONObject("hourly")
                val times = hourlyObj?.optJSONArray("time")
                val temps = hourlyObj?.optJSONArray("temperature_2m")
                val points = mutableListOf<ChartDataPoint>()
                if (times != null && temps != null) {
                    val count = minOf(times.length(), temps.length(), 24)
                    for (i in 0 until count) {
                        val tStr = times.optString(i, "")
                        val v = temps.optDouble(i, Double.NaN)
                        if (tStr.isNotBlank() && !v.isNaN()) {
                            val label = tStr.substringAfter("T", tStr)
                            points.add(
                                ChartDataPoint(
                                    timestampEpochMs = currentTimeProvider() + i * 3_600_000L,
                                    label = label,
                                    value = v
                                )
                            )
                        }
                    }
                }

                val nowTs = currentTimeProvider()
                val tsFormatted = formatTimestampUtcAndLocal(nowTs)
                val aiContext = buildString {
                    appendLine("[VERIFIED LIVE WEATHER DATA — DO NOT INVENT NUMBERS]")
                    appendLine("Location: $resolvedName")
                    appendLine("Provider: Open-Meteo Meteorological Service")
                    appendLine("Timestamp: $tsFormatted")
                    appendLine("Current Temperature: ${formatDecimal(tempC, 1)} °C")
                    if (humidity != null && !humidity.isNaN()) {
                        appendLine("Relative Humidity: ${formatDecimal(humidity, 0)}%")
                    }
                    if (windKmh != null && !windKmh.isNaN()) {
                        appendLine("Wind Speed: ${formatDecimal(windKmh, 1)} km/h")
                    }
                }.trim()

                LiveMarketDataPayload(
                    id = UUID.randomUUID().toString(),
                    category = LiveDataCategory.CURRENT_INFORMATION,
                    title = "Current Weather • $resolvedName",
                    symbolOrPair = resolvedName,
                    currentValue = tempC,
                    formattedPrimaryValue = "${formatDecimal(tempC, 1)} °C",
                    currencyOrUnit = "°C",
                    high24h = points.maxOfOrNull { it.value },
                    low24h = points.minOfOrNull { it.value },
                    providerName = "Open-Meteo Meteorological Service",
                    sourceAttributionUrl = "https://open-meteo.com",
                    providerTimestampEpochMs = nowTs,
                    formattedTimestamp = tsFormatted,
                    freshness = LiveDataFreshness.LIVE,
                    timeframeLabel = "24H Forecast",
                    historicalPoints = points,
                    verifiedContextForAiPrompt = aiContext
                )
            }
        } catch (e: Exception) {
            buildUnavailablePayload(
                category = LiveDataCategory.CURRENT_INFORMATION,
                title = "Live Weather ($city)",
                symbolOrPair = city,
                providerName = "Open-Meteo Weather API",
                reason = "Unable to retrieve live weather for $city (${e.message ?: "network error"})."
            )
        }
    }

    private fun buildUnavailablePayload(
        category: LiveDataCategory,
        title: String,
        symbolOrPair: String,
        providerName: String,
        reason: String
    ): LiveMarketDataPayload {
        val nowMs = currentTimeProvider()
        val tsFormatted = formatTimestampUtcAndLocal(nowMs)
        val aiContext = buildString {
            appendLine("[LIVE DATA PROVIDER NOTICE — STRICT HONESTY RULE]")
            appendLine("Requested Category: ${category.displayTitle} ($symbolOrPair)")
            appendLine("Attempted Provider: $providerName")
            appendLine("Timestamp: $tsFormatted")
            appendLine("Status: UNAVAILABLE — $reason")
            appendLine("CRITICAL INSTRUCTION: Do NOT invent, guess, or present outdated training prices as current live market rates. Clearly inform the user that live provider data could not be retrieved right now ($reason).")
        }.trim()

        return LiveMarketDataPayload(
            id = UUID.randomUUID().toString(),
            category = category,
            title = title,
            symbolOrPair = symbolOrPair,
            currentValue = null,
            formattedPrimaryValue = "Live Data Unavailable",
            currencyOrUnit = "",
            providerName = providerName,
            providerTimestampEpochMs = nowMs,
            formattedTimestamp = tsFormatted,
            freshness = LiveDataFreshness.UNAVAILABLE,
            historicalPoints = emptyList(),
            historicalUnavailableReason = reason,
            verifiedContextForAiPrompt = aiContext,
            errorOrLimitationNotice = reason
        )
    }

    // ========================================================================
    // Helpers & Symbol Dictionaries
    // ========================================================================

    private fun containsLiveTimeSignal(lower: String): Boolean {
        return LIVE_TIME_KEYWORDS.any { lower.contains(it) }
    }

    private fun detectRequestedFiatCurrency(lower: String, defaultCurrency: String): String {
        return when {
            Regex("""\b(inr|rupee|rupees|india|indian|rs\.?)\b""").containsMatchIn(lower) -> "INR"
            Regex("""\b(usd|dollar|dollars|us\s+dollar)\b""").containsMatchIn(lower) -> "USD"
            Regex("""\b(eur|euro|euros)\b""").containsMatchIn(lower) -> "EUR"
            Regex("""\b(gbp|pound|pounds|sterling)\b""").containsMatchIn(lower) -> "GBP"
            Regex("""\b(AED|dirham)\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower) -> "AED"
            Regex("""\b(jpy|yen)\b""").containsMatchIn(lower) -> "JPY"
            Regex("""\b(cad|canadian\s+dollar)\b""").containsMatchIn(lower) -> "CAD"
            Regex("""\b(aud|australian\s+dollar)\b""").containsMatchIn(lower) -> "AUD"
            else -> defaultCurrency
        }
    }

    private fun detectCryptoSymbol(lower: String): Pair<String, String>? {
        for ((pattern, pair) in CRYPTO_KEYWORDS) {
            if (pattern.containsMatchIn(lower)) {
                return pair
            }
        }
        return null
    }

    private fun detectStockTicker(original: String, lower: String): Pair<String, String>? {
        for ((keyword, pair) in KNOWN_STOCKS_AND_INDICES) {
            if (lower.contains(keyword)) return pair
        }
        val explicitTicker = Regex("""\b([A-Z]{2,5})\b""").findAll(original)
            .map { it.groupValues[1] }
            .firstOrNull { it !in COMMON_UPPERCASE_WORDS && it !in SUPPORTED_FIAT_CODES }
        if (explicitTicker != null) {
            return explicitTicker to explicitTicker
        }
        return null
    }

    private fun parseIsoTimestampEpochMs(iso: String): Long? {
        if (iso.isBlank()) return null
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd HH:mm:ss"
        )
        for (p in patterns) {
            runCatching {
                val sdf = SimpleDateFormat(p, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                sdf.parse(iso)?.time?.let { return it }
            }
        }
        return null
    }

    private fun parseDateOnlyEpochMs(dateStr: String): Long? {
        if (dateStr.isBlank()) return null
        return runCatching {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            sdf.parse(dateStr)?.time
        }.getOrNull()
    }

    companion object {
        val SUPPORTED_FIAT_CODES = setOf(
            "USD", "INR", "EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "CNY", "SGD",
            "HKD", "NZD", "SEK", "NOK", "DKK", "ZAR", "MXN", "BRL", "KRW", "TRY",
            "AED", "SAR", "THB", "MYR", "IDR", "PHP", "PLN", "HUF", "CZK", "ILS"
        )

        private val LIVE_TIME_KEYWORDS = listOf(
            "live", "today", "right now", "current", "currently", "latest", "now",
            "spot", "real-time", "realtime", "market price", "rate today", "price today"
        )

        private val CRYPTO_KEYWORDS: List<Pair<Regex, Pair<String, String>>> = listOf(
            Regex("""\b(bitcoin|btc)\b""") to ("BTC" to "Bitcoin"),
            Regex("""\b(ethereum|eth|ether)\b""") to ("ETH" to "Ethereum"),
            Regex("""\b(solana|sol)\b""") to ("SOL" to "Solana"),
            Regex("""\b(binance\s+coin|bnb)\b""") to ("BNB" to "BNB"),
            Regex("""\b(ripple|xrp)\b""") to ("XRP" to "XRP"),
            Regex("""\b(dogecoin|doge)\b""") to ("DOGE" to "Dogecoin"),
            Regex("""\b(cardano|ada)\b""") to ("ADA" to "Cardano"),
            Regex("""\b(avalanche|avax)\b""") to ("AVAX" to "Avalanche"),
            Regex("""\b(polkadot|dot)\b""") to ("DOT" to "Polkadot"),
            Regex("""\b(chainlink|link)\b""") to ("LINK" to "Chainlink"),
            Regex("""\b(litecoin|ltc)\b""") to ("LTC" to "Litecoin")
        )

        private val KNOWN_STOCKS_AND_INDICES: Map<String, Pair<String, String>> = mapOf(
            "apple" to ("AAPL" to "Apple Inc."),
            "aapl" to ("AAPL" to "Apple Inc."),
            "microsoft" to ("MSFT" to "Microsoft Corp."),
            "msft" to ("MSFT" to "Microsoft Corp."),
            "nvidia" to ("NVDA" to "NVIDIA Corp."),
            "nvda" to ("NVDA" to "NVIDIA Corp."),
            "tesla" to ("TSLA" to "Tesla Inc."),
            "tsla" to ("TSLA" to "Tesla Inc."),
            "google" to ("GOOGL" to "Alphabet Inc."),
            "alphabet" to ("GOOGL" to "Alphabet Inc."),
            "googl" to ("GOOGL" to "Alphabet Inc."),
            "amazon" to ("AMZN" to "Amazon.com Inc."),
            "amzn" to ("AMZN" to "Amazon.com Inc."),
            "meta" to ("META" to "Meta Platforms Inc."),
            "s&p 500" to ("^GSPC" to "S&P 500 Index"),
            "sp500" to ("^GSPC" to "S&P 500 Index"),
            "nasdaq" to ("^IXIC" to "NASDAQ Composite"),
            "dow jones" to ("^DJI" to "Dow Jones Industrial Average"),
            "nifty" to ("^NSEI" to "NIFTY 50 Index"),
            "sensex" to ("^BSESN" to "BSE SENSEX")
        )

        private val COMMON_UPPERCASE_WORDS = setOf(
            "WHAT", "SHOW", "TELL", "LIVE", "RATE", "PRICE", "TODAY", "NOW",
            "THE", "FOR", "AND", "WITH", "STOCK", "SHARE", "CURRENT", "MARKET"
        )

        fun formatDecimal(value: Double, decimals: Int = 2): String {
            val pattern = if (decimals <= 0) "#,##0" else "#,##0." + "0".repeat(decimals)
            return DecimalFormat(pattern).format(value)
        }

        fun formatCurrencyValue(value: Double, currencyCode: String): String {
            val symbol = when (currencyCode.uppercase(Locale.ROOT)) {
                "USD", "USDT" -> "$"
                "INR" -> "₹"
                "EUR" -> "€"
                "GBP" -> "£"
                "JPY" -> "¥"
                else -> "${currencyCode.uppercase(Locale.ROOT)} "
            }
            val decimals = when {
                value >= 100.0 -> 2
                value >= 1.0 -> 2
                else -> 4
            }
            return "$symbol${formatDecimal(value, decimals)}"
        }

        fun formatTimestampUtcAndLocal(epochMs: Long): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            return sdf.format(Date(epochMs))
        }
    }
}
