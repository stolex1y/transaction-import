package io.github.stolex1y.transactionimport.persistence

import io.github.stolex1y.transactionimport.core.AgentConfig
import io.github.stolex1y.transactionimport.core.ContextManagementConfig
import io.github.stolex1y.transactionimport.core.ConversationMessage
import io.github.stolex1y.transactionimport.core.ConversationRole
import io.github.stolex1y.transactionimport.core.ConversationSummary
import io.github.stolex1y.transactionimport.core.ConversationSummaryUpdate
import io.github.stolex1y.transactionimport.core.DraftTransaction
import io.github.stolex1y.transactionimport.core.ImportDraft
import io.github.stolex1y.transactionimport.core.ImportSession
import io.github.stolex1y.transactionimport.core.ImportSessionRepository
import io.github.stolex1y.transactionimport.core.ImportSessionState
import io.github.stolex1y.transactionimport.core.ImportStatus
import io.github.stolex1y.transactionimport.core.ModelCallMetric
import io.github.stolex1y.transactionimport.core.ModelCallStatus
import io.github.stolex1y.transactionimport.core.ModelCallType
import io.github.stolex1y.transactionimport.core.RevisionConflictException
import io.github.stolex1y.transactionimport.core.SessionNotFoundException
import io.github.stolex1y.transactionimport.core.StickyFact
import io.github.stolex1y.transactionimport.core.StructuredTransaction
import io.github.stolex1y.transactionimport.core.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

class SqliteImportSessionRepository(
    databasePath: String,
    private val defaultContextManagement: ContextManagementConfig? = null,
) : ImportSessionRepository {
    private val jdbcUrl = "jdbc:sqlite:$databasePath"

    init {
        Class.forName("org.sqlite.JDBC")
        openConnection().use(::initializeSchema)
    }

    override suspend fun create(session: ImportSession): ImportSessionState = database {
        require(session.revision == 0L && !session.hasDraft) {
            "Новая сессия должна начинаться без черновика."
        }
        prepareStatement(
            """
            INSERT INTO import_sessions (
                id, title, config_json, context_management_json, parent_session_id,
                checkpoint_message_count, branch_label, revision, created_at_epoch_ms,
                updated_at_epoch_ms, draft_status, rejection_reason, unparsed_fragments_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, session.id)
            statement.setString(2, session.title)
            statement.setString(3, databaseJson.encodeToString(session.config))
            statement.setString(4, session.contextManagement?.let(databaseJson::encodeToString))
            statement.setString(5, session.parentSessionId)
            statement.setObject(6, session.checkpointMessageCount)
            statement.setString(7, session.branchLabel)
            statement.setLong(8, session.revision)
            statement.setLong(9, session.createdAtEpochMs)
            statement.setLong(10, session.updatedAtEpochMs)
            statement.executeUpdate()
        }
        requireState(this, session.id)
    }

    override suspend fun list(): List<ImportSession> = database {
        prepareStatement(
            """
            SELECT id, title, config_json, context_management_json, parent_session_id,
                   checkpoint_message_count, branch_label, revision, created_at_epoch_ms,
                   updated_at_epoch_ms, draft_status
            FROM import_sessions
            ORDER BY updated_at_epoch_ms DESC, id ASC
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(rows.toSession())
                }
            }
        }
    }

    override suspend fun get(id: String): ImportSessionState? = database {
        readState(this, id)
    }

    override suspend fun saveExchange(
        sessionId: String,
        expectedRevision: Long,
        userMessage: ConversationMessage,
        assistantMessage: ConversationMessage,
        draft: ImportDraft,
        metric: ModelCallMetric,
        updatedAtEpochMs: Long,
        facts: List<StickyFact>?,
        additionalMetrics: List<ModelCallMetric>,
    ): ImportSessionState = transaction {
        checkRevision(this, sessionId, expectedRevision)
        require(draft.version == expectedRevision + 1) {
            "Версия черновика должна совпадать со следующей ревизией сессии."
        }
        val nextSequence = nextMessageSequence(this, sessionId)
        insertMessage(this, sessionId, nextSequence, userMessage)
        insertMessage(this, sessionId, nextSequence + 1, assistantMessage)
        additionalMetrics.forEach { insertMetric(this, sessionId, it) }
        insertMetric(this, sessionId, metric)
        if (facts != null) replaceFacts(this, sessionId, facts)
        replaceDraft(this, sessionId, expectedRevision, draft, updatedAtEpochMs)
        requireState(this, sessionId)
    }
    override suspend fun saveSummaryBatch(
        sessionId: String,
        expectedRevision: Long,
        updates: List<ConversationSummaryUpdate>,
    ): ImportSessionState = transaction {
        checkRevision(this, sessionId, expectedRevision)
        require(updates.isNotEmpty()) { "Summary batch не должен быть пустым." }
        val messageCount = countMessages(this, sessionId)
        var previousCount = currentSummaryMessageCount(this, sessionId)
        updates.forEach { update ->
            val summary = update.summary
            require(summary.text.isNotBlank()) { "Summary не должен быть пустым." }
            require(summary.summarizedMessageCount > previousCount) {
                "Граница summary должна двигаться вперёд."
            }
            require(summary.summarizedMessageCount <= messageCount) {
                "Граница summary выходит за пределы архива сообщений."
            }
            require(update.metric.callType == ModelCallType.SUMMARY) {
                "Metric summary должна иметь call_type=SUMMARY."
            }
            upsertSummary(this, sessionId, summary)
            insertMetric(this, sessionId, update.metric)
            previousCount = summary.summarizedMessageCount
        }
        requireState(this, sessionId)
    }

    override suspend fun saveCallMetric(
        sessionId: String,
        expectedRevision: Long,
        metric: ModelCallMetric,
    ): ImportSessionState = transaction {
        checkRevision(this, sessionId, expectedRevision)
        insertMetric(this, sessionId, metric)
        requireState(this, sessionId)
    }

    override suspend fun saveDraft(
        sessionId: String,
        expectedRevision: Long,
        draft: ImportDraft,
        updatedAtEpochMs: Long,
    ): ImportSessionState = transaction {
        checkRevision(this, sessionId, expectedRevision)
        require(draft.version == expectedRevision + 1) {
            "Версия черновика должна совпадать со следующей ревизией сессии."
        }
        replaceDraft(this, sessionId, expectedRevision, draft, updatedAtEpochMs)
        requireState(this, sessionId)
    }

    override suspend fun saveConfig(
        sessionId: String,
        expectedRevision: Long,
        config: AgentConfig,
        updatedAtEpochMs: Long,
    ): ImportSessionState = transaction {
        checkRevision(this, sessionId, expectedRevision)
        prepareStatement(
            """
            UPDATE import_sessions
            SET config_json = ?, revision = revision + 1, updated_at_epoch_ms = ?
            WHERE id = ? AND revision = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, databaseJson.encodeToString(config))
            statement.setLong(2, updatedAtEpochMs)
            statement.setString(3, sessionId)
            statement.setLong(4, expectedRevision)
            requireSingleUpdate(statement.executeUpdate(), sessionId, expectedRevision, this)
        }
        requireState(this, sessionId)
    }

    override suspend fun delete(sessionId: String, expectedRevision: Long): Unit = transaction {
        checkRevision(this, sessionId, expectedRevision)
        prepareStatement("DELETE FROM import_sessions WHERE id = ? AND revision = ?").use { statement ->
            statement.setString(1, sessionId)
            statement.setLong(2, expectedRevision)
            requireSingleUpdate(statement.executeUpdate(), sessionId, expectedRevision, this)
        }
    }

    override suspend fun fork(
        sessionId: String,
        expectedRevision: Long,
        forkSession: ImportSession,
    ): ImportSessionState = transaction {
        checkRevision(this, sessionId, expectedRevision)
        val source = requireState(this, sessionId)
        require(forkSession.id != sessionId) { "Идентификатор ветки должен отличаться от источника." }
        require(forkSession.revision == source.session.revision) {
            "Ревизия ветки должна совпадать с checkpoint источника."
        }
        require(forkSession.hasDraft == (source.draft != null)) {
            "Состояние draft ветки должно совпадать с checkpoint источника."
        }
        prepareStatement(
            """
            INSERT INTO import_sessions (
                id, title, config_json, context_management_json, parent_session_id,
                checkpoint_message_count, branch_label, revision, created_at_epoch_ms,
                updated_at_epoch_ms, draft_status, rejection_reason, unparsed_fragments_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, forkSession.id)
            statement.setString(2, forkSession.title)
            statement.setString(3, databaseJson.encodeToString(forkSession.config))
            statement.setString(4, forkSession.contextManagement?.let(databaseJson::encodeToString))
            statement.setString(5, forkSession.parentSessionId)
            statement.setObject(6, forkSession.checkpointMessageCount)
            statement.setString(7, forkSession.branchLabel)
            statement.setLong(8, forkSession.revision)
            statement.setLong(9, forkSession.createdAtEpochMs)
            statement.setLong(10, forkSession.updatedAtEpochMs)
            statement.setString(11, source.draft?.status?.name)
            statement.setString(12, source.draft?.rejectionReason)
            statement.setString(
                13,
                source.draft?.unparsedFragments?.let(databaseJson::encodeToString),
            )
            statement.executeUpdate()
        }
        prepareStatement(
            """
            INSERT INTO conversation_messages (
                session_id, sequence_number, id, role, content, display_text, created_at_epoch_ms
            )
            SELECT ?, sequence_number, id, role, content, display_text, created_at_epoch_ms
            FROM conversation_messages
            WHERE session_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, forkSession.id)
            statement.setString(2, sessionId)
            statement.executeUpdate()
        }
        prepareStatement(
            """
            INSERT INTO conversation_summaries (
                session_id, summary_text, summarized_message_count, updated_at_epoch_ms
            )
            SELECT ?, summary_text, summarized_message_count, updated_at_epoch_ms
            FROM conversation_summaries
            WHERE session_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, forkSession.id)
            statement.setString(2, sessionId)
            statement.executeUpdate()
        }
        prepareStatement(
            """
            INSERT INTO conversation_facts (
                session_id, fact_key, fact_value, source_message_id, updated_at_epoch_ms
            )
            SELECT ?, fact_key, fact_value, source_message_id, updated_at_epoch_ms
            FROM conversation_facts
            WHERE session_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, forkSession.id)
            statement.setString(2, sessionId)
            statement.executeUpdate()
        }
        prepareStatement(
            """
            INSERT INTO model_call_metrics (
                session_id, sequence_number, id, provider_id, model_id, call_type, inherited,
                status, prompt_tokens, completion_tokens, total_tokens,
                context_window_tokens, estimated_context_tokens, compaction_threshold_tokens,
                compaction_reserve_tokens, context_estimate_source, created_at_epoch_ms
            )
            SELECT ?, sequence_number, id, provider_id, model_id, call_type, 1,
                   status, prompt_tokens, completion_tokens, total_tokens,
                   context_window_tokens, estimated_context_tokens, compaction_threshold_tokens,
                   compaction_reserve_tokens, context_estimate_source, created_at_epoch_ms
            FROM model_call_metrics
            WHERE session_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, forkSession.id)
            statement.setString(2, sessionId)
            statement.executeUpdate()
        }
        prepareStatement(
            """
            INSERT INTO draft_transactions (
                session_id, position, transaction_id, included, description,
                field_errors_json, transaction_json
            )
            SELECT ?, position, transaction_id, included, description,
                   field_errors_json, transaction_json
            FROM draft_transactions
            WHERE session_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, forkSession.id)
            statement.setString(2, sessionId)
            statement.executeUpdate()
        }
        requireState(this, forkSession.id)
    }

    override suspend fun getOrCreatePreferences(defaultUserPrompt: String): UserPreferences = transaction {
        prepareStatement(
            "INSERT OR IGNORE INTO user_preferences (singleton_id, user_prompt) VALUES (1, ?)",
        ).use { statement ->
            statement.setString(1, defaultUserPrompt)
            statement.executeUpdate()
        }
        readPreferences(this)
    }

    override suspend fun savePreferences(preferences: UserPreferences): UserPreferences = transaction {
        prepareStatement(
            """
            INSERT INTO user_preferences (singleton_id, user_prompt)
            VALUES (1, ?)
            ON CONFLICT(singleton_id) DO UPDATE SET user_prompt = excluded.user_prompt
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, preferences.userPrompt)
            statement.executeUpdate()
        }
        readPreferences(this)
    }

    private fun initializeSchema(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode=WAL")
            statement.execute("PRAGMA foreign_keys=ON")
            statement.execute("PRAGMA busy_timeout=5000")
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS import_sessions (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    config_json TEXT NOT NULL,
                    context_management_json TEXT,
                    parent_session_id TEXT,
                    checkpoint_message_count INTEGER,
                    branch_label TEXT,
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
                CREATE TABLE IF NOT EXISTS conversation_messages (
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
                CREATE TABLE IF NOT EXISTS conversation_summaries (
                    session_id TEXT PRIMARY KEY,
                    summary_text TEXT NOT NULL,
                    summarized_message_count INTEGER NOT NULL,
                    updated_at_epoch_ms INTEGER NOT NULL,
                    FOREIGN KEY (session_id) REFERENCES import_sessions(id) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS conversation_facts (
                    session_id TEXT NOT NULL,
                    fact_key TEXT NOT NULL,
                    fact_value TEXT NOT NULL,
                    source_message_id TEXT NOT NULL,
                    updated_at_epoch_ms INTEGER NOT NULL,
                    PRIMARY KEY (session_id, fact_key),
                    FOREIGN KEY (session_id) REFERENCES import_sessions(id) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS model_call_metrics (
                    session_id TEXT NOT NULL,
                    sequence_number INTEGER NOT NULL,
                    id TEXT NOT NULL,
                    provider_id TEXT NOT NULL,
                    model_id TEXT NOT NULL,
                    call_type TEXT NOT NULL DEFAULT 'NORMAL',
                    inherited INTEGER NOT NULL DEFAULT 0,
                    status TEXT NOT NULL,
                    prompt_tokens INTEGER,
                    completion_tokens INTEGER,
                    total_tokens INTEGER,
                    context_window_tokens INTEGER,
                    estimated_context_tokens INTEGER,
                    compaction_threshold_tokens INTEGER,
                    compaction_reserve_tokens INTEGER,
                    context_estimate_source TEXT,
                    created_at_epoch_ms INTEGER NOT NULL,
                    PRIMARY KEY (session_id, sequence_number),
                    UNIQUE (session_id, id),
                    FOREIGN KEY (session_id) REFERENCES import_sessions(id) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS draft_transactions (
                    session_id TEXT NOT NULL,
                    position INTEGER NOT NULL,
                    transaction_id TEXT NOT NULL,
                    included INTEGER NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    field_errors_json TEXT NOT NULL DEFAULT '{}',
                    transaction_json TEXT NOT NULL,
                    PRIMARY KEY (session_id, transaction_id),
                    UNIQUE (session_id, position),
                    FOREIGN KEY (session_id) REFERENCES import_sessions(id) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS user_preferences (
                    singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1),
                    user_prompt TEXT NOT NULL
                )
                """.trimIndent(),
            )
        }
        ensureColumn(
            connection,
            table = "draft_transactions",
            column = "description",
            definition = "description TEXT NOT NULL DEFAULT ''",
        )
        ensureColumn(
            connection,
            table = "draft_transactions",
            column = "field_errors_json",
            definition = "field_errors_json TEXT NOT NULL DEFAULT '{}'",
        )
        ensureColumn(
            connection,
            table = "model_call_metrics",
            column = "call_type",
            definition = "call_type TEXT NOT NULL DEFAULT 'NORMAL'",
        )
        ensureColumn(
            connection,
            table = "model_call_metrics",
            column = "inherited",
            definition = "inherited INTEGER NOT NULL DEFAULT 0",
        )
        ensureColumn(
            connection,
            table = "model_call_metrics",
            column = "estimated_context_tokens",
            definition = "estimated_context_tokens INTEGER",
        )
        ensureColumn(
            connection,
            table = "model_call_metrics",
            column = "compaction_threshold_tokens",
            definition = "compaction_threshold_tokens INTEGER",
        )
        ensureColumn(
            connection,
            table = "model_call_metrics",
            column = "compaction_reserve_tokens",
            definition = "compaction_reserve_tokens INTEGER",
        )
        ensureColumn(
            connection,
            table = "model_call_metrics",
            column = "context_estimate_source",
            definition = "context_estimate_source TEXT",
        )
        ensureColumn(
            connection,
            table = "import_sessions",
            column = "context_management_json",
            definition = "context_management_json TEXT",
        )
        ensureColumn(
            connection,
            table = "import_sessions",
            column = "parent_session_id",
            definition = "parent_session_id TEXT",
        )
        ensureColumn(
            connection,
            table = "import_sessions",
            column = "checkpoint_message_count",
            definition = "checkpoint_message_count INTEGER",
        )
        ensureColumn(
            connection,
            table = "import_sessions",
            column = "branch_label",
            definition = "branch_label TEXT",
        )
        migrateTransactionIds(connection)
    }

    private fun readState(connection: Connection, id: String): ImportSessionState? {
        val header = connection.prepareStatement(
            """
            SELECT id, title, config_json, context_management_json, parent_session_id,
                   checkpoint_message_count, branch_label, revision, created_at_epoch_ms,
                   updated_at_epoch_ms, draft_status, rejection_reason, unparsed_fragments_json
            FROM import_sessions
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows ->
                if (rows.next()) SessionHeader(
                    session = rows.toSession(),
                    draftStatus = rows.getString("draft_status"),
                    rejectionReason = rows.getString("rejection_reason"),
                    unparsedFragmentsJson = rows.getString("unparsed_fragments_json"),
                ) else null
            }
        } ?: return null

        val messages = connection.prepareStatement(
            """
            SELECT id, role, content, display_text, created_at_epoch_ms
            FROM conversation_messages
            WHERE session_id = ?
            ORDER BY sequence_number ASC
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            ConversationMessage(
                                id = rows.getString("id"),
                                role = ConversationRole.valueOf(rows.getString("role")),
                                content = rows.getString("content"),
                                displayText = rows.getString("display_text"),
                                createdAtEpochMs = rows.getLong("created_at_epoch_ms"),
                            ),
                        )
                    }
                }
            }
        }
        val summary = connection.prepareStatement(
            """
            SELECT summary_text, summarized_message_count, updated_at_epoch_ms
            FROM conversation_summaries
            WHERE session_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows ->
                if (rows.next()) {
                    ConversationSummary(
                        text = rows.getString("summary_text"),
                        summarizedMessageCount = rows.getInt("summarized_message_count"),
                        updatedAtEpochMs = rows.getLong("updated_at_epoch_ms"),
                    )
                } else {
                    null
                }
            }
        }
        val facts = connection.prepareStatement(
            """
            SELECT fact_key, fact_value, source_message_id, updated_at_epoch_ms
            FROM conversation_facts
            WHERE session_id = ?
            ORDER BY updated_at_epoch_ms ASC, fact_key ASC
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            StickyFact(
                                key = rows.getString("fact_key"),
                                value = rows.getString("fact_value"),
                                sourceMessageId = rows.getString("source_message_id"),
                                updatedAtEpochMs = rows.getLong("updated_at_epoch_ms"),
                            ),
                        )
                    }
                }
            }
        }
        val metrics = connection.prepareStatement(
            """
            SELECT id, provider_id, model_id, call_type, inherited, status, prompt_tokens, completion_tokens,
                   total_tokens, context_window_tokens, estimated_context_tokens,
                   compaction_threshold_tokens, compaction_reserve_tokens, context_estimate_source,
                   created_at_epoch_ms
            FROM model_call_metrics
            WHERE session_id = ?
            ORDER BY sequence_number ASC
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            ModelCallMetric(
                                id = rows.getString("id"),
                                providerId = rows.getString("provider_id"),
                                modelId = rows.getString("model_id"),
                                callType = ModelCallType.valueOf(rows.getString("call_type")),
                                inherited = rows.getInt("inherited") != 0,
                                status = ModelCallStatus.valueOf(rows.getString("status")),
                                promptTokens = rows.getIntOrNull("prompt_tokens"),
                                completionTokens = rows.getIntOrNull("completion_tokens"),
                                totalTokens = rows.getIntOrNull("total_tokens"),
                                contextWindowTokens = rows.getIntOrNull("context_window_tokens"),
                                estimatedContextTokens = rows.getIntOrNull("estimated_context_tokens"),
                                compactionThresholdTokens = rows.getIntOrNull("compaction_threshold_tokens"),
                                compactionReserveTokens = rows.getIntOrNull("compaction_reserve_tokens"),
                                contextEstimateSource = rows.getString("context_estimate_source"),
                                createdAtEpochMs = rows.getLong("created_at_epoch_ms"),
                            ),
                        )
                    }
                }
            }
        }
        val draft = header.draftStatus?.let { status ->
            val transactions = connection.prepareStatement(
                """
                SELECT transaction_id, included, description, field_errors_json, transaction_json
                FROM draft_transactions
                WHERE session_id = ?
                ORDER BY position ASC
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            val transaction = databaseJson.decodeFromString<StructuredTransaction>(
                                rows.getString("transaction_json"),
                            )
                            add(
                                DraftTransaction(
                                    id = transaction.sourceIndex.toString(),
                                    included = rows.getInt("included") != 0,
                                    description = rows.getString("description"),
                                    fieldErrors = databaseJson.decodeFromString(
                                        rows.getString("field_errors_json"),
                                    ),
                                    transaction = transaction,
                                ),
                            )
                        }
                    }
                }
            }
            ImportDraft(
                status = ImportStatus.valueOf(status),
                rejectionReason = header.rejectionReason,
                transactions = transactions,
                unparsedFragments = databaseJson.decodeFromString(
                    requireNotNull(header.unparsedFragmentsJson) {
                        "У сохранённого черновика отсутствуют unparsed_fragments."
                    },
                ),
                version = header.session.revision,
            )
        }
        return ImportSessionState(
            session = header.session,
            messages = messages,
            draft = draft,
            facts = facts,
            metrics = metrics,
            summary = summary,
        )
    }

    private fun ensureColumn(
        connection: Connection,
        table: String,
        column: String,
        definition: String,
    ) {
        val exists = connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info($table)").use { rows ->
                var found = false
                while (rows.next()) {
                    if (rows.getString("name") == column) found = true
                }
                found
            }
        }
        if (!exists) {
            connection.createStatement().use { it.execute("ALTER TABLE $table ADD COLUMN $definition") }
        }
    }

    private fun migrateTransactionIds(connection: Connection) {
        val migrations = connection.prepareStatement(
            """
            SELECT session_id, transaction_id, transaction_json
            FROM draft_transactions
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val sessionId = rows.getString("session_id")
                        val storedId = rows.getString("transaction_id")
                        val transaction = databaseJson.decodeFromString<StructuredTransaction>(
                            rows.getString("transaction_json"),
                        )
                        val canonicalId = transaction.sourceIndex.toString()
                        if (storedId != canonicalId) {
                            add(TransactionIdMigration(sessionId, storedId, canonicalId))
                        }
                    }
                }
            }
        }
        if (migrations.isEmpty()) return

        connection.autoCommit = false
        try {
            connection.prepareStatement(
                "UPDATE draft_transactions SET transaction_id = ? WHERE session_id = ? AND transaction_id = ?",
            ).use { statement ->
                migrations.forEachIndexed { index, migration ->
                    statement.setString(1, temporaryTransactionId(index))
                    statement.setString(2, migration.sessionId)
                    statement.setString(3, migration.storedId)
                    require(statement.executeUpdate() == 1)
                }
            }
            connection.prepareStatement(
                "UPDATE draft_transactions SET transaction_id = ? WHERE session_id = ? AND transaction_id = ?",
            ).use { statement ->
                migrations.forEachIndexed { index, migration ->
                    statement.setString(1, migration.canonicalId)
                    statement.setString(2, migration.sessionId)
                    statement.setString(3, temporaryTransactionId(index))
                    require(statement.executeUpdate() == 1)
                }
            }
            connection.commit()
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    private fun temporaryTransactionId(index: Int): String = "__transaction-id-migration-$index"

    private suspend fun <T> database(block: Connection.() -> T): T = withContext(Dispatchers.IO) {
        openConnection().use(block)
    }

    private suspend fun <T> transaction(block: Connection.() -> T): T = withContext(Dispatchers.IO) {
        openConnection().use { connection ->
            connection.autoCommit = false
            try {
                connection.block().also { connection.commit() }
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            }
        }
    }

    private fun openConnection(): Connection = DriverManager.getConnection(jdbcUrl).also { connection ->
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys=ON")
            statement.execute("PRAGMA busy_timeout=5000")
        }
    }


    private fun ResultSet.toSession(): ImportSession = ImportSession(
        id = getString("id"),
        title = getString("title"),
        config = databaseJson.decodeFromString<AgentConfig>(getString("config_json")),
        contextManagement = getString("context_management_json")
            ?.let(databaseJson::decodeFromString)
            ?: defaultContextManagement,
        parentSessionId = getString("parent_session_id"),
        checkpointMessageCount = getIntOrNull("checkpoint_message_count"),
        branchLabel = getString("branch_label"),
        revision = getLong("revision"),
        createdAtEpochMs = getLong("created_at_epoch_ms"),
        updatedAtEpochMs = getLong("updated_at_epoch_ms"),
        hasDraft = getString("draft_status") != null,
    )

    private fun requireState(connection: Connection, id: String): ImportSessionState =
        readState(connection, id) ?: throw SessionNotFoundException(id)

    private fun checkRevision(connection: Connection, id: String, expected: Long) {
        val actual = currentRevision(connection, id) ?: throw SessionNotFoundException(id)
        if (actual != expected) throw RevisionConflictException(expected, actual)
    }

    private fun currentRevision(connection: Connection, id: String): Long? =
        connection.prepareStatement("SELECT revision FROM import_sessions WHERE id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
        }

    private fun requireSingleUpdate(
        updatedRows: Int,
        sessionId: String,
        expectedRevision: Long,
        connection: Connection,
    ) {
        if (updatedRows == 1) return
        val actual = currentRevision(connection, sessionId) ?: throw SessionNotFoundException(sessionId)
        throw RevisionConflictException(expectedRevision, actual)
    }

    private fun nextMessageSequence(connection: Connection, sessionId: String): Int =
        connection.prepareStatement(
            "SELECT COALESCE(MAX(sequence_number), 0) + 1 FROM conversation_messages WHERE session_id = ?",
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getInt(1)
            }
        }

    private fun insertMessage(
        connection: Connection,
        sessionId: String,
        sequence: Int,
        message: ConversationMessage,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO conversation_messages (
                session_id, sequence_number, id, role, content, display_text, created_at_epoch_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.setInt(2, sequence)
            statement.setString(3, message.id)
            statement.setString(4, message.role.name)
            statement.setString(5, message.content)
            statement.setString(6, message.displayText)
            statement.setLong(7, message.createdAtEpochMs)
            statement.executeUpdate()
        }
    }

    private fun insertMetric(
        connection: Connection,
        sessionId: String,
        metric: ModelCallMetric,
    ) {
        val sequence = nextMetricSequence(connection, sessionId)
        connection.prepareStatement(
            """
            INSERT INTO model_call_metrics (
                session_id, sequence_number, id, provider_id, model_id, call_type, inherited, status,
                prompt_tokens, completion_tokens, total_tokens,
                context_window_tokens, estimated_context_tokens, compaction_threshold_tokens,
                compaction_reserve_tokens, context_estimate_source, created_at_epoch_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.setInt(2, sequence)
            statement.setString(3, metric.id)
            statement.setString(4, metric.providerId)
            statement.setString(5, metric.modelId)
            statement.setString(6, metric.callType.name)
            statement.setInt(7, if (metric.inherited) 1 else 0)
            statement.setString(8, metric.status.name)
            statement.setNullableInt(9, metric.promptTokens)
            statement.setNullableInt(10, metric.completionTokens)
            statement.setNullableInt(11, metric.totalTokens)
            statement.setNullableInt(12, metric.contextWindowTokens)
            statement.setNullableInt(13, metric.estimatedContextTokens)
            statement.setNullableInt(14, metric.compactionThresholdTokens)
            statement.setNullableInt(15, metric.compactionReserveTokens)
            statement.setString(16, metric.contextEstimateSource)
            statement.setLong(17, metric.createdAtEpochMs)
            statement.executeUpdate()
        }
    }
    private fun currentSummaryMessageCount(connection: Connection, sessionId: String): Int =
        connection.prepareStatement(
            "SELECT COALESCE(MAX(summarized_message_count), 0) FROM conversation_summaries WHERE session_id = ?",
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getInt(1)
            }
        }

    private fun upsertSummary(
        connection: Connection,
        sessionId: String,
        summary: ConversationSummary,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO conversation_summaries (
                session_id, summary_text, summarized_message_count, updated_at_epoch_ms
            ) VALUES (?, ?, ?, ?)
            ON CONFLICT(session_id) DO UPDATE SET
                summary_text = excluded.summary_text,
                summarized_message_count = excluded.summarized_message_count,
                updated_at_epoch_ms = excluded.updated_at_epoch_ms
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.setString(2, summary.text)
            statement.setInt(3, summary.summarizedMessageCount)
            statement.setLong(4, summary.updatedAtEpochMs)
            statement.executeUpdate()
        }
    }

    private fun countMessages(connection: Connection, sessionId: String): Int =
        connection.prepareStatement(
            "SELECT COUNT(*) FROM conversation_messages WHERE session_id = ?",
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getInt(1)
            }
        }


    private fun nextMetricSequence(connection: Connection, sessionId: String): Int =
        connection.prepareStatement(
            "SELECT COALESCE(MAX(sequence_number), 0) + 1 FROM model_call_metrics WHERE session_id = ?",
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getInt(1)
            }
        }

    private fun ResultSet.getIntOrNull(column: String): Int? =
        getObject(column)?.let { getInt(column) }

    private fun java.sql.PreparedStatement.setNullableInt(index: Int, value: Int?) {
        if (value == null) setNull(index, java.sql.Types.INTEGER) else setInt(index, value)
    }

    private fun replaceFacts(
        connection: Connection,
        sessionId: String,
        facts: List<StickyFact>,
    ) {
        connection.prepareStatement("DELETE FROM conversation_facts WHERE session_id = ?").use { statement ->
            statement.setString(1, sessionId)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO conversation_facts (
                session_id, fact_key, fact_value, source_message_id, updated_at_epoch_ms
            ) VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            facts.forEach { fact ->
                statement.setString(1, sessionId)
                statement.setString(2, fact.key)
                statement.setString(3, fact.value)
                statement.setString(4, fact.sourceMessageId)
                statement.setLong(5, fact.updatedAtEpochMs)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun replaceDraft(
        connection: Connection,
        sessionId: String,

        expectedRevision: Long,
        draft: ImportDraft,
        updatedAtEpochMs: Long,
    ) {
        connection.prepareStatement("DELETE FROM draft_transactions WHERE session_id = ?").use { statement ->
            statement.setString(1, sessionId)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO draft_transactions (
                session_id, position, transaction_id, included, description,
                field_errors_json, transaction_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            draft.transactions.forEachIndexed { position, transaction ->
                statement.setString(1, sessionId)
                statement.setInt(2, position)
                statement.setString(3, transaction.id)
                statement.setInt(4, if (transaction.included) 1 else 0)
                statement.setString(5, transaction.description)
                statement.setString(6, databaseJson.encodeToString(transaction.fieldErrors))
                statement.setString(7, databaseJson.encodeToString(transaction.transaction))
                statement.addBatch()
            }
            statement.executeBatch()
        }
        connection.prepareStatement(
            """
            UPDATE import_sessions
            SET revision = revision + 1,
                updated_at_epoch_ms = ?,
                draft_status = ?,
                rejection_reason = ?,
                unparsed_fragments_json = ?
            WHERE id = ? AND revision = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, updatedAtEpochMs)
            statement.setString(2, draft.status.name)
            statement.setString(3, draft.rejectionReason)
            statement.setString(4, databaseJson.encodeToString(draft.unparsedFragments))
            statement.setString(5, sessionId)
            statement.setLong(6, expectedRevision)
            requireSingleUpdate(statement.executeUpdate(), sessionId, expectedRevision, connection)
        }
    }

    private fun readPreferences(connection: Connection): UserPreferences =
        connection.prepareStatement(
            "SELECT user_prompt FROM user_preferences WHERE singleton_id = 1",
        ).use { statement ->
            statement.executeQuery().use { rows ->
                check(rows.next()) { "Пользовательские настройки не созданы." }
                UserPreferences(rows.getString("user_prompt"))
            }
        }

    private data class TransactionIdMigration(
        val sessionId: String,
        val storedId: String,
        val canonicalId: String,
    )

    private data class SessionHeader(
        val session: ImportSession,
        val draftStatus: String?,
        val rejectionReason: String?,
        val unparsedFragmentsJson: String?,
    )

    private companion object {
        val databaseJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = true
        }
    }
}
