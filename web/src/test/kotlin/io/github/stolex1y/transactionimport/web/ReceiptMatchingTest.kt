package io.github.stolex1y.transactionimport.web

import io.github.stolex1y.transactionimport.core.AgentConfig
import io.github.stolex1y.transactionimport.core.ExternalTransactionCandidate
import io.github.stolex1y.transactionimport.core.ReceiptAssociationStatus
import io.github.stolex1y.transactionimport.core.ReceiptReviewReason
import io.github.stolex1y.transactionimport.core.SchedulerTraceEvent
import io.github.stolex1y.transactionimport.core.TransactionDirection
import io.github.stolex1y.transactionimport.core.TransactionItem
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReceiptMatchingTest {

    @Test
    fun unknownExpenseWithoutSummaryCandidateIsUnmatchedAndTransfersStayUnsearched() = runBlocking {
        val source = RecordingReceiptSource(
            pages = mapOf(0 to """{"receipts":[],"has_more":false}"""),
            details = emptyMap(),
        )
        val result = ReceiptMatchingEngine(
            mcpTools = source,
            selector = ReceiptMatchSelector { _, _, _, _, _ ->
                error("Non-purchase operations must not reach the selector.")
            },
        ).match(
            transactions = listOf(
                candidate("transfer", "2026-09-10", -500, "RUB", "Перевод"),
                candidate("income", "2026-09-10", 500, "RUB", "Зачисление"),
                candidate("unknown", "2026-09-10", -500, "RUB", "Неизвестный тип")
                    .copy(categoryId = null),
            ),
            config = testConfig,
        )

        assertEquals(1, source.searchCalls.size)
        assertTrue(source.detailCalls.isEmpty())
        assertEquals(ReceiptAssociationStatus.UNMATCHED, result.transactions[0].receiptAssociation?.status)
        assertEquals(ReceiptAssociationStatus.UNMATCHED, result.transactions[1].receiptAssociation?.status)
        assertEquals(ReceiptAssociationStatus.UNMATCHED, result.transactions[2].receiptAssociation?.status)
    }
    @Test
    fun purchasesAndRefundsRequireTheirMatchingReceiptOperationType() = runBlocking {
        val cases = listOf(
            candidate("purchase", "2026-09-10T10:00:00Z", -500, "RUB", "Кафе") to
                detail("2026-09-10T10:00:00Z", 500, "RUB", "Кафе", operationType = 1),
            candidate(
                "refund",
                "2026-09-10T10:00:00Z",
                500,
                "RUB",
                "Кафе",
                direction = TransactionDirection.REFUND,
            ) to detail("2026-09-10T10:00:00Z", 500, "RUB", "Кафе", operationType = 2),
        )

        for ((transaction, receiptDetail) in cases) {
            val source = singleReceiptSource(receiptDetail)
            val result = ReceiptMatchingEngine(
                source,
                ReceiptMatchSelector { _, _, _, _, _ -> error("A unique safe receipt needs no selector.") },
            ).match(listOf(transaction), testConfig)

            assertEquals(ReceiptAssociationStatus.MATCHED, result.transactions.single().receiptAssociation?.status)
            assertEquals(1, source.detailCalls.size)
            assertTrue(result.transactions.single().items.isNotEmpty())
        }
    }

    @Test
    fun unknownOrIncompatibleReceiptTypesRequireReviewWithoutSelector() = runBlocking {
        data class Case(
            val transaction: ExternalTransactionCandidate,
            val detail: String,
            val reason: ReceiptReviewReason,
        )
        val expense = candidate("expense", "2026-09-10T10:00:00Z", -500, "RUB", "Кафе")
        val refund = candidate(
            "refund",
            "2026-09-10T10:00:00Z",
            500,
            "RUB",
            "Кафе",
            direction = TransactionDirection.REFUND,
        )
        val cases = listOf(
            Case(
                expense,
                detail("2026-09-10T10:00:00Z", 500, "RUB", "Кафе", operationType = 2),
                ReceiptReviewReason.RECEIPT_OPERATION_TYPE_INCOMPATIBLE,
            ),
            Case(
                refund,
                detail("2026-09-10T10:00:00Z", 500, "RUB", "Кафе", operationType = 1),
                ReceiptReviewReason.RECEIPT_OPERATION_TYPE_INCOMPATIBLE,
            ),
            Case(
                expense,
                detail("2026-09-10T10:00:00Z", 500, "RUB", "Кафе", operationType = null),
                ReceiptReviewReason.RECEIPT_OPERATION_TYPE_UNKNOWN,
            ),
            Case(
                expense,
                detail("2026-09-10T10:00:00Z", 500, "RUB", "Кафе")
                    .replace("\"operation_type\":1", "\"operation_type\":\"invalid\""),
                ReceiptReviewReason.RECEIPT_OPERATION_TYPE_UNKNOWN,
            ),
            Case(
                expense,
                detail("2026-09-10T10:00:00Z", 500, "RUB", "Кафе", operationType = 7),
                ReceiptReviewReason.RECEIPT_OPERATION_TYPE_UNKNOWN,
            ),
        )
        var selectorCalls = 0

        for ((index, case) in cases.withIndex()) {
            val source = singleReceiptSource(case.detail, key = "synthetic-$index")
            val result = ReceiptMatchingEngine(
                source,
                ReceiptMatchSelector { _, _, choices, _, _ ->
                    selectorCalls += 1
                    ReceiptSelectionResponseView(choices.single().alias, 1.0)
                },
            ).match(listOf(case.transaction), testConfig)
            val matched = result.transactions.single()

            assertEquals(ReceiptAssociationStatus.AMBIGUOUS, matched.receiptAssociation?.status)
            assertEquals(case.reason, matched.receiptAssociation?.review?.reason)
            assertTrue(matched.items.isEmpty())
            assertEquals(1, matched.receiptAssociation?.review?.candidates?.size)
            assertEquals(1, source.detailCalls.size)
        }

        assertEquals(0, selectorCalls)
    }

    @Test
    fun abbreviatedFiscalIdentifiersAreRemovedFromReceiptItemNames() = runBlocking {
        val rawName = "Булочка ФН: 1234567890123456, ФД=123456, ФП № 987654321"
        val source = singleReceiptSource(
            detail("2026-09-10T10:00:00Z", 500, "RUB", "Кафе")
                .replace("Синтетическая позиция", rawName),
        )
        val result = ReceiptMatchingEngine(
            source,
            ReceiptMatchSelector { _, _, _, _, _ -> error("Unique receipt must not need a selector.") },
        ).match(
            listOf(candidate("fiscal-redaction", "2026-09-10T10:00:00Z", -500, "RUB", "Кафе")),
            testConfig,
        )
        val itemName = result.transactions.single().items.single().name

        assertEquals(ReceiptAssociationStatus.MATCHED, result.transactions.single().receiptAssociation?.status)
        assertTrue(itemName.contains("Булочка"))
        listOf("ФН", "ФД", "ФП", "1234567890123456", "123456", "987654321")
            .forEach { assertFalse(itemName.contains(it)) }
    }

    @Test
    fun unknownOrInconsistentBankDirectionDoesNotSearchOrSelectReceipts() = runBlocking {
        val source = RecordingReceiptSource(
            pages = mapOf(0 to """{"receipts":[],"has_more":false}"""),
            details = emptyMap(),
        )
        var selectorCalls = 0
        val result = ReceiptMatchingEngine(
            source,
            ReceiptMatchSelector { _, _, _, _, _ ->
                selectorCalls += 1
                null
            },
        ).match(
            listOf(
                candidate("unknown-direction", "2026-09-10", -500, "RUB", "Кафе")
                    .copy(direction = null, issues = listOf("direction: неизвестно")),
                candidate(
                    "wrong-sign-refund",
                    "2026-09-10",
                    -500,
                    "RUB",
                    "Возврат",
                    direction = TransactionDirection.REFUND,
                ),
            ),
            testConfig,
        )

        assertTrue(source.searchCalls.isEmpty())
        assertTrue(source.detailCalls.isEmpty())
        assertEquals(0, selectorCalls)
        for (transaction in result.transactions) {
            assertEquals(ReceiptAssociationStatus.AMBIGUOUS, transaction.receiptAssociation?.status)
            assertEquals(ReceiptReviewReason.BANK_DIRECTION_UNKNOWN, transaction.receiptAssociation?.review?.reason)
            assertTrue(transaction.items.isEmpty())
        }
    }

    @Test
    fun sbpRefundCanMatchButOwnAccountTransferCannot() = runBlocking {
        val source = singleReceiptSource(
            detail("2026-09-10T10:00:00Z", 500, "RUB", "Кофе", operationType = 2),
        )
        val result = ReceiptMatchingEngine(
            source,
            ReceiptMatchSelector { _, _, _, _, _ -> error("Unique receipt must not need a selector.") },
        ).match(
            listOf(
                candidate(
                    "sbp-refund",
                    "2026-09-10T10:00:00Z",
                    500,
                    "RUB",
                    "СБП Кофе",
                    direction = TransactionDirection.REFUND,
                ),
                candidate(
                    "own-transfer",
                    "2026-09-10T10:00:00Z",
                    500,
                    "RUB",
                    "Перевод",
                    description = "Между своими счетами",
                    direction = TransactionDirection.REFUND,
                ),
            ),
            testConfig,
        )

        assertEquals(1, source.searchCalls.size)
        assertEquals(1, source.detailCalls.size)
        assertEquals(ReceiptAssociationStatus.MATCHED, result.transactions[0].receiptAssociation?.status)
        assertEquals(ReceiptAssociationStatus.UNMATCHED, result.transactions[1].receiptAssociation?.status)
        assertTrue(result.transactions[1].items.isEmpty())
    }

    @Test
    fun purchaseAndRefundUseDifferentInclusiveDateWindows() = runBlocking {
        data class Case(
            val name: String,
            val direction: TransactionDirection,
            val daysFromOperation: Long,
            val expected: ReceiptAssociationStatus,
        )
        val cases = listOf(
            Case("purchase previous day", TransactionDirection.EXPENSE, -1, ReceiptAssociationStatus.MATCHED),
            Case("purchase next day", TransactionDirection.EXPENSE, 1, ReceiptAssociationStatus.MATCHED),
            Case("purchase too early", TransactionDirection.EXPENSE, -2, ReceiptAssociationStatus.UNMATCHED),
            Case("purchase too late", TransactionDirection.EXPENSE, 2, ReceiptAssociationStatus.UNMATCHED),
            Case("refund same day", TransactionDirection.REFUND, 0, ReceiptAssociationStatus.MATCHED),
            Case("refund day fourteen", TransactionDirection.REFUND, -14, ReceiptAssociationStatus.MATCHED),
            Case("refund day fifteen", TransactionDirection.REFUND, -15, ReceiptAssociationStatus.UNMATCHED),
            Case("refund in future", TransactionDirection.REFUND, 1, ReceiptAssociationStatus.UNMATCHED),
        )
        val operationDate = LocalDate.parse("2026-09-10")

        for (case in cases) {
            val receiptDateTime = "${operationDate.plusDays(case.daysFromOperation)}T10:00:00Z"
            val isRefund = case.direction == TransactionDirection.REFUND
            val amount = if (isRefund) 500L else -500L
            val operationType = if (isRefund) 2 else 1
            val source = singleReceiptSource(
                detail(receiptDateTime, 500, "RUB", "Продавец", operationType),
                receivedAt = receiptDateTime,
            )
            val result = ReceiptMatchingEngine(
                source,
                ReceiptMatchSelector { _, _, _, _, _ -> error("${case.name} has no selector candidates.") },
            ).match(
                listOf(
                    candidate(
                        "window-case",
                        "${operationDate}T10:00:00Z",
                        amount,
                        "RUB",
                        "Продавец",
                        direction = case.direction,
                    ),
                ),
                testConfig,
            )

            assertEquals(case.expected, result.transactions.single().receiptAssociation?.status, case.name)
            assertEquals(
                if (case.expected == ReceiptAssociationStatus.MATCHED) 1 else 0,
                source.detailCalls.size,
                case.name,
            )
        }
    }

    @Test
    fun failedReceiptDetailIsReviewOnlyAndDoesNotBlockSiblingMatch() = runBlocking {
        val source = RecordingReceiptSource(
            pages = mapOf(
                0 to """{"receipts":[
                    {"receipt_key":"synthetic-failed-detail","merchant":"Продавец 1","received_at":"2026-09-10T10:00:00Z","amount_minor":500,"currency":"RUB"},
                    {"receipt_key":"synthetic-valid-detail","merchant":"Продавец 2","received_at":"2026-09-10T10:00:00Z","amount_minor":800,"currency":"RUB"}
                ],"has_more":false}""",
            ),
            details = mapOf(
                "synthetic-valid-detail" to detail("2026-09-10T10:00:00Z", 800, "RUB", "Продавец 2"),
            ),
        )
        val result = ReceiptMatchingEngine(
            source,
            ReceiptMatchSelector { _, _, _, _, _ -> error("Both rows have only one candidate.") },
        ).match(
            listOf(
                candidate("failed-row", "2026-09-10T10:00:00Z", -500, "RUB", "Продавец 1"),
                candidate("valid-row", "2026-09-10T10:00:00Z", -800, "RUB", "Продавец 2"),
            ),
            testConfig,
        )

        assertEquals(2, source.detailCalls.size)
        assertEquals(1, result.receiptDetailCount)
        assertEquals(ReceiptAssociationStatus.AMBIGUOUS, result.transactions[0].receiptAssociation?.status)
        assertEquals(
            ReceiptReviewReason.RECEIPT_DETAIL_UNAVAILABLE,
            result.transactions[0].receiptAssociation?.review?.reason,
        )
        assertTrue(result.transactions[0].items.isEmpty())
        assertEquals(ReceiptAssociationStatus.MATCHED, result.transactions[1].receiptAssociation?.status)
        assertEquals(listOf("Синтетическая позиция"), result.transactions[1].items.map(TransactionItem::name))
    }


    @Test
    fun unknownExpenseWithUniqueReceiptAutoLinksAndFetchesDetails() = runBlocking {
        val source = singleReceiptSource(detail("2026-09-10T10:00:00Z", 500, "RUB", "Синтетический продавец"))
        val result = ReceiptMatchingEngine(
            mcpTools = source,
            selector = ReceiptMatchSelector { _, _, _, _, _ ->
                error("A unique unknown-category expense must not need selector inference.")
            },
        ).match(
            transactions = listOf(
                candidate("unknown", "2026-09-10T10:00:00Z", -500, "RUB", "Неизвестный тип")
                    .copy(categoryId = null),
            ),
            config = testConfig,
        )

        val matched = result.transactions.single()
        assertEquals(1, source.searchCalls.size)
        assertEquals(1, source.detailCalls.size)
        assertEquals(ReceiptAssociationStatus.MATCHED, matched.receiptAssociation?.status)
        assertEquals("Синтетический продавец", matched.receiptAssociation?.summary?.merchant)
        assertEquals(listOf("Синтетическая позиция"), matched.items.map(TransactionItem::name))
    }

    @Test
    fun invalidCategoryExpenseCanAutoLinkOnlyOneEligibleReceipt() = runBlocking {
        val source = singleReceiptSource(detail("2026-09-10T10:00:00Z", 500, "RUB", "Синтетический продавец"))
        val result = ReceiptMatchingEngine(
            mcpTools = source,
            selector = ReceiptMatchSelector { _, _, _, _, _ ->
                error("A unique invalid-category expense must not need selector inference.")
            },
        ).match(
            transactions = listOf(
                candidate("invalid-category", "2026-09-10T10:00:00Z", -500, "RUB", "Синтетический расход")
                    .copy(categoryId = "invalid.synthetic", categoryIssue = "category_not_found"),
            ),
            config = testConfig,
        )

        assertEquals(ReceiptAssociationStatus.MATCHED, result.transactions.single().receiptAssociation?.status)
        assertEquals(1, source.detailCalls.size)
    }

    @Test
    fun strictSelectorCanRejectUniqueUnknownCategoryExpense() = runBlocking {
        val source = singleReceiptSource(detail("2026-09-10T10:00:00Z", 500, "RUB", "Синтетический продавец"))
        var selectorCalls = 0
        val result = ReceiptMatchingEngine(
            mcpTools = source,
            selector = ReceiptMatchSelector { _, _, choices, _, _ ->
                selectorCalls += 1
                assertEquals(1, choices.size)
                ReceiptSelectionResponseView(receiptAlias = null, confidence = 0.0, notTarget = true)
            },
        ).match(
            transactions = listOf(
                candidate("strict-unknown", "2026-09-10T10:00:00Z", -500, "RUB", "Синтетический расход")
                    .copy(categoryId = null),
            ),
            config = testConfig,
            strictSelector = true,
        )

        assertEquals(1, selectorCalls)
        assertEquals(ReceiptAssociationStatus.UNMATCHED, result.transactions.single().receiptAssociation?.status)
    }

    @Test
    fun automaticPreviewKeepsExactMatchAndIsolatesSelectorFailureToAmbiguousRows() = runBlocking {
        val source = RecordingReceiptSource(
            pages = mapOf(
                0 to """{"receipts":[
                    {"receipt_key":"synthetic-unique-key","merchant":"Синтетический продавец 1","received_at":"2026-09-10T10:00:00Z","amount_minor":500,"currency":"RUB"},
                    {"receipt_key":"synthetic-ambiguous-key-1","merchant":"Синтетический продавец 2","received_at":"2026-09-10T10:00:00Z","amount_minor":600,"currency":"RUB"},
                    {"receipt_key":"synthetic-ambiguous-key-2","merchant":"Синтетический продавец 3","received_at":"2026-09-10T10:00:00Z","amount_minor":600,"currency":"RUB"}
                ],"has_more":false}""",
            ),
            details = mapOf(
                "synthetic-unique-key" to detail("2026-09-10T10:00:00Z", 500, "RUB", "Синтетический продавец 1"),
                "synthetic-ambiguous-key-1" to detail("2026-09-10T10:00:00Z", 600, "RUB", "Синтетический продавец 2"),
                "synthetic-ambiguous-key-2" to detail("2026-09-10T10:00:00Z", 600, "RUB", "Синтетический продавец 3"),
            ),
        )
        var selectorCalls = 0
        val result = ReceiptMatchingEngine(
            mcpTools = source,
            selector = ReceiptMatchSelector { _, _, _, _, _ ->
                selectorCalls += 1
                error("Synthetic selector timeout for the ambiguous operation.")
            },
        ).match(
            transactions = listOf(
                candidate("unique", "2026-09-10T10:00:00Z", -500, "RUB", "Неизвестная категория")
                    .copy(categoryId = null),
                candidate("ambiguous", "2026-09-10T10:00:00Z", -600, "RUB", "Кафе"),
            ),
            config = testConfig,
            explicitInstruction = "",
            autoLinkUniqueReceipt = true,
        )

        assertEquals(ReceiptAssociationStatus.MATCHED, result.transactions[0].receiptAssociation?.status)
        assertEquals(ReceiptAssociationStatus.AMBIGUOUS, result.transactions[1].receiptAssociation?.status)
        assertEquals(2, result.transactions[1].receiptAssociation?.review?.candidates?.size)
        assertEquals(1, result.transactions[0].items.size)
        assertTrue(result.transactions[1].items.isEmpty())
        assertEquals(1, selectorCalls)
    }

    @Test
    fun automaticUniqueReceiptDoesNotOverrideExplicitInstruction() = runBlocking {
        val source = singleReceiptSource(detail("2026-09-10T10:00:00Z", 500, "RUB", "Синтетический продавец"))
        var selectorCalls = 0
        val result = ReceiptMatchingEngine(
            mcpTools = source,
            selector = ReceiptMatchSelector { _, _, _, instruction, _ ->
                selectorCalls += 1
                assertTrue(instruction.contains("Привяжи чек только к этой операции"))
                ReceiptSelectionResponseView(null, 0.0, notTarget = true)
            },
        ).match(
            transactions = listOf(
                candidate("explicit-scope", "2026-09-10T10:00:00Z", -500, "RUB", "Неизвестная категория")
                    .copy(categoryId = null),
            ),
            config = testConfig,
            explicitInstruction = "Привяжи чек только к этой операции.",
            autoLinkUniqueReceipt = true,
        )

        assertEquals(1, selectorCalls)
        assertEquals(ReceiptAssociationStatus.UNMATCHED, result.transactions.single().receiptAssociation?.status)
    }


    @Test
    fun automaticPreviewDoesNotFilterSharedReceiptIntoFalseUniqueMatch() = runBlocking {
        val source = RecordingReceiptSource(
            pages = mapOf(
                0 to """{"receipts":[
                    {"receipt_key":"synthetic-unique-key","merchant":"Синтетический продавец 1","received_at":"2026-09-10T23:00:00Z","amount_minor":500,"currency":"RUB"},
                    {"receipt_key":"synthetic-shared-key","merchant":"Синтетический продавец 2","received_at":"2026-09-11T12:00:00Z","amount_minor":500,"currency":"RUB"}
                ],"has_more":false}""",
            ),
            details = mapOf(
                "synthetic-unique-key" to detail("2026-09-10T23:00:00Z", 500, "RUB", "Синтетический продавец 1"),
                "synthetic-shared-key" to detail("2026-09-11T12:00:00Z", 500, "RUB", "Синтетический продавец 2"),
            ),
        )
        var selectorCalls = 0
        val result = ReceiptMatchingEngine(
            mcpTools = source,
            selector = ReceiptMatchSelector { _, _, choices, _, _ ->
                selectorCalls += 1
                assertEquals(2, choices.size)
                error("Synthetic selector failure must leave the ambiguous row for review.")
            },
        ).match(
            transactions = listOf(
                candidate("first", "2026-09-10T23:00:00Z", -500, "RUB", "Продавец 1"),
                candidate("second", "2026-09-12T00:00:00Z", -500, "RUB", "Продавец 2"),
            ),
            config = testConfig,
            autoLinkUniqueReceipt = true,
        )

        assertEquals(1, selectorCalls)
        assertEquals(ReceiptAssociationStatus.AMBIGUOUS, result.transactions[0].receiptAssociation?.status)
        assertEquals(ReceiptReviewReason.MULTIPLE_CANDIDATES, result.transactions[0].receiptAssociation?.review?.reason)
        assertEquals(ReceiptAssociationStatus.AMBIGUOUS, result.transactions[1].receiptAssociation?.status)
        assertEquals(ReceiptReviewReason.SHARED_BY_OPERATIONS, result.transactions[1].receiptAssociation?.review?.reason)
        assertTrue(result.transactions.all { it.items.isEmpty() })
    }

    @Test
    fun candidateSharedByConfirmedAndUnknownExpensesStaysReviewAndTransfersStayExcluded() = runBlocking {
        val source = singleReceiptSource(detail("2026-09-10T10:00:00Z", 500, "RUB", "Синтетический продавец"))
        val result = ReceiptMatchingEngine(
            mcpTools = source,
            selector = ReceiptMatchSelector { _, _, _, _, _ ->
                error("Shared or unknown-category candidates require explicit review.")
            },
        ).match(
            transactions = listOf(
                candidate("purchase", "2026-09-10T10:00:00Z", -500, "RUB", "Покупка"),
                candidate("unknown", "2026-09-10T10:00:00Z", -500, "RUB", "Операция без категории")
                    .copy(categoryId = null),
                candidate("transfer", "2026-09-10T10:00:00Z", -500, "RUB", "Получатель")
                    .copy(description = "Перевод между своими счетами", categoryId = null),
                candidate("income", "2026-09-10T10:00:00Z", 500, "RUB", "Зачисление")
                    .copy(categoryId = null),
            ),
            config = testConfig,
        )

        assertEquals(1, source.searchCalls.size)
        assertEquals(1, source.detailCalls.size)
        assertEquals(ReceiptAssociationStatus.AMBIGUOUS, result.transactions[0].receiptAssociation?.status)
        assertEquals(ReceiptReviewReason.SHARED_BY_OPERATIONS, result.transactions[0].receiptAssociation?.review?.reason)
        assertEquals(ReceiptAssociationStatus.AMBIGUOUS, result.transactions[1].receiptAssociation?.status)
        assertEquals(ReceiptReviewReason.SHARED_BY_OPERATIONS, result.transactions[1].receiptAssociation?.review?.reason)
        assertEquals(ReceiptAssociationStatus.UNMATCHED, result.transactions[2].receiptAssociation?.status)
        assertEquals(ReceiptAssociationStatus.UNMATCHED, result.transactions[3].receiptAssociation?.status)
        assertTrue(result.transactions.drop(2).all { it.items.isEmpty() })
    }

    @Test
    fun ambiguousUnknownExpenseCanSelectExactSafeReceiptSummary() = runBlocking {
        val source = RecordingReceiptSource(
            pages = mapOf(
                0 to """{"receipts":[
                    {"receipt_key":"synthetic-key-one","merchant":"Синтетический продавец 1","received_at":"2026-09-10T10:00:00Z","amount_minor":500,"currency":"RUB"},
                    {"receipt_key":"synthetic-key-two","merchant":"Синтетический продавец 2","received_at":"2026-09-10T10:00:00Z","amount_minor":500,"currency":"RUB"}
                ],"has_more":false}""",
            ),
            details = mapOf(
                "synthetic-key-one" to detail("2026-09-10T10:00:00Z", 500, "RUB", "Синтетический продавец 1"),
                "synthetic-key-two" to detail("2026-09-10T10:00:00Z", 500, "RUB", "Синтетический продавец 2"),
            ),
        )
        val engine = ReceiptMatchingEngine(
            mcpTools = source,
            selector = ReceiptMatchSelector { _, _, _, _, _ ->
                error("Explicit receipt approval must not ask a model to select another receipt.")
            },
        )
        val transaction = candidate("unknown", "2026-09-10T10:00:00Z", -500, "RUB", "Без категории")
            .copy(categoryId = null)
        val requested = engine.match(
            transactions = listOf(transaction),
            config = testConfig,
        ).transactions.single().receiptAssociation!!.review!!.candidates.single {
            it.merchant == "Синтетический продавец 2"
        }
        val result = engine.match(
            transactions = listOf(transaction),
            config = testConfig,
            selectedCandidates = mapOf("unknown" to requested),
        )

        val matched = result.transactions.single()
        assertEquals(ReceiptAssociationStatus.MATCHED, matched.receiptAssociation?.status)
        assertEquals(requested, matched.receiptAssociation?.summary)
        assertEquals("Синтетическая позиция", matched.items.single().name)
    }

    @Test
    fun manualRefreshKeepsMultipleCategorizedCandidatesForExplicitReview() = runBlocking {
        val source = RecordingReceiptSource(
            pages = mapOf(
                0 to """{"receipts":[
                    {"receipt_key":"synthetic-key-one","merchant":"Синтетический продавец 1","received_at":"2026-09-10T10:00:00Z","amount_minor":500,"currency":"RUB"},
                    {"receipt_key":"synthetic-key-two","merchant":"Синтетический продавец 2","received_at":"2026-09-10T10:00:00Z","amount_minor":500,"currency":"RUB"}
                ],"has_more":false}""",
            ),
            details = mapOf(
                "synthetic-key-one" to detail("2026-09-10T10:00:00Z", 500, "RUB", "Синтетический продавец 1"),
                "synthetic-key-two" to detail("2026-09-10T10:00:00Z", 500, "RUB", "Синтетический продавец 2"),
            ),
        )
        val result = ReceiptMatchingEngine(
            mcpTools = source,
            selector = ReceiptMatchSelector { _, _, _, _, _ ->
                error("Manual refresh must not choose among multiple candidates.")
            },
        ).match(
            transactions = listOf(candidate("categorized", "2026-09-10T10:00:00Z", -500, "RUB", "Synthetic")),
            config = testConfig,
            allowSelectorChoice = false,
        )

        assertEquals(ReceiptAssociationStatus.AMBIGUOUS, result.transactions.single().receiptAssociation?.status)
        assertEquals(2, result.transactions.single().receiptAssociation?.review?.candidates?.size)
    }

    @Test
    fun manualRefreshKeepsMultipleCandidatesWhenOwnershipLeavesOneExclusive() = runBlocking {
        val source = RecordingReceiptSource(
            pages = mapOf(
                0 to """{"receipts":[
                    {"receipt_key":"synthetic-exclusive-key","merchant":"Синтетический продавец 1","received_at":"2026-09-10T10:00:00Z","amount_minor":500,"currency":"RUB"},
                    {"receipt_key":"synthetic-shared-key","merchant":"Синтетический продавец 2","received_at":"2026-09-11T10:00:00Z","amount_minor":500,"currency":"RUB"}
                ],"has_more":false}""",
            ),
            details = mapOf(
                "synthetic-exclusive-key" to detail("2026-09-10T10:00:00Z", 500, "RUB", "Синтетический продавец 1"),
                "synthetic-shared-key" to detail("2026-09-11T10:00:00Z", 500, "RUB", "Синтетический продавец 2"),
            ),
        )
        val result = ReceiptMatchingEngine(
            mcpTools = source,
            selector = ReceiptMatchSelector { _, _, _, _, _ ->
                error("Manual refresh must preserve all local choices for review.")
            },
        ).match(
            transactions = listOf(
                candidate("manual-target", "2026-09-10T10:00:00Z", -500, "RUB", "Target"),
                candidate("other-owner", "2026-09-12T10:00:00Z", -500, "RUB", "Other"),
            ),
            config = testConfig,
            allowSelectorChoice = false,
        )

        val target = result.transactions.first().receiptAssociation
        assertEquals(ReceiptAssociationStatus.AMBIGUOUS, target?.status)
        assertEquals(2, target?.review?.candidates?.size)
        assertEquals(
            listOf("Синтетический продавец 1", "Синтетический продавец 2"),
            target?.review?.candidates?.map { it.merchant },
        )
        assertEquals(ReceiptAssociationStatus.AMBIGUOUS, result.transactions.last().receiptAssociation?.status)
    }

    @Test
    fun explicitInstructionScopesMatchingForUnknownCategoryExpenses() = runBlocking {
        val source = RecordingReceiptSource(
            pages = mapOf(
                0 to """{"receipts":[
                    {"receipt_key":"synthetic-target-key","merchant":"Синтетический target","received_at":"2026-09-10T10:00:00Z","amount_minor":500,"currency":"RUB"},
                    {"receipt_key":"synthetic-other-key","merchant":"Синтетический other","received_at":"2026-09-10T10:00:00Z","amount_minor":600,"currency":"RUB"}
                ],"has_more":false}""",
            ),
            details = mapOf(
                "synthetic-target-key" to detail("2026-09-10T10:00:00Z", 500, "RUB", "Синтетический target"),
                "synthetic-other-key" to detail("2026-09-10T10:00:00Z", 600, "RUB", "Синтетический other"),
            ),
        )
        val result = ReceiptMatchingEngine(
            mcpTools = source,
            selector = ReceiptMatchSelector { _, transaction, choices, _, _ ->
                if (transaction.sourceRef == "target") {
                    ReceiptSelectionResponseView(choices.single().alias, 0.98)
                } else {
                    ReceiptSelectionResponseView(null, 0.0, notTarget = true)
                }
            },
        ).match(
            transactions = listOf(
                candidate("target", "2026-09-10T10:00:00Z", -500, "RUB", "Target").copy(categoryId = null),
                candidate("other", "2026-09-10T10:00:00Z", -600, "RUB", "Other").copy(categoryId = null),
            ),
            config = testConfig,
            explicitInstruction = "Привяжи чек только к указанной операции.",
            strictSelector = true,
        )

        assertEquals(ReceiptAssociationStatus.MATCHED, result.transactions[0].receiptAssociation?.status)
        assertEquals(ReceiptAssociationStatus.UNMATCHED, result.transactions[1].receiptAssociation?.status)
    }


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
        assertEquals(
            io.github.stolex1y.transactionimport.core.ReceiptAssociationSummary.CURRENT_PRIVACY_VERSION,
            first.receiptAssociation?.summary?.privacyVersion,
        )
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
        val review = result.transactions.first().receiptAssociation?.review
        assertEquals(ReceiptReviewReason.SHARED_BY_OPERATIONS, review?.reason)
        assertEquals(1, review?.candidates?.size)
        assertEquals("Синтетическая торговая точка", review?.candidates?.single()?.settlementPlace)
        assertEquals("2026-09-10T10:00Z", review?.candidates?.single()?.dateTime)
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
        assertEquals("2026-09-10T10:00Z", validResult.transactions.single().receiptAssociation?.summary?.dateTime)
        assertEquals(null, validResult.transactions.single().receiptAssociation?.summary?.settlementPlace)

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
    fun dateTimeWithUnparsedSuffixIsRejectedBeforePersistence() = runBlocking {
        val maliciousDate = detailWithSettlementPlace("null")
            .replace("2026-09-10T10:00:00Z", "2026-09-10T10:00:00Z synthetic-private-key")
        assertFailsWith<ReceiptMatchingSourceException> {
            ReceiptMatchingEngine(
                singleReceiptSource(maliciousDate),
                ReceiptMatchSelector { _, _, _, _, _ -> null },
            ).match(
                transactions = listOf(candidate("bank-row", "2026-09-10", -500, "RUB", "Продавец")),
                config = testConfig,
            )
        }
    }

    @Test
    fun echoedReceiptKeyIsRemovedFromAllowlistedTextFields() = runBlocking {
        val key = "a14f9876-35db-4d44-9aab-8bf1a8727e09"
        val detail = """
            {"date_time":"2026-09-10T10:00:00Z","total_minor":500,"currency":"RUB","operation_type":1,
             "merchant":"$key","settlement_place":"Кафе $key",
             "items":[{"name":"Товар $key","quantity":1,"price_minor":500,"sum_minor":500}]}
        """.trimIndent()
        val selectorMerchants = mutableListOf<String>()
        val result = ReceiptMatchingEngine(
            singleReceiptSource(detail, key),
            ReceiptMatchSelector { _, _, choices, _, _ ->
                selectorMerchants += choices.single().merchant
                ReceiptSelectionResponseView(choices.single().alias, 0.98)
            },
        ).match(
            transactions = listOf(candidate("bank-row", "2026-09-10", -500, "RUB", "Продавец")),
            config = testConfig,
            explicitInstruction = "Привяжи найденный чек.",
        )
        val safeOutput = listOf(
            result.transactions.single().receiptAssociation?.summary,
            result.transactions.single().items,
            selectorMerchants,
        ).toString()

        assertTrue(result.transactions.single().receiptAssociation?.status == ReceiptAssociationStatus.MATCHED)
        assertTrue(selectorMerchants.single().contains("скрыта"))
        assertFalse(safeOutput.contains(key))
    }

    @Test
    fun buyerLabelsAreRemovedFromReceiptDetailTextBeforePersistence() = runBlocking {
        val buyerName = "Synthetic Buyer Identity"
        val key = "synthetic-buyer-receipt"
        val detail = """
            {"date_time":"2026-09-10T10:00:00Z","total_minor":500,"currency":"RUB","operation_type":1,
             "merchant":"Продавец","settlement_place":"Покупатель: $buyerName",
             "items":[{"name":"Buyer: $buyerName","quantity":1,"price_minor":500,"sum_minor":500}]}
        """.trimIndent()
        val result = ReceiptMatchingEngine(
            singleReceiptSource(detail, key),
            ReceiptMatchSelector { _, _, choices, _, _ ->
                ReceiptSelectionResponseView(choices.single().alias, 0.98)
            },
        ).match(
            transactions = listOf(candidate("buyer-private-row", "2026-09-10", -500, "RUB", "Продавец")),
            config = testConfig,
            explicitInstruction = "Привяжи найденный чек.",
        )
        val storedText = listOf(
            result.transactions.single().receiptAssociation?.summary,
            result.transactions.single().items,
        ).toString()

        assertTrue(result.transactions.single().receiptAssociation?.status == ReceiptAssociationStatus.MATCHED)
        assertFalse(storedText.contains(buyerName))
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
            direction: TransactionDirection? = when {
                amountMinor < 0L -> TransactionDirection.EXPENSE
                amountMinor > 0L -> TransactionDirection.INCOME
                else -> null
            },
        ) = ExternalTransactionCandidate(
            occurredAt = occurredAt,
            amountMinor = amountMinor,
            direction = direction,
            currency = currency,
            merchant = merchant,
            description = description,
            categoryId = "food.groceries",
            sourceRef = sourceRef,
        )

        fun detail(
            dateTime: String,
            amount: Long,
            currency: String,
            merchant: String,
            operationType: Int? = 1,
        ) = """
            {"date_time":"$dateTime","total_minor":$amount,"currency":"$currency",
             ${operationType?.let { "\"operation_type\":$it," }.orEmpty()}
             "merchant":"$merchant",
             "settlement_place":"Синтетическая торговая точка","fiscal_document_number":"synthetic-fiscal-id",
             "items":[{"name":"Синтетическая позиция","quantity":1,"price_minor":$amount,"sum_minor":$amount}]}
        """.trimIndent()

        fun singleReceiptSource(
            detailText: String,
            key: String = "synthetic-key",
            receivedAt: String = "2026-09-10T10:00:00Z",
            amountMinor: Long = 500,
        ) = RecordingReceiptSource(
            pages = mapOf(
                0 to """{"receipts":[{"receipt_key":"$key","merchant":"Продавец","received_at":"$receivedAt","amount_minor":$amountMinor,"currency":"RUB"}],"has_more":false}""",
            ),
            details = mapOf(key to detailText),
        )

        fun detailWithSettlementPlace(settlementPlaceJson: String) = """
            {"date_time":"2026-09-10T10:00:00Z","total_minor":500,"currency":"RUB","operation_type":1,"merchant":"Продавец",
             "settlement_place":$settlementPlaceJson,"items":[{"name":"Синтетическая позиция","quantity":1,"price_minor":500,"sum_minor":500}]}
        """.trimIndent()
    }
}
