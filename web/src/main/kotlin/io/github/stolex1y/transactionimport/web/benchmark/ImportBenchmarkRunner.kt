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
    val categoryAccuracy: Double?,
    val categoryCoverage: Double?,
    val categoryAccuracyWhenAssigned: Double?,
    val categoryCorrect: Int,
    val categoryAssigned: Int,
    val categoryLabelCount: Int,
    val categoryApplicableLabelCount: Int,
    val matchedReceiptCount: Int,
    val ambiguousReceiptCount: Int,
    val unmatchedReceiptCount: Int,
    val receiptCorrectCount: Int,
    val receiptAccuracy: Double,
    val receiptLabelCount: Int,
)

internal enum class ImportBenchmarkFailureCode(val reportValue: String) {
    NO_IMPORT_PREVIEW("no_import_preview"),
    PROVIDER_REQUEST_FAILED("provider_request_failed"),
    REPEAT_TIMEOUT("repeat_timeout"),
    RECEIPT_SOURCE_FAILED("receipt_source_failed"),
    INVALID_PREVIEW("invalid_preview"),
    CONFIRMATION_FAILED("confirmation_failed"),
    SCORING_FAILED("scoring_failed"),
    UNEXPECTED_REPEAT_FAILURE("unexpected_repeat_failure"),
}

internal data class ImportBenchmarkRepeatResult(
    val metrics: ImportBenchmarkMetrics,
    val draft: ImportBenchmarkDraftSnapshot,
)

internal sealed interface ImportBenchmarkOutcome {
    data class Scored(val metrics: ImportBenchmarkMetrics) : ImportBenchmarkOutcome
    data class Failed(val failureCode: ImportBenchmarkFailureCode) : ImportBenchmarkOutcome
}


internal class ImportBenchmarkRepeatFailure(
    val failureCode: ImportBenchmarkFailureCode,
    message: String,
) : IllegalStateException(message)

private fun deleteBenchmarkTree(path: Path) {
    Files.walk(path).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}

internal class BenchmarkCleanupException : IllegalStateException(
    "Temporary benchmark resource cleanup failed; the run stopped.",
)

internal class ImportBenchmarkRunner(
    private val fixture: ImportBenchmarkFixture,
    private val gatewayForRun: suspend (Int) -> ChatCompletionGateway,
    private val confirmPreview: suspend (Int, ImportBenchmarkPreviewSummary) -> Boolean,
    private val previewTransactionSourceRefs: (NativeMcpAgent, String, Long, String) -> List<String?>? =
        NativeMcpAgent::previewTransactionSourceRefs,
    private val workDirectoryFactory: () -> Path = { Files.createTempDirectory("import-benchmark-") },
    private val deleteWorkDirectory: (Path) -> Unit = ::deleteBenchmarkTree,
    private val emit: (String) -> Unit = {},
    private val draftArtifactWriter: ImportBenchmarkDraftArtifactWriter? = null,
) {
    suspend fun run(repetitions: Int): List<ImportBenchmarkMetrics> {
        require(repetitions in 1..fixture.repetitions) { "Benchmark repetition count is not approved." }
        val results = ArrayList<ImportBenchmarkMetrics>(repetitions)
        for (repetition in 1..repetitions) {
            var validatedDraft: ImportBenchmarkDraftSnapshot? = null
            var validatedPreview: ImportBenchmarkPreviewSnapshot? = null
            val result = try {
                runOne(
                    repetition,
                    classifyRepeatFailures = false,
                    onValidatedDraft = { validatedDraft = it },
                    onValidatedPreview = { validatedPreview = it },
                )
            } catch (error: Throwable) {
                val failureCode = failureCodeFor(error)
                if (failureCode != null) {
                    draftArtifactWriter?.write(
                        repetition,
                        ImportBenchmarkOutcome.Failed(failureCode),
                        validatedDraft,
                        preview = validatedPreview.takeIf { validatedDraft == null },
                    )
                }
                throw error
            }
            draftArtifactWriter?.write(
                repetition,
                ImportBenchmarkOutcome.Scored(result.metrics),
                result.draft,
            )
            results += result.metrics
        }
        return results
    }

    internal suspend fun runOutcomes(repetitions: Int): List<ImportBenchmarkOutcome> {
        require(repetitions in 1..fixture.repetitions) { "Benchmark repetition count is not approved." }
        val outcomes = ArrayList<ImportBenchmarkOutcome>(repetitions)
        for (repetition in 1..repetitions) {
            var validatedDraft: ImportBenchmarkDraftSnapshot? = null
            var validatedPreview: ImportBenchmarkPreviewSnapshot? = null
            var terminalFailure: Throwable? = null
            val outcome = try {
                val result = runOne(
                    repetition,
                    classifyRepeatFailures = true,
                    onValidatedDraft = { validatedDraft = it },
                    onValidatedPreview = { validatedPreview = it },
                )
                ImportBenchmarkOutcome.Scored(result.metrics)
            } catch (error: ImportBenchmarkCancelledException) {
                terminalFailure = error
                ImportBenchmarkOutcome.Failed(ImportBenchmarkFailureCode.PROVIDER_REQUEST_FAILED)
            } catch (error: OmpGatewayPreflightException) {
                terminalFailure = error
                ImportBenchmarkOutcome.Failed(ImportBenchmarkFailureCode.PROVIDER_REQUEST_FAILED)
            } catch (error: ImportBenchmarkModelRequestException) {
                if (error.fatal) terminalFailure = error
                ImportBenchmarkOutcome.Failed(ImportBenchmarkFailureCode.PROVIDER_REQUEST_FAILED)
            } catch (error: CancellationException) {
                if (!currentCoroutineContext().isActive) throw error
                ImportBenchmarkOutcome.Failed(ImportBenchmarkFailureCode.REPEAT_TIMEOUT)
            } catch (error: BenchmarkCleanupException) {
                terminalFailure = error
                ImportBenchmarkOutcome.Failed(ImportBenchmarkFailureCode.UNEXPECTED_REPEAT_FAILURE)
            } catch (error: ImportBenchmarkReceiptSourceException) {
                ImportBenchmarkOutcome.Failed(ImportBenchmarkFailureCode.RECEIPT_SOURCE_FAILED)
            } catch (error: ImportBenchmarkRepeatFailure) {
                ImportBenchmarkOutcome.Failed(error.failureCode)
            } catch (error: Exception) {
                ImportBenchmarkOutcome.Failed(ImportBenchmarkFailureCode.UNEXPECTED_REPEAT_FAILURE)
            }
            draftArtifactWriter?.write(
                repetition,
                outcome,
                validatedDraft,
                preview = validatedPreview.takeIf { validatedDraft == null },
            )
            outcomes += outcome
            terminalFailure?.let { throw it }
        }
        return outcomes
    }

    private suspend fun failureCodeFor(error: Throwable): ImportBenchmarkFailureCode? = when (error) {
        is ImportBenchmarkCancelledException -> null
        is OmpGatewayPreflightException, is ImportBenchmarkModelRequestException ->
            ImportBenchmarkFailureCode.PROVIDER_REQUEST_FAILED
        is CancellationException ->
            if (currentCoroutineContext().isActive) ImportBenchmarkFailureCode.REPEAT_TIMEOUT else null
        is BenchmarkCleanupException -> ImportBenchmarkFailureCode.UNEXPECTED_REPEAT_FAILURE
        is ImportBenchmarkReceiptSourceException -> ImportBenchmarkFailureCode.RECEIPT_SOURCE_FAILED
        is ImportBenchmarkRepeatFailure -> error.failureCode
        is Exception -> ImportBenchmarkFailureCode.UNEXPECTED_REPEAT_FAILURE
        else -> null
    }

    private suspend fun runOne(
        repetition: Int,
        classifyRepeatFailures: Boolean,
        onValidatedDraft: (ImportBenchmarkDraftSnapshot) -> Unit,
        onValidatedPreview: (ImportBenchmarkPreviewSnapshot) -> Unit,
    ): ImportBenchmarkRepeatResult {


        val workDirectory = workDirectoryFactory()
        var primaryFailure: Throwable? = null
        try {
            val database = workDirectory.resolve("benchmark.sqlite")
            val repository = SqliteImportSessionRepository(database.toString())
            repository.saveLongTermMemory(fixture.modelInput.preferences)
            for (category in BENCHMARK_ONLY_CATEGORIES) repository.insertCategory(category)

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
                ?: if (classifyRepeatFailures) {
                    throw ImportBenchmarkRepeatFailure(
                        ImportBenchmarkFailureCode.NO_IMPORT_PREVIEW,
                        "Benchmark model did not produce an import preview.",
                    )
                } else {
                    throw IllegalStateException("Benchmark model did not produce an import preview.")
                }
            val preview = handled.response.mcpPreview
                ?: if (classifyRepeatFailures) {
                    throw ImportBenchmarkRepeatFailure(
                        ImportBenchmarkFailureCode.INVALID_PREVIEW,
                        "Benchmark import preview is missing.",
                    )
                } else {
                    throw IllegalStateException("Benchmark import preview is missing.")
                }
            val expectedSourceRefs = fixture.modelInput.transactions.map { it.id }.toSet()
            val previewSourceRefs = previewTransactionSourceRefs(
                native,
                session.session.id,
                preview.sessionRevision,
                preview.id,
            )
            if (classifyRepeatFailures) {
                try {
                    requireValidBenchmarkPreview(
                        preview = preview,
                        draftIsPresent = handled.response.state.draft != null,
                        expectedSourceRefs = expectedSourceRefs,
                        previewSourceRefs = previewSourceRefs,
                    )
                } catch (error: IllegalArgumentException) {
                    throw ImportBenchmarkRepeatFailure(
                        ImportBenchmarkFailureCode.INVALID_PREVIEW,
                        "Benchmark preview did not contain the reviewed operation set.",
                    )
                } catch (error: IllegalStateException) {
                    throw ImportBenchmarkRepeatFailure(
                        ImportBenchmarkFailureCode.INVALID_PREVIEW,
                        "Benchmark preview did not contain the reviewed operation set.",
                    )
                }
            } else {
                requireValidBenchmarkPreview(
                    preview = preview,
                    draftIsPresent = handled.response.state.draft != null,
                    expectedSourceRefs = expectedSourceRefs,
                    previewSourceRefs = previewSourceRefs,
                )
            }
            val artifactCategories = draftArtifactWriter?.let { repository.listCategories() }
            if (draftArtifactWriter != null) {
                val snapshot = try {
                    ImportBenchmarkDraftArtifactWriter.snapshotPreview(
                        fixture = fixture,
                        preview = preview,
                        sourceRefs = previewSourceRefs,
                        categories = checkNotNull(artifactCategories),
                    )
                } catch (error: IllegalArgumentException) {
                    if (classifyRepeatFailures) {
                        throw ImportBenchmarkRepeatFailure(
                            ImportBenchmarkFailureCode.INVALID_PREVIEW,
                            "Benchmark preview did not contain the reviewed operation set.",
                        )
                    }
                    throw error
                } catch (error: IllegalStateException) {
                    if (classifyRepeatFailures) {
                        throw ImportBenchmarkRepeatFailure(
                            ImportBenchmarkFailureCode.INVALID_PREVIEW,
                            "Benchmark preview did not contain the reviewed operation set.",
                        )
                    }
                    throw error
                }
                onValidatedPreview(snapshot)
            }
            if (preview.receiptMatching?.status == ReceiptMatchingStageStatus.SOURCE_ERROR) {
                throw ImportBenchmarkReceiptSourceException()
            }
            val previewSummary = previewSummary(preview)
            emitPreview(repetition, previewSummary)
            if (!confirmPreview(repetition, previewSummary)) {
                throw ImportBenchmarkCancelledException()
            }

            val confirmed = if (!classifyRepeatFailures) {
                native.confirm(
                    sessionId = session.session.id,
                    expectedRevision = preview.sessionRevision,
                    previewId = preview.id,
                )
            } else {
                try {
                    native.confirm(
                        sessionId = session.session.id,
                        expectedRevision = preview.sessionRevision,
                        previewId = preview.id,
                    )
                } catch (error: ImportBenchmarkCancelledException) {
                    throw error
                } catch (error: OmpGatewayPreflightException) {
                    throw error
                } catch (error: ImportBenchmarkModelRequestException) {
                    throw error
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    throw ImportBenchmarkRepeatFailure(
                        ImportBenchmarkFailureCode.CONFIRMATION_FAILED,
                        "Benchmark confirmation failed.",
                    )
                }
            }
            gateway.throwIfFailed()
            if (confirmed.lastError != null) {
                if (classifyRepeatFailures) {
                    throw ImportBenchmarkRepeatFailure(
                        ImportBenchmarkFailureCode.CONFIRMATION_FAILED,
                        "Post-confirm inclusion review failed; this repetition cannot be scored.",
                    )
                }
                require(confirmed.lastError == null) {
                    "Post-confirm inclusion review failed; this repetition cannot be scored."
                }
            }
            val draft = confirmed.draft ?: if (classifyRepeatFailures) {
                throw ImportBenchmarkRepeatFailure(
                    ImportBenchmarkFailureCode.CONFIRMATION_FAILED,
                    "Confirmed benchmark preview did not create a draft.",
                )
            } else {
                throw IllegalStateException("Confirmed benchmark preview did not create a draft.")
            }
            if (draft.transactions.size != fixture.modelInput.transactions.size) {
                if (classifyRepeatFailures) {
                    throw ImportBenchmarkRepeatFailure(
                        ImportBenchmarkFailureCode.CONFIRMATION_FAILED,
                        "Confirmed benchmark draft did not contain the reviewed operation set.",
                    )
                }
                require(draft.transactions.size == fixture.modelInput.transactions.size) {
                    "Confirmed benchmark draft did not contain the reviewed operation set."
                }
            }
            if (classifyRepeatFailures) {
                try {
                    requireAmbiguousReviewsPreserved(preview, draft.transactions)
                } catch (error: IllegalArgumentException) {
                    throw ImportBenchmarkRepeatFailure(
                        ImportBenchmarkFailureCode.CONFIRMATION_FAILED,
                        "Post-confirm inclusion review failed; this repetition cannot be scored.",
                    )
                } catch (error: IllegalStateException) {
                    throw ImportBenchmarkRepeatFailure(
                        ImportBenchmarkFailureCode.CONFIRMATION_FAILED,
                        "Post-confirm inclusion review failed; this repetition cannot be scored.",
                    )
                }
            } else {
                requireAmbiguousReviewsPreserved(preview, draft.transactions)
            }
            val categories = artifactCategories ?: repository.listCategories()
            val draftSnapshot = ImportBenchmarkDraftArtifactWriter.snapshot(fixture, draft, categories)
            onValidatedDraft(draftSnapshot)
            val metrics = try {
                ImportBenchmarkScoring.score(fixture, draft.transactions, categories)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (classifyRepeatFailures) {
                    throw ImportBenchmarkRepeatFailure(
                        ImportBenchmarkFailureCode.SCORING_FAILED,
                        "Benchmark scoring failed.",
                    )
                }
                throw error
            }
            return ImportBenchmarkRepeatResult(metrics, draftSnapshot)
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            try {
                deleteWorkDirectory(workDirectory)
            } catch (cleanupError: Exception) {
                val originalFailure = primaryFailure
                val originalIsFatal = when (originalFailure) {
                    is ImportBenchmarkCancelledException, is OmpGatewayPreflightException, is Error -> true
                    is ImportBenchmarkModelRequestException -> originalFailure.fatal
                    is CancellationException -> !currentCoroutineContext().isActive
                    else -> false
                }
                if (originalFailure != null && originalIsFatal) {
                    originalFailure.addSuppressed(cleanupError)
                } else {
                    throw BenchmarkCleanupException().also { cleanupFailure ->
                        originalFailure?.let(cleanupFailure::addSuppressed)
                        cleanupFailure.addSuppressed(cleanupError)
                    }
                }
            }
    }

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



    private companion object {
        const val BENCHMARK_PROVIDER = "benchmark-local"
        const val BENCHMARK_MODEL = "benchmark-local-model"
        const val DISABLED_REASONING = "disabled"
        val BENCHMARK_ONLY_CATEGORIES = listOf(
            TransactionCategory(
                id = "benchmark.clothing",
                displayName = "Одежда",
                type = CategoryType.EXPENSE,
                hint = "Одежда, обувь и аксессуары.",
            ),
            TransactionCategory(
                id = "benchmark.food.ration",
                displayName = "Рацион",
                type = CategoryType.EXPENSE,
                parentId = "category.food",
                hint = "Готовые рационы и питание по плану.",
            ),
            TransactionCategory(
                id = "benchmark.beauty",
                displayName = "Красота",
                type = CategoryType.EXPENSE,
                hint = "Косметика и товары для ухода.",
            ),
        )
    }
}

internal class ImportBenchmarkCancelledException : IllegalStateException("Benchmark stopped before draft confirmation.")

private class BenchmarkProviderFailureGuard(
    private val delegate: ChatCompletionGateway,
) : ChatCompletionGateway, PreCompletionRequestAuthorizer {
    private data class ProviderFailure(
        val stage: String,
        val safeSummary: String,
        val fatal: Boolean,
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
                fatal = error is OmpGatewayModelMismatchException,
            ),
        )
        return failureException()
    }

    private fun failureException(): ImportBenchmarkModelRequestException {
        val recorded = checkNotNull(failure.get())
        return ImportBenchmarkModelRequestException(recorded.stage, recorded.safeSummary, recorded.fatal)
    }

    private fun safeFailureSummary(error: Throwable): String {
        if (error is OmpGatewayDiagnosticCompletedException) {
            return when (error.safeReportedModelId) {
                "gpt-6-luna" ->
                    "One-completion diagnostic matched catalog model ID gpt-6-luna; scoring stopped by design"
                "openai-codex/gpt-6-luna:min" ->
                    "One-completion diagnostic matched exact approved selector openai-codex/gpt-6-luna:min; scoring stopped by design"
                "openai-codex/gpt-6-luna" ->
                    "One-completion diagnostic matched base response alias openai-codex/gpt-6-luna; scoring stopped by design"
                else -> "One-completion diagnostic completed; response model ID omitted; scoring stopped by design"
            }
        }
        if (error is OmpGatewayModelMismatchException) {
            return error.safeReportedModelId?.let {
                "OMP response model ID $it did not match expected catalog ID gpt-6-luna"
            } ?: "OMP response model ID was not allowlisted; raw response omitted"
        }
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
    val fatal: Boolean = false,
) : IllegalStateException(
    "Benchmark model request failed${stage?.let { " during $it" }.orEmpty()}" +
        "${safeSummary?.let { ": $it" }.orEmpty()}; this repetition cannot be scored.",
)

internal class ImportBenchmarkReceiptSourceException : IllegalStateException(
    "Benchmark receipt source replay failed; this repetition cannot be scored.",
)

internal fun requireValidBenchmarkPreview(
    preview: McpPreview,
    draftIsPresent: Boolean,
    expectedSourceRefs: Set<String>,
    previewSourceRefs: List<String?>?,
) {
    require(preview.transactions.size == expectedSourceRefs.size) {
        "Benchmark preview did not contain the reviewed operation set."
    }
    check(!draftIsPresent) {
        "Native MCP preview changed the draft before explicit confirmation."
    }
    require(
        previewSourceRefs != null &&
            previewSourceRefs.size == expectedSourceRefs.size &&
            previewSourceRefs.all { !it.isNullOrBlank() } &&
            previewSourceRefs.filterNotNull().toSet() == expectedSourceRefs,
    ) {
        "Benchmark preview did not contain the reviewed operation identities."
    }
}
