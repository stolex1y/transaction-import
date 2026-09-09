package io.github.stolex1y.transactionimport.persistence

import io.github.stolex1y.transactionimport.core.AgentConfig
import io.github.stolex1y.transactionimport.core.AgentGatewayResolver
import io.github.stolex1y.transactionimport.core.ChatChoice
import io.github.stolex1y.transactionimport.core.ChatCompletionGateway
import io.github.stolex1y.transactionimport.core.ChatCompletionRequest
import io.github.stolex1y.transactionimport.core.ChatCompletionResponse
import io.github.stolex1y.transactionimport.core.ResponseMessage
import io.github.stolex1y.transactionimport.core.SmartExpenseAgent
import io.github.stolex1y.transactionimport.core.Usage
import io.github.stolex1y.transactionimport.core.UserPreferences
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteImportSessionRepositoryTest {
    @Test
    fun restoresConversationDraftConfigurationAndPreferencesAfterRestart() = runBlocking {
        val database = Files.createTempFile("transaction-import-restart-", ".sqlite")
        try {
            var firstId = 0
            val firstAgent = SmartExpenseAgent(
                repository = SqliteImportSessionRepository(database.absolutePathString()),
                gatewayResolver = AgentGatewayResolver { SingleResponseGateway(initialDraftJson) },
                idGenerator = { "first-${++firstId}" },
                nowEpochMs = { 1_000L + firstId },
            )
            val created = firstAgent.createSession("Persisted import", defaultConfig)
            val extracted = firstAgent.sendMessage(
                sessionId = created.session.id,
                expectedRevision = 0,
                text = "15.01 DEMO MARKET 1250,50 RUB",
            )
            val transaction = extracted.draft!!.transactions.single().transaction
            val described = firstAgent.replaceTransaction(
                sessionId = created.session.id,
                expectedRevision = extracted.session.revision,
                transactionId = "1",
                included = true,
                description = "Рабочий обед",
                replacement = transaction.copy(cardLast4 = null),
            )
            val saved = firstAgent.updateSessionConfig(
                sessionId = created.session.id,
                expectedRevision = described.session.revision,
                config = defaultConfig.copy(modelId = "next-model"),
            )
            firstAgent.updatePreferences("Не выбирай переводы между счетами")

            var secondId = 0
            val secondGateway = SingleResponseGateway(renameMerchantPatchJson)
            val secondAgent = SmartExpenseAgent(
                repository = SqliteImportSessionRepository(database.absolutePathString()),
                gatewayResolver = AgentGatewayResolver { secondGateway },
                idGenerator = { "second-${++secondId}" },
                nowEpochMs = { 2_000L + secondId },
            )
            val restored = secondAgent.getSession(created.session.id)
            assertEquals(7, restored.metrics.single().totalTokens)
            assertEquals("Рабочий обед", restored.draft!!.transactions.single().description)
            assertNull(restored.draft!!.transactions.single().transaction.cardLast4)
            assertEquals("Не выбирай переводы между счетами", secondAgent.getPreferences().userPrompt)

            val corrected = secondAgent.sendMessage(
                sessionId = restored.session.id,
                expectedRevision = restored.session.revision,
                text = "Замени контрагента 1 на DEMO STORE",
            )
            assertEquals("next-model", secondGateway.request!!.model)
            assertEquals(
                "Замени контрагента 1 на DEMO STORE",
                secondGateway.request!!.messages.last().content,
            )
            assertEquals(2, corrected.metrics.size)

            val sibling = secondAgent.createSession("Не удалять", defaultConfig)
            secondAgent.deleteSession(created.session.id, corrected.session.revision)
            assertNull(SqliteImportSessionRepository(database.absolutePathString()).get(created.session.id))
            assertEquals(sibling.session.id, secondAgent.getSession(sibling.session.id).session.id)
        } finally {
            deleteDatabase(database)
        }
    }

    @Test
    fun migratesVersionTwoDatabaseWithoutDeletingSessions() = runBlocking {
        val database = Files.createTempFile("transaction-import-v2-", ".sqlite")
        try {
            createVersionTwoDatabase(database)
            val repository = SqliteImportSessionRepository(database.absolutePathString())
            val restored = repository.get("legacy-session")!!

            assertEquals("legacy-model", restored.session.config.modelId)
            assertEquals(1, restored.draft!!.transactions.size)
            assertEquals("", restored.draft!!.transactions.single().description)
            assertTrue(restored.draft!!.transactions.single().fieldErrors.isEmpty())
            assertEquals("1", restored.draft!!.transactions.single().id)
            DriverManager.getConnection("jdbc:sqlite:${database.absolutePathString()}").use { connection ->
                connection.prepareStatement(
                    "SELECT transaction_id FROM draft_transactions WHERE session_id = 'legacy-session'",
                ).use { statement ->
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals("1", rows.getString(1))
                    }
                }
            }
            assertEquals(UserPreferences("Начальная настройка"), repository.getOrCreatePreferences("Начальная настройка"))
            assertEquals(UserPreferences("Новая настройка"), repository.savePreferences(UserPreferences("Новая настройка")))

            val transaction = restored.draft!!.transactions.single().transaction
            val updated = SmartExpenseAgent(
                repository = repository,
                gatewayResolver = AgentGatewayResolver { error("LLM не нужен для локальной правки") },
                idGenerator = { "unused" },
                nowEpochMs = { 5_000L },
            ).replaceTransaction(
                sessionId = "legacy-session",
                expectedRevision = restored.session.revision,
                transactionId = "1",
                included = true,
                description = "После миграции",
                replacement = transaction,
            )

            val afterRestart = SqliteImportSessionRepository(database.absolutePathString())
                .get("legacy-session")!!
            assertEquals(updated.session.revision, afterRestart.session.revision)
            assertEquals("После миграции", afterRestart.draft!!.transactions.single().description)
            assertFalse(afterRestart.draft!!.transactions.single().fieldErrors.containsKey("card_last4"))
            assertEquals("Новая настройка", afterRestart.run { repository.getOrCreatePreferences("").userPrompt })
        } finally {
            deleteDatabase(database)
        }
    }

    private fun createVersionTwoDatabase(path: Path) {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${path.absolutePathString()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE import_sessions (
                        id TEXT PRIMARY KEY,
                        title TEXT NOT NULL,
                        config_json TEXT NOT NULL,
                        revision INTEGER NOT NULL,
                        created_at_epoch_ms INTEGER NOT NULL,
                        updated_at_epoch_ms INTEGER NOT NULL,
                        draft_status TEXT,
                        rejection_reason TEXT,
                        unparsed_fragments_json TEXT
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE conversation_messages (
                        session_id TEXT NOT NULL,
                        sequence_number INTEGER NOT NULL,
                        id TEXT NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        display_text TEXT NOT NULL,
                        created_at_epoch_ms INTEGER NOT NULL,
                        PRIMARY KEY (session_id, sequence_number),
                        UNIQUE (session_id, id),
                        FOREIGN KEY (session_id) REFERENCES import_sessions(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE draft_transactions (
                        session_id TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        transaction_id TEXT NOT NULL,
                        included INTEGER NOT NULL,
                        transaction_json TEXT NOT NULL,
                        PRIMARY KEY (session_id, transaction_id),
                        UNIQUE (session_id, position),
                        FOREIGN KEY (session_id) REFERENCES import_sessions(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    INSERT INTO import_sessions VALUES (
                        'legacy-session', 'Старая сессия',
                        '{"provider_id":"legacy","model_id":"legacy-model","reasoning_mode_id":"disabled","temperature":0.2,"max_tokens":2000}',
                        1, 1000, 1100, 'READY', NULL, '[]'
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    INSERT INTO draft_transactions VALUES (
                        'legacy-session', 0, 'tx-1', 1,
                        '{"source_index":1,"direction":"expense","occurred_at":"2026-01-15T12:10:00","posted_at":null,"amount_minor":125050,"currency":"RUB","merchant":"DEMO MARKET","category_id":"food.groceries","card_last4":null,"needs_review":false,"issues":[]}'
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    private fun deleteDatabase(database: Path) {
        Files.deleteIfExists(database)
        Files.deleteIfExists(database.resolveSibling("${database.fileName}-wal"))
        Files.deleteIfExists(database.resolveSibling("${database.fileName}-shm"))
    }

    private class SingleResponseGateway(
        private val content: String,
    ) : ChatCompletionGateway {
        var request: ChatCompletionRequest? = null
            private set

        override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
            this.request = request
            return ChatCompletionResponse(
                choices = listOf(
                    ChatChoice(
                        message = ResponseMessage(content = content),
                        finishReason = "stop",
                    ),
                ),
                usage = Usage(promptTokens = 5, completionTokens = 2, totalTokens = 7),
            )
        }
    }

    private companion object {
        val defaultConfig = AgentConfig("fake", "fake-model", "disabled")

        val initialDraftJson = """
            {
              "status": "ready",
              "rejection_reason": null,
              "transactions": [
                {
                  "source_index": 1,
                  "included": true,
                  "direction": "expense",
                  "occurred_at": "2026-01-15T12:10:00",
                  "posted_at": null,
                  "amount_minor": 125050,
                  "currency": "RUB",
                  "merchant": "DEMO MARKET",
                  "category_id": "food.groceries",
                  "card_last4": "1234",
                  "needs_review": false,
                  "issues": []
                }
              ],
              "unparsed_fragments": []
            }
        """.trimIndent()

        val renameMerchantPatchJson = """
            {
              "status": "applied",
              "message": "Контрагент изменён.",
              "operations": [
                {
                  "transaction_id": "1",
                  "action": "set_field",
                  "field": "merchant",
                  "value": "DEMO STORE"
                }
              ]
            }
        """.trimIndent()
    }
}
