package io.github.stolex1y.transactionimport.d04

import io.github.stolex1y.transactionimport.core.DEFAULT_MODEL
import io.github.stolex1y.transactionimport.core.ExtractionOptions
import io.github.stolex1y.transactionimport.core.ReasoningLevel
import io.github.stolex1y.transactionimport.core.ResponseMode
import io.github.stolex1y.transactionimport.core.StructuredImport
import io.github.stolex1y.transactionimport.core.StructuredValidation
import io.github.stolex1y.transactionimport.core.TransactionImportService
import io.github.stolex1y.transactionimport.core.Usage
import io.github.stolex1y.transactionimport.transport.DeepSeekGateway
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Instant

const val D04_MAX_TOKENS = 1200
const val D04_REPEATS = 5
val D04_TEMPERATURES = listOf(0.0, 0.7, 1.2)

@Serializable
data class D04Configuration(
    val model: String,
    val reasoning: String,
    val responseMode: String,
    val responseFormat: String,
    val maxTokens: Int,
    val temperatures: List<Double>,
    val repeatsPerTemperature: Int,
    val stream: Boolean,
)

@Serializable
data class D04Run(
    val index: Int,
    val temperature: Double,
    val processingTimeMs: Long,
    val text: String? = null,
    val textLength: Int? = null,
    val finishReason: String? = null,
    val usage: Usage? = null,
    val validation: StructuredValidation? = null,
    val evaluation: D04Evaluation? = null,
    val responseFingerprint: String? = null,
    val error: String? = null,
)

@Serializable
data class D04TemperatureSummary(
    val temperature: Double,
    val runs: List<D04Run>,
    val distinctResponseFingerprints: Int,
    val validRuns: Int,
    val importableRuns: Int,
    val exactReferenceRuns: Int,
    val medianProcessingTimeMs: Long?,
)

@Serializable
data class D04Report(
    val generatedAt: String,
    val fixture: String,
    val configuration: D04Configuration,
    val reference: List<ReferenceOperation>,
    val summaries: List<D04TemperatureSummary>,
)

suspend fun runD04Experiment(
    statement: String,
    fixture: String,
    httpClient: HttpClient,
    apiKey: String,
): D04Report {
    val service = TransactionImportService(DeepSeekGateway(httpClient, apiKey))
    val reference = hardReference()
    val summaries = D04_TEMPERATURES.map { temperature ->
        val runs = (1..D04_REPEATS).map { index ->
            runOnce(service, statement, temperature, index, reference)
        }
        D04TemperatureSummary(
            temperature = temperature,
            runs = runs,
            distinctResponseFingerprints = runs.mapNotNull(D04Run::responseFingerprint).distinct().size,
            validRuns = runs.count { it.validation?.valid == true },
            importableRuns = runs.count { it.validation?.importable == true },
            exactReferenceRuns = runs.count { it.evaluation?.exactReference == true },
            medianProcessingTimeMs = median(runs.map(D04Run::processingTimeMs)),
        )
    }
    return D04Report(
        generatedAt = Instant.now().toString(),
        fixture = fixture,
        configuration = D04Configuration(
            model = DEFAULT_MODEL,
            reasoning = "disabled",
            responseMode = "controlled_json",
            responseFormat = "json_object",
            maxTokens = D04_MAX_TOKENS,
            temperatures = D04_TEMPERATURES,
            repeatsPerTemperature = D04_REPEATS,
            stream = false,
        ),
        reference = reference,
        summaries = summaries,
    )
}

private suspend fun runOnce(
    service: TransactionImportService,
    statement: String,
    temperature: Double,
    index: Int,
    reference: List<ReferenceOperation>,
): D04Run {
    val startedAt = System.nanoTime()
    return try {
        val result = service.extract(
            statement = statement,
            options = ExtractionOptions(
                model = DEFAULT_MODEL,
                reasoning = ReasoningLevel.DISABLED,
                responseMode = ResponseMode.CONTROLLED_JSON,
                temperature = temperature,
            ),
        )
        val processingTimeMs = (System.nanoTime() - startedAt) / 1_000_000
        D04Run(
            index = index,
            temperature = temperature,
            processingTimeMs = processingTimeMs,
            text = result.text,
            textLength = result.text.length,
            finishReason = result.finishReason,
            usage = result.usage,
            validation = result.validation,
            evaluation = evaluateStructured(result.structured, result.validation, reference),
            responseFingerprint = fingerprint(result.structured, result.text),
        )
    } catch (exception: Exception) {
        D04Run(
            index = index,
            temperature = temperature,
            processingTimeMs = (System.nanoTime() - startedAt) / 1_000_000,
            error = exception.message ?: exception::class.simpleName,
        )
    }
}

private fun fingerprint(document: StructuredImport?, text: String): String {
    val canonical = document?.let { Json.encodeToString(it) }
        ?: text.trim().replace(Regex("\\s+"), " ")
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
}

private fun median(values: List<Long>): Long? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    return sorted[sorted.size / 2]
}

fun reportJson(): Json = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = true
}
