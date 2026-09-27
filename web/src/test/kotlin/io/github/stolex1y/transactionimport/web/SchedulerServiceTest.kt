package io.github.stolex1y.transactionimport.web

import io.github.stolex1y.transactionimport.core.AgentGatewayResolver
import io.github.stolex1y.transactionimport.core.SchedulerRunStatus
import io.github.stolex1y.transactionimport.persistence.SqliteSchedulerRepository
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
                timeZone = "UTC",
            )
            val first = scheduler.runNow(created.id)
            val firstResult = assertNotNull(first.lastResult)
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
                    "session-binding",
                    "session-persistence",
                ),
                firstResult.trace.filter { it.status == "started" }.map { it.stage },
            )

            val session = dependencies.agent.getSession(first.targetSessionId!!)
            val transaction = assertNotNull(session.draft).transactions.single().transaction
            assertEquals("transaction-ref-001", transaction.sourceRef)
            now = epoch("2026-09-11")
            val second = scheduler.runNow(created.id)
            assertEquals(0, assertNotNull(second.lastResult).transactionCount)
            assertEquals("2026-09-12", second.cursorDate)
            assertEquals(2, source.bankFromDates.size)
            assertEquals(listOf("2026-09-10", "2026-09-11"), source.bankFromDates)
            assertEquals(2, repository.history(created.id, 10).size)
            assertTrue(repository.history(created.id, 10).all { it.status == SchedulerRunStatus.SUCCEEDED })
        } finally {
            scheduler.close()
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
                "tbank-transactions" to "get-account-transactions" -> {
                    val from = arguments["from"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    bankFromDates += from
                    if (from == "2026-09-10") {
                        TbankToolCallResponse(
                            tool = tool,
                            text = """
                                {"account_name":"Основной счёт","transactions":[{"transaction_ref":"transaction-ref-001","date":"2026-09-10T10:00:00Z","amount_minor":-1,"currency":"RUB","merchant":"Кофе","description":"Утренний кофе"}]}
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
