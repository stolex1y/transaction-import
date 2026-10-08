package io.github.stolex1y.transactionimport.web.benchmark

import io.github.stolex1y.transactionimport.web.McpCallableTool
import io.github.stolex1y.transactionimport.web.McpToolProvider
import io.github.stolex1y.transactionimport.web.TbankToolCallResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate

internal class ImportBenchmarkReplay(
    private val fixture: ImportBenchmarkFixture,
) : McpToolProvider {
    private val tools = listOf(
        McpCallableTool(
            serverId = BANK_SERVER_ID,
            serverDisplayName = "Банковские операции",
            name = LIST_ACCOUNTS,
            description = "Список доступных счетов.",
            inputSchema = objectSchema(),
        ),
        McpCallableTool(
            serverId = BANK_SERVER_ID,
            serverDisplayName = "Банковские операции",
            name = GET_TRANSACTIONS,
            description = "Операции счёта за период.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "account_ref" to stringSchema("Opaque reference returned by list-accounts."),
                    "from" to stringSchema("Inclusive start date, YYYY-MM-DD."),
                    "to" to stringSchema("Inclusive end date, YYYY-MM-DD."),
                    "limit" to integerSchema(),
                    "cursor" to stringSchema("Opaque page cursor."),
                ),
                required = listOf("account_ref", "from", "to"),
            ),
        ),
        McpCallableTool(
            serverId = RECEIPTS_SERVER_ID,
            serverDisplayName = "Сводки чеков",
            name = SEARCH_RECEIPTS,
            description = "Поиск сводок чеков за период.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "from" to stringSchema("Inclusive start date, YYYY-MM-DD."),
                    "to" to stringSchema("Inclusive end date, YYYY-MM-DD."),
                    "limit" to integerSchema(),
                    "offset" to integerSchema(),
                ),
                required = listOf("from", "to", "limit", "offset"),
            ),
        ),
        McpCallableTool(
            serverId = RECEIPTS_SERVER_ID,
            serverDisplayName = "Детали чека",
            name = GET_RECEIPT,
            description = "Получение разрешённых позиций найденного чека.",
            inputSchema = objectSchema(
                properties = mapOf("receipt_key" to stringSchema("Opaque receipt key.")),
                required = listOf("receipt_key"),
            ),
        ),
    )

    override suspend fun allowedTools(): List<McpCallableTool> = tools

    override suspend fun callConfiguredTool(
        serverId: String,
        tool: String,
        arguments: JsonObject,
    ): TbankToolCallResponse = when (serverId to tool) {
        BANK_SERVER_ID to LIST_ACCOUNTS -> bankResponse(tool, listAccounts())
        BANK_SERVER_ID to GET_TRANSACTIONS -> bankResponse(tool, listTransactions(arguments))
        RECEIPTS_SERVER_ID to SEARCH_RECEIPTS -> bankResponse(tool, searchReceipts(arguments))
        RECEIPTS_SERVER_ID to GET_RECEIPT -> bankResponse(tool, receiptDetail(arguments))
        else -> throw IllegalArgumentException("Benchmark replay rejected an unknown source tool.")
    }

    private fun listAccounts(): String = buildJsonObject {
        putJsonArray("accounts") {
            add(
                buildJsonObject {
                    put("account_ref", ACCOUNT_REF)
                    put("name", fixture.modelInput.accountAlias)
                    put("currency", fixture.modelInput.transactions.first().currency)
                },
            )
        }
    }.toString()

    private fun listTransactions(arguments: JsonObject): String {
        require(arguments.requiredText("account_ref") == ACCOUNT_REF) {
            "Benchmark replay rejected an unknown account reference."
        }
        val from = parseDate(arguments.requiredText("from"))
        val to = parseDate(arguments.requiredText("to"))
        require(from <= to && from >= APPROVED_FROM && to <= APPROVED_TO) {
            "Benchmark replay permits only the reviewed transaction period."
        }
        val limit = arguments["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_TRANSACTION_LIMIT
        require(limit in 1..MAX_TRANSACTION_LIMIT) { "Benchmark replay rejected an invalid page limit." }
        val offset = arguments["cursor"]?.jsonPrimitive?.contentOrNull
            ?.let(::parseTransactionCursor) ?: 0
        val matching = fixture.modelInput.transactions.filter { transaction ->
            val date = parseDate(transaction.occurredAt)
            date >= from && date <= to
        }
        val page = matching.drop(offset).take(limit)
        val nextOffset = offset + page.size
        val hasMore = nextOffset < matching.size
        return buildJsonObject {
            put("account_name", fixture.modelInput.accountAlias)
            putJsonArray("transactions") {
                page.forEach { transaction ->
                    add(
                        buildJsonObject {
                            put("transaction_ref", transaction.id)
                            put("date", transaction.occurredAt)
                            put("amount_minor", transaction.signedAmountMinor)
                            put("currency", transaction.currency)
                            put("merchant", transaction.merchant)
                            put("description", transaction.description)
                        },
                    )
                }
            }
            put("has_more", hasMore)
            put("next_cursor", if (hasMore) JsonPrimitive(nextOffset.toString()) else JsonPrimitive(""))
        }.toString()
    }

    private fun searchReceipts(arguments: JsonObject): String {
        val from = parseDate(arguments.requiredText("from"))
        val to = parseDate(arguments.requiredText("to"))
        require(from <= to && from >= RECEIPT_QUERY_FROM && to <= RECEIPT_QUERY_TO) {
            "Benchmark replay permits only the reviewed receipt period."
        }
        val limit = arguments["limit"]?.jsonPrimitive?.intOrNull
            ?: throw IllegalArgumentException("Benchmark replay requires a receipt page limit.")
        val offset = arguments["offset"]?.jsonPrimitive?.intOrNull
            ?: throw IllegalArgumentException("Benchmark replay requires a receipt page offset.")
        require(limit == RECEIPT_PAGE_SIZE && offset >= 0) {
            "Benchmark replay rejected an invalid receipt page request."
        }
        val receipts = fixture.modelInput.receiptSummaries.filter { receipt ->
            val date = parseDate(receipt.receivedAt)
            date >= from && date <= to
        }
        val page = receipts.drop(offset).take(limit)
        val hasMore = offset + page.size < receipts.size
        return buildJsonObject {
            putJsonArray("receipts") {
                page.forEach { receipt ->
                    add(
                        buildJsonObject {
                            put("receipt_key", receiptKey(receipt.id))
                            put("received_at", receipt.receivedAt)
                            put("merchant", receipt.merchant)
                            put("amount_minor", receipt.amountMinor)
                            put("currency", receipt.currency)
                        },
                    )
                }
            }
            put("has_more", hasMore)
        }.toString()
    }

    private fun receiptDetail(arguments: JsonObject): String {
        val key = arguments.requiredText("receipt_key")
        val id = key.removePrefix(RECEIPT_KEY_PREFIX)
        require(key == receiptKey(id) && id.matches(RECEIPT_ID_PATTERN)) {
            "Benchmark replay rejected an unknown receipt reference."
        }
        val detail = fixture.modelInput.receiptDetails[id]
            ?: throw IllegalArgumentException("Benchmark replay has no reviewed detail for this receipt.")
        return JsonObject(detail + ("receipt_key" to JsonPrimitive(key))).toString()
    }

    private fun bankResponse(tool: String, text: String) =
        TbankToolCallResponse(tool = tool, text = text)

    private fun JsonObject.requiredText(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Benchmark replay rejected a missing tool argument.")

    private fun parseDate(value: String): LocalDate =
        runCatching { LocalDate.parse(value.take(10)) }
            .getOrElse { throw IllegalArgumentException("Benchmark replay rejected an invalid date.") }

    private fun parseTransactionCursor(value: String): Int =
        value.toIntOrNull()?.takeIf { it >= 0 }
            ?: throw IllegalArgumentException("Benchmark replay rejected an invalid transaction cursor.")

    private fun receiptKey(id: String) = "$RECEIPT_KEY_PREFIX$id"

    private fun objectSchema(
        properties: Map<String, JsonObject> = emptyMap(),
        required: List<String> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(properties))
        putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
        put("additionalProperties", false)
    }

    private fun stringSchema(description: String) = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private fun integerSchema() = buildJsonObject { put("type", "integer") }

    private companion object {
        const val BANK_SERVER_ID = "tbank-transactions"
        const val RECEIPTS_SERVER_ID = "receipts"
        const val LIST_ACCOUNTS = "list-accounts"
        const val GET_TRANSACTIONS = "get-account-transactions"
        const val SEARCH_RECEIPTS = "search-receipts"
        const val GET_RECEIPT = "get-receipt"
        const val ACCOUNT_REF = "benchmark-account"
        const val DEFAULT_TRANSACTION_LIMIT = 100
        const val MAX_TRANSACTION_LIMIT = 100
        const val RECEIPT_PAGE_SIZE = 20
        const val RECEIPT_KEY_PREFIX = "receipt-"
        val RECEIPT_ID_PATTERN = Regex("C\\d{3}")
        val APPROVED_FROM: LocalDate = LocalDate.parse("2026-09-01")
        val APPROVED_TO: LocalDate = LocalDate.parse("2026-09-30")
        val RECEIPT_QUERY_FROM: LocalDate = LocalDate.parse("2026-08-31")
        val RECEIPT_QUERY_TO: LocalDate = LocalDate.parse("2026-10-01")
    }
}
