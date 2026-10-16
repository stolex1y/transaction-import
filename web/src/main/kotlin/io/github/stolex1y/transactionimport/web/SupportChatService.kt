package io.github.stolex1y.transactionimport.web

import io.github.stolex1y.transactionimport.core.AgentConfig
import io.github.stolex1y.transactionimport.core.AgentGatewayResolver
import io.github.stolex1y.transactionimport.core.AgentRuntimeConfig
import io.github.stolex1y.transactionimport.core.ChatCompletionRequest
import io.github.stolex1y.transactionimport.core.ContextStrategy
import io.github.stolex1y.transactionimport.core.ProviderUnavailableException
import io.github.stolex1y.transactionimport.core.RequestMessage
import io.github.stolex1y.transactionimport.core.ResponseFormat
import io.github.stolex1y.transactionimport.core.SupportChatInteractionState
import io.github.stolex1y.transactionimport.core.SupportChatMessage
import io.github.stolex1y.transactionimport.core.SupportChatRepository
import io.github.stolex1y.transactionimport.core.SupportChatSnapshot
import io.github.stolex1y.transactionimport.core.SupportCitation
import io.github.stolex1y.transactionimport.core.SupportMessageRole
import io.github.stolex1y.transactionimport.core.ThinkingOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Locale
import java.util.UUID

private const val SUPPORT_DEMO_USER_ID = "demo-user"
private val supportJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

private const val SUPPORT_MCP_SERVER_ID = "support-tickets"
private const val SUPPORT_USER_TOOL = "get_support_user"
private const val SUPPORT_OPEN_TICKET_TOOL = "get_open_support_ticket"
private const val SUPPORT_CLOSED_TICKETS_TOOL = "list_closed_support_ticket_summaries"
private const val SUPPORT_CREATE_TICKET_TOOL = "create_support_ticket"
private const val SUPPORT_TICKET_CONTEXT_TOOL = "get_support_ticket_context"
private const val SUPPORT_CLOSE_TICKET_TOOL = "close_support_ticket"
private const val SUPPORT_REOPEN_TICKET_TOOL = "reopen_support_ticket"
private const val SUPPORT_TICKET_INACTIVITY_MS = 30 * 60 * 1_000L
private const val DEFAULT_EXPIRY_POLL_MS = 10_000L
private const val FOLLOW_UP_QUESTION = "Есть ли ещё вопросы?"
private const val DEFAULT_ROUTING_QUESTION = "Уточните, пожалуйста: вы продолжаете прежний вопрос или задаёте новый?"
private const val PROVIDER_ERROR = "Ассистент поддержки временно недоступен. Повторите попытку позже."
private const val MCP_ERROR = "Внутренний контекст поддержки временно недоступен. Повторите попытку позже."

@Serializable
data class SupportChatMessageView(
    val id: String,
    val role: String,
    val text: String,
    @SerialName("created_at_epoch_ms") val createdAtEpochMs: Long,
    val citations: List<SupportCitation> = emptyList(),
)

@Serializable
data class SupportChatInteractionView(
    @SerialName("awaiting_follow_up") val awaitingFollowUp: Boolean,
    @SerialName("awaiting_clarification") val awaitingClarification: Boolean,
)

@Serializable
data class SupportChatView(
    val messages: List<SupportChatMessageView>,
    val interaction: SupportChatInteractionView,
    val warning: String? = null,
)

@Serializable
data class SupportChatMessageRequest(
    val message: String,
)

internal class SupportChatService(
    private val repository: SupportChatRepository,
    private val tickets: SupportTicketGateway,
    private val knowledgeBase: SupportKnowledgeBase,
    private val gatewayResolver: AgentGatewayResolver,
    private val runtimeConfig: AgentRuntimeConfig,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val expiryPollMs: Long = DEFAULT_EXPIRY_POLL_MS,
): AutoCloseable, SupportChatApi {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var expiryJob: Job? = null

    init {
        require(expiryPollMs > 0)
    }

    @Synchronized
    fun start() {
        if (expiryJob?.isActive == true) return
        expiryJob = scope.launch {
            while (currentCoroutineContext().isActive) {
                try {
                    mutex.withLock { reconcileExpiredTicket() }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    // An unavailable local MCP store is retried on the next poll and on the next request.
                }
                delay(expiryPollMs)
            }
        }
    }

    override fun close() {
        scope.cancel()
    }

    override suspend fun loadHistory(): SupportChatView = mutex.withLock {
        val warning = try {
            reconcileExpiredTicket()
            null
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            MCP_ERROR
        }
        repository.snapshot().toView(warning)
    }

    override suspend fun sendMessage(text: String): SupportChatView = mutex.withLock {
        val normalizedText = text.trim()
        require(normalizedText.isNotEmpty()) { "Введите вопрос для поддержки." }
        try {
            reconcileExpiredTicket()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            repository.append(userMessage(normalizedText, ticketId = null))
            throw error
        }
        processMessage(normalizedText)
        repository.snapshot().toView()
    }

    internal suspend fun reconcileInactivity() = mutex.withLock { reconcileExpiredTicket() }

    private suspend fun processMessage(text: String) {
        val interaction = repository.snapshot().interaction
        val pendingRouteMessageId = interaction.pendingRouteMessageId
        if (pendingRouteMessageId != null) {
            repository.append(userMessage(text, ticketId = null))
            continueRouting(pendingRouteMessageId)
            return
        }

        val incomingMessage = repository.append(userMessage(text, ticketId = null))
        val openTicket = tickets.openTicket()
        if (openTicket != null) {
            repository.associateAllUnassignedMessages(openTicket.id)
            if (interaction.awaitingFollowUp && isYes(text)) {
                repository.saveInteractionState(SupportChatInteractionState())
                appendAssistant(
                    text = "Хорошо. Напишите следующий вопрос по Transaction Import.",
                    ticketId = openTicket.id,
                )
                return
            }
            if (interaction.awaitingFollowUp && isNo(text)) {
                tickets.close(openTicket.id)
                repository.saveInteractionState(SupportChatInteractionState())
                appendAssistant(
                    text = "Обращение завершено. Если появится новый вопрос, напишите его здесь.",
                    ticketId = openTicket.id,
                )
                return
            }
            repository.saveInteractionState(SupportChatInteractionState())
            answerForTicket(openTicket.id, text)
            return
        }

        val closed = tickets.closedTickets()
        if (closed.isNotEmpty()) {
            repository.saveInteractionState(
                SupportChatInteractionState(pendingRouteMessageId = incomingMessage.id),
            )
            continueRouting(incomingMessage.id)
        } else {
            createTicketAndAnswer(incomingMessage.id, text, associateAll = true)
        }
    }

    private suspend fun createTicketAndAnswer(
        routeMessageId: String,
        question: String,
        associateAll: Boolean,
    ) {
        val ticket = tickets.create()
        if (associateAll) {
            repository.associateAllUnassignedMessages(ticket.id)
        } else {
            repository.associateUnassignedMessagesFrom(routeMessageId, ticket.id)
        }
        repository.saveInteractionState(SupportChatInteractionState())
        answerForTicket(ticket.id, question)
    }

    private suspend fun continueRouting(routeMessageId: String) {
        val pendingMessages = repository.messagesForRoutingFrom(routeMessageId)
        val question = pendingMessages
            .filter { it.role == SupportMessageRole.USER }
            .joinToString("\n") { it.text }
        val latestClosedTicket = tickets.closedTickets().firstOrNull()
        if (latestClosedTicket == null) {
            createTicketAndAnswer(routeMessageId, question, associateAll = false)
            return
        }

        val routingMessages = pendingMessages.map {
            RequestMessage(it.role.name.lowercase(Locale.ROOT), it.text)
        }
        val decision = classifyRoute(latestClosedTicket, routingMessages)
        when (decision) {
            RouteDecision.REOPEN_PREVIOUS -> {
                val reopened = tickets.reopen(latestClosedTicket.id)
                repository.associateUnassignedMessagesFrom(routeMessageId, reopened.id)
                repository.saveInteractionState(SupportChatInteractionState())
                answerForTicket(reopened.id, question)
            }
            RouteDecision.NEW -> createTicketAndAnswer(routeMessageId, question, associateAll = false)
            is RouteDecision.CLARIFY -> {
                repository.saveInteractionState(
                    SupportChatInteractionState(pendingRouteMessageId = routeMessageId),
                )
                appendAssistant(decision.question, ticketId = null)
            }
        }
    }

    private suspend fun classifyRoute(
        candidate: SupportTicketSummaryRecord,
        conversation: List<RequestMessage>,
    ): RouteDecision {
        val candidateTranscript = repository.messagesForTicket(
            candidate.id,
            runtimeConfig.effectiveSupportAssistant().contextManagement.recentMessages,
        )
        val allConversation = (candidateTranscript.map {
            RequestMessage(it.role.name.lowercase(Locale.ROOT), it.text)
        } + conversation).takeLast(runtimeConfig.effectiveSupportAssistant().contextManagement.recentMessages)
        val candidateDetails = buildString {
            append("Тема прежнего обращения: ")
            append(candidate.topic)
            append(". Оно закрыто.")
        }
        val response = complete(
            messages = listOf(
                RequestMessage("system", ROUTING_SYSTEM_PROMPT + "\n" + candidateDetails),
            ) + allConversation,
            responseFormat = ResponseFormat(type = "json_object"),
            maxTokens = ROUTING_MAX_TOKENS,
        )
        val rawContent = response.choices.firstOrNull()?.message?.content.orEmpty()
        val route = runCatching { supportJson.decodeFromString<SupportRouteResponse>(rawContent) }.getOrNull()
            ?: return RouteDecision.CLARIFY(DEFAULT_ROUTING_QUESTION)
        return when (route.decision.lowercase(Locale.ROOT)) {
            "reopen_previous" -> RouteDecision.REOPEN_PREVIOUS
            "new" -> RouteDecision.NEW
            "clarify" -> RouteDecision.CLARIFY(
                route.question?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_ROUTING_QUESTION_CHARS)
                    ?: DEFAULT_ROUTING_QUESTION,
            )
            else -> RouteDecision.CLARIFY(DEFAULT_ROUTING_QUESTION)
        }
    }

    private suspend fun answerForTicket(ticketId: String, question: String) {
        val supportConfig = runtimeConfig.effectiveSupportAssistant()
        check(supportConfig.contextManagement.strategy == ContextStrategy.SLIDING_WINDOW)
        val user = tickets.user()
        val ticketContext = tickets.context(ticketId)
        val contextMessages = repository.messagesForTicket(ticketId, supportConfig.contextManagement.recentMessages)
        val passages = retrievePassages(question, contextMessages)
        if (passages.isEmpty()) {
            appendAssistant(
                text = "В FAQ и руководстве интерфейса нет подтверждённой информации по этому вопросу. " +
                    "Не хочу придумывать ответ. Попробуйте уточнить вопрос о работе Transaction Import.\n\n" +
                    FOLLOW_UP_QUESTION,
                ticketId = ticketId,
                citations = emptyList(),
            )
            repository.saveInteractionState(SupportChatInteractionState(awaitingFollowUp = true))
            return
        }

        val systemPrompt = buildString {
            append(ANSWER_SYSTEM_PROMPT)
            append("\n\nКонтекст обращения: тема '")
            append(ticketContext.ticket.topic)
            append("', профиль пользователя: язык '")
            append(user.locale)
            append("'. Не выводи и не упоминай внутренние ticket fields.")
            append("\n\nПодтверждённые материалы; используй только их:\n")
            passages.forEachIndexed { index, passage ->
                append("[S")
                append(index + 1)
                append("] ")
                append(passage.citation.title)
                append(" — раздел «")
                append(passage.citation.section)
                append("»\n")
                append(passage.text)
                append("\n\n")
            }
            append("Не добавляй sources или citations в ответ: UI покажет только реальные источники отдельно.")
        }
        val response = complete(
            messages = listOf(RequestMessage("system", systemPrompt)) + contextMessages.map {
                RequestMessage(it.role.name.lowercase(Locale.ROOT), it.text)
            },
            maxTokens = ANSWER_MAX_TOKENS,
        )
        val answer = response.choices.firstOrNull()?.message?.content?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: throw ProviderUnavailableException(PROVIDER_ERROR)
        val answerWithFollowUp = if (answer.contains(FOLLOW_UP_QUESTION, ignoreCase = true)) {
            answer
        } else {
            "$answer\n\n$FOLLOW_UP_QUESTION"
        }
        appendAssistant(
            text = answerWithFollowUp,
            ticketId = ticketId,
            citations = passages.map(RetrievedSupportPassage::citation),
        )
        repository.saveInteractionState(SupportChatInteractionState(awaitingFollowUp = true))
    }

    private fun retrievePassages(
        question: String,
        contextMessages: List<SupportChatMessage>,
    ): List<RetrievedSupportPassage> {
        if (isYes(question) || isNo(question)) return emptyList()
        val currentQuestionPassages = knowledgeBase.retrieve(question)
        if (currentQuestionPassages.isNotEmpty()) return currentQuestionPassages

        return contextMessages.asReversed()
            .asSequence()
            .filter {
                it.role == SupportMessageRole.USER &&
                    it.text != question &&
                    !isYes(it.text) &&
                    !isNo(it.text)
            }
            .map { knowledgeBase.retrieve(it.text) }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
    }

    private suspend fun complete(
        messages: List<RequestMessage>,
        responseFormat: ResponseFormat? = null,
        maxTokens: Int,
    ) = try {
        val support = runtimeConfig.effectiveSupportAssistant()
        val gateway = gatewayResolver.resolve(
            AgentConfig(
                providerId = support.providerId,
                modelId = support.modelId,
                reasoningModeId = support.reasoningModeId,
            ),
        )
        gateway.complete(
            ChatCompletionRequest(
                model = support.modelId,
                messages = messages,
                thinking = ThinkingOptions(type = "disabled"),
                reasoningEffort = support.reasoningModeId,
                responseFormat = responseFormat,
                maxTokens = maxTokens.coerceAtMost(gateway.maxOutputTokens ?: maxTokens),
                temperature = runtimeConfig.temperature,
                stream = false,
            ),
        )
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        throw ProviderUnavailableException(PROVIDER_ERROR)
    }

    private suspend fun reconcileExpiredTicket() {
        val open = tickets.openTicket() ?: return
        val lastActivity = repository.latestMessageTimeForTicket(open.id) ?: open.createdAtEpochMs
        val now = nowEpochMs()
        if (now >= lastActivity && now - lastActivity >= SUPPORT_TICKET_INACTIVITY_MS) {
            tickets.close(open.id)
            repository.saveInteractionState(SupportChatInteractionState())
        }
    }

    private suspend fun appendAssistant(
        text: String,
        ticketId: String?,
        citations: List<SupportCitation> = emptyList(),
    ) {
        repository.append(
            SupportChatMessage(
                id = newMessageId(),
                role = SupportMessageRole.ASSISTANT,
                text = text,
                createdAtEpochMs = nowEpochMs(),
                ticketId = ticketId,
                citations = citations,
            ),
        )
    }

    private fun userMessage(text: String, ticketId: String?) = SupportChatMessage(
        id = newMessageId(),
        role = SupportMessageRole.USER,
        text = text,
        createdAtEpochMs = nowEpochMs(),
        ticketId = ticketId,
    )

    private fun newMessageId(): String = idGenerator().also {
        require(it.isNotBlank()) { "Support message ID не должен быть пустым." }
    }

    private fun isYes(text: String): Boolean = text.trim().lowercase(Locale.ROOT).trimEnd('.', '!', '?', '…') == "да"

    private fun isNo(text: String): Boolean = text.trim().lowercase(Locale.ROOT).trimEnd('.', '!', '?', '…') == "нет"

    private fun SupportChatSnapshot.toView(warning: String? = null) = SupportChatView(
        messages = messages.map { message ->
            SupportChatMessageView(
                id = message.id,
                role = message.role.name.lowercase(Locale.ROOT),
                text = message.text,
                createdAtEpochMs = message.createdAtEpochMs,
                citations = message.citations,
            )
        },
        interaction = SupportChatInteractionView(
            awaitingFollowUp = interaction.awaitingFollowUp,
            awaitingClarification = interaction.pendingRouteMessageId != null &&
                messages.lastOrNull()?.role == SupportMessageRole.ASSISTANT,
        ),
        warning = warning,
    )

    private companion object {
        const val ANSWER_MAX_TOKENS = 1_500
        const val ROUTING_MAX_TOKENS = 300
        const val MAX_ROUTING_QUESTION_CHARS = 500
        val ROUTING_SYSTEM_PROMPT = """
            Ты маршрутизируешь сообщение пользователя в локальном support-чате Transaction Import.
            Сравни текущий разговор с единственным последним закрытым обращением.
            Верни только JSON-объект: {"decision":"reopen_previous"|"new"|"clarify","question":"..."}.
            Выбери reopen_previous только если новый вопрос явно продолжает историю прежнего обращения.
            Выбери new, если тема явно новая. Если связь неясна, выбери clarify и задай короткий вопрос.
            Не отвечай на вопрос поддержки, не выдумывай факты и не запрашивай/не возвращай ticket ID.
        """.trimIndent()
        val ANSWER_SYSTEM_PROMPT = """
            Ты ассистент поддержки приложения Transaction Import. Отвечай по-русски, кратко и только по переданным
            фрагментам FAQ и руководства. Если материалы не подтверждают факт, прямо скажи об этом и не угадывай.
            Не называй внутренние тикеты, их ID или статусы. Не выполняй действия с банковскими данными, импортами,
            счетами или транзакциями. Не добавляй вопрос о продолжении диалога: backend добавит его сам.
        """.trimIndent()
    }
}

internal sealed interface RouteDecision {
    data object REOPEN_PREVIOUS : RouteDecision
    data object NEW : RouteDecision
    data class CLARIFY(val question: String) : RouteDecision
}

internal interface SupportTicketGateway {
    suspend fun user(): SupportUserRecord
    suspend fun openTicket(): SupportTicketRecord?
    suspend fun closedTickets(): List<SupportTicketSummaryRecord>
    suspend fun create(): SupportTicketRecord
    suspend fun context(ticketId: String): SupportTicketContextRecord
    suspend fun close(ticketId: String): SupportTicketRecord
    suspend fun reopen(ticketId: String): SupportTicketRecord
}

internal class McpSupportTicketGateway(
    private val mcpTools: McpToolProvider,
) : SupportTicketGateway {
    override suspend fun user(): SupportUserRecord = call<SupportUserRecord>(
        SUPPORT_USER_TOOL,
        EMPTY_ARGUMENTS,
    ).also { user ->
        if (user.id != SUPPORT_DEMO_USER_ID || user.locale.isBlank()) {
            throw ProviderUnavailableException(MCP_ERROR)
        }
    }

    override suspend fun openTicket(): SupportTicketRecord? = call<SupportTicketLookupRecord>(
        SUPPORT_OPEN_TICKET_TOOL,
        EMPTY_ARGUMENTS,
    ).ticket?.requireStatus("open")

    override suspend fun closedTickets(): List<SupportTicketSummaryRecord> =
        call<List<SupportTicketSummaryRecord>>(SUPPORT_CLOSED_TICKETS_TOOL, EMPTY_ARGUMENTS).also { closed ->
            if (closed.any { it.id.isBlank() }) throw ProviderUnavailableException(MCP_ERROR)
        }

    override suspend fun create(): SupportTicketRecord =
        call<SupportTicketRecord>(SUPPORT_CREATE_TICKET_TOOL, EMPTY_ARGUMENTS).requireStatus("open")

    override suspend fun context(ticketId: String): SupportTicketContextRecord =
        call<SupportTicketContextRecord>(SUPPORT_TICKET_CONTEXT_TOOL, ticketIdArguments(ticketId)).also { context ->
            if (
                context.ticket.id != ticketId ||
                context.ticket.status != "open" ||
                context.user.id != SUPPORT_DEMO_USER_ID
            ) {
                throw ProviderUnavailableException(MCP_ERROR)
            }
        }

    override suspend fun close(ticketId: String): SupportTicketRecord =
        call<SupportTicketRecord>(SUPPORT_CLOSE_TICKET_TOOL, ticketIdArguments(ticketId)).requireStatus("closed")

    override suspend fun reopen(ticketId: String): SupportTicketRecord =
        call<SupportTicketRecord>(SUPPORT_REOPEN_TICKET_TOOL, ticketIdArguments(ticketId)).requireStatus("open")

    private fun SupportTicketRecord.requireStatus(expectedStatus: String): SupportTicketRecord {
        if (id.isBlank() || userId != SUPPORT_DEMO_USER_ID || status != expectedStatus) {
            throw ProviderUnavailableException(MCP_ERROR)
        }
        return this
    }
    private suspend inline fun <reified T> call(tool: String, arguments: JsonObject): T {
        val response = try {
            mcpTools.callConfiguredTool(SUPPORT_MCP_SERVER_ID, tool, arguments)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            throw ProviderUnavailableException(MCP_ERROR)
        }
        if (response.isError) throw ProviderUnavailableException(MCP_ERROR)
        return try {
            supportJson.decodeFromString(response.text)
        } catch (_: Throwable) {
            throw ProviderUnavailableException(MCP_ERROR)
        }
    }

    private companion object {
        val EMPTY_ARGUMENTS = JsonObject(emptyMap())
        fun ticketIdArguments(ticketId: String) = buildJsonObject { put("ticket_id", ticketId) }
    }
}

@Serializable
internal data class SupportUserRecord(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val locale: String,
)

@Serializable
internal data class SupportTicketRecord(
    val id: String,
    @SerialName("user_id") val userId: String,
    val topic: String,
    val status: String,
    @SerialName("created_at_epoch_ms") val createdAtEpochMs: Long,
    @SerialName("closed_at_epoch_ms") val closedAtEpochMs: Long? = null,
    @SerialName("reopened_at_epoch_ms") val reopenedAtEpochMs: Long? = null,
)

@Serializable
internal data class SupportTicketSummaryRecord(
    val id: String,
    val topic: String,
    @SerialName("created_at_epoch_ms") val createdAtEpochMs: Long,
    @SerialName("closed_at_epoch_ms") val closedAtEpochMs: Long,
)

@Serializable
internal data class SupportTicketLookupRecord(
    val ticket: SupportTicketRecord? = null,
)

@Serializable
internal data class SupportTicketContextRecord(
    val user: SupportUserRecord,
    val ticket: SupportTicketRecord,
)

@Serializable
private data class SupportRouteResponse(
    val decision: String,
    val question: String? = null,
)

private fun ticketIdArguments(ticketId: String): JsonObject = buildJsonObject {
    put("ticket_id", ticketId)
}
