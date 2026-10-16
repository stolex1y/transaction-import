package io.github.stolex1y.transactionimport.web

import io.github.stolex1y.transactionimport.core.AgentGatewayResolver
import io.github.stolex1y.transactionimport.core.ChatChoice
import io.github.stolex1y.transactionimport.core.ChatCompletionGateway
import io.github.stolex1y.transactionimport.core.ChatCompletionRequest
import io.github.stolex1y.transactionimport.core.ChatCompletionResponse
import io.github.stolex1y.transactionimport.core.McpServerConfig
import io.github.stolex1y.transactionimport.core.ResponseMessage
import io.github.stolex1y.transactionimport.core.Usage
import io.github.stolex1y.transactionimport.persistence.SqliteSupportChatRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

class SupportBrowserAppFixture(responses: List<String>) : AutoCloseable {
    private val directory = Files.createTempDirectory("support-browser-")
    private val database = directory.resolve("agent.sqlite")
    private val ticketStore = directory.resolve("support-tickets.json")
    private val gateway = SupportBrowserGateway(responses)
    private val base = fakeAgentDependencies(database.toString(), gateway)
    private val httpClient = HttpClient(CIO)
    private val ticketServer = JsonSupportTicketMcpServer(
        path = ticketStore,
        idGenerator = { "support-ticket-${gateway.nextId()}" },
    )
    private val allowedTools = runBlocking { ticketServer.tools().map { it.name } }
    private val ticketCatalog = runBlocking {
        McpCatalogService.connect(
            configs = listOf(
                McpServerConfig(
                    id = "support-tickets",
                    displayName = "Synthetic support tickets",
                    endpoint = "http://localhost/support-tickets",
                    enabled = true,
                    allowedTools = allowedTools,
                ),
            ),
            httpClient = httpClient,
            logicalServers = mapOf("support-tickets" to ticketServer),
        )
    }
    private val supportService = SupportChatService(
        repository = SqliteSupportChatRepository(database.toString()),
        tickets = McpSupportTicketGateway(ticketCatalog),
        knowledgeBase = SupportKnowledgeBase.load(),
        gatewayResolver = AgentGatewayResolver { gateway },
        runtimeConfig = base.runtimeConfig,
        idGenerator = { "support-message-${gateway.nextId()}" },
    )
    private val dependencies = AgentWebDependencies(
        agent = base.agent,
        catalog = base.catalog,
        runtimeConfig = base.runtimeConfig,
        availableProviderIds = base.availableProviderIds,
        newSessionTitle = base.newSessionTitle,
        mcpCatalog = base.mcpCatalog,
        tbankMcp = base.tbankMcp,
        nativeMcpAgent = base.nativeMcpAgent,
        scheduler = base.scheduler,
        receiptsProxy = base.receiptsProxy,
        supportChat = supportService,
    )
    private val port = ServerSocket(0).use { it.localPort }
    private val server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
        module(agentDependencies = dependencies)
    }.start(wait = false)

    val baseUrl: String = "http://127.0.0.1:$port"

    fun ticketStatuses(): List<String> = ticketState().map {
        it.jsonObject.getValue("status").jsonPrimitive.content
    }

    fun ticketCount(): Int = ticketState().size
    fun breakTicketStore() {
        Files.writeString(ticketStore, "{broken")
    }

    private fun ticketState() = Json.parseToJsonElement(Files.readString(ticketStore))
        .jsonObject.getValue("tickets").jsonArray

    override fun close() {
        server.stop(1_000, 2_000)
        supportService.close()
        base.scheduler?.close()
        ticketCatalog.close()
        httpClient.close()
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) }
        }
    }

    private class SupportBrowserGateway(responses: List<String>) : ChatCompletionGateway {
        private val responses = ArrayDeque(responses)
        private var sequence = 0
        override val contextWindowTokens: Int = 32_000

        @Synchronized
        fun nextId(): Int = ++sequence

        override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
            val content = responses.removeFirstOrNull()
                ?: error("The support browser response fixture is exhausted.")
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
}
