package io.github.stolex1y.transactionimport.persistence

import io.github.stolex1y.transactionimport.core.RevisionConflictException
import io.github.stolex1y.transactionimport.core.LinkedSchedulerTasksChangedException
import io.github.stolex1y.transactionimport.core.SchedulerRunHistory
import io.github.stolex1y.transactionimport.core.SchedulerRunStatus
import io.github.stolex1y.transactionimport.core.SchedulerTask
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.io.path.deleteIfExists
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SqliteSchedulerRepositoryTest {
    @Test
    fun linkedSessionDeletionRollsBackAsAUnitAndPreservesUnrelatedData() = runBlocking {
        val database = Files.createTempFile("scheduler-delete-", ".sqlite")
        try {
            val repository = SqliteSchedulerRepository(database.toString())
            DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "CREATE TABLE import_sessions (id TEXT PRIMARY KEY, revision INTEGER NOT NULL)",
                    )
                    statement.execute("INSERT INTO import_sessions VALUES ('linked-session', 4)")
                    statement.execute("INSERT INTO import_sessions VALUES ('unrelated-session', 2)")
                }
            }
            repository.create(task("linked-task", "linked-session"))
            repository.create(task("unrelated-task", "unrelated-session"))
            assertEquals(
                "linked-task",
                repository.claimRun(
                    taskId = "linked-task",
                    expectedCursorDate = null,
                    runId = "linked-run",
                    claimedAtEpochMs = 1_000,
                )?.id,
            )
            repository.recordFailure(
                taskId = "linked-task",
                expectedCursorDate = null,
                run = run("linked-task", "linked-run"),
            )
            assertEquals(
                "unrelated-task",
                repository.claimRun(
                    taskId = "unrelated-task",
                    expectedCursorDate = null,
                    runId = "unrelated-run",
                    claimedAtEpochMs = 1_000,
                )?.id,
            )
            repository.recordFailure(
                taskId = "unrelated-task",
                expectedCursorDate = null,
                run = run("unrelated-task", "unrelated-run"),
            )

            assertFailsWith<RevisionConflictException> {
                repository.deleteLinkedSession(
                    "linked-session",
                    expectedRevision = 3,
                    expectedLinkedTaskIds = listOf("linked-task"),
                )
            }
            assertEquals(1, repository.history("linked-task").size)
            assertFailsWith<LinkedSchedulerTasksChangedException> {
                repository.deleteLinkedSession(
                    "linked-session",
                    expectedRevision = 4,
                    expectedLinkedTaskIds = emptyList(),
                )
            }
            assertEquals("linked-task", repository.get("linked-task")?.id)
            assertEquals(1, repository.history("linked-task").size)

            DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TRIGGER reject_linked_run_delete
                        BEFORE DELETE ON scheduler_runs
                        WHEN OLD.task_id = 'linked-task'
                        BEGIN
                            SELECT RAISE(ABORT, 'injected run-history delete failure');
                        END
                        """.trimIndent(),
                    )
                }
            }
            assertFailsWith<Exception> {
                repository.deleteLinkedSession(
                    "linked-session",
                    expectedRevision = 4,
                    expectedLinkedTaskIds = listOf("linked-task"),
                )
            }
            assertEquals("linked-task", repository.get("linked-task")?.id)
            assertEquals(1, repository.history("linked-task").size)
            DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT revision FROM import_sessions WHERE id = 'linked-session'",
                    ).use { rows ->
                        check(rows.next())
                        assertEquals(4L, rows.getLong("revision"))
                    }
                }
            }
            assertEquals("unrelated-task", repository.get("unrelated-task")?.id)
            assertEquals(1, repository.history("unrelated-task").size)

            DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP TRIGGER reject_linked_run_delete")
                }
            }
            repository.deleteLinkedSession(
                "linked-session",
                expectedRevision = 4,
                expectedLinkedTaskIds = listOf("linked-task"),
            )
            assertEquals(null, repository.get("linked-task"))
            assertEquals(emptyList(), repository.history("linked-task"))
            assertEquals("unrelated-task", repository.get("unrelated-task")?.id)
            assertEquals(1, repository.history("unrelated-task").size)
            DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT id FROM import_sessions ORDER BY id").use { rows ->
                        val remaining = buildList { while (rows.next()) add(rows.getString("id")) }
                        assertEquals(listOf("unrelated-session"), remaining)
                    }
                }
            }
        } finally {
            database.deleteIfExists()
            Files.deleteIfExists(database.resolveSibling(database.fileName.toString() + "-wal"))
            Files.deleteIfExists(database.resolveSibling(database.fileName.toString() + "-shm"))
        }
    }

    private fun task(id: String, sessionId: String) = SchedulerTask(
        id = id,
        name = id,
        accountRefs = listOf("opaque-account"),
        startDate = "2026-09-01",
        intervalMinutes = 60,
        targetSessionId = sessionId,
    )

    private fun run(taskId: String, runId: String) = SchedulerRunHistory(
        taskId = taskId,
        runId = runId,
        status = SchedulerRunStatus.FAILED,
        startedAtEpochMs = 1_000,
        finishedAtEpochMs = 2_000,
        error = "test failure",
    )
}
