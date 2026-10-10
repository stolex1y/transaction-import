package io.github.stolex1y.transactionimport.web.benchmark

import io.github.stolex1y.transactionimport.web.McpPreview
import io.github.stolex1y.transactionimport.web.McpPreviewTransaction
import io.github.stolex1y.transactionimport.core.ChatCompletionGateway
import io.github.stolex1y.transactionimport.core.ChatCompletionRequest
import io.github.stolex1y.transactionimport.core.ChatCompletionResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportBenchmarkRunnerTest {
    @Test
    fun offlineRunUsesCompleteReviewedDatasetAndCleansTemporaryState() = runBlocking {
        val fixture = ImportBenchmarkFixtureLoader.loadBundled()
        val previews = mutableListOf<ImportBenchmarkPreviewSummary>()
        val output = mutableListOf<String>()
        val workDirectories = mutableListOf<Path>()
        val runner = ImportBenchmarkRunner(
            fixture = fixture,
            gatewayForRun = { repetition ->
                assertEquals(repetition, workDirectories.size)
                assertTrue(Files.exists(workDirectories[repetition - 1].resolve("benchmark.sqlite")))
                OfflineBenchmarkGateway(fixture)
            },
            confirmPreview = { repetition, summary ->
                assertEquals(repetition, previews.size + 1)
                previews += summary
                true
            },
            previewTransactionSourceRefs = { native, sessionId, revision, previewId ->
                val refs = native.previewTransactionSourceRefs(sessionId, revision, previewId)
                assertEquals(fixture.modelInput.transactions.map { it.id }.toSet(), refs?.filterNotNull()?.toSet())
                assertNull(native.previewTransactionSourceRefs("other-session", revision, previewId))
                assertNull(native.previewTransactionSourceRefs(sessionId, revision + 1, previewId))
                assertNull(native.previewTransactionSourceRefs(sessionId, revision, "other-preview"))
                refs
            },
            workDirectoryFactory = {
                Files.createTempDirectory("import-benchmark-runner-test-").also(workDirectories::add)
            },
            emit = output::add,
        )

        val results = runner.run(fixture.repetitions)

        assertEquals(3, results.size)
        assertEquals(3, previews.size)
        previews.forEach { preview ->
            assertEquals(50, preview.transactionCount)
        }
        results.forEach { result ->
            assertEquals(50, result.categoryLabelCount)
            assertEquals(23, result.categoryApplicableLabelCount)
            assertEquals(50, result.receiptLabelCount)
            assertEquals(
                50,
                result.matchedReceiptCount + result.ambiguousReceiptCount + result.unmatchedReceiptCount,
            )
        }
        assertEquals(3, workDirectories.distinct().size)
        assertTrue(workDirectories.all { !Files.exists(it) })
        assertEquals(3, output.size)
        output.forEachIndexed { index, line ->
            assertTrue(line.contains("Повтор ${index + 1}: preview"))
            assertFalse(line.contains("89990000000"))
        }
    }

    @Test
    fun runOutcomesContinuesAfterRepeatLocalFailureAndReportsAllAttempts() = runBlocking {
        val fixture = ImportBenchmarkFixtureLoader.loadBundled()
        val workDirectories = mutableListOf<Path>()
        var confirmations = 0
        var previewLookups = 0
        val runner = ImportBenchmarkRunner(
            fixture = fixture,
            gatewayForRun = { repetition ->
                assertEquals(repetition, workDirectories.size)
                assertTrue(Files.exists(workDirectories[repetition - 1].resolve("benchmark.sqlite")))
                OfflineBenchmarkGateway(fixture)
            },
            confirmPreview = { _, _ ->
                confirmations += 1
                true
            },
            previewTransactionSourceRefs = { native, sessionId, revision, previewId ->
                previewLookups += 1
                if (previewLookups == 1) {
                    emptyList()
                } else {
                    native.previewTransactionSourceRefs(sessionId, revision, previewId)
                }
            },
            workDirectoryFactory = {
                Files.createTempDirectory("import-benchmark-outcomes-test-").also(workDirectories::add)
            },
        )

        val outcomes = runner.runOutcomes(fixture.repetitions)

        assertEquals(3, outcomes.size)
        assertEquals(
            ImportBenchmarkOutcome.Failed(ImportBenchmarkFailureCode.INVALID_PREVIEW),
            outcomes.first(),
        )
        assertTrue(outcomes.drop(1).all { it is ImportBenchmarkOutcome.Scored })
        assertEquals(2, confirmations)
        assertEquals(3, workDirectories.size)
        assertTrue(workDirectories.all { !Files.exists(it) })
    }

    @Test
    fun fatalModelMismatchStopsOutcomeCollection() = runBlocking {
        val fixture = ImportBenchmarkFixtureLoader.loadBundled()
        val workDirectories = mutableListOf<Path>()
        var gatewayCreations = 0
        var mismatchedRequests = 0
        val runner = ImportBenchmarkRunner(
            fixture = fixture,
            gatewayForRun = {
                gatewayCreations += 1
                val offline = OfflineBenchmarkGateway(fixture)
                object : ChatCompletionGateway {
                    override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
                        if (request.tools.orEmpty().isNotEmpty()) {
                            mismatchedRequests += 1
                            throw OmpGatewayModelMismatchException("gpt-6-luna:medium")
                        }
                        return offline.complete(request)
                    }
                }
            },
            confirmPreview = { _, _ -> true },
            workDirectoryFactory = {
                Files.createTempDirectory("import-benchmark-fatal-outcome-test-").also(workDirectories::add)
            },
        )

        val failure = assertFailsWith<ImportBenchmarkModelRequestException> {
            runner.runOutcomes(fixture.repetitions)
        }

        assertTrue(failure.fatal)
        assertEquals(1, gatewayCreations)
        assertEquals(1, mismatchedRequests)
        assertEquals(1, workDirectories.size)
        assertTrue(workDirectories.all { !Files.exists(it) })
    }

    @Test
    fun runOutcomesContinuesAfterProviderRequestFailure() = runBlocking {
        val fixture = ImportBenchmarkFixtureLoader.loadBundled()
        val workDirectories = mutableListOf<Path>()
        val rawFailureMarker = "SENSITIVE_PROVIDER_FAILURE_MARKER"
        val runner = ImportBenchmarkRunner(
            fixture = fixture,
            gatewayForRun = { repetition ->
                if (repetition == 1) {
                    object : ChatCompletionGateway {
                        override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
                            throw IllegalStateException(rawFailureMarker)
                        }
                    }
                } else {
                    OfflineBenchmarkGateway(fixture)
                }
            },
            confirmPreview = { _, _ -> true },
            workDirectoryFactory = {
                Files.createTempDirectory("import-benchmark-provider-outcome-test-").also(workDirectories::add)
            },
        )

        val outcomes = runner.runOutcomes(fixture.repetitions)

        assertEquals(3, outcomes.size)
        assertEquals(
            ImportBenchmarkOutcome.Failed(ImportBenchmarkFailureCode.PROVIDER_REQUEST_FAILED),
            outcomes.first(),
        )
        assertTrue(outcomes.drop(1).all { it is ImportBenchmarkOutcome.Scored })
        assertFalse(outcomes.toString().contains(rawFailureMarker))
        assertEquals(3, workDirectories.size)
        assertTrue(workDirectories.all { !Files.exists(it) })
    }

    @Test
    fun externalCancellationStopsOutcomeCollectionAndCleansTemporaryDatabase() = runBlocking {
        val fixture = ImportBenchmarkFixtureLoader.loadBundled()
        val workDirectories = mutableListOf<Path>()
        val firstCompletionStarted = CompletableDeferred<Unit>()
        var gatewayCreations = 0
        val runner = ImportBenchmarkRunner(
            fixture = fixture,
            gatewayForRun = {
                gatewayCreations += 1
                object : ChatCompletionGateway {
                    override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
                        firstCompletionStarted.complete(Unit)
                        awaitCancellation()
                    }
                }
            },
            confirmPreview = { _, _ -> true },
            workDirectoryFactory = {
                Files.createTempDirectory("import-benchmark-external-cancel-test-").also(workDirectories::add)
            },
        )

        val job = launch { runner.runOutcomes(fixture.repetitions) }
        withTimeout(5_000) { firstCompletionStarted.await() }
        job.cancelAndJoin()

        assertEquals(1, gatewayCreations)
        assertEquals(1, workDirectories.size)
        assertTrue(workDirectories.all { !Files.exists(it) })
    }


    @Test
    fun fatalModelMismatchDuringInclusionReviewStopsOutcomeCollection() = runBlocking {
        val fixture = ImportBenchmarkFixtureLoader.loadBundled()
        val workDirectories = mutableListOf<Path>()
        var gatewayCreations = 0
        var mismatchedRequests = 0
        var confirmations = 0
        val runner = ImportBenchmarkRunner(
            fixture = fixture,
            gatewayForRun = {
                gatewayCreations += 1
                val offline = OfflineBenchmarkGateway(fixture)
                object : ChatCompletionGateway {
                    override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
                        val inclusionReview = request.messages.any {
                            it.role == "user" && it.content.startsWith("Новые операции после принятия MCP preview:")
                        }
                        if (inclusionReview) {
                            mismatchedRequests += 1
                            throw OmpGatewayModelMismatchException("gpt-6-luna:medium")
                        }
                        return offline.complete(request)
                    }
                }
            },
            confirmPreview = { _, _ ->
                confirmations += 1
                true
            },
            workDirectoryFactory = {
                Files.createTempDirectory("import-benchmark-fatal-inclusion-test-").also(workDirectories::add)
            },
            deleteWorkDirectory = { path ->
                deleteTestTree(path)
                throw IllegalStateException("synthetic cleanup failure")
            },
        )

        val failure = assertFailsWith<ImportBenchmarkModelRequestException> {
            runner.runOutcomes(fixture.repetitions)
        }

        assertTrue(failure.fatal)
        assertEquals(1, failure.suppressed.size)
        assertEquals(1, gatewayCreations)
        assertEquals(1, mismatchedRequests)
        assertEquals(1, confirmations)
        assertEquals(1, workDirectories.size)
        assertTrue(workDirectories.all { !Files.exists(it) })
    }

    @Test
    fun preflightFailureStopsOutcomeCollection() = runBlocking {
        val fixture = ImportBenchmarkFixtureLoader.loadBundled()
        val workDirectories = mutableListOf<Path>()
        var gatewayCreations = 0
        val runner = ImportBenchmarkRunner(
            fixture = fixture,
            gatewayForRun = {
                gatewayCreations += 1
                throw OmpGatewayPreflightException("creating temporary fallback config")
            },
            confirmPreview = { _, _ -> true },
            workDirectoryFactory = {
                Files.createTempDirectory("import-benchmark-preflight-outcome-test-").also(workDirectories::add)
            },
        )

        val failure = assertFailsWith<OmpGatewayPreflightException> {
            runner.runOutcomes(fixture.repetitions)
        }

        assertEquals("creating temporary fallback config", failure.stage)
        assertEquals(1, gatewayCreations)
        assertEquals(1, workDirectories.size)
        assertTrue(workDirectories.all { !Files.exists(it) })
    }

    @Test
    fun temporaryDatabaseCleanupFailureStopsFurtherRepeats() = runBlocking {
        val fixture = ImportBenchmarkFixtureLoader.loadBundled()
        val workDirectories = mutableListOf<Path>()
        var gatewayCreations = 0
        val rawCleanupMarker = "RAW_CLEANUP_FAILURE_MARKER"
        val runner = ImportBenchmarkRunner(
            fixture = fixture,
            gatewayForRun = {
                gatewayCreations += 1
                OfflineBenchmarkGateway(fixture)
            },
            confirmPreview = { _, _ -> true },
            workDirectoryFactory = {
                Files.createTempDirectory("import-benchmark-cleanup-outcome-test-").also(workDirectories::add)
            },
            deleteWorkDirectory = { path ->
                deleteTestTree(path)
                throw IllegalStateException(rawCleanupMarker)
            },
        )

        val failure = assertFailsWith<BenchmarkCleanupException> {
            runner.runOutcomes(fixture.repetitions)
        }

        assertEquals(1, gatewayCreations)
        assertEquals(1, workDirectories.size)
        assertTrue(workDirectories.all { !Files.exists(it) })
        assertFalse(failure.message.orEmpty().contains(rawCleanupMarker))
    }

    @Test
    fun evaluationOnlyCategorySentinelNeverEntersProviderRequests() = runBlocking {
        val approved = ImportBenchmarkFixtureLoader.loadBundled()
        val sentinel = "EVALUATION_ONLY_SENTINEL_6F9D2C"
        val labelledOperation = approved.evaluationOnly.categories.keys.first()
        val fixture = approved.copy(
            evaluationOnly = approved.evaluationOnly.copy(
                categories = approved.evaluationOnly.categories + (labelledOperation to sentinel),
            ),
        )
        val requests = mutableListOf<ChatCompletionRequest>()
        val workDirectories = mutableListOf<Path>()
        var acceptedPreviews = 0
        val runner = ImportBenchmarkRunner(
            fixture = fixture,
            gatewayForRun = { recordingOfflineGateway(fixture) { requests += it } },
            confirmPreview = { _, _ ->
                acceptedPreviews += 1
                true
            },
            workDirectoryFactory = {
                Files.createTempDirectory("import-benchmark-sentinel-test-").also(workDirectories::add)
            },
        )

        val failure = assertFailsWith<IllegalStateException> { runner.run(1) }

        assertTrue(failure.message.orEmpty().contains("category label"))
        assertEquals(1, acceptedPreviews)
        assertTrue(requests.isNotEmpty())
        assertTrue(requests.none { it.toString().contains(sentinel) })
        assertEquals(1, workDirectories.distinct().size)
        assertTrue(workDirectories.all { !Files.exists(it) })
    }

    @Test
    fun decliningPreviewDoesNotStartInclusionReview() = runBlocking {
        val fixture = ImportBenchmarkFixtureLoader.loadBundled()
        var inclusionRequests = 0
        val runner = ImportBenchmarkRunner(
            fixture = fixture,
            gatewayForRun = {
                recordingOfflineGateway(fixture) { request ->
                    if (request.messages.any { it.content.startsWith("Новые операции после принятия MCP preview:") }) {
                        inclusionRequests += 1
                    }
                }
            },
            confirmPreview = { _, _ -> false },
        )

        assertFailsWith<ImportBenchmarkCancelledException> { runner.run(1) }
        assertEquals(0, inclusionRequests)
    }

    @Test
    fun receiptSearchSourceFailureSavesOnlyValidatedPreviewAndAbortsBeforeConfirmation() = runBlocking {
        val approved = ImportBenchmarkFixtureLoader.loadBundled()
        val fixture = approved.copy(
            modelInput = approved.modelInput.copy(
                receiptSummaries = approved.modelInput.receiptSummaries.mapIndexed { index, summary ->
                    if (index == 0) summary.copy(receivedAt = "not-a-date") else summary
                },
            ),
        )
        val workDirectories = mutableListOf<Path>()
        val artifactDirectory = Files.createTempDirectory("import-benchmark-source-error-artifact-test-")
        val output = mutableListOf<String>()
        var gatewayCreations = 0
        var confirmations = 0
        try {
            val writer = ImportBenchmarkDraftArtifactWriter(
                fixture = fixture,
                directory = artifactDirectory,
                fileNameForRepetition = { "draft-repeat-$it.json" },
            )
            val runner = ImportBenchmarkRunner(
                fixture = fixture,
                gatewayForRun = { repetition ->
                    assertEquals(repetition, workDirectories.size)
                    assertTrue(Files.exists(workDirectories[repetition - 1].resolve("benchmark.sqlite")))
                    gatewayCreations += 1
                    OfflineBenchmarkGateway(fixture)
                },
                confirmPreview = { _, _ ->
                    confirmations += 1
                    true
                },
                workDirectoryFactory = {
                    Files.createTempDirectory("import-benchmark-source-error-test-").also(workDirectories::add)
                },
                emit = output::add,
                draftArtifactWriter = writer,
            )

            assertFailsWith<ImportBenchmarkReceiptSourceException> {
                runner.run(fixture.repetitions)
            }

            assertEquals(1, gatewayCreations)
            assertEquals(0, confirmations)
            assertTrue(output.isEmpty())
            assertEquals(1, workDirectories.size)
            assertTrue(workDirectories.all { !Files.exists(it) })

            val artifactText = Files.readString(artifactDirectory.resolve("draft-repeat-1.json"))
            val artifact = Json.parseToJsonElement(artifactText).jsonObject
            assertEquals("2", artifact.getValue("schemaVersion").jsonPrimitive.content)
            assertEquals("failed", artifact.getValue("status").jsonPrimitive.content)
            assertEquals("receipt_source_failed", artifact.getValue("failureCode").jsonPrimitive.content)
            assertEquals(JsonNull, artifact.getValue("draft"))
            val preview = artifact.getValue("preview").jsonObject
            assertEquals("source_error", preview.getValue("status").jsonPrimitive.content)
            assertEquals("false", preview.getValue("confirmed").jsonPrimitive.content)
            val rows = preview.getValue("rows").jsonObject
            assertEquals(fixture.modelInput.transactions.map { it.id }.toSet(), rows.keys)
            assertEquals(50, rows.size)

            val firstId = fixture.modelInput.transactions.first().id
            val firstRow = rows.getValue(firstId).jsonObject
            val actual = firstRow.getValue("actual").jsonObject
            val expected = firstRow.getValue("expected").jsonObject
            val comparisons = firstRow.getValue("comparisons").jsonObject
            assertTrue("merchant" in actual)
            assertTrue("categoryId" in actual)
            assertFalse("sourceRef" in actual)
            assertEquals(
                fixture.evaluationOnly.selection.getValue(firstId).toString(),
                expected.getValue("selection").jsonPrimitive.content,
            )
            assertEquals("not_finalized", comparisons.getValue("selection").jsonPrimitive.content)
            assertEquals("not_scored", comparisons.getValue("receiptAssociation").jsonPrimitive.content)
            assertTrue(comparisons.getValue("category").jsonPrimitive.content in setOf("matched", "mismatched"))

            assertFalse(artifactText.contains(fixture.modelInput.userTurn))
            assertFalse(artifactText.contains("messages"))
            assertFalse(artifactText.contains("rawResponse"))
            fixture.modelInput.receiptSummaries.forEach { receipt ->
                assertFalse(artifactText.contains("\"${receipt.id}\""))
            }
            fixture.modelInput.receiptDetails.keys.forEach { receiptId ->
                assertFalse(artifactText.contains("\"$receiptId\""))
            }
        } finally {
            deleteTestTree(artifactDirectory)
        }
    }

    @Test
    fun invalidPreviewIdentityDoesNotSavePreviewArtifact() = runBlocking {
        val fixture = ImportBenchmarkFixtureLoader.loadBundled()
        val expectedIds = fixture.modelInput.transactions.map { it.id }
        val invalidIds = expectedIds.dropLast(1) + "TX_UNEXPECTED"
        val artifactDirectory = Files.createTempDirectory("import-benchmark-invalid-preview-artifact-test-")
        var confirmations = 0
        try {
            val writer = ImportBenchmarkDraftArtifactWriter(
                fixture = fixture,
                directory = artifactDirectory,
                fileNameForRepetition = { "draft-repeat-$it.json" },
            )
            val runner = ImportBenchmarkRunner(
                fixture = fixture,
                gatewayForRun = { OfflineBenchmarkGateway(fixture) },
                confirmPreview = { _, _ ->
                    confirmations += 1
                    true
                },
                previewTransactionSourceRefs = { _, _, _, _ -> invalidIds },
                draftArtifactWriter = writer,
            )

            assertEquals(
                ImportBenchmarkOutcome.Failed(ImportBenchmarkFailureCode.INVALID_PREVIEW),
                runner.runOutcomes(1).single(),
            )
            assertEquals(0, confirmations)

            val artifact = Json.parseToJsonElement(
                Files.readString(artifactDirectory.resolve("draft-repeat-1.json")),
            ).jsonObject
            assertEquals(JsonNull, artifact.getValue("draft"))
            assertFalse("preview" in artifact)
        } finally {
            deleteTestTree(artifactDirectory)
        }
    }

    @Test
    fun invalidPreviewSourceIdentitySetsAbortBeforeConfirmationAndCleanTemporaryDatabase() = runBlocking {
        val fixture = ImportBenchmarkFixtureLoader.loadBundled()
        val expectedIds = fixture.modelInput.transactions.map { it.id }
        val invalidIdentitySets: List<List<String?>> = listOf(
            expectedIds.dropLast(1),
            expectedIds.dropLast(1) + "TX_UNEXPECTED",
            expectedIds.dropLast(1) + expectedIds.first(),
            expectedIds.dropLast(1) + null,
            expectedIds.dropLast(1) + "",
        )

        invalidIdentitySets.forEach { invalidIds ->
            val workDirectories = mutableListOf<Path>()
            var confirmations = 0
            val runner = ImportBenchmarkRunner(
                fixture = fixture,
                gatewayForRun = { OfflineBenchmarkGateway(fixture) },
                confirmPreview = { _, _ ->
                    confirmations += 1
                    true
                },
                previewTransactionSourceRefs = { _, _, _, _ -> invalidIds },
                workDirectoryFactory = {
                    Files.createTempDirectory("import-benchmark-invalid-identities-").also(workDirectories::add)
                },
            )

            val failure = assertFailsWith<IllegalArgumentException> { runner.run(1) }

            assertTrue(failure.message.orEmpty().contains("identities"))
            assertEquals(0, confirmations)
            assertEquals(1, workDirectories.size)
            assertTrue(workDirectories.all { !Files.exists(it) })
        }
    }

    @Test
    fun previewValidatorRejectsWrongCountDraftMutationAndBadIdentitySet() {
        val fixture = ImportBenchmarkFixtureLoader.loadBundled()
        val expectedIds = fixture.modelInput.transactions.map { it.id }
        val expectedSourceRefs = expectedIds.toSet()
        val preview = McpPreview(
            id = "safe-preview",
            sessionRevision = 1,
            transactions = fixture.modelInput.transactions.map { transaction ->
                McpPreviewTransaction(
                    occurredAt = transaction.occurredAt,
                    amountMinor = transaction.signedAmountMinor,
                    currency = transaction.currency,
                    merchant = transaction.merchant,
                    description = transaction.description,
                )
            },
        )

        assertFailsWith<IllegalArgumentException> {
            requireValidBenchmarkPreview(
                preview = preview.copy(transactions = preview.transactions.dropLast(1)),
                draftIsPresent = false,
                expectedSourceRefs = expectedSourceRefs,
                previewSourceRefs = expectedIds,
            )
        }
        assertFailsWith<IllegalStateException> {
            requireValidBenchmarkPreview(
                preview = preview,
                draftIsPresent = true,
                expectedSourceRefs = expectedSourceRefs,
                previewSourceRefs = expectedIds,
            )
        }
        listOf(
            expectedIds.dropLast(1),
            expectedIds.dropLast(1) + "TX_UNEXPECTED",
            expectedIds.dropLast(1) + expectedIds.first(),
        ).forEach { invalidIds ->
            assertFailsWith<IllegalArgumentException> {
                requireValidBenchmarkPreview(
                    preview = preview,
                    draftIsPresent = false,
                    expectedSourceRefs = expectedSourceRefs,
                    previewSourceRefs = invalidIds,
                )
            }
        }
    }

    @Test
    fun providerErrorAtClassifierAbortsRun() = runBlocking {
        val fixture = ImportBenchmarkFixtureLoader.loadBundled()

        assertProviderFailureStopsRun(fixture) { request ->
            request.messages.any { it.content.contains("Операции для обогащения:") }
        }
    }

    @Test
    fun providerHttpStatusIsReportedWithoutRawBody() = runBlocking {
        val fixture = ImportBenchmarkFixtureLoader.loadBundled()

        assertProviderFailureStopsRun(
            fixture = fixture,
            providerError = OmpGatewayHttpStatusException(401),
            expectedSafeSummary = "OMP returned HTTP 401",
            expectedStage = "tool-planning",
            failWhen = { it.tools.orEmpty().isNotEmpty() },
        )
    }

    @Test
    fun knownOmpFailureCategoryIsReportedWithoutRawResponse() = runBlocking {
        val fixture = ImportBenchmarkFixtureLoader.loadBundled()

        assertProviderFailureStopsRun(
            fixture = fixture,
            providerError = IllegalStateException(
                "OMP stdio gateway response named an unexpected model; benchmark stopped.",
            ),
            expectedSafeSummary = "OMP stdio gateway response named an unexpected model",
            expectedStage = "tool-planning",
            failWhen = { it.tools.orEmpty().isNotEmpty() },
        )
    }

    @Test
    fun modelIdentitySummaryUsesOnlyAllowlistedResponseIds() = runBlocking {
        val fixture = ImportBenchmarkFixtureLoader.loadBundled()
        val failDuringPlanning: (ChatCompletionRequest) -> Boolean = { it.tools.orEmpty().isNotEmpty() }

        assertProviderFailureStopsRun(
            fixture = fixture,
            providerError = OmpGatewayModelMismatchException("gpt-6-luna:medium"),
            expectedSafeSummary = "OMP response model ID gpt-6-luna:medium did not match expected catalog ID gpt-6-luna",
            expectedStage = "tool-planning",
            failWhen = failDuringPlanning,
        )
        assertProviderFailureStopsRun(
            fixture = fixture,
            providerError = OmpGatewayModelMismatchException(null),
            expectedSafeSummary = "OMP response model ID was not allowlisted; raw response omitted",
            expectedStage = "tool-planning",
            failWhen = failDuringPlanning,
        )
        assertProviderFailureStopsRun(
            fixture = fixture,
            providerError = OmpGatewayDiagnosticCompletedException("openai-codex/gpt-6-luna"),
            expectedSafeSummary =
                "One-completion diagnostic matched base response alias openai-codex/gpt-6-luna; scoring stopped by design",
            expectedStage = "tool-planning",
            failWhen = failDuringPlanning,
        )
        val rawMarker = "gpt-6-luna-SECRET_MARKER"
        assertProviderFailureStopsRun(
            fixture = fixture,
            providerError = OmpGatewayDiagnosticCompletedException(rawMarker),
            expectedSafeSummary =
                "One-completion diagnostic completed; response model ID omitted; scoring stopped by design",
            expectedStage = "tool-planning",
            rawMarker = rawMarker,
            failWhen = failDuringPlanning,
        )
    }

    @Test
    fun unknownOmpFailureMessageUsesGenericRedaction() = runBlocking {
        val fixture = ImportBenchmarkFixtureLoader.loadBundled()
        val rawMarker = "OMP stdio gateway unknown: RAW_PROVIDER_ERROR_MARKER"

        assertProviderFailureStopsRun(
            fixture = fixture,
            providerError = IllegalStateException(rawMarker),
            expectedSafeSummary = "provider request failed; raw details omitted",
            expectedStage = "tool-planning",
            rawMarker = rawMarker,
            failWhen = { it.tools.orEmpty().isNotEmpty() },
        )
    }

    private suspend fun assertProviderFailureStopsRun(
        fixture: ImportBenchmarkFixture,
        providerError: Exception = IllegalStateException("synthetic provider failure"),
        expectedSafeSummary: String? = null,
        expectedStage: String? = null,
        rawMarker: String? = null,
        failWhen: (ChatCompletionRequest) -> Boolean,
    ) {
        val workDirectories = mutableListOf<Path>()
        val output = mutableListOf<String>()
        var gatewayCreations = 0
        var failureTriggered = false
        var callsAfterFailure = 0
        var confirmations = 0
        val runner = ImportBenchmarkRunner(
            fixture = fixture,
            gatewayForRun = { repetition ->
                gatewayCreations += 1
                assertEquals(repetition, gatewayCreations)
                val offline = OfflineBenchmarkGateway(fixture)
                object : ChatCompletionGateway {
                    override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
                        if (failureTriggered) {
                            callsAfterFailure += 1
                            throw providerError
                        }
                        if (failWhen(request)) {
                            failureTriggered = true
                            throw providerError
                        }
                        return offline.complete(request)
                    }
                }
            },
            confirmPreview = { _, _ ->
                confirmations += 1
                true
            },
            workDirectoryFactory = {
                Files.createTempDirectory("import-benchmark-provider-error-test-").also(workDirectories::add)
            },
            emit = output::add,
        )

        val failure = assertFailsWith<ImportBenchmarkModelRequestException> {
            runner.run(fixture.repetitions)
        }

        assertTrue(failureTriggered)
        expectedSafeSummary?.let { assertTrue(failure.message.orEmpty().contains(it)) }
        expectedStage?.let { assertEquals(it, failure.stage) }
        assertFalse(failure.message.orEmpty().contains("synthetic provider failure"))
        rawMarker?.let { assertFalse(failure.message.orEmpty().contains(it)) }
        assertEquals(1, gatewayCreations)
        assertEquals(0, callsAfterFailure)
        assertEquals(0, confirmations)
        assertTrue(output.isEmpty())
        assertEquals(1, workDirectories.size)
        assertTrue(workDirectories.all { !Files.exists(it) })
    }

    private fun recordingOfflineGateway(
        fixture: ImportBenchmarkFixture,
        onRequest: (ChatCompletionRequest) -> Unit = {},
    ): ChatCompletionGateway {
        val delegate = OfflineBenchmarkGateway(fixture)
        return object : ChatCompletionGateway {
            override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
                onRequest(request)
                return delegate.complete(request)
            }
        }
    }

    @Test
    fun providerCancellationWhileCallerIsActiveFailsClosed() = runBlocking {
        val fixture = ImportBenchmarkFixtureLoader.loadBundled()
        val workDirectories = mutableListOf<Path>()
        var gatewayCreations = 0
        var providerFailureThrown = false
        var underlyingCallsAfterFailure = 0
        var confirmations = 0
        val runner = ImportBenchmarkRunner(
            fixture = fixture,
            gatewayForRun = { repetition ->
                gatewayCreations += 1
                assertEquals(repetition, gatewayCreations)
                val offline = OfflineBenchmarkGateway(fixture)
                object : ChatCompletionGateway {
                    override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
                        if (providerFailureThrown) {
                            underlyingCallsAfterFailure += 1
                            throw IllegalStateException("unexpected provider call after cancellation")
                        }
                        if (request.messages.lastOrNull { it.role == "user" }?.content
                                ?.trimStart()
                                ?.contains("Операции для обогащения:") == true
                        ) {
                            providerFailureThrown = true
                            throw CancellationException("synthetic provider timeout")
                        }
                        return offline.complete(request)
                    }
                }
            },
            confirmPreview = { _, _ ->
                confirmations += 1
                true
            },
            workDirectoryFactory = {
                Files.createTempDirectory("import-benchmark-cancellation-test-").also(workDirectories::add)
            },
        )

        assertFailsWith<ImportBenchmarkModelRequestException> {
            runner.run(fixture.repetitions)
        }

        assertTrue(providerFailureThrown)
        assertEquals(0, underlyingCallsAfterFailure)
        assertEquals(0, confirmations)
        assertEquals(1, workDirectories.size)
        assertTrue(workDirectories.all { !Files.exists(it) })
    }

    private fun deleteTestTree(path: Path) {
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

}
