package io.github.stolex1y.transactionimport.web

import io.github.stolex1y.transactionimport.core.McpServerConfig
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Path
import kotlin.io.path.isDirectory

private const val MCP_CLIENT_NAME = "smart-expense-agent"
private const val MCP_CLIENT_VERSION = "0.1.0"
private const val CONNECT_TIMEOUT_MS = 15_000L
private const val LIST_TIMEOUT_MS = 5_000L

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

fun interface McpCatalogProvider {
    suspend fun catalog(): McpCatalogResponse
}

class McpCatalogService private constructor(
    private val configs: List<McpServerConfig>,
    private val states: Map<String, McpServerCatalog>,
    private val connections: MutableMap<String, ActiveMcpConnection>,
) : McpCatalogProvider, AutoCloseable {
    private val mutex = Mutex()

    override suspend fun catalog(): McpCatalogResponse = mutex.withLock {
        val current = configs.map { config ->
            val connection = connections[config.id]
                ?: return@map states.getValue(config.id)

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
                )
            } catch (error: Throwable) {
                closeConnection(config.id, connection)
                McpServerCatalog(
                    id = config.id,
                    displayName = config.displayName,
                    status = STATUS_PROTOCOL_ERROR,
                    error = safeError(error),
                )
            }
        }
        McpCatalogResponse(servers = current)
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

    private suspend fun closeConnection(id: String, connection: ActiveMcpConnection) {
        connections.remove(id)
        try {
            connection.client.close()
        } catch (_: Throwable) {
            // The child process is still terminated below even if MCP shutdown fails.
        }
        connection.process.destroy()
        if (connection.process.isAlive) {
            withContext(Dispatchers.IO) {
                connection.process.destroyForcibly()
            }
        }
    }

    private data class ActiveMcpConnection(
        val id: String,
        val process: Process,
        val client: Client,
    )

    companion object {
        const val STATUS_CONNECTED = "connected"
        const val STATUS_DISABLED = "disabled"
        const val STATUS_UNAVAILABLE = "unavailable"
        const val STATUS_PROTOCOL_ERROR = "protocol_error"

        suspend fun connect(configs: List<McpServerConfig>): McpCatalogService =
            withContext(Dispatchers.IO) {
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

                    val result = connect(config)
                    if (result.connection != null) {
                        connections[config.id] = result.connection
                    }
                    states[config.id] = McpServerCatalog(
                        id = config.id,
                        displayName = config.displayName,
                        status = result.status,
                        tools = result.tools,
                        error = result.error,
                    )
                }

                McpCatalogService(
                    configs = configs,
                    states = states,
                    connections = connections,
                )
            }

        private suspend fun connect(config: McpServerConfig): ConnectionResult {
            val workingDirectory = try {
                resolveWorkingDirectory(config.workingDirectory)
            } catch (error: Throwable) {
                return ConnectionResult(
                    status = STATUS_UNAVAILABLE,
                    error = safeError(error),
                )
            }
            val command = resolveCommand(config.command, workingDirectory)
            val process = try {
                ProcessBuilder(buildList {
                    add(command)
                    addAll(config.arguments)
                })
                    .directory(workingDirectory.toFile())
                    .start()
            } catch (error: Throwable) {
                return ConnectionResult(
                    status = STATUS_UNAVAILABLE,
                    error = safeError(error),
                )
            }

            val client = Client(
                Implementation(
                    name = MCP_CLIENT_NAME,
                    version = MCP_CLIENT_VERSION,
                ),
            )
            val transport = StdioClientTransport(
                input = process.inputStream.asSource().buffered(),
                output = process.outputStream.asSink().buffered(),
                error = process.errorStream.asSource().buffered(),
            )

            return try {
                withTimeout(CONNECT_TIMEOUT_MS) {
                    client.connect(transport)
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
                            process = process,
                            client = client,
                        ),
                    )
                }
            } catch (error: Throwable) {
                try {
                    client.close()
                } catch (_: Throwable) {
                    // The process termination below is the final cleanup path.
                }
                process.destroyForcibly()
                ConnectionResult(
                    status = STATUS_PROTOCOL_ERROR,
                    error = safeError(error),
                )
            }
        }

        private data class ConnectionResult(
            val status: String,
            val tools: List<McpToolCatalog> = emptyList(),
            val error: String? = null,
            val connection: ActiveMcpConnection? = null,
        )
    }
}

private fun resolveWorkingDirectory(configured: String?): Path {
    val path = configured
        ?.let(Path::of)
        ?.toAbsolutePath()
        ?.normalize()
        ?: Path.of("").toAbsolutePath().normalize()
    require(path.isDirectory()) {
        "Рабочий каталог MCP не найден: $path"
    }
    return path
}

private fun resolveCommand(command: String, workingDirectory: Path): String {
    val configured = Path.of(command)
    return if (configured.isAbsolute || !command.contains('/')) {
        command
    } else {
        workingDirectory.resolve(configured).normalize().toString()
    }
}

private fun safeError(error: Throwable): String =
    (error.message?.takeIf(String::isNotBlank) ?: error::class.simpleName.orEmpty())
        .replace(Regex("\\s+"), " ")
        .take(240)
