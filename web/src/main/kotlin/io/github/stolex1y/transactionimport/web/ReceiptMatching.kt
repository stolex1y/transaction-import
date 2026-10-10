package io.github.stolex1y.transactionimport.web

import io.github.stolex1y.transactionimport.core.AgentConfig
import io.github.stolex1y.transactionimport.core.ExternalTransactionCandidate
import io.github.stolex1y.transactionimport.core.ReceiptAssociation
import io.github.stolex1y.transactionimport.core.ReceiptAssociationStatus
import io.github.stolex1y.transactionimport.core.ReceiptAssociationSummary
import io.github.stolex1y.transactionimport.core.ReceiptReviewEvidence
import io.github.stolex1y.transactionimport.core.ReceiptReviewReason
import io.github.stolex1y.transactionimport.core.SchedulerTraceEvent
import io.github.stolex1y.transactionimport.core.TransactionDirection
import io.github.stolex1y.transactionimport.core.TransactionItem
import io.github.stolex1y.transactionimport.core.normalizeCurrencyCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs

private const val RECEIPTS_SERVER_ID = "receipts"
private const val RECEIPTS_SEARCH_TOOL = "search-receipts"
private const val RECEIPTS_DETAIL_TOOL = "get-receipt"
private const val PAGE_SIZE = 20
private const val MAX_PAGES = 100
private const val MAX_TOTAL_CANDIDATES = 2_000
private const val MAX_RECEIPT_ITEMS = 200
private const val MAX_SOURCE_RESPONSE_CHARS = 1_000_000
private const val MAX_REVIEW_CANDIDATES = 5
private const val SOURCE_CALL_TIMEOUT_MS = 30_000L
private const val MATCH_CONFIDENCE_THRESHOLD = 0.75
private val CURRENCY_PATTERN = Regex("^[A-Z]{3}$")
private val RECEIPT_REFERENCE_PATTERN = Regex(
    "(?iu)(?<![\\p{L}\\p{N}_])(?:receipt|чек)[-_][A-Za-z0-9][A-Za-z0-9_-]{0,199}(?![\\p{L}\\p{N}_])",
)
private val SENSITIVE_RECEIPT_VALUE_PATTERN = Regex(
    "(?iu)(?<![\\p{L}\\p{N}_])(?:receipt[_ -]?key|fiscal[_ -]?(?:drive|document|sign|id)|" +
        "(?:(?:фн|фд|фп)(?=\\s*[:=#№]?\\s*\\p{Nd})|фискальн(?:ый|ого)?[_ -]?(?:накопитель|документ|признак|идентификатор))|" +
        "qr[_ -]?(?:code|код)?|кассир|cashier|buyer|покупател(?:ь|я|ю|ем|е))(?![\\p{L}\\p{N}_])\\s*[:=#№]?\\s*[^,;\\n]+",
)


private enum class ReceiptEligibility {
    PURCHASE,
    REFUND,
    BANK_DIRECTION_UNKNOWN,
    NOT_A_PURCHASE,
}

private data class ReceiptSummaryCandidate(
    val key: String,
    val merchant: String,
    val date: LocalDate,
    val amountMinor: Long,
    val currency: String,
    val alias: String,
)

private data class DatedReceiptOperation(
    val transaction: ExternalTransactionCandidate,
    val date: LocalDate,
    val eligibility: ReceiptEligibility,
)

private fun ReceiptSummaryCandidate.safeSummary() = ReceiptAssociationSummary(
    date = date.toString(),
    merchant = merchant,
    amountMinor = amountMinor,
    currency = currency,
    privacyVersion = ReceiptAssociationSummary.CURRENT_PRIVACY_VERSION,
)

private data class ReceiptDetailCandidate(
    val summary: ReceiptSummaryCandidate,
    val merchant: String,
    val date: LocalDate,
    val dateTime: String,
    val amountMinor: Long,
    val currency: String,
    val operationType: Int?,
    val settlementPlace: String?,
    val items: List<TransactionItem>,
) {
    fun safeSummary() = ReceiptAssociationSummary(
        date = date.toString(),
        merchant = merchant,
        amountMinor = amountMinor,
        currency = currency,
        dateTime = dateTime,
        settlementPlace = settlementPlace,
        privacyVersion = ReceiptAssociationSummary.CURRENT_PRIVACY_VERSION,
    )
}

data class ReceiptMatchingRun(
    val transactions: List<ExternalTransactionCandidate>,
    val receiptCandidateCount: Int,
    val receiptDetailCount: Int,
)

class ReceiptMatchingEngine(
    private val mcpTools: McpToolProvider,
    private val selector: ReceiptMatchSelector,
) {
    suspend fun match(
        transactions: List<ExternalTransactionCandidate>,
        config: AgentConfig,
        explicitInstruction: String = "",
        confirmedReceiptRules: List<String> = emptyList(),
        trace: MutableList<SchedulerTraceEvent>? = null,
        strictSelector: Boolean = false,
        selectedCandidates: Map<String, ReceiptAssociationSummary> = emptyMap(),
        allowSelectorChoice: Boolean = true,
        autoLinkUniqueReceipt: Boolean = false,
    ): ReceiptMatchingRun {
        if (transactions.isEmpty()) return ReceiptMatchingRun(emptyList(), 0, 0)
        val datedTransactions = transactions.mapNotNull { transaction ->
            val eligibility = transaction.receiptEligibility()
            if (eligibility != ReceiptEligibility.PURCHASE && eligibility != ReceiptEligibility.REFUND) {
                null
            } else {
                DatedReceiptOperation(
                    transaction = transaction,
                    date = parseDate(transaction.occurredAt),
                    eligibility = eligibility,
                )
            }
        }
        val periodFrom = datedTransactions.minOfOrNull { operation ->
            operation.date.minusDays(if (operation.eligibility == ReceiptEligibility.REFUND) 14 else 1)
        }
        val periodTo = datedTransactions.maxOfOrNull { operation ->
            operation.date.plusDays(if (operation.eligibility == ReceiptEligibility.PURCHASE) 1 else 0)
        }
        val summaries = if (periodFrom == null || periodTo == null) {
            emptyList()
        } else {
            try {
                searchPeriod(periodFrom, periodTo, trace)
            } catch (error: CancellationException) {
                throw error
            } catch (error: ReceiptMatchingSourceException) {
                throw error
            } catch (_: Throwable) {
                throw ReceiptMatchingSourceException("Источник receipts/search-receipts вернул некорректные данные.")
            }
        }
        val summaryCandidates = summaries.mapIndexed { index, summary ->
            summary.copy(alias = "receipt-${index + 1}")
        }
        val summariesByTransaction = datedTransactions.associate { operation ->
            val transaction = operation.transaction
            transaction.sourceRef.orEmpty() to summaryCandidates.filter { summary ->
                isEligible(
                    eligibility = operation.eligibility,
                    operationDate = operation.date,
                    operationAmount = transaction.amountMinor,
                    operationCurrency = transaction.currency,
                    receiptDate = summary.date,
                    receiptAmount = summary.amountMinor,
                    receiptCurrency = summary.currency,
                )
            }
        }
        val matchableRefs = datedTransactions.mapTo(hashSetOf()) {
            it.transaction.sourceRef.orEmpty()
        }
        val wantedKeys = summariesByTransaction
            .filterKeys { it in matchableRefs }
            .values.flatten().map { it.key }.toSet()
        val detailCache = linkedMapOf<String, ReceiptDetailCandidate>()
        val detailFailures = mutableSetOf<String>()
        for (summary in summaryCandidates) {
            if (summary.key !in wantedKeys || summary.key in detailCache || summary.key in detailFailures) {
                continue
            }
            try {
                val response = sourceCall(
                    tool = RECEIPTS_DETAIL_TOOL,
                    arguments = buildJsonObject { put("receipt_key", summary.key) },
                    trace = trace,
                    stage = "receipts-details",
                )
                detailCache[summary.key] = parseDetail(response, summary)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                detailFailures += summary.key
            }
        }
        val eligibleByTransaction = linkedMapOf<String, List<ReceiptDetailCandidate>>()
        val detailReviewsByTransaction = mutableMapOf<String, ReceiptReviewEvidence>()
        val detailIssuesByTransaction = mutableMapOf<String, String>()
        for (operation in datedTransactions) {
            val transaction = operation.transaction
            val id = transaction.sourceRef.orEmpty()
            val rowSummaries = summariesByTransaction[id].orEmpty()
            val details = rowSummaries.mapNotNull { detailCache[it.key] }
            val dateAndAmountCompatible = details.filter { detail ->
                isEligible(
                    eligibility = operation.eligibility,
                    operationDate = operation.date,
                    operationAmount = transaction.amountMinor,
                    operationCurrency = transaction.currency,
                    receiptDate = detail.date,
                    receiptAmount = detail.amountMinor,
                    receiptCurrency = detail.currency,
                )
            }
            val hasUnavailableDetail = rowSummaries.any {
                it.key in detailFailures || it.key !in detailCache
            }
            val hasInconsistentDetail = details.size != dateAndAmountCompatible.size
            val expectedType = if (operation.eligibility == ReceiptEligibility.REFUND) 2 else 1
            val unknownTypeDetails = dateAndAmountCompatible.filter {
                it.operationType != 1 && it.operationType != 2
            }
            val incompatibleTypeDetails = dateAndAmountCompatible.filter {
                (it.operationType == 1 || it.operationType == 2) && it.operationType != expectedType
            }
            val compatible = dateAndAmountCompatible.filter { it.operationType == expectedType }
            eligibleByTransaction[id] = compatible
            val reason = when {
                hasUnavailableDetail || hasInconsistentDetail ->
                    ReceiptReviewReason.RECEIPT_DETAIL_UNAVAILABLE
                unknownTypeDetails.isNotEmpty() ->
                    ReceiptReviewReason.RECEIPT_OPERATION_TYPE_UNKNOWN
                compatible.isEmpty() && incompatibleTypeDetails.isNotEmpty() ->
                    ReceiptReviewReason.RECEIPT_OPERATION_TYPE_INCOMPATIBLE
                else -> null
            }
            if (reason != null) {
                val summariesForReview = rowSummaries
                    .map { detailCache[it.key]?.safeSummary() ?: it.safeSummary() }
                    .distinct()
                    .take(MAX_REVIEW_CANDIDATES)
                detailReviewsByTransaction[id] = ReceiptReviewEvidence(
                    reason = reason,
                    candidates = summariesForReview,
                )
                detailIssuesByTransaction[id] = when (reason) {
                    ReceiptReviewReason.RECEIPT_DETAIL_UNAVAILABLE ->
                        "receipt_detail_unavailable: детали чека не удалось безопасно проверить"
                    ReceiptReviewReason.RECEIPT_OPERATION_TYPE_UNKNOWN ->
                        "receipt_operation_type_unknown: тип операции в чеке отсутствует или неизвестен"
                    ReceiptReviewReason.RECEIPT_OPERATION_TYPE_INCOMPATIBLE ->
                        "receipt_operation_type_incompatible: тип чека не соответствует банковской операции"
                    else -> error("Unexpected receipt detail review reason: $reason")
                }
            }
        }
        val ownershipCounts = eligibleByTransaction
            .filterKeys { it in matchableRefs }
            .values.flatten()
            .groupingBy { it.summary.key }
            .eachCount()
        val safeReceiptRules = confirmedReceiptRules
            .map(::sanitizeMatchingText)
            .filter(String::isNotBlank)
        val safeInstruction = sanitizeReceiptMatchingInstruction(explicitInstruction)
        data class MatchDecision(
            val transaction: ExternalTransactionCandidate,
            val status: ReceiptAssociationStatus,
            val selected: ReceiptDetailCandidate? = null,
            val issue: String? = null,
            val review: ReceiptReviewEvidence? = null,
        )
        val decisions = transactions.map { transaction ->
            when (transaction.receiptEligibility()) {
                ReceiptEligibility.NOT_A_PURCHASE ->
                    MatchDecision(transaction, ReceiptAssociationStatus.UNMATCHED)
                ReceiptEligibility.BANK_DIRECTION_UNKNOWN ->
                    MatchDecision(
                        transaction = transaction,
                        status = ReceiptAssociationStatus.AMBIGUOUS,
                        issue = "receipt_direction_unknown: направление операции нужно подтвердить",
                        review = ReceiptReviewEvidence(ReceiptReviewReason.BANK_DIRECTION_UNKNOWN),
                    )
                ReceiptEligibility.PURCHASE, ReceiptEligibility.REFUND -> {
                    val id = transaction.sourceRef.orEmpty()
                    detailReviewsByTransaction[id]?.let { review ->
                        return@map MatchDecision(
                            transaction = transaction,
                            status = ReceiptAssociationStatus.AMBIGUOUS,
                            issue = detailIssuesByTransaction[id],
                            review = review,
                        )
                    }
                    val local = eligibleByTransaction[id].orEmpty()
                    if (local.isEmpty()) return@map MatchDecision(transaction, ReceiptAssociationStatus.UNMATCHED)
                    selectedCandidates[id]?.let { requested ->
                        val selected = local.singleOrNull { it.safeSummary() == requested }
                        if (selected != null && ownershipCounts[selected.summary.key] == 1) {
                            return@map MatchDecision(transaction, ReceiptAssociationStatus.MATCHED, selected)
                        }
                        val shared = selected != null && ownershipCounts[selected.summary.key] != 1
                        return@map MatchDecision(
                            transaction = transaction,
                            status = ReceiptAssociationStatus.AMBIGUOUS,
                            issue = if (shared) {
                                "receipt_ambiguous: чек подходит нескольким операциям"
                            } else {
                                "receipt_ambiguous: выбранный чек больше не подходит однозначно"
                            },
                            review = ReceiptReviewEvidence(
                                reason = if (shared) {
                                    ReceiptReviewReason.SHARED_BY_OPERATIONS
                                } else {
                                    ReceiptReviewReason.SELECTOR_UNCERTAIN
                                },
                                candidates = local.map(ReceiptDetailCandidate::safeSummary)
                                    .distinct()
                                    .take(MAX_REVIEW_CANDIDATES),
                            ),
                        )
                    }
                    val uniqueReceipt = local.singleOrNull()
                        ?.takeIf { ownershipCounts[it.summary.key] == 1 }
                    if (
                        autoLinkUniqueReceipt &&
                        !strictSelector &&
                        safeInstruction.isBlank() &&
                        safeReceiptRules.isEmpty() &&
                        uniqueReceipt != null
                    ) {
                        return@map MatchDecision(
                            transaction,
                            ReceiptAssociationStatus.MATCHED,
                            uniqueReceipt,
                        )
                    }
                    val hasExplicitSelectionContext = strictSelector ||
                        safeInstruction.isNotBlank() ||
                        safeReceiptRules.isNotEmpty()
                    if (transaction.categoryId.isNullOrBlank() || transaction.categoryIssue != null) {
                        val unique = local.singleOrNull()
                            ?.takeIf { ownershipCounts[it.summary.key] == 1 }
                        if (unique != null) {
                            if (!hasExplicitSelectionContext) {
                                return@map MatchDecision(transaction, ReceiptAssociationStatus.MATCHED, unique)
                            }
                        } else {
                            val shared = local.size == 1
                            return@map MatchDecision(
                                transaction = transaction,
                                status = ReceiptAssociationStatus.AMBIGUOUS,
                                issue = if (shared) {
                                    "receipt_ambiguous: чек подходит нескольким операциям"
                                } else {
                                    "receipt_ambiguous: несколько подходящих чеков требуют уточнения"
                                },
                                review = ReceiptReviewEvidence(
                                    reason = if (shared) {
                                        ReceiptReviewReason.SHARED_BY_OPERATIONS
                                    } else {
                                        ReceiptReviewReason.MULTIPLE_CANDIDATES
                                    },
                                    candidates = local.map(ReceiptDetailCandidate::safeSummary)
                                        .distinct()
                                        .take(MAX_REVIEW_CANDIDATES),
                                ),
                            )
                        }
                    }
                    if (!allowSelectorChoice && local.size > 1) {
                        return@map MatchDecision(
                            transaction = transaction,
                            status = ReceiptAssociationStatus.AMBIGUOUS,
                            issue = "receipt_ambiguous: несколько подходящих чеков требуют уточнения",
                            review = ReceiptReviewEvidence(
                                reason = ReceiptReviewReason.MULTIPLE_CANDIDATES,
                                candidates = local.map(ReceiptDetailCandidate::safeSummary)
                                    .distinct()
                                    .take(MAX_REVIEW_CANDIDATES),
                            ),
                        )
                    }
                    val globallyUnique = local.filter { ownershipCounts[it.summary.key] == 1 }
                    val selectionCandidates = if (hasExplicitSelectionContext || local.size > 1) {
                        local
                    } else {
                        globallyUnique
                    }
                    if (selectionCandidates.isEmpty()) {
                        return@map MatchDecision(
                            transaction = transaction,
                            status = ReceiptAssociationStatus.AMBIGUOUS,
                            issue = "receipt_ambiguous: чек подходит нескольким операциям",
                            review = ReceiptReviewEvidence(
                                reason = ReceiptReviewReason.SHARED_BY_OPERATIONS,
                                candidates = local.map(ReceiptDetailCandidate::safeSummary)
                                    .distinct()
                                    .take(MAX_REVIEW_CANDIDATES),
                            ),
                        )
                    }
                    if (selectionCandidates.size > 1 &&
                        !allowSelectorChoice &&
                        safeInstruction.isBlank() &&
                        safeReceiptRules.isEmpty()
                    ) {
                        return@map MatchDecision(
                            transaction = transaction,
                            status = ReceiptAssociationStatus.AMBIGUOUS,
                            issue = "receipt_ambiguous: несколько подходящих чеков требуют уточнения",
                            review = ReceiptReviewEvidence(
                                reason = ReceiptReviewReason.MULTIPLE_CANDIDATES,
                                candidates = selectionCandidates.map(ReceiptDetailCandidate::safeSummary)
                                    .distinct()
                                    .take(MAX_REVIEW_CANDIDATES),
                            ),
                        )
                    }
                    val useSelector = strictSelector ||
                        selectionCandidates.size > 1 ||
                        safeInstruction.isNotBlank() ||
                        safeReceiptRules.isNotEmpty()
                    val selected = if (!useSelector) {
                        selectionCandidates.single()
                    } else {
                        val proposal = try {
                            selector.select(
                                config = config,
                                transaction = transaction,
                                choices = selectionCandidates.map { it.toChoice() },
                                explicitInstruction = safeInstruction,
                                confirmedReceiptRules = safeReceiptRules,
                            )
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Throwable) {
                            if (strictSelector) throw ReceiptMatchingSelectionException()
                            null
                        }
                        val validProposal = proposal?.let {
                            val aliases = selectionCandidates.mapTo(hashSetOf()) { candidate -> candidate.summary.alias }
                            val validConfidence = it.confidence.isFinite() && it.confidence in 0.0..1.0
                            val validAlias = it.receiptAlias == null || it.receiptAlias in aliases
                            val abstention = it.receiptAlias == null &&
                                it.confidence >= MATCH_CONFIDENCE_THRESHOLD
                            val validNotTarget = !it.notTarget ||
                                (it.receiptAlias == null && it.confidence == 0.0)
                            if (!validConfidence || !validAlias || abstention || !validNotTarget) {
                                if (strictSelector) throw ReceiptMatchingSelectionException()
                                null
                            } else {
                                it
                            }
                        }
                        if (strictSelector && validProposal == null) throw ReceiptMatchingSelectionException()
                        if (validProposal?.notTarget == true) {
                            return@map MatchDecision(transaction, ReceiptAssociationStatus.UNMATCHED)
                        }
                        validProposal
                            ?.takeIf { it.confidence >= MATCH_CONFIDENCE_THRESHOLD }
                            ?.receiptAlias
                            ?.let { alias -> selectionCandidates.singleOrNull { it.summary.alias == alias } }
                            ?.takeIf { hasExplicitSelectionContext || ownershipCounts[it.summary.key] == 1 }
                    }
                    if (selected == null) {
                        MatchDecision(
                            transaction = transaction,
                            status = ReceiptAssociationStatus.AMBIGUOUS,
                            issue = if (selectionCandidates.size > 1) {
                                "receipt_ambiguous: несколько подходящих чеков требуют уточнения"
                            } else {
                                "receipt_ambiguous: не удалось уверенно выбрать чек"
                            },
                            review = ReceiptReviewEvidence(
                                reason = if (selectionCandidates.size > 1) {
                                    ReceiptReviewReason.MULTIPLE_CANDIDATES
                                } else {
                                    ReceiptReviewReason.SELECTOR_UNCERTAIN
                                },
                                candidates = selectionCandidates
                                    .map(ReceiptDetailCandidate::safeSummary)
                                    .distinct()
                                    .take(MAX_REVIEW_CANDIDATES),
                            ),
                        )
                    } else {
                        MatchDecision(transaction, ReceiptAssociationStatus.MATCHED, selected)
                    }
                }
            }
        }
        val selectionCounts = decisions.mapNotNull { it.selected?.summary?.key }
            .groupingBy { it }
            .eachCount()
        val enriched = decisions.map { decision ->
            val transaction = decision.transaction.copy(
                issues = decision.transaction.issues.filterNot { it.startsWith("receipt_") },
            )
            val selected = decision.selected
            when {
                decision.status == ReceiptAssociationStatus.UNMATCHED -> {
                    trace?.add(SchedulerTraceEvent(stage = "match-proposal", status = "unmatched"))
                    transaction.copy(
                        items = emptyList(),
                        receiptAssociation = ReceiptAssociation(ReceiptAssociationStatus.UNMATCHED),
                    )
                }
                decision.status == ReceiptAssociationStatus.MATCHED &&
                    selected != null &&
                    selectionCounts[selected.summary.key] == 1 -> {
                    trace?.add(SchedulerTraceEvent(stage = "match-proposal", status = "matched"))
                    transaction.copy(
                        items = selected.items,
                        receiptAssociation = ReceiptAssociation(
                            status = ReceiptAssociationStatus.MATCHED,
                            summary = selected.safeSummary(),
                        ),
                    )
                }
                else -> {
                    trace?.add(SchedulerTraceEvent(stage = "match-proposal", status = "ambiguous"))
                    val issue = decision.issue ?: if (selected != null) {
                        "receipt_ambiguous: один чек выбран для нескольких операций"
                    } else {
                        "receipt_ambiguous: несколько подходящих чеков требуют уточнения"
                    }
                    val review = decision.review ?: ReceiptReviewEvidence(
                        reason = if (selected != null && selectionCounts[selected.summary.key] != 1) {
                            ReceiptReviewReason.DUPLICATE_SELECTION
                        } else {
                            ReceiptReviewReason.SELECTOR_UNCERTAIN
                        },
                        candidates = listOfNotNull(selected?.safeSummary())
                            .take(MAX_REVIEW_CANDIDATES),
                    )
                    transaction.copy(
                        issues = transaction.issues + issue,
                        items = emptyList(),
                        receiptAssociation = ReceiptAssociation(
                            status = ReceiptAssociationStatus.AMBIGUOUS,
                            review = review,
                        ),
                    )
                }
            }
        }
        return ReceiptMatchingRun(
            transactions = enriched,
            receiptCandidateCount = summaries.size,
            receiptDetailCount = detailCache.size,
        )
    }

    private suspend fun searchPeriod(
        from: LocalDate,
        to: LocalDate,
        trace: MutableList<SchedulerTraceEvent>?,
    ): List<ReceiptSummaryCandidate> {
        val result = mutableListOf<ReceiptSummaryCandidate>()
        val keys = mutableSetOf<String>()
        var offset = 0
        var complete = false
        for (page in 0 until MAX_PAGES) {
            val element = sourceCall(
                tool = RECEIPTS_SEARCH_TOOL,
                arguments = buildJsonObject {
                    put("from", from.toString())
                    put("to", to.toString())
                    put("limit", PAGE_SIZE)
                    put("offset", offset)
                },
                trace = trace,
                stage = "receipts-candidates",
            )
            val root = element as? JsonObject
                ?: throw IllegalStateException("Источник чеков вернул некорректную страницу.")
            val receipts = root["receipts"] as? JsonArray
                ?: throw IllegalStateException("Источник чеков вернул ответ без списка чеков.")
            require(receipts.size <= PAGE_SIZE) {
                "Источник чеков вернул страницу больше запрошенного лимита."
            }
            val hasMore = root["has_more"]?.jsonPrimitive?.booleanOrNull
                ?: throw IllegalStateException("Источник чеков не подтвердил полноту страницы.")
            val parsed = receipts.mapIndexed { index, elementValue ->
                parseSummary(elementValue, offset + index)
            }
            require(result.size + parsed.size <= MAX_TOTAL_CANDIDATES) {
                "Источник чеков превысил общий лимит кандидатов; результаты неполные."
            }
            parsed.forEach { summary ->
                if (keys.add(summary.key)) result += summary
            }
            if (!hasMore) {
                complete = true
                break
            }
            require(parsed.isNotEmpty()) {
                "Источник чеков сообщил о следующей странице без результатов; поиск остановлен как неполный."
            }
            offset += parsed.size
        }
        require(complete) {
            "Источник чеков превысил лимит страниц scheduler; результаты неполные."
        }
        return result
    }

    private fun parseSummary(element: JsonElement, index: Int): ReceiptSummaryCandidate {
        val value = element as? JsonObject
            ?: throw IllegalStateException("Источник чеков вернул некорректный чек #${index + 1}.")
        val key = value["receipt_key"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("Источник чеков вернул чек без ключа.")
        require(key.length <= 200) { "Источник чеков вернул слишком длинный ключ чека." }
        val amount = value["amount_minor"]?.jsonPrimitive?.longOrNull
            ?.takeIf { it >= 0L }
            ?: throw IllegalStateException("Источник чеков вернул некорректную сумму чека.")
        val currency = value["currency"]?.jsonPrimitive?.contentOrNull
            ?.trim()?.takeIf(String::isNotBlank)?.let(::normalizeCurrencyCode)
            ?: throw IllegalStateException("Источник чеков вернул чек без валюты.")
        require(CURRENCY_PATTERN.matches(currency)) { "Источник чеков вернул некорректную валюту чека." }
        val receivedAt = value["received_at"]?.jsonPrimitive?.contentOrNull
            ?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("Источник чеков вернул чек без даты.")
        require(receivedAt.length <= 80) { "Источник чеков вернул слишком длинную дату чека." }
        return ReceiptSummaryCandidate(
            key = key,
            merchant = sanitizeMatchingText(
                value["merchant"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                receiptKey = key,
            ).take(200),
            date = parseDate(receivedAt),
            amountMinor = amount,
            currency = currency,
            alias = "receipt-${index + 1}",
        )
    }

    private fun parseDetail(element: JsonElement, summary: ReceiptSummaryCandidate): ReceiptDetailCandidate {
        val root = element as? JsonObject
            ?: throw IllegalStateException("Источник чеков вернул некорректные детали.")

        val operationType = (root["operation_type"] as? JsonPrimitive)
            ?.takeUnless { it.isString }
            ?.longOrNull
            ?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
            ?.toInt()
        val currency = normalizeCurrencyCode(
            root["currency"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
                ?: summary.currency,
        )
        require(CURRENCY_PATTERN.matches(currency)) { "Источник чеков вернул некорректную валюту чека." }
        val rawItems = root["items"] as? JsonArray
            ?: throw IllegalStateException("Источник чеков вернул чек без списка позиций.")
        require(rawItems.size in 1..MAX_RECEIPT_ITEMS) {
            "Источник чеков вернул некорректное количество позиций."
        }
        val items = rawItems.mapIndexed { index, elementValue ->
            val item = elementValue as? JsonObject
                ?: throw IllegalStateException("Источник чеков вернул некорректную позицию #${index + 1}.")
            val name = item["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
                ?: throw IllegalStateException("Источник чеков вернул позицию без названия.")
            require(name.length <= 200) { "Источник чеков вернул слишком длинное название позиции." }
            val quantity = item["quantity"]?.jsonPrimitive?.doubleOrNull
                ?.takeIf { it.isFinite() && it > 0.0 && it <= 100_000.0 }
                ?: throw IllegalStateException("Источник чеков вернул некорректное количество позиции.")
            val priceMinor = item["price_minor"]?.jsonPrimitive?.longOrNull
                ?.takeIf { it >= 0L }
                ?: throw IllegalStateException("Источник чеков вернул некорректную цену позиции.")
            val sumMinor = item["sum_minor"]?.jsonPrimitive?.longOrNull
                ?.takeIf { it >= 0L }
                ?: throw IllegalStateException("Источник чеков вернул некорректную сумму позиции.")
            TransactionItem(
                name = sanitizeMatchingText(name, receiptKey = summary.key).take(200),
                quantity = quantity,
                priceMinor = priceMinor,
                sumMinor = sumMinor,
                currency = currency,
            )
        }
        val amount = root["total_minor"]?.jsonPrimitive?.longOrNull
            ?.takeIf { it >= 0L }
            ?: throw IllegalStateException("Источник чеков вернул некорректную итоговую сумму.")
        val dateText = root["date_time"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("Источник чеков вернул чек без даты.")
        require(dateText.length <= 80) { "Источник чеков вернул слишком длинную дату." }
        val (receiptDate, normalizedDateTime) = parseReceiptDateTime(dateText)
        require(amount == summary.amountMinor && currency == summary.currency) {
            "Источник чеков вернул детали, не совпадающие со сводкой поиска."
        }
        val settlementPlace = when (val place = root["settlement_place"]) {
            null, JsonNull -> null
            is JsonPrimitive -> {
                if (!place.isString) {
                    throw IllegalStateException("Источник чеков вернул некорректное место расчёта.")
                }
                place.content.trim().takeIf(String::isNotBlank)?.also {
                    require(it.length <= 300) { "Источник чеков вернул слишком длинное место расчёта." }
                }?.let { sanitizeMatchingText(it, receiptKey = summary.key) }
            }
            else -> throw IllegalStateException("Источник чеков вернул некорректное место расчёта.")
        }
        val merchant = sanitizeMatchingText(
            root["merchant"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf(String::isNotBlank) ?: summary.merchant,
            receiptKey = summary.key,
        ).take(200)
        return ReceiptDetailCandidate(
            summary = summary,
            merchant = merchant,
            date = receiptDate,
            dateTime = normalizedDateTime,
            amountMinor = amount,
            currency = currency,
            operationType = operationType,
            settlementPlace = settlementPlace,
            items = items,
        )
    }

    private suspend fun sourceCall(
        tool: String,
        arguments: JsonObject,
        trace: MutableList<SchedulerTraceEvent>?,
        stage: String,
    ): JsonElement {
        trace?.add(
            SchedulerTraceEvent(stage = stage, serverId = RECEIPTS_SERVER_ID, tool = tool, status = "started"),
        )
        return try {
            val response = try {
                withTimeout(SOURCE_CALL_TIMEOUT_MS) {
                    mcpTools.callConfiguredTool(RECEIPTS_SERVER_ID, tool, arguments)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                throw ReceiptMatchingSourceException("Источник receipts/$tool временно недоступен.")
            }
            if (response.isError || response.text.length > MAX_SOURCE_RESPONSE_CHARS) {
                throw ReceiptMatchingSourceException("Источник receipts/$tool временно недоступен.")
            }
            val parsed = runCatching { json.parseToJsonElement(response.text) }
                .getOrElse { throw ReceiptMatchingSourceException("Источник receipts/$tool вернул некорректный JSON.") }
            trace?.add(
                SchedulerTraceEvent(stage = stage, serverId = RECEIPTS_SERVER_ID, tool = tool, status = "succeeded"),
            )
            parsed
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            trace?.add(
                SchedulerTraceEvent(stage = stage, serverId = RECEIPTS_SERVER_ID, tool = tool, status = "failed"),
            )
            throw ReceiptMatchingSourceException("Источник receipts/$tool не ответил вовремя.")
        } catch (error: CancellationException) {
            trace?.add(
                SchedulerTraceEvent(stage = stage, serverId = RECEIPTS_SERVER_ID, tool = tool, status = "failed"),
            )
            throw error
        } catch (error: Throwable) {
            trace?.add(
                SchedulerTraceEvent(stage = stage, serverId = RECEIPTS_SERVER_ID, tool = tool, status = "failed"),
            )
            throw error
        }
    }

    private fun ReceiptDetailCandidate.toChoice() = ReceiptMatchChoice(
        alias = summary.alias,
        receivedAt = date.toString(),
        amountMinor = amountMinor,
        currency = currency,
        merchant = merchant,
        settlementPlace = settlementPlace,
    )

    private fun ExternalTransactionCandidate.receiptEligibility(): ReceiptEligibility {
        val transactionDirection = direction ?: return ReceiptEligibility.BANK_DIRECTION_UNKNOWN
        if (issues.any { it.substringBefore(':').trim() == "direction" }) {
            return ReceiptEligibility.BANK_DIRECTION_UNKNOWN
        }
        if (
            (transactionDirection == TransactionDirection.EXPENSE && amountMinor >= 0) ||
            (transactionDirection == TransactionDirection.REFUND && amountMinor <= 0) ||
            (transactionDirection == TransactionDirection.INCOME && amountMinor <= 0)
        ) {
            return ReceiptEligibility.BANK_DIRECTION_UNKNOWN
        }
        if (transactionDirection == TransactionDirection.INCOME) return ReceiptEligibility.NOT_A_PURCHASE

        val transferMerchant = categoryId?.startsWith("transfer.") == true ||
            merchant.equals("Перевод", ignoreCase = true) ||
            merchant.equals("Между своими счетами", ignoreCase = true)
        return when (transactionDirection) {
            TransactionDirection.EXPENSE ->
                if (transferMerchant || EXPLICIT_TRANSFER_MARKER.containsMatchIn(description)) {
                    ReceiptEligibility.NOT_A_PURCHASE
                } else {
                    ReceiptEligibility.PURCHASE
                }

            TransactionDirection.REFUND ->
                if (transferMerchant || EXPLICIT_SELF_TRANSFER_MARKER.containsMatchIn(description)) {
                    ReceiptEligibility.NOT_A_PURCHASE
                } else {
                    ReceiptEligibility.REFUND
                }

            TransactionDirection.INCOME -> ReceiptEligibility.NOT_A_PURCHASE
        }
    }

    private fun isEligible(
        eligibility: ReceiptEligibility,
        operationDate: LocalDate,
        operationAmount: Long,
        operationCurrency: String,
        receiptDate: LocalDate,
        receiptAmount: Long,
        receiptCurrency: String,
    ): Boolean {
        val dateEligible = when (eligibility) {
            ReceiptEligibility.PURCHASE ->
                !receiptDate.isBefore(operationDate.minusDays(1)) &&
                    !receiptDate.isAfter(operationDate.plusDays(1))

            ReceiptEligibility.REFUND ->
                !receiptDate.isBefore(operationDate.minusDays(14)) &&
                    !receiptDate.isAfter(operationDate)

            ReceiptEligibility.BANK_DIRECTION_UNKNOWN,
            ReceiptEligibility.NOT_A_PURCHASE -> false
        }
        return dateEligible &&
            receiptAmount == abs(operationAmount) &&
            normalizeCurrencyCode(receiptCurrency) == normalizeCurrencyCode(operationCurrency)
    }


    private fun parseReceiptDateTime(value: String): Pair<LocalDate, String> {
        val text = value.trim()
        val offsetDateTime = runCatching { OffsetDateTime.parse(text) }.getOrNull()
        if (offsetDateTime != null) return offsetDateTime.toLocalDate() to offsetDateTime.toString()
        val localDateTime = runCatching { LocalDateTime.parse(text) }.getOrNull()
            ?: throw IllegalStateException("Источник вернул дату и время, которые нельзя сравнить с операцией.")
        return localDateTime.toLocalDate() to localDateTime.toString()
    }
    private fun parseDate(value: String): LocalDate = try {
        LocalDate.parse(value.trim().take(10))
    } catch (_: Exception) {
        throw IllegalStateException("Источник вернул дату, которую нельзя сравнить с операцией.")
    }


    private fun sanitizeMatchingText(value: String, receiptKey: String? = null): String {
        val withoutKey = receiptKey?.takeIf(String::isNotEmpty)?.let { key ->
            value.replace(key, "[ссылка на чек скрыта]", ignoreCase = true)
        } ?: value
        return sanitizeReceiptMatchingText(withoutKey)
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val EXPLICIT_TRANSFER_MARKER = Regex(
            """(?:\bперевод\b|\bсбп\b|внутрибанк\w*|межбанк\w*|по\s+номер(?:у)?\s+телефон|между\s+своими\s+сч[её]т\w*|собственн\w*\s+сч[её]т\w*)""",
            RegexOption.IGNORE_CASE,
        )

        val EXPLICIT_SELF_TRANSFER_MARKER = Regex(
            """(?:\bперевод\s+себе\b|\bна\s+свой\s+сч[её]т\w*|\bмежду\s+своими\s+сч[её]т\w*|\bсобственн\w*\s+сч[её]т\w*)""",
            RegexOption.IGNORE_CASE,
        )
    }
}


internal fun sanitizeReceiptMatchingInstruction(value: String): String = SENSITIVE_RECEIPT_VALUE_PATTERN
    .replace(RECEIPT_REFERENCE_PATTERN.replace(value, "[идентификатор чека скрыт]")) {
        "[чувствительные реквизиты скрыты]"
    }

internal fun sanitizeReceiptMatchingText(value: String): String =
    sanitizeReceiptMatchingInstruction(value).take(2_000)


class ReceiptMatchingSourceException(message: String) : IllegalStateException(message)

class ReceiptMatchingSelectionException : IllegalStateException(
    "Результат сопоставления чеков не удалось безопасно проверить; правило не сохранено.",
)
