package io.github.stolex1y.transactionimport.web

import io.github.stolex1y.transactionimport.core.AgentConfig
import io.github.stolex1y.transactionimport.core.AgentGatewayResolver
import io.github.stolex1y.transactionimport.core.AgentRuntimeConfig
import io.github.stolex1y.transactionimport.core.ChatChoice
import io.github.stolex1y.transactionimport.core.ChatCompletionGateway
import io.github.stolex1y.transactionimport.core.ChatCompletionRequest
import io.github.stolex1y.transactionimport.core.ChatCompletionResponse
import io.github.stolex1y.transactionimport.core.ContextManagementConfig
import io.github.stolex1y.transactionimport.core.ContextStrategy
import io.github.stolex1y.transactionimport.core.ProviderCatalog
import io.github.stolex1y.transactionimport.core.ProviderDefinition
import io.github.stolex1y.transactionimport.core.ProviderModelDefinition
import io.github.stolex1y.transactionimport.core.ProviderUnavailableException
import io.github.stolex1y.transactionimport.core.ReasoningModeDefinition
import io.github.stolex1y.transactionimport.core.ResponseMessage
import io.github.stolex1y.transactionimport.core.SupportAssistantConfig
import io.github.stolex1y.transactionimport.core.SupportChatRepository
import io.github.stolex1y.transactionimport.core.SupportMessageRole
import io.github.stolex1y.transactionimport.core.Usage
import io.github.stolex1y.transactionimport.persistence.SqliteSupportChatRepository
import io.github.stolex1y.transactionimport.core.McpServerConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class SupportChatServiceTest {
    @Test
    fun supportAnswersWithRetrievedSourcesAndPersistsTicketFreeTranscript() = runBlocking {
        val fixture = fixture(listOf(ANSWER))
        try {
            val view = fixture.service.sendMessage("Как начать новый импорт банковской выписки?")
            val assistant = view.messages.last()

            assertEquals(2, view.messages.size)
            assertEquals("assistant", assistant.role)
            assertTrue(assistant.text.contains("Есть ли ещё вопросы?"))
            assertTrue(assistant.citations.any { it.sourceId == "support-faq.md" })
            assertTrue(
                assistant.citations.all {
                    it.sourceId in setOf("support-faq.md", "user-guide.md") && it.section.isNotBlank()
                },
            )
            assertEquals(
                AgentConfig("support-provider", "support-model", "support-reasoning"),
                fixture.resolvedConfigs.single(),
            )
            assertEquals("support-model", fixture.provider.requests.single().model)
            assertEquals(1, fixture.provider.requests.single().messages.count { it.role != "system" })
            assertTrue(fixture.provider.requests.single().messages.first().content.contains("Новый импорт"))

            val serialized = Json.encodeToString(view)
            assertFalse(serialized.contains("ticketId"))
            assertFalse(serialized.contains("ticket_id"))
            assertFalse(serialized.contains("ticketStatus"))
            assertFalse(serialized.contains("\"status\""))
            val restartedHistory = SqliteSupportChatRepository(fixture.databasePath.toString()).snapshot()
            assertEquals(view.messages.map { it.text }, restartedHistory.messages.map { it.text })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun supportHttpApiUsesTrustedOriginAndNeverReturnsTicketMetadata() = runBlocking {
        val fixture = fixture(listOf(ANSWER))
        val base = fakeAgentDependencies(fixture.databasePath.toString())
        val dependencies = AgentWebDependencies(
            agent = base.agent,
            catalog = base.catalog,
            runtimeConfig = base.runtimeConfig,
            availableProviderIds = base.availableProviderIds,
            supportChat = fixture.service,
        )
        try {
            testApplication {
                application { module(agentDependencies = dependencies) }

                val denied = client.post("/api/agent/support/messages") {
                    header("Host", "localhost")
                    header("Origin", "https://attacker.example")
                    header("Sec-Fetch-Site", "cross-site")
                    contentType(ContentType.Application.Json)
                    setBody("""{"message":"Как начать новый импорт банковской выписки?"}""")
                }
                assertEquals(HttpStatusCode.Forbidden, denied.status)
                assertTrue(fixture.repository.snapshot().messages.isEmpty())

                val accepted = client.post("/api/agent/support/messages") {
                    header("Host", "localhost")
                    header("Origin", "http://localhost")
                    contentType(ContentType.Application.Json)
                    setBody("""{"message":"Как начать новый импорт банковской выписки?"}""")
                }
                assertEquals(HttpStatusCode.OK, accepted.status)
                val response = accepted.bodyAsText()
                assertTrue(response.contains("support-faq.md"))
                assertFalse(response.contains("ticket_id"))
                assertFalse(response.contains("ticketId"))
                assertFalse(response.contains("\"status\""))
                assertEquals(2, fixture.repository.snapshot().messages.size)

                val rejectedRebindingHistory = client.get("/api/agent/support/messages") {
                    header("Host", "attacker.example")
                    header("Origin", "http://attacker.example")
                    header("Sec-Fetch-Site", "same-origin")
                }
                assertEquals(HttpStatusCode.Forbidden, rejectedRebindingHistory.status)
                val rejectedBody = rejectedRebindingHistory.bodyAsText()
                assertFalse(rejectedBody.contains("Как начать"))
                assertFalse(rejectedBody.contains("Нажмите"))

                val acceptedHistory = client.get("/api/agent/support/messages") {
                    header("Host", "localhost")
                    header("Origin", "http://localhost")
                }
                assertEquals(HttpStatusCode.OK, acceptedHistory.status)
                val historyBody = acceptedHistory.bodyAsText()
                assertTrue(historyBody.contains("Как начать"))
                assertTrue(historyBody.contains("Нажмите"))
                assertFalse(historyBody.contains("ticket_id"))
                assertFalse(historyBody.contains("ticketId"))
                assertFalse(historyBody.contains("\"status\""))
            }
        } finally {
            base.scheduler?.close()
            fixture.close()
        }
    }

    @Test
    fun emptyRetrievalAbstainsWithoutCallingTheProviderOrCreatingCitations() = runBlocking {
        val fixture = fixture(emptyList())
        try {
            val view = fixture.service.sendMessage("Почему не работает авторизация?")
            val assistant = view.messages.last()

            assertTrue(assistant.text.contains("нет подтверждённой информации"))
            assertTrue(assistant.citations.isEmpty())
            assertTrue(fixture.provider.requests.isEmpty())
            assertEquals("open", assertNotNull(fixture.ticketGateway.openTicket()).status)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun contextualFollowUpCanReuseEvidenceFromEarlierUserQuestion() = runBlocking {
        val fixture = fixture(listOf(ANSWER, ANSWER))
        try {
            fixture.service.sendMessage("Как начать новый импорт банковской выписки?")
            val response = fixture.service.sendMessage("А где это?")

            assertTrue(response.messages.last().citations.isNotEmpty())
            val conversation = fixture.provider.requests.last().messages
                .filter { it.role != "system" }
                .joinToString("\n") { it.content }
            assertTrue(conversation.contains("А где это?"))
            assertTrue(conversation.contains("Как начать новый импорт банковской выписки?"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun savedTranscriptLoadsWithWarningWhenTicketMcpIsUnavailable() = runBlocking {
        val fixture = fixture(listOf(ANSWER))
        try {
            fixture.service.sendMessage("Как начать новый импорт банковской выписки?")
            Files.writeString(fixture.ticketStorePath, "{broken")

            val history = fixture.service.loadHistory()

            assertEquals(2, history.messages.size)
            assertTrue(history.messages.last().citations.isNotEmpty())
            assertTrue(history.warning.orEmpty().contains("временно недоступен"))
            assertFalse(history.warning.orEmpty().contains("{broken"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun slidingWindowCountsAllRolesButKeepsTheFullTranscript() = runBlocking {
        val fixture = fixture(List(7) { ANSWER })
        try {
            repeat(7) { index ->
                fixture.service.sendMessage("Как начать новый импорт банковской выписки? Вопрос ${index + 1}")
            }

            val requestMessages = fixture.provider.requests.last().messages.filter { it.role != "system" }
            assertEquals(10, requestMessages.size)
            assertEquals(14, fixture.repository.snapshot().messages.size)
            assertEquals(7, fixture.provider.requests.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun yesKeepsTicketOpenAndOnlyExactNoDuringFollowUpClosesIt() = runBlocking {
        val fixture = fixture(listOf(ANSWER, ANSWER))
        try {
            fixture.service.sendMessage("Как начать новый импорт банковской выписки?")
            fixture.service.sendMessage("Да")
            assertEquals("open", assertNotNull(fixture.ticketGateway.openTicket()).status)

            fixture.service.sendMessage("Нет")
            assertEquals("open", assertNotNull(fixture.ticketGateway.openTicket()).status)
            fixture.service.sendMessage("Нет, ещё вопрос")
            assertEquals("open", assertNotNull(fixture.ticketGateway.openTicket()).status)

            fixture.service.sendMessage("НЕТ!")
            assertEquals(null, fixture.ticketGateway.openTicket())
            assertEquals(1, fixture.ticketGateway.closedTickets().size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun closedTicketCanBeReopenedOrReplacedAccordingToValidatedRoute() = runBlocking {
        val reopenFixture = fixture(listOf(ANSWER, """{"decision":"reopen_previous"}""", ANSWER))
        try {
            val original = createAndCloseTicket(reopenFixture)
            val view = reopenFixture.service.sendMessage("Как продолжить прежний импорт выписки?")
            val current = assertNotNull(reopenFixture.ticketGateway.openTicket())

            assertEquals(original.id, current.id)
            assertNotNull(current.reopenedAtEpochMs)
            assertTrue(view.messages.last().citations.isNotEmpty())
            assertEquals("json_object", reopenFixture.provider.requests[1].responseFormat?.type)
        } finally {
            reopenFixture.close()
        }

        val newFixture = fixture(listOf(ANSWER, """{"decision":"new"}""", ANSWER))
        try {
            val original = createAndCloseTicket(newFixture)
            newFixture.service.sendMessage("Как экспортировать JSON из новой сессии?")
            val newTicket = assertNotNull(newFixture.ticketGateway.openTicket())
            val finalRequest = newFixture.provider.requests.last().messages
                .filter { it.role != "system" }
                .joinToString("\n") { it.content }

            assertFalse(newTicket.id == original.id)
            assertEquals(1, newFixture.ticketGateway.closedTickets().size)
            assertFalse(finalRequest.contains("Как начать новый импорт банковской выписки?"))
            assertTrue(finalRequest.contains("Как экспортировать JSON из новой сессии?"))
        } finally {
            newFixture.close()
        }
    }

    @Test
    fun ambiguousClosedTicketRouteAsksBeforeAnyStatusMutation() = runBlocking {
        val fixture = fixture(
            listOf(
                ANSWER,
                """{"decision":"clarify","question":"Вы продолжаете прежний вопрос об импорте?"}""",
                """{"decision":"reopen_previous"}""",
                ANSWER,
            ),
        )
        try {
            val original = createAndCloseTicket(fixture)
            val clarification = fixture.service.sendMessage("У меня снова вопрос по приложению")

            assertTrue(clarification.interaction.awaitingClarification)
            assertEquals(null, fixture.ticketGateway.openTicket())
            assertEquals(1, fixture.ticketGateway.closedTickets().size)
            assertTrue(clarification.messages.last().text.contains("прежний вопрос"))

            fixture.service.sendMessage("Да, про импорт выписки")
            assertEquals(original.id, assertNotNull(fixture.ticketGateway.openTicket()).id)
            assertEquals(4, fixture.provider.requests.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun failedClosedTicketRoutingRetainsOriginalMessageForRetry() = runBlocking {
        val fixture = fixture(listOf(ANSWER, """{"decision":"reopen_previous"}""", ANSWER))
        try {
            val previousTicket = createAndCloseTicket(fixture)
            fixture.provider.failNext()
            val providerError = assertFailsWith<ProviderUnavailableException> {
                fixture.service.sendMessage("Как продолжить прежний вопрос о новом импорте банковской выписки?")
            }
            assertFalse(providerError.message.orEmpty().contains("secret"))
            assertFalse(fixture.service.loadHistory().interaction.awaitingClarification)

            val response = fixture.service.sendMessage("Продолжаю тот же вопрос о новом импорте банковской выписки.")
            assertEquals(previousTicket.id, assertNotNull(fixture.ticketGateway.openTicket()).id)
            assertTrue(response.messages.last().citations.isNotEmpty())
            val routingInput = fixture.provider.requests[2].messages
                .filter { it.role != "system" }
                .joinToString("\n") { it.content }
            assertTrue(routingInput.contains("Как продолжить прежний вопрос о новом импорте банковской выписки?"))
            assertTrue(routingInput.contains("Продолжаю тот же вопрос о новом импорте банковской выписки."))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun inactivityWorkerClosesAtDeadlineWithoutARequest() = runBlocking {
        var now = 1_000_000L
        val fixture = fixture(listOf(ANSWER, ANSWER), nowEpochMs = { now }, expiryPollMs = 5L)
        try {
            fixture.service.sendMessage("Как начать новый импорт банковской выписки?")
            val ticket = assertNotNull(fixture.ticketGateway.openTicket())
            fixture.service.start()

            now = 1_000_000L + INACTIVITY_MS - 1
            delay(25)
            assertEquals(ticket.id, assertNotNull(fixture.ticketGateway.openTicket()).id)

            now += 1
            withTimeout(2_000) {
                while (fixture.ticketGateway.openTicket() != null) delay(5)
            }
            assertEquals(1, fixture.ticketGateway.closedTickets().size)

        } finally {
            fixture.close()
        }
    }

    @Test
    fun newActivityExtendsInactivityDeadlinePastThePreviousTimeout() = runBlocking {
        var now = FIXED_NOW
        val fixture = fixture(listOf(ANSWER, ANSWER), nowEpochMs = { now }, expiryPollMs = 5L)
        try {
            fixture.service.sendMessage("Как начать новый импорт банковской выписки?")
            val ticket = assertNotNull(fixture.ticketGateway.openTicket())
            fixture.service.start()

            now = FIXED_NOW + INACTIVITY_MS - 1
            fixture.service.sendMessage("Как изменить формат импорта?")
            assertEquals(ticket.id, assertNotNull(fixture.ticketGateway.openTicket()).id)

            now = FIXED_NOW + INACTIVITY_MS
            delay(25)
            assertEquals(ticket.id, assertNotNull(fixture.ticketGateway.openTicket()).id)

            now = FIXED_NOW + 2 * INACTIVITY_MS - 2
            delay(25)
            assertEquals(ticket.id, assertNotNull(fixture.ticketGateway.openTicket()).id)

            now += 1
            withTimeout(2_000) {
                while (fixture.ticketGateway.openTicket() != null) delay(5)
            }
            assertEquals(1, fixture.ticketGateway.closedTickets().size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun applicationRestartClosesAnAlreadyExpiredTicketWhenHistoryLoads() = runBlocking {
        var now = FIXED_NOW
        val fixture = fixture(listOf(ANSWER), nowEpochMs = { now })
        try {
            fixture.service.sendMessage("Как начать новый импорт банковской выписки?")
            assertNotNull(fixture.ticketGateway.openTicket())
            now = FIXED_NOW + INACTIVITY_MS

            val restarted = fixture.restartService()
            assertEquals(2, restarted.loadHistory().messages.size)
            assertEquals(null, fixture.ticketGateway.openTicket())
            assertEquals(1, fixture.ticketGateway.closedTickets().size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun providerAndMalformedMcpFailuresKeepSubmittedTextAndDoNotExposeRawErrors() = runBlocking {
        val providerFailure = fixture(emptyList(), failProvider = true)
        try {
            val error = assertFailsWith<ProviderUnavailableException> {
                providerFailure.service.sendMessage("Как начать новый импорт банковской выписки?")
            }
            assertFalse(error.message.orEmpty().contains("secret"))
            val transcript = providerFailure.repository.snapshot().messages
            assertEquals(listOf(SupportMessageRole.USER), transcript.map { it.role })
            assertTrue(transcript.single().ticketId != null)
        } finally {
            providerFailure.close()
        }

        val malformedMcp = fixture(emptyList(), malformedTicketStore = true)
        try {
            assertFailsWith<ProviderUnavailableException> {
                malformedMcp.service.sendMessage("Как начать новый импорт банковской выписки?")
            }
            val transcript = malformedMcp.repository.snapshot().messages
            assertEquals(listOf(SupportMessageRole.USER), transcript.map { it.role })
            assertEquals(null, transcript.single().ticketId)
            assertEquals("{broken", Files.readString(malformedMcp.ticketStorePath))
        } finally {
            malformedMcp.close()
        }
    }

    private suspend fun createAndCloseTicket(fixture: Fixture): SupportTicketRecord {
        fixture.service.sendMessage("Как начать новый импорт банковской выписки?")
        val ticket = assertNotNull(fixture.ticketGateway.openTicket())
        fixture.service.sendMessage("Нет")
        assertEquals(null, fixture.ticketGateway.openTicket())
        return ticket
    }

    private suspend fun fixture(
        responses: List<String>,
        nowEpochMs: () -> Long = { FIXED_NOW },
        expiryPollMs: Long = 60_000L,
        failProvider: Boolean = false,
        malformedTicketStore: Boolean = false,
    ): Fixture {
        val directory = Files.createTempDirectory("support-chat-test-")
        val databasePath = directory.resolve("support.sqlite")
        val ticketStorePath = directory.resolve("support-tickets.json")
        if (malformedTicketStore) Files.writeString(ticketStorePath, "{broken")
        var ticketNumber = 0
        val logicalServer = JsonSupportTicketMcpServer(
            path = ticketStorePath,
            nowEpochMs = nowEpochMs,
            idGenerator = { "support-ticket-${++ticketNumber}" },
        )
        val httpClient = HttpClient(CIO)
        val catalog = McpCatalogService.connect(
            configs = listOf(
                McpServerConfig(
                    id = "support-tickets",
                    displayName = "Synthetic support tickets",
                    endpoint = "http://localhost/support-tickets",
                    enabled = true,
                    allowedTools = logicalServer.tools().map { it.name },
                ),
            ),
            httpClient = httpClient,
            logicalServers = mapOf("support-tickets" to logicalServer),
        )
        val repository = SqliteSupportChatRepository(databasePath.toString())
        val provider = RecordingSupportGateway(responses, failProvider)
        val resolvedConfigs = mutableListOf<AgentConfig>()
        val providerCatalog = ProviderCatalog(
            providers = listOf(
                testProvider("import-provider", "import-model", "import-reasoning"),
                testProvider("support-provider", "support-model", "support-reasoning"),
            ),
        ).validated()
        val runtimeConfig = AgentRuntimeConfig(
            defaultProviderId = "import-provider",
            defaultModelId = "import-model",
            defaultReasoningModeId = "import-reasoning",
            supportAssistant = SupportAssistantConfig(
                providerId = "support-provider",
                modelId = "support-model",
                reasoningModeId = "support-reasoning",
                contextManagement = ContextManagementConfig(
                    strategy = ContextStrategy.SLIDING_WINDOW,
                    recentMessages = 10,
                ),
            ),
        ).validated(providerCatalog)
        var messageNumber = 0
        val makeService: (SupportChatRepository) -> SupportChatService = { supportRepository ->
            SupportChatService(
                repository = supportRepository,
                tickets = McpSupportTicketGateway(catalog),
                knowledgeBase = SupportKnowledgeBase.load(),
                gatewayResolver = AgentGatewayResolver { config ->
                    resolvedConfigs += config
                    provider
                },
                runtimeConfig = runtimeConfig,
                idGenerator = { "support-message-${++messageNumber}" },
                nowEpochMs = nowEpochMs,
                expiryPollMs = expiryPollMs,
            )
        }
        val service = makeService(repository)
        return Fixture(
            directory = directory,
            databasePath = databasePath,
            ticketStorePath = ticketStorePath,
            repository = repository,
            ticketGateway = McpSupportTicketGateway(catalog),
            provider = provider,
            resolvedConfigs = resolvedConfigs,
            service = service,
            makeService = makeService,
            catalog = catalog,
            httpClient = httpClient,
        )
    }

    private fun testProvider(id: String, modelId: String, reasoningId: String) = ProviderDefinition(
        id = id,
        displayName = id,
        baseUrl = "http://localhost",
        models = listOf(
            ProviderModelDefinition(
                id = modelId,
                reasoningModes = listOf(ReasoningModeDefinition(id = reasoningId)),
            ),
        ),
    )

    private class RecordingSupportGateway(
        private val responses: List<String>,
        private val failProvider: Boolean,
    ) : ChatCompletionGateway {
        val requests = mutableListOf<ChatCompletionRequest>()
        private var responseIndex = 0

        private var failuresRemaining = if (failProvider) Int.MAX_VALUE else 0

        fun failNext() {
            failuresRemaining += 1
        }

        override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
            requests += request
            if (failuresRemaining > 0) {
                failuresRemaining -= 1
                throw IllegalStateException("secret=never-log")
            }
            val content = responses.getOrNull(responseIndex++)
                ?: error("Support fake response queue is empty.")
            return ChatCompletionResponse(
                choices = listOf(
                    ChatChoice(
                        message = ResponseMessage(role = "assistant", content = content),
                        finishReason = "stop",
                    ),
                ),
                usage = Usage(promptTokens = 1, completionTokens = 1, totalTokens = 2),
            )
        }
    }

    private data class Fixture(
        val directory: java.nio.file.Path,
        val databasePath: java.nio.file.Path,
        val ticketStorePath: java.nio.file.Path,
        val repository: SqliteSupportChatRepository,
        val ticketGateway: McpSupportTicketGateway,
        val provider: RecordingSupportGateway,
        val resolvedConfigs: List<AgentConfig>,
        var service: SupportChatService,
        val makeService: (SupportChatRepository) -> SupportChatService,
        val catalog: McpCatalogService,
        val httpClient: HttpClient,
    ) : AutoCloseable {
        fun restartService(): SupportChatService {
            service.close()
            service = makeService(SqliteSupportChatRepository(databasePath.toString()))
            return service
        }

        override fun close() {
            service.close()
            catalog.close()
            httpClient.close()
            databasePath.deleteIfExists()
            Files.deleteIfExists(databasePath.resolveSibling(databasePath.fileName.toString() + "-wal"))
            Files.deleteIfExists(databasePath.resolveSibling(databasePath.fileName.toString() + "-shm"))
            ticketStorePath.deleteIfExists()
            ticketStorePath.resolveSibling("${ticketStorePath.fileName}.lock").deleteIfExists()
            Files.deleteIfExists(directory)
        }
    }

    private companion object {
        const val ANSWER = "Нажмите «Новый импорт», чтобы открыть пустую сессию."
        const val FIXED_NOW = 1_000_000L
        const val INACTIVITY_MS = 30 * 60 * 1_000L
    }
}
