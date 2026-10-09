package io.github.stolex1y.transactionimport.web.benchmark

import io.github.stolex1y.transactionimport.core.AgentGatewayResolver
import io.github.stolex1y.transactionimport.core.AgentRuntimeConfig
import io.github.stolex1y.transactionimport.core.ChatCompletionGateway
import io.github.stolex1y.transactionimport.core.ChatCompletionRequest
import io.github.stolex1y.transactionimport.core.ChatCompletionResponse
import io.github.stolex1y.transactionimport.core.CategoryType
import io.github.stolex1y.transactionimport.core.ContextManagementConfig
import io.github.stolex1y.transactionimport.core.ContextStrategy
import io.github.stolex1y.transactionimport.core.DraftTransaction
import io.github.stolex1y.transactionimport.core.McpToolLoopConfig
import io.github.stolex1y.transactionimport.core.ProviderCatalog
import io.github.stolex1y.transactionimport.core.ProviderDefinition
import io.github.stolex1y.transactionimport.core.ProviderModelDefinition
import io.github.stolex1y.transactionimport.core.ReasoningModeDefinition
import io.github.stolex1y.transactionimport.core.ReceiptAssociationStatus
import io.github.stolex1y.transactionimport.core.SmartExpenseAgent
import io.github.stolex1y.transactionimport.core.TransactionCategory
import io.github.stolex1y.transactionimport.persistence.SqliteImportSessionRepository
import io.github.stolex1y.transactionimport.web.NativeMcpAgent
import io.github.stolex1y.transactionimport.web.NativeMcpDateContext
import io.github.stolex1y.transactionimport.web.NativeMcpHandlingResult
import io.github.stolex1y.transactionimport.web.McpPreview
import io.github.stolex1y.transactionimport.web.ReceiptMatchingStageStatus
import io.github.stolex1y.transactionimport.web.PreCompletionRequestAuthorizer

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import java.util.Comparator
import java.util.concurrent.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicInteger

internal data class ImportBenchmarkPreviewSummary(
    val transactionCount: Int,
    val categorizedCount: Int,
    val matchedReceiptCount: Int,
    val ambiguousReceiptCount: Int,
    val unmatchedReceiptCount: Int,
)

internal data class ImportBenchmarkMetrics(
    val selectionPrecision: Double?,
    val selectionRecall: Double,
    val selectionTruePositive: Int,
    val selectionFalsePositive: Int,
    val selectionFalseNegative: Int,
    val categoryAccuracy: Double,
    val categoryCoverage: Double,
    val categoryAccuracyWhenAssigned: Double?,
    val categoryCorrect: Int,
    val categoryAssigned: Int,
    val categoryLabelCount: Int,
    val matchedReceiptCount: Int,
    val ambiguousReceiptCount: Int,
    val unmatchedReceiptCount: Int,
    val receiptLabelCount: Int,
)

internal class ImportBenchmarkRunner(
    private val fixture: ImportBenchmarkFixture,
    private val gatewayForRun: suspend (Int) -> ChatCompletionGateway,
    private val confirmPreview: suspend (Int, ImportBenchmarkPreviewSummary) -> Boolean,
    private val workDirectoryFactory: () -> Path = { Files.createTempDirectory("import-benchmark-") },
    private val emit: (String) -> Unit = {},
) {
    suspend fun run(repetitions: Int): List<ImportBenchmarkMetrics> {
        require(repetitions in 1..fixture.repetitions) { "Benchmark repetition count is not approved." }
        return (1..repetitions).map { repetition -> runOne(repetition) }
    }

    private suspend fun runOne(repetition: Int): ImportBenchmarkMetrics {
        val workDirectory = workDirectoryFactory()
        try {
            val database = workDirectory.resolve("benchmark.sqlite")
            val repository = SqliteImportSessionRepository(database.toString())
            repository.saveLongTermMemory(fixture.modelInput.preferences)

            val catalog = benchmarkProviderCatalog()
            val loop = McpToolLoopConfig(
                enabled = true,
                maxIterations = 8,
                maxToolCalls = 8,
            )
            val runtime = AgentRuntimeConfig(
                defaultProviderId = BENCHMARK_PROVIDER,
                defaultModelId = BENCHMARK_MODEL,
                defaultReasoningModeId = DISABLED_REASONING,
                maxTokens = 4_096,
                defaultUserPrompt = "",
                contextManagement = ContextManagementConfig(
                    strategy = ContextStrategy.SLIDING_WINDOW,
                    recentMessages = 8,
                ),
                mcpToolLoop = loop,
            ).validated(catalog)
            val gateway = BenchmarkProviderFailureGuard(gatewayForRun(repetition))
            val ids = AtomicInteger()
            val agent = SmartExpenseAgent(
                repository = repository,
                gatewayResolver = AgentGatewayResolver { gateway },
                idGenerator = { "benchmark-${ids.incrementAndGet()}" },
                nowEpochMs = { 1_790_000_000_000L + ids.get() },
                runtimeConfig = runtime,
                configValidator = { catalog.resolve(it) },
            )
            val source = ImportBenchmarkReplay(fixture)
            val native = NativeMcpAgent(
                agent = agent,
                gatewayResolver = AgentGatewayResolver { gateway },
                mcpTools = source,
                runtimeConfig = runtime,
                idGenerator = { "benchmark-preview-${ids.incrementAndGet()}" },
                dateContextProvider = { NativeMcpDateContext("2026-09-30", "UTC") },
            )
            val session = agent.createSession(
                title = "Import benchmark repetition $repetition",
                config = runtime.defaultAgentConfig(),
                contextManagement = runtime.sessionContextManagement(),
            )
            val handlingResult = native.handle(
                sessionId = session.session.id,
                expectedRevision = session.session.revision,
                text = fixture.modelInput.userTurn,
            )
            gateway.throwIfFailed()
            val handled = handlingResult as? NativeMcpHandlingResult.Handled
                ?: throw IllegalStateException("Benchmark model did not produce an import preview.")
            val preview = handled.response.mcpPreview
                ?: throw IllegalStateException("Benchmark import preview is missing.")
            if (preview.receiptMatching?.status == ReceiptMatchingStageStatus.SOURCE_ERROR) {
                throw ImportBenchmarkReceiptSourceException()
            }
            require(preview.transactions.size == fixture.modelInput.transactions.size) {
                "Benchmark preview did not contain the reviewed operation set."
            }
            check(handled.response.state.draft == null) {
                "Native MCP preview changed the draft before explicit confirmation."
            }
            val previewSummary = previewSummary(preview)
            emitPreview(repetition, previewSummary)
            if (!confirmPreview(repetition, previewSummary)) {
                throw ImportBenchmarkCancelledException()
            }

            val confirmed = native.confirm(
                sessionId = session.session.id,
                expectedRevision = preview.sessionRevision,
                previewId = preview.id,
            )
            require(confirmed.lastError == null) {
                "Post-confirm inclusion review failed; this repetition cannot be scored."
            }
            val draft = confirmed.draft
                ?: throw IllegalStateException("Confirmed benchmark preview did not create a draft.")
            require(draft.transactions.size == fixture.modelInput.transactions.size) {
                "Confirmed benchmark draft did not contain the reviewed operation set."
            }
            requireAmbiguousReviewsPreserved(preview, draft.transactions)
            return score(draft.transactions, repository.listCategories())
        } finally {
            deleteTree(workDirectory)
        }
    }

    private fun score(
        rows: List<DraftTransaction>,
        categories: List<TransactionCategory>,
    ): ImportBenchmarkMetrics {
        val bySourceId = rows.associateBy { row ->
            row.transaction.sourceRef?.takeIf(String::isNotBlank)
                ?: throw IllegalStateException("Benchmark draft row has no source reference.")
        }
        require(bySourceId.size == rows.size && bySourceId.keys == fixture.evaluationOnly.selection.keys) {
            "Benchmark result lost the approved operation identity mapping."
        }
        val selection = fixture.evaluationOnly.selection
        val truePositive = selection.count { (id, expected) -> expected && bySourceId.getValue(id).included }
        val falsePositive = selection.count { (id, expected) -> !expected && bySourceId.getValue(id).included }
        val falseNegative = selection.count { (id, expected) -> expected && !bySourceId.getValue(id).included }
        val predictedIncluded = truePositive + falsePositive
        val expectedIncluded = selection.values.count { it }
        val categoryIdByName = categoryLeafIdsByName(categories)
        val categoryLabels = fixture.evaluationOnly.categories
        val expectedCategoryIds = categoryLabels.mapValues { (_, name) ->
            categoryIdByName[name]
                ?: throw IllegalStateException("Benchmark category label is unavailable in the active catalog.")
        }
        val categoryAssigned = expectedCategoryIds.count { (id, _) ->
            bySourceId.getValue(id).transaction.categoryId != null
        }
        val categoryCorrect = expectedCategoryIds.count { (id, expectedId) ->
            bySourceId.getValue(id).transaction.categoryId == expectedId
        }
        val associationCounts = rows.mapNotNull { it.transaction.receiptAssociation?.status }
            .groupingBy { it }
            .eachCount()
        return ImportBenchmarkMetrics(
            selectionPrecision = predictedIncluded.takeIf { it > 0 }?.let { truePositive.toDouble() / it },
            selectionRecall = truePositive.toDouble() / expectedIncluded,
            selectionTruePositive = truePositive,
            selectionFalsePositive = falsePositive,
            selectionFalseNegative = falseNegative,
            categoryAccuracy = categoryCorrect.toDouble() / categoryLabels.size,
            categoryCoverage = categoryAssigned.toDouble() / categoryLabels.size,
            categoryAccuracyWhenAssigned = categoryAssigned.takeIf { it > 0 }
                ?.let { categoryCorrect.toDouble() / it },
            categoryCorrect = categoryCorrect,
            categoryAssigned = categoryAssigned,
            categoryLabelCount = categoryLabels.size,
            matchedReceiptCount = associationCounts[ReceiptAssociationStatus.MATCHED] ?: 0,
            ambiguousReceiptCount = associationCounts[ReceiptAssociationStatus.AMBIGUOUS] ?: 0,
            unmatchedReceiptCount = associationCounts[ReceiptAssociationStatus.UNMATCHED] ?: 0,
            receiptLabelCount = 0,
        )
    }

    private fun requireAmbiguousReviewsPreserved(
        preview: McpPreview,
        transactions: List<DraftTransaction>,
    ) {
        val expected = preview.transactions.mapNotNull { transaction ->
            transaction.receiptAssociation?.takeIf { it.status == ReceiptAssociationStatus.AMBIGUOUS }
        }
        if (expected.isEmpty()) return
        require(expected.all { !it.review?.candidates.isNullOrEmpty() }) {
            "Ambiguous receipt preview omitted reviewed candidates."
        }

        val remaining = transactions.mapNotNull { transaction ->
            transaction.transaction.receiptAssociation
                ?.takeIf { it.status == ReceiptAssociationStatus.AMBIGUOUS }
        }.toMutableList()
        require(expected.size == remaining.size && expected.all(remaining::remove) && remaining.isEmpty()) {
            "Confirmation changed an ambiguous receipt review; this repetition cannot be scored."
        }
    }

    private fun categoryLeafIdsByName(categories: List<TransactionCategory>): Map<String, String> {
        val parentIds = categories.mapNotNullTo(hashSetOf()) { it.parentId }
        return categories.asSequence()
            .filter { !it.archived && it.type == CategoryType.EXPENSE && it.id !in parentIds }
            .groupBy(TransactionCategory::displayName)
            .mapNotNull { (name, matches) -> matches.singleOrNull()?.let { name to it.id } }
            .toMap()
    }

    private fun previewSummary(preview: McpPreview): ImportBenchmarkPreviewSummary {
        val receiptStatuses = preview.transactions.mapNotNull { it.receiptAssociation?.status }
            .groupingBy { it }
            .eachCount()
        return ImportBenchmarkPreviewSummary(
            transactionCount = preview.transactions.size,
            categorizedCount = preview.transactions.count { it.categoryId != null },
            matchedReceiptCount = receiptStatuses[ReceiptAssociationStatus.MATCHED] ?: 0,
            ambiguousReceiptCount = receiptStatuses[ReceiptAssociationStatus.AMBIGUOUS] ?: 0,
            unmatchedReceiptCount = receiptStatuses[ReceiptAssociationStatus.UNMATCHED] ?: 0,
        )
    }

    private fun emitPreview(repetition: Int, summary: ImportBenchmarkPreviewSummary) {
        emit(
            "Повтор $repetition: preview: операций ${summary.transactionCount}, " +
                "с категорией ${summary.categorizedCount}; чеков: " +
                "сопоставлено ${summary.matchedReceiptCount}, " +
                "на проверке ${summary.ambiguousReceiptCount}, " +
                "без сопоставления ${summary.unmatchedReceiptCount}.",
        )
    }

    private fun benchmarkProviderCatalog(): ProviderCatalog = ProviderCatalog(
        providers = listOf(
            ProviderDefinition(
                id = BENCHMARK_PROVIDER,
                displayName = "Isolated benchmark gateway",
                baseUrl = "http://127.0.0.1:1",
                credentialEnv = null,
                models = listOf(
                    ProviderModelDefinition(
                        id = BENCHMARK_MODEL,
                        reasoningModes = listOf(ReasoningModeDefinition(id = DISABLED_REASONING)),
                    ),
                ),
            ),
        ),
    ).validated()

    private fun deleteTree(path: Path) {
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }


    private companion object {
        const val BENCHMARK_PROVIDER = "benchmark-local"
        const val BENCHMARK_MODEL = "benchmark-local-model"
        const val DISABLED_REASONING = "disabled"
    }
}

internal class ImportBenchmarkCancelledException : IllegalStateException("Benchmark stopped before draft confirmation.")

private class BenchmarkProviderFailureGuard(
    private val delegate: ChatCompletionGateway,
) : ChatCompletionGateway, PreCompletionRequestAuthorizer {
    private data class ProviderFailure(
        val stage: String,
        val safeSummary: String,
    )

    private val failure = AtomicReference<ProviderFailure?>(null)

    override val contextWindowTokens: Int?
        get() = delegate.contextWindowTokens

    override val maxOutputTokens: Int?
        get() = delegate.maxOutputTokens

    override suspend fun authorizeBeforeCompletion(request: ChatCompletionRequest) {
        if (failure.get() != null) throw failureException()
        val authorizer = delegate as? PreCompletionRequestAuthorizer ?: return
        try {
            authorizer.authorizeBeforeCompletion(request)
        } catch (error: ImportBenchmarkPayloadPreviewException) {
            throw error
        } catch (error: ImportBenchmarkCancelledException) {
            throw error
        } catch (error: CancellationException) {
            if (!currentCoroutineContext().isActive) throw error
            throw recordFailure(request, error)
        } catch (error: Exception) {
            throw recordFailure(request, error)
        }
    }

    override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse =
        if (failure.get() != null) {
            throw failureException()
        } else {
            try {
                delegate.complete(request)
            } catch (error: ImportBenchmarkPayloadPreviewException) {
                throw error
            } catch (error: ImportBenchmarkCancelledException) {
                throw error
            } catch (error: CancellationException) {
                if (!currentCoroutineContext().isActive) throw error
                throw recordFailure(request, error)
            } catch (error: Exception) {
                throw recordFailure(request, error)
            }
        }

    fun throwIfFailed() {
        if (failure.get() != null) throw failureException()
    }

    private fun recordFailure(
        request: ChatCompletionRequest,
        error: Throwable,
    ): ImportBenchmarkModelRequestException {
        failure.compareAndSet(
            null,
            ProviderFailure(
                stage = safeRequestStage(request),
                safeSummary = safeFailureSummary(error),
            ),
        )
        return failureException()
    }

    private fun failureException(): ImportBenchmarkModelRequestException {
        val recorded = checkNotNull(failure.get())
        return ImportBenchmarkModelRequestException(recorded.stage, recorded.safeSummary)
    }

    private fun safeFailureSummary(error: Throwable): String {
        if (error is OmpGatewayHttpStatusException) {
            return "OMP returned HTTP ${error.status}; raw provider response omitted"
        }
        val message = error.message
        if ((error is IllegalStateException || error is IllegalArgumentException) &&
            message != null &&
            message in SAFE_OMP_FAILURE_MESSAGES
        ) {
            return message
        }
        return "provider request failed; raw details omitted"
    }

    private companion object {
        // Exact fixed diagnostics only; never forward arbitrary OMP/provider exception text.
        val SAFE_OMP_FAILURE_MESSAGES = setOf(
            "OMP stdio gateway was not initialized.",
            "OMP gateway already has an authorized request.",
            "OMP completion request changed after its authorization preview.",
            "OMP completion request was not authorized before the model-call deadline.",
            "OMP stdio gateway timed out or closed; raw request and response omitted.",
            "OMP stdio gateway returned a mismatched response id.",
            "OMP stdio gateway omitted its response status.",
            "OMP stdio gateway returned a non-JSON completion body.",
            "OMP stdio gateway response omitted its model id.",
            "OMP stdio gateway response named an unexpected model; benchmark stopped.",
            "OMP stdio gateway returned an invalid completion shape; raw response omitted.",
            "OMP stdio request exceeds the safe line limit.",
            "OMP stdio gateway exited before a request.",
            "OMP stdio gateway is closed.",
            "OMP stdio request timed out; raw request and response omitted.",
            "OMP stdio operation was cancelled.",
            "OMP stdio request failed; raw request and response omitted.",
            "OMP stdio response line exceeds the safe limit.",
            "OMP stdio completion response was not valid JSON.",
            "OMP stdio completion response was not a JSON object.",
        )
    }

    private fun safeRequestStage(request: ChatCompletionRequest): String {
        val latestUser = request.messages.lastOrNull { it.role == "user" }?.content.orEmpty().trimStart()
        return when {
            request.tools.orEmpty().isNotEmpty() -> "tool-planning"
            latestUser.startsWith("Операция: дата=") -> "receipt-selector"
            latestUser.contains("Операции для обогащения:") -> "classifier"
            latestUser.startsWith("Новые операции после принятия MCP preview:") -> "inclusion-review"
            else -> "application-request"
        }
    }
}

internal class ImportBenchmarkModelRequestException(
    val stage: String? = null,
    val safeSummary: String? = null,
) : IllegalStateException(
    "Benchmark model request failed${stage?.let { " during $it" }.orEmpty()}" +
        "${safeSummary?.let { ": $it" }.orEmpty()}; this repetition cannot be scored.",
)

internal class ImportBenchmarkReceiptSourceException : IllegalStateException(
    "Benchmark receipt source replay failed; this repetition cannot be scored.",
)
