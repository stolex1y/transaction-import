package io.github.stolex1y.transactionimport.web

import io.github.stolex1y.transactionimport.core.AgentGatewayResolver
import io.github.stolex1y.transactionimport.core.ImportSession
import io.github.stolex1y.transactionimport.core.ImportSessionRepository
import io.github.stolex1y.transactionimport.core.ImportSessionState
import io.github.stolex1y.transactionimport.core.LinkedSchedulerTasksChangedException
import io.github.stolex1y.transactionimport.core.SchedulerRunStatus
import io.github.stolex1y.transactionimport.core.SchedulerRepository
import io.github.stolex1y.transactionimport.core.SchedulerTask
import io.github.stolex1y.transactionimport.core.SmartExpenseAgent
import io.github.stolex1y.transactionimport.persistence.SqliteSchedulerRepository
import io.github.stolex1y.transactionimport.persistence.SqliteImportSessionRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.deleteIfExists
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchedulerServiceTest {
    @Test
    fun schedulerPersistsEnrichedItemsAndAdvancesIncrementalCursor() = runBlocking {
        val database = Files.createTempFile("scheduler-test-", ".sqlite")
        var now = epoch("2026-09-10")
        var id = 0
        val dependencies = fakeAgentDependencies(database.toString())
        val source = RecordingSchedulerSource()
        val repository = SqliteSchedulerRepository(database.toString())
        val scheduler = SchedulerService(
            repository = repository,
            agent = dependencies.agent,
            mcpTools = source,
            gatewayResolver = AgentGatewayResolver { FakeAgentGateway() },
            runtimeConfig = dependencies.runtimeConfig,
            idGenerator = { "scheduler-test-${++id}" },
            nowEpochMs = { now },
            matchSelector = ReceiptMatchSelector { _, _, choices ->
                ReceiptSelectionResponseView(choices.single().alias, 0.96)
            },
        )
        try {
            val created = scheduler.createTask(
                name = "Тест receipts",
                accountRefs = listOf("transaction-ref-account-1"),
                startDate = "2026-09-10",
                intervalMinutes = 1,
            )
            val linkedSessionId = assertNotNull(created.targetSessionId)
            val emptyLinkedSession = dependencies.agent.getSession(linkedSessionId)
            assertEquals(emptyList(), emptyLinkedSession.messages)
            assertEquals(null, emptyLinkedSession.draft)
            assertEquals(java.time.ZoneId.systemDefault().id, created.timeZone)

            val first = scheduler.runNow(created.id)
            val firstResult = assertNotNull(first.lastResult)
            assertEquals(linkedSessionId, firstResult.targetSessionId)
            assertEquals(1, firstResult.transactionCount)
            assertEquals(1, firstResult.enrichedItemCount)
            assertEquals(1, firstResult.receiptCandidateCount)
            assertEquals(1, firstResult.receiptDetailCount)
            assertEquals(0, firstResult.unmatchedCount)
            assertEquals(0, firstResult.ambiguousCount)
            assertEquals("2026-09-11", first.cursorDate)
            assertEquals(
                listOf(
                    "bank-transactions",
                    "receipts-candidates",
                    "receipts-details",
                    "session-persistence",
                ),
                firstResult.trace.filter { it.status == "started" }.map { it.stage },
            )

            val session = dependencies.agent.getSession(linkedSessionId)
            val item = assertNotNull(session.draft).transactions.single()
            val transaction = item.transaction
            assertEquals("transaction-ref-001", transaction.sourceRef)
            assertEquals("Синтетический чек [идентификатор чека скрыт]", item.description)
            dependencies.agent.replaceTransaction(
                sessionId = linkedSessionId,
                expectedRevision = session.session.revision,
                transactionId = item.id,
                included = item.included,
                description = "Описание, изменённое вручную",
                replacement = transaction.copy(merchant = "Правка пользователя"),
            )
            now = epoch("2026-09-11")
            val second = scheduler.runNow(created.id)
            assertEquals(0, assertNotNull(second.lastResult).transactionCount)
            assertEquals("2026-09-12", second.cursorDate)
            assertEquals(2, source.bankFromDates.size)
            assertEquals(listOf("2026-09-10", "2026-09-11"), source.bankFromDates)
            assertEquals(2, repository.history(created.id, 10).size)
            assertTrue(repository.history(created.id, 10).all { it.status == SchedulerRunStatus.SUCCEEDED })
            val editedAfterRun = dependencies.agent.getSession(linkedSessionId).draft!!.transactions.single()
            assertEquals("Правка пользователя", editedAfterRun.transaction.merchant)
            assertEquals("Описание, изменённое вручную", editedAfterRun.description)

        } finally {
            scheduler.close()
            database.deleteIfExists()
            Files.deleteIfExists(database.resolveSibling(database.fileName.toString() + "-wal"))
            Files.deleteIfExists(database.resolveSibling(database.fileName.toString() + "-shm"))
        }
    }

    @Test
    fun schedulerTaskCreateRemovesItsSessionWhenTaskPersistenceFails() = runBlocking {
        val database = Files.createTempFile("scheduler-create-failure-", ".sqlite")
        val dependencies = fakeAgentDependencies(database.toString())
        val source = RecordingSchedulerSource()
        val repository = object : SchedulerRepository by SqliteSchedulerRepository(database.toString()) {
            override suspend fun create(task: io.github.stolex1y.transactionimport.core.SchedulerTask): SchedulerTask {
                throw IllegalStateException("injected scheduler persistence failure")
            }
        }
        val scheduler = SchedulerService(
            repository = repository,
            agent = dependencies.agent,
            mcpTools = source,
            gatewayResolver = AgentGatewayResolver { FakeAgentGateway() },
            runtimeConfig = dependencies.runtimeConfig,
            idGenerator = { "failed-task" },
        )
        try {
            assertFailsWith<IllegalStateException> {
                scheduler.createTask(
                    name = "Не сохранённое задание",
                    accountRefs = listOf("transaction-ref-account-1"),
                    startDate = "2026-09-10",
                    intervalMinutes = 60,
                )
            }
            assertEquals(emptyList(), dependencies.agent.listSessions())
            assertEquals(emptyList(), repository.list())
        } finally {
            scheduler.close()
            database.deleteIfExists()
            Files.deleteIfExists(database.resolveSibling(database.fileName.toString() + "-wal"))
            Files.deleteIfExists(database.resolveSibling(database.fileName.toString() + "-shm"))
        }
    }

    @Test
    fun cancellationDuringLinkedSessionCreationKeepsTaskAndSessionConsistent() = runBlocking {
        val database = Files.createTempFile("scheduler-create-cancel-", ".sqlite")
        val dependencies = fakeAgentDependencies(database.toString())
        val baseSessionRepository = SqliteImportSessionRepository(database.toString())
        val sessionPersisted = CompletableDeferred<ImportSessionState>()
        val allowSessionCreateToReturn = CompletableDeferred<Unit>()
        val sessionRepository = object : ImportSessionRepository by baseSessionRepository {
            override suspend fun create(session: ImportSession): ImportSessionState {
                val created = baseSessionRepository.create(session)
                sessionPersisted.complete(created)
                allowSessionCreateToReturn.await()
                return created
            }
        }
        val agent = SmartExpenseAgent(
            repository = sessionRepository,
            gatewayResolver = AgentGatewayResolver { FakeAgentGateway() },
            idGenerator = { "cancel-session" },
            nowEpochMs = { 1_000L },
            runtimeConfig = dependencies.runtimeConfig,
            configValidator = {},
        )
        val baseSchedulerRepository = SqliteSchedulerRepository(database.toString())
        val taskPersisted = CompletableDeferred<SchedulerTask>()
        val allowTaskCreateToReturn = CompletableDeferred<Unit>()
        val schedulerRepository = object : SchedulerRepository by baseSchedulerRepository {
            override suspend fun create(task: SchedulerTask): SchedulerTask {
                val created = baseSchedulerRepository.create(task)
                taskPersisted.complete(created)
                allowTaskCreateToReturn.await()
                return created
            }
        }
        val scheduler = SchedulerService(
            repository = schedulerRepository,
            agent = agent,
            mcpTools = RecordingSchedulerSource(),
            gatewayResolver = AgentGatewayResolver { FakeAgentGateway() },
            runtimeConfig = dependencies.runtimeConfig,
            idGenerator = { "cancel-task" },
        )
        var creation: Deferred<SchedulerTask>? = null
        try {
            val creationJob = async {
                scheduler.createTask(
                    name = "Cancellation-safe task",
                    accountRefs = listOf("transaction-ref-account-1"),
                    startDate = "2026-09-10",
                    intervalMinutes = 60,
                )
            }
            creation = creationJob
            val session = sessionPersisted.await()
            creationJob.cancel()
            allowSessionCreateToReturn.complete(Unit)
            val task = taskPersisted.await()
            allowTaskCreateToReturn.complete(Unit)
            creationJob.join()

            assertTrue(creationJob.isCancelled)
            assertEquals(session.session.id, task.targetSessionId)
            assertEquals(task.id, baseSchedulerRepository.get(task.id)?.id)
            assertEquals(session.session.id, baseSessionRepository.list().single().id)
        } finally {
            allowSessionCreateToReturn.complete(Unit)
            allowTaskCreateToReturn.complete(Unit)
            creation?.cancelAndJoin()
            scheduler.close()
            dependencies.scheduler?.close()
            database.deleteIfExists()
            Files.deleteIfExists(database.resolveSibling(database.fileName.toString() + "-wal"))
            Files.deleteIfExists(database.resolveSibling(database.fileName.toString() + "-shm"))
        }
    }

    @Test
    fun cancellationAfterTaskCommitKeepsLinkedSession() = runBlocking {
        val database = Files.createTempFile("scheduler-task-commit-cancel-", ".sqlite")
        val dependencies = fakeAgentDependencies(database.toString())
        val baseRepository = SqliteSchedulerRepository(database.toString())
        val taskPersisted = CompletableDeferred<SchedulerTask>()
        val allowTaskCreateToReturn = CompletableDeferred<Unit>()
        val repository = object : SchedulerRepository by baseRepository {
            override suspend fun create(task: SchedulerTask): SchedulerTask {
                val created = baseRepository.create(task)
                taskPersisted.complete(created)
                allowTaskCreateToReturn.await()
                return created
            }
        }
        val scheduler = SchedulerService(
            repository = repository,
            agent = dependencies.agent,
            mcpTools = RecordingSchedulerSource(),
            gatewayResolver = AgentGatewayResolver { FakeAgentGateway() },
            runtimeConfig = dependencies.runtimeConfig,
            idGenerator = { "task-commit-cancel" },
        )
        var creation: Deferred<SchedulerTask>? = null
        try {
            val creationJob = async {
                scheduler.createTask(
                    name = "Committed task",
                    accountRefs = listOf("transaction-ref-account-1"),
                    startDate = "2026-09-10",
                    intervalMinutes = 60,
                )
            }
            creation = creationJob
            val task = taskPersisted.await()
            creationJob.cancel()
            allowTaskCreateToReturn.complete(Unit)
            creationJob.join()

            assertTrue(creationJob.isCancelled)
            assertEquals(task.id, baseRepository.get(task.id)?.id)
            assertEquals(task.targetSessionId, dependencies.agent.listSessions().single().id)
        } finally {
            allowTaskCreateToReturn.complete(Unit)
            creation?.cancelAndJoin()
            scheduler.close()
            dependencies.scheduler?.close()
            database.deleteIfExists()
            Files.deleteIfExists(database.resolveSibling(database.fileName.toString() + "-wal"))
            Files.deleteIfExists(database.resolveSibling(database.fileName.toString() + "-shm"))
        }
    }

    @Test
    fun taskCreationCannotRaceLinkedSessionDeletion() = runBlocking {
        val database = Files.createTempFile("scheduler-create-delete-race-", ".sqlite")
        val dependencies = fakeAgentDependencies(database.toString())
        val baseRepository = SqliteSchedulerRepository(database.toString())
        val createEntered = CompletableDeferred<Unit>()
        val allowTaskPersistence = CompletableDeferred<Unit>()
        val deleteEntered = CompletableDeferred<Unit>()
        val repository = object : SchedulerRepository by baseRepository {
            override suspend fun create(task: SchedulerTask): SchedulerTask {
                createEntered.complete(Unit)
                allowTaskPersistence.await()
                return baseRepository.create(task)
            }

            override suspend fun deleteLinkedSession(
                sessionId: String,
                expectedRevision: Long,
                expectedLinkedTaskIds: List<String>,
            ) {
                deleteEntered.complete(Unit)
                baseRepository.deleteLinkedSession(sessionId, expectedRevision, expectedLinkedTaskIds)
            }
        }
        val scheduler = SchedulerService(
            repository = repository,
            agent = dependencies.agent,
            mcpTools = RecordingSchedulerSource(),
            gatewayResolver = AgentGatewayResolver { FakeAgentGateway() },
            runtimeConfig = dependencies.runtimeConfig,
            idGenerator = { "create-delete-race-task" },
        )
        var creation: Deferred<SchedulerTask>? = null
        var deletion: Deferred<Throwable?>? = null
        try {
            val creationJob = async {
                scheduler.createTask(
                    name = "Race-safe task",
                    accountRefs = listOf("transaction-ref-account-1"),
                    startDate = "2026-09-10",
                    intervalMinutes = 60,
                )
            }
            creation = creationJob
            createEntered.await()
            val session = dependencies.agent.listSessions().single()
            val deletionJob = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    scheduler.deleteLinkedSession(
                        session.id,
                        session.revision,
                        expectedLinkedTaskIds = emptyList(),
                    )
                }.exceptionOrNull()
            }
            deletion = deletionJob

            assertFalse(deleteEntered.isCompleted)
            allowTaskPersistence.complete(Unit)
            val task = creationJob.await()
            assertTrue(deletionJob.await() is LinkedSchedulerTasksChangedException)

            assertTrue(deleteEntered.isCompleted)
            assertEquals(task.id, baseRepository.get(task.id)?.id)
            assertEquals(task.targetSessionId, dependencies.agent.listSessions().single().id)
        } finally {
            allowTaskPersistence.complete(Unit)
            deletion?.cancelAndJoin()
            creation?.cancelAndJoin()
            scheduler.close()
            dependencies.scheduler?.close()
            database.deleteIfExists()
            Files.deleteIfExists(database.resolveSibling(database.fileName.toString() + "-wal"))
            Files.deleteIfExists(database.resolveSibling(database.fileName.toString() + "-shm"))
        }
    }

    private class RecordingSchedulerSource : McpToolProvider {
        val bankFromDates = mutableListOf<String>()

        override suspend fun allowedTools(): List<McpCallableTool> = emptyList()

        override suspend fun callConfiguredTool(
            serverId: String,
            tool: String,
            arguments: JsonObject,
        ): TbankToolCallResponse {
            return when (serverId to tool) {
                "tbank-transactions" to "list-accounts" -> TbankToolCallResponse(
                    tool = tool,
                    text = """[{"account_ref":"transaction-ref-account-1","name":"Основной счёт","currency":"RUB"}]""",
                )
                "tbank-transactions" to "get-account-transactions" -> {
                    val from = arguments["from"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    bankFromDates += from
                    if (from == "2026-09-10") {
                        TbankToolCallResponse(
                            tool = tool,
                            text = """
                                {"account_name":"Основной счёт","transactions":[{"transaction_ref":"transaction-ref-001","date":"2026-09-10T10:00:00Z","amount_minor":-1,"currency":"RUB","merchant":"Кофе","description":"Синтетический чек receipt-key-private"}]}
                            """.trimIndent(),
                        )
                    } else {
                        TbankToolCallResponse(tool = tool, text = "{\"account_name\":\"Основной счёт\",\"transactions\":[]}")
                    }
                }
                "receipts" to "search-receipts" -> TbankToolCallResponse(
                    tool = tool,
                    text = """
                        {"receipts":[{"receipt_key":"receipt-key-private","received_at":"2026-09-10T12:00:00Z","amount_minor":1,"currency":"RUB","merchant":"Кофе"}]}
                    """.trimIndent(),
                )
                "receipts" to "get-receipt" -> TbankToolCallResponse(
                    tool = tool,
                    text = """
                        {"date_time":"2026-09-10T12:00:00Z","total_minor":1,"currency":"RUB","items":[{"name":"Кофе","quantity":1,"price_minor":1,"sum_minor":1}]}
                    """.trimIndent(),
                )
                else -> TbankToolCallResponse(tool = tool, isError = true, text = "unexpected source call")
            }
        }
    }

    private companion object {
        fun epoch(date: String): Long = Instant.parse("${date}T00:00:00Z").toEpochMilli()
    }
}
