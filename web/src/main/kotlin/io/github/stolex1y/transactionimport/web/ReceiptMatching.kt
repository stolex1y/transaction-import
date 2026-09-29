package io.github.stolex1y.transactionimport.web

import io.github.stolex1y.transactionimport.core.AgentConfig
import io.github.stolex1y.transactionimport.core.ExternalTransactionCandidate
import io.github.stolex1y.transactionimport.core.ReceiptAssociation
import io.github.stolex1y.transactionimport.core.ReceiptAssociationStatus
import io.github.stolex1y.transactionimport.core.ReceiptAssociationSummary
import io.github.stolex1y.transactionimport.core.SchedulerTraceEvent
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
private const val SOURCE_CALL_TIMEOUT_MS = 30_000L
private const val MATCH_CONFIDENCE_THRESHOLD = 0.75
private val CURRENCY_PATTERN = Regex("^[A-Z]{3}$")
private val RECEIPT_REFERENCE_PATTERN = Regex(
    "(?iu)(?<![\\p{L}\\p{N}_])(?:receipt|чек)[-_][A-Za-z0-9][A-Za-z0-9_-]{0,199}(?![\\p{L}\\p{N}_])",
)
private val SENSITIVE_RECEIPT_VALUE_PATTERN = Regex(
    "(?iu)(?<![\\p{L}\\p{N}_])(?:receipt[_ -]?key|fiscal[_ -]?(?:drive|document|sign|id)|" +
        "фискальн(?:ый|ого)?[_ -]?(?:накопитель|документ|признак|идентификатор)|" +
        "qr[_ -]?(?:code|код)?|кассир|cashier)(?![\\p{L}\\p{N}_])\\s*[:=#]?\\s*[^,;\\n]+",
)


private data class ReceiptSummaryCandidate(
    val key: String,
    val merchant: String,
    val date: LocalDate,
    val amountMinor: Long,
    val currency: String,
    val alias: String,
)

private data class ReceiptDetailCandidate(
    val summary: ReceiptSummaryCandidate,
    val merchant: String,
    val date: LocalDate,
    val amountMinor: Long,
    val currency: String,
    val settlementPlace: String?,
    val items: List<TransactionItem>,
) {
    fun safeSummary() = ReceiptAssociationSummary(
        date = date.toString(),
        merchant = merchant,
        amountMinor = amountMinor,
        currency = currency,
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
    ): ReceiptMatchingRun {
        if (transactions.isEmpty()) return ReceiptMatchingRun(emptyList(), 0, 0)
        val datedTransactions = transactions.map { transaction ->
            transaction to parseDate(transaction.occurredAt)
        }
        val periodFrom = datedTransactions.minOf { it.second }.minusDays(1)
        val periodTo = datedTransactions.maxOf { it.second }.plusDays(1)
        val summaries = try {
            searchPeriod(periodFrom, periodTo, trace)
        } catch (error: CancellationException) {
            throw error
        } catch (error: ReceiptMatchingSourceException) {
            throw error
        } catch (_: Throwable) {
            throw ReceiptMatchingSourceException("Источник receipts/search-receipts вернул некорректные данные.")
        }
        val summaryCandidates = summaries.mapIndexed { index, summary ->
            summary.copy(alias = "receipt-${index + 1}")
        }
        val summariesByTransaction = datedTransactions.associate { (transaction, date) ->
            transaction.sourceRef.orEmpty() to summaryCandidates.filter { summary ->
                isEligible(
                    operationDate = date,
                    operationAmount = transaction.amountMinor,
                    operationCurrency = transaction.currency,
                    receiptDate = summary.date,
                    receiptAmount = summary.amountMinor,
                    receiptCurrency = summary.currency,
                )
            }
        }
        val wantedKeys = summariesByTransaction.values.flatten().map { it.key }.toSet()
        val detailCache = linkedMapOf<String, ReceiptDetailCandidate>()
        summaryCandidates.filter { it.key in wantedKeys }.forEach { summary ->
            if (summary.key !in detailCache) {
                val detail = try {
                    val response = sourceCall(
                        tool = RECEIPTS_DETAIL_TOOL,
                        arguments = buildJsonObject { put("receipt_key", summary.key) },
                        trace = trace,
                        stage = "receipts-details",
                    )
                    parseDetail(response, summary)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: ReceiptMatchingSourceException) {
                    throw error
                } catch (_: Throwable) {
                    throw ReceiptMatchingSourceException(
                        "Источник receipts/get-receipt вернул некорректные данные.",
                    )
                }
                detailCache[summary.key] = detail
            }
        }
        val eligibleByTransaction = datedTransactions.associate { (transaction, operationDate) ->
            transaction.sourceRef.orEmpty() to summariesByTransaction[transaction.sourceRef.orEmpty()]
                .orEmpty()
                .mapNotNull { detailCache[it.key] }
                .filter { detail ->
                    isEligible(
                        operationDate = operationDate,
                        operationAmount = transaction.amountMinor,
                        operationCurrency = transaction.currency,
                        receiptDate = detail.date,
                        receiptAmount = detail.amountMinor,
                        receiptCurrency = detail.currency,
                    )
                }
        }
        val ownershipCounts = eligibleByTransaction.values.flatten()
            .groupingBy { it.summary.key }
            .eachCount()
        val safeReceiptRules = confirmedReceiptRules
            .map(::sanitizeMatchingText)
            .filter(String::isNotBlank)
        val safeInstruction = sanitizeMatchingText(explicitInstruction)
        data class MatchDecision(
            val transaction: ExternalTransactionCandidate,
            val status: ReceiptAssociationStatus,
            val selected: ReceiptDetailCandidate? = null,
            val issue: String? = null,
        )
        val decisions = transactions.map { transaction ->
            val id = transaction.sourceRef.orEmpty()
            val local = eligibleByTransaction[id].orEmpty()
            if (local.isEmpty()) return@map MatchDecision(transaction, ReceiptAssociationStatus.UNMATCHED)
            val globallyUnique = local.filter { ownershipCounts[it.summary.key] == 1 }
            val selectionCandidates = if (strictSelector || safeReceiptRules.isNotEmpty()) {
                local
            } else {
                globallyUnique
            }
            if (selectionCandidates.isEmpty()) {
                return@map MatchDecision(
                    transaction = transaction,
                    status = ReceiptAssociationStatus.AMBIGUOUS,
                    issue = "receipt_ambiguous: чек подходит нескольким операциям",
                )
            }
            val useSelector = selectionCandidates.size > 1 ||
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
            }
            if (selected == null) {
                MatchDecision(
                    transaction = transaction,
                    status = ReceiptAssociationStatus.AMBIGUOUS,
                    issue = "receipt_ambiguous: несколько подходящих чеков требуют уточнения",
                )
            } else {
                MatchDecision(transaction, ReceiptAssociationStatus.MATCHED, selected)
            }
        }
        val selectionCounts = decisions.mapNotNull { it.selected?.summary?.key }
            .groupingBy { it }
            .eachCount()
        val enriched = decisions.map { decision ->
            val transaction = decision.transaction
            val selected = decision.selected
            when {
                decision.status == ReceiptAssociationStatus.UNMATCHED -> {
                    trace?.add(SchedulerTraceEvent(stage = "match-proposal", status = "unmatched"))
                    transaction.copy(
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
                    transaction.copy(
                        issues = transaction.issues + issue,
                        receiptAssociation = ReceiptAssociation(ReceiptAssociationStatus.AMBIGUOUS),
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
            merchant = sanitizeMatchingText(value["merchant"]?.jsonPrimitive?.contentOrNull.orEmpty()).take(200),
            date = parseDate(receivedAt),
            amountMinor = amount,
            currency = currency,
            alias = "receipt-${index + 1}",
        )
    }

    private fun parseDetail(element: JsonElement, summary: ReceiptSummaryCandidate): ReceiptDetailCandidate {
        val root = element as? JsonObject
            ?: throw IllegalStateException("Источник чеков вернул некорректные детали.")
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
                name = sanitizeMatchingText(name).take(200),
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
        require(dateText.length <= 80) { "Источник чеков вернул слишком длинную дату чека." }
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
                }?.let(::sanitizeMatchingText)
            }
            else -> throw IllegalStateException("Источник чеков вернул некорректное место расчёта.")
        }
        val merchant = sanitizeMatchingText(
            root["merchant"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf(String::isNotBlank) ?: summary.merchant,
        ).take(200)
        return ReceiptDetailCandidate(
            summary = summary,
            merchant = merchant,
            date = parseDate(dateText),
            amountMinor = amount,
            currency = currency,
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

    private fun isEligible(
        operationDate: LocalDate,
        operationAmount: Long,
        operationCurrency: String,
        receiptDate: LocalDate,
        receiptAmount: Long,
        receiptCurrency: String,
    ): Boolean = receiptAmount == abs(operationAmount) &&
        normalizeCurrencyCode(receiptCurrency) == normalizeCurrencyCode(operationCurrency) &&
        abs(ChronoUnit.DAYS.between(operationDate, receiptDate)) <= 1

    private fun parseDate(value: String): LocalDate = try {
        LocalDate.parse(value.trim().take(10))
    } catch (_: Exception) {
        throw IllegalStateException("Источник вернул дату, которую нельзя сравнить с операцией.")
    }


    private fun sanitizeMatchingText(value: String): String = sanitizeReceiptMatchingText(value)

    private companion object {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}


internal fun sanitizeReceiptMatchingText(value: String): String = SENSITIVE_RECEIPT_VALUE_PATTERN
    .replace(RECEIPT_REFERENCE_PATTERN.replace(value, "[идентификатор чека скрыт]")) {
        "[чувствительные реквизиты скрыты]"
    }
    .take(2_000)


class ReceiptMatchingSourceException(message: String) : IllegalStateException(message)

class ReceiptMatchingSelectionException : IllegalStateException(
    "Результат сопоставления чеков не удалось безопасно проверить; правило не сохранено.",
)
