package io.github.stolex1y.transactionimport.persistence

import io.github.stolex1y.transactionimport.core.SupportChatInteractionState
import io.github.stolex1y.transactionimport.core.SupportChatMessage
import io.github.stolex1y.transactionimport.core.SupportChatRepository
import io.github.stolex1y.transactionimport.core.SupportChatSnapshot
import io.github.stolex1y.transactionimport.core.SupportCitation
import io.github.stolex1y.transactionimport.core.SupportMessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types

class SqliteSupportChatRepository(
    databasePath: String,
) : SupportChatRepository {
    private val jdbcUrl = "jdbc:sqlite:$databasePath"

    init {
        Class.forName("org.sqlite.JDBC")
        openConnection().use(::initializeSchema)
    }

    override suspend fun snapshot(): SupportChatSnapshot = database {
        SupportChatSnapshot(
            messages = prepareStatement(
                "SELECT * FROM support_chat_messages ORDER BY sequence_number ASC",
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) add(rows.toSupportMessage())
                    }
                }
            },
            interaction = readInteractionState(this),
        )
    }

    override suspend fun append(message: SupportChatMessage): SupportChatMessage = transaction {
        require(message.id.isNotBlank()) { "Support message ID не должен быть пустым." }
        require(message.text.isNotBlank()) { "Support message не должен быть пустым." }
        prepareStatement(
            """
            INSERT INTO support_chat_messages (
                id, role, message_text, created_at_epoch_ms, ticket_id, citations_json
            ) VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, message.id)
            statement.setString(2, message.role.name)
            statement.setString(3, message.text)
            statement.setLong(4, message.createdAtEpochMs)
            setNullableString(statement, 5, message.ticketId)
            statement.setString(6, databaseJson.encodeToString(message.citations))
            require(statement.executeUpdate() == 1) { "Не удалось сохранить support message." }
        }
        message
    }

    override suspend fun saveInteractionState(state: SupportChatInteractionState): Unit = transaction {
        prepareStatement(
            """
            UPDATE support_chat_state
            SET awaiting_follow_up = ?, pending_route_message_id = ?
            WHERE singleton_id = 1
            """.trimIndent(),
        ).use { statement ->
            statement.setInt(1, state.awaitingFollowUp.toSqlBoolean())
            setNullableString(statement, 2, state.pendingRouteMessageId)
            require(statement.executeUpdate() == 1) { "Не удалось сохранить состояние support chat." }
        }
    }

    override suspend fun messagesForTicket(ticketId: String, limit: Int): List<SupportChatMessage> = database {
        require(ticketId.isNotBlank()) { "Ticket ID не должен быть пустым." }
        require(limit in 1..MAX_CONTEXT_MESSAGES) {
            "Лимит support context должен быть от 1 до $MAX_CONTEXT_MESSAGES."
        }
        prepareStatement(
            """
            SELECT * FROM support_chat_messages
            WHERE ticket_id = ?
            ORDER BY sequence_number DESC
            LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, ticketId)
            statement.setInt(2, limit)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(rows.toSupportMessage())
                }.asReversed()
            }
        }
    }

    override suspend fun messagesForRoutingFrom(messageId: String): List<SupportChatMessage> = database {
        val sequence = messageSequence(this, messageId) ?: return@database emptyList()
        prepareStatement(
            """
            SELECT * FROM support_chat_messages
            WHERE ticket_id IS NULL AND sequence_number >= ?
            ORDER BY sequence_number ASC
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, sequence)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(rows.toSupportMessage())
                }
            }
        }
    }

    override suspend fun associateUnassignedMessagesFrom(messageId: String, ticketId: String): Unit = transaction {
        require(ticketId.isNotBlank()) { "Ticket ID не должен быть пустым." }
        val sequence = messageSequence(this, messageId)
            ?: throw IllegalArgumentException("Support route message не найден.")
        prepareStatement(
            """
            UPDATE support_chat_messages
            SET ticket_id = ?
            WHERE ticket_id IS NULL AND sequence_number >= ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, ticketId)
            statement.setLong(2, sequence)
            statement.executeUpdate()
        }
        Unit
    }

    override suspend fun associateAllUnassignedMessages(ticketId: String): Unit = transaction {
        require(ticketId.isNotBlank()) { "Ticket ID не должен быть пустым." }
        prepareStatement(
            "UPDATE support_chat_messages SET ticket_id = ? WHERE ticket_id IS NULL",
        ).use { statement ->
            statement.setString(1, ticketId)
            statement.executeUpdate()
        }
        Unit
    }

    override suspend fun latestMessageTimeForTicket(ticketId: String): Long? = database {
        require(ticketId.isNotBlank()) { "Ticket ID не должен быть пустым." }
        prepareStatement(
            "SELECT MAX(created_at_epoch_ms) FROM support_chat_messages WHERE ticket_id = ?",
        ).use { statement ->
            statement.setString(1, ticketId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) null else rows.getLong(1).let { if (rows.wasNull()) null else it }
            }
        }
    }

    private fun initializeSchema(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode=WAL")
            statement.execute("PRAGMA foreign_keys=ON")
            statement.execute("PRAGMA busy_timeout=5000")
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS support_chat_messages (
                    sequence_number INTEGER PRIMARY KEY AUTOINCREMENT,
                    id TEXT NOT NULL UNIQUE,
                    role TEXT NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
                    message_text TEXT NOT NULL,
                    created_at_epoch_ms INTEGER NOT NULL,
                    ticket_id TEXT,
                    citations_json TEXT NOT NULL DEFAULT '[]'
                )
                """.trimIndent(),
            )
            statement.execute(
                "CREATE INDEX IF NOT EXISTS support_chat_ticket_idx " +
                    "ON support_chat_messages(ticket_id, sequence_number DESC)",
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS support_chat_state (
                    singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1),
                    awaiting_follow_up INTEGER NOT NULL DEFAULT 0 CHECK (awaiting_follow_up IN (0, 1)),
                    pending_route_message_id TEXT
                )
                """.trimIndent(),
            )
            statement.execute(
                "INSERT OR IGNORE INTO support_chat_state (singleton_id) VALUES (1)",
            )
        }
    }

    private fun readInteractionState(connection: Connection): SupportChatInteractionState =
        connection.prepareStatement(
            "SELECT awaiting_follow_up, pending_route_message_id FROM support_chat_state WHERE singleton_id = 1",
        ).use { statement ->
            statement.executeQuery().use { rows ->
                check(rows.next()) { "Support chat state row is missing." }
                SupportChatInteractionState(
                    awaitingFollowUp = rows.getInt("awaiting_follow_up") == 1,
                    pendingRouteMessageId = rows.getString("pending_route_message_id"),
                )
            }
        }

    private fun messageSequence(connection: Connection, messageId: String): Long? =
        connection.prepareStatement(
            "SELECT sequence_number FROM support_chat_messages WHERE id = ?",
        ).use { statement ->
            statement.setString(1, messageId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
        }

    private fun ResultSet.toSupportMessage(): SupportChatMessage = SupportChatMessage(
        id = getString("id"),
        role = SupportMessageRole.valueOf(getString("role")),
        text = getString("message_text"),
        createdAtEpochMs = getLong("created_at_epoch_ms"),
        ticketId = getString("ticket_id"),
        citations = databaseJson.decodeFromString(getString("citations_json")),
    )

    private suspend fun <T> database(block: Connection.() -> T): T = withContext(Dispatchers.IO) {
        openConnection().use(block)
    }

    private suspend fun <T> transaction(block: Connection.() -> T): T = withContext(Dispatchers.IO) {
        openConnection().use { connection ->
            connection.autoCommit = false
            try {
                connection.block().also { connection.commit() }
            } catch (error: Throwable) {
                runCatching { connection.rollback() }
                throw error
            }
        }
    }

    private fun openConnection(): Connection =
        java.sql.DriverManager.getConnection(jdbcUrl).also { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys=ON")
                statement.execute("PRAGMA busy_timeout=5000")
            }
        }

    private fun Boolean.toSqlBoolean(): Int = if (this) 1 else 0

    private companion object {
        const val MAX_CONTEXT_MESSAGES = 10
        val databaseJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}

private fun setNullableString(statement: java.sql.PreparedStatement, index: Int, value: String?) {
    if (value == null) statement.setNull(index, Types.VARCHAR) else statement.setString(index, value)
}
