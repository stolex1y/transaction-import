package io.github.stolex1y.transactionimport.web

import io.github.stolex1y.transactionimport.core.McpServerConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JsonSupportTicketMcpServerTest {
    @Test
    fun syntheticTicketLifecyclePersistsOnlyGenericContextAndValidTransitions() = runBlocking {
        val directory = Files.createTempDirectory("support-ticket-test-")
        val storePath = directory.resolve("tickets.json")
        var now = 10_000L
        val server = JsonSupportTicketMcpServer(
            path = storePath,
            nowEpochMs = { now },
            idGenerator = { "support-ticket-1" },
        )
        try {
            val user = server.call("get_support_user", EMPTY_ARGUMENTS)
            assertFalse(user.isError)
            assertEquals("demo-user", Json.parseToJsonElement(user.text).jsonObject["id"]?.jsonPrimitive?.content)
            assertFalse(Files.exists(storePath))

            val created = server.call("create_support_ticket", EMPTY_ARGUMENTS)
            assertFalse(created.isError)
            val ticket = Json.parseToJsonElement(created.text).jsonObject
            val ticketId = assertNotNull(ticket["id"]?.jsonPrimitive?.content)
            assertEquals("support-ticket-1", ticketId)
            assertEquals("Вопрос о Transaction Import", ticket["topic"]?.jsonPrimitive?.content)
            assertEquals("open", ticket["status"]?.jsonPrimitive?.content)
            val storedJson = Files.readString(storePath)
            assertTrue(storedJson.contains("Вопрос о Transaction Import"))
            assertFalse(storedJson.contains("transcript"))
            assertFalse(storedJson.contains("message_text"))

            val duplicateOpen = server.call("create_support_ticket", EMPTY_ARGUMENTS)
            assertTrue(duplicateOpen.isError)

            val ticketArguments = ticketIdArguments(ticketId)
            now += 1_000
            val closed = server.call("close_support_ticket", ticketArguments)
            assertFalse(closed.isError)
            assertEquals("closed", Json.parseToJsonElement(closed.text).jsonObject["status"]?.jsonPrimitive?.content)

            val closedContext = server.call("get_support_ticket_context", ticketArguments)
            assertTrue(closedContext.isError)
            val closedSummary = server.call("list_closed_support_ticket_summaries", EMPTY_ARGUMENTS)
            val summary = Json.parseToJsonElement(closedSummary.text).jsonArray.single().jsonObject
            assertEquals(ticketId, summary["id"]?.jsonPrimitive?.content)
            assertFalse(summary.containsKey("messages"))

            assertTrue(server.call("close_support_ticket", ticketArguments).isError)
            now += 1_000
            val reopened = server.call("reopen_support_ticket", ticketArguments)
            assertFalse(reopened.isError)
            assertEquals("open", Json.parseToJsonElement(reopened.text).jsonObject["status"]?.jsonPrimitive?.content)
            assertTrue(Json.parseToJsonElement(reopened.text).jsonObject.containsKey("reopened_at_epoch_ms"))

            now += 1_000
            assertFalse(server.call("close_support_ticket", ticketArguments).isError)
            val summaries = Json.parseToJsonElement(
                server.call("list_closed_support_ticket_summaries", EMPTY_ARGUMENTS).text,
            ).jsonArray
            assertEquals(1, summaries.size)
        } finally {
            storePath.deleteIfExists()
            storePath.resolveSibling("${storePath.fileName}.lock").deleteIfExists()
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun failedAtomicReplaceDoesNotReportTicketTransitions() = runBlocking {
        val directory = Files.createTempDirectory("support-ticket-write-failure-")
        val storePath = directory.resolve("tickets.json")
        val failingServer = JsonSupportTicketMcpServer(
            path = storePath,
            atomicReplace = { _, _ -> throw IOException("disk full") },
        )
        try {
            val failedCreate = failingServer.call("create_support_ticket", EMPTY_ARGUMENTS)
            assertTrue(failedCreate.isError)
            assertFalse(failedCreate.text.contains("disk full"))
            assertFalse(Files.exists(storePath))

            val workingServer = JsonSupportTicketMcpServer(
                path = storePath,
                idGenerator = { "support-ticket-1" },
            )
            val created = workingServer.call("create_support_ticket", EMPTY_ARGUMENTS)
            val ticketId = assertNotNull(
                Json.parseToJsonElement(created.text).jsonObject["id"]?.jsonPrimitive?.content,
            )
            val ticketArguments = ticketIdArguments(ticketId)

            val failedClose = failingServer.call("close_support_ticket", ticketArguments)
            assertTrue(failedClose.isError)
            assertFalse(failedClose.text.contains("disk full"))
            assertEquals(
                "open",
                Json.parseToJsonElement(Files.readString(storePath)).jsonObject["tickets"]
                    ?.jsonArray?.single()?.jsonObject?.get("status")?.jsonPrimitive?.content,
            )

            assertFalse(workingServer.call("close_support_ticket", ticketArguments).isError)
            val failedReopen = failingServer.call("reopen_support_ticket", ticketArguments)
            assertTrue(failedReopen.isError)
            assertFalse(failedReopen.text.contains("disk full"))
            assertEquals(
                "closed",
                Json.parseToJsonElement(Files.readString(storePath)).jsonObject["tickets"]
                    ?.jsonArray?.single()?.jsonObject?.get("status")?.jsonPrimitive?.content,
            )
        } finally {
            storePath.deleteIfExists()
            storePath.resolveSibling("${storePath.fileName}.lock").deleteIfExists()
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun concurrentServerInstancesCannotLoseTicketCreation() = runBlocking {
        val directory = Files.createTempDirectory("support-ticket-concurrent-")
        val storePath = directory.resolve("tickets.json")
        val first = JsonSupportTicketMcpServer(storePath)
        val second = JsonSupportTicketMcpServer(storePath)
        try {
            val results = listOf(
                async { first.call("create_support_ticket", EMPTY_ARGUMENTS) },
                async { second.call("create_support_ticket", EMPTY_ARGUMENTS) },
            ).awaitAll()

            assertEquals(1, results.count { !it.isError })
            assertEquals(1, results.count { it.isError })
            val state = Json.parseToJsonElement(Files.readString(storePath)).jsonObject
            assertEquals(
                listOf("open"),
                state["tickets"]?.jsonArray?.map { it.jsonObject["status"]!!.jsonPrimitive.content },
            )
        } finally {
            storePath.deleteIfExists()
            storePath.resolveSibling("${storePath.fileName}.lock").deleteIfExists()
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun supportCatalogExposesOnlyConfiguredSupportToolsAndRejectsOtherCalls() = runBlocking {
        val directory = Files.createTempDirectory("support-mcp-isolation-")
        val storePath = directory.resolve("tickets.json")
        val logicalServer = JsonSupportTicketMcpServer(storePath)
        val allowedTools = listOf("get_support_user")
        val client = HttpClient(CIO)
        val catalog = McpCatalogService.connect(
            configs = listOf(
                McpServerConfig(
                    id = "support-tickets",
                    displayName = "Локальные обращения поддержки",
                    endpoint = "http://localhost/support-tickets",
                    enabled = true,
                    allowedTools = allowedTools,
                ),
            ),
            httpClient = client,
            logicalServers = mapOf("support-tickets" to logicalServer),
        )
        try {
            assertEquals(listOf("support-tickets"), catalog.catalog().servers.map { it.id })
            assertEquals(allowedTools, catalog.allowedTools().map { it.name })
            assertFalse(catalog.callConfiguredTool("support-tickets", "get_support_user", EMPTY_ARGUMENTS).isError)
            assertTrue(catalog.callConfiguredTool("support-tickets", "create_support_ticket", EMPTY_ARGUMENTS).isError)
            assertTrue(catalog.callConfiguredTool("tbank-transactions", "list_transactions", EMPTY_ARGUMENTS).isError)
            assertFalse(Files.exists(storePath))
        } finally {
            catalog.close()
            client.close()
            storePath.deleteIfExists()
            storePath.resolveSibling("${storePath.fileName}.lock").deleteIfExists()
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun malformedTicketJsonIsRejectedWithoutReplacingTheFile() = runBlocking {
        val directory = Files.createTempDirectory("support-ticket-corrupt-")
        val storePath = directory.resolve("tickets.json")
        Files.writeString(storePath, "{broken")
        val server = JsonSupportTicketMcpServer(storePath)
        try {
            val result = server.call("get_support_user", EMPTY_ARGUMENTS)
            assertTrue(result.isError)
            assertFalse(result.text.contains("{broken"))
            assertEquals("{broken", Files.readString(storePath))
        } finally {
            storePath.deleteIfExists()
            storePath.resolveSibling("${storePath.fileName}.lock").deleteIfExists()
            Files.deleteIfExists(directory)
        }
    }

    private fun ticketIdArguments(ticketId: String): JsonObject = buildJsonObject {
        put("ticket_id", ticketId)
    }

    private companion object {
        val EMPTY_ARGUMENTS = JsonObject(emptyMap())
    }
}
