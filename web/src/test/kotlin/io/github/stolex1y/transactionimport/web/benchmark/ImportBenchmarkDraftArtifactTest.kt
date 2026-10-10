package io.github.stolex1y.transactionimport.web.benchmark

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportBenchmarkDraftArtifactTest {
    @Test
    fun offlineRepeatWritesAllRowsWithScrubbedIdentityAndExplicitNoCategoryFields() = runBlocking {
        val fixture = ImportBenchmarkFixtureLoader.loadBundled()
        val directory = Files.createTempDirectory("import-benchmark-draft-artifact-test-")
        val output = mutableListOf<String>()
        try {
            val writer = ImportBenchmarkDraftArtifactWriter(
                fixture = fixture,
                directory = directory,
                fileNameForRepetition = { "draft-repeat-$it.json" },
                emit = output::add,
            )
            val runner = ImportBenchmarkRunner(
                fixture = fixture,
                gatewayForRun = { OfflineBenchmarkGateway(fixture) },
                confirmPreview = { _, _ -> true },
                draftArtifactWriter = writer,
            )

            val outcome = runner.runOutcomes(1).single()

            assertTrue(outcome is ImportBenchmarkOutcome.Scored)
            val path = directory.resolve("draft-repeat-1.json")
            assertTrue(Files.exists(path))
            assertEquals(1, output.size)
            assertTrue(output.single().contains(path.fileName.toString()))
            val text = Files.readString(path)
            val root = Json.parseToJsonElement(text).jsonObject
            val draft = root.getValue("draft").jsonObject
            val rows = draft.getValue("rows").jsonObject
            assertEquals(50, rows.size)
            assertEquals("scored", root.getValue("status").jsonPrimitive.content)
            assertEquals("ready", draft.getValue("status").jsonPrimitive.content)

            val firstOperation = fixture.modelInput.transactions.first()
            val row = rows.getValue(firstOperation.id).jsonObject
            val actual = row.getValue("actual").jsonObject
            val expected = row.getValue("expected").jsonObject
            val comparisons = row.getValue("comparisons").jsonObject
            val expectedSelection = fixture.evaluationOnly.selection.getValue(firstOperation.id)
            val actualIncluded = actual.getValue("included").jsonPrimitive.content.toBoolean()
            assertEquals(firstOperation.direction, actual.getValue("direction").jsonPrimitive.content)
            assertTrue("merchant" in actual)
            assertTrue("items" in actual)
            assertFalse("sourceRef" in actual)
            assertEquals(expectedSelection, expected.getValue("selection").jsonPrimitive.content.toBoolean())
            assertEquals(
                if (actualIncluded == expectedSelection) "matched" else "mismatched",
                comparisons.getValue("selection").jsonPrimitive.content,
            )
            assertTrue("receiptAssociation" in expected)

            val noCategoryId = fixture.evaluationOnly.categories.entries.first { it.value == null }.key
            val noCategoryRow = rows.getValue(noCategoryId).jsonObject
            val expectedNoCategory = noCategoryRow.getValue("expected").jsonObject.getValue("category")
            assertEquals(JsonNull, expectedNoCategory)
            val actualCategoryId = noCategoryRow.getValue("actual").jsonObject.getValue("categoryId")
            assertEquals(
                if (actualCategoryId == JsonNull) "matched" else "mismatched",
                noCategoryRow.getValue("comparisons").jsonObject.getValue("category").jsonPrimitive.content,
            )

            assertFalse(text.contains(fixture.modelInput.userTurn))
            assertFalse(text.contains("messages"))
            assertFalse(text.contains("rawResponse"))
            fixture.modelInput.receiptSummaries.forEach { receipt ->
                assertFalse(text.contains("\"${receipt.id}\""))
            }
            fixture.modelInput.receiptDetails.keys.forEach { receiptId ->
                assertFalse(text.contains("\"$receiptId\""))
            }
        } finally {
            deleteTree(directory)
        }
    }

    @Test
    fun missingCategoryLabelIsUnlabeledAndExcludedFromCategoryScores() = runBlocking {
        val approvedFixture = ImportBenchmarkFixtureLoader.loadBundled()
        val missingLabel = approvedFixture.evaluationOnly.categories.entries.first { it.value != null }.key
        val fixture = approvedFixture.copy(
            evaluationOnly = approvedFixture.evaluationOnly.copy(
                categories = approvedFixture.evaluationOnly.categories - missingLabel,
            ),
        )
        val directory = Files.createTempDirectory("import-benchmark-missing-label-test-")
        try {
            val writer = ImportBenchmarkDraftArtifactWriter(
                fixture = fixture,
                directory = directory,
                fileNameForRepetition = { "draft-repeat-$it.json" },
            )
            val runner = ImportBenchmarkRunner(
                fixture = fixture,
                gatewayForRun = { OfflineBenchmarkGateway(fixture) },
                confirmPreview = { _, _ -> true },
                draftArtifactWriter = writer,
            )

            val outcome = runner.runOutcomes(1).single()

            assertTrue(outcome is ImportBenchmarkOutcome.Scored)
            assertEquals(49, (outcome as ImportBenchmarkOutcome.Scored).metrics.categoryLabelCount)
            val root = Json.parseToJsonElement(Files.readString(directory.resolve("draft-repeat-1.json")))
                .jsonObject
            val row = root.getValue("draft").jsonObject.getValue("rows").jsonObject
                .getValue(missingLabel).jsonObject
            assertEquals(
                "unlabeled",
                row.getValue("expected").jsonObject.getValue("category")
                    .jsonObject.getValue("state").jsonPrimitive.content,
            )
            assertEquals(
                "unlabeled",
                row.getValue("comparisons").jsonObject.getValue("category").jsonPrimitive.content,
            )
        } finally {
            deleteTree(directory)
        }
    }

    @Test
    fun repeatFailureWritesSafeCodeAndNullDraftThenNextRepeatIsSaved() = runBlocking {
        val fixture = ImportBenchmarkFixtureLoader.loadBundled()
        val directory = Files.createTempDirectory("import-benchmark-draft-failure-test-")
        val rawFailure = "SENSITIVE_PROVIDER_RESPONSE_MARKER"
        try {
            val writer = ImportBenchmarkDraftArtifactWriter(
                fixture = fixture,
                directory = directory,
                fileNameForRepetition = { "draft-repeat-$it.json" },
            )
            val runner = ImportBenchmarkRunner(
                fixture = fixture,
                gatewayForRun = { repetition ->
                    if (repetition == 1) {
                        object : io.github.stolex1y.transactionimport.core.ChatCompletionGateway {
                            override suspend fun complete(
                                request: io.github.stolex1y.transactionimport.core.ChatCompletionRequest,
                            ): io.github.stolex1y.transactionimport.core.ChatCompletionResponse =
                                error(rawFailure)
                        }
                    } else {
                        OfflineBenchmarkGateway(fixture)
                    }
                },
                confirmPreview = { _, _ -> true },
                draftArtifactWriter = writer,
            )

            val outcomes = runner.runOutcomes(2)

            assertEquals(2, outcomes.size)
            assertEquals(
                ImportBenchmarkOutcome.Failed(ImportBenchmarkFailureCode.PROVIDER_REQUEST_FAILED),
                outcomes.first(),
            )
            val failedText = Files.readString(directory.resolve("draft-repeat-1.json"))
            val failed = Json.parseToJsonElement(failedText).jsonObject
            assertEquals("provider_request_failed", failed.getValue("failureCode").jsonPrimitive.content)
            assertTrue(failed.getValue("draft") is kotlinx.serialization.json.JsonNull)
            assertFalse(failedText.contains(rawFailure))

            val scored = Json.parseToJsonElement(Files.readString(directory.resolve("draft-repeat-2.json")))
                .jsonObject
            assertEquals(50, scored.getValue("draft").jsonObject.getValue("rows").jsonObject.size)
        } finally {
            deleteTree(directory)
        }
    }

    @Test
    fun fatalModelMismatchWritesFailureArtifactBeforeStopping() = runBlocking {
        val fixture = ImportBenchmarkFixtureLoader.loadBundled()
        val directory = Files.createTempDirectory("import-benchmark-draft-fatal-test-")
        try {
            val writer = ImportBenchmarkDraftArtifactWriter(
                fixture = fixture,
                directory = directory,
                fileNameForRepetition = { "draft-repeat-$it.json" },
            )
            val runner = ImportBenchmarkRunner(
                fixture = fixture,
                gatewayForRun = {
                    object : io.github.stolex1y.transactionimport.core.ChatCompletionGateway {
                        override suspend fun complete(
                            request: io.github.stolex1y.transactionimport.core.ChatCompletionRequest,
                        ): io.github.stolex1y.transactionimport.core.ChatCompletionResponse =
                            throw OmpGatewayModelMismatchException("gpt-6-luna:medium")
                    }
                },
                confirmPreview = { _, _ -> true },
                draftArtifactWriter = writer,
            )

            assertFailsWith<ImportBenchmarkModelRequestException> { runner.runOutcomes(3) }

            val text = Files.readString(directory.resolve("draft-repeat-1.json"))
            val artifact = Json.parseToJsonElement(text).jsonObject
            assertEquals("provider_request_failed", artifact.getValue("failureCode").jsonPrimitive.content)
            assertTrue(artifact.getValue("draft") is kotlinx.serialization.json.JsonNull)
            assertFalse(text.contains("gpt-6-luna:medium"))
            assertFalse(Files.exists(directory.resolve("draft-repeat-2.json")))
        } finally {
            deleteTree(directory)
        }
    }

    @Test
    fun createNewCollisionPreservesExistingArtifactAndRejectsOverwrite() {
        val fixture = ImportBenchmarkFixtureLoader.loadBundled()
        val directory = Files.createTempDirectory("import-benchmark-draft-collision-test-")
        try {
            val path = directory.resolve("repeat-1.json")
            Files.writeString(path, "existing artifact")
            val writer = ImportBenchmarkDraftArtifactWriter(
                fixture = fixture,
                directory = directory,
                fileNameForRepetition = { "repeat-$it.json" },
            )

            assertFailsWith<java.nio.file.FileAlreadyExistsException> {
                writer.write(
                    repetition = 1,
                    outcome = ImportBenchmarkOutcome.Failed(ImportBenchmarkFailureCode.INVALID_PREVIEW),
                    draft = null,
                    generatedAt = "2026-10-14T12:00:00Z",
                )
            }
            assertEquals("existing artifact", Files.readString(path))
        } finally {
            deleteTree(directory)
        }
    }

    @Test
    fun validatedDraftSurvivesLaterScoringFailure() = runBlocking {
        val approved = ImportBenchmarkFixtureLoader.loadBundled()
        val labeledTransaction = approved.evaluationOnly.categories.entries.first { it.value != null }.key
        val fixture = approved.copy(
            evaluationOnly = approved.evaluationOnly.copy(
                categories = approved.evaluationOnly.categories +
                    (labeledTransaction to "UNAVAILABLE_EXPECTED_CATEGORY"),
            ),
        )
        val directory = Files.createTempDirectory("import-benchmark-draft-partial-test-")
        try {
            val writer = ImportBenchmarkDraftArtifactWriter(
                fixture = fixture,
                directory = directory,
                fileNameForRepetition = { "repeat-$it.json" },
            )
            val runner = ImportBenchmarkRunner(
                fixture = fixture,
                gatewayForRun = { OfflineBenchmarkGateway(fixture) },
                confirmPreview = { _, _ -> true },
                draftArtifactWriter = writer,
            )

            val outcome = runner.runOutcomes(1).single()

            assertEquals(
                ImportBenchmarkOutcome.Failed(ImportBenchmarkFailureCode.SCORING_FAILED),
                outcome,
            )
            val failed = Json.parseToJsonElement(Files.readString(directory.resolve("repeat-1.json"))).jsonObject
            assertEquals("scoring_failed", failed.getValue("failureCode").jsonPrimitive.content)
            val rows = failed.getValue("draft").jsonObject.getValue("rows").jsonObject
            assertEquals(50, rows.size)
            assertEquals(
                "UNAVAILABLE_EXPECTED_CATEGORY",
                rows.getValue(labeledTransaction).jsonObject.getValue("expected").jsonObject
                    .getValue("category").jsonPrimitive.content,
            )
        } finally {
            deleteTree(directory)
        }
    }

    @Test
    fun approvedProviderRequestFingerprintRemainsPinned() = runBlocking {
        val fixture = ImportBenchmarkFixtureLoader.loadBundled()
        val runner = ImportBenchmarkRunner(
            fixture = fixture,
            gatewayForRun = {
                object : io.github.stolex1y.transactionimport.core.ChatCompletionGateway {
                    override suspend fun complete(
                        request: io.github.stolex1y.transactionimport.core.ChatCompletionRequest,
                    ): io.github.stolex1y.transactionimport.core.ChatCompletionResponse {
                        val body = OmpCodexRequestProjection.body(
                            request,
                            fixture.modelSelector,
                            fixture.reasoningEffort,
                        )
                        val compactJson = Json { encodeDefaults = true; explicitNulls = false }
                            .encodeToString(JsonObject.serializer(), body)
                        val fingerprint = MessageDigest.getInstance("SHA-256")
                            .digest(compactJson.toByteArray(StandardCharsets.UTF_8))
                            .joinToString("") { byte -> "%02x".format(byte) }
                        throw ImportBenchmarkPayloadPreviewException("", fingerprint)
                    }
                }
            },
            confirmPreview = { _, _ -> true },
        )

        val error = assertFailsWith<ImportBenchmarkPayloadPreviewException> { runner.run(1) }

        assertEquals(APPROVED_BENCHMARK_BODY_SHA256, error.fingerprint)
    }

    private fun deleteTree(path: Path) {
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
