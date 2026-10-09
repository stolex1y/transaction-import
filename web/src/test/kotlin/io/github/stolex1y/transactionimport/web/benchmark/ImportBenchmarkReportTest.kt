package io.github.stolex1y.transactionimport.web.benchmark

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
            metrics = ImportBenchmarkMetrics(
                selectionPrecision = 0.75,
                selectionRecall = 0.6,
                selectionTruePositive = 3,
                selectionFalsePositive = 1,
                selectionFalseNegative = 2,
                categoryAccuracy = 0.5,
                categoryCoverage = 0.75,
                categoryAccuracyWhenAssigned = 2.0 / 3.0,
                categoryCorrect = 2,
                categoryAssigned = 3,
                categoryLabelCount = 4,
                matchedReceiptCount = 2,
                ambiguousReceiptCount = 1,
                unmatchedReceiptCount = 47,
                receiptLabelCount = 0,
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
        assertTrue(report.contains(fixture.modelSelector))
        assertTrue(report.contains("reasoningEffort"))
        assertTrue(report.contains("medium"))
        assertTrue(report.contains("requestFingerprints"))
        assertTrue(report.contains("selectionPrecision"))
        assertTrue(report.contains("\"range\": 0.0"))
        assertFalse(report.contains(fixture.modelInput.userTurn))
        assertFalse(fixture.modelInput.transactions.any { report.contains(it.id) })
        assertFalse(fixture.modelInput.transactions.any { it.merchant.isNotBlank() && report.contains(it.merchant) })
        assertFalse(report.contains("rawUsage"))
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
