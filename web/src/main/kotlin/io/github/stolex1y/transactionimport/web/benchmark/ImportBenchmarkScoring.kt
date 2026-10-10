package io.github.stolex1y.transactionimport.web.benchmark

import io.github.stolex1y.transactionimport.core.CategoryType
import io.github.stolex1y.transactionimport.core.DraftTransaction
import io.github.stolex1y.transactionimport.core.ReceiptAssociation
import io.github.stolex1y.transactionimport.core.ReceiptAssociationStatus
import io.github.stolex1y.transactionimport.core.ReceiptAssociationSummary
import io.github.stolex1y.transactionimport.core.TransactionCategory
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime

internal object ImportBenchmarkScoring {
    private data class ReceiptIdentity(
        val date: String,
        val merchant: String,
        val amountMinor: Long,
        val currency: String,
        val dateTime: String?,
    )

    fun score(
        fixture: ImportBenchmarkFixture,
        rows: List<DraftTransaction>,
        categories: List<TransactionCategory>,
    ): ImportBenchmarkMetrics {
        val bySourceId = rows.associateBy { row ->
            row.transaction.sourceRef?.takeIf(String::isNotBlank)
                ?: throw IllegalStateException("Benchmark draft row has no source reference.")
        }
        val expectedIds = fixture.evaluationOnly.selection.keys
        require(
                fixture.evaluationOnly.categories.keys.all { it in expectedIds } &&
                fixture.evaluationOnly.receiptAssociations.keys == expectedIds,
        ) {
            "Benchmark result lost the approved operation identity mapping."
        }

        val selection = fixture.evaluationOnly.selection
        val truePositive = selection.count { (id, expected) -> expected && bySourceId.getValue(id).included }
        val falsePositive = selection.count { (id, expected) -> !expected && bySourceId.getValue(id).included }
        val falseNegative = selection.count { (id, expected) -> expected && !bySourceId.getValue(id).included }
        val predictedIncluded = truePositive + falsePositive
        val expectedIncluded = selection.values.count { it }

        val categoryIdByName = categoryLeafIdsByName(categories)
        val categoryLabels = fixture.evaluationOnly.categories
        val expectedCategoryIds = categoryLabels.mapValues { (_, name) ->
            name?.let { categoryIdByName[it]
                ?: throw IllegalStateException("Benchmark category label is unavailable in the active catalog.") }
        }
        val applicableCategoryLabelCount = expectedCategoryIds.values.count { it != null }
        val categoryAssigned = expectedCategoryIds.count { (id, expectedId) ->
            expectedId != null && bySourceId.getValue(id).transaction.categoryId != null
        }
        val categoryCorrect = expectedCategoryIds.count { (id, expectedId) ->
            bySourceId.getValue(id).transaction.categoryId == expectedId
        }
        val correctlyAssignedCategories = expectedCategoryIds.count { (id, expectedId) ->
            expectedId != null && bySourceId.getValue(id).transaction.categoryId == expectedId
        }

        val receiptLabels = fixture.evaluationOnly.receiptAssociations
        val expectedReceipts = receiptLabels.values.flatMap { it.receiptIds }.distinct().associateWith { id ->
            expectedReceiptIdentity(fixture, id)
        }
        val receiptCorrect = receiptLabels.count { (id, label) ->
            receiptLabelMatches(bySourceId.getValue(id).transaction.receiptAssociation, label, expectedReceipts)
        }
        val associationCounts = rows.mapNotNull { it.transaction.receiptAssociation?.status }
            .groupingBy { it }
            .eachCount()

        return ImportBenchmarkMetrics(
            selectionPrecision = predictedIncluded.takeIf { it > 0 }?.let { truePositive.toDouble() / it },
            selectionRecall = truePositive.toDouble() / expectedIncluded,
            selectionTruePositive = truePositive,
            selectionFalsePositive = falsePositive,
            selectionFalseNegative = falseNegative,
            categoryAccuracy = categoryLabels.size.takeIf { it > 0 }
                ?.let { categoryCorrect.toDouble() / it },
            categoryCoverage = applicableCategoryLabelCount.takeIf { it > 0 }
                ?.let { categoryAssigned.toDouble() / it },
            categoryAccuracyWhenAssigned = categoryAssigned.takeIf { it > 0 }
                ?.let { correctlyAssignedCategories.toDouble() / it },
            categoryCorrect = categoryCorrect,
            categoryAssigned = categoryAssigned,
            categoryLabelCount = categoryLabels.size,
            categoryApplicableLabelCount = applicableCategoryLabelCount,
            matchedReceiptCount = associationCounts[ReceiptAssociationStatus.MATCHED] ?: 0,
            ambiguousReceiptCount = associationCounts[ReceiptAssociationStatus.AMBIGUOUS] ?: 0,
            unmatchedReceiptCount = associationCounts[ReceiptAssociationStatus.UNMATCHED] ?: 0,
            receiptCorrectCount = receiptCorrect,
            receiptAccuracy = receiptCorrect.toDouble() / receiptLabels.size,
            receiptLabelCount = receiptLabels.size,
        )
    }

    private fun receiptLabelMatches(
        actual: ReceiptAssociation?,
        expected: BenchmarkReceiptLabel,
        expectedReceipts: Map<String, ReceiptIdentity>,
    ): Boolean = when (expected.expectation) {
        BenchmarkReceiptExpectation.NO_RECEIPT ->
            actual?.status == ReceiptAssociationStatus.UNMATCHED &&
                actual.summary == null && actual.review == null
        BenchmarkReceiptExpectation.MATCHED ->
            actual?.status == ReceiptAssociationStatus.MATCHED && actual.review == null &&
                actual.summary?.toIdentity() == expected.receiptIds.singleOrNull()?.let(expectedReceipts::get)
        BenchmarkReceiptExpectation.AMBIGUOUS ->
            actual?.status == ReceiptAssociationStatus.AMBIGUOUS && actual.summary == null &&
                actual.review?.candidates?.map { it.toIdentity() }?.toSet() ==
                expected.receiptIds.mapNotNull(expectedReceipts::get).toSet()
    }
    fun receiptLabelMatchesForArtifact(
        fixture: ImportBenchmarkFixture,
        actual: ReceiptAssociation?,
        expected: BenchmarkReceiptLabel,
    ): Boolean {
        val expectedReceipts = expected.receiptIds.associateWith { id -> expectedReceiptIdentity(fixture, id) }
        return receiptLabelMatches(actual, expected, expectedReceipts)
    }


    private fun expectedReceiptIdentity(fixture: ImportBenchmarkFixture, id: String): ReceiptIdentity {
        val receipt = fixture.modelInput.receiptSummaries.singleOrNull { it.id == id }
            ?: throw IllegalStateException("Benchmark receipt label refers to an unavailable summary.")
        val detail = fixture.modelInput.receiptDetails[id]
        val receiptDateTime = detail?.get("date_time")?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank)
        val (date, dateTime) = receiptDateTime?.let(::parseReceiptDateTime)
            ?: (LocalDate.parse(receipt.receivedAt.take(10)).toString() to null)
        return ReceiptIdentity(
            date = date,
            merchant = detail?.get("merchant")?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf(String::isNotBlank) ?: receipt.merchant,
            amountMinor = detail?.get("total_minor")?.jsonPrimitive?.longOrNull ?: receipt.amountMinor,
            currency = detail?.get("currency")?.jsonPrimitive?.contentOrNull?.trim()
                ?.uppercase() ?: receipt.currency.uppercase(),
            dateTime = dateTime,
        )
    }

    private fun ReceiptAssociationSummary.toIdentity(): ReceiptIdentity {
        val (date, dateTime) = dateTime?.let(::parseReceiptDateTime) ?: (this.date to null)
        return ReceiptIdentity(date, merchant, amountMinor, currency.uppercase(), dateTime)
    }

    private fun parseReceiptDateTime(value: String): Pair<String, String> {
        val offset = runCatching { OffsetDateTime.parse(value) }.getOrNull()
        if (offset != null) return offset.toLocalDate().toString() to offset.toString()
        val local = LocalDateTime.parse(value)
        return local.toLocalDate().toString() to local.toString()
    }

    internal fun activeExpenseLeafNamesById(categories: List<TransactionCategory>): Map<String, String> =
        categoryLeafIdsByName(categories).entries.associate { (name, id) -> id to name }

    private fun categoryLeafIdsByName(categories: List<TransactionCategory>): Map<String, String> {
        val parentIds = categories.mapNotNullTo(hashSetOf()) { it.parentId }
        return categories.asSequence()
            .filter { !it.archived && it.type == CategoryType.EXPENSE && it.id !in parentIds }
            .groupBy(TransactionCategory::displayName)
            .mapNotNull { (name, matches) -> matches.singleOrNull()?.let { name to it.id } }
            .toMap()
    }
}
