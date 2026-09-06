package io.github.stolex1y.transactionimport.d05

import io.github.stolex1y.transactionimport.core.ExtractionOptions
import io.github.stolex1y.transactionimport.core.ReasoningLevel
import io.github.stolex1y.transactionimport.core.ResponseMode
import io.github.stolex1y.transactionimport.core.StructuredImport
import io.github.stolex1y.transactionimport.core.Usage
import io.github.stolex1y.transactionimport.core.TransactionImportService
import io.github.stolex1y.transactionimport.d04.D04Evaluation
import io.github.stolex1y.transactionimport.d04.ReferenceOperation
import io.github.stolex1y.transactionimport.d04.evaluateStructured
import io.github.stolex1y.transactionimport.d04.hardReference
import io.github.stolex1y.transactionimport.transport.DeepSeekGateway
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.ceil

private const val REPEATS = 5
private const val MAX_TOKENS = 4096
private const val TEMPERATURE = 0.0
private const val DEEPSEEK_ENDPOINT = "https://api.deepseek.com/chat/completions"
private const val OPENROUTER_ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
private const val OPENROUTER_MODEL = "z-ai/glm-5.2:free"

@Serializable
data class D05Controls(
    val temperature: Double,
    val reasoning: String,
    val deepSeekThinking: String,
    val deepSeekReasoningEffort: String,
    val openRouterReasoningEffort: String,
    val responseFormat: String,
    val maxTokens: Int,
    val stream: Boolean,
    val repeatsPerModel: Int,
)

@Serializable
data class D05ModelSpec(
    val provider: String,
    val model: String,
    val levelHypothesis: String,
    val endpoint: String,
    val apiKeyEnv: String,
)

@Serializable
data class D05Preflight(
    val provider: String,
    val model: String,
    val endpoint: String,
    val passed: Boolean,
    val reasoningSupport: String,
    val modelMetadata: JsonObject? = null,
    val error: String? = null,
)

@Serializable
data class D05CostEstimate(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val cacheHitTokens: Int? = null,
    val cacheMissTokens: Int? = null,
    val inputCacheHitUsdPerMillion: Double? = null,
    val inputCacheMissUsdPerMillion: Double? = null,
    val outputUsdPerMillion: Double? = null,
    val estimatedUsd: Double? = null,
    val pricingBasis: String,
)

@Serializable
data class D05Run(
    val repeat: Int,
    val processingTimeMs: Long,
    val text: String? = null,
    val textLength: Int? = null,
    val finishReason: String? = null,
    val reasoningContentLength: Int? = null,
    val usage: Usage? = null,
    val rawUsage: JsonObject? = null,
    val validation: io.github.stolex1y.transactionimport.core.StructuredValidation? = null,
    val evaluation: D04Evaluation? = null,
    val responseFingerprint: String? = null,
    val cost: D05CostEstimate? = null,
    val error: String? = null,
)

@Serializable
data class D05TokenTotals(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
)

@Serializable
data class D05ModelSummary(
    val spec: D05ModelSpec,
    val preflight: D05Preflight,
    val runs: List<D05Run>,
    val validRuns: Int,
    val importableRuns: Int,
    val exactReferenceRuns: Int,
    val meanAccuracy: Double? = null,
    val medianProcessingTimeMs: Long? = null,
    val p95ProcessingTimeMs: Long? = null,
    val distinctResponseFingerprints: Int,
    val tokenTotals: D05TokenTotals,
    val estimatedCostUsd: Double? = null,
    val compatibilityErrors: List<String> = emptyList(),
)

@Serializable
data class D05Report(
    val generatedAt: String,
    val fixture: String,
    val controls: D05Controls,
    val reference: List<ReferenceOperation>,
    val summaries: List<D05ModelSummary>,
)

fun d05Controls(): D05Controls = D05Controls(
    temperature = TEMPERATURE,
    reasoning = "enabled",
    deepSeekThinking = "enabled",
    deepSeekReasoningEffort = "high",
    openRouterReasoningEffort = "high",
    responseFormat = "json_object",
    maxTokens = MAX_TOKENS,
    stream = false,
    repeatsPerModel = REPEATS,
)

suspend fun runD05Experiment(
    statement: String,
    fixture: String,
    httpClient: HttpClient,
    deepSeekApiKey: String,
    openRouterApiKey: String,
): D05Report {
    val reference = hardReference()
    val controls = d05Controls()
    val openRouterGateway = OpenRouterGateway(httpClient, openRouterApiKey)
    val specs = listOf(
        D05ModelSpec(
            provider = "deepseek",
            model = "deepseek-v4-flash",
            levelHypothesis = "budget",
            endpoint = DEEPSEEK_ENDPOINT,
            apiKeyEnv = "DEEPSEEK_API_KEY",
        ),
        D05ModelSpec(
            provider = "openrouter",
            model = OPENROUTER_MODEL,
            levelHypothesis = "cross-provider baseline",
            endpoint = OPENROUTER_ENDPOINT,
            apiKeyEnv = "OPENROUTER_API_KEY",
        ),
        D05ModelSpec(
            provider = "deepseek",
            model = "deepseek-v4-pro",
            levelHypothesis = "strong baseline",
            endpoint = DEEPSEEK_ENDPOINT,
            apiKeyEnv = "DEEPSEEK_API_KEY",
        ),
    )
    val summaries = specs.map { spec ->
        val preflight = if (spec.provider == "openrouter") {
            try {
                val metadata = openRouterGateway.verifyModel(spec.model)
                D05Preflight(
                    provider = spec.provider,
                    model = spec.model,
                    endpoint = spec.endpoint,
                    passed = true,
                    reasoningSupport = metadata["supported_parameters"]
                        ?.toString()
                        ?.let { if (it.contains("reasoning")) "advertised" else "metadata does not list reasoning" }
                        ?: "not exposed in metadata",
                    modelMetadata = metadata,
                )
            } catch (error: Throwable) {
                D05Preflight(
                    provider = spec.provider,
                    model = spec.model,
                    endpoint = spec.endpoint,
                    passed = false,
                    reasoningSupport = "not verified",
                    error = safeError(error, openRouterApiKey),
                )
            }
        } else {
            D05Preflight(
                provider = spec.provider,
                model = spec.model,
                endpoint = spec.endpoint,
                passed = true,
                reasoningSupport = "DeepSeek request contract",
            )
        }
        val gateway = if (spec.provider == "openrouter") {
            openRouterGateway
        } else {
            DeepSeekGateway(httpClient, deepSeekApiKey)
        }
        val runs = if (preflight.passed) {
            runModel(statement, gateway, spec, reference, deepSeekApiKey, openRouterApiKey)
        } else {
            emptyList()
        }
        summarize(spec, preflight, runs)
    }
    return D05Report(
        generatedAt = Instant.now().toString(),
        fixture = fixture,
        controls = controls,
        reference = reference,
        summaries = summaries,
    )
}

private suspend fun runModel(
    statement: String,
    gateway: io.github.stolex1y.transactionimport.core.ChatCompletionGateway,
    spec: D05ModelSpec,
    reference: List<ReferenceOperation>,
    deepSeekApiKey: String,
    openRouterApiKey: String,
): List<D05Run> {
    val service = TransactionImportService(gateway)
    val options = ExtractionOptions(
        model = spec.model,
        reasoning = ReasoningLevel.HIGH,
        responseMode = ResponseMode.CONTROLLED_JSON,
        temperature = TEMPERATURE,
        maxTokens = MAX_TOKENS,
    )
    val runs = mutableListOf<D05Run>()
    for (index in 0 until REPEATS) {
        val startedAt = System.nanoTime()
        try {
            val result = service.extract(statement, options)
            val elapsed = elapsedMs(startedAt)
            val evaluation = evaluateStructured(result.structured, result.validation, reference)
            runs += D05Run(
                repeat = index + 1,
                processingTimeMs = elapsed,
                text = result.text,
                textLength = result.text.length,
                finishReason = result.finishReason,
                reasoningContentLength = result.reasoningContentLength,
                usage = result.usage,
                rawUsage = result.rawUsage,
                validation = result.validation,
                evaluation = evaluation,
                responseFingerprint = responseFingerprint(result.structured, result.text),
                cost = estimateCost(spec, result.usage, result.rawUsage),
            )
        } catch (error: Throwable) {
            runs += D05Run(
                repeat = index + 1,
                processingTimeMs = elapsedMs(startedAt),
                error = safeError(
                    error,
                    if (spec.provider == "openrouter") openRouterApiKey else deepSeekApiKey,
                ),
            )
            break
        }
    }
    return runs
}

private fun summarize(
    spec: D05ModelSpec,
    preflight: D05Preflight,
    runs: List<D05Run>,
): D05ModelSummary {
    val successful = runs.filter { it.error == null }
    val evaluations = successful.mapNotNull { it.evaluation }
    val times = successful.map { it.processingTimeMs }
    val usage = successful.mapNotNull { it.usage }
    val costs = successful.mapNotNull { it.cost?.estimatedUsd }
    val compatibilityErrors = buildList {
        preflight.error?.let(::add)
        successful.filter { it.finishReason != "stop" }.forEach { run ->
            add("repeat ${run.repeat} finish_reason=${run.finishReason}")
        }
        runs.mapNotNull { it.error }.forEach(::add)
    }
    return D05ModelSummary(
        spec = spec,
        preflight = preflight,
        runs = runs,
        validRuns = evaluations.count { it.valid },
        importableRuns = evaluations.count { it.importable },
        exactReferenceRuns = evaluations.count { it.exactReference },
        meanAccuracy = evaluations.takeIf { it.isNotEmpty() }?.map { it.accuracy }?.average(),
        medianProcessingTimeMs = times.medianOrNull(),
        p95ProcessingTimeMs = times.percentile(0.95),
        distinctResponseFingerprints = successful.mapNotNull { it.responseFingerprint }.distinct().size,
        tokenTotals = D05TokenTotals(
            promptTokens = usage.sumOf { it.promptTokens ?: 0 },
            completionTokens = usage.sumOf { it.completionTokens ?: 0 },
            totalTokens = usage.sumOf { it.totalTokens ?: 0 },
        ),
        estimatedCostUsd = costs.takeIf { it.isNotEmpty() }?.sum(),
        compatibilityErrors = compatibilityErrors,
    )
}

private fun estimateCost(
    spec: D05ModelSpec,
    usage: Usage?,
    rawUsage: JsonObject?,
): D05CostEstimate {
    val inputTokens = usage?.promptTokens
    val outputTokens = usage?.completionTokens
    if (spec.provider == "openrouter") {
        return D05CostEstimate(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cacheHitTokens = null,
            cacheMissTokens = null,
            estimatedUsd = 0.0,
            pricingBasis = "OpenRouter :free; participant quota and rate limits are not inferred from price.",
        )
    }
    val cacheHit = rawUsage?.intValue("prompt_cache_hit_tokens") ?: 0
    val cacheMiss = rawUsage?.intValue("prompt_cache_miss_tokens") ?: (inputTokens ?: 0)
    val peak = Instant.now().atZone(java.time.ZoneOffset.UTC).hour in 1..3 ||
        Instant.now().atZone(java.time.ZoneOffset.UTC).hour in 6..9
    val (hitRate, missRate, outputRate) = when (spec.model) {
        "deepseek-v4-pro" -> if (peak) Triple(0.044, 1.32, 3.96) else Triple(0.022, 0.66, 1.98)
        else -> if (peak) Triple(0.014, 0.44, 1.32) else Triple(0.007, 0.22, 0.66)
    }
    val estimated = (cacheHit * hitRate + cacheMiss * missRate + (outputTokens ?: 0) * outputRate) / 1_000_000.0
    return D05CostEstimate(
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        cacheHitTokens = cacheHit,
        cacheMissTokens = cacheMiss,
        inputCacheHitUsdPerMillion = hitRate,
        inputCacheMissUsdPerMillion = missRate,
        outputUsdPerMillion = outputRate,
        estimatedUsd = estimated,
        pricingBasis = "DeepSeek official V4 pricing; ${if (peak) "peak" else "off-peak"} UTC window; missing cache fields treated as cache miss.",
    )
}

private fun JsonObject.intValue(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

private fun responseFingerprint(document: StructuredImport?, text: String): String {
    val canonical = if (document != null) {
        Json.encodeToString(StructuredImport.serializer(), document)
    } else {
        text.replace(Regex("\\s+"), " ").trim()
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

private fun elapsedMs(startedAt: Long): Long =
    (System.nanoTime() - startedAt) / 1_000_000L

private fun List<Long>.medianOrNull(): Long? =
    if (isEmpty()) null else sorted()[size / 2]

private fun List<Long>.percentile(fraction: Double): Long? =
    if (isEmpty()) null else sorted()[(ceil(size * fraction).toInt() - 1).coerceAtLeast(0).coerceAtMost(size - 1)]

private fun safeError(error: Throwable, secret: String): String =
    (error.message ?: error::class.simpleName ?: "unknown error").replace(secret, "[REDACTED]")
