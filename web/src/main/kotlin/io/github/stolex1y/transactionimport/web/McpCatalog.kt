package io.github.stolex1y.transactionimport.web

import io.github.stolex1y.transactionimport.core.McpServerConfig

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

private const val MCP_CLIENT_NAME = "smart-expense-agent"
private const val MCP_CLIENT_VERSION = "0.1.0"
private const val CONNECT_TIMEOUT_MS = 15_000L
private const val LIST_TIMEOUT_MS = 5_000L
private const val CALL_TIMEOUT_MS = 30_000L
private const val TBANK_SERVER_ID = "tbank-transactions"
private const val LEGACY_TBANK_SERVER_ID = "bank-transactions"

@Serializable
data class McpToolCatalog(
    val name: String,
    val description: String? = null,
    @SerialName("input_schema") val inputSchema: ToolSchema,
)

@Serializable
data class McpServerCatalog(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val status: String,
    val tools: List<McpToolCatalog> = emptyList(),
    val error: String? = null,
)

@Serializable
data class McpCatalogResponse(
    val servers: List<McpServerCatalog>,
)

@Serializable
data class TbankLoginRequest(
    val mode: String = "fake",
    val phone: String = "",
    val password: String = "",
    val otp: String = "",
)

@Serializable
data class TbankLoginResponse(
    val mode: String,
    val status: String,
    val message: String,
    @SerialName("requires_otp") val requiresOtp: Boolean = false,
    @SerialName("requires_password") val requiresPassword: Boolean = false,
    val authenticated: Boolean = false,
    @SerialName("account_count") val accountCount: Int = 0,
    @SerialName("persistence_status") val persistenceStatus: String = "not_configured",
    @SerialName("persistence_message") val persistenceMessage: String? = null,
)

@Serializable
data class TbankSessionResponse(
    val mode: String? = null,
    val authenticated: Boolean,
    @SerialName("account_count") val accountCount: Int = 0,
    @SerialName("persistence_status") val persistenceStatus: String = "not_configured",
    @SerialName("persistence_message") val persistenceMessage: String? = null,
)

@Serializable
data class TbankToolCallRequest(
    val tool: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class TbankToolCallResponse(
    val tool: String,
    @SerialName("is_error") val isError: Boolean = false,
    val text: String,
)

interface TbankMcpProvider {
    suspend fun login(request: TbankLoginRequest): TbankLoginResponse
    suspend fun logout(): TbankSessionResponse
    suspend fun session(): TbankSessionResponse
    suspend fun callTool(request: TbankToolCallRequest): TbankToolCallResponse
}

object UnavailableTbankMcpProvider : TbankMcpProvider {
    private const val MESSAGE = "T-Bank MCP server не настроен."

    override suspend fun login(request: TbankLoginRequest) =
        TbankLoginResponse(mode = request.mode, status = "error", message = MESSAGE)

    override suspend fun logout() = TbankSessionResponse(authenticated = false)

    override suspend fun session() = TbankSessionResponse(authenticated = false)

    override suspend fun callTool(request: TbankToolCallRequest) =
        TbankToolCallResponse(tool = request.tool, isError = true, text = MESSAGE)
}

fun interface McpCatalogProvider {
    suspend fun catalog(): McpCatalogResponse
}
class McpCatalogService private constructor(
    private val httpClient: HttpClient,
    private val configs: List<McpServerConfig>,
    private val states: MutableMap<String, McpServerCatalog>,
    private val connections: MutableMap<String, ActiveMcpConnection>,
) : McpCatalogProvider, TbankMcpProvider, AutoCloseable {
    private val mutex = Mutex()

    override suspend fun catalog(): McpCatalogResponse = mutex.withLock {
        val current = configs.map { config ->
            if (!config.enabled) {
                return@map states.getValue(config.id)
            }

            val connection = connections[config.id]
            if (connection == null) {
                val result = connect(config, httpClient)
                result.connection?.let { connections[config.id] = it }
                return@map updateState(config, result).also { states[config.id] = it }
            }

            try {
                val tools = withTimeout(LIST_TIMEOUT_MS) {
                    connection.client.listTools().tools.map { tool ->
                        McpToolCatalog(
                            name = tool.name,
                            description = tool.description,
                            inputSchema = tool.inputSchema,
                        )
                    }
                }
                McpServerCatalog(
                    id = config.id,
                    displayName = config.displayName,
                    status = STATUS_CONNECTED,
                    tools = tools,
                ).also { states[config.id] = it }
            } catch (error: Throwable) {
                closeConnection(config.id, connection)
                McpServerCatalog(
                    id = config.id,
                    displayName = config.displayName,
                    status = STATUS_PROTOCOL_ERROR,
                    error = safeError(error),
                ).also { states[config.id] = it }
            }
        }
        McpCatalogResponse(servers = current)
    }

    override suspend fun login(request: TbankLoginRequest): TbankLoginResponse = mutex.withLock {
        val config = tbankConfig()
            ?: return@withLock TbankLoginResponse(
                mode = request.mode,
                status = "error",
                message = "T-Bанк MCP endpoint не настроен.",
            )
        try {
            val response = httpClient.post(serviceUrl(config, "/tbank/login")) {
                header(io.ktor.http.HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(request)
            }
            if (!response.status.isSuccess()) {
                TbankLoginResponse(
                    mode = request.mode,
                    status = "error",
                    message = "Т-Банк MCP server вернул HTTP ${response.status.value}. Проверьте актуальность server и его logs.",
                )
            } else {
                response.body()
            }
        } catch (error: Throwable) {
            TbankLoginResponse(
                mode = request.mode,
                status = "error",
                message = "Не удалось выполнить login: ${safeError(error)}",
            )
        }
    }

    override suspend fun logout(): TbankSessionResponse = mutex.withLock {
        val config = tbankConfig() ?: return@withLock TbankSessionResponse(authenticated = false)
        try {
            httpClient.post(serviceUrl(config, "/tbank/logout")).body()
        } catch (_: Throwable) {
            TbankSessionResponse(authenticated = false)
        }
    }

    override suspend fun session(): TbankSessionResponse = mutex.withLock {
        val config = tbankConfig() ?: return@withLock TbankSessionResponse(authenticated = false)
        try {
            httpClient.get(serviceUrl(config, "/tbank/session")).body()
        } catch (_: Throwable) {
            TbankSessionResponse(authenticated = false)
        }
    }

    override suspend fun callTool(request: TbankToolCallRequest): TbankToolCallResponse = mutex.withLock {
        val config = tbankConfig()
            ?: return@withLock TbankToolCallResponse(
                tool = request.tool,
                isError = true,
                text = "T-Банк MCP endpoint не настроен.",
            )
        val connection = connections[config.id] ?: run {
            val result = connect(config, httpClient)
            result.connection?.let { connections[config.id] = it }
            states[config.id] = updateState(config, result)
            result.connection
        }
            ?: return@withLock TbankToolCallResponse(
                tool = request.tool,
                isError = true,
                text = states[config.id]?.error ?: "MCP-соединение недоступно.",
            )
        try {
            val result = withTimeout(CALL_TIMEOUT_MS) {
                connection.client.callTool(
                    CallToolRequest(
                        CallToolRequestParams(request.tool, request.arguments),
                    ),
                )
            }
            val text = result.content
                .filterIsInstance<TextContent>()
                .joinToString("\n") { it.text }
                .ifBlank { result.structuredContent?.toString().orEmpty() }
            TbankToolCallResponse(
                tool = request.tool,
                isError = result.isError == true,
                text = text,
            )
        } catch (error: Throwable) {
            TbankToolCallResponse(
                tool = request.tool,
                isError = true,
                text = safeError(error),
            )
        }
    }

    override fun close() {
        runBlocking {
            mutex.withLock {
                connections.values.toList().forEach { connection ->
                    closeConnection(connection.id, connection)
                }
                connections.clear()
            }
        }
    }

    private fun tbankConfig(): McpServerConfig? =
        configs.firstOrNull { config ->
            config.enabled && config.id in setOf(TBANK_SERVER_ID, LEGACY_TBANK_SERVER_ID)
        }

    private fun serviceUrl(config: McpServerConfig, path: String): String {
        val endpoint = config.endpoint.trimEnd('/')
        val base = endpoint.substringBeforeLast('/')
        return "$base$path"
    }

    private suspend fun closeConnection(id: String, connection: ActiveMcpConnection) {
        connections.remove(id)
        try {
            connection.client.close()
        } catch (_: Throwable) {
            // MCP session shutdown is best effort; the HTTP client is closed by Main.
        }
    }

    private data class ActiveMcpConnection(
        val id: String,
        val client: Client,
    )

    companion object {
        const val STATUS_CONNECTED = "connected"
        const val STATUS_DISABLED = "disabled"
        const val STATUS_UNAVAILABLE = "unavailable"
        const val STATUS_PROTOCOL_ERROR = "protocol_error"

        suspend fun connect(
            configs: List<McpServerConfig>,
            httpClient: HttpClient,
        ): McpCatalogService = withContext(kotlinx.coroutines.Dispatchers.IO) {
            val states = linkedMapOf<String, McpServerCatalog>()
            val connections = linkedMapOf<String, ActiveMcpConnection>()

            configs.forEach { config ->
                if (!config.enabled) {
                    states[config.id] = McpServerCatalog(
                        id = config.id,
                        displayName = config.displayName,
                        status = STATUS_DISABLED,
                    )
                    return@forEach
                }

                val result = connect(config, httpClient)
                result.connection?.let { connections[config.id] = it }
                states[config.id] = updateState(config, result)
            }

            McpCatalogService(
                httpClient = httpClient,
                configs = configs,
                states = states,
                connections = connections,
            )
        }

        private suspend fun connect(
            config: McpServerConfig,
            httpClient: HttpClient,
        ): ConnectionResult {
            val endpoint = config.endpoint.trim()
            if (endpoint.isEmpty()) {
                return ConnectionResult(
                    status = STATUS_UNAVAILABLE,
                    error = "MCP endpoint не задан.",
                )
            }

            val client = Client(
                Implementation(
                    name = MCP_CLIENT_NAME,
                    version = MCP_CLIENT_VERSION,
                ),
            )

            return try {
                withTimeout(CONNECT_TIMEOUT_MS) {
                    client.connect(
                        StreamableHttpClientTransport(httpClient, endpoint),
                    )
                    val tools = client.listTools().tools.map { tool ->
                        McpToolCatalog(
                            name = tool.name,
                            description = tool.description,
                            inputSchema = tool.inputSchema,
                        )
                    }
                    ConnectionResult(
                        status = STATUS_CONNECTED,
                        tools = tools,
                        connection = ActiveMcpConnection(
                            id = config.id,
                            client = client,
                        ),
                    )
                }
            } catch (error: Throwable) {
                try {
                    client.close()
                } catch (_: Throwable) {
                    // There may be no established HTTP MCP session to close.
                }
                ConnectionResult(
                    status = statusFor(error),
                    error = safeError(error),
                )
            }
        }

        private fun updateState(
            config: McpServerConfig,
            result: ConnectionResult,
        ): McpServerCatalog = McpServerCatalog(
            id = config.id,
            displayName = config.displayName,
            status = result.status,
            tools = result.tools,
            error = result.error,
        )

        private fun statusFor(error: Throwable): String =
            if (error.causes().any(::isUnavailableError)) {
                STATUS_UNAVAILABLE
            } else {
                STATUS_PROTOCOL_ERROR
            }

        private fun isUnavailableError(error: Throwable): Boolean =
            error is ConnectException ||
                error is UnknownHostException ||
                error is UnresolvedAddressException ||
                error is SocketTimeoutException

        private fun Throwable.causes(): Sequence<Throwable> =
            generateSequence(this) { it.cause }

        private data class ConnectionResult(
            val status: String,
            val tools: List<McpToolCatalog> = emptyList(),
            val error: String? = null,
            val connection: ActiveMcpConnection? = null,
        )
    }
}

private val sensitiveErrorPattern = Regex(
    """(?i)\b(access[_-]?token|refresh[_-]?token|session[_-]?id|sessionid|auth[_-]?cookie|cookie|password|otp|device[_-]?id|token)\b\s*[:=]\s*["']?[^,;\s"'})]+""",
)
private val bearerErrorPattern = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+""")

private fun safeError(error: Throwable): String =
    (error.message?.takeIf(String::isNotBlank) ?: error::class.simpleName.orEmpty())
        .replace(Regex("\\s+"), " ")
        .replace(bearerErrorPattern, "Bearer [скрыт]")
        .replace(sensitiveErrorPattern, "$1=[скрыт]")
        .take(240)
