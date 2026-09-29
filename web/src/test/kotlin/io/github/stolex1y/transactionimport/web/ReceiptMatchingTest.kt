package io.github.stolex1y.transactionimport.web

import io.github.stolex1y.transactionimport.core.AgentConfig
import io.github.stolex1y.transactionimport.core.ExternalTransactionCandidate
import io.github.stolex1y.transactionimport.core.ReceiptAssociationStatus
import io.github.stolex1y.transactionimport.core.SchedulerTraceEvent
import io.github.stolex1y.transactionimport.core.TransactionItem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReceiptMatchingTest {
    @Test
    fun searchesWholePeriodFiltersLocallyAndFetchesOnlyCandidateDetailsOnce() = runBlocking {
        val source = RecordingReceiptSource(
            pages = mapOf(
                0 to """
                    {"receipts":[
                      {"receipt_key":"synthetic-before-boundary","merchant":"Синтетическая булочная","received_at":"2026-09-09T23:00:00Z","amount_minor":500,"currency":"RUB"},
                      {"receipt_key":"synthetic-wrong-amount","merchant":"Синтетический продавец","received_at":"2026-09-10T10:00:00Z","amount_minor":999,"currency":"RUB"},
                      {"receipt_key":"synthetic-wrong-currency","merchant":"Синтетический продавец","received_at":"2026-09-12T10:00:00Z","amount_minor":800,"currency":"EUR"}
                    ],"has_more":true}
                """.trimIndent(),
                3 to """
                    {"receipts":[
                      {"receipt_key":"synthetic-after-boundary","merchant":"Синтетическая мастерская","received_at":"2026-09-13T10:00:00Z","amount_minor":800,"currency":"USD"}
                    ],"has_more":false}
                """.trimIndent(),
            ),
            details = mapOf(
                "synthetic-before-boundary" to detail("2026-09-09T23:00:00Z", 500, "RUB", "Синтетическая булочная"),
                "synthetic-after-boundary" to detail("2026-09-13T10:00:00Z", 800, "USD", "Синтетическая мастерская"),
            ),
        )
        val selectorCalls = mutableListOf<SelectorObservation>()
        val trace = mutableListOf<SchedulerTraceEvent>()
        val engine = ReceiptMatchingEngine(
            mcpTools = source,
            selector = ReceiptMatchSelector { _, _, choices, instruction, rules ->
                selectorCalls += SelectorObservation(choices, instruction, rules)
                ReceiptSelectionResponseView(choices.single().alias, 0.96)
            },
        )

        val result = engine.match(
            transactions = listOf(
                candidate("bank-row-1", "2026-09-10T10:00:00Z", -500, "RUB", "Банковский продавец 1"),
                candidate("bank-row-2", "2026-09-12T10:00:00Z", -800, "USD", "Банковский продавец 2"),
                candidate("bank-row-no-receipt", "2026-09-10T11:00:00Z", -123, "RUB", "Продавец без чека"),
            ),
            config = testConfig,
            explicitInstruction = "Привяжи подходящий чек к каждой операции.",
            confirmedReceiptRules = listOf(
                "Сопоставляй операции с чеками от Синтетического продавца.",
            ),
            trace = trace,
        )

        assertEquals(4, result.receiptCandidateCount)
        assertEquals(2, result.receiptDetailCount)
        assertEquals(listOf(0, 3), source.searchCalls.map { it.arguments["offset"]!!.jsonPrimitive.intOrNull })
        assertEquals("2026-09-09", source.searchCalls.first().arguments.getValue("from").jsonPrimitive.content)
        assertEquals("2026-09-13", source.searchCalls.first().arguments.getValue("to").jsonPrimitive.content)
        assertEquals(
            listOf("synthetic-before-boundary", "synthetic-after-boundary"),
            source.detailCalls.map { it.arguments.getValue("receipt_key").jsonPrimitive.content },
        )
        assertEquals(2, selectorCalls.size)
        assertTrue(selectorCalls.all { it.rules == listOf("Сопоставляй операции с чеками от Синтетического продавца.") })
        assertEquals(
            setOf("Синтетическая торговая точка"),
            selectorCalls.flatMap { it.choices }.mapNotNull { it.settlementPlace }.toSet(),
        )
        assertTrue(!selectorCalls.flatMap { it.choices }.toString().contains("synthetic-before-boundary"))

        val first = result.transactions[0]
        assertEquals(ReceiptAssociationStatus.MATCHED, first.receiptAssociation?.status)
        assertEquals("Банковский продавец 1", first.merchant)
        assertEquals("2026-09-09", first.receiptAssociation?.summary?.date)
        assertEquals("Синтетическая булочная", first.receiptAssociation?.summary?.merchant)
        assertEquals(500L, first.receiptAssociation?.summary?.amountMinor)
        assertEquals("RUB", first.receiptAssociation?.summary?.currency)
        assertEquals(listOf("Синтетическая позиция"), first.items.map(TransactionItem::name))
        assertEquals("Банковский продавец 2", result.transactions[1].merchant)
        assertEquals(ReceiptAssociationStatus.MATCHED, result.transactions[1].receiptAssociation?.status)
        val noReceipt = result.transactions[2]
        assertEquals(ReceiptAssociationStatus.UNMATCHED, noReceipt.receiptAssociation?.status)
        assertTrue(noReceipt.issues.isEmpty())
        assertEquals(2, trace.count { it.stage == "receipts-candidates" && it.status == "started" })
    }

    @Test
    fun oneReceiptSharedByOperationsIsAmbiguousAndItsDetailIsFetchedOnce() = runBlocking {
        val source = RecordingReceiptSource(
            pages = mapOf(
                0 to """{"receipts":[{"receipt_key":"synthetic-shared-key","merchant":"Продавец","received_at":"2026-09-10T10:00:00Z","amount_minor":500,"currency":"RUB"}],"has_more":false}""",
            ),
            details = mapOf(
                "synthetic-shared-key" to detail("2026-09-10T10:00:00Z", 500, "RUB", "Продавец"),
            ),
        )
        var selectorCalls = 0
        val result = ReceiptMatchingEngine(
            mcpTools = source,
            selector = ReceiptMatchSelector { _, _, _, _, _ ->
                selectorCalls += 1
                null
            },
        ).match(
            transactions = listOf(
                candidate("bank-row-1", "2026-09-10", -500, "RUB", "Продавец 1"),
                candidate("bank-row-2", "2026-09-10", -500, "RUB", "Продавец 2"),
            ),
            config = testConfig,
        )

        assertEquals(1, result.receiptDetailCount)
        assertEquals(1, source.detailCalls.size)
        assertEquals(0, selectorCalls)
        assertTrue(result.transactions.all { it.receiptAssociation?.status == ReceiptAssociationStatus.AMBIGUOUS })
        assertTrue(result.transactions.all { it.issues.any { issue -> issue.startsWith("receipt_ambiguous:") } })
    }

    @Test
    fun savedRuleCanDisambiguateSharedReceiptWithoutAttachingItTwice() = runBlocking {
        val source = singleReceiptSource(
            detail("2026-09-10T10:00:00Z", 500, "RUB", "Продавец"),
            key = "synthetic-shared-key",
        )
        val rule = "Пополнение транспортной карты связывать с чеком транспорта."
        var selectorCalls = 0
        val result = ReceiptMatchingEngine(
            source,
            ReceiptMatchSelector { _, transaction, choices, _, rules ->
                selectorCalls += 1
                assertEquals(listOf(rule), rules)
                if (transaction.description == "Пополнение проездного") {
                    ReceiptSelectionResponseView(choices.single().alias, 0.98)
                } else {
                    ReceiptSelectionResponseView(null, 0.0, notTarget = true)
                }
            },
        ).match(
            transactions = listOf(
                candidate("bank-row-cafe", "2026-09-10", -500, "RUB", "Продавец", "Кафе"),
                candidate("bank-row-transit", "2026-09-10", -500, "RUB", "Продавец", "Пополнение проездного"),
            ),
            config = testConfig,
            confirmedReceiptRules = listOf(rule),
        )

        assertEquals(2, selectorCalls)
        assertEquals(1, source.detailCalls.size)
        assertEquals(ReceiptAssociationStatus.UNMATCHED, result.transactions[0].receiptAssociation?.status)
        assertEquals(ReceiptAssociationStatus.MATCHED, result.transactions[1].receiptAssociation?.status)
        assertEquals(1, result.transactions[1].items.size)
    }

    @Test
    fun conflictingSavedRuleSelectionsRemainAmbiguousForBothTransactions() = runBlocking {
        val source = singleReceiptSource(
            detail("2026-09-10T10:00:00Z", 500, "RUB", "Продавец"),
            key = "synthetic-shared-key",
        )
        val result = ReceiptMatchingEngine(
            source,
            ReceiptMatchSelector { _, _, choices, _, _ ->
                ReceiptSelectionResponseView(choices.single().alias, 0.98)
            },
        ).match(
            transactions = listOf(
                candidate("bank-row-1", "2026-09-10", -500, "RUB", "Продавец 1"),
                candidate("bank-row-2", "2026-09-10", -500, "RUB", "Продавец 2"),
            ),
            config = testConfig,
            confirmedReceiptRules = listOf("Синтетическое правило чеков."),
        )

        assertEquals(1, source.detailCalls.size)
        assertTrue(result.transactions.all { it.receiptAssociation?.status == ReceiptAssociationStatus.AMBIGUOUS })
        assertTrue(result.transactions.all { it.items.isEmpty() })
    }

    @Test
    fun schedulerRejectsOutOfRangeSelectorConfidence() = runBlocking {
        val source = singleReceiptSource(detail("2026-09-10T10:00:00Z", 500, "RUB", "Продавец"))
        val result = ReceiptMatchingEngine(
            source,
            ReceiptMatchSelector { _, _, choices, _, _ ->
                ReceiptSelectionResponseView(choices.single().alias, 1.2)
            },
        ).match(
            transactions = listOf(candidate("bank-row", "2026-09-10", -500, "RUB", "Продавец")),
            config = testConfig,
            confirmedReceiptRules = listOf("Синтетическое правило чеков."),
        )

        assertEquals(ReceiptAssociationStatus.AMBIGUOUS, result.transactions.single().receiptAssociation?.status)
        assertTrue(result.transactions.single().items.isEmpty())
    }

    @Test
    fun explicitNullSettlementPlaceIsOptionalAndNonStringValuesAreRejected() = runBlocking {
        val transaction = candidate("bank-row", "2026-09-10", -500, "RUB", "Продавец")
        var observedSettlementPlace = "not-set"
        val validResult = ReceiptMatchingEngine(
            singleReceiptSource(detailWithSettlementPlace("null")),
            ReceiptMatchSelector { _, _, choices, _, _ ->
                observedSettlementPlace = choices.single().settlementPlace ?: "null"
                ReceiptSelectionResponseView(choices.single().alias, 0.98)
            },
        ).match(
            transactions = listOf(transaction),
            config = testConfig,
            explicitInstruction = "Привяжи синтетический чек.",
            strictSelector = true,
        )
        assertEquals("null", observedSettlementPlace)
        assertEquals(ReceiptAssociationStatus.MATCHED, validResult.transactions.single().receiptAssociation?.status)

        assertFailsWith<ReceiptMatchingSourceException> {
            ReceiptMatchingEngine(
                singleReceiptSource(detailWithSettlementPlace("123")),
                ReceiptMatchSelector { _, _, _, _, _ -> null },
            ).match(
                transactions = listOf(transaction),
                config = testConfig,
            )
        }
    }


    @Test
    fun explicitInstructionTargetsOnlyTheNamedOperationWhenReceiptIsShared() = runBlocking {
        val source = RecordingReceiptSource(
            pages = mapOf(
                0 to """{"receipts":[{"receipt_key":"synthetic-shared-key","merchant":"Синтетический продавец","received_at":"2026-09-10T10:00:00Z","amount_minor":500,"currency":"RUB"}],"has_more":false}""",
            ),
            details = mapOf(
                "synthetic-shared-key" to detail("2026-09-10T10:00:00Z", 500, "RUB", "Синтетический продавец"),
            ),
        )
        val selectedDescriptions = mutableListOf<String>()
        val instruction = "Привяжи найденный чек к операции пополнения проездного."
        val result = ReceiptMatchingEngine(
            source,
            ReceiptMatchSelector { _, transaction, choices, explicitInstruction, _ ->
                selectedDescriptions += transaction.description
                assertEquals(instruction, explicitInstruction)
                if (transaction.description == "Пополнение проездного") {
                    ReceiptSelectionResponseView(choices.single().alias, 0.98)
                } else {
                    ReceiptSelectionResponseView(null, 0.0, notTarget = true)
                }
            },
        ).match(
            transactions = listOf(
                candidate(
                    "bank-row-cafe",
                    "2026-09-10T10:00:00Z",
                    -500,
                    "RUB",
                    "Банковский продавец",
                    description = "Кафе",
                ),
                candidate(
                    "bank-row-transit",
                    "2026-09-10T10:00:00Z",
                    -500,
                    "RUB",
                    "Банковский продавец",
                    description = "Пополнение проездного",
                ),
            ),
            config = testConfig,
            explicitInstruction = instruction,
            strictSelector = true,
        )

        assertEquals(listOf("Кафе", "Пополнение проездного"), selectedDescriptions)
        assertEquals(1, source.detailCalls.size)
        assertEquals(ReceiptAssociationStatus.UNMATCHED, result.transactions[0].receiptAssociation?.status)
        assertTrue(result.transactions[0].issues.isEmpty())
        assertEquals(ReceiptAssociationStatus.MATCHED, result.transactions[1].receiptAssociation?.status)
    }

    @Test
    fun incompletePagesAndMalformedExplicitSelectionsDoNotReturnPartialMatches() = runBlocking {
        val incompleteSource = RecordingReceiptSource(
            pages = mapOf(0 to """{"receipts":[],"has_more":true}"""),
            details = emptyMap(),
        )
        assertFailsWith<IllegalArgumentException> {
            ReceiptMatchingEngine(incompleteSource, ReceiptMatchSelector { _, _, _, _, _ -> null })
                .match(
                    transactions = listOf(candidate("bank-row", "2026-09-10", -500, "RUB", "Продавец")),
                    config = testConfig,
                )
        }

        val selectorSource = RecordingReceiptSource(
            pages = mapOf(
                0 to """{"receipts":[{"receipt_key":"synthetic-key","merchant":"Продавец","received_at":"2026-09-10T10:00:00Z","amount_minor":500,"currency":"RUB"}],"has_more":false}""",
            ),
            details = mapOf("synthetic-key" to detail("2026-09-10T10:00:00Z", 500, "RUB", "Продавец")),
        )
        assertFailsWith<ReceiptMatchingSelectionException> {
            ReceiptMatchingEngine(
                selectorSource,
                ReceiptMatchSelector { _, _, _, _, _ -> ReceiptSelectionResponseView("unknown-alias", 0.99) },
            ).match(
                transactions = listOf(candidate("bank-row", "2026-09-10", -500, "RUB", "Продавец")),
                config = testConfig,
                explicitInstruction = "Привяжи чек к этой операции.",
                strictSelector = true,
            )
        }
    }

    @Test
    fun sourceFailureIsNotReportedAsNoReceiptAndDoesNotExposeUpstreamErrorText() = runBlocking {
        val rawFailure = "synthetic-private-fiscal-response"
        val source = object : McpToolProvider {
            override suspend fun allowedTools(): List<McpCallableTool> = emptyList()

            override suspend fun callConfiguredTool(
                serverId: String,
                tool: String,
                arguments: JsonObject,
            ): TbankToolCallResponse {
                throw IllegalStateException(rawFailure)
            }
        }
        val trace = mutableListOf<SchedulerTraceEvent>()

        val error = assertFailsWith<IllegalStateException> {
            ReceiptMatchingEngine(source, ReceiptMatchSelector { _, _, _, _, _ -> null }).match(
                transactions = listOf(candidate("bank-row", "2026-09-10", -500, "RUB", "Продавец")),
                config = testConfig,
                trace = trace,
            )
        }

        assertTrue(!error.message.orEmpty().contains(rawFailure))
        assertEquals(listOf("started", "failed"), trace.map { it.status })
    }

    private data class SelectorObservation(
        val choices: List<ReceiptMatchChoice>,
        val instruction: String,
        val rules: List<String>,
    )

    private class RecordingReceiptSource(
        private val pages: Map<Int, String>,
        private val details: Map<String, String>,
    ) : McpToolProvider {
        val searchCalls = mutableListOf<ToolCall>()
        val detailCalls = mutableListOf<ToolCall>()

        override suspend fun allowedTools(): List<McpCallableTool> = emptyList()

        override suspend fun callConfiguredTool(
            serverId: String,
            tool: String,
            arguments: JsonObject,
        ): TbankToolCallResponse {
            assertEquals("receipts", serverId)
            val call = ToolCall(tool, arguments)
            return when (tool) {
                "search-receipts" -> {
                    searchCalls += call
                    val offset = arguments["offset"]?.jsonPrimitive?.intOrNull ?: 0
                    TbankToolCallResponse(tool = tool, text = pages.getValue(offset))
                }
                "get-receipt" -> {
                    detailCalls += call
                    val key = arguments["receipt_key"]?.jsonPrimitive?.content
                        ?: error("No synthetic receipt key in detail request")
                    TbankToolCallResponse(tool = tool, text = details.getValue(key))
                }
                else -> error("Unexpected synthetic receipt tool: $tool")
            }
        }
    }

    private data class ToolCall(val tool: String, val arguments: JsonObject)

    private companion object {
        val testConfig = AgentConfig("synthetic-provider", "synthetic-model", "disabled")

        fun candidate(
            sourceRef: String,
            occurredAt: String,
            amountMinor: Long,
            currency: String,
            merchant: String,
            description: String = "Синтетическое банковское описание",
        ) = ExternalTransactionCandidate(
            occurredAt = occurredAt,
            amountMinor = amountMinor,
            currency = currency,
            merchant = merchant,
            description = description,
            categoryId = "food.groceries",
            sourceRef = sourceRef,
        )

        fun detail(dateTime: String, amount: Long, currency: String, merchant: String) = """
            {"date_time":"$dateTime","total_minor":$amount,"currency":"$currency","merchant":"$merchant",
             "settlement_place":"Синтетическая торговая точка","fiscal_document_number":"synthetic-fiscal-id",
             "items":[{"name":"Синтетическая позиция","quantity":1,"price_minor":$amount,"sum_minor":$amount}]}
        """.trimIndent()

        fun singleReceiptSource(
            detailText: String,
            key: String = "synthetic-key",
        ) = RecordingReceiptSource(
            pages = mapOf(
                0 to """{"receipts":[{"receipt_key":"$key","merchant":"Продавец","received_at":"2026-09-10T10:00:00Z","amount_minor":500,"currency":"RUB"}],"has_more":false}""",
            ),
            details = mapOf(key to detailText),
        )

        fun detailWithSettlementPlace(settlementPlaceJson: String) = """
            {"date_time":"2026-09-10T10:00:00Z","total_minor":500,"currency":"RUB","merchant":"Продавец",
             "settlement_place":$settlementPlaceJson,"items":[{"name":"Синтетическая позиция","quantity":1,"price_minor":500,"sum_minor":500}]}
        """.trimIndent()
    }
}
