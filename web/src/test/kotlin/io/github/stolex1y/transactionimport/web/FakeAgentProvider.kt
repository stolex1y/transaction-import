package io.github.stolex1y.transactionimport.web

import io.github.stolex1y.transactionimport.core.AgentGatewayResolver
import io.github.stolex1y.transactionimport.core.AgentRuntimeConfig
import io.github.stolex1y.transactionimport.core.ChatChoice
import io.github.stolex1y.transactionimport.core.ContextManagementConfig
import io.github.stolex1y.transactionimport.core.ContextStrategy
import io.github.stolex1y.transactionimport.core.ChatCompletionGateway
import io.github.stolex1y.transactionimport.core.ChatCompletionRequest
import io.github.stolex1y.transactionimport.core.ChatCompletionResponse
import io.github.stolex1y.transactionimport.core.ContextWindowExceededException
import io.github.stolex1y.transactionimport.core.ProviderCatalog
import io.github.stolex1y.transactionimport.core.ProviderDefinition
import io.github.stolex1y.transactionimport.core.ProviderModelDefinition
import io.github.stolex1y.transactionimport.core.ReasoningModeDefinition
import io.github.stolex1y.transactionimport.core.ResponseMessage
import io.github.stolex1y.transactionimport.core.SmartExpenseAgent
import io.github.stolex1y.transactionimport.core.Usage
import io.github.stolex1y.transactionimport.persistence.SqliteImportSessionRepository
import io.github.stolex1y.transactionimport.persistence.SqliteSchedulerRepository
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicInteger

class FakeAgentGateway(
    private val responses: ArrayDeque<String> = ArrayDeque(listOf(READY_DRAFT_JSON)),
    override val contextWindowTokens: Int? = DEFAULT_CONTEXT_WINDOW_TOKENS,
) : ChatCompletionGateway {
    val requests = mutableListOf<ChatCompletionRequest>()

    override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
        requests += request
        val promptTokens = request.messages.sumOf { it.content.length }
        val completionTokens = 84
        if (contextWindowTokens != null && promptTokens + completionTokens > contextWindowTokens) {
            throw ContextWindowExceededException()
        }
        val content = responses.removeFirstOrNull()
            ?: if (request.messages.any { it.role == "assistant" }) FOLLOW_UP_NOOP_JSON else READY_DRAFT_JSON
        return ChatCompletionResponse(
            choices = listOf(
                ChatChoice(
                    message = ResponseMessage(content = content),
                    finishReason = "stop",
                ),
            ),
            usage = Usage(
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                totalTokens = promptTokens + completionTokens,
            ),
        )
    }

    companion object {
        val READY_DRAFT_JSON = """
            {
              "status": "ready",
              "rejection_reason": null,
              "transactions": [
                {
                  "source_index": 1,
                  "direction": "expense",
                  "occurred_at": "2026-02-08T12:10:00",
                  "included": true,
                  "posted_at": null,
                  "amount_minor": 125050,
                  "currency": "RUB",
                  "merchant": "ДЕМО МАРКЕТ",
                  "description": "Покупка яблок",
                  "category_id": "food.groceries",
                  "needs_review": false,
                  "issues": []
                },
                {
                  "source_index": 2,
                  "direction": "expense",
                  "occurred_at": "2026-02-08T18:45:00",
                  "posted_at": null,
                  "included": true,
                  "amount_minor": 9900,
                  "currency": "RUB",
                  "merchant": "НЕИЗВЕСТНЫЙ ПЛАТЁЖ",
                  "category_id": null,
                  "needs_review": true,
                  "issues": ["Нужно выбрать категорию"]
                }
              ],
              "unparsed_fragments": []
            }
        """.trimIndent()

        val READY_DRAFT_WITH_MEMORY_CANDIDATE_JSON = READY_DRAFT_JSON.replace(
            "\"unparsed_fragments\": []",
            "\"unparsed_fragments\": [],\n  \"memory_candidates\": [{\"text\":\"Исключать переводы между своими счетами\",\"reason\":\"Правило явно подтверждается сообщением пользователя.\"}]",
        )

        val FOLLOW_UP_NOOP_JSON = """
            {
              "intent": "correction",
              "message": "Изменений не найдено.",
              "operations": [],
              "transactions": []
            }
        """.trimIndent()

        val APPEND_STATEMENT_JSON = """
            {
              "intent": "append_statement",
              "message": "Найдены операции во второй выписке.",
              "operations": [],
              "transactions": [
                {
                  "source_index": 40,
                  "direction": "expense",
                  "occurred_at": "2026-02-08T12:10:00",
                  "included": true,
                  "posted_at": null,
                  "amount_minor": 125050,
                  "currency": "RUB",
                  "merchant": "ДЕМО МАРКЕТ-ABC123",
                  "category_id": "food.groceries",
                  "needs_review": false,
                  "issues": []
                },
                {
                  "source_index": 41,
                  "direction": "expense",
                  "occurred_at": "2026-02-09T10:00:00",
                  "included": true,
                  "posted_at": null,
                  "amount_minor": 35000,
                  "currency": "RUB",
                  "merchant": "НОВЫЙ КАФЕ",
                  "category_id": "food.cafes",
                  "needs_review": false,
                  "issues": []
                }
              ]
            }
        """.trimIndent()

        val NOT_APPLICABLE_JSON = """
            {
              "status": "not_applicable",
              "rejection_reason": "not used",
              "transactions": [],
              "unparsed_fragments": ["Это не финансовая операция"]
            }
        """.trimIndent()

        const val DEFAULT_CONTEXT_WINDOW_TOKENS = 32_000
    }
}

fun fakeAgentDependencies(
    databasePath: String,
    gateway: ChatCompletionGateway = FakeAgentGateway(),
    contextManagement: ContextManagementConfig = ContextManagementConfig(
        strategy = ContextStrategy.SUMMARY,
        recentMessages = 4,
        summaryBatchMessages = 4,
        summaryMaxTokens = 1_024,
    ),
    receiptsProxy: ReceiptsProxyService? = null,
    schedulerAccountAvailable: Boolean = false,
    schedulerNowEpochMs: () -> Long = { System.currentTimeMillis() },
    schedulerMatchSelector: ReceiptMatchSelector? = null,
): AgentWebDependencies {
    val catalog = ProviderCatalog(
        providers = listOf(
            ProviderDefinition(
                id = "fake",
                displayName = "Локальный fake-provider",
                baseUrl = "http://localhost",
                credentialEnv = "FAKE_API_KEY",
                models = listOf(
                    ProviderModelDefinition(
                        id = "fake-model",
                        displayName = "Deterministic Fake",
                        reasoningModes = listOf(
                            ReasoningModeDefinition(id = "disabled", displayName = "Без reasoning"),
                        ),
                        contextWindowTokens = gateway.contextWindowTokens
                            ?: FakeAgentGateway.DEFAULT_CONTEXT_WINDOW_TOKENS,
                    ),
                ),
            ),
        ),
    ).validated()
    val runtime = AgentRuntimeConfig(
        defaultProviderId = "fake",
        defaultModelId = "fake-model",
        defaultReasoningModeId = "disabled",
        maxTokens = 4_096,
        defaultUserPrompt = "",
        contextManagement = contextManagement,
    ).validated(catalog)
    val idSequence = AtomicInteger()
    val agent = SmartExpenseAgent(
        repository = SqliteImportSessionRepository(databasePath),
        gatewayResolver = AgentGatewayResolver { gateway },
        idGenerator = { "test-${idSequence.incrementAndGet()}" },
        nowEpochMs = { 1_770_000_000_000L + idSequence.get() },
        runtimeConfig = runtime,
        configValidator = { catalog.resolve(it) },
    )
    val scheduler = SchedulerService(
        repository = SqliteSchedulerRepository(databasePath),
        agent = agent,
        mcpTools = object : McpToolProvider {
            override suspend fun allowedTools() = emptyList<McpCallableTool>()

            override suspend fun callConfiguredTool(
                serverId: String,
                tool: String,
                arguments: kotlinx.serialization.json.JsonObject,
            ): TbankToolCallResponse {
                if (!schedulerAccountAvailable) {
                    return TbankToolCallResponse(tool = tool, isError = true, text = "fake source unavailable")
                }
                return when (serverId to tool) {
                    "tbank-transactions" to "list-accounts" -> TbankToolCallResponse(
                        tool = tool,
                        text = """{"accounts":[{"account_ref":"fixture-account","name":"Synthetic account"}]}""",
                    )
                    "tbank-transactions" to "get-account-transactions" -> {
                        val transactionList = when (arguments["from"]?.jsonPrimitive?.contentOrNull) {
                            "2026-09-10" ->
                                """[{"transaction_ref":"fixture-transaction-1","date":"2026-09-10T10:00:00Z","amount_minor":-499,"currency":"RUB","merchant":"Browser scheduled purchase","description":"Browser scheduled purchase"}]"""
                            "2026-09-11" ->
                                """[{"transaction_ref":"fixture-transaction-2","date":"2026-09-11T10:00:00Z","amount_minor":-350,"currency":"RUB","merchant":"Second scheduled purchase","description":"Second scheduled purchase"}]"""
                            else -> "[]"
                        }
                        TbankToolCallResponse(
                            tool = tool,
                            text = """{"account_name":"Synthetic account","transactions":$transactionList}""",
                        )
                    }
                    "receipts" to "search-receipts" -> {
                        val firstReceipt = """{"receipt_key":"fixture-receipt-private","merchant":"Browser scheduled purchase","received_at":"2026-09-10T10:05:00Z","amount_minor":499,"currency":"RUB"}"""
                        val secondReceipt = """{"receipt_key":"fixture-receipt-private-2","merchant":"Second scheduled purchase","received_at":"2026-09-11T10:05:00Z","amount_minor":350,"currency":"RUB"}"""
                        val receipts = when (arguments["from"]?.jsonPrimitive?.contentOrNull) {
                            "2026-09-09" -> firstReceipt
                            "2026-09-10" -> secondReceipt
                            else -> "$firstReceipt,$secondReceipt"
                        }
                        TbankToolCallResponse(
                            tool = tool,
                            text = """{"receipts":[$receipts],"has_more":false}""",
                        )
                    }
                    "receipts" to "get-receipt" -> {
                        val receiptKey = arguments["receipt_key"]?.jsonPrimitive?.contentOrNull
                        val second = receiptKey == "fixture-receipt-private-2"
                        val merchant = if (second) "Second scheduled purchase" else "Browser scheduled purchase"
                        val receivedAt = if (second) "2026-09-11T10:05:00Z" else "2026-09-10T10:05:00Z"
                        val amountMinor = if (second) 350 else 499
                        val itemName = if (second) "Second imported item" else "Imported item"
                        TbankToolCallResponse(
                            tool = tool,
                            text = """{"receipt_key":"$receiptKey","date_time":"$receivedAt","total_minor":$amountMinor,"currency":"RUB","merchant":"$merchant","settlement_place":"Синтетическая торговая точка","items":[{"name":"$itemName","quantity":1,"price_minor":$amountMinor,"sum_minor":$amountMinor}]}""",
                        )
                    }
                    else -> TbankToolCallResponse(tool = tool, isError = true, text = "unexpected fake source tool")
                }
            }
        },
        gatewayResolver = AgentGatewayResolver { gateway },
        runtimeConfig = runtime,
        nowEpochMs = schedulerNowEpochMs,
        matchSelector = schedulerMatchSelector ?: ReceiptMatchSelector { _, _, choices, _, _ ->
            choices.singleOrNull()?.let { ReceiptSelectionResponseView(it.alias, 0.96) }
        },
    )
    return AgentWebDependencies(
        agent = agent,
        catalog = catalog,
        runtimeConfig = runtime,
        availableProviderIds = setOf("fake"),
        newSessionTitle = { "Тестовый импорт" },
        scheduler = scheduler,
        tbankMcp = object : TbankMcpProvider by UnavailableTbankMcpProvider {
            override suspend fun callTool(request: TbankToolCallRequest) =
                if (schedulerAccountAvailable && request.tool == "list-accounts") {
                    TbankToolCallResponse(
                        tool = request.tool,
                        text = """{"accounts":[{"account_ref":"fixture-account","name":"Synthetic account"}]}""",
                    )
                } else {
                    UnavailableTbankMcpProvider.callTool(request)
                }
        },
        receiptsProxy = receiptsProxy,
    )
}
