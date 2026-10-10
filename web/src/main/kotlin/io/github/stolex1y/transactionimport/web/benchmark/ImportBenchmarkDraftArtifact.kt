package io.github.stolex1y.transactionimport.web.benchmark

import io.github.stolex1y.transactionimport.core.DraftTransaction
import io.github.stolex1y.transactionimport.core.ImportDraft
import io.github.stolex1y.transactionimport.core.ReceiptAssociation
import io.github.stolex1y.transactionimport.core.ReceiptAssociationSummary
import io.github.stolex1y.transactionimport.core.TransactionCategory
import io.github.stolex1y.transactionimport.web.McpPreview
import io.github.stolex1y.transactionimport.web.McpPreviewTransaction
import io.github.stolex1y.transactionimport.web.ReceiptMatchingStageStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

internal data class ImportBenchmarkDraftSnapshot(
    val status: String,
    val version: Long,
    val rows: Map<String, JsonObject>,
)

internal data class ImportBenchmarkPreviewSnapshot(
    val status: String,
    val rows: Map<String, JsonObject>,
)

internal class ImportBenchmarkDraftArtifactWriter(
    private val fixture: ImportBenchmarkFixture,
    private val directory: Path = Path.of("build", "reports", "import-benchmark"),
    private val fileNameForRepetition: (Int) -> String = ::defaultFileName,
    private val emit: (String) -> Unit = {},
) {
    fun write(
        repetition: Int,
        outcome: ImportBenchmarkOutcome,
        draft: ImportBenchmarkDraftSnapshot?,
        generatedAt: String = Instant.now().toString(),
        preview: ImportBenchmarkPreviewSnapshot? = null,
    ): Path {
        require(repetition in 1..fixture.repetitions)
        require(outcome !is ImportBenchmarkOutcome.Scored || draft != null) {
            "A scored benchmark artifact requires its validated draft."
        }
        require(preview == null || draft == null) {
            "A pre-confirmation preview cannot be stored as a finalized draft."
        }
        val path = directory.resolve(fileNameForRepetition(repetition))
        ImportBenchmarkReportWriter.writeNew(path, render(repetition, outcome, draft, generatedAt, preview))
        val savedKind = when {
            draft != null -> "нормализованный draft"
            preview != null -> "валидированный preview"
            else -> "failure artifact"
        }
        emit("Повтор $repetition: $savedKind сохранён локально: ${path.toAbsolutePath().normalize()}.")
        return path
    }

    internal fun render(
        repetition: Int,
        outcome: ImportBenchmarkOutcome,
        draft: ImportBenchmarkDraftSnapshot?,
        generatedAt: String,
        preview: ImportBenchmarkPreviewSnapshot? = null,
    ): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("schemaVersion", 2)
            put("generatedAt", generatedAt)
            put("repetition", repetition)
            put("modelSelector", fixture.modelSelector)
            put("fixtureSha256", fixture.sha256)
            when (outcome) {
                is ImportBenchmarkOutcome.Scored -> put("status", "scored")
                is ImportBenchmarkOutcome.Failed -> {
                    put("status", "failed")
                    put("failureCode", outcome.failureCode.reportValue)
                }
            }
            if (draft == null) {
                put("draft", JsonNull)
            } else {
                put("draft", buildJsonObject {
                    put("status", draft.status)
                    put("version", draft.version)
                    put("rows", JsonObject(draft.rows))
                })
            }
            preview?.let {
                put("preview", buildJsonObject {
                    put("status", it.status)
                    put("confirmed", false)
                    put("rows", JsonObject(it.rows))
                })
            }
        },
    )

    companion object {
        private val json = Json { prettyPrint = true; explicitNulls = false }
        private val timestampFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'")
            .withZone(ZoneOffset.UTC)

        fun snapshot(
            fixture: ImportBenchmarkFixture,
            draft: ImportDraft,
            categories: List<TransactionCategory>,
        ): ImportBenchmarkDraftSnapshot {
            val labelsBySourceRef = fixture.modelInput.transactions.associate { transaction ->
                require(transaction.id.matches(Regex("TX\\d{2}"))) {
                    "Benchmark operation reference is not scrubbed."
                }
                transaction.id to transaction.id
            }
            val categoryNames = ImportBenchmarkScoring.activeExpenseLeafNamesById(categories)
            val categoryIdsByName = categoryNames.entries.associate { (id, name) -> name to id }
            val rows = draft.transactions.associate { row ->
                val sourceRef = row.transaction.sourceRef?.takeIf(String::isNotBlank)
                    ?: throw IllegalStateException("Benchmark draft row has no source reference.")
                val label = labelsBySourceRef[sourceRef]
                    ?: throw IllegalStateException("Benchmark draft row has an unreviewed source reference.")
                label to rowJson(fixture, row, label, categoryNames, categoryIdsByName)
            }
            require(rows.size == draft.transactions.size && rows.keys == labelsBySourceRef.values.toSet()) {
                "Benchmark draft does not contain the reviewed operation set."
            }
            return ImportBenchmarkDraftSnapshot(
                status = draft.status.name.lowercase(),
                version = draft.version,
                rows = rows.toSortedMap(),
            )
        }

        fun snapshotPreview(
            fixture: ImportBenchmarkFixture,
            preview: McpPreview,
            sourceRefs: List<String?>?,
            categories: List<TransactionCategory>,
        ): ImportBenchmarkPreviewSnapshot {
            val receiptMatching = preview.receiptMatching
                ?.takeIf { it.status == ReceiptMatchingStageStatus.SOURCE_ERROR }
                ?: throw IllegalStateException("Benchmark preview is not a receipt-source failure.")
            val expectedSourceRefs = fixture.modelInput.transactions.map { transaction ->
                require(transaction.id.matches(Regex("TX\\d{2}"))) {
                    "Benchmark operation reference is not scrubbed."
                }
                transaction.id
            }.toSet()
            requireValidBenchmarkPreview(
                preview = preview,
                draftIsPresent = false,
                expectedSourceRefs = expectedSourceRefs,
                previewSourceRefs = sourceRefs,
            )
            val labelsBySourceRef = expectedSourceRefs.associateWith { it }
            val categoryNames = ImportBenchmarkScoring.activeExpenseLeafNamesById(categories)
            val categoryIdsByName = categoryNames.entries.associate { (id, name) -> name to id }
            val refs = checkNotNull(sourceRefs)
            val rows = preview.transactions.mapIndexed { index, transaction ->
                val label = refs[index]?.let(labelsBySourceRef::get)
                    ?: throw IllegalStateException("Benchmark preview has an unreviewed source reference.")
                label to previewRowJson(
                    fixture,
                    transaction,
                    label,
                    categoryNames,
                    categoryIdsByName,
                )
            }.toMap()
            require(rows.size == preview.transactions.size && rows.keys == expectedSourceRefs) {
                "Benchmark preview does not contain the reviewed operation set."
            }
            return ImportBenchmarkPreviewSnapshot(
                status = receiptMatching.status.name.lowercase(),
                rows = rows.toSortedMap(),
            )
        }

        private fun rowJson(
            fixture: ImportBenchmarkFixture,
            row: DraftTransaction,
            label: String,
            categoryNames: Map<String, String>,
            categoryIdsByName: Map<String, String>,
        ): JsonObject {
            val transaction = row.transaction
            val actualCategory = transaction.categoryId?.let(categoryNames::get)
            val hasExpectedCategoryLabel = label in fixture.evaluationOnly.categories
            val expectedCategory = fixture.evaluationOnly.categories[label]
            val expectedCategoryId = expectedCategory?.let(categoryIdsByName::get)
            val categoryMatches = when {
                expectedCategory == null -> transaction.categoryId == null
                expectedCategoryId == null -> false
                else -> transaction.categoryId == expectedCategoryId
            }
            val categoryComparison = if (hasExpectedCategoryLabel) {
                comparison(categoryMatches)
            } else {
                "unlabeled"
            }
            val expectedReceipt = fixture.evaluationOnly.receiptAssociations.getValue(label)
            val actualReceipt = transaction.receiptAssociation
            val receiptMatches = ImportBenchmarkScoring.receiptLabelMatchesForArtifact(
                fixture,
                actualReceipt,
                expectedReceipt,
            )
            return buildJsonObject {
                put("actual", buildJsonObject {
                    put("included", row.included)
                    put("description", row.description)
                    putJsonArray("fieldErrorFields") {
                        row.fieldErrors.keys.map(::safeFieldName).distinct().sorted()
                            .forEach { add(JsonPrimitive(it)) }
                    }
                    put("sourceIndex", transaction.sourceIndex)
                    put("direction", transaction.direction.name.lowercase())
                    put("occurredAt", transaction.occurredAt)
                    putNullable("postedAt", transaction.postedAt)
                    put("amountMinor", transaction.amountMinor)
                    put("currency", transaction.currency)
                    put("merchant", transaction.merchant)
                    putNullable("sourceLabel", transaction.sourceLabel)
                    putNullable("categoryId", transaction.categoryId)
                    putNullable("category", actualCategory)
                    put("needsReview", transaction.needsReview)
                    putJsonArray("issueFields") {
                        transaction.issues.map(::safeFieldName).distinct().sorted()
                            .forEach { add(JsonPrimitive(it)) }
                    }
                    putJsonArray("items") {
                        transaction.items.forEach { item ->
                            add(buildJsonObject {
                                put("name", item.name)
                                put("quantity", item.quantity)
                                put("priceMinor", item.priceMinor)
                                put("sumMinor", item.sumMinor)
                                put("currency", item.currency)
                            })
                        }
                    }
                    put("receiptAssociation", receiptAssociationJson(actualReceipt) ?: JsonNull)
                })
                put("expected", buildJsonObject {
                    put("selection", fixture.evaluationOnly.selection.getValue(label))
                    if (hasExpectedCategoryLabel) {
                        put("category", expectedCategory?.let(::JsonPrimitive) ?: JsonNull)
                    } else {
                        put("category", buildJsonObject { put("state", "unlabeled") })
                    }
                    put("receiptAssociation", expectedReceiptJson(fixture, expectedReceipt))
                })
                put("comparisons", buildJsonObject {
                    put(
                        "selection",
                        comparison(row.included == fixture.evaluationOnly.selection.getValue(label)),
                    )
                    put(
                        "category",
                        categoryComparison,
                    )
                    put("receiptAssociation", comparison(receiptMatches))
                })
            }
        }

        private fun previewRowJson(
            fixture: ImportBenchmarkFixture,
            row: McpPreviewTransaction,
            label: String,
            categoryNames: Map<String, String>,
            categoryIdsByName: Map<String, String>,
        ): JsonObject {
            val actualCategory = row.categoryId?.let(categoryNames::get)
            val hasExpectedCategoryLabel = label in fixture.evaluationOnly.categories
            val expectedCategory = fixture.evaluationOnly.categories[label]
            val expectedCategoryId = expectedCategory?.let(categoryIdsByName::get)
            val categoryMatches = when {
                expectedCategory == null -> row.categoryId == null
                expectedCategoryId == null -> false
                else -> row.categoryId == expectedCategoryId
            }
            val categoryComparison = if (hasExpectedCategoryLabel) {
                comparison(categoryMatches)
            } else {
                "unlabeled"
            }
            val expectedReceipt = fixture.evaluationOnly.receiptAssociations.getValue(label)
            return buildJsonObject {
                put("actual", buildJsonObject {
                    put("description", row.description)
                    putNullable("direction", row.direction?.name?.lowercase())
                    put("occurredAt", row.occurredAt)
                    put("amountMinor", row.amountMinor)
                    put("currency", row.currency)
                    put("merchant", row.merchant)
                    putNullable("sourceLabel", row.sourceLabel)
                    putNullable("categoryId", row.categoryId)
                    putNullable("category", actualCategory)
                    put("categoryIssuePresent", row.categoryIssue != null)
                    putJsonArray("items") {
                        row.items.forEach { item ->
                            add(buildJsonObject {
                                put("name", item.name)
                                put("quantity", item.quantity)
                                put("priceMinor", item.priceMinor)
                                put("sumMinor", item.sumMinor)
                                put("currency", item.currency)
                            })
                        }
                    }
                    put("receiptAssociation", receiptAssociationJson(row.receiptAssociation) ?: JsonNull)
                })
                put("expected", buildJsonObject {
                    put("selection", fixture.evaluationOnly.selection.getValue(label))
                    if (hasExpectedCategoryLabel) {
                        put("category", expectedCategory?.let(::JsonPrimitive) ?: JsonNull)
                    } else {
                        put("category", buildJsonObject { put("state", "unlabeled") })
                    }
                    put("receiptAssociation", expectedReceiptJson(fixture, expectedReceipt))
                })
                put("comparisons", buildJsonObject {
                    put("selection", "not_finalized")
                    put("category", categoryComparison)
                    put("receiptAssociation", "not_scored")
                })
            }
        }

        private fun expectedReceiptJson(
            fixture: ImportBenchmarkFixture,
            label: BenchmarkReceiptLabel,
        ): JsonObject = when (label.expectation) {
            BenchmarkReceiptExpectation.NO_RECEIPT -> buildJsonObject {
                put("status", "unmatched")
            }
            BenchmarkReceiptExpectation.MATCHED -> buildJsonObject {
                put("status", "matched")
                put("summary", safeExpectedReceipt(fixture, label.receiptIds.single()))
            }
            BenchmarkReceiptExpectation.AMBIGUOUS -> buildJsonObject {
                put("status", "ambiguous")
                put("candidates", buildJsonArray {
                    label.receiptIds.map { safeExpectedReceipt(fixture, it) }
                        .sortedBy { it.getValue("merchant").jsonPrimitive.content }
                        .forEach(::add)
                })
            }
        }

        private fun safeExpectedReceipt(
            fixture: ImportBenchmarkFixture,
            id: String,
        ): JsonObject {
            val receipt = fixture.modelInput.receiptSummaries.singleOrNull { it.id == id }
                ?: throw IllegalStateException("Benchmark receipt label refers to an unavailable summary.")
            val detail = fixture.modelInput.receiptDetails[id]
            val dateTime = detail?.get("date_time")?.jsonPrimitive?.contentOrNull
                ?.takeIf(String::isNotBlank)
            val date = dateTime?.substringBefore('T') ?: receipt.receivedAt.take(10)
            val merchant = detail?.get("merchant")?.jsonPrimitive?.contentOrNull
                ?.trim()?.takeIf(String::isNotBlank) ?: receipt.merchant
            val amount = detail?.get("total_minor")?.jsonPrimitive?.longOrNull ?: receipt.amountMinor
            val currency = detail?.get("currency")?.jsonPrimitive?.contentOrNull
                ?.trim()?.uppercase() ?: receipt.currency.uppercase()
            return buildJsonObject {
                put("date", date)
                put("merchant", merchant)
                put("amountMinor", amount)
                put("currency", currency)
                putNullable("dateTime", dateTime)
                detail?.get("settlement_place")?.jsonPrimitive?.contentOrNull
                    ?.takeIf(String::isNotBlank)?.let { put("settlementPlace", it) }
            }
        }

        private fun receiptAssociationJson(association: ReceiptAssociation?): JsonObject? = association?.let {
            buildJsonObject {
                put("status", it.status.name.lowercase())
                put("summary", it.summary?.let(::receiptSummaryJson) ?: JsonNull)
                it.review?.let { review ->
                    put("reviewReason", review.reason.name.lowercase())
                    putJsonArray("candidates") {
                        review.candidates.map(::receiptSummaryJson)
                            .sortedBy { summary -> summary.getValue("merchant").jsonPrimitive.content }
                            .forEach(::add)
                    }
                }
            }
        }

        private fun receiptSummaryJson(summary: ReceiptAssociationSummary): JsonObject = buildJsonObject {
            put("date", summary.date)
            put("merchant", summary.merchant)
            put("amountMinor", summary.amountMinor)
            put("currency", summary.currency)
            putNullable("dateTime", summary.dateTime)
            putNullable("settlementPlace", summary.settlementPlace)
        }

        private fun safeFieldName(issue: String): String {
            val field = issue.substringBefore(':').trim()
            return field.takeIf { it in SAFE_DRAFT_FIELDS } ?: "_transaction"
        }

        private fun comparison(matches: Boolean): String = if (matches) "matched" else "mismatched"

        private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: String?) {
            put(key, value?.let(::JsonPrimitive) ?: JsonNull)
        }

        private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonArray(
            key: String,
            builder: kotlinx.serialization.json.JsonArrayBuilder.() -> Unit,
        ) {
            put(key, buildJsonArray(builder))
        }

        private val SAFE_DRAFT_FIELDS = setOf(
            "direction",
            "occurred_at",
            "posted_at",
            "amount_minor",
            "currency",
            "merchant",
            "category_id",
        )

        private fun defaultFileName(repetition: Int): String =
            "draft-${timestampFormat.format(Instant.now())}-repeat-${repetition.toString().padStart(2, '0')}-${UUID.randomUUID()}.json"
    }
}

private val JsonPrimitive.contentOrNull: String?
    get() = if (isString) content else null

private val JsonPrimitive.longOrNull: Long?
    get() = content.toLongOrNull()
