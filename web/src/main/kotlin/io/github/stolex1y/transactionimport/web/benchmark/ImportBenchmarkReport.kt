package io.github.stolex1y.transactionimport.web.benchmark

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.time.Instant

internal data class ImportBenchmarkRun(
    val metrics: ImportBenchmarkMetrics,
    val gateway: OmpGatewayMetadata? = null,
)

internal object ImportBenchmarkReportWriter {
    private val json = Json { prettyPrint = true; explicitNulls = false }

    fun render(
        mode: String,
        fixture: ImportBenchmarkFixture,
        runs: List<ImportBenchmarkRun>,
        sourceCommit: String,
        generatedAt: String = Instant.now().toString(),
    ): String {
        require(mode in setOf("offline", "codex"))
        require(runs.isNotEmpty())
        return json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("schemaVersion", 1)
                put("generatedAt", generatedAt)
                put("sourceCommit", sourceCommit)
                put("mode", mode)
                put("modelSelector", fixture.modelSelector)
                put("fixtureSha256", fixture.sha256)
                put("repeatCount", runs.size)
                putJsonArray("runs") {
                    runs.forEachIndexed { index, run ->
                        add(runJson(index + 1, run))
                    }
                }
                put("variation", variationJson(runs.map(ImportBenchmarkRun::metrics)))
            },
        )
    }

    fun writeNew(path: Path, content: String) {
        val absolutePath = path.toAbsolutePath().normalize()
        absolutePath.parent?.let(Files::createDirectories)
        Files.writeString(
            absolutePath,
            content + "\n",
            StandardCharsets.UTF_8,
            CREATE_NEW,
            WRITE,
        )
    }

    private fun runJson(repetition: Int, run: ImportBenchmarkRun): JsonObject = buildJsonObject {
        put("repetition", repetition)
        put("metrics", metricsJson(run.metrics))
        run.gateway?.let { gateway ->
            putJsonObject("gateway") {
                put("modelSelector", gateway.modelSelector)
                put("gatewayVersion", gateway.gatewayVersion)
                put("fallbackOverlaySha256", gateway.fallbackOverlaySha256)
                put("requestCount", gateway.requestCount)
                putJsonArray("requestFingerprints") {
                    gateway.requestFingerprints.forEach { add(JsonPrimitive(it)) }
                }
                putJsonArray("responseModels") {
                    gateway.responseModels.forEach { add(JsonPrimitive(it)) }
                }
            }
        }
    }

    private fun metricsJson(metrics: ImportBenchmarkMetrics): JsonObject = buildJsonObject {
        putNullable("selectionPrecision", metrics.selectionPrecision)
        put("selectionRecall", metrics.selectionRecall)
        put("selectionTruePositive", metrics.selectionTruePositive)
        put("selectionFalsePositive", metrics.selectionFalsePositive)
        put("selectionFalseNegative", metrics.selectionFalseNegative)
        put("categoryAccuracy", metrics.categoryAccuracy)
        put("categoryCoverage", metrics.categoryCoverage)
        putNullable("categoryAccuracyWhenAssigned", metrics.categoryAccuracyWhenAssigned)
        put("categoryCorrect", metrics.categoryCorrect)
        put("categoryAssigned", metrics.categoryAssigned)
        put("categoryLabelCount", metrics.categoryLabelCount)
        put("matchedReceiptCount", metrics.matchedReceiptCount)
        put("ambiguousReceiptCount", metrics.ambiguousReceiptCount)
        put("unmatchedReceiptCount", metrics.unmatchedReceiptCount)
        put("receiptLabelCount", metrics.receiptLabelCount)
    }

    private fun variationJson(metrics: List<ImportBenchmarkMetrics>): JsonObject = buildJsonObject {
        put("selectionPrecision", rangeJson(metrics.map(ImportBenchmarkMetrics::selectionPrecision)))
        put("selectionRecall", rangeJson(metrics.map(ImportBenchmarkMetrics::selectionRecall)))
        put("categoryAccuracy", rangeJson(metrics.map(ImportBenchmarkMetrics::categoryAccuracy)))
        put("categoryCoverage", rangeJson(metrics.map(ImportBenchmarkMetrics::categoryCoverage)))
    }

    private fun rangeJson(values: List<Double?>): JsonObject {
        val observed = values.filterNotNull()
        return buildJsonObject {
            put("observedRuns", observed.size)
            putNullable("minimum", observed.minOrNull())
            putNullable("maximum", observed.maxOrNull())
            putNullable("range", observed.takeIf { it.isNotEmpty() }?.let { it.max() - it.min() })
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: Double?) {
        put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonArray(
        key: String,
        builder: kotlinx.serialization.json.JsonArrayBuilder.() -> Unit,
    ) {
        put(key, buildJsonArray(builder))
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonObject(
        key: String,
        builder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ) {
        put(key, buildJsonObject(builder))
    }
}
