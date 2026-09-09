package io.github.stolex1y.transactionimport.web

import io.github.stolex1y.transactionimport.core.SmartExpenseAgent
import io.github.stolex1y.transactionimport.core.TransactionImportService
import io.github.stolex1y.transactionimport.d05.DEFAULT_OPENROUTER_MODEL
import io.github.stolex1y.transactionimport.d05.OpenRouterGateway
import io.github.stolex1y.transactionimport.persistence.SqliteImportSessionRepository
import io.github.stolex1y.transactionimport.transport.ConfiguredProviderRegistry
import io.github.stolex1y.transactionimport.transport.DeepSeekGateway
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
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

private const val API_KEY_ENV = "DEEPSEEK_API_KEY"
private const val OPENROUTER_MODEL_ENV = "OPENROUTER_MODEL"
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

    val openRouterModel = System.getenv(OPENROUTER_MODEL_ENV)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: DEFAULT_OPENROUTER_MODEL
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

    try {
        val providerRegistry = ConfiguredProviderRegistry(httpClient, catalog)
        val repository = SqliteImportSessionRepository(databasePath.toString())
        val agent = SmartExpenseAgent(
            repository = repository,
            gatewayResolver = providerRegistry,
            idGenerator = { UUID.randomUUID().toString() },
            nowEpochMs = System::currentTimeMillis,
            runtimeConfig = runtimeConfig,
            configValidator = { catalog.resolve(it) },
        )
        val deepSeekGateway = System.getenv(API_KEY_ENV)
            ?.takeIf { it.isNotBlank() }
            ?.let { DeepSeekGateway(httpClient, it) }
        val service = deepSeekGateway?.let(::TransactionImportService)
        val experimentService = deepSeekGateway?.let { gateway ->
            val openRouterGateway = System.getenv("OPENROUTER_API_KEY")
                ?.takeIf { it.isNotBlank() }
                ?.let { OpenRouterGateway(httpClient, it) }
            ExperimentService(
                deepSeekGateway = gateway,
                openRouterGateway = openRouterGateway,
                openRouterModel = openRouterModel,
            )
        }
        val agentDependencies = AgentWebDependencies(
            agent = agent,
            catalog = catalog,
            runtimeConfig = runtimeConfig,
            availableProviderIds = providerRegistry.availableProviderIds(),
        )
        embeddedServer(Netty, host = "127.0.0.1", port = port) {
            module(service, experimentService, agentDependencies)
        }.start(wait = true)
    } finally {
        httpClient.close()
    }
}
