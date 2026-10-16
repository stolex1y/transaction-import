package io.github.stolex1y.transactionimport.web

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class JsonSupportTicketMcpServer(
    path: Path,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val atomicReplace: (Path, Path) -> Unit = { source, destination ->
        Files.move(
            source,
            destination,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    },
) : McpLogicalServer {
    private val path = path.toAbsolutePath().normalize()
    private val lockPath = path.resolveSibling("${path.fileName}.lock")
    private val mutex = storeLocks.computeIfAbsent(path) { Mutex() }

    override suspend fun tools(): List<McpToolCatalog> = listOf(
        McpToolCatalog(
            name = GET_USER_TOOL,
            description = "Возвращает минимальный synthetic context локального пользователя поддержки.",
            inputSchema = ToolSchema(),
        ),
        McpToolCatalog(
            name = GET_OPEN_TICKET_TOOL,
            description = "Возвращает единственный активный внутренний ticket или null.",
            inputSchema = ToolSchema(),
        ),
        McpToolCatalog(
            name = LIST_CLOSED_TICKETS_TOOL,
            description = "Возвращает темы закрытых tickets без transcript.",
            inputSchema = ToolSchema(),
        ),
        McpToolCatalog(
            name = CREATE_TICKET_TOOL,
            description = "Создаёт ticket с обобщённой темой без сохранения текста пользователя.",
            inputSchema = ToolSchema(),
        ),
        McpToolCatalog(
            name = GET_TICKET_CONTEXT_TOOL,
            description = "Возвращает минимальные данные текущего open ticket и synthetic user.",
            inputSchema = stringArgumentSchema("ticket_id"),
        ),
        McpToolCatalog(
            name = CLOSE_TICKET_TOOL,
            description = "Закрывает только существующий open ticket.",
            inputSchema = stringArgumentSchema("ticket_id"),
        ),
        McpToolCatalog(
            name = REOPEN_TICKET_TOOL,
            description = "Переоткрывает только существующий closed ticket.",
            inputSchema = stringArgumentSchema("ticket_id"),
        ),
    )

    override suspend fun call(tool: String, arguments: JsonObject): TbankToolCallResponse =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withFileLock {
                    try {
                        when (tool) {
                            GET_USER_TOOL -> success(tool, readStore().users.single { it.id == DEMO_USER_ID })
                            GET_OPEN_TICKET_TOOL -> {
                                val open = readStore().tickets.filter { it.status == SupportTicketStatus.OPEN }
                                check(open.size <= 1) { "More than one active support ticket." }
                                success(tool, SupportTicketLookup(open.singleOrNull()))
                            }
                            LIST_CLOSED_TICKETS_TOOL -> success(
                                tool,
                                readStore().tickets
                                    .filter { it.status == SupportTicketStatus.CLOSED }
                                    .sortedByDescending(JsonSupportTicketRecord::createdAtEpochMs)
                                    .map { it.toSummary() },
                            )
                            CREATE_TICKET_TOOL -> createTicket(tool)
                            GET_TICKET_CONTEXT_TOOL -> getTicketContext(tool, arguments)
                            CLOSE_TICKET_TOOL -> transitionTicket(tool, arguments, SupportTicketStatus.CLOSED)
                            REOPEN_TICKET_TOOL -> transitionTicket(tool, arguments, SupportTicketStatus.OPEN)
                            else -> failure(tool, "Support ticket tool не разрешён.")
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: IllegalArgumentException) {
                        failure(tool, "Некорректный запрос к support ticket store.")
                    } catch (error: Throwable) {
                        failure(tool, "Данные поддержки временно недоступны.")
                    }
                }
            }
        }

    private fun createTicket(tool: String): TbankToolCallResponse {
        val state = readStore()
        check(state.tickets.none { it.status == SupportTicketStatus.OPEN }) {
            "An active support ticket already exists."
        }
        val id = idGenerator().also { require(it.isNotBlank()) }
        require(state.tickets.none { it.id == id }) { "Ticket ID must be unique." }
        val user = state.users.single { it.id == DEMO_USER_ID }
        val ticket = JsonSupportTicketRecord(
            id = id,
            userId = user.id,
            topic = DEFAULT_TOPIC,
            status = SupportTicketStatus.OPEN,
            createdAtEpochMs = nowEpochMs(),
        )
        writeStore(state.copy(tickets = state.tickets + ticket))
        return success(tool, ticket)
    }

    private fun getTicketContext(tool: String, arguments: JsonObject): TbankToolCallResponse {
        val state = readStore()
        val ticket = state.ticketForCurrentUser(arguments.requiredString("ticket_id"))
        check(ticket.status == SupportTicketStatus.OPEN) { "Ticket is not open." }
        val user = state.users.single { it.id == ticket.userId }
        return success(tool, SupportTicketContext(user, ticket))
    }

    private fun transitionTicket(
        tool: String,
        arguments: JsonObject,
        target: SupportTicketStatus,
    ): TbankToolCallResponse {
        val state = readStore()
        val ticketId = arguments.requiredString("ticket_id")
        val ticket = state.ticketForCurrentUser(ticketId)
        val expected = if (target == SupportTicketStatus.CLOSED) SupportTicketStatus.OPEN else SupportTicketStatus.CLOSED
        check(ticket.status == expected) { "Ticket status transition is not allowed." }
        val updated = when (target) {
            SupportTicketStatus.CLOSED -> ticket.copy(
                status = SupportTicketStatus.CLOSED,
                closedAtEpochMs = nowEpochMs(),
            )
            SupportTicketStatus.OPEN -> ticket.copy(
                status = SupportTicketStatus.OPEN,
                closedAtEpochMs = null,
                reopenedAtEpochMs = nowEpochMs(),
            )
        }
        writeStore(state.copy(tickets = state.tickets.map { if (it.id == ticket.id) updated else it }))
        return success(tool, updated)
    }

    private fun readStore(): SupportTicketStore {
        if (!Files.exists(path)) return emptyStore()
        val state = storeJson.decodeFromString<SupportTicketStore>(Files.readString(path))
        require(state.schemaVersion == STORE_SCHEMA_VERSION)
        require(state.users.size == 1 && state.users.single().id == DEMO_USER_ID)
        require(state.tickets.map(JsonSupportTicketRecord::id).distinct().size == state.tickets.size)
        require(state.tickets.all { it.userId == DEMO_USER_ID })
        require(state.tickets.all { ticket ->
            when (ticket.status) {
                SupportTicketStatus.OPEN -> ticket.closedAtEpochMs == null
                SupportTicketStatus.CLOSED -> ticket.closedAtEpochMs != null
            }
        })
        return state
    }

    private fun writeStore(state: SupportTicketStore) {
        val parent = requireNotNull(path.parent) { "Support ticket store requires a parent directory." }
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, "support-tickets-", ".tmp")
        try {
            Files.writeString(temporary, storeJson.encodeToString(state))
            atomicReplace(temporary, path)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun <T> withFileLock(action: () -> T): T {
        val parent = requireNotNull(path.parent) { "Support ticket store requires a parent directory." }
        Files.createDirectories(parent)
        return FileChannel.open(
            lockPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.lock().use { action() }
        }
    }

    private fun SupportTicketStore.ticketForCurrentUser(ticketId: String): JsonSupportTicketRecord =
        tickets.singleOrNull { it.id == ticketId && it.userId == DEMO_USER_ID }
            ?: throw IllegalArgumentException("Ticket not found.")

    private fun emptyStore() = SupportTicketStore(
        users = listOf(JsonSupportUserRecord(DEMO_USER_ID, "Пользователь Transaction Import", "ru")),
    )

    private inline fun <reified T> success(tool: String, value: T): TbankToolCallResponse =
        TbankToolCallResponse(tool = tool, text = storeJson.encodeToString(value))

    private fun failure(tool: String, message: String) =
        TbankToolCallResponse(tool = tool, isError = true, text = message)

    private fun JsonSupportTicketRecord.toSummary() = SupportTicketSummary(
        id = id,
        topic = topic,
        createdAtEpochMs = createdAtEpochMs,
        closedAtEpochMs = requireNotNull(closedAtEpochMs),
    )

    private fun JsonObject.requiredString(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Missing string argument.")

    private fun stringArgumentSchema(name: String): ToolSchema = ToolSchema(
        properties = buildJsonObject {
            put(name, buildJsonObject { put("type", "string") })
        },
        required = listOf(name),
    )

    private companion object {
        const val GET_USER_TOOL = "get_support_user"
        const val GET_OPEN_TICKET_TOOL = "get_open_support_ticket"
        const val LIST_CLOSED_TICKETS_TOOL = "list_closed_support_ticket_summaries"
        const val CREATE_TICKET_TOOL = "create_support_ticket"
        const val GET_TICKET_CONTEXT_TOOL = "get_support_ticket_context"
        const val CLOSE_TICKET_TOOL = "close_support_ticket"
        const val REOPEN_TICKET_TOOL = "reopen_support_ticket"
        const val DEMO_USER_ID = "demo-user"
        const val DEFAULT_TOPIC = "Вопрос о Transaction Import"
        const val STORE_SCHEMA_VERSION = 1
        val storeLocks = ConcurrentHashMap<Path, Mutex>()
        val storeJson = Json {
            ignoreUnknownKeys = false
            encodeDefaults = true
            explicitNulls = false
        }
    }
}

@Serializable
private data class SupportTicketStore(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val users: List<JsonSupportUserRecord>,
    val tickets: List<JsonSupportTicketRecord> = emptyList(),
)

@Serializable
private data class JsonSupportUserRecord(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val locale: String,
)

@Serializable
private enum class SupportTicketStatus {
    @SerialName("open")
    OPEN,

    @SerialName("closed")
    CLOSED,
}

@Serializable
private data class JsonSupportTicketRecord(
    val id: String,
    @SerialName("user_id") val userId: String,
    val topic: String,
    val status: SupportTicketStatus,
    @SerialName("created_at_epoch_ms") val createdAtEpochMs: Long,
    @SerialName("closed_at_epoch_ms") val closedAtEpochMs: Long? = null,
    @SerialName("reopened_at_epoch_ms") val reopenedAtEpochMs: Long? = null,
)

@Serializable
private data class SupportTicketLookup(
    val ticket: JsonSupportTicketRecord?,
)

@Serializable
private data class SupportTicketSummary(
    val id: String,
    val topic: String,
    @SerialName("created_at_epoch_ms") val createdAtEpochMs: Long,
    @SerialName("closed_at_epoch_ms") val closedAtEpochMs: Long,
)

@Serializable
private data class SupportTicketContext(
    val user: JsonSupportUserRecord,
    val ticket: JsonSupportTicketRecord,
)
