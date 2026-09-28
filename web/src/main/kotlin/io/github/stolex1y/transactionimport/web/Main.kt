package io.github.stolex1y.transactionimport.web

import io.github.stolex1y.transactionimport.core.SmartExpenseAgent
import io.github.stolex1y.transactionimport.persistence.SqliteImportSessionRepository
import io.github.stolex1y.transactionimport.persistence.SqliteSchedulerRepository
import io.github.stolex1y.transactionimport.transport.ConfiguredProviderRegistry
import io.github.stolex1y.transactionimport.transport.loadProviderCatalog
import io.github.stolex1y.transactionimport.transport.loadAgentRuntimeConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

private const val PROVIDER_CONFIG_ENV = "PROVIDER_CONFIG_PATH"
private const val AGENT_CONFIG_ENV = "AGENT_CONFIG_PATH"
private const val DATABASE_PATH_ENV = "TRANSACTION_IMPORT_DB"
private const val DEFAULT_PROVIDER_CONFIG_PATH = "config/providers.json"
private const val DEFAULT_AGENT_CONFIG_PATH = "config/agent.json"
private const val DEFAULT_DATABASE_PATH = ".data/agent.sqlite"
private const val DEFAULT_PORT = 8080

fun main() {
    val providerConfigPath = Path.of(
        System.getenv(PROVIDER_CONFIG_ENV)?.takeIf(String::isNotBlank)
            ?: DEFAULT_PROVIDER_CONFIG_PATH,
    )
    val agentConfigPath = Path.of(
        System.getenv(AGENT_CONFIG_ENV)?.takeIf(String::isNotBlank)
            ?: DEFAULT_AGENT_CONFIG_PATH,
    )
    val databasePath = Path.of(
        System.getenv(DATABASE_PATH_ENV)?.takeIf(String::isNotBlank)
            ?: DEFAULT_DATABASE_PATH,
    ).toAbsolutePath().normalize()
    databasePath.parent?.let(Files::createDirectories)
    val catalog = loadProviderCatalog(providerConfigPath)
    val runtimeConfig = loadAgentRuntimeConfig(agentConfigPath, catalog)

    val port = System.getenv("PORT")
        ?.toIntOrNull()
        ?.takeIf { it in 1..65_535 }
        ?: DEFAULT_PORT
    val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 300_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 300_000
        }
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                    explicitNulls = false
                },
            )
        }
    }

    val providerRegistry = ConfiguredProviderRegistry(httpClient, catalog)
    val repository = SqliteImportSessionRepository(
        databasePath = databasePath.toString(),
        defaultContextManagement = runtimeConfig.sessionContextManagement(),
    )
    val schedulerRepository = SqliteSchedulerRepository(databasePath.toString())
    val agent = SmartExpenseAgent(
        repository = repository,
        gatewayResolver = providerRegistry,
        idGenerator = { UUID.randomUUID().toString() },
        nowEpochMs = System::currentTimeMillis,
        runtimeConfig = runtimeConfig,
        configValidator = { catalog.resolve(it) },
    )
    lateinit var sourceTools: McpToolProvider
    val scheduler = SchedulerService(
        repository = schedulerRepository,
        agent = agent,
        mcpTools = object : McpToolProvider {
            override suspend fun allowedTools() = sourceTools.allowedTools()

            override suspend fun callConfiguredTool(
                serverId: String,
                tool: String,
                arguments: kotlinx.serialization.json.JsonObject,
            ) = sourceTools.callConfiguredTool(serverId, tool, arguments)
        },
        gatewayResolver = providerRegistry,
        runtimeConfig = runtimeConfig,
    )
    var mcpCatalog: McpCatalogService? = null
    try {
        val connectedCatalog = runBlocking {
            McpCatalogService.connect(
                configs = runtimeConfig.mcpServers,
                httpClient = httpClient,
                logicalServers = mapOf("scheduler" to scheduler),
            )
        }
        mcpCatalog = connectedCatalog
        sourceTools = connectedCatalog
        scheduler.start()
        val nativeMcpAgent = NativeMcpAgent(
            agent = agent,
            gatewayResolver = providerRegistry,
            mcpTools = connectedCatalog,
            runtimeConfig = runtimeConfig,
        )
        val agentDependencies = AgentWebDependencies(
            agent = agent,
            catalog = catalog,
            runtimeConfig = runtimeConfig,
            availableProviderIds = providerRegistry.availableProviderIds(),
            mcpCatalog = connectedCatalog,
            tbankMcp = connectedCatalog,
            nativeMcpAgent = nativeMcpAgent,
            scheduler = scheduler,
            receiptsProxy = ReceiptsProxyService(connectedCatalog),
        )
        embeddedServer(Netty, host = "127.0.0.1", port = port) {
            module(agentDependencies = agentDependencies)
        }.start(wait = true)
    } finally {
        scheduler.close()
        mcpCatalog?.close()
        httpClient.close()
    }
}
