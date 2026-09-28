package io.github.stolex1y.transactionimport.persistence

import io.github.stolex1y.transactionimport.core.SchedulerAggregateResult
import io.github.stolex1y.transactionimport.core.SchedulerRepository
import io.github.stolex1y.transactionimport.core.SchedulerRunHistory
import io.github.stolex1y.transactionimport.core.SchedulerRunStatus
import io.github.stolex1y.transactionimport.core.SchedulerTask
import io.github.stolex1y.transactionimport.core.RevisionConflictException
import io.github.stolex1y.transactionimport.core.SessionNotFoundException
import io.github.stolex1y.transactionimport.core.LinkedSchedulerTasksChangedException
import io.github.stolex1y.transactionimport.core.SchedulerTaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types

class SqliteSchedulerRepository(
    databasePath: String,
) : SchedulerRepository {
    private val jdbcUrl = "jdbc:sqlite:$databasePath"

    init {
        Class.forName("org.sqlite.JDBC")
        openConnection().use(::initializeSchema)
    }

    override suspend fun create(task: SchedulerTask): SchedulerTask = transaction {
        validateTask(task)
        prepareStatement(
            """
            INSERT INTO scheduler_tasks (
                id, name, account_refs_json, start_date, interval_minutes, time_zone,
                status, target_session_id, cursor_date, last_run_at_epoch_ms,
                next_run_at_epoch_ms, last_error, last_result_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            bindTask(statement, task)
            require(statement.executeUpdate() == 1) { "Не удалось создать scheduler task." }
        }
        task
    }

    override suspend fun list(): List<SchedulerTask> = database {
        prepareStatement(
            "SELECT * FROM scheduler_tasks ORDER BY name COLLATE NOCASE ASC, id ASC",
        ).use { statement ->
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(rows.toTask())
                }
            }
        }
    }

    override suspend fun get(id: String): SchedulerTask? = database {
        readTask(this, id)
    }

    override suspend fun update(task: SchedulerTask): SchedulerTask = transaction {
        validateTask(task)
        require(updateTaskRow(this, task) == 1) { "Scheduler task не найден: ${task.id}" }
        task
    }
    override suspend fun claimRun(
        taskId: String,
        expectedCursorDate: String?,
        runId: String,
        claimedAtEpochMs: Long,
    ): SchedulerTask? = transaction {
        require(runId.isNotBlank()) { "Scheduler run id не должен быть пустым." }
        val updated = prepareStatement(
            """
            UPDATE scheduler_tasks
            SET run_claim_id = ?, run_claimed_at_epoch_ms = ?
            WHERE id = ?
              AND ((cursor_date IS NULL AND ? IS NULL) OR cursor_date = ?)
              AND (
                  run_claim_id IS NULL
                  OR run_claimed_at_epoch_ms IS NULL
                  OR run_claimed_at_epoch_ms < ?
              )
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, runId)
            statement.setLong(2, claimedAtEpochMs)
            statement.setString(3, taskId)
            setNullableString(statement, 4, expectedCursorDate)
            setNullableString(statement, 5, expectedCursorDate)
            statement.setLong(6, claimedAtEpochMs - RUN_LEASE_TIMEOUT_MS)
            statement.executeUpdate()
        }
        if (updated != 1) null else readTask(this, taskId)
    }
    override suspend fun renewRunClaim(
        taskId: String,
        expectedCursorDate: String?,
        runId: String,
        claimedAtEpochMs: Long,
    ): Boolean = transaction {
        require(runId.isNotBlank()) { "Scheduler run id не должен быть пустым." }
        prepareStatement(
            """
            UPDATE scheduler_tasks
            SET run_claimed_at_epoch_ms = ?
            WHERE id = ?
              AND ((cursor_date IS NULL AND ? IS NULL) OR cursor_date = ?)
              AND run_claim_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, claimedAtEpochMs)
            statement.setString(2, taskId)
            setNullableString(statement, 3, expectedCursorDate)
            setNullableString(statement, 4, expectedCursorDate)
            statement.setString(5, runId)
            statement.executeUpdate() == 1
        }
    }

    override suspend fun bindTargetSession(
        taskId: String,
        expectedCursorDate: String?,
        runId: String,
        sessionId: String,
    ): SchedulerTask = transaction {
        require(sessionId.isNotBlank()) { "Target session id не должен быть пустым." }
        val current = readTask(this, taskId) ?: error("Scheduler task не найден: $taskId")
        require(current.cursorDate == expectedCursorDate) {
            "Scheduler task был изменён во время запуска: $taskId"
        }
        require(current.targetSessionId == null || current.targetSessionId == sessionId) {
            "Scheduler task уже привязан к другой session: $taskId"
        }
        val updated = current.copy(targetSessionId = sessionId)
        validateTask(updated)
        check(updateTaskRow(this, updated, runClaimId = runId) == 1) {
            "Scheduler run claim потерян при связывании session: $taskId."
        }
        updated
    }

    override suspend fun recordSuccess(
        taskId: String,
        expectedCursorDate: String?,
        result: SchedulerAggregateResult,
        nextCursorDate: String,
        startedAtEpochMs: Long,
        finishedAtEpochMs: Long,
        nextRunAtEpochMs: Long,
    ): SchedulerTask = transaction {
        val current = readTask(this, taskId) ?: error("Scheduler task не найден: $taskId")
        require(current.cursorDate == expectedCursorDate) {
            "Scheduler task был изменён во время запуска: $taskId"
        }
        val updated = current.copy(
            targetSessionId = result.targetSessionId,
            cursorDate = nextCursorDate,
            lastRunAtEpochMs = finishedAtEpochMs,
            nextRunAtEpochMs = nextRunAtEpochMs,
            lastError = null,
            lastResult = result,
        )
        check(updateTaskRow(this, updated, runClaimId = result.runId, clearRunClaim = true) == 1) {
            "Scheduler run claim потерян перед фиксацией результата: $taskId."
        }
        insertRun(
            this,
            SchedulerRunHistory(
                taskId = taskId,
                runId = result.runId,
                status = SchedulerRunStatus.SUCCEEDED,
                startedAtEpochMs = startedAtEpochMs,
                finishedAtEpochMs = finishedAtEpochMs,
                result = result,
            ),
        )
        updated
    }

    override suspend fun recordFailure(
        taskId: String,
        expectedCursorDate: String?,
        run: SchedulerRunHistory,
    ): SchedulerTask = transaction {
        val current = readTask(this, taskId) ?: error("Scheduler task не найден: $taskId")
        require(current.cursorDate == expectedCursorDate) {
            "Scheduler task был изменён во время запуска: $taskId"
        }
        val updated = current.copy(
            lastRunAtEpochMs = run.finishedAtEpochMs,
            nextRunAtEpochMs = run.finishedAtEpochMs?.plus(
                minOf(current.intervalMinutes * 60_000L, 300_000L),
            ),
            lastError = run.error?.take(500),
        )
        check(updateTaskRow(this, updated, runClaimId = run.runId, clearRunClaim = true) == 1) {
            "Scheduler run claim потерян перед фиксацией ошибки: $taskId."
        }
        insertRun(this, run.copy(error = run.error?.take(500)))
        updated
    }

    override suspend fun deleteLinkedSession(
        sessionId: String,
        expectedRevision: Long,
        expectedLinkedTaskIds: List<String>,
    ) = transaction {
        val actualRevision = prepareStatement(
            "SELECT revision FROM import_sessions WHERE id = ?",
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getLong("revision") else null
            }
        } ?: throw SessionNotFoundException(sessionId)
        if (actualRevision != expectedRevision) {
            throw RevisionConflictException(expectedRevision, actualRevision)
        }
        val actualLinkedTaskIds = prepareStatement(
            "SELECT id FROM scheduler_tasks WHERE target_session_id = ? ORDER BY id",
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.getString("id")) }
            }
        }
        if (actualLinkedTaskIds != expectedLinkedTaskIds.sorted()) {
            throw LinkedSchedulerTasksChangedException()
        }
        prepareStatement(
            """
            DELETE FROM scheduler_runs
            WHERE task_id IN (
                SELECT id FROM scheduler_tasks WHERE target_session_id = ?
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.executeUpdate()
        }
        prepareStatement(
            "DELETE FROM scheduler_tasks WHERE target_session_id = ?",
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.executeUpdate()
        }
        val deletedSession = prepareStatement(
            "DELETE FROM import_sessions WHERE id = ? AND revision = ?",
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.setLong(2, expectedRevision)
            statement.executeUpdate()
        }
        if (deletedSession != 1) {
            throw RevisionConflictException(expectedRevision, actualRevision)
        }
    }

    override suspend fun history(taskId: String, limit: Int): List<SchedulerRunHistory> = database {
        require(limit in 1..100) { "Лимит истории должен быть от 1 до 100." }
        prepareStatement(
            """
            SELECT task_id, run_id, status, started_at_epoch_ms, finished_at_epoch_ms,
                   result_json, error
            FROM scheduler_runs
            WHERE task_id = ?
            ORDER BY started_at_epoch_ms DESC, run_id DESC
            LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, taskId)
            statement.setInt(2, limit)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(rows.toRun())
                }
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
                CREATE TABLE IF NOT EXISTS scheduler_tasks (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    account_refs_json TEXT NOT NULL,
                    start_date TEXT NOT NULL,
                    interval_minutes INTEGER NOT NULL,
                    time_zone TEXT NOT NULL,
                    status TEXT NOT NULL,
                    target_session_id TEXT,
                    cursor_date TEXT,
                    last_run_at_epoch_ms INTEGER,
                    next_run_at_epoch_ms INTEGER,
                    last_error TEXT,
                    last_result_json TEXT,
                    run_claim_id TEXT,
                    run_claimed_at_epoch_ms INTEGER
                )
                """.trimIndent(),
            )
            ensureTaskColumn(connection, "next_run_at_epoch_ms", "INTEGER")
            ensureTaskColumn(connection, "run_claim_id", "TEXT")
            ensureTaskColumn(connection, "run_claimed_at_epoch_ms", "INTEGER")
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS scheduler_runs (
                    task_id TEXT NOT NULL,
                    run_id TEXT PRIMARY KEY,
                    status TEXT NOT NULL,
                    started_at_epoch_ms INTEGER NOT NULL,
                    finished_at_epoch_ms INTEGER,
                    result_json TEXT,
                    error TEXT
                )
                """.trimIndent(),
            )
            statement.execute(
                "CREATE INDEX IF NOT EXISTS scheduler_runs_task_idx " +
                    "ON scheduler_runs(task_id, started_at_epoch_ms DESC)",
            )
        }
    }

    private fun ensureTaskColumn(connection: Connection, name: String, definition: String) {
        val present = connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(scheduler_tasks)").use { rows ->
                var found = false
                while (rows.next()) {
                    if (rows.getString("name") == name) found = true
                }
                found
            }
        }
        if (!present) {
            connection.createStatement().use { statement ->
                statement.execute("ALTER TABLE scheduler_tasks ADD COLUMN $name $definition")
            }
        }
    }

    private fun validateTask(task: SchedulerTask) {
        require(task.id.isNotBlank()) { "Scheduler task id не должен быть пустым." }
        require(task.name.trim().isNotEmpty() && task.name.length <= 120) {
            "Название scheduler task должно быть от 1 до 120 символов."
        }
        require(task.accountRefs.isNotEmpty() && task.accountRefs.size <= 20) {
            "Scheduler task должен содержать от 1 до 20 счетов."
        }
        require(task.accountRefs.all { it.trim().isNotEmpty() && it.length <= 200 }) {
            "Ссылки счетов должны быть непрозрачными непустыми значениями."
        }
        require(task.startDate.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$"))) {
            "start_date должен иметь формат YYYY-MM-DD."
        }
        require(task.intervalMinutes in 1..43_200) {
            "Период scheduler task должен быть от 1 минуты до 30 дней."
        }
        require(task.timeZone.length in 1..80) { "Часовой пояс scheduler task задан некорректно." }
    }

    private fun bindTask(statement: java.sql.PreparedStatement, task: SchedulerTask) {
        statement.setString(1, task.id)
        statement.setString(2, task.name.trim())
        statement.setString(3, databaseJson.encodeToString(task.accountRefs))
        statement.setString(4, task.startDate)
        statement.setLong(5, task.intervalMinutes)
        statement.setString(6, task.timeZone)
        statement.setString(7, task.status.name)
        statement.setString(8, task.targetSessionId)
        statement.setString(9, task.cursorDate)
        setNullableLong(statement, 10, task.lastRunAtEpochMs)
        setNullableLong(statement, 11, task.nextRunAtEpochMs)
        statement.setString(12, task.lastError)
        statement.setString(13, task.lastResult?.let(databaseJson::encodeToString))
    }

    private fun updateTaskRow(
        connection: Connection,
        task: SchedulerTask,
        runClaimId: String? = null,
        clearRunClaim: Boolean = false,
    ): Int {
        val claimClause = if (runClaimId == null) "" else " AND run_claim_id = ?"
        val clearClaim = if (clearRunClaim) ", run_claim_id = NULL, run_claimed_at_epoch_ms = NULL" else ""
        return connection.prepareStatement(
            """
            UPDATE scheduler_tasks
            SET name = ?, account_refs_json = ?, start_date = ?, interval_minutes = ?,
                time_zone = ?, status = ?, target_session_id = ?, cursor_date = ?,
                last_run_at_epoch_ms = ?, next_run_at_epoch_ms = ?, last_error = ?,
                last_result_json = ?$clearClaim
            WHERE id = ?$claimClause
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, task.name.trim())
            statement.setString(2, databaseJson.encodeToString(task.accountRefs))
            statement.setString(3, task.startDate)
            statement.setLong(4, task.intervalMinutes)
            statement.setString(5, task.timeZone)
            statement.setString(6, task.status.name)
            statement.setString(7, task.targetSessionId)
            statement.setString(8, task.cursorDate)
            setNullableLong(statement, 9, task.lastRunAtEpochMs)
            setNullableLong(statement, 10, task.nextRunAtEpochMs)
            statement.setString(11, task.lastError)
            statement.setString(12, task.lastResult?.let(databaseJson::encodeToString))
            statement.setString(13, task.id)
            runClaimId?.let { statement.setString(14, it) }
            statement.executeUpdate()
        }
    }

    private fun insertRun(connection: Connection, run: SchedulerRunHistory) {
        connection.prepareStatement(
            """
            INSERT OR REPLACE INTO scheduler_runs (
                task_id, run_id, status, started_at_epoch_ms, finished_at_epoch_ms,
                result_json, error
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, run.taskId)
            statement.setString(2, run.runId)
            statement.setString(3, run.status.name)
            statement.setLong(4, run.startedAtEpochMs)
            setNullableLong(statement, 5, run.finishedAtEpochMs)
            statement.setString(6, run.result?.let(databaseJson::encodeToString))
            statement.setString(7, run.error)
            statement.executeUpdate()
        }
    }

    private fun readTask(connection: Connection, id: String): SchedulerTask? =
        connection.prepareStatement("SELECT * FROM scheduler_tasks WHERE id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.toTask() else null
            }
        }

    private fun ResultSet.toTask(): SchedulerTask = SchedulerTask(
        id = getString("id"),
        name = getString("name"),
        accountRefs = databaseJson.decodeFromString(getString("account_refs_json")),
        startDate = getString("start_date"),
        intervalMinutes = getLong("interval_minutes"),
        timeZone = getString("time_zone"),
        status = SchedulerTaskStatus.valueOf(getString("status")),
        targetSessionId = getString("target_session_id"),
        cursorDate = getString("cursor_date"),
        lastRunAtEpochMs = getLongOrNull("last_run_at_epoch_ms"),
        nextRunAtEpochMs = getLongOrNull("next_run_at_epoch_ms"),
        lastError = getString("last_error"),
        lastResult = getString("last_result_json")?.let { databaseJson.decodeFromString(it) },
    )

    private fun ResultSet.toRun(): SchedulerRunHistory = SchedulerRunHistory(
        taskId = getString("task_id"),
        runId = getString("run_id"),
        status = SchedulerRunStatus.valueOf(getString("status")),
        startedAtEpochMs = getLong("started_at_epoch_ms"),
        finishedAtEpochMs = getLongOrNull("finished_at_epoch_ms"),
        result = getString("result_json")?.let { databaseJson.decodeFromString(it) },
        error = getString("error"),
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

    private companion object {
        const val RUN_LEASE_TIMEOUT_MS = 30 * 60 * 1_000L
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

private fun setNullableLong(statement: java.sql.PreparedStatement, index: Int, value: Long?) {
    if (value == null) statement.setNull(index, Types.INTEGER) else statement.setLong(index, value)
}

private fun ResultSet.getLongOrNull(column: String): Long? =
    getObject(column)?.let { getLong(column) }
