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
                  "category_id": "food.groceries",
                  "card_last4": "1234",
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
                  "card_last4": null,
                  "needs_review": true,
                  "issues": ["Нужно выбрать категорию"]
                }
              ],
              "unparsed_fragments": []
            }
        """.trimIndent()

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
                  "card_last4": "1234",
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
                  "card_last4": null,
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
    gateway: FakeAgentGateway = FakeAgentGateway(),
    contextManagement: ContextManagementConfig = ContextManagementConfig(
        strategy = ContextStrategy.SUMMARY,
        recentMessages = 4,
        summaryBatchMessages = 4,
        summaryMaxTokens = 1_024,
    ),
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
    return AgentWebDependencies(
        agent = agent,
        catalog = catalog,
        runtimeConfig = runtime,
        availableProviderIds = setOf("fake"),
        newSessionTitle = { "Тестовый импорт" },
    )
}
