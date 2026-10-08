package io.github.stolex1y.transactionimport.web.benchmark

import io.github.stolex1y.transactionimport.web.McpCallableTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportBenchmarkReplayTest {
    @Test
    fun replaysSignedTransactionsAndAllReceiptPagesWithoutUpstreamAccess() = runBlocking {
        val fixture = ImportBenchmarkFixtureLoader.loadBundled()
        val replay = ImportBenchmarkReplay(fixture)
        val tools = replay.allowedTools()
        assertEquals(4, tools.size)
        assertTrue(tools.any { it.serverId == "tbank-transactions" && it.name == "list-accounts" })
        assertTrue(tools.any { it.serverId == "receipts" && it.name == "get-receipt" })

        val accounts = call(replay, "tbank-transactions", "list-accounts", buildJsonObject {})
        assertFalse(accounts.isError)
        val accountRef = Json.parseToJsonElement(accounts.text).jsonObject
            .getValue("accounts").jsonArray.single().jsonObject
            .getValue("account_ref").jsonPrimitive.content

        val operations = call(
            replay,
            "tbank-transactions",
            "get-account-transactions",
            buildJsonObject {
                put("account_ref", accountRef)
                put("from", "2026-09-01")
                put("to", "2026-09-30")
                put("limit", 100)
            },
        )
        val replayedOperations = Json.parseToJsonElement(operations.text).jsonObject
            .getValue("transactions").jsonArray
        assertEquals(50, replayedOperations.size)
        fixture.modelInput.transactions.zip(replayedOperations).forEach { (expected, actual) ->
            val row = actual.jsonObject
            assertEquals(expected.id, row.getValue("transaction_ref").jsonPrimitive.content)
            assertEquals(expected.signedAmountMinor, row.getValue("amount_minor").jsonPrimitive.longOrNull)
        }

        val receiptPages = (0..2).map { page ->
            val response = call(
                replay,
                "receipts",
                "search-receipts",
                buildJsonObject {
                    put("from", "2026-08-31")
                    put("to", "2026-10-01")
                    put("limit", 20)
                    put("offset", page * 20)
                },
            )
            Json.parseToJsonElement(response.text).jsonObject
        }
        assertEquals(listOf(20, 20, 11), receiptPages.map { it.getValue("receipts").jsonArray.size })
        assertEquals(listOf(true, true, false), receiptPages.map {
            it.getValue("has_more").jsonPrimitive.content.toBooleanStrict()
        })
        val receiptRows = receiptPages.flatMap { it.getValue("receipts").jsonArray }
        val receiptKeys = receiptRows.map { it.jsonObject.getValue("receipt_key").jsonPrimitive.content }
        assertEquals(51, receiptKeys.distinct().size)
        assertTrue(receiptKeys.all { it.matches(Regex("receipt-C\\d{3}")) })

        val detailKeys = fixture.modelInput.receiptDetails.keys.map { "receipt-$it" }
        assertEquals(9, detailKeys.size)
        assertTrue(detailKeys.all { it in receiptKeys })
        val itemCount = detailKeys.sumOf { key ->
            val details = call(
                replay,
                "receipts",
                "get-receipt",
                buildJsonObject { put("receipt_key", key) },
            )
            Json.parseToJsonElement(details.text).jsonObject.getValue("items").jsonArray.size
        }
        assertEquals(25, itemCount)
    }

    @Test
    fun rejectsSourceQueriesOutsideApprovedDateWindow() = runBlocking {
        val replay = ImportBenchmarkReplay(ImportBenchmarkFixtureLoader.loadBundled())

        assertFailsWith<IllegalArgumentException> {
            call(
                replay,
                "tbank-transactions",
                "get-account-transactions",
                buildJsonObject {
                    put("account_ref", "benchmark-account")
                    put("from", "2026-08-01")
                    put("to", "2026-09-30")
                },
            )
        }
    }

    private suspend fun call(
        replay: ImportBenchmarkReplay,
        serverId: String,
        tool: String,
        arguments: kotlinx.serialization.json.JsonObject,
    ) = replay.callConfiguredTool(serverId, tool, arguments)
}
