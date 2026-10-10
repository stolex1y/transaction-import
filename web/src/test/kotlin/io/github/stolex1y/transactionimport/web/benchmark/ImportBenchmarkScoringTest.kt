package io.github.stolex1y.transactionimport.web.benchmark

import io.github.stolex1y.transactionimport.core.CategoryType
import io.github.stolex1y.transactionimport.core.DEFAULT_AGENT_CATEGORIES
import io.github.stolex1y.transactionimport.core.DraftTransaction
import io.github.stolex1y.transactionimport.core.ReceiptAssociation
import io.github.stolex1y.transactionimport.core.ReceiptAssociationStatus
import io.github.stolex1y.transactionimport.core.ReceiptAssociationSummary
import io.github.stolex1y.transactionimport.core.ReceiptReviewEvidence
import io.github.stolex1y.transactionimport.core.ReceiptReviewReason
import io.github.stolex1y.transactionimport.core.StructuredTransaction
import io.github.stolex1y.transactionimport.core.TransactionCategory
import io.github.stolex1y.transactionimport.core.TransactionDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImportBenchmarkScoringTest {
    @Test
    fun categoryScoringRequiresExpectedNoCategoryForNAAndMeasuresCoverageOnlyOnApplicableRows() {
        val fixture = ImportBenchmarkFixtureLoader.loadBundled()
        val categories = benchmarkCategories()
        val categoryIdByName = categories.associate { it.displayName to it.id }
        val correctRows = fixtureRows(fixture, categoryIdByName)

        val correct = ImportBenchmarkScoring.score(fixture, correctRows, categories)
        assertEquals(50, correct.categoryLabelCount)
        assertEquals(23, correct.categoryApplicableLabelCount)
        assertEquals(23, correct.categoryAssigned)
        assertEquals(50, correct.categoryCorrect)
        assertEquals(1.0, correct.categoryAccuracy)
        assertEquals(1.0, correct.categoryCoverage)

        val incorrectlyCategorizedNA = correctRows.map { row ->
            if (row.transaction.sourceRef == "TX02") {
                row.copy(transaction = row.transaction.copy(categoryId = "food.cafe"))
            } else {
                row
            }
        }
        val wrongNA = ImportBenchmarkScoring.score(fixture, incorrectlyCategorizedNA, categories)
        assertEquals(49, wrongNA.categoryCorrect)
        assertEquals(49.0 / 50.0, wrongNA.categoryAccuracy)
        assertEquals(1.0, wrongNA.categoryCoverage)

        val missingApplicableCategory = correctRows.map { row ->
            if (row.transaction.sourceRef == "TX03") {
                row.copy(transaction = row.transaction.copy(categoryId = null))
            } else {
                row
            }
        }
        val missing = ImportBenchmarkScoring.score(fixture, missingApplicableCategory, categories)
        assertEquals(22, missing.categoryAssigned)
        assertEquals(22.0 / 23.0, missing.categoryCoverage)
        assertEquals(49, missing.categoryCorrect)
    }

    @Test
    fun missingCategoryLabelsAreExcludedButExplicitNoCategoryLabelsRemainScored() {
        val fixture = ImportBenchmarkFixtureLoader.loadBundled()
        val categories = benchmarkCategories()
        val categoryIdByName = categories.associate { it.displayName to it.id }
        val missingCategoryId = fixture.evaluationOnly.categories.entries.first { it.value != null }.key
        val explicitNoCategoryId = fixture.evaluationOnly.categories.entries.first { it.value == null }.key
        val partialFixture = fixture.copy(
            evaluationOnly = fixture.evaluationOnly.copy(
                categories = fixture.evaluationOnly.categories - missingCategoryId,
            ),
        )
        val alteredRows = fixtureRows(fixture, categoryIdByName).map { row ->
            when (row.transaction.sourceRef) {
                missingCategoryId -> row.copy(transaction = row.transaction.copy(categoryId = "unknown.category"))
                explicitNoCategoryId -> row.copy(transaction = row.transaction.copy(categoryId = "food.cafe"))
                else -> row
            }
        }

        val metrics = ImportBenchmarkScoring.score(partialFixture, alteredRows, categories)

        assertEquals(49, metrics.categoryLabelCount)
        assertEquals(22, metrics.categoryApplicableLabelCount)
        assertEquals(22, metrics.categoryAssigned)
        assertEquals(48, metrics.categoryCorrect)
        assertEquals(48.0 / 49.0, metrics.categoryAccuracy)
        assertEquals(1.0, metrics.categoryCoverage)
    }

    @Test
    fun categoryMetricsAreUndefinedWhenNoCategoryLabelsExist() {
        val fixture = ImportBenchmarkFixtureLoader.loadBundled()
        val categories = benchmarkCategories()
        val categoryIdByName = categories.associate { it.displayName to it.id }
        val unlabeledFixture = fixture.copy(
            evaluationOnly = fixture.evaluationOnly.copy(categories = emptyMap()),
        )

        val metrics = ImportBenchmarkScoring.score(
            unlabeledFixture,
            fixtureRows(unlabeledFixture, categoryIdByName),
            categories,
        )

        assertEquals(0, metrics.categoryLabelCount)
        assertEquals(0, metrics.categoryApplicableLabelCount)
        assertNull(metrics.categoryAccuracy)
        assertNull(metrics.categoryCoverage)
    }

    @Test
    fun receiptScoringChecksReceiptIdentityAndAmbiguousCandidateSetNotOnlyStatus() {
        val fixture = ImportBenchmarkFixtureLoader.loadBundled()
        val categories = benchmarkCategories()
        val categoryIdByName = categories.associate { it.displayName to it.id }
        val unmatchedRows = fixtureRows(fixture, categoryIdByName)
        val allUnmatched = ImportBenchmarkScoring.score(fixture, unmatchedRows, categories)
        assertEquals(42, allUnmatched.receiptCorrectCount)
        assertEquals(0.84, allUnmatched.receiptAccuracy)

        val wrongMatched = withReceipt(
            unmatchedRows,
            "TX15",
            ReceiptAssociation(
                status = ReceiptAssociationStatus.MATCHED,
                summary = podorozhnikSummary.copy(merchant = "Другой продавец"),
            ),
        )
        assertEquals(42, ImportBenchmarkScoring.score(fixture, wrongMatched, categories).receiptCorrectCount)

        val podorozhnikMatched = withReceipt(
            unmatchedRows,
            "TX15",
            ReceiptAssociation(ReceiptAssociationStatus.MATCHED, summary = podorozhnikSummary),
        )
        assertEquals(43, ImportBenchmarkScoring.score(fixture, podorozhnikMatched, categories).receiptCorrectCount)

        val oneGoldAppleCandidate = withReceipt(
            podorozhnikMatched,
            "TX34",
            ReceiptAssociation(
                status = ReceiptAssociationStatus.AMBIGUOUS,
                review = ReceiptReviewEvidence(
                    reason = ReceiptReviewReason.MULTIPLE_CANDIDATES,
                    candidates = listOf(goldAppleFirstSummary),
                ),
            ),
        )
        assertEquals(43, ImportBenchmarkScoring.score(fixture, oneGoldAppleCandidate, categories).receiptCorrectCount)

        val bothGoldAppleCandidates = withReceipt(
            podorozhnikMatched,
            "TX34",
            ReceiptAssociation(
                status = ReceiptAssociationStatus.AMBIGUOUS,
                review = ReceiptReviewEvidence(
                    reason = ReceiptReviewReason.MULTIPLE_CANDIDATES,
                    candidates = listOf(goldAppleFirstSummary, goldAppleSecondSummary),
                ),
            ),
        )
        assertEquals(44, ImportBenchmarkScoring.score(fixture, bothGoldAppleCandidates, categories).receiptCorrectCount)

        val falseReceiptForSelfTransfer = withReceipt(
            bothGoldAppleCandidates,
            "TX05",
            ReceiptAssociation(
                status = ReceiptAssociationStatus.MATCHED,
                summary = ReceiptAssociationSummary(
                    date = "2026-09-28",
                    merchant = "ООО \"ИНТЕРНЕТ РЕШЕНИЯ\"",
                    amountMinor = 111800,
                    currency = "RUB",
                ),
            ),
        )
        assertEquals(43, ImportBenchmarkScoring.score(fixture, falseReceiptForSelfTransfer, categories).receiptCorrectCount)
    }

    private fun fixtureRows(
        fixture: ImportBenchmarkFixture,
        categoryIdByName: Map<String, String>,
    ): List<DraftTransaction> = fixture.modelInput.transactions.mapIndexed { index, source ->
        val categoryId = fixture.evaluationOnly.categories[source.id]?.let(categoryIdByName::getValue)
        val association = ReceiptAssociation(ReceiptAssociationStatus.UNMATCHED)
        DraftTransaction(
            id = "draft-${source.id}",
            included = fixture.evaluationOnly.selection.getValue(source.id),
            transaction = StructuredTransaction(
                sourceIndex = index + 1,
                direction = if (source.direction == "expense") {
                    TransactionDirection.EXPENSE
                } else {
                    TransactionDirection.INCOME
                },
                occurredAt = source.occurredAt,
                postedAt = null,
                amountMinor = source.amountMinor,
                currency = source.currency,
                merchant = source.merchant,
                categoryId = categoryId,
                needsReview = false,
                issues = emptyList(),
                receiptAssociation = association,
                sourceRef = source.id,
            ),
        )
    }

    private fun withReceipt(
        rows: List<DraftTransaction>,
        transactionId: String,
        association: ReceiptAssociation,
    ): List<DraftTransaction> = rows.map { row ->
        if (row.transaction.sourceRef == transactionId) {
            row.copy(transaction = row.transaction.copy(receiptAssociation = association))
        } else {
            row
        }
    }

    private fun benchmarkCategories() = DEFAULT_AGENT_CATEGORIES + listOf(
        TransactionCategory(
            id = "benchmark.clothing",
            displayName = "Одежда",
            type = CategoryType.EXPENSE,
        ),
        TransactionCategory(
            id = "benchmark.food.ration",
            displayName = "Рацион",
            type = CategoryType.EXPENSE,
            parentId = "category.food",
        ),
        TransactionCategory(
            id = "benchmark.beauty",
            displayName = "Красота",
            type = CategoryType.EXPENSE,
        ),
    )

    private companion object {
        val podorozhnikSummary = ReceiptAssociationSummary(
            date = "2026-09-22",
            merchant = "СПБ ГКУ \"ОРГАНИЗАТОР ПЕРЕВОЗОК\"",
            amountMinor = 50000,
            currency = "RUB",
            dateTime = "2026-09-22T18:40:00",
        )
        val goldAppleFirstSummary = ReceiptAssociationSummary(
            date = "2026-09-16",
            merchant = "ООО \"ЕКАТЕРИНБУРГ ЯБЛОКО\"",
            amountMinor = 435500,
            currency = "RUB",
            dateTime = "2026-09-16T21:02:00",
        )
        val goldAppleSecondSummary = goldAppleFirstSummary.copy(dateTime = "2026-09-16T14:34:00")
    }
}
