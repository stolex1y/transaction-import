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
import io.github.stolex1y.transactionimport.core.ConfirmedDecision
import io.github.stolex1y.transactionimport.core.MemoryCandidate
import io.github.stolex1y.transactionimport.core.MemoryCandidateStatus
import io.github.stolex1y.transactionimport.core.MemoryTrace
import io.github.stolex1y.transactionimport.core.RevisionConflictException
import io.github.stolex1y.transactionimport.core.SessionNotFoundException
import io.github.stolex1y.transactionimport.core.StickyFact
import io.github.stolex1y.transactionimport.core.StructuredTransaction
import io.github.stolex1y.transactionimport.core.DEFAULT_AGENT_CATEGORIES
import io.github.stolex1y.transactionimport.core.TransactionCategory
import io.github.stolex1y.transactionimport.core.UserPreferences
import io.github.stolex1y.transactionimport.core.InvariantCheckResult
import io.github.stolex1y.transactionimport.core.ReceiptState
import io.github.stolex1y.transactionimport.core.ReceiptStatus
import io.github.stolex1y.transactionimport.core.defaultReceiptState
import io.github.stolex1y.transactionimport.core.receiptStateFor
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
        writeReceiptState(this, session.id, defaultReceiptState())
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

    override suspend fun listCategories(includeArchived: Boolean): List<TransactionCategory> = database {
        prepareStatement(
            """
            SELECT id, display_name, type, parent_id, hint, archived
            FROM transaction_categories
            WHERE ? = 1 OR archived = 0
            ORDER BY archived ASC, display_name COLLATE NOCASE ASC, id ASC
            """.trimIndent(),
        ).use { statement ->
            statement.setInt(1, if (includeArchived) 1 else 0)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            TransactionCategory(
                                id = rows.getString("id"),
                                displayName = rows.getString("display_name"),
                                type = io.github.stolex1y.transactionimport.core.CategoryType.valueOf(
                                    rows.getString("type"),
                                ),
                                parentId = rows.getString("parent_id"),
                                hint = rows.getString("hint"),
                                archived = rows.getInt("archived") != 0,
                            ),
                        )
                    }
                }
            }
        }
    }

    override suspend fun insertCategory(category: TransactionCategory): TransactionCategory = transaction {
        prepareStatement(
            """
            INSERT INTO transaction_categories (id, display_name, type, parent_id, hint, archived)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, category.id)
            statement.setString(2, category.displayName)
            statement.setString(3, category.type.name)
            statement.setString(4, category.parentId)
            statement.setString(5, category.hint)
            statement.setInt(6, if (category.archived) 1 else 0)
            statement.executeUpdate()
        }
        category
    }

    override suspend fun updateCategory(category: TransactionCategory): TransactionCategory = transaction {
        prepareStatement(
            """
            UPDATE transaction_categories
            SET display_name = ?, parent_id = ?, hint = ?
            WHERE id = ? AND archived = 0
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, category.displayName)
            statement.setString(2, category.parentId)
            statement.setString(3, category.hint)
            statement.setString(4, category.id)
            require(statement.executeUpdate() == 1) {
                "Категория не найдена или уже архивирована: ${category.id}"
            }
        }
        category
    }

    override suspend fun archiveCategory(id: String): TransactionCategory = transaction {
        prepareStatement(
            "UPDATE transaction_categories SET archived = 1 WHERE id = ? AND archived = 0",
        ).use { statement ->
            statement.setString(1, id)
            require(statement.executeUpdate() == 1) {
                "Категория не найдена или уже архивирована: $id"
            }
        }
        readCategory(this, id) ?: error("Архивированная категория не найдена: $id")
    }

    override suspend fun isCategoryReferenced(id: String): Boolean = database {
        prepareStatement("SELECT transaction_json FROM draft_transactions").use { statement ->
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    val transaction = databaseJson.decodeFromString<StructuredTransaction>(
                        rows.getString("transaction_json"),
                    )
                    if (transaction.categoryId == id) return@use true
                }
                false
            }
        }
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
        memoryTrace: MemoryTrace?,
        memoryCandidates: List<MemoryCandidate>,
        receiptState: ReceiptState?,
    ): ImportSessionState = transaction {
        checkRevision(this, sessionId, expectedRevision)
        require(draft.version == expectedRevision + 1) {
            "Версия черновика должна совпадать со следующей ревизией сессии."
        }
        val nextSequence = nextMessageSequence(this, sessionId)
        insertMessage(this, sessionId, nextSequence, userMessage)
        insertMessage(this, sessionId, nextSequence + 1, assistantMessage)
        insertMemoryCandidates(this, sessionId, memoryCandidates)
        additionalMetrics.forEach { insertMetric(this, sessionId, it) }
        insertMetric(this, sessionId, metric)
        if (facts != null) replaceFacts(this, sessionId, facts)
        replaceDraft(
            connection = this,
            sessionId = sessionId,
            expectedRevision = expectedRevision,
            draft = draft,
            updatedAtEpochMs = updatedAtEpochMs,
            memoryTrace = memoryTrace,
            receiptState = receiptState,
        )
        requireState(this, sessionId)
    }
    override suspend fun acceptMemoryCandidate(
        sessionId: String,
        expectedRevision: Long,
        candidateId: String,
        decision: ConfirmedDecision,
        updatedAtEpochMs: Long,
    ): ImportSessionState = transaction {
        checkRevision(this, sessionId, expectedRevision)
        val candidate = readCandidate(this, sessionId, candidateId)
            ?: throw IllegalArgumentException("Кандидат решения не найден: $candidateId")
        require(candidate.status == MemoryCandidateStatus.PENDING) {
            "Кандидат решения уже был принят."
        }
        val preferences = readPreferences(this)
        writePreferences(
            this,
            preferences.copy(confirmedDecisions = preferences.confirmedDecisions + decision),
        )
        updateAcceptedCandidate(this, sessionId, candidateId, decision.id, updatedAtEpochMs)
        bumpSession(this, sessionId, expectedRevision, updatedAtEpochMs)
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
        receiptState: ReceiptState?,
    ): ImportSessionState = transaction {
        checkRevision(this, sessionId, expectedRevision)
        require(draft.version == expectedRevision + 1) {
            "Версия черновика должна совпадать со следующей ревизией сессии."
        }
        replaceDraft(
            connection = this,
            sessionId = sessionId,
            expectedRevision = expectedRevision,
            draft = draft,
            updatedAtEpochMs = updatedAtEpochMs,
            receiptState = receiptState,
        )
        requireState(this, sessionId)
    }


    override suspend fun saveInvariantCheck(
        sessionId: String,
        expectedRevision: Long,
        result: InvariantCheckResult,
        updatedAtEpochMs: Long,
    ): ImportSessionState = transaction {
        checkRevision(this, sessionId, expectedRevision)
        val current = requireState(this, sessionId)
        writeReceiptState(
            this,
            sessionId,
            receiptStateFor(current.draft, result),
        )
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
        writeReceiptState(
            this,
            forkSession.id,
            source.receiptState,
        )
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
            INSERT INTO memory_candidates (
                session_id, candidate_id, text, reason, source_message_id,
                created_at_epoch_ms, status, accepted_decision_id, accepted_at_epoch_ms
            )
            SELECT ?, candidate_id, text, reason, source_message_id,
                   created_at_epoch_ms, status, accepted_decision_id, accepted_at_epoch_ms
            FROM memory_candidates
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
        writePreferences(this, preferences)
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
                    unparsed_fragments_json TEXT,
                    memory_trace_json TEXT
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
                CREATE TABLE IF NOT EXISTS memory_candidates (
                    session_id TEXT NOT NULL,
                    candidate_id TEXT NOT NULL,
                    text TEXT NOT NULL,
                    reason TEXT NOT NULL,
                    source_message_id TEXT NOT NULL,
                    created_at_epoch_ms INTEGER NOT NULL,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    accepted_decision_id TEXT,
                    accepted_at_epoch_ms INTEGER,
                    PRIMARY KEY (session_id, candidate_id),
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
                    user_prompt TEXT NOT NULL,
                    confirmed_decisions_json TEXT NOT NULL DEFAULT '[]'
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS receipt_states (
                    session_id TEXT PRIMARY KEY,
                    status TEXT NOT NULL,
                    last_compliance_json TEXT,
                    FOREIGN KEY (session_id) REFERENCES import_sessions(id) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS transaction_categories (
                    id TEXT PRIMARY KEY,
                    display_name TEXT NOT NULL COLLATE NOCASE UNIQUE,
                    type TEXT NOT NULL CHECK (type IN ('INCOME', 'EXPENSE')),
                    parent_id TEXT,
                    hint TEXT NOT NULL DEFAULT '',
                    archived INTEGER NOT NULL DEFAULT 0 CHECK (archived IN (0, 1)),
                    FOREIGN KEY (parent_id) REFERENCES transaction_categories(id)
                )
                """.trimIndent(),
            )
            statement.execute(
                "CREATE INDEX IF NOT EXISTS transaction_categories_parent_idx " +
                    "ON transaction_categories(parent_id)",
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
            column = "memory_trace_json",
            definition = "memory_trace_json TEXT",
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
        ensureColumn(
            connection,
            table = "user_preferences",
            column = "confirmed_decisions_json",
            definition = "confirmed_decisions_json TEXT NOT NULL DEFAULT '[]'",
        )
        migrateRemovedSettings(connection)
        seedCategories(connection)
        migrateLegacyModelIds(connection)
        migrateTransactionIds(connection)
    }

    private fun migrateRemovedSettings(connection: Connection) {
        if (tableExists(connection, "user_profile")) {
            val profile = connection.prepareStatement(
                """
                SELECT name, addressing, style, output_format, constraints_text, context_text
                FROM user_profile
                WHERE singleton_id = 1
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    if (!rows.next()) {
                        null
                    } else {
                        listOf(
                            "Имя пользователя" to rows.getString("name"),
                            "Обращение" to rows.getString("addressing"),
                            "Стиль ответа" to rows.getString("style"),
                            "Формат результата" to rows.getString("output_format"),
                            "Ограничения" to rows.getString("constraints_text"),
                            "Контекст пользователя" to rows.getString("context_text"),
                        )
                    }
                }
            }
            if (profile != null) {
                val migrated = profile
                    .filter { (_, value) -> value.isNotBlank() }
                    .joinToString(
                        prefix = "Перенесённые настройки профиля:\n",
                        separator = "\n",
                    ) { (label, value) -> "$label: ${io.github.stolex1y.transactionimport.core.maskExplicitPhoneNumbers(value)}" }
                if (migrated != "Перенесённые настройки профиля:\n") {
                    connection.prepareStatement(
                        "INSERT OR IGNORE INTO user_preferences (singleton_id, user_prompt) VALUES (1, '')",
                    ).use { it.executeUpdate() }
                    val preferences = readPreferences(connection)
                    val combined = listOf(preferences.userPrompt, migrated)
                        .filter(String::isNotBlank)
                        .joinToString("\n\n")
                    writePreferences(connection, preferences.copy(userPrompt = combined))
                }
            }
            connection.createStatement().use { it.execute("DROP TABLE user_profile") }
        }
        connection.createStatement().use { it.execute("DROP TABLE IF EXISTS task_invariants") }
        migrateLegacyTaskState(connection)
    }

    private fun migrateLegacyTaskState(connection: Connection) {
        if (!tableExists(connection, "task_states")) return
        connection.prepareStatement(
            """
            INSERT OR IGNORE INTO receipt_states (session_id, status, last_compliance_json)
            SELECT legacy.session_id,
                   CASE WHEN sessions.draft_status IS NULL THEN 'NOT_STARTED' ELSE 'HAS_ERRORS' END,
                   legacy.last_compliance_json
            FROM task_states legacy
            JOIN import_sessions sessions ON sessions.id = legacy.session_id
            """.trimIndent(),
        ).use { it.executeUpdate() }
        connection.createStatement().use { it.execute("DROP TABLE task_states") }
    }

    private fun tableExists(connection: Connection, table: String): Boolean =
        connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
        ).use { statement ->
            statement.setString(1, table)
            statement.executeQuery().use { it.next() }
        }

    private fun columnExists(connection: Connection, table: String, column: String): Boolean =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info($table)").use { rows ->
                generateSequence { if (rows.next()) rows.getString("name") else null }
                    .any { it == column }
            }
        }

    private fun seedCategories(connection: Connection) {
        connection.prepareStatement(
            """
            INSERT OR IGNORE INTO transaction_categories (
                id, display_name, type, parent_id, hint, archived
            ) VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            DEFAULT_AGENT_CATEGORIES.forEach { category ->
                statement.setString(1, category.id)
                statement.setString(2, category.displayName)
                statement.setString(3, category.type.name)
                statement.setString(4, category.parentId)
                statement.setString(5, category.hint)
                statement.setInt(6, if (category.archived) 1 else 0)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun migrateLegacyModelIds(connection: Connection) {
        val migrations = connection.prepareStatement(
            "SELECT id, config_json FROM import_sessions",
        ).use { statement ->
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val config = databaseJson.decodeFromString<AgentConfig>(
                            rows.getString("config_json"),
                        )
                        if (config.modelId == "deepseek-v4-flash") {
                            add(rows.getString("id"))
                        }
                    }
                }
            }
        }
        if (migrations.isEmpty()) return
        connection.prepareStatement(
            "UPDATE import_sessions SET config_json = ? WHERE id = ?",
        ).use { statement ->
            migrations.forEach { sessionId ->
                val config = connection.prepareStatement(
                    "SELECT config_json FROM import_sessions WHERE id = ?",
                ).use { readStatement ->
                    readStatement.setString(1, sessionId)
                    readStatement.executeQuery().use { rows ->
                        check(rows.next())
                        databaseJson.decodeFromString<AgentConfig>(rows.getString("config_json"))
                    }
                }
                statement.setString(
                    1,
                    databaseJson.encodeToString(config.copy(modelId = "deepseek-flash")),
                )
                statement.setString(2, sessionId)
                require(statement.executeUpdate() == 1)
            }
        }
    }

    private fun readState(connection: Connection, id: String): ImportSessionState? {
        val header = connection.prepareStatement(
            """
            SELECT id, title, config_json, context_management_json, parent_session_id,
                   checkpoint_message_count, branch_label, revision, created_at_epoch_ms,
                   updated_at_epoch_ms, draft_status, rejection_reason, unparsed_fragments_json,
                   memory_trace_json
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
                    memoryTraceJson = rows.getString("memory_trace_json"),
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
        val memoryCandidates = connection.prepareStatement(
            """
            SELECT candidate_id, text, reason, source_message_id, created_at_epoch_ms,
                   status, accepted_decision_id, accepted_at_epoch_ms
            FROM memory_candidates
            WHERE session_id = ?
            ORDER BY created_at_epoch_ms ASC, candidate_id ASC
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            MemoryCandidate(
                                id = rows.getString("candidate_id"),
                                text = rows.getString("text"),
                                reason = rows.getString("reason"),
                                sourceMessageId = rows.getString("source_message_id"),
                                createdAtEpochMs = rows.getLong("created_at_epoch_ms"),
                                status = MemoryCandidateStatus.valueOf(rows.getString("status")),
                                acceptedDecisionId = rows.getString("accepted_decision_id"),
                                acceptedAtEpochMs = rows.getLongOrNull("accepted_at_epoch_ms"),
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
        val storedReceiptState = readReceiptState(connection, id)
        val receiptState = receiptStateFor(draft, storedReceiptState.lastCompliance)
        return ImportSessionState(
            session = header.session,
            messages = messages,
            draft = draft,
            facts = facts,
            metrics = metrics,
            summary = summary,
            memoryTrace = header.memoryTraceJson?.let(databaseJson::decodeFromString),
            memoryCandidates = memoryCandidates,
            receiptState = receiptState,
        )
    }
    private fun readReceiptState(connection: Connection, sessionId: String): ReceiptState =
        connection.prepareStatement(
            """
            SELECT status, last_compliance_json
            FROM receipt_states
            WHERE session_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) {
                    defaultReceiptState()
                } else {
                    ReceiptState(
                        status = ReceiptStatus.valueOf(rows.getString("status")),
                        lastCompliance = rows.getString("last_compliance_json")
                            ?.let(databaseJson::decodeFromString),
                    )
                }
            }
        }

    private fun writeReceiptState(
        connection: Connection,
        sessionId: String,
        receiptState: ReceiptState,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO receipt_states (
                session_id, status, last_compliance_json
            ) VALUES (?, ?, ?)
            ON CONFLICT(session_id) DO UPDATE SET
                status = excluded.status,
                last_compliance_json = excluded.last_compliance_json
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.setString(2, receiptState.status.name)
            statement.setString(
                3,
                receiptState.lastCompliance?.let(databaseJson::encodeToString),
            )
            statement.executeUpdate()
        }
    }





    private fun readCategory(connection: Connection, id: String): TransactionCategory? =
        connection.prepareStatement(
            """
            SELECT id, display_name, type, parent_id, hint, archived
            FROM transaction_categories
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows ->
                if (!rows.next()) {
                    null
                } else {
                    TransactionCategory(
                        id = rows.getString("id"),
                        displayName = rows.getString("display_name"),
                        type = io.github.stolex1y.transactionimport.core.CategoryType.valueOf(rows.getString("type")),
                        parentId = rows.getString("parent_id"),
                        hint = rows.getString("hint"),
                        archived = rows.getInt("archived") != 0,
                    )
                }
            }
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

    private fun bumpSession(
        connection: Connection,
        sessionId: String,
        expectedRevision: Long,
        updatedAtEpochMs: Long,
    ) {
        connection.prepareStatement(
            """
            UPDATE import_sessions
            SET revision = revision + 1, updated_at_epoch_ms = ?
            WHERE id = ? AND revision = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, updatedAtEpochMs)
            statement.setString(2, sessionId)
            statement.setLong(3, expectedRevision)
            requireSingleUpdate(statement.executeUpdate(), sessionId, expectedRevision, connection)
        }
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
    private fun insertMemoryCandidates(
        connection: Connection,
        sessionId: String,
        candidates: List<MemoryCandidate>,
    ) {
        if (candidates.isEmpty()) return
        connection.prepareStatement(
            """
            INSERT INTO memory_candidates (
                session_id, candidate_id, text, reason, source_message_id,
                created_at_epoch_ms, status, accepted_decision_id, accepted_at_epoch_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            candidates.forEach { candidate ->
                statement.setString(1, sessionId)
                statement.setString(2, candidate.id)
                statement.setString(3, candidate.text)
                statement.setString(4, candidate.reason)
                statement.setString(5, candidate.sourceMessageId)
                statement.setLong(6, candidate.createdAtEpochMs)
                statement.setString(7, candidate.status.name)
                statement.setString(8, candidate.acceptedDecisionId)
                val acceptedAtEpochMs = candidate.acceptedAtEpochMs
                if (acceptedAtEpochMs == null) {
                    statement.setNull(9, java.sql.Types.INTEGER)
                } else {
                    statement.setLong(9, acceptedAtEpochMs)
                }
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun readCandidate(
        connection: Connection,
        sessionId: String,
        candidateId: String,
    ): MemoryCandidate? = connection.prepareStatement(
        """
        SELECT candidate_id, text, reason, source_message_id, created_at_epoch_ms,
               status, accepted_decision_id, accepted_at_epoch_ms
        FROM memory_candidates
        WHERE session_id = ? AND candidate_id = ?
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, sessionId)
        statement.setString(2, candidateId)
        statement.executeQuery().use { rows ->
            if (!rows.next()) {
                null
            } else {
                MemoryCandidate(
                    id = rows.getString("candidate_id"),
                    text = rows.getString("text"),
                    reason = rows.getString("reason"),
                    sourceMessageId = rows.getString("source_message_id"),
                    createdAtEpochMs = rows.getLong("created_at_epoch_ms"),
                    status = MemoryCandidateStatus.valueOf(rows.getString("status")),
                    acceptedDecisionId = rows.getString("accepted_decision_id"),
                    acceptedAtEpochMs = rows.getLongOrNull("accepted_at_epoch_ms"),
                )
            }
        }
    }

    private fun updateAcceptedCandidate(
        connection: Connection,
        sessionId: String,
        candidateId: String,
        decisionId: String,
        acceptedAtEpochMs: Long,
    ) {
        connection.prepareStatement(
            """
            UPDATE memory_candidates
            SET status = ?, accepted_decision_id = ?, accepted_at_epoch_ms = ?
            WHERE session_id = ? AND candidate_id = ? AND status = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, MemoryCandidateStatus.ACCEPTED.name)
            statement.setString(2, decisionId)
            statement.setLong(3, acceptedAtEpochMs)
            statement.setString(4, sessionId)
            statement.setString(5, candidateId)
            statement.setString(6, MemoryCandidateStatus.PENDING.name)
            require(statement.executeUpdate() == 1) {
                "Кандидат решения уже был принят или не найден."
            }
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

    private fun ResultSet.getLongOrNull(column: String): Long? =
        getObject(column)?.let { getLong(column) }

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
        memoryTrace: MemoryTrace? = null,
        receiptState: ReceiptState? = null,
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
                unparsed_fragments_json = ?,
                memory_trace_json = COALESCE(?, memory_trace_json)
            WHERE id = ? AND revision = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, updatedAtEpochMs)
            statement.setString(2, draft.status.name)
            statement.setString(3, draft.rejectionReason)
            statement.setString(4, databaseJson.encodeToString(draft.unparsedFragments))
            statement.setString(5, memoryTrace?.let(databaseJson::encodeToString))
            statement.setString(6, sessionId)
            statement.setLong(7, expectedRevision)
            requireSingleUpdate(statement.executeUpdate(), sessionId, expectedRevision, connection)
        }
        receiptState?.let { writeReceiptState(connection, sessionId, it) }
    }

    private fun writePreferences(connection: Connection, preferences: UserPreferences) {
        connection.prepareStatement(
            """
            INSERT INTO user_preferences (singleton_id, user_prompt, confirmed_decisions_json)
            VALUES (1, ?, ?)
            ON CONFLICT(singleton_id) DO UPDATE SET
                user_prompt = excluded.user_prompt,
                confirmed_decisions_json = excluded.confirmed_decisions_json
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, preferences.userPrompt)
            statement.setString(2, databaseJson.encodeToString(preferences.confirmedDecisions))
            statement.executeUpdate()
        }
    }

    private fun readPreferences(connection: Connection): UserPreferences =
        connection.prepareStatement(
            "SELECT user_prompt, confirmed_decisions_json FROM user_preferences WHERE singleton_id = 1",
        ).use { statement ->
            statement.executeQuery().use { rows ->
                check(rows.next()) { "Пользовательские настройки не созданы." }
                UserPreferences(
                    userPrompt = rows.getString("user_prompt"),
                    confirmedDecisions = databaseJson.decodeFromString(
                        rows.getString("confirmed_decisions_json") ?: "[]",
                    ),
                )
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
        val memoryTraceJson: String?,
    )

    private companion object {
        val databaseJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = true
        }
    }
}
