package io.github.stolex1y.transactionimport.web

import io.github.stolex1y.transactionimport.core.AgentGatewayResolver
import io.github.stolex1y.transactionimport.core.AgentResponseException
import io.github.stolex1y.transactionimport.core.AgentRuntimeConfig
import io.github.stolex1y.transactionimport.core.ConfirmedDecisionScope
import io.github.stolex1y.transactionimport.core.ChatCompletionGateway
import io.github.stolex1y.transactionimport.core.ChatCompletionRequest
import io.github.stolex1y.transactionimport.core.ChatCompletionResponse
import io.github.stolex1y.transactionimport.core.ChatFunctionCall
import io.github.stolex1y.transactionimport.core.ChatFunctionDefinition
import io.github.stolex1y.transactionimport.core.ChatToolCall
import io.github.stolex1y.transactionimport.core.ChatToolDefinition
import io.github.stolex1y.transactionimport.core.ConversationRole
import io.github.stolex1y.transactionimport.core.ExternalTransactionCandidate
import io.github.stolex1y.transactionimport.core.ImportSessionState
import io.github.stolex1y.transactionimport.core.ModelCallMetric
import io.github.stolex1y.transactionimport.core.ModelCallStatus
import io.github.stolex1y.transactionimport.core.ReceiptAssociation
import io.github.stolex1y.transactionimport.core.ReceiptAssociationStatus
import io.github.stolex1y.transactionimport.core.ReceiptAssociationSummary
import io.github.stolex1y.transactionimport.core.ReceiptReviewEvidence
import io.github.stolex1y.transactionimport.core.ReceiptReviewReason
import io.github.stolex1y.transactionimport.core.ReceiptMatchUpdate
import io.github.stolex1y.transactionimport.core.RequestMessage
import io.github.stolex1y.transactionimport.core.TransactionItem
import io.github.stolex1y.transactionimport.core.ThinkingOptions
import io.github.stolex1y.transactionimport.core.McpToolLoopConfig
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate
import io.github.stolex1y.transactionimport.core.TransactionDirection
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.math.BigDecimal

private const val TBANK_SERVER_ID = "tbank-transactions"
private const val LEGACY_TBANK_SERVER_ID = "bank-transactions"
private const val TBANK_LIST_ACCOUNTS = "list-accounts"
private const val TBANK_GET_TRANSACTIONS = "get-account-transactions"
private const val RECEIPTS_SERVER_ID = "receipts"
private const val SCHEDULER_SERVER_ID = "scheduler"
private const val SCHEDULER_RUN = "run-scheduled-task"
private const val SCHEDULER_HISTORY = "get-scheduler-history"
private const val MAX_HISTORY_MESSAGES = 8
private const val MAX_NATIVE_RESULT_CHARS = 200_000
private const val MAX_NATIVE_RESULT_ITEMS = 200
private const val MAX_NATIVE_CANDIDATES = 500
private const val MAX_NATIVE_JSON_DEPTH = 8
private val NATIVE_CURRENCY_PATTERN = Regex("^[A-Z]{3}$")
private val RAW_RECEIPT_REFERENCE_PATTERN =
    Regex("""(?i)(?<![\p{L}\p{N}_])(?:receipt|чек)[-_][A-Za-z0-9][A-Za-z0-9_-]{0,199}(?![\p{L}\p{N}_])""")
private const val REDACTED_RECEIPT_REFERENCE = "[идентификатор чека скрыт]"
private const val MAX_RECEIPT_MATCHING_CONTEXT_MESSAGES = 4
private const val MAX_RECEIPT_INSTRUCTION_MESSAGE_CHARS = 2_000

private const val APP_ROUTE_TO_AGENT = "app_route_to_agent"
private const val APP_ASSOCIATE_RECEIPTS = "app_associate_receipts"
private const val APP_READ_RECEIPT_CATALOG = "app_read_receipt_catalog"
private const val APP_READ_RECEIPT_INFO = "app_read_receipt_info"
private const val MAX_RECEIPT_CATALOG_ROWS = 100

@Serializable
private data class ReceiptInfoCatalogResponse(
    val truncated: Boolean,
    val operations: List<ReceiptInfoCatalogOperation>,
)

@Serializable
private data class ReceiptInfoCatalogOperation(
    @SerialName("operation_ref") val operationRef: String,
    @SerialName("operation_date") val operationDate: String,
    @SerialName("operation_merchant") val operationMerchant: String,
    @SerialName("operation_amount_minor") val operationAmountMinor: Long,
    @SerialName("operation_currency") val operationCurrency: String,
    @SerialName("receipt_status") val receiptStatus: String,
    @SerialName("available_fields") val availableFields: List<String>,
    val summary: ReceiptAssociationSummary? = null,
    val review: ReceiptReviewEvidence? = null,
)

@Serializable
private data class ReceiptInfoResponse(
    val status: String,
    val summary: ReceiptAssociationSummary? = null,
    val review: ReceiptReviewEvidence? = null,
    val items: List<TransactionItem>? = null,
    val message: String? = null,
)

private data class ReceiptInfoRequest(
    val operationRef: String,
    val fields: Set<String>,
)

private val receiptInfoJson = Json {
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
enum class ReceiptMatchingStageStatus {
    @SerialName("completed")
    COMPLETED,

    @SerialName("source_error")
    SOURCE_ERROR,

    @SerialName("selector_error")
    SELECTOR_ERROR,
}

@Serializable
data class McpReceiptMatchingSummary(
    val status: ReceiptMatchingStageStatus,
    @SerialName("matched_count") val matchedCount: Int = 0,
    @SerialName("ambiguous_count") val ambiguousCount: Int = 0,
    @SerialName("unmatched_count") val unmatchedCount: Int = 0,
    @SerialName("candidate_count") val candidateCount: Int = 0,
    @SerialName("detail_count") val detailCount: Int = 0,
)
sealed interface NativeMcpArgumentPreparation {
    data class Ready(val arguments: JsonObject) : NativeMcpArgumentPreparation
    data class Clarification(val message: String) : NativeMcpArgumentPreparation
}

interface NativeMcpTransactionSourceAdapter {
    fun supports(tool: McpCallableTool): Boolean

    fun toolDefinition(tool: McpCallableTool): ChatToolDefinition

    fun prepareArguments(tool: McpCallableTool, arguments: JsonObject): JsonObject

    suspend fun prepareArgumentsForExecution(
        tool: McpCallableTool,
        arguments: JsonObject,
        userText: String,
    ): NativeMcpArgumentPreparation =
        NativeMcpArgumentPreparation.Ready(prepareArguments(tool, arguments))

    suspend fun normalize(
        tool: McpCallableTool,
        arguments: JsonObject,
        result: NativeMcpToolCallResult,
    ): NativeMcpTransactionSourceResult

    fun isTransactionFetch(tool: McpCallableTool): Boolean
}

data class NativeMcpTransactionSourceResult(
    val text: String,
    val candidates: List<ExternalTransactionCandidate>,
    val redactedValues: Set<String> = emptySet(),
    val isError: Boolean = false,
)
data class NativeMcpToolCallResult(
    val isError: Boolean,
    val text: String,
)
@Serializable
data class McpPreviewTransaction(
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("amount_minor") val amountMinor: Long,
    val currency: String,
    val merchant: String,
    val description: String,
    @SerialName("source_label") val sourceLabel: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("category_issue") val categoryIssue: String? = null,
    @SerialName("receipt_association") val receiptAssociation: ReceiptAssociation? = null,
    @SerialName("items") val items: List<TransactionItem> = emptyList(),
)

@Serializable
data class McpPreview(
    val id: String,
    @SerialName("session_revision") val sessionRevision: Long,
    val transactions: List<McpPreviewTransaction>,
    @SerialName("receipt_matching") val receiptMatching: McpReceiptMatchingSummary? = null,
)

@Serializable
data class NativeMcpMessageResponse(
    val state: ImportSessionState,
    @SerialName("assistant_text") val assistantText: String,
    @SerialName("mcp_preview") val mcpPreview: McpPreview? = null,
)
@Serializable
data class MerchantCanonicalCandidateAcceptanceResponse(
    val state: ImportSessionState,
    @SerialName("mcp_preview") val mcpPreview: McpPreview? = null,
)

data class NativeMcpDateContext(
    val date: String,
    val timeZone: String,
)

@Serializable
data class ConfirmMcpPreviewRequest(
    val revision: Long,
)

sealed class NativeMcpHandlingResult {
    data object NotHandled : NativeMcpHandlingResult()

    data class Handled(
        val response: NativeMcpMessageResponse,
    ) : NativeMcpHandlingResult()
}


class NativeMcpAgent(
    private val agent: io.github.stolex1y.transactionimport.core.SmartExpenseAgent,
    private val gatewayResolver: AgentGatewayResolver,
    private val mcpTools: McpToolProvider,
    private val runtimeConfig: AgentRuntimeConfig,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val dateContextProvider: () -> NativeMcpDateContext = {
        val zone = ZoneId.systemDefault()
        NativeMcpDateContext(
            date = LocalDate.now(zone).toString(),
            timeZone = zone.id,
        )
    },
    private val transactionSourceAdapters: List<NativeMcpTransactionSourceAdapter> = emptyList(),
    private val receiptMatchSelector: ReceiptMatchSelector = LlmReceiptMatchSelector(gatewayResolver),
)
{
    private val pendingPreviews = ConcurrentHashMap<String, PendingPreview>()
    private val receiptMatchingEngine = ReceiptMatchingEngine(mcpTools, receiptMatchSelector)

    suspend fun refreshReceiptAssociation(
        sessionId: String,
        expectedRevision: Long,
        rowId: String,
    ): ImportSessionState = matchDraftReceiptAction(
        sessionId = sessionId,
        expectedRevision = expectedRevision,
        rowId = rowId,
        selectedSummary = null,
    )

    suspend fun approveReceiptCandidate(
        sessionId: String,
        expectedRevision: Long,
        rowId: String,
        candidateIndex: Int,
    ): ImportSessionState {
        val state = agent.getSession(sessionId)
        requireRevision(state, expectedRevision)
        val row = state.draft?.transactions?.singleOrNull { it.id == rowId }
            ?: throw IllegalArgumentException("Операция не найдена.")
        val association = row.transaction.receiptAssociation
        require(association?.status == ReceiptAssociationStatus.AMBIGUOUS) {
            "Для операции нет чека, ожидающего подтверждения."
        }
        val selected = association.review?.candidates?.getOrNull(candidateIndex)
            ?: throw IllegalArgumentException("Кандидат изменился; сопоставьте чеки повторно.")
        return matchDraftReceiptAction(
            sessionId = sessionId,
            expectedRevision = expectedRevision,
            rowId = rowId,
            selectedSummary = selected,
        )
    }

    suspend fun rejectReceiptCandidate(
        sessionId: String,
        expectedRevision: Long,
        rowId: String,
        candidateIndex: Int,
    ): ImportSessionState {
        val state = agent.getSession(sessionId)
        requireRevision(state, expectedRevision)
        val row = state.draft?.transactions?.singleOrNull { it.id == rowId }
            ?: throw IllegalArgumentException("Операция не найдена.")
        val association = row.transaction.receiptAssociation
        require(association?.status == ReceiptAssociationStatus.AMBIGUOUS) {
            "Для операции нет кандидата на отклонение."
        }
        val review = association.review
            ?: throw IllegalArgumentException("Для операции нет кандидата на отклонение.")
        require(candidateIndex in review.candidates.indices) {
            "Кандидат изменился; сопоставьте чеки повторно."
        }
        val remaining = review.candidates.filterIndexed { index, _ -> index != candidateIndex }
        val update = if (remaining.isEmpty()) {
            ReceiptMatchUpdate(ReceiptAssociationStatus.UNMATCHED)
        } else {
            ReceiptMatchUpdate(
                status = ReceiptAssociationStatus.AMBIGUOUS,
                review = review.copy(candidates = remaining),
            )
        }
        return agent.recordReceiptAssociation(
            sessionId = sessionId,
            expectedRevision = expectedRevision,
            userText = "Отклонить этот вариант чека.",
            assistantText = "Вариант убран из текущего результата. При следующем сопоставлении он может появиться снова.",
            updates = mapOf(rowId to update),
            persistRule = false,
        )
    }

    suspend fun unlinkReceiptAssociation(
        sessionId: String,
        expectedRevision: Long,
        rowId: String,
    ): ImportSessionState {
        val state = agent.getSession(sessionId)
        requireRevision(state, expectedRevision)
        val row = state.draft?.transactions?.singleOrNull { it.id == rowId }
            ?: throw IllegalArgumentException("Операция не найдена.")
        require(row.transaction.receiptAssociation?.status == ReceiptAssociationStatus.MATCHED) {
            "У операции нет связанного чека."
        }
        return agent.recordReceiptAssociation(
            sessionId = sessionId,
            expectedRevision = expectedRevision,
            userText = "Отвязать чек от операции.",
            assistantText = "Связь и сведения из чека удалены из draft; банковская операция не изменена.",
            updates = mapOf(rowId to ReceiptMatchUpdate(ReceiptAssociationStatus.UNMATCHED)),
            persistRule = false,
        )
    }

    private suspend fun matchDraftReceiptAction(
        sessionId: String,
        expectedRevision: Long,
        rowId: String,
        selectedSummary: ReceiptAssociationSummary?,
    ): ImportSessionState {
        val state = agent.getSession(sessionId)
        requireRevision(state, expectedRevision)
        val draft = state.draft ?: throw IllegalArgumentException("В сессии ещё нет черновика.")
        val target = draft.transactions.singleOrNull { it.id == rowId }
            ?: throw IllegalArgumentException("Операция не найдена.")
        require(target.transaction.receiptAssociation?.status != ReceiptAssociationStatus.MATCHED) {
            "Сначала отвяжите текущий чек."
        }
        require(target.transaction.items.isEmpty()) {
            "Операция содержит позиции без подтверждённой связи; draft не изменён."
        }
        val rows = draft.transactions.filter { row ->
            row.transaction.receiptAssociation?.status != ReceiptAssociationStatus.MATCHED &&
                row.transaction.items.isEmpty()
        }
        val candidates = rows.map { row ->
            val transaction = row.transaction
            ExternalTransactionCandidate(
                occurredAt = transaction.occurredAt,
                postedAt = transaction.postedAt,
                amountMinor = if (transaction.direction == TransactionDirection.EXPENSE) {
                    -transaction.amountMinor
                } else {
                    transaction.amountMinor
                },
                currency = transaction.currency,
                merchant = transaction.merchant,
                description = row.description,
                sourceLabel = transaction.sourceLabel,
                categoryId = transaction.categoryId,
                sourceRef = row.id,
            )
        }
        val matching = try {
            receiptMatchingEngine.match(
                transactions = candidates,
                config = state.session.config,
                selectedCandidates = selectedSummary?.let { mapOf(rowId to it) }.orEmpty(),
                allowSelectorChoice = false,
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: ReceiptMatchingSourceException) {
            return agent.recordReceiptAssociation(
                sessionId = sessionId,
                expectedRevision = expectedRevision,
                userText = "Повторно сопоставить чек.",
                assistantText = "Источник чеков недоступен; банковская операция не изменена.",
                updates = mapOf(rowId to ReceiptMatchUpdate(ReceiptAssociationStatus.SOURCE_ERROR)),
                persistRule = false,
            )
        }
        val result = matching.transactions
            .withReceiptReuseGuard(state.receiptUsage(exceptRowId = rowId))
            .singleOrNull { it.sourceRef == rowId }
            ?: throw IllegalStateException("Результат сопоставления не содержит выбранную операцию.")
        val selectedStillUnique = result.receiptAssociation?.let {
            it.status == ReceiptAssociationStatus.MATCHED && it.summary == selectedSummary
        } == true
        if (selectedSummary != null && !selectedStillUnique) {
            throw IllegalArgumentException("Чек больше не подходит однозначно; draft не изменён.")
        }
        val association = result.receiptAssociation
            ?: ReceiptAssociation(ReceiptAssociationStatus.UNMATCHED)
        return agent.recordReceiptAssociation(
            sessionId = sessionId,
            expectedRevision = expectedRevision,
            userText = if (selectedSummary == null) {
                "Повторно сопоставить чек для операции."
            } else {
                "Утвердить выбранный чек."
            },
            assistantText = when (association.status) {
                ReceiptAssociationStatus.MATCHED -> "Чек привязан после повторной проверки."
                ReceiptAssociationStatus.AMBIGUOUS -> "Безопасный однозначный выбор не получен; выберите чек или отклоните его."
                ReceiptAssociationStatus.UNMATCHED -> "Подходящий чек не найден."
                ReceiptAssociationStatus.SOURCE_ERROR -> "Источник чеков недоступен; банковская операция не изменена."
            },
            updates = mapOf(
                rowId to ReceiptMatchUpdate(
                    status = association.status,
                    items = result.items,
                    summary = association.summary,
                    review = association.review,
                ),
            ),
            persistRule = false,
        )
    }


suspend fun handle(
    sessionId: String,
    expectedRevision: Long,
    text: String,
): NativeMcpHandlingResult {
    val loopConfig = runtimeConfig.mcpToolLoop
    if (!loopConfig.enabled) return NativeMcpHandlingResult.NotHandled

    val state = agent.getSession(sessionId)
    requireRevision(state, expectedRevision)
    val pending = pendingPreviewFor(sessionId, expectedRevision)
    val configuredTools = mcpTools.allowedTools()
    val receiptsAvailable = configuredTools.any {
        it.serverId == RECEIPTS_SERVER_ID && it.name == "search-receipts"
    } && configuredTools.any {
        it.serverId == RECEIPTS_SERVER_ID && it.name == "get-receipt"
    }
    val tools = configuredTools
        .filterNot { it.serverId == RECEIPTS_SERVER_ID }
        .sortedWith(compareBy<McpCallableTool> { it.serverId }.thenBy { it.name })
    if (tools.isEmpty() && state.draft == null) {
        return NativeMcpHandlingResult.NotHandled
    }

    val gateway = gatewayResolver.resolve(state.session.config)
    val bindings = tools.associateBy { functionName(it.serverId, it.name) }
    val sourceAdapters = listOf<NativeMcpTransactionSourceAdapter>(TbankTransactionSourceAdapter(tools)) +
        transactionSourceAdapters
    val adaptersByFunction = tools.associate { tool ->
        val matchingAdapters = sourceAdapters.filter { it.supports(tool) }
        require(matchingAdapters.size <= 1) {
            "Для MCP tool настроено больше одного source adapter."
        }
        functionName(tool.serverId, tool.name) to matchingAdapters.singleOrNull()
    }
    val applicationActions = applicationActions(
        state = state,
        pending = pending,
        receiptsAvailable = receiptsAvailable,
        transactionSourceAvailable = tools.any { tool ->
            adaptersByFunction[functionName(tool.serverId, tool.name)]
                ?.isTransactionFetch(tool) == true
        },
    )
    val messages = buildInitialMessages(state, text, receiptsAvailable, pending)
    val functionDefinitions = tools.map { tool ->
        val adapter = adaptersByFunction[functionName(tool.serverId, tool.name)]
        adapter?.toolDefinition(tool) ?: ChatToolDefinition(
            function = ChatFunctionDefinition(
                name = functionName(tool.serverId, tool.name),
                description = buildDescription(tool),
                parameters = modelSchema(tool),
            ),
        )
    } + applicationActions.map { (name, description) ->
        appActionDefinition(name, description)
    }
    val toolSchemasByFunction = functionDefinitions.associate { it.function.name to it.function.parameters }
    val schedulerAliases = linkedMapOf<String, String>()
    val receiptRowRefs = linkedMapOf<String, String>()
    val candidates = mutableListOf<ExternalTransactionCandidate>()
    val redactedValues = mutableSetOf<String>()
    var response: ChatCompletionResponse
    var iterations = 0
    var toolCalls = 0
    var lastFetchArguments: JsonObject? = null
    var receiptsRequested = false
    var receiptCatalogLoaded = false
    var receiptInfoRead = false
    var persistReceiptRule = false
    var receiptRulePersistenceDeferred = false
    var transactionFetchCompleted = false
    var transactionFetchFailed = false
    var transactionFetchErrorText: String? = null

    while (true) {
        if (iterations >= loopConfig.maxIterations) {
            throw AgentResponseException(
                "MCP-цикл достиг лимита итераций (${loopConfig.maxIterations}); " +
                    "операции не импортированы.",
            )
        }
        response = withTimeout(loopConfig.callTimeoutMs) {
            gateway.complete(
                request(
                    config = state.session.config,
                    gateway = gateway,
                    messages = messages,
                    tools = functionDefinitions,
                ),
            )
        }
        iterations += 1
        val message = response.choices.firstOrNull()?.message
            ?: throw AgentResponseException("Провайдер не вернул сообщение для MCP-цикла.")
        val calls = message.toolCalls.orEmpty()
        if (calls.isEmpty()) {
            if (toolCalls == 0 && candidates.isEmpty()) {
                return NativeMcpHandlingResult.NotHandled
            }
            val classification = if (candidates.isEmpty()) {
                null
            } else {
                agent.classifyExternalTransactionsWithProposals(
                    sessionId = sessionId,
                    candidates = candidates,
                    userText = text,
                )
            }
            if (classification != null) {
                candidates.clear()
                candidates += classification.candidates
            }
            var receiptSummary: McpReceiptMatchingSummary? = null
            var receiptStatusText: String? = null
            if (receiptsRequested) {
                if (candidates.isNotEmpty()) {
                    val matching = if (receiptsAvailable) {
                        matchBankCandidatesForPreview(
                            candidates = candidates,
                            state = state,
                            userText = text,
                        )
                    } else {
                        BankReceiptMatch(
                            candidates = candidates.toList(),
                            summary = McpReceiptMatchingSummary(
                                status = ReceiptMatchingStageStatus.SOURCE_ERROR,
                            ),
                            statusText =
                                "Сопоставление чеков недоступно: требуемые инструменты источника " +
                                    "не предоставлены; preview содержит только банковские операции.",
                        )
                    }
                    candidates.clear()
                    candidates += matching.candidates
                    receiptSummary = matching.summary
                    receiptStatusText = listOfNotNull(
                        if (receiptRulePersistenceDeferred) {
                            "Правило для будущих операций не сохранено: сначала подтвердите preview, " +
                                "затем попросите применить правило к созданному draft."
                        } else {
                            null
                        },
                        matching.statusText,
                    ).joinToString(" ")
                } else if (transactionFetchFailed) {
                    receiptStatusText =
                        "Сопоставление чеков не выполнялось из-за ошибки банковского источника."
                } else if (transactionFetchCompleted) {
                    receiptStatusText =
                        "Сопоставление чеков с существующими операциями не выполнялось."
                } else if (state.draft != null && pending == null) {
                    return handleReceiptAssociation(
                        state = state,
                        expectedRevision = expectedRevision,
                        userText = text,
                        instructionContext = receiptInstructionContext(state, text),
                        persistRule = persistReceiptRule,
                    )
                } else {
                    throw AgentResponseException(
                        "Для сопоставления чеков сначала загрузите допустимые операции.",
                    )
                }
            }
            val baseAssistantText = when {
                receiptsRequested && !receiptsAvailable && candidates.isNotEmpty() ->
                    "Банковские операции получены для preview."

                transactionFetchFailed ->
                    listOfNotNull(
                        "Не удалось загрузить операции из банковского источника.",
                        transactionFetchErrorText,
                    ).joinToString(" ")

                transactionFetchCompleted && candidates.isEmpty() ->
                    listOfNotNull(
                        "Новых банковских операций не найдено; существующий draft не изменён.",
                        if (receiptRulePersistenceDeferred) {
                            "Правило для будущих операций не сохранено."
                        } else {
                            null
                        },
                    ).joinToString(" ")

                else -> sanitizeAssistantText(
                    message.content?.trim().orEmpty().ifBlank {
                        if (candidates.isNotEmpty()) {
                            "Операции получены. Проверьте предварительный просмотр перед импортом."
                        } else {
                            "Не удалось получить операции из подключённых MCP-серверов."
                        }
                    },
                    redactedValues,
                )
            }
            val assistantText = listOfNotNull(baseAssistantText, receiptStatusText)
                .joinToString(" ")
                .take(20_000)
            val savedState = agent.recordExternalExchange(
                sessionId = sessionId,
                expectedRevision = expectedRevision,
                userText = text,
                assistantText = assistantText,
                metric = metric(state, response),
                merchantCanonicalProposals = classification?.merchantCanonicalProposals.orEmpty(),
            )
            val preview = createPreviewIfNeeded(
                sessionId = sessionId,
                revision = savedState.session.revision,
                userText = text,
                candidates = candidates.toList(),
                fetchArguments = lastFetchArguments,
                receiptMatching = receiptSummary,
            )
            return NativeMcpHandlingResult.Handled(
                NativeMcpMessageResponse(
                    state = savedState,
                    assistantText = assistantText,
                    mcpPreview = preview,
                ),
            )
        }
        if (toolCalls + calls.size > loopConfig.maxToolCalls) {
            throw AgentResponseException(
                "MCP-цикл достиг лимита вызовов (${loopConfig.maxToolCalls}); " +
                    "операции не импортированы.",
            )
        }
        val previousToolCalls = toolCalls
        val actionCalls = calls.filter { it.function.name in applicationActions }
        if (
            actionCalls.any { it.function.name == APP_ROUTE_TO_AGENT } &&
            (calls.size != 1 || previousToolCalls != 0 || candidates.isNotEmpty())
        ) {
            throw AgentResponseException("Маршрут к обычному агенту должен быть единственным первым шагом.")
        }
        if (
            actionCalls.any { it.function.name == APP_ASSOCIATE_RECEIPTS } &&
                calls.any { adaptersByFunction[it.function.name] != null }
        ) {
            throw AgentResponseException(
                "Сопоставление чеков должно идти отдельным шагом после получения операций.",
            )
        }
        if (receiptCatalogLoaded && calls.any { it.function.name != APP_READ_RECEIPT_INFO }) {
            throw AgentResponseException("После чтения каталога разрешён только отдельный шаг чтения сведений о чеке.")
        }
        if (receiptInfoRead && calls.isNotEmpty()) {
            throw AgentResponseException("План завершён после чтения сведений о чеке; другие действия запрещены.")
        }
        val receiptCatalogCallCount = calls.count { it.function.name == APP_READ_RECEIPT_CATALOG }
        val receiptInfoCallCount = calls.count { it.function.name == APP_READ_RECEIPT_INFO }
        if (receiptCatalogCallCount + receiptInfoCallCount > 1 || (receiptCatalogCallCount > 0 && receiptInfoRead)) {
            throw AgentResponseException("План содержит повторный шаг чтения чека.")
        }
        if (receiptCatalogCallCount > 0 &&
            (calls.size != 1 || previousToolCalls != 0 || candidates.isNotEmpty() || receiptCatalogLoaded)
        ) {
            throw AgentResponseException("Каталог чеков должен быть отдельным первым шагом.")
        }
        if (receiptInfoCallCount > 0 &&
            (calls.size != 1 || !receiptCatalogLoaded || receiptInfoRead || candidates.isNotEmpty() || receiptsRequested)
        ) {
            throw AgentResponseException("Детали чека можно читать только после каталога и отдельным шагом.")
        }
        val receiptActionCount = calls.count { it.function.name == APP_ASSOCIATE_RECEIPTS }
        if (receiptActionCount > 1 || (receiptsRequested && receiptActionCount > 0)) {
            throw AgentResponseException("План содержит повторный шаг сопоставления чеков.")
        }
        if (receiptActionCount > 0 && (receiptCatalogCallCount > 0 || receiptInfoCallCount > 0)) {
            throw AgentResponseException("Сопоставление и чтение чеков выполняются разными действиями.")
        }
        if (receiptActionCount > 0 && calls.size != 1) {
            throw AgentResponseException("Сопоставление чеков должно выполняться отдельным шагом.")
        }
        if (calls.any { it.id.isBlank() } || calls.map { it.id }.distinct().size != calls.size) {
            throw AgentResponseException("План содержит некорректные идентификаторы tool calls.")
        }
        val preparedCalls = calls.map { call ->
            val rawArguments = parseArguments(call.function)
            when (call.function.name) {
                APP_ROUTE_TO_AGENT -> {
                    requireEmptyActionArguments(rawArguments)
                    PreparedPlanCall.Route(call)
                }

                APP_ASSOCIATE_RECEIPTS -> {
                    val requestedRule = receiptRuleRequested(rawArguments)
                    if (candidates.isEmpty() && state.draft?.transactions.isNullOrEmpty()) {
                        throw AgentResponseException(
                            "Сначала получите допустимые transaction-source операции.",
                        )
                    }
                    PreparedPlanCall.AssociateReceipts(call, requestedRule)
                }
                APP_READ_RECEIPT_CATALOG -> {
                    requireEmptyActionArguments(rawArguments)
                    PreparedPlanCall.ReadReceiptCatalog(call)
                }

                APP_READ_RECEIPT_INFO -> {
                    val request = parseReceiptInfoRequest(rawArguments)
                    val rowId = receiptRowRefs[request.operationRef]
                        ?: throw AgentResponseException("Ссылка на операцию недоступна или устарела.")
                    PreparedPlanCall.ReadReceiptInfo(call, rowId, request.fields)
                }

                else -> {
                    if (call.function.name in applicationActions) {
                        throw AgentResponseException("Модель запросила неизвестное действие приложения.")
                    }
                    val binding = bindings[call.function.name]
                        ?: throw AgentResponseException("Модель запросила неизвестный MCP tool.")
                    val sourceAdapter = adaptersByFunction[call.function.name]
                    if (receiptsRequested && sourceAdapter != null) {
                        throw AgentResponseException("Загрузка операций должна предшествовать сопоставлению чеков.")
                    }
                    val schema = toolSchemasByFunction[call.function.name]
                        ?: throw AgentResponseException("Для MCP tool отсутствует проверенная schema.")
                    validateToolArgumentsAgainstSchema(schema, rawArguments)
                    val sourceArguments = sourceAdapter?.prepareArguments(binding, rawArguments)
                        ?: prepareArguments(
                            binding = binding,
                            arguments = rawArguments,
                            schedulerAliases = schedulerAliases,
                        )
                    PreparedPlanCall.Mcp(
                        call = call,
                        rawArguments = rawArguments,
                        binding = binding,
                        sourceAdapter = sourceAdapter,
                        sourceArguments = sourceArguments,
                    )
                }
            }
        }
        toolCalls += calls.size
        messages += RequestMessage(
            role = "assistant",
            content = sanitizeAssistantText(message.content.orEmpty(), redactedValues),
            toolCalls = calls,
        )
        preparedCalls.forEach { planned ->
            when (planned) {
                is PreparedPlanCall.Route -> return NativeMcpHandlingResult.NotHandled
                is PreparedPlanCall.AssociateReceipts -> {
                    if (planned.rememberRule) {
                        if (state.draft == null || candidates.isNotEmpty()) {
                            receiptRulePersistenceDeferred = true
                        } else {
                            persistReceiptRule = true
                        }
                    }
                    receiptsRequested = true
                    messages += RequestMessage(
                        role = "tool",
                        content = "Шаг принят; сопоставление выполнится после завершения текущего плана.",
                        toolCallId = planned.call.id,
                        name = planned.call.function.name,
                    )
                }
                is PreparedPlanCall.ReadReceiptCatalog -> {
                    receiptRowRefs.clear()
                    messages += RequestMessage(
                        role = "tool",
                        content = receiptCatalogResponse(state, receiptRowRefs),
                        toolCallId = planned.call.id,
                        name = planned.call.function.name,
                    )
                    receiptCatalogLoaded = true
                }

                is PreparedPlanCall.ReadReceiptInfo -> {
                    receiptInfoRead = true
                    messages += RequestMessage(
                        role = "tool",
                        content = receiptInfoResponse(
                            state = state,
                            rowId = planned.rowId,
                            fields = planned.fields,
                            userText = text,
                            receiptsAvailable = receiptsAvailable,
                        ),
                        toolCallId = planned.call.id,
                        name = planned.call.function.name,
                    )
                }

                is PreparedPlanCall.Mcp -> {
                    val call = planned.call
                    val rawArguments = planned.rawArguments
                    val binding = planned.binding
                    val sourceAdapter = planned.sourceAdapter
                    val argumentPreparation = if (sourceAdapter?.isTransactionFetch(binding) == true) {
                        withTimeout(loopConfig.callTimeoutMs) {
                            sourceAdapter.prepareArgumentsForExecution(binding, rawArguments, text)
                        }
                    } else {
                        NativeMcpArgumentPreparation.Ready(planned.sourceArguments)
                    }
                    if (argumentPreparation is NativeMcpArgumentPreparation.Clarification) {
                        val assistantText = argumentPreparation.message
                        val savedState = agent.recordExternalExchange(
                            sessionId = sessionId,
                            expectedRevision = expectedRevision,
                            userText = text,
                            assistantText = assistantText,
                            metric = metric(state, response),
                        )
                        return NativeMcpHandlingResult.Handled(
                            NativeMcpMessageResponse(
                                state = savedState,
                                assistantText = assistantText,
                            ),
                        )
                    }
                    val sourceArguments = (argumentPreparation as NativeMcpArgumentPreparation.Ready).arguments
                    if (sourceAdapter?.isTransactionFetch(binding) == true) {
                        lastFetchArguments = rawArguments
                    }
                    val result = if (
                        binding.serverId == SCHEDULER_SERVER_ID &&
                        binding.name == SCHEDULER_RUN &&
                        !hasExplicitSchedulerRunIntent(text)
                    ) {
                        TbankToolCallResponse(
                            tool = binding.name,
                            isError = true,
                            text = "Явно запросите запуск scheduler task.",
                        )
                    } else {
                        withTimeout(loopConfig.callTimeoutMs) {
                            mcpTools.callConfiguredTool(
                                serverId = binding.serverId,
                                tool = binding.name,
                                arguments = sourceArguments,
                            )
                        }
                    }
                    val normalized = if (sourceAdapter != null) {
                        val adapted = sourceAdapter.normalize(
                            binding,
                            rawArguments,
                            NativeMcpToolCallResult(result.isError, result.text),
                        )
                        NormalizedToolResult(
                            text = adapted.text,
                            candidates = adapted.candidates,
                            redactedValues = adapted.redactedValues,
                            isError = adapted.isError,
                        )
                    } else {
                        normalizeResult(
                            binding = binding,
                            result = result,
                            schedulerAliases = schedulerAliases,
                        )
                    }
                    if (sourceAdapter?.isTransactionFetch(binding) == true) {
                        transactionFetchCompleted = true
                        if (normalized.isError) {
                            transactionFetchFailed = true
                            transactionFetchErrorText = normalized.text
                        }
                    }
                    require(normalized.text.length <= MAX_NATIVE_RESULT_CHARS) {
                        "MCP tool вернул слишком большой нормализованный ответ."
                    }
                    require(candidates.size + normalized.candidates.size <= MAX_NATIVE_CANDIDATES) {
                        "MCP tool вернул слишком много операций."
                    }
                    candidates += normalized.candidates
                    redactedValues += normalized.redactedValues
                    messages += RequestMessage(
                        role = "tool",
                        content = normalized.text,
                        toolCallId = call.id,
                        name = call.function.name,
                    )
                }
            }
        }
    }
}

private fun applicationActions(
    state: ImportSessionState,
    pending: PendingPreview?,
    receiptsAvailable: Boolean,
    transactionSourceAvailable: Boolean,
): Map<String, String> = buildMap {
    put(
        APP_ROUTE_TO_AGENT,
        "Передать обычный разговор, правку текущего draft или запрос о правиле штатному агенту приложения. " +
            "Используй как единственный первый шаг, не вызывая MCP tools.",
    )
    if (
        pending == null && state.draft?.transactions?.isNotEmpty() == true
    ) {
        put(
            APP_READ_RECEIPT_CATALOG,
            "Для явного вопроса пользователя о receipt сначала получить краткий каталог " +
                "операций и safe receipt status/candidates. Не вызывать это действие для обычного чата.",
        )
        put(
            APP_READ_RECEIPT_INFO,
            "После app_read_receipt_catalog получить только запрошенные поля receipt по operation_ref. " +
                "Действие read-only: не связывает receipt и не меняет draft.",
        )
    }
    if (
        (pending == null && state.draft?.transactions?.isNotEmpty() == true) ||
        transactionSourceAvailable
    ) {
        val sourceStatus = if (receiptsAvailable) {
            "Источник чеков настроен."
        } else {
            "Источник чеков отсутствует; действие вернёт source_error."
        }
        put(
            APP_ASSOCIATE_RECEIPTS,
            "Сопоставить чеки приложением с текущим draft либо после transaction-source fetch. " +
                "$sourceStatus remember_rule=true используй только если план сохраняет правило " +
                "для существующего draft. При pending preview вызывай только после нового fetch. " +
                "Не вызывай receipts tools напрямую.",
        )
    }
}

private fun appActionDefinition(name: String, description: String): ChatToolDefinition =
    ChatToolDefinition(
        function = ChatFunctionDefinition(
            name = name,
            description = description,
            parameters = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    when (name) {
                        APP_ASSOCIATE_RECEIPTS -> buildJsonObject {
                            put(
                                "remember_rule",
                                buildJsonObject {
                                    put("type", "boolean")
                                    put("description", "Сохранить receipt rule и применить его к существующему draft")
                                },
                            )
                        }
                        APP_READ_RECEIPT_INFO -> buildJsonObject {
                            put(
                                "operation_ref",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "Только ref из результата app_read_receipt_catalog")
                                },
                            )
                            put(
                                "fields",
                                buildJsonObject {
                                    put("type", "array")
                                    put("minItems", 1)
                                    put("maxItems", 5)
                                    put("uniqueItems", true)
                                    put(
                                        "items",
                                        buildJsonObject {
                                            put("type", "string")
                                            putJsonArray("enum") {
                                                listOf(
                                                    "merchant",
                                                    "settlement_place",
                                                    "date_time",
                                                    "total",
                                                    "items",
                                                ).forEach { add(JsonPrimitive(it)) }
                                            }
                                        },
                                    )
                                },
                            )
                        }
                        else -> buildJsonObject {}
                    },
                )
                if (name == APP_READ_RECEIPT_INFO) {
                    putJsonArray("required") {
                        add(JsonPrimitive("operation_ref"))
                        add(JsonPrimitive("fields"))
                    }
                }
                put("additionalProperties", false)
            },
        ),
    )


private fun requireEmptyActionArguments(arguments: JsonObject) {
    if (arguments.isNotEmpty()) {
        throw AgentResponseException("Действие приложения получило недопустимые аргументы.")
    }
}
private fun receiptRuleRequested(arguments: JsonObject): Boolean {
    if (arguments.keys.any { it != "remember_rule" }) {
        throw AgentResponseException("Действие приложения получило недопустимые аргументы.")
    }
    val value = arguments["remember_rule"] ?: return false
    return (value as? JsonPrimitive)?.booleanOrNull
        ?: throw AgentResponseException("Значение remember_rule должно быть логическим.")
}

private val RECEIPT_INFO_FIELDS = setOf(
    "merchant",
    "settlement_place",
    "date_time",
    "total",
    "items",
)

private fun parseReceiptInfoRequest(arguments: JsonObject): ReceiptInfoRequest {
    if (arguments.keys != setOf("operation_ref", "fields")) {
        throw AgentResponseException("Действие чтения чека получило недопустимые аргументы.")
    }
    val operationRef = (arguments["operation_ref"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.contentOrNull
        ?.takeIf { RECEIPT_OPERATION_REF_PATTERN.matches(it) }
        ?: throw AgentResponseException("Ссылка на операцию имеет неверный формат.")
    val rawFields = arguments["fields"] as? JsonArray
        ?: throw AgentResponseException("Список полей чека имеет неверный формат.")
    val fields = rawFields.map { element ->
        (element as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
            ?: throw AgentResponseException("Список полей чека имеет неверный формат.")
    }
    if (fields.isEmpty() || fields.size > RECEIPT_INFO_FIELDS.size ||
        fields.size != fields.distinct().size || fields.any { it !in RECEIPT_INFO_FIELDS }
    ) {
        throw AgentResponseException("Список полей чека не прошёл проверку.")
    }
    return ReceiptInfoRequest(operationRef, fields.toSet())
}

private val RECEIPT_OPERATION_REF_PATTERN = Regex("""^receipt-operation-[1-9][0-9]{0,2}$""")

private fun receiptCatalogResponse(
    state: ImportSessionState,
    rowRefs: MutableMap<String, String>,
): String {
    val rows = state.draft?.transactions.orEmpty()
    val visibleRows = rows.take(MAX_RECEIPT_CATALOG_ROWS)
    val operations = visibleRows.mapIndexed { index, row ->
        val reference = "receipt-operation-${index + 1}"
        rowRefs[reference] = row.id
        val transaction = row.transaction
        val association = transaction.receiptAssociation
        val summary = association?.summary
        val availableFields = buildList {
            if (summary?.merchant != null) add("merchant")
            if (summary?.settlementPlace != null) add("settlement_place")
            if (summary?.dateTime != null) add("date_time")
            if (summary != null) add("total")
            if (transaction.items.isNotEmpty()) add("items")
        }
        ReceiptInfoCatalogOperation(
            operationRef = reference,
            operationDate = transaction.occurredAt,
            operationMerchant = transaction.merchant,
            operationAmountMinor = if (transaction.direction == TransactionDirection.EXPENSE) {
                -transaction.amountMinor
            } else {
                transaction.amountMinor
            },
            operationCurrency = transaction.currency,
            receiptStatus = association?.status?.name?.lowercase() ?: "unmatched",
            availableFields = availableFields,
            summary = summary,
            review = association?.review,
        )
    }
    return receiptInfoJson.encodeToString(
        ReceiptInfoCatalogResponse.serializer(),
        ReceiptInfoCatalogResponse(
            truncated = rows.size > visibleRows.size,
            operations = operations,
        ),
    )
}

private fun receiptInstructionContext(
    state: ImportSessionState,
    text: String,
): List<RequestMessage> = buildList {
    state.messages.takeLast(MAX_HISTORY_MESSAGES).forEach { message ->
        add(
            RequestMessage(
                role = when (message.role) {
                    ConversationRole.USER -> "user"
                    ConversationRole.ASSISTANT -> "assistant"
                },
                content = sanitizeReceiptMatchingText(redactText(message.content))
                    .take(MAX_RECEIPT_INSTRUCTION_MESSAGE_CHARS),
            ),
        )
    }
    add(
        RequestMessage(
            role = "user",
            content = sanitizeReceiptMatchingText(redactText(text.trim()))
                .take(MAX_RECEIPT_INSTRUCTION_MESSAGE_CHARS),
        ),
    )
}

private fun safeReceiptInstruction(instructionContext: List<RequestMessage>): String = buildList {
    instructionContext.lastOrNull()?.let { current ->
        add(
            "Текущее сообщение пользователя: " +
                sanitizeReceiptMatchingText(redactText(current.content)),
        )
    }
    instructionContext.dropLast(1).takeLast(MAX_RECEIPT_MATCHING_CONTEXT_MESSAGES)
        .asReversed()
        .forEach { message ->
            add("${message.role}: ${sanitizeReceiptMatchingText(redactText(message.content))}")
        }
}.joinToString("\n")

private suspend fun receiptInfoResponse(
    state: ImportSessionState,
    rowId: String,
    fields: Set<String>,
    userText: String,
    receiptsAvailable: Boolean,
): String {
    val row = state.draft?.transactions?.singleOrNull { it.id == rowId }
        ?: return receiptInfoJson.encodeToString(
            ReceiptInfoResponse.serializer(),
            ReceiptInfoResponse(status = "operation_unavailable", message = "Операция больше недоступна."),
        )
    val transaction = row.transaction
    val existingAssociation = transaction.receiptAssociation
    val existingSummary = existingAssociation?.summary
    fun available(field: String): Boolean = when (field) {
        "merchant" -> existingSummary?.merchant?.isNotBlank() == true
        "settlement_place" -> existingSummary?.settlementPlace != null
        "date_time" -> existingSummary?.dateTime != null
        "total" -> existingSummary != null
        "items" -> transaction.items.isNotEmpty()
        else -> false
    }
    val missing = fields.filterNot(::available)
    val includeSummary = fields.any { it != "items" }
    fun encode(
        status: String,
        summary: ReceiptAssociationSummary? = existingSummary.takeIf { includeSummary },
        review: ReceiptReviewEvidence? = existingAssociation?.review,
        items: List<TransactionItem>? = transaction.items.takeIf { "items" in fields && it.isNotEmpty() },
        message: String? = null,
    ): String = receiptInfoJson.encodeToString(
        ReceiptInfoResponse.serializer(),
        ReceiptInfoResponse(status, summary, review, items, message),
    )
    if (missing.isEmpty()) return encode("available")
    if (existingAssociation?.status == ReceiptAssociationStatus.MATCHED) {
        return encode(
            status = "saved_details_unavailable",
            message = "Сохранённая связь не содержит запрошенные сведения; другой чек не подставлен.",
        )
    }
    if (!receiptsAvailable) {
        return encode(
            status = "source_unavailable",
            message = "Источник чеков не настроен; сохранённые сведения не изменены.",
        )
    }
    val candidate = ExternalTransactionCandidate(
        occurredAt = transaction.occurredAt,
        postedAt = transaction.postedAt,
        amountMinor = if (transaction.direction == TransactionDirection.EXPENSE) {
            -transaction.amountMinor
        } else {
            transaction.amountMinor
        },
        currency = transaction.currency,
        merchant = transaction.merchant,
        description = row.description,
        sourceLabel = transaction.sourceLabel,
        categoryId = transaction.categoryId,
        items = transaction.items,
        receiptAssociation = existingAssociation,
        sourceRef = row.id,
    )
    val matching = try {
        receiptMatchingEngine.match(
            transactions = listOf(candidate),
            config = state.session.config,
            explicitInstruction = safeReceiptInstruction(receiptInstructionContext(state, userText)),
            strictSelector = true,
        )
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (_: ReceiptMatchingSourceException) {
        return encode(
            status = "source_error",
            message = "Не удалось прочитать чек из источника; draft не изменён.",
        )
    } catch (_: ReceiptMatchingSelectionException) {
        return encode(
            status = "selector_error",
            message = "Не удалось безопасно выбрать сведения о чеке; draft не изменён.",
        )
    }
    val guarded = matching.transactions.withReceiptReuseGuard(state.receiptUsage(exceptRowId = row.id))
        .single()
    val association = guarded.receiptAssociation
    val status = when (association?.status) {
        ReceiptAssociationStatus.MATCHED -> "matched"
        ReceiptAssociationStatus.AMBIGUOUS -> "ambiguous"
        ReceiptAssociationStatus.UNMATCHED -> "unmatched"
        ReceiptAssociationStatus.SOURCE_ERROR -> "source_error"
        null -> "unmatched"
    }
    return encode(
        status = status,
        summary = association?.summary?.takeIf { includeSummary },
        review = association?.review ?: existingAssociation?.review.takeIf { association == null },
        items = guarded.items.takeIf { "items" in fields && it.isNotEmpty() },
        message = when (status) {
            "unmatched" -> "Подходящий чек не найден."
            "ambiguous" -> "Нужен выбор пользователя; draft не изменён."
            else -> null
        },
    )
}

private suspend fun handleReceiptAssociation(
    state: ImportSessionState,
    expectedRevision: Long,
    userText: String,
    instructionContext: List<RequestMessage>,
    persistRule: Boolean,
): NativeMcpHandlingResult.Handled {
    val safeInstruction = safeReceiptInstruction(instructionContext)
    val safeUserText = sanitizeReceiptMatchingText(redactText(userText.trim()))
    val updates = linkedMapOf<String, ReceiptMatchUpdate>()
    val draft = state.draft
        ?: throw AgentResponseException("Для сопоставления чеков нужен текущий draft.")
    val matchableRows = draft.transactions.filter { row ->
        if (row.transaction.receiptAssociation?.status == ReceiptAssociationStatus.MATCHED) {
            false
        } else if (row.transaction.items.isNotEmpty()) {
            updates[row.id] = ReceiptMatchUpdate(ReceiptAssociationStatus.AMBIGUOUS)
            false
        } else {
            true
        }
    }
    val preferences = agent.getPreferences()
    val candidates = matchableRows.map { row ->
        val transaction = row.transaction
        ExternalTransactionCandidate(
            occurredAt = transaction.occurredAt,
            postedAt = transaction.postedAt,
            amountMinor = if (transaction.direction ==
                io.github.stolex1y.transactionimport.core.TransactionDirection.EXPENSE
            ) {
                -transaction.amountMinor
            } else {
                transaction.amountMinor
            },
            currency = transaction.currency,
            merchant = transaction.merchant,
            description = row.description,
            sourceLabel = transaction.sourceLabel,
            categoryId = transaction.categoryId,
            sourceRef = row.id,
        )
    }
    val matching = try {
        receiptMatchingEngine.match(
            transactions = candidates,
            config = state.session.config,
            explicitInstruction = safeInstruction,
            confirmedReceiptRules = preferences.confirmedDecisions
                .filter { it.scope == ConfirmedDecisionScope.RECEIPT_MATCHING }
                .map { it.text },
            strictSelector = true,
        )
    } catch (_: ReceiptMatchingSourceException) {
        matchableRows.forEach { row ->
            updates[row.id] = ReceiptMatchUpdate(ReceiptAssociationStatus.SOURCE_ERROR)
        }
        val assistantText = if (persistRule) {
            "Правило сохранено; источник чеков недоступен, поэтому привязка к draft не выполнена."
        } else {
            "Источник чеков недоступен; операции помечены ошибкой сопоставления, банковские поля не изменены."
        }
        val saved = agent.recordReceiptAssociation(
            sessionId = state.session.id,
            expectedRevision = expectedRevision,
            userText = safeUserText,
            assistantText = assistantText,
            updates = updates,
            persistRule = persistRule,
        )
        return NativeMcpHandlingResult.Handled(
            NativeMcpMessageResponse(state = saved, assistantText = assistantText),
        )
    }
    matching.transactions.withReceiptReuseGuard(state.receiptUsage()).forEach { candidate ->
        val association = candidate.receiptAssociation
            ?: ReceiptAssociation(ReceiptAssociationStatus.UNMATCHED)
        updates[requireNotNull(candidate.sourceRef)] = ReceiptMatchUpdate(
            status = association.status,
            items = candidate.items,
            summary = association.summary,
            review = association.review,
        )
    }
    val matchedCount = updates.values.count { it.status == ReceiptAssociationStatus.MATCHED }
    val ambiguousCount = updates.values.count { it.status == ReceiptAssociationStatus.AMBIGUOUS }
    val unmatchedCount = updates.values.count { it.status == ReceiptAssociationStatus.UNMATCHED }
    val assistantText = buildList {
        if (persistRule) add("Правило сохранено и применено к текущему draft.")
        if (matchedCount > 0) add("Привязано уникальных чеков: $matchedCount.")
        if (ambiguousCount > 0) {
            val explanations = updates.entries
                .filter { it.value.status == ReceiptAssociationStatus.AMBIGUOUS }
                .take(3)
                .mapNotNull { (rowId, update) ->
                    val review = update.review ?: return@mapNotNull null
                    val transaction = draft.transactions.singleOrNull { it.id == rowId }?.transaction
                    val operation = transaction?.let {
                        "${it.occurredAt} ${sanitizeReceiptMatchingText(it.merchant)}"
                    } ?: "Операция"
                    val reason = when (review.reason) {
                        ReceiptReviewReason.MULTIPLE_CANDIDATES -> "подошло несколько чеков"
                        ReceiptReviewReason.SELECTOR_UNCERTAIN ->
                            "система не смогла уверенно выбрать один чек"
                        ReceiptReviewReason.SHARED_BY_OPERATIONS ->
                            "один чек подходит нескольким операциям"
                        ReceiptReviewReason.DUPLICATE_SELECTION ->
                            "этот чек уже использован другой операцией"
                        ReceiptReviewReason.OPERATION_TYPE_UNKNOWN ->
                            "Категория не определена; направление расхода известно."
                    }
                    val options = review.candidates.take(5).joinToString("; ") { summary ->
                        buildString {
                            append(summary.merchant)
                            summary.settlementPlace?.let { append(" — ").append(it) }
                            append(", ")
                            append(summary.dateTime ?: summary.date)
                            append(", ")
                            append(formatMinorAmount(summary.amountMinor, summary.currency))
                        }
                    }.ifBlank { "без безопасно различимых вариантов" }
                    "$operation: $reason. Варианты: $options."
                }
            if (explanations.isEmpty()) {
                add("Есть неоднозначные операции; выберите чек в карточке операции.")
            } else {
                add(explanations.joinToString(" "))
            }
            if (ambiguousCount > 3) {
                add("Ещё неоднозначных операций: ${ambiguousCount - 3}; варианты показаны в draft.")
            }
        }
        if (unmatchedCount > 0) {
            add("Подходящих чеков для операций: $unmatchedCount; это не мешает экспорту.")
        }
        if (isEmpty()) add("В текущем draft нет операций для сопоставления.")
    }.joinToString(" ")
    val saved = agent.recordReceiptAssociation(
        sessionId = state.session.id,
        expectedRevision = expectedRevision,
        userText = safeUserText,
        assistantText = assistantText,
        updates = updates,
        persistRule = persistRule,
    )
    return NativeMcpHandlingResult.Handled(
        NativeMcpMessageResponse(state = saved, assistantText = assistantText),
    )
}

private suspend fun matchBankCandidatesForPreview(
    candidates: List<ExternalTransactionCandidate>,
    state: ImportSessionState,
    userText: String,
): BankReceiptMatch {
    val identifiedCandidates = candidates.withUniqueReceiptMatchingRefs()
    if (identifiedCandidates.isEmpty()) {
        return BankReceiptMatch(
            candidates = identifiedCandidates,
            summary = McpReceiptMatchingSummary(ReceiptMatchingStageStatus.COMPLETED),
            statusText = "Банковских операций для сопоставления нет; новые чеки не запрашивались.",
        )
    }
    val preferences = agent.getPreferences()
    return try {
        val matching = receiptMatchingEngine.match(
            transactions = identifiedCandidates,
            config = state.session.config,
            explicitInstruction = safeReceiptInstruction(receiptInstructionContext(state, userText)),
            confirmedReceiptRules = preferences.confirmedDecisions
                .filter { it.scope == ConfirmedDecisionScope.RECEIPT_MATCHING }
                .map { it.text },
            strictSelector = true,
        )
        val candidatesWithRestoredRefs = matching.transactions
            .withReceiptReuseGuard(state.receiptUsage())
            .mapIndexed { index, candidate ->
                candidate.copy(sourceRef = candidates[index].sourceRef)
            }
        val statuses = candidatesWithRestoredRefs.mapNotNull { it.receiptAssociation?.status }
        val summary = McpReceiptMatchingSummary(
            status = ReceiptMatchingStageStatus.COMPLETED,
            matchedCount = statuses.count { it == ReceiptAssociationStatus.MATCHED },
            ambiguousCount = statuses.count { it == ReceiptAssociationStatus.AMBIGUOUS },
            unmatchedCount = statuses.count { it == ReceiptAssociationStatus.UNMATCHED },
            candidateCount = matching.receiptCandidateCount,
            detailCount = matching.receiptDetailCount,
        )
        val statusText = when {
            summary.matchedCount > 0 || summary.ambiguousCount > 0 ->
                "Сопоставление чеков завершено: привязано ${summary.matchedCount}, " +
                    "неоднозначных ${summary.ambiguousCount}, без подходящего чека ${summary.unmatchedCount}."
            else -> "Сопоставление чеков завершено; подходящих чеков не найдено для операций preview."
        }
        BankReceiptMatch(candidatesWithRestoredRefs, summary, statusText)
    } catch (_: ReceiptMatchingSourceException) {
        BankReceiptMatch(
            candidates = candidates.toList(),
            summary = McpReceiptMatchingSummary(status = ReceiptMatchingStageStatus.SOURCE_ERROR),
            statusText =
                "Источник чеков недоступен или не авторизован; preview содержит банковские операции " +
                    "без сопоставления с чеками.",
        )
    } catch (_: ReceiptMatchingSelectionException) {
        BankReceiptMatch(
            candidates = candidates.toList(),
            summary = McpReceiptMatchingSummary(status = ReceiptMatchingStageStatus.SELECTOR_ERROR),
            statusText =
                "Источник чеков ответил, но безопасно сопоставить чеки не удалось; preview " +
                    "содержит банковские операции без сопоставления.",
        )
    }
}

private fun List<ExternalTransactionCandidate>.withUniqueReceiptMatchingRefs():
    List<ExternalTransactionCandidate> {
    val used = mutableSetOf<String>()
    return mapIndexed { index, candidate ->
        val existing = candidate.sourceRef?.trim()?.takeIf(String::isNotBlank)
        var sourceRef = existing?.takeIf { it !in used }
        if (sourceRef == null) {
            sourceRef = "native-preview-${index + 1}"
            var suffix = 2
            while (sourceRef in used) {
                sourceRef = "native-preview-${index + 1}-$suffix"
                suffix += 1
            }
        }
        used += sourceRef
        candidate.copy(sourceRef = sourceRef)
    }
}

private data class BankReceiptMatch(
    val candidates: List<ExternalTransactionCandidate>,
    val summary: McpReceiptMatchingSummary,
    val statusText: String,
)



    suspend fun confirm(
        sessionId: String,
        expectedRevision: Long,
        previewId: String,
    ): ImportSessionState {
        val pending = pendingPreviews.remove(previewId)
            ?: throw IllegalArgumentException("Предварительный просмотр MCP не найден или уже использован.")
        if (pending.sessionId != sessionId || pending.revision != expectedRevision) {
            pendingPreviews[previewId] = pending
            throw IllegalArgumentException("Предварительный просмотр MCP относится к другой ревизии сессии.")
        }
        return try {
            val before = agent.getSession(sessionId)
            requireRevision(before, expectedRevision)
            val existingIds = before.draft?.transactions.orEmpty()
                .map { it.id }
                .toSet()
            val appended = agent.appendExternalTransactions(
                sessionId = sessionId,
                expectedRevision = expectedRevision,
                candidates = pending.candidates,
            )
            val importedIds = appended.draft?.transactions.orEmpty()
                .map { it.id }
                .filterNot(existingIds::contains)
            agent.reviewImportedDraftInclusion(
                sessionId = sessionId,
                expectedRevision = appended.session.revision,
                importedTransactionIds = importedIds,
                userText = pending.userText,
            )
        } catch (error: Throwable) {
            pendingPreviews[previewId] = pending
            throw error
        }
    }
    suspend fun acceptMerchantCanonicalCandidate(
        sessionId: String,
        expectedRevision: Long,
        candidateId: String,
        previewId: String?,
    ): MerchantCanonicalCandidateAcceptanceResponse {
        val state = agent.getSession(sessionId)
        requireRevision(state, expectedRevision)
        val pending = previewId?.let { id ->
            pendingPreviews[id]?.takeIf {
                it.sessionId == sessionId && it.revision == expectedRevision
            }
        }
        val acceptedState = agent.acceptMerchantCanonicalCandidate(
            sessionId = sessionId,
            expectedRevision = expectedRevision,
            candidateId = candidateId,
        )
        val updatedPreview = if (previewId != null && pending != null) {
            val updated = pending.copy(
                revision = acceptedState.session.revision,
                candidates = agent.applyMerchantCanonicalRulesToExternalCandidates(pending.candidates),
            )
            pendingPreviews[previewId] = updated
            pendingPreviewToResponse(previewId, updated)
        } else {
            null
        }
        return MerchantCanonicalCandidateAcceptanceResponse(
            state = acceptedState,
            mcpPreview = updatedPreview,
        )
    }


    fun cancel(sessionId: String, previewId: String) {
        val pending = pendingPreviews[previewId]
        if (pending?.sessionId == sessionId) {
            pendingPreviews.remove(previewId, pending)
        }
    }

    private fun createPreviewIfNeeded(
        sessionId: String,
        revision: Long,
        userText: String,
        candidates: List<ExternalTransactionCandidate>,
        fetchArguments: JsonObject?,
        receiptMatching: McpReceiptMatchingSummary?,
    ): McpPreview? {
        if (candidates.isEmpty()) return null
        pendingPreviews.entries.removeIf { (_, pending) ->
            pending.sessionId == sessionId && pending.revision == revision
        }
        val id = idGenerator()
        val pending = PendingPreview(
            sessionId = sessionId,
            revision = revision,
            userText = userText,
            candidates = candidates.toList(),
            fetchArguments = fetchArguments,
            receiptMatching = receiptMatching,
        )
        pendingPreviews[id] = pending
        return pendingPreviewToResponse(id, pending)
    }

    private fun pendingPreviewToResponse(
        previewId: String,
        pending: PendingPreview,
    ): McpPreview = McpPreview(
        id = previewId,
        sessionRevision = pending.revision,
        transactions = pending.candidates.map { candidate ->
            McpPreviewTransaction(
                occurredAt = candidate.occurredAt,
                amountMinor = candidate.amountMinor,
                currency = candidate.currency,
                merchant = candidate.merchant,
                description = candidate.description,
                sourceLabel = candidate.sourceLabel,
                categoryId = candidate.categoryId,
                categoryIssue = candidate.categoryIssue,
                receiptAssociation = candidate.receiptAssociation,
                items = candidate.items,
            )
        },
        receiptMatching = pending.receiptMatching,
    )

    private fun pendingFetchCorrectionContext(pending: PendingPreview): String {
        val arguments = pending.fetchArguments
        val from = arguments?.get("from")?.jsonPrimitive?.contentOrNull
        val to = arguments?.get("to")?.jsonPrimitive?.contentOrNull
        val durationHint = runCatching {
            val start = from?.let(LocalDate::parse)
            val end = to?.let(LocalDate::parse)
            if (start == null || end == null) {
                null
            } else {
                val days = end.toEpochDay() - start.toEpochDay()
                "Если пользователь меняет только дату начала, сохрани длительность " +
                    "$days дн. и вычисли новую дату окончания."
            }
        }.getOrNull()
        return buildString {
            appendLine("В сессии есть незавершённый MCP preview; preview нельзя редактировать инструкциями.")
            appendLine("Текущий запрос может исправлять исходную инструкцию выгрузки.")
            appendLine("Предыдущий запрос: ${redactText(pending.userText)}")
            appendLine(
                "Предыдущие аргументы transaction-source fetch (данные уже редактированы): " +
                    (arguments?.let(::redactJson) ?: "не сохранены"),
            )
            durationHint?.let(::appendLine)
            appendLine(
                "Если пользователь уточняет счёт, период или добавляет ещё один счёт, " +
                "запусти новый transaction-source fetch с исправленными параметрами. " +
                "Не проси прислать выписку и не меняй старый preview.",
            )
        }.trim()
    }

    private fun buildInitialMessages(
        state: ImportSessionState,
        text: String,
        receiptsAvailable: Boolean,
        pending: PendingPreview? = null,
    ): MutableList<RequestMessage> = buildList {
        add(
            RequestMessage(
                role = "system",
                content = """
                    Ты составляешь единый последовательный план из разрешённых MCP tools
                    и typed-действий приложения. Обычный разговор, правки текущего draft
                    и прочие правила передавай через app_route_to_agent как единственный
                    первый шаг; не вызывай для них MCP. Семантическое решение принимай по
                    полному сообщению и контексту; фиксированные списки слов его не заменяют.
                    Сопоставление чеков выполняется только через typed-действие
                    app_associate_receipts; receipts tools напрямую не вызывай.
                    Для явного вопроса пользователя о данных чека сначала вызови
                    app_read_receipt_catalog. Это отдельный read-only путь: он не запускает
                    сопоставление и не меняет draft. Затем вызови app_read_receipt_info только
                    для нужной операции и полей merchant, settlement_place, date_time, total
                    или items; при неоднозначности объясни причину и не выбирай чек за пользователя.
                    ${if (receiptsAvailable) {
                        "Источник чеков настроен. Если текущий план включает банковские " +
                            "операции и сопоставление чеков, сначала получи весь новый набор " +
                            "операций, затем отдельным шагом вызови app_associate_receipts. " +
                            "Для существующего draft это действие запускает сопоставление с его " +
                            "несвязанными операциями. Запоминание receipt rule указывай отдельным " +
                            "параметром remember_rule только если смысл запроса включает сохранение."
                    } else {
                        "Источники чеков недоступны. Если план включает сопоставление чеков, " +
                            "вызови предложенное typed-действие app_associate_receipts; приложение " +
                            "вернёт source_error и не заявит о выполненном сопоставлении. Не вызывай " +
                            "отсутствующие tools и не имитируй результат."
                    }}
                    Каталог и результат read-only действия не содержат receipt keys, fiscal/QR
                    identifiers, банковские идентификаторы, сведения о покупателе/кассире или
                    полный upstream payload. Не запрашивай и не пересказывай такие данные.
                    Если запрос на receipt rule относится к операциям, которых ещё нет в draft,
                    после безопасного preview правило не сохраняй; запроси подтверждение импорта,
                    затем повтори применение правила к созданному draft.
                    Transaction-source tools вызывай только если семантический план включает
                    загрузку или обновление операций. Добавление операций за другой источник
                    или период — новый fetch: получи preview, но не считай импорт завершённым.
                    Сам не редактируй draft и не выполняй отдельный fetch для обычных правок и
                    правил. Используй разрешённые tools по их описаниям и схемам. Поля с
                    суффиксом _minor — целые минимальные денежные единицы: для RUB 876027 означает
                    8760.27 RUB. Не проси секреты, пароли, OTP, реквизиты или сырые
                    идентификаторы и не раскрывай их. После получения операций кратко
                    объясни результат. Ничего не импортируй: изменение draft произойдёт
                    только после явного подтверждения пользователя в preview.
                    Текущее trusted-состояние приложения: ${state.draft?.let {
                        "draft есть, статус=${it.status}, операций=${it.transactions.size}"
                    } ?: "draft отсутствует"}.
                """.trimIndent(),
            ),
        )
        pending?.let { preview ->
            add(
                RequestMessage(
                    role = "system",
                    content = pendingFetchCorrectionContext(preview),
                ),
            )
        }
        state.messages.takeLast(MAX_HISTORY_MESSAGES).forEach { message ->
            add(
                RequestMessage(
                    role = when (message.role) {
                        ConversationRole.USER -> "user"
                        ConversationRole.ASSISTANT -> "assistant"
                    },
                    content = redactText(message.content),
                ),
            )
        }
        val dateContext = dateContextProvider()
        add(
            RequestMessage(
                role = "system",
                content = "Текущий календарный день: ${dateContext.date}. " +
                    "Часовой пояс: ${dateContext.timeZone}. " +
                    "Используй эту дату и часовой пояс для интерпретации относительных периодов.",
            ),
        )
        add(RequestMessage(role = "user", content = redactText(text.trim())))
    }.toMutableList()

    private fun request(
        config: io.github.stolex1y.transactionimport.core.AgentConfig,
        gateway: ChatCompletionGateway,
        messages: List<RequestMessage>,
        tools: List<ChatToolDefinition>,
        responseFormat: io.github.stolex1y.transactionimport.core.ResponseFormat? = null,
    ): ChatCompletionRequest = ChatCompletionRequest(
        model = config.modelId,
        messages = messages.toList(),
        thinking = ThinkingOptions(type = "disabled"),
        reasoningEffort = config.reasoningModeId,
        responseFormat = responseFormat,
        maxTokens = runtimeConfig.maxTokens.coerceAtMost(gateway.maxOutputTokens ?: runtimeConfig.maxTokens),
        temperature = runtimeConfig.temperature,
        stream = false,
        tools = tools,
        useConfiguredReasoning = true,
    )

    private fun metric(
        state: ImportSessionState,
        response: ChatCompletionResponse,
    ): ModelCallMetric = ModelCallMetric(
        id = idGenerator(),
        providerId = state.session.config.providerId,
        modelId = state.session.config.modelId,
        status = ModelCallStatus.SUCCEEDED,
        promptTokens = response.usage?.promptTokens,
        completionTokens = response.usage?.completionTokens,
        totalTokens = response.usage?.totalTokens,
        contextWindowTokens = null,
        createdAtEpochMs = System.currentTimeMillis(),
    )
    private fun parseArguments(call: ChatFunctionCall): JsonObject = runCatching {
        json.parseToJsonElement(call.arguments.ifBlank { "{}" }).jsonObject
    }.getOrElse {
        throw AgentResponseException("MCP tool получил некорректные аргументы.")
    }
    private fun validateToolArgumentsAgainstSchema(
        schema: JsonObject,
        arguments: JsonObject,
    ) {
        fun invalid(): Nothing =
            throw AgentResponseException("MCP tool получил аргументы, не соответствующие схеме.")
        fun isJsonString(value: JsonPrimitive): Boolean = value.toString().startsWith('"')

        fun validate(value: JsonElement, definition: JsonElement, depth: Int) {
            if (depth > MAX_NATIVE_JSON_DEPTH) invalid()
            if (definition is JsonPrimitive) {
                when (definition.booleanOrNull) {
                    true -> return
                    false -> invalid()
                    null -> invalid()
                }
            }
            val constraints = definition as? JsonObject ?: invalid()
            val types = when (val type = constraints["type"]) {
                is JsonPrimitive -> listOf(type.contentOrNull ?: invalid())
                is JsonArray -> type.map { (it as? JsonPrimitive)?.contentOrNull ?: invalid() }
                else -> emptyList()
            }
            if (types.isNotEmpty() && types.none { type ->
                    when (type) {
                        "object" -> value is JsonObject
                        "array" -> value is JsonArray
                        "string" -> (value as? JsonPrimitive)?.let(::isJsonString) == true
                        "integer" -> (value as? JsonPrimitive)?.let {
                            !isJsonString(it) && it.longOrNull != null
                        } == true
                        "number" -> (value as? JsonPrimitive)?.let {
                            !isJsonString(it) && it.doubleOrNull != null
                        } == true
                        "boolean" -> (value as? JsonPrimitive)?.let {
                            !isJsonString(it) && it.booleanOrNull != null
                        } == true
                        "null" -> value == JsonNull
                        else -> invalid()
                    }
                }
            ) {
                invalid()
            }
            val enumValues = constraints["enum"] as? JsonArray
            if (enumValues != null && enumValues.none { it == value }) invalid()
            when (value) {
                is JsonObject -> {
                    val properties = constraints["properties"] as? JsonObject ?: JsonObject(emptyMap())
                    val required = constraints["required"] as? JsonArray ?: JsonArray(emptyList())
                    if (required.any { requiredKey ->
                            val key = (requiredKey as? JsonPrimitive)?.contentOrNull ?: invalid()
                            key !in value
                        }
                    ) {
                        invalid()
                    }
                    val additionalProperties = constraints["additionalProperties"]
                    if (
                        additionalProperties == JsonPrimitive(false) &&
                        value.keys.any { it !in properties }
                    ) {
                        invalid()
                    }
                    value.forEach { (key, child) ->
                        val childSchema = properties[key] ?: (additionalProperties as? JsonObject)
                        childSchema?.let { validate(child, it, depth + 1) }
                    }
                }

                is JsonArray -> {
                    val itemSchema = constraints["items"]
                    if (itemSchema != null) {
                        value.forEach { validate(it, itemSchema, depth + 1) }
                    }
                }

                else -> Unit
            }
        }

        validate(arguments, schema, 0)
    }

    private fun prepareArguments(
        binding: McpCallableTool,
        arguments: JsonObject,
        schedulerAliases: Map<String, String>,
    ): JsonObject {
        if (binding.serverId == SCHEDULER_SERVER_ID) {
            require(arguments.keys.all { it == "task_alias" || it == "limit" }) {
                "Scheduler tool получил недопустимые аргументы."
            }
            if (binding.name == "list-scheduled-tasks") return JsonObject(emptyMap())
            val alias = arguments["task_alias"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: throw AgentResponseException("Для scheduler нужен task_alias из списка задач.")
            val taskId = schedulerAliases[alias]
                ?: throw AgentResponseException("Неизвестный task_alias; сначала запросите список задач.")
            return buildJsonObject {
                put("task_id", taskId)
                arguments["limit"]?.let { put("limit", it) }
            }
        }
        return redactJson(arguments).jsonObject
    }

    private fun normalizeResult(
        binding: McpCallableTool,
        result: TbankToolCallResponse,
        schedulerAliases: MutableMap<String, String>,
    ): NormalizedToolResult {
        if (result.isError) {
            return NormalizedToolResult("MCP tool завершился ошибкой.", emptyList(), isError = true)
        }
        if (result.text.length > MAX_NATIVE_RESULT_CHARS) {
            return NormalizedToolResult(
                "MCP tool вернул слишком большой ответ.",
                emptyList(),
                isError = true,
            )
        }
        val element = runCatching { json.parseToJsonElement(result.text) }.getOrElse {
            return NormalizedToolResult("MCP tool вернул некорректный ответ.", emptyList(), isError = true)
        }
        val redactedValues = sensitiveValues(element)
        if (binding.serverId == SCHEDULER_SERVER_ID) {
            return normalizeSchedulerResult(element, binding.name, schedulerAliases)
                .copy(redactedValues = redactedValues)
        }
        return NormalizedToolResult(
            text = redactJson(element).toString(),
            candidates = emptyList(),
            redactedValues = redactedValues,
        )
    }
    private fun normalizeSchedulerResult(
        element: JsonElement,
        tool: String,
        schedulerAliases: MutableMap<String, String>,
    ): NormalizedToolResult {
        if (tool == "list-scheduled-tasks") {
            val tasks = (element as? JsonArray)
                ?: (element as? JsonObject)?.get("tasks") as? JsonArray
                ?: return NormalizedToolResult("""{"tasks":[]}""", emptyList())
            if (tasks.size > MAX_NATIVE_RESULT_ITEMS) {
                return NormalizedToolResult("MCP scheduler вернул слишком много задач.", emptyList())
            }
            val normalized = buildJsonArray {
                tasks.forEach { value ->
                    val task = value as? JsonObject ?: return@forEach
                    val id = task["id"]?.jsonPrimitive?.contentOrNull
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                        ?: return@forEach
                    val alias = "task-${schedulerAliases.size + 1}"
                    schedulerAliases[alias] = id
                    add(
                        buildJsonObject {
                            put("task_alias", alias)
                            task["name"]?.let { put("name", it) }
                            task["status"]?.let { put("status", it) }
                            task["start_date"]?.let { put("start_date", it) }
                            task["interval_minutes"]?.let { put("interval_minutes", it) }
                            task["time_zone"]?.let { put("time_zone", it) }
                            task["cursor_date"]?.let { put("cursor_date", it) }
                            task["next_run_at_epoch_ms"]?.let { put("next_run_at_epoch_ms", it) }
                            (task["account_refs"] as? JsonArray)?.let {
                                put("account_count", JsonPrimitive(it.size))
                            }
                        },
                    )
                }
            }
            return NormalizedToolResult(
                text = buildJsonObject { put("tasks", normalized) }.toString(),
                candidates = emptyList(),
            )
        }
        if (tool == SCHEDULER_HISTORY) {
            val runs = (element as? JsonArray)
                ?: (element as? JsonObject)?.get("runs") as? JsonArray
                ?: return NormalizedToolResult("""{"runs":[]}""", emptyList())
            if (runs.size > MAX_NATIVE_RESULT_ITEMS) {
                return NormalizedToolResult("MCP scheduler вернул слишком много запусков.", emptyList())
            }
            val normalized = buildJsonArray {
                runs.forEach { value ->
                    val run = value as? JsonObject ?: return@forEach
                    add(
                        buildJsonObject {
                            run["status"]?.let { put("status", it) }
                            run["started_at_epoch_ms"]?.let { put("started_at_epoch_ms", it) }
                            run["finished_at_epoch_ms"]?.let { put("finished_at_epoch_ms", it) }
                            run["error"]?.let { put("error", it) }
                            val result = run["result"] as? JsonObject
                            result?.let {
                                listOf(
                                    "transaction_count",
                                    "receipt_candidate_count",
                                    "receipt_detail_count",
                                    "enriched_item_count",
                                    "unmatched_count",
                                    "ambiguous_count",
                                ).forEach { key -> it[key]?.let { valueForKey -> put(key, valueForKey) } }
                            }
                        },
                    )
                }
            }
            return NormalizedToolResult(
                text = buildJsonObject { put("runs", normalized) }.toString(),
                candidates = emptyList(),
            )
        }
        val task = element as? JsonObject
            ?: return NormalizedToolResult("{}", emptyList())
        return NormalizedToolResult(
            text = buildJsonObject {
                task["name"]?.let { put("name", it) }
                task["status"]?.let { put("status", it) }
                task["cursor_date"]?.let { put("cursor_date", it) }
                task["last_error"]?.let { put("last_error", it) }
            }.toString(),
            candidates = emptyList(),
        )
    }

    private fun normalizeAccounts(
        element: JsonElement,
        aliases: MutableMap<String, String>,
        aliasBases: MutableMap<String, String>,
    ): NormalizedToolResult {
        aliases.clear()
        aliasBases.clear()
        val accounts = accountObjects(element)
        if (accounts.size > MAX_NATIVE_RESULT_ITEMS) {
            return NormalizedToolResult(
                "MCP tool вернул слишком много счетов.",
                emptyList(),
                isError = true,
            )
        }
        val eligibleAccounts = accounts.mapNotNull { account ->
            val ref = accountReference(account) ?: return@mapNotNull null
            val baseAlias = accountName(account).ifBlank { "Счёт" }
            Triple(account, ref, baseAlias)
        }
        val baseCounts = eligibleAccounts
            .groupingBy { it.third.lowercase(java.util.Locale.ROOT) }
            .eachCount()
        val aliasOccurrences = mutableMapOf<String, Int>()
        val used = mutableSetOf<String>()
        val normalized = eligibleAccounts.map { (account, ref, baseAlias) ->
            val baseKey = baseAlias.lowercase(java.util.Locale.ROOT)
            val alias = if ((baseCounts[baseKey] ?: 0) > 1) {
                val ordinal = (aliasOccurrences[baseKey] ?: 0) + 1
                aliasOccurrences[baseKey] = ordinal
                "$baseAlias #$ordinal"
            } else {
                baseAlias
            }
            var uniqueAlias = alias
            var collisionSuffix = 2
            while (!used.add(uniqueAlias.lowercase(java.util.Locale.ROOT))) {
                uniqueAlias = "$alias ($collisionSuffix)"
                collisionSuffix += 1
            }
            aliases[uniqueAlias] = ref
            aliasBases[uniqueAlias] = baseAlias
            buildJsonObject {
                put("account_alias", uniqueAlias)
                put("name", accountName(account).ifBlank { uniqueAlias })
                val currency = normalizeCurrencyCode(
                    account["currency"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
                if (currency.isNotBlank()) {
                    put("currency", currency)
                }
                account["balance_minor"]?.jsonPrimitive?.longOrNull?.let { balanceMinor ->
                    put("balance", formatMinorAmount(balanceMinor, currency))
                }
            }
        }
        return NormalizedToolResult(
            text = buildJsonObject { putJsonArray("accounts") { normalized.forEach(::add) } }.toString(),
            candidates = emptyList(),
        )
    }
    private fun formatMinorAmount(amountMinor: Long, currency: String): String =
        "${BigDecimal.valueOf(amountMinor, 2).toPlainString()} $currency"

    private fun accountObjects(element: JsonElement, depth: Int = 0): List<JsonObject> {
        require(depth <= MAX_NATIVE_JSON_DEPTH) { "MCP tool вернул слишком глубокий ответ." }
        return when (element) {
            is JsonArray -> {
                require(element.size <= MAX_NATIVE_RESULT_ITEMS) {
                    "MCP tool вернул слишком много счетов."
                }
                element.flatMap { accountObjects(it, depth + 1) }
            }
            is JsonObject -> {
                if (accountReference(element) != null) {
                    listOf(element)
                } else {
                    listOf(
                        "accounts",
                        "account_list",
                        "items",
                        "payload",
                        "data",
                        "result",
                        "response",
                        "content",
                        "text",
                        "structuredContent",
                        "structured_content",
                    ).asSequence()
                        .mapNotNull(element::get)
                        .flatMap { accountObjects(it, depth + 1).asSequence() }
                        .toList()
                }
            }
            is JsonPrimitive -> element.contentOrNull
                ?.let { encoded -> runCatching { json.parseToJsonElement(encoded) }.getOrNull() }
                ?.let { accountObjects(it, depth + 1) }
                .orEmpty()
            else -> emptyList()
        }
    }

    private fun accountReference(account: JsonObject): String? =
        sequenceOf("account_ref", "account_id", "accountId", "id", "ref")
            .mapNotNull { key -> account[key]?.jsonPrimitive?.contentOrNull?.trim() }
            .firstOrNull { it.isNotBlank() && it.length <= 200 }
    private fun accountName(account: JsonObject): String =
        sequenceOf("name", "account_name", "accountName", "display_name", "displayName", "title", "alias")
            .mapNotNull { key -> account[key]?.jsonPrimitive?.contentOrNull?.trim() }
            .firstOrNull(String::isNotBlank)
            .orEmpty()

    private fun normalizeTransactions(
        element: JsonElement,
        arguments: JsonObject,
    ): NormalizedToolResult {
        val root = element as? JsonObject
        val transactions = when {
            root?.get("transactions") is JsonArray -> root["transactions"]!!.jsonArray
            element is JsonArray -> element
            else -> return NormalizedToolResult(
                "MCP tool вернул некорректный ответ.",
                emptyList(),
                isError = true,
            )
        }
        if (transactions.size > MAX_NATIVE_RESULT_ITEMS) {
            return NormalizedToolResult(
                "MCP tool вернул слишком много операций.",
                emptyList(),
                isError = true,
            )
        }
        val alias = arguments["account_alias"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val sourceLabel = accountLabel(root, alias)
        val candidates = transactions.map { item ->
            val transaction = item as? JsonObject
                ?: throw AgentResponseException("MCP tool вернул некорректную операцию.")
            val amount = transaction["amount_minor"]?.jsonPrimitive?.longOrNull
                ?.takeIf { it != Long.MIN_VALUE }
                ?: throw AgentResponseException("MCP tool вернул операцию без корректной суммы.")
            val date = transaction["date"]?.jsonPrimitive?.contentOrNull
                ?: transaction["occurred_at"]?.jsonPrimitive?.contentOrNull
                ?: throw AgentResponseException("MCP tool вернул операцию без даты.")
            validateNativeDate(date)
            val postedAt = transaction["posted_at"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.also(::validateNativeDate)
            val currency = normalizeCurrencyCode(
                transaction["currency"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
            require(NATIVE_CURRENCY_PATTERN.matches(currency)) {
                "MCP tool вернул операцию с некорректной валютой."
            }
            val description = transactionDescription(transaction)
            val merchant = transactionMerchant(transaction)
            ExternalTransactionCandidate(
                occurredAt = date,
                postedAt = postedAt,
                amountMinor = amount,
                currency = currency,
                merchant = merchant,
                description = description,
                sourceLabel = sourceLabel,
                sourceRef = transaction["transaction_ref"]?.jsonPrimitive?.contentOrNull
                    ?.trim()
                    ?.takeIf(String::isNotBlank),
            )
        }
        val normalizedTransactions = transactions.map { item ->
            val transaction = item as? JsonObject
                ?: return@map JsonObject(emptyMap())
            val description = transactionDescription(transaction)
            val merchant = transactionMerchant(transaction)
            buildJsonObject {
                transaction.forEach { (key, value) ->
                    val normalizedKey = key.lowercase(java.util.Locale.ROOT)
                    if (normalizedKey !in FORBIDDEN_KEYS &&
                        normalizedKey !in setOf("account_ref", "account_id", "id")
                    ) {
                        when (normalizedKey) {
                            "merchant", "merchant_name", "shop_name" ->
                                put(key, JsonPrimitive(merchant))
                            "currency", "currency_code" ->
                                put(key, JsonPrimitive(normalizeCurrencyCode(value.jsonPrimitive.contentOrNull.orEmpty())))
                            "description" ->
                                put(key, JsonPrimitive(description))
                            else -> put(key, redactJson(value))
                        }
                    }
                }
            }
        }
        val normalizedRoot = buildJsonObject {
            if (root != null) {
                root.forEach { (key, value) ->
                    val normalizedKey = key.lowercase(java.util.Locale.ROOT)
                    if (normalizedKey != "transactions" &&
                        normalizedKey !in FORBIDDEN_KEYS &&
                        normalizedKey !in setOf("account_ref", "account_id")
                    ) {
                        put(key, redactJson(value))
                    }
                }
            }
            put("account_alias", JsonPrimitive(alias))
            put("transactions", JsonArray(normalizedTransactions))
        }
        return NormalizedToolResult(normalizedRoot.toString(), candidates)
    }

    private fun accountLabel(root: JsonObject?, alias: String): String {
        val named = sequenceOf(
            (root?.get("account") as? JsonObject)
                ?.get("name")
                ?.jsonPrimitive
                ?.contentOrNull,
            root?.get("account_name")?.jsonPrimitive?.contentOrNull,
            alias,
        ).mapNotNull(::humanLabel)
            .firstOrNull()
        if (named != null) return named

        return "Счёт / карта"
    }
    private fun sanitizeSourceText(value: String): String =
        RAW_RECEIPT_REFERENCE_PATTERN.replace(value.trim(), REDACTED_RECEIPT_REFERENCE)

    private fun transactionDescription(transaction: JsonObject): String =
        sanitizeSourceText(transaction["description"]?.jsonPrimitive?.contentOrNull.orEmpty())

    private fun transactionMerchant(transaction: JsonObject): String =
        humanLabel(
            sanitizeSourceText(transaction["merchant"]?.jsonPrimitive?.contentOrNull.orEmpty()),
        ) ?: humanLabel(transactionDescription(transaction))
            ?: "Операция"

    private fun humanLabel(value: String?): String? =
        value?.trim()?.takeIf { it.isNotBlank() && !it.all(Char::isDigit) }
    private fun normalizeCurrencyCode(value: String): String = when (value.trim().uppercase()) {
        "643" -> "RUB"
        "840" -> "USD"
        "978" -> "EUR"
        "826" -> "GBP"
        "156" -> "CNY"
        else -> value.trim().uppercase()
    }
    private fun validateNativeDate(value: String) {
        val normalized = value.trim()
        require(normalized.length in 1..80) { "MCP tool вернул слишком длинную дату." }
        runCatching { LocalDate.parse(normalized) }
            .recoverCatching { java.time.OffsetDateTime.parse(normalized).toLocalDate() }
            .recoverCatching { java.time.LocalDateTime.parse(normalized).toLocalDate() }
            .getOrElse { throw AgentResponseException("MCP tool вернул некорректную дату.") }
    }


private fun modelSchema(tool: McpCallableTool): JsonObject {
    if (tool.serverId == SCHEDULER_SERVER_ID) {
        if (tool.name == SCHEDULER_RUN || tool.name == SCHEDULER_HISTORY) {
            val schema = tool.inputSchema.toMutableMap()
            val properties = (schema["properties"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            properties.remove("task_id")
            properties["task_alias"] = buildJsonObject {
                put("type", "string")
                put("description", "Непрозрачный alias из результата list-scheduled-tasks")
            }
            schema["properties"] = JsonObject(properties)
            schema["required"] = JsonArray(listOf(JsonPrimitive("task_alias")))
            return JsonObject(schema)
        }
        return JsonObject(tool.inputSchema)
    }
    return redactJson(tool.inputSchema).jsonObject
}

private fun buildDescription(tool: McpCallableTool): String = buildString {
    append(tool.serverDisplayName)
    tool.description?.trim()?.takeIf(String::isNotBlank)?.let {
        append(": ")
        append(it)
    }
    append(" Не раскрывай сырые идентификаторы и секреты.")
}

private fun functionName(serverId: String, tool: String): String =
    "mcp_${serverId}_${tool}".replace(Regex("[^A-Za-z0-9_-]"), "_")

    private inner class TbankTransactionSourceAdapter(
        private val availableTools: List<McpCallableTool>,
    ) : NativeMcpTransactionSourceAdapter {
        private val accountAliases = linkedMapOf<String, String>()
        private val accountAliasBases = linkedMapOf<String, String>()

        override fun supports(tool: McpCallableTool): Boolean =
            tool.serverId in setOf(TBANK_SERVER_ID, LEGACY_TBANK_SERVER_ID) &&
                tool.name in setOf(TBANK_LIST_ACCOUNTS, TBANK_GET_TRANSACTIONS)

        override fun toolDefinition(tool: McpCallableTool): ChatToolDefinition =
            ChatToolDefinition(
                function = ChatFunctionDefinition(
                    name = functionName(tool.serverId, tool.name),
                    description = buildString {
                        append(tool.serverDisplayName)
                        tool.description?.trim()?.takeIf(String::isNotBlank)?.let {
                            append(": ")
                            append(it)
                        }
                        append(" Не раскрывай сырые идентификаторы и секреты.")
                    },
                    parameters = tbankModelSchema(tool),
                ),
            )

        override fun prepareArguments(
            tool: McpCallableTool,
            arguments: JsonObject,
        ): JsonObject {
            if (tool.name != TBANK_GET_TRANSACTIONS) {
                return redactJson(arguments).jsonObject
            }
            require("account_ref" !in arguments && "account_id" !in arguments) {
                "MCP tool должен использовать account_alias, а не идентификатор счёта."
            }
            val alias = arguments["account_alias"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: throw AgentResponseException("Для операций нужен account_alias из list-accounts.")
            return JsonObject(arguments + ("account_alias" to JsonPrimitive(alias)))
        }

        override suspend fun prepareArgumentsForExecution(
            tool: McpCallableTool,
            arguments: JsonObject,
            userText: String,
        ): NativeMcpArgumentPreparation {
            val prepared = prepareArguments(tool, arguments)
            if (tool.name != TBANK_GET_TRANSACTIONS) {
                return NativeMcpArgumentPreparation.Ready(prepared)
            }
            val alias = prepared["account_alias"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (accountAliases.isEmpty()) {
                val listTool = availableTools.firstOrNull {
                    it.serverId == tool.serverId && it.name == TBANK_LIST_ACCOUNTS
                } ?: return NativeMcpArgumentPreparation.Clarification(
                    "Не удалось получить список доступных счетов. Проверьте подключение и повторите запрос.",
                )
                val response = try {
                    withTimeout(runtimeConfig.mcpToolLoop.callTimeoutMs) {
                        mcpTools.callConfiguredTool(
                            serverId = listTool.serverId,
                            tool = listTool.name,
                            arguments = buildJsonObject {},
                        )
                    }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    return NativeMcpArgumentPreparation.Clarification(
                        "Не удалось получить список доступных счетов. Проверьте подключение и повторите запрос.",
                    )
                }
                val normalized = normalize(
                    listTool,
                    buildJsonObject {},
                    NativeMcpToolCallResult(response.isError, response.text),
                )
                if (normalized.isError || accountAliases.isEmpty()) {
                    return NativeMcpArgumentPreparation.Clarification(
                        "Не удалось получить список доступных счетов. Проверьте подключение и повторите запрос.",
                    )
                }
            }
            val accountRef = resolveAccountRef(alias, userText)
                ?: return NativeMcpArgumentPreparation.Clarification(
                    "Уточните, какой счёт использовать: " +
                        accountAliases.keys.joinToString("、") { "«$it»" } +
                        ".",
                )
            val sourceArguments = prepared.toMutableMap()
            sourceArguments.remove("account_alias")
            sourceArguments["account_ref"] = JsonPrimitive(accountRef)
            return NativeMcpArgumentPreparation.Ready(JsonObject(sourceArguments))
        }

        private fun resolveAccountRef(alias: String, userText: String): String? {
            val normalizedAlias = accountAliases.keys.singleOrNull {
                it.equals(alias, ignoreCase = true)
            } ?: return null
            val baseAlias = accountAliasBases[normalizedAlias] ?: normalizedAlias
            val duplicateName = accountAliasBases.values.count {
                it.equals(baseAlias, ignoreCase = true)
            } > 1
            if (!duplicateName) return accountAliases[normalizedAlias]
            if (normalizedAlias.equals(baseAlias, ignoreCase = true)) return null
            if (!userText.contains(normalizedAlias, ignoreCase = true)) return null
            return accountAliases[normalizedAlias]
        }

        override suspend fun normalize(
            tool: McpCallableTool,
            arguments: JsonObject,
            result: NativeMcpToolCallResult,
        ): NativeMcpTransactionSourceResult {
            if (result.isError) {
                return NativeMcpTransactionSourceResult(
                    "MCP tool завершился ошибкой.",
                    emptyList(),
                    isError = true,
                )
            }
            if (result.text.length > MAX_NATIVE_RESULT_CHARS) {
                return NativeMcpTransactionSourceResult(
                    "MCP tool вернул слишком большой ответ.",
                    emptyList(),
                    isError = true,
                )
            }
            val element = runCatching { json.parseToJsonElement(result.text) }.getOrElse {
                return NativeMcpTransactionSourceResult(
                    "MCP tool вернул некорректный ответ.",
                    emptyList(),
                    isError = true,
                )
            }
            val redactedValues = sensitiveValues(element)
            val normalized = when (tool.name) {
                TBANK_LIST_ACCOUNTS -> normalizeAccounts(element, accountAliases, accountAliasBases)
                TBANK_GET_TRANSACTIONS -> normalizeTransactions(element, arguments)
                else -> NormalizedToolResult(redactJson(element).toString(), emptyList())
            }
            return NativeMcpTransactionSourceResult(
                text = normalized.text,
                candidates = normalized.candidates,
                redactedValues = redactedValues + normalized.redactedValues,
                isError = normalized.isError,
            )
        }

        override fun isTransactionFetch(tool: McpCallableTool): Boolean =
            tool.name == TBANK_GET_TRANSACTIONS

        private fun tbankModelSchema(tool: McpCallableTool): JsonObject {
            if (tool.name == TBANK_LIST_ACCOUNTS) {
                return redactJson(tool.inputSchema).jsonObject
            }
            val schema = tool.inputSchema.toMutableMap()
            val properties = (schema["properties"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            properties.remove("account_ref")
            properties.remove("account_id")
            properties["account_alias"] = buildJsonObject {
                put("type", "string")
                put("description", "Отображаемый алиас счёта из списка list-accounts; перед fetch сопоставляется приложением, не передавай идентификаторы.")
            }
            schema["properties"] = JsonObject(properties)
            val required = (schema["required"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.map { if (it == "account_ref" || it == "account_id") "account_alias" else it }
                ?.plus("account_alias")
                ?.distinct()
                ?.takeIf { it.isNotEmpty() }
                ?: listOf("account_alias", "from", "to")
            schema["required"] = JsonArray(required.map(::JsonPrimitive))
            return JsonObject(schema)
        }
    }



    private fun hasExplicitSchedulerRunIntent(text: String): Boolean {
        val normalized = text.trim().lowercase()
        return listOf(
            "запусти",
            "запустить",
            "выполни",
            "run scheduled",
            "run-scheduled-task",
            "start scheduled",
        ).any(normalized::contains)
    }


    private fun pendingPreviewFor(sessionId: String, revision: Long): PendingPreview? =
        pendingPreviews.values.firstOrNull {
            it.sessionId == sessionId && it.revision == revision
        }

    private fun requireRevision(state: ImportSessionState, expectedRevision: Long) {
        if (state.session.revision != expectedRevision) {
            throw io.github.stolex1y.transactionimport.core.RevisionConflictException(
                expected = expectedRevision,
                actual = state.session.revision,
            )
        }
    }

    private data class PendingPreview(
        val sessionId: String,
        val revision: Long,
        val userText: String,
        val candidates: List<ExternalTransactionCandidate>,
        val fetchArguments: JsonObject? = null,
        val receiptMatching: McpReceiptMatchingSummary? = null,
    )

    private sealed interface PreparedPlanCall {
        val call: ChatToolCall

        data class Route(override val call: ChatToolCall) : PreparedPlanCall

        data class AssociateReceipts(
            override val call: ChatToolCall,
            val rememberRule: Boolean,
        ) : PreparedPlanCall

        data class ReadReceiptCatalog(
            override val call: ChatToolCall,
        ) : PreparedPlanCall

        data class ReadReceiptInfo(
            override val call: ChatToolCall,
            val rowId: String,
            val fields: Set<String>,
        ) : PreparedPlanCall

        data class Mcp(
            override val call: ChatToolCall,
            val rawArguments: JsonObject,
            val binding: McpCallableTool,
            val sourceAdapter: NativeMcpTransactionSourceAdapter?,
            val sourceArguments: JsonObject,
        ) : PreparedPlanCall
    }

    private data class NormalizedToolResult(
        val text: String,
        val candidates: List<ExternalTransactionCandidate>,
        val redactedValues: Set<String> = emptySet(),
        val isError: Boolean = false,
    )

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

        private val FORBIDDEN_KEYS = setOf(
            "account_id",
            "accountid",
            "account_ref",
            "accountref",
            "account_refs",
            "accountrefs",
            "target_session_id",
            "targetsessionid",
            "task_id",
            "taskid",
            "run_id",
            "runid",
            "cursor_date",
            "cursordate",
            "next_cursor",
            "nextcursor",
            "source_ref",
            "sourceref",
            "session_id",
            "sessionid",
            "access_token",
            "refresh_token",
            "token",
            "cookie",
            "password",
            "otp",
            "phone",
            "card_number",
            "pan",
            "requisites",
            "transaction_id",
            "transactionid",
            "transaction_ref",
            "transactionref",
            "operation_id",
            "operationid",
            "receipt_key",
            "receiptkey",
            "fiscal_id",
            "fiscalid",
            "fiscal_document_number",
            "fiscaldocumentnumber",
            "fiscal_drive_number",
            "fiscaldrivenumber",
            "fiscal_sign",
            "fiscalsign",
            "kkt_reg_id",
            "kktregid",
        )

        private val SENSITIVE_VALUE_KEYS = FORBIDDEN_KEYS + setOf(
            "id",
            "transaction_id",
            "transactionid",
            "operation_id",
            "operationid",
        )

        private val sensitiveTextPattern = Regex(
            "(?i)\\b(access[_-]?token|refresh[_-]?token|session[_-]?id|account[_-]?(?:id|ref)|transaction[_-]?(?:id|ref)|operation[_-]?id|receipt[_-]?key|fiscal[_-]?(?:id|document[_-]?number|drive[_-]?number|sign)|kkt[_-]?reg[_-]?id|cookie|password|otp|phone|card[_-]?number|pan|requisites)\\b\\s*[:=]\\s*[^,;\\s]+",
        )

        private fun sanitizeAssistantText(text: String, redactedValues: Set<String>): String {
            var sanitized = redactText(text)
            redactedValues
                .filter { it.length >= 3 }
                .sortedByDescending(String::length)
                .forEach { value -> sanitized = sanitized.replace(value, "[скрыто]") }
            return sanitized.take(20_000)
        }

        private fun sensitiveValues(element: JsonElement): Set<String> = when (element) {
            is JsonObject -> buildSet {
                element.forEach { (key, value) ->
                    if (key.lowercase() in SENSITIVE_VALUE_KEYS) {
                        (value as? JsonPrimitive)?.contentOrNull
                            ?.takeIf(String::isNotBlank)
                            ?.let(::add)
                    }
                    addAll(sensitiveValues(value))
                }
            }
            is JsonArray -> buildSet {
                element.forEach { addAll(sensitiveValues(it)) }
            }
            is JsonNull, is JsonPrimitive -> emptySet()
        }

        private fun redactText(text: String): String =
            text.replace(sensitiveTextPattern) { "${it.value.substringBefore(':').substringBefore('=')}=[скрыто]" }
                .take(20_000)

        private fun redactJson(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> buildJsonObject {
                element.forEach { (key, value) ->
                    if (key.lowercase() in FORBIDDEN_KEYS) {
                        put(key, JsonPrimitive("[скрыто]"))
                    } else {
                        put(key, redactJson(value))
                    }
                }
            }
            is JsonArray -> buildJsonArray { element.forEach { add(redactJson(it)) } }
            is JsonNull, is JsonPrimitive -> element
        }
    }
}
private data class ReceiptFingerprint(
    val summary: io.github.stolex1y.transactionimport.core.ReceiptAssociationSummary,
    val items: List<TransactionItem>,
)

private data class ReceiptUsage(
    val fingerprints: Set<ReceiptFingerprint>,
    val hasUnidentifiableMatchedReceipt: Boolean,
)

private fun ImportSessionState.receiptUsage(exceptRowId: String? = null): ReceiptUsage {
    val matchedRows = draft?.transactions.orEmpty().filter { row ->
        row.id != exceptRowId && row.transaction.receiptAssociation?.status == ReceiptAssociationStatus.MATCHED
    }
    val fingerprints = matchedRows.mapNotNull { row ->
        val summary = row.transaction.receiptAssociation?.summary ?: return@mapNotNull null
        ReceiptFingerprint(summary, row.transaction.items)
    }.toSet()
    return ReceiptUsage(
        fingerprints = fingerprints,
        hasUnidentifiableMatchedReceipt = matchedRows.any {
            it.transaction.receiptAssociation?.summary == null
        },
    )
}

private fun List<ExternalTransactionCandidate>.withReceiptReuseGuard(
    receiptUsage: ReceiptUsage,
): List<ExternalTransactionCandidate> {
    val incomingFingerprintCounts = mapNotNull { candidate ->
        val association = candidate.receiptAssociation
        if (association?.status != ReceiptAssociationStatus.MATCHED) {
            null
        } else {
            association.summary?.let { ReceiptFingerprint(it, candidate.items) }
        }
    }.groupingBy { it }.eachCount()
    val used = receiptUsage.fingerprints.toMutableSet()
    return map { candidate ->
        val association = candidate.receiptAssociation
        if (association?.status != ReceiptAssociationStatus.MATCHED) {
            candidate
        } else {
            val fingerprint = association.summary?.let { ReceiptFingerprint(it, candidate.items) }
            if (
                receiptUsage.hasUnidentifiableMatchedReceipt ||
                fingerprint == null ||
                incomingFingerprintCounts[fingerprint] != 1 ||
                fingerprint in used
            ) {
                val selectedSummary = association.summary
                candidate.copy(
                    items = emptyList(),
                    receiptAssociation = ReceiptAssociation(
                        status = ReceiptAssociationStatus.AMBIGUOUS,
                        review = ReceiptReviewEvidence(
                            reason = ReceiptReviewReason.DUPLICATE_SELECTION,
                            candidates = listOfNotNull(selectedSummary),
                        ),
                    ),
                )
            } else {
                used += fingerprint
                candidate
            }
        }
    }
}
