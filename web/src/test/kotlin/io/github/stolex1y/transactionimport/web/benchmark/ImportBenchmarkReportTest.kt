package io.github.stolex1y.transactionimport.web.benchmark

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ImportBenchmarkReportTest {
    @Test
    fun reportContainsOnlyMetricsAndRequestFingerprints() {
        val fixture = ImportBenchmarkFixtureLoader.loadBundled()
        val run = ImportBenchmarkRun(
            outcome = ImportBenchmarkOutcome.Scored(
                ImportBenchmarkMetrics(
                    selectionPrecision = 0.75,
                    selectionRecall = 0.6,
                    selectionTruePositive = 3,
                    selectionFalsePositive = 1,
                    selectionFalseNegative = 2,
                    categoryAccuracy = 0.94,
                    categoryCoverage = 20.0 / 23.0,
                    categoryAccuracyWhenAssigned = 1.0,
                    categoryCorrect = 47,
                    categoryAssigned = 20,
                    categoryLabelCount = 50,
                    categoryApplicableLabelCount = 23,
                    matchedReceiptCount = 7,
                    ambiguousReceiptCount = 1,
                    unmatchedReceiptCount = 42,
                    receiptCorrectCount = 31,
                    receiptAccuracy = 0.62,
                    receiptLabelCount = 50,
                ),
            ),
            gateway = OmpGatewayMetadata(
                modelSelector = fixture.modelSelector,
                reasoningEffort = fixture.reasoningEffort,
                gatewayVersion = "synthetic-omp-version",
                fallbackOverlaySha256 = "a".repeat(64),
                requestCount = 1,
                requestFingerprints = listOf("b".repeat(64)),
                responseModels = listOf("openai-codex/gpt-6-luna"),
            ),
        )

        val report = ImportBenchmarkReportWriter.render(
            mode = "codex",
            fixture = fixture,
            runs = listOf(run),
            sourceCommit = "0123456789ab",
            generatedAt = "2026-10-14T12:00:00Z",
        )

        val reportedResponseModel = Json.parseToJsonElement(report)
            .jsonObject.getValue("runs").jsonArray.single().jsonObject
            .getValue("gateway").jsonObject.getValue("responseModels").jsonArray.single().jsonPrimitive.content
        assertEquals("openai-codex/gpt-6-luna", reportedResponseModel)
        val reportedMetrics = Json.parseToJsonElement(report).jsonObject
            .getValue("runs").jsonArray.single().jsonObject
            .getValue("metrics").jsonObject
        assertEquals(50, reportedMetrics.getValue("categoryLabelCount").jsonPrimitive.int)
        assertEquals(23, reportedMetrics.getValue("categoryApplicableLabelCount").jsonPrimitive.int)
        assertEquals(50, reportedMetrics.getValue("receiptLabelCount").jsonPrimitive.int)
        assertTrue(report.contains(fixture.modelSelector))
        assertTrue(report.contains("reasoningEffort"))
        assertTrue(report.contains("medium"))
        assertTrue(report.contains("requestFingerprints"))
        assertTrue(report.contains("selectionPrecision"))
        assertTrue(report.contains("receiptAccuracy"))
        assertTrue(report.contains("\"range\": 0.0"))
        assertFalse(report.contains(fixture.modelInput.userTurn))
        assertFalse(fixture.modelInput.transactions.any { report.contains(it.id) })
        assertFalse(fixture.modelInput.transactions.any { it.merchant.isNotBlank() && report.contains(it.merchant) })
        assertFalse(report.contains("rawUsage"))
    }

    @Test
    fun nullCategoryMetricsRemainNullAndDoNotEnterVariationRanges() {
        val fixture = ImportBenchmarkFixtureLoader.loadBundled()
        val metrics = ImportBenchmarkMetrics(
            selectionPrecision = 1.0,
            selectionRecall = 1.0,
            selectionTruePositive = 1,
            selectionFalsePositive = 0,
            selectionFalseNegative = 0,
            categoryAccuracy = null,
            categoryCoverage = null,
            categoryAccuracyWhenAssigned = null,
            categoryCorrect = 0,
            categoryAssigned = 0,
            categoryLabelCount = 0,
            categoryApplicableLabelCount = 0,
            matchedReceiptCount = 0,
            ambiguousReceiptCount = 0,
            unmatchedReceiptCount = 50,
            receiptCorrectCount = 50,
            receiptAccuracy = 1.0,
            receiptLabelCount = 50,
        )
        val report = ImportBenchmarkReportWriter.render(
            mode = "codex",
            fixture = fixture,
            runs = listOf(ImportBenchmarkRun(ImportBenchmarkOutcome.Scored(metrics))),
            sourceCommit = "0123456789ab",
            generatedAt = "2026-10-14T12:00:00Z",
        )
        val root = Json.parseToJsonElement(report).jsonObject
        val runMetrics = root.getValue("runs").jsonArray.single().jsonObject
            .getValue("metrics").jsonObject
        val accuracyVariation = root.getValue("variation").jsonObject
            .getValue("categoryAccuracy").jsonObject

        assertEquals(JsonNull, runMetrics.getValue("categoryAccuracy"))
        assertEquals(JsonNull, runMetrics.getValue("categoryCoverage"))
        assertEquals(0, accuracyVariation.getValue("observedRuns").jsonPrimitive.int)
        assertEquals(JsonNull, accuracyVariation.getValue("minimum"))
        assertEquals(JsonNull, accuracyVariation.getValue("maximum"))
        assertEquals(JsonNull, accuracyVariation.getValue("range"))
    }

    @Test
    fun rendersScoredAndFailedRepeatOutcomesWithoutRawFailureDetails() {
        val fixture = ImportBenchmarkFixtureLoader.loadBundled()
        val metrics = ImportBenchmarkMetrics(
            selectionPrecision = 0.75,
            selectionRecall = 0.6,
            selectionTruePositive = 3,
            selectionFalsePositive = 1,
            selectionFalseNegative = 2,
            categoryAccuracy = 0.94,
            categoryCoverage = 20.0 / 23.0,
            categoryAccuracyWhenAssigned = 1.0,
            categoryCorrect = 47,
            categoryAssigned = 20,
            categoryLabelCount = 50,
            categoryApplicableLabelCount = 23,
            matchedReceiptCount = 7,
            ambiguousReceiptCount = 1,
            unmatchedReceiptCount = 42,
            receiptCorrectCount = 31,
            receiptAccuracy = 0.62,
            receiptLabelCount = 50,
        )
        val runs = listOf(
            ImportBenchmarkRun(ImportBenchmarkOutcome.Scored(metrics)),
            ImportBenchmarkRun(
                ImportBenchmarkOutcome.Scored(metrics.copy(selectionPrecision = 0.5)),
            ),
            ImportBenchmarkRun(
                ImportBenchmarkOutcome.Failed(ImportBenchmarkFailureCode.NO_IMPORT_PREVIEW),
            ),
        )

        val report = ImportBenchmarkReportWriter.render(
            mode = "codex",
            fixture = fixture,
            runs = runs,
            sourceCommit = "0123456789ab",
            generatedAt = "2026-10-14T12:00:00Z",
        )
        val root = Json.parseToJsonElement(report).jsonObject
        val reportedRuns = root.getValue("runs").jsonArray.map { it.jsonObject }
        val variation = root.getValue("variation").jsonObject
            .getValue("selectionPrecision").jsonObject

        assertEquals(2, root.getValue("successfulRunCount").jsonPrimitive.int)
        assertEquals(1, root.getValue("failedRunCount").jsonPrimitive.int)
        assertEquals(
            1,
            root.getValue("failureCounts").jsonObject
                .getValue("no_import_preview").jsonPrimitive.int,
        )
        assertEquals(listOf("scored", "scored", "failed"), reportedRuns.map {
            it.getValue("status").jsonPrimitive.content
        })
        assertEquals("no_import_preview", reportedRuns.last().getValue("failureCode").jsonPrimitive.content)
        assertFalse(reportedRuns.last().containsKey("metrics"))
        assertEquals(2, variation.getValue("observedRuns").jsonPrimitive.int)
        assertEquals(0.5, variation.getValue("minimum").jsonPrimitive.content.toDouble())
        assertEquals(0.75, variation.getValue("maximum").jsonPrimitive.content.toDouble())
        assertEquals(0.25, variation.getValue("range").jsonPrimitive.content.toDouble())
        assertFalse(report.contains("raw failure detail"))
        assertFalse(report.contains(fixture.modelInput.userTurn))
        assertFalse(fixture.modelInput.transactions.any { report.contains(it.id) })
        assertEquals(3, root.getValue("schemaVersion").jsonPrimitive.int)
    }

    @Test
    fun reportWriterDoesNotOverwriteExistingFile() {
        val directory = Files.createTempDirectory("benchmark-report-test-")
        try {
            val path = directory.resolve("report.json")
            Files.writeString(path, "existing")

            assertFailsWith<java.nio.file.FileAlreadyExistsException> {
                ImportBenchmarkReportWriter.writeNew(path, "replacement")
            }
            assertEquals("existing", Files.readString(path))
        } finally {
            Files.deleteIfExists(directory.resolve("report.json"))
            Files.deleteIfExists(directory)
        }
    }
}
