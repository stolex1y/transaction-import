package io.github.stolex1y.transactionimport.d04

import io.github.stolex1y.transactionimport.core.ImportStatus
import io.github.stolex1y.transactionimport.core.StructuredImport
import io.github.stolex1y.transactionimport.core.StructuredTransaction
import io.github.stolex1y.transactionimport.core.StructuredValidation
import io.github.stolex1y.transactionimport.core.TransactionDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HardReferenceTest {
    @Test
    fun exactHardReferenceGetsFullFieldScore() {
        val evaluation = evaluateStructured(
            document = StructuredImport(
                status = ImportStatus.READY,
                rejectionReason = null,
                transactions = hardReference().map { reference ->
                    StructuredTransaction(
                        sourceIndex = reference.sourceIndex,
                        direction = if (reference.direction == "income") {
                            TransactionDirection.INCOME
                        } else {
                            TransactionDirection.EXPENSE
                        },
                        occurredAt = reference.occurredAtPrefix,
                        postedAt = reference.postedAtPrefix,
                        amountMinor = reference.amountMinor,
                        currency = reference.currency,
                        merchant = reference.merchant,
                        categoryId = reference.allowedCategoryIds.first(),
                        cardLast4 = null,
                        needsReview = reference.allowedNeedsReview.first(),
                        issues = emptyList(),
                    )
                },
                unparsedFragments = emptyList(),
            ),
            validation = StructuredValidation(valid = true, importable = true),
        )

        assertEquals(6, evaluation.matchedOperations)
        assertEquals(48, evaluation.fieldMatches)
        assertEquals(48, evaluation.fieldCount)
        assertEquals(1.0, evaluation.accuracy)
        assertTrue(evaluation.exactReference)
    }

    @Test
    fun wrongFieldAndExtraTransactionAreVisibleInScore() {
        val reference = hardReference()
        val transactions = reference.take(1).map { expected ->
            StructuredTransaction(
                sourceIndex = expected.sourceIndex,
                direction = TransactionDirection.INCOME,
                occurredAt = expected.occurredAtPrefix,
                postedAt = expected.postedAtPrefix,
                amountMinor = expected.amountMinor,
                currency = expected.currency,
                merchant = expected.merchant,
                categoryId = expected.allowedCategoryIds.first(),
                cardLast4 = null,
                needsReview = expected.allowedNeedsReview.first(),
                issues = emptyList(),
            )
        } + StructuredTransaction(
            sourceIndex = 99,
            direction = TransactionDirection.EXPENSE,
            occurredAt = "2026-02-20T10:00",
            postedAt = "2026-02-20",
            amountMinor = 1,
            currency = "RUB",
            merchant = "EXTRA",
            categoryId = null,
            cardLast4 = null,
            needsReview = true,
            issues = emptyList(),
        )

        val evaluation = evaluateStructured(
            document = StructuredImport(
                status = ImportStatus.READY,
                rejectionReason = null,
                transactions = transactions,
                unparsedFragments = emptyList(),
            ),
            validation = StructuredValidation(valid = true, importable = true),
            reference = reference,
        )

        assertEquals(2, evaluation.actualTransactionCount)
        assertEquals(1, evaluation.extraTransactions)
        assertEquals(7, evaluation.fieldMatches)
        assertEquals(0, evaluation.matchedOperations)
        assertTrue(!evaluation.exactReference)
    }
}
