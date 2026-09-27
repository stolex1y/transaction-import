package io.github.stolex1y.transactionimport.web

import io.github.stolex1y.transactionimport.core.AgentGatewayResolver
import io.github.stolex1y.transactionimport.core.AgentResponseException
import io.github.stolex1y.transactionimport.core.AgentRuntimeConfig
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
import io.github.stolex1y.transactionimport.core.RequestMessage
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
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.math.BigDecimal

private const val TBANK_SERVER_ID = "tbank-transactions"
private const val LEGACY_TBANK_SERVER_ID = "bank-transactions"
private const val TBANK_LIST_ACCOUNTS = "list-accounts"
private const val TBANK_GET_TRANSACTIONS = "get-account-transactions"
private const val RECEIPTS_SERVER_ID = "receipts"
private const val RECEIPTS_SEARCH = "search-receipts"
private const val RECEIPTS_GET = "get-receipt"
private const val MAX_HISTORY_MESSAGES = 8

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
)

@Serializable
data class McpPreview(
    val id: String,
    @SerialName("session_revision") val sessionRevision: Long,
    val transactions: List<McpPreviewTransaction>,
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
)
{
    private val pendingPreviews = ConcurrentHashMap<String, PendingPreview>()

    suspend fun handle(
        sessionId: String,
        expectedRevision: Long,
        text: String,
    ): NativeMcpHandlingResult {
        val loopConfig = runtimeConfig.mcpToolLoop
        if (!loopConfig.enabled) return NativeMcpHandlingResult.NotHandled
        val state = agent.getSession(sessionId)
        val pending = pendingPreviewFor(sessionId, expectedRevision)
        if (shouldRouteToRegularAgent(state, text, pending)) {
            return NativeMcpHandlingResult.NotHandled
        }
        val tools = mcpTools.allowedTools()
            .sortedWith(compareBy<McpCallableTool> { it.serverId }.thenBy { it.name })
        if (tools.isEmpty()) return NativeMcpHandlingResult.NotHandled
        val gateway = gatewayResolver.resolve(state.session.config)
        val bindings = tools.associateBy { functionName(it.serverId, it.name) }
        val messages = buildInitialMessages(state, text, pending)
        val functionDefinitions = tools.map { tool ->
            ChatToolDefinition(
                function = ChatFunctionDefinition(
                    name = functionName(tool.serverId, tool.name),
                    description = buildDescription(tool),
                    parameters = modelSchema(tool),
                ),
            )
        }
        val aliases = linkedMapOf<String, String>()
        val receiptAliases = linkedMapOf<String, String>()
        val candidates = mutableListOf<ExternalTransactionCandidate>()
        val redactedValues = mutableSetOf<String>()
        var response: ChatCompletionResponse
        var iterations = 0
        var toolCalls = 0
        var lastFetchArguments: JsonObject? = null

        while (true) {
            if (iterations >= loopConfig.maxIterations) {
                throw AgentResponseException(
                    "MCP-цикл достиг лимита итераций (${loopConfig.maxIterations}); " +
                        "операции не импортированы.",
                )
            }
            response = gateway.complete(
                request(
                    config = state.session.config,
                    gateway = gateway,
                    messages = messages,
                    tools = functionDefinitions,
                ),
            )
            iterations += 1
            val message = response.choices.firstOrNull()?.message
                ?: throw AgentResponseException("Провайдер не вернул сообщение для MCP-цикла.")
            val calls = message.toolCalls.orEmpty()
            if (calls.isEmpty()) {
                if (toolCalls == 0 && candidates.isEmpty()) {
                    return NativeMcpHandlingResult.NotHandled
                }
                val assistantText = sanitizeAssistantText(
                    message.content?.trim().orEmpty().ifBlank {
                        if (candidates.isNotEmpty()) {
                            "Операции получены. Проверьте предварительный просмотр перед импортом."
                        } else {
                            "Не удалось получить операции из подключённых MCP-серверов."
                        }
                    },
                    redactedValues,
                )
                val classification = agent.classifyExternalTransactionsWithProposals(
                    sessionId = sessionId,
                    candidates = candidates,
                    userText = text,
                )
                val savedState = agent.recordExternalExchange(
                    sessionId = sessionId,
                    expectedRevision = expectedRevision,
                    userText = text,
                    assistantText = assistantText,
                    metric = metric(state, response),
                    merchantCanonicalProposals = classification.merchantCanonicalProposals,
                )
                val preview = createPreviewIfNeeded(
                    sessionId = sessionId,
                    revision = savedState.session.revision,
                    userText = text,
                    candidates = classification.candidates,
                    fetchArguments = lastFetchArguments,
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
            toolCalls += calls.size
            messages += RequestMessage(
                role = "assistant",
                content = sanitizeAssistantText(message.content.orEmpty(), redactedValues),
                toolCalls = calls,
            )
            calls.forEach { call ->
                val binding = bindings[call.function.name]
                    ?: throw AgentResponseException("Модель запросила неизвестный MCP tool.")
                val rawArguments = parseArguments(call.function)
                if (
                    binding.name == TBANK_GET_TRANSACTIONS &&
                    binding.serverId in setOf(TBANK_SERVER_ID, LEGACY_TBANK_SERVER_ID)
                ) {
                    lastFetchArguments = rawArguments
                }
                val sourceArguments = prepareArguments(binding, rawArguments, aliases, receiptAliases)
                val result = withTimeout(loopConfig.callTimeoutMs) {
                    mcpTools.callConfiguredTool(
                        serverId = binding.serverId,
                        tool = binding.name,
                        arguments = sourceArguments,
                    )
                }
                val normalized = normalizeResult(
                    binding = binding,
                    arguments = rawArguments,
                    result = result,
                    aliases = aliases,
                    receiptAliases = receiptAliases,
                )
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
            )
        },
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
                "Предыдущие аргументы get-account-transactions (данные уже редактированы): " +
                    (arguments?.let(::redactJson) ?: "не сохранены"),
            )
            durationHint?.let(::appendLine)
            appendLine(
                "Если пользователь уточняет счёт, период или добавляет ещё один счёт, " +
                    "запусти новый native bank fetch с исправленными параметрами. " +
                    "Не проси прислать выписку и не меняй старый preview.",
            )
        }.trim()
    }

    private fun buildInitialMessages(
        state: ImportSessionState,
        text: String,
        pending: PendingPreview? = null,
    ): MutableList<RequestMessage> = buildList {
        add(
            RequestMessage(
                role = "system",
                content = """
                    Ты маршрутизатор read-only MCP для импорта банковских операций.
                    Используй только выданные tools и не проси секреты, пароли, OTP,
                    реквизиты или сырые идентификаторы. Для выбора счёта используй
                    account_alias из list-accounts и передавай его в get-account-transactions.
                    Все поля с суффиксом _minor содержат целые минимальные денежные единицы:
                    для RUB 876027 означает 8760.27 RUB. Не показывай _minor как сумму
                    в рублях; используй человекочитаемое поле balance или пересчитай его.
                    Вызывай tools только если текущий запрос явно просит получить,
                    обновить, перечислить или добавить банковские операции. Если в
                    текущей сессии уже есть draft, запрос просит исправить,
                    переименовать, перекатегоризировать операции или применить правило
                    к этому draft — не вызывай tools: передай запрос обычному агенту
                    приложения. Добавление операций за другой счёт или период — это
                    bank fetch: получи preview, но не считай импорт завершённым.
                    После получения операций кратко объясни результат. Ничего не импортируй
                    и не утверждай, что импорт завершён: приложение покажет preview и спросит
                    подтверждение пользователя.
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

    private fun prepareArguments(
        binding: McpCallableTool,
        arguments: JsonObject,
        aliases: Map<String, String>,
        receiptAliases: Map<String, String>,
    ): JsonObject {
        if (binding.serverId == RECEIPTS_SERVER_ID && binding.name == RECEIPTS_GET) {
            require("receipt_key" !in arguments) {
                "MCP tool должен использовать receipt_alias, а не ключ чека."
            }
            val alias = arguments["receipt_alias"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: throw AgentResponseException("Для деталей нужен receipt_alias из search-receipts.")
            val receiptKey = receiptAliases[alias]
                ?: throw AgentResponseException("Неизвестный receipt_alias; сначала запросите search-receipts.")
            val sanitized = arguments.toMutableMap()
            sanitized.remove("receipt_alias")
            sanitized["receipt_key"] = JsonPrimitive(receiptKey)
            return JsonObject(sanitized)
        }
        if (!isTbank(binding.serverId) || binding.name != TBANK_GET_TRANSACTIONS) {
            return redactJson(arguments).jsonObject
        }
        require("account_ref" !in arguments && "account_id" !in arguments) {
            "MCP tool должен использовать account_alias, а не идентификатор счёта."
        }
        val alias = arguments["account_alias"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: throw AgentResponseException("Для операций нужен account_alias из list-accounts.")
        val accountRef = aliases[alias]
            ?: throw AgentResponseException("Неизвестный account_alias; сначала запросите list-accounts.")
        val sanitized = arguments.toMutableMap()
        sanitized.remove("account_alias")
        sanitized["account_ref"] = JsonPrimitive(accountRef)
        return JsonObject(sanitized)
    }
    private fun normalizeResult(
        binding: McpCallableTool,
        arguments: JsonObject,
        result: TbankToolCallResponse,
        aliases: MutableMap<String, String>,
        receiptAliases: MutableMap<String, String>,
    ): NormalizedToolResult {
        if (result.isError) {
            return NormalizedToolResult(
                text = "MCP tool завершился ошибкой: ${redactText(result.text)}",
                candidates = emptyList(),
            )
        }
        val element = runCatching { json.parseToJsonElement(result.text) }.getOrElse {
            return NormalizedToolResult(redactText(result.text), emptyList())
        }
        val redactedValues = sensitiveValues(element)
        if (binding.serverId == RECEIPTS_SERVER_ID) {
            val normalized = when (binding.name) {
                RECEIPTS_SEARCH -> normalizeReceiptSearch(element, receiptAliases)
                else -> NormalizedToolResult(redactJson(element).toString(), emptyList())
            }
            return normalized.copy(redactedValues = redactedValues)
        }
        if (!isTbank(binding.serverId)) {
            return NormalizedToolResult(
                text = redactJson(element).toString(),
                candidates = emptyList(),
                redactedValues = redactedValues,
            )
        }
        return when (binding.name) {
            TBANK_LIST_ACCOUNTS -> normalizeAccounts(element, aliases)
            TBANK_GET_TRANSACTIONS -> normalizeTransactions(element, arguments)
            else -> NormalizedToolResult(redactJson(element).toString(), emptyList())
        }.copy(redactedValues = redactedValues)
    }
    private fun normalizeReceiptSearch(
        element: JsonElement,
        receiptAliases: MutableMap<String, String>,
    ): NormalizedToolResult {
        val root = element as? JsonObject ?: return NormalizedToolResult("{}", emptyList())
        val receipts = root["receipts"] as? JsonArray ?: return NormalizedToolResult(
            text = buildJsonObject { putJsonArray("receipts") {} }.toString(),
            candidates = emptyList(),
        )
        val normalized = buildJsonArray {
            receipts.forEach { value ->
                val receipt = value as? JsonObject ?: return@forEach
                val key = receipt["receipt_key"]?.jsonPrimitive?.contentOrNull
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?: return@forEach
                var suffix = receiptAliases.size + 1
                var alias = "receipt-$suffix"
                while (receiptAliases.containsKey(alias)) {
                    suffix += 1
                    alias = "receipt-$suffix"
                }
                receiptAliases[alias] = key
                add(
                    buildJsonObject {
                        put("receipt_alias", alias)
                        receipt["merchant"]?.let { put("merchant", it) }
                        receipt["received_at"]?.let { put("received_at", it) }
                        receipt["amount_minor"]?.let { put("amount_minor", it) }
                        receipt["currency"]?.let { put("currency", it) }
                    },
                )
            }
        }
        return NormalizedToolResult(
            text = buildJsonObject {
                put("receipts", normalized)
                root["has_more"]?.let { put("has_more", it) }
            }.toString(),
            candidates = emptyList(),
        )
    }


    private fun normalizeAccounts(
        element: JsonElement,
        aliases: MutableMap<String, String>,
    ): NormalizedToolResult {
        val accounts = accountObjects(element)
        val used = aliases.keys.toMutableSet()
        val normalized = accounts.mapNotNull { account ->
            val ref = accountReference(account) ?: return@mapNotNull null
            val name = accountName(account)
            val baseAlias = name.ifBlank { "Счёт" }
            var alias = baseAlias
            var suffix = 2
            while (!used.add(alias)) {
                alias = "$baseAlias #$suffix"
                suffix += 1
            }
            aliases[alias] = ref
            buildJsonObject {
                put("account_alias", alias)
                put("name", name.ifBlank { alias })
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

    private fun accountObjects(element: JsonElement): List<JsonObject> = when (element) {
        is JsonArray -> element.flatMap(::accountObjects)
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
                    .flatMap { accountObjects(it).asSequence() }
                    .toList()
            }
        }
        is JsonPrimitive -> element.contentOrNull
            ?.let { encoded -> runCatching { json.parseToJsonElement(encoded) }.getOrNull() }
            ?.let(::accountObjects)
            .orEmpty()
        else -> emptyList()
    }

    private fun accountReference(account: JsonObject): String? =
        sequenceOf("account_ref", "account_id", "accountId", "ref", "id")
            .mapNotNull { key -> account[key]?.jsonPrimitive?.contentOrNull }
            .firstOrNull(String::isNotBlank)

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
            else -> JsonArray(emptyList())
        }
        val alias = arguments["account_alias"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val sourceLabel = accountLabel(root, alias)
        val candidates = transactions.mapNotNull { item ->
            val transaction = item as? JsonObject ?: return@mapNotNull null
            val amount = transaction["amount_minor"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val date = transaction["date"]?.jsonPrimitive?.contentOrNull
                ?: transaction["occurred_at"]?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            val description = transactionDescription(transaction)
            ExternalTransactionCandidate(
                occurredAt = date,
                postedAt = transaction["posted_at"]?.jsonPrimitive?.contentOrNull,
                amountMinor = amount,
                currency = normalizeCurrencyCode(transaction["currency"]?.jsonPrimitive?.contentOrNull.orEmpty()),
                merchant = transactionMerchant(transaction),
                description = description,
                sourceLabel = sourceLabel,
                sourceRef = transaction["transaction_ref"]?.jsonPrimitive?.contentOrNull
                    ?.trim()
                    ?.takeIf(String::isNotBlank),
            )
        }
        val normalizedTransactions = transactions.map { item ->
            val transaction = item as? JsonObject ?: return@map redactJson(item)
            val description = transactionDescription(transaction)
            val merchant = transactionMerchant(transaction)
            buildJsonObject {
                transaction.forEach { (key, value) ->
                    if (key !in FORBIDDEN_KEYS && key !in setOf("account_ref", "account_id", "id")) {
                        when (key) {
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
                    if (key != "transactions" && key !in FORBIDDEN_KEYS && key !in setOf("account_ref", "account_id")) {
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

    private fun transactionDescription(transaction: JsonObject): String =
        transaction["description"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

    private fun transactionMerchant(transaction: JsonObject): String =
        humanLabel(transaction["merchant"]?.jsonPrimitive?.contentOrNull)
            ?: humanLabel(transactionDescription(transaction))
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


    private fun modelSchema(tool: McpCallableTool): JsonObject {
        if (tool.serverId == RECEIPTS_SERVER_ID && tool.name == RECEIPTS_GET) {
            val schema = tool.inputSchema.toMutableMap()
            val properties = (schema["properties"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            properties.remove("receipt_key")
            properties["receipt_alias"] = buildJsonObject {
                put("type", "string")
                put("description", "Непрозрачный alias из результата search-receipts")
            }
            schema["properties"] = JsonObject(properties)
            schema["required"] = JsonArray(listOf(JsonPrimitive("receipt_alias")))
            return JsonObject(schema)
        }
        if (!isTbank(tool.serverId) || tool.name != TBANK_GET_TRANSACTIONS) {
            return redactJson(tool.inputSchema).jsonObject
        }
        val schema = tool.inputSchema.toMutableMap()
        val properties = (schema["properties"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        properties.remove("account_ref")
        properties.remove("account_id")
        properties["account_alias"] = buildJsonObject {
            put("type", "string")
            put("description", "Отображаемое имя из результата list-accounts")
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

    private fun buildDescription(tool: McpCallableTool): String = buildString {
        append(tool.serverDisplayName)
        tool.description?.trim()?.takeIf(String::isNotBlank)?.let {
            append(": ")
            append(it)
        }
        if (isTbank(tool.serverId) || tool.serverId == RECEIPTS_SERVER_ID) {
            append(" Не раскрывай сырые идентификаторы и секреты.")
        }
    }

    private fun functionName(serverId: String, tool: String): String =
        "mcp_${serverId}_${tool}".replace(Regex("[^A-Za-z0-9_-]"), "_")

    private fun isTbank(serverId: String): Boolean =
        serverId == TBANK_SERVER_ID || serverId == LEGACY_TBANK_SERVER_ID


    private fun shouldRouteToRegularAgent(
        state: ImportSessionState,
        text: String,
        pending: PendingPreview?,
    ): Boolean {
        val normalized = text.trim().lowercase()
        if (normalized.isBlank()) return true
        val editRequest = listOf(
            "примен",
            "исправ",
            "измени",
            "переимен",
            "перекатег",
            "категор",
            "описан",
            "чернов",
            "draft",
            "правил",
            "merchant",
        ).any(normalized::contains)
        val previewEditRequest = normalized.contains("превью") && editRequest
        val hasBankSource = listOf(
            "банк",
            "счет",
            "счёт",
            "совместн",
            "аккаунт",
            "карт",
        ).any(normalized::contains)
        val hasPeriod = listOf(
            "недел",
            "месяц",
            "квартал",
            "сегодня",
            "вчера",
            "позавчера",
            "период",
            "диапазон",
            "январ",
            "феврал",
            "март",
            "апрел",
            "май",
            "июн",
            "июл",
            "август",
            "сентябр",
            "октябр",
            "ноябр",
            "декабр",
        ).any(normalized::contains) ||
            Regex("""\d{1,2}[./-]\d{1,4}|\b\d{1,2}\s+\p{L}+""")
                .containsMatchIn(normalized)
        val appendAction = listOf(
            "добав",
            "подгруз",
            "подтян",
        ).any(normalized::contains)
        val appendBankRequest = appendAction && hasBankSource && hasPeriod
        val incompleteAppendRequest = appendAction &&
            !appendBankRequest &&
            listOf(
                "банк",
                "счет",
                "счёт",
                "транзакц",
                "операц",
                "выписк",
            ).any(normalized::contains)
        val genericFetchRequest = listOf(
            "загруз",
            "получ",
            "обнов",
            "запрос",
            "выписк",
            "покаж",
            "баланс",
            "список счет",
            "list-accounts",
            "get-account-transactions",
        ).any(normalized::contains)
        val fetchRequest = (genericFetchRequest && !incompleteAppendRequest) || appendBankRequest
        val schedulerRequest = listOf(
            "планиров",
            "scheduler",
            "фонов",
            "запуск задач",
            "задач распис",
            "истори запуск",
            "run-scheduled-task",
            "scheduled task",
        ).any(normalized::contains)
        if (schedulerRequest) return false
        if (
            pending != null &&
            !previewEditRequest &&
            isPendingFetchCorrection(normalized, hasBankSource, hasPeriod, appendAction)
        ) {
            return false
        }
        if (state.draft != null && !fetchRequest) return true
        return editRequest && !fetchRequest
    }

    private fun isPendingFetchCorrection(
        normalized: String,
        hasBankSource: Boolean,
        hasPeriod: Boolean,
        appendAction: Boolean,
    ): Boolean {
        val correctionMarker = listOf(
            "ошиб",
            "исправ",
            "уточн",
            "надо",
            "нужно",
            "замен",
            "вместо",
            "друг",
            "еще",
            "ещё",
        ).any(normalized::contains)
        return (correctionMarker && (hasBankSource || hasPeriod)) ||
            (appendAction && hasBankSource)
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
    )

    private data class NormalizedToolResult(
        val text: String,
        val candidates: List<ExternalTransactionCandidate>,
        val redactedValues: Set<String> = emptySet(),
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
