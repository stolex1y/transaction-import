package io.github.stolex1y.transactionimport.d04

import io.github.stolex1y.transactionimport.core.StructuredImport
import io.github.stolex1y.transactionimport.core.StructuredValidation
import io.github.stolex1y.transactionimport.core.TransactionDirection
import kotlinx.serialization.Serializable

@Serializable
data class ReferenceOperation(
    val sourceIndex: Int,
    val direction: String,
    val occurredAtPrefix: String,
    val postedAtPrefix: String?,
    val amountMinor: Long,
    val currency: String,
    val merchant: String,
    val allowedMerchants: List<String> = emptyList(),
    val allowedCategoryIds: List<String?>,
    val allowedNeedsReview: List<Boolean>,
)

@Serializable
data class D04Evaluation(
    val valid: Boolean,
    val importable: Boolean,
    val expectedTransactionCount: Int,
    val actualTransactionCount: Int,
    val matchedOperations: Int,
    val fieldMatches: Int,
    val fieldCount: Int,
    val accuracy: Double,
    val extraTransactions: Int,
    val exactReference: Boolean,
)

fun hardReference(): List<ReferenceOperation> = listOf(
    ReferenceOperation(
        sourceIndex = 1,
        direction = "expense",
        occurredAtPrefix = "2026-02-15T09:02",
        postedAtPrefix = "2026-02-16",
        amountMinor = 120490,
        currency = "RUB",
        merchant = "POS*MARKET TEST",
        allowedCategoryIds = listOf("food.groceries"),
        allowedNeedsReview = listOf(false),
    ),
    ReferenceOperation(
        sourceIndex = 2,
        direction = "expense",
        occurredAtPrefix = "2026-02-15T09:03",
        postedAtPrefix = null,
        amountMinor = 2500,
        currency = "RUB",
        merchant = "POS*MARKET TEST",
        allowedMerchants = listOf("Комиссия POS*MARKET TEST"),
        allowedCategoryIds = listOf(null),
        allowedNeedsReview = listOf(true),
    ),
    ReferenceOperation(
        sourceIndex = 3,
        direction = "income",
        occurredAtPrefix = "2026-02-15T09:04",
        postedAtPrefix = null,
        amountMinor = 120490,
        currency = "RUB",
        merchant = "POS*MARKET TEST",
        allowedMerchants = listOf("POS*MARKET TEST — ОТМЕНА"),
        allowedCategoryIds = listOf("food.groceries"),
        allowedNeedsReview = listOf(false),
    ),
    ReferenceOperation(
        sourceIndex = 4,
        direction = "expense",
        occurredAtPrefix = "2026-02-16T21:17",
        postedAtPrefix = "2026-02-17",
        amountMinor = 185000,
        currency = "RUB",
        merchant = "RIDE TEST",
        allowedCategoryIds = listOf("transport"),
        allowedNeedsReview = listOf(true),
    ),
    ReferenceOperation(
        sourceIndex = 5,
        direction = "income",
        occurredAtPrefix = "2026-02-18T12:00",
        postedAtPrefix = null,
        amountMinor = 1200000,
        currency = "RUB",
        merchant = "COMPANY TEST",
        allowedMerchants = listOf("TRANSFER TEST"),
        allowedCategoryIds = listOf(null, "transfer.internal"),
        allowedNeedsReview = listOf(false, true),
    ),
    ReferenceOperation(
        sourceIndex = 6,
        direction = "expense",
        occurredAtPrefix = "2026-02-19T08:40",
        postedAtPrefix = null,
        amountMinor = 79900,
        currency = "RUB",
        merchant = "CLOUD SERVICE TEST",
        allowedCategoryIds = listOf("services.digital"),
        allowedNeedsReview = listOf(false),
    ),
)

fun evaluateStructured(
    document: StructuredImport?,
    validation: StructuredValidation?,
    reference: List<ReferenceOperation> = hardReference(),
): D04Evaluation {
    val transactions = document?.transactions.orEmpty()
    val bySourceIndex = transactions.associateBy { it.sourceIndex }
    var fieldMatches = 0
    var matchedOperations = 0
    reference.forEach { expected ->
        val actual = bySourceIndex[expected.sourceIndex] ?: return@forEach
        val actualPostedAt = actual.postedAt
        val expectedPostedAt = expected.postedAtPrefix
        var operationMatches = 0
        if (actual.direction.name.lowercase() == expected.direction) operationMatches++
        if (actual.occurredAt.startsWith(expected.occurredAtPrefix)) operationMatches++
        if (actualPostedAt == expectedPostedAt ||
            (actualPostedAt != null &&
                expectedPostedAt != null &&
                actualPostedAt.startsWith(expectedPostedAt))
        ) operationMatches++
        if (expected.allowedMerchants
                .plus(expected.merchant)
                .any { normalizeMerchant(actual.merchant) == normalizeMerchant(it) }
        ) operationMatches++
        if (actual.amountMinor == expected.amountMinor) operationMatches++
        if (actual.currency == expected.currency) operationMatches++
        if (actual.categoryId in expected.allowedCategoryIds) operationMatches++
        if (actual.needsReview in expected.allowedNeedsReview) operationMatches++
        fieldMatches += operationMatches
        if (operationMatches == 8) matchedOperations++
    }
    val fieldCount = reference.size * 8
    val extraTransactions = transactions.count { it.sourceIndex !in reference.map(ReferenceOperation::sourceIndex) }
    val exactReference = validation?.valid == true &&
        transactions.size == reference.size &&
        fieldMatches == fieldCount &&
        extraTransactions == 0
    return D04Evaluation(
        valid = validation?.valid == true,
        importable = validation?.importable == true,
        expectedTransactionCount = reference.size,
        actualTransactionCount = transactions.size,
        matchedOperations = matchedOperations,
        fieldMatches = fieldMatches,
        fieldCount = fieldCount,
        accuracy = if (fieldCount == 0) 0.0 else fieldMatches.toDouble() / fieldCount,
        extraTransactions = extraTransactions,
        exactReference = exactReference,
    )
}

private fun normalizeMerchant(value: String): String =
    value.trim()
        .uppercase()
        .replace(Regex("\\s+"), " ")
        .removePrefix("КОМИССИЯ ")
        .substringBefore(" — ОТМЕНА")
