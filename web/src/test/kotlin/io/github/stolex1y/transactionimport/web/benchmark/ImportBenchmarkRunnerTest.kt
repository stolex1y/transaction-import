package io.github.stolex1y.transactionimport.web.benchmark

import io.github.stolex1y.transactionimport.core.ChatCompletionGateway
import io.github.stolex1y.transactionimport.core.ChatCompletionRequest
import io.github.stolex1y.transactionimport.core.ChatCompletionResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ImportBenchmarkRunnerTest {
    @Test
    fun offlineRunUsesProductionImportPathAndScoresOnlyReviewedLabels() = runBlocking {
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
            assertEquals(42, preview.categorizedCount)
        }
        results.forEach { result ->
            assertEquals(0.52, result.selectionPrecision)
            assertEquals(1.0, result.selectionRecall)
            assertEquals(24, result.selectionFalsePositive)
            assertEquals(0, result.selectionFalseNegative)
            assertEquals(1.0, result.categoryAccuracy)
            assertEquals(1.0, result.categoryCoverage)
            assertEquals(0, result.receiptLabelCount)
            assertTrue(result.ambiguousReceiptCount > 0)
            assertTrue(result.matchedReceiptCount + result.ambiguousReceiptCount + result.unmatchedReceiptCount <= 50)
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
    fun receiptSourceFailureAbortsBeforeConfirmationAndCleansTemporaryDatabase() = runBlocking {
        val approved = ImportBenchmarkFixtureLoader.loadBundled()
        val fixture = approved.copy(
            modelInput = approved.modelInput.copy(receiptDetails = emptyMap()),
        )
        val workDirectories = mutableListOf<Path>()
        val output = mutableListOf<String>()
        var gatewayCreations = 0
        var confirmations = 0
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
        )

        assertFailsWith<ImportBenchmarkReceiptSourceException> {
            runner.run(fixture.repetitions)
        }

        assertEquals(1, gatewayCreations)
        assertEquals(0, confirmations)
        assertTrue(output.isEmpty())
        assertEquals(1, workDirectories.size)
        assertTrue(workDirectories.all { !Files.exists(it) })
    }

    @Test
    fun providerErrorsAtClassifierAndReceiptSelectorAbortTheRun() = runBlocking {
        val fixture = ImportBenchmarkFixtureLoader.loadBundled()

        assertProviderFailureStopsRun(fixture) { request ->
            request.messages.any { it.content.contains("Операции для обогащения:") }
        }
        assertProviderFailureStopsRun(fixture) { request ->
            request.messages.any { it.content.startsWith("Операция: дата=") }
        }
    }

    private suspend fun assertProviderFailureStopsRun(
        fixture: ImportBenchmarkFixture,
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
                            throw IllegalStateException("synthetic provider failure")
                        }
                        if (failWhen(request)) {
                            failureTriggered = true
                            throw IllegalStateException("synthetic provider failure")
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
        assertFalse(failure.message.orEmpty().contains("synthetic provider failure"))
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

}
