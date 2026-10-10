package io.github.stolex1y.transactionimport.web.benchmark

import io.github.stolex1y.transactionimport.core.ConfirmedDecision
import io.github.stolex1y.transactionimport.core.MerchantCanonicalRule
import io.github.stolex1y.transactionimport.core.MerchantSuffixPolicy
import io.github.stolex1y.transactionimport.core.UserPreferences
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.InputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeParseException

internal data class ImportBenchmarkFixture(
    val modelSelector: String,
    val reasoningEffort: String,
    val repetitions: Int,
    val modelInput: ImportBenchmarkModelInput,
    val evaluationOnly: ImportBenchmarkEvaluation,
    val sha256: String,
)

internal data class ImportBenchmarkModelInput(
    val userTurn: String,
    val accountAlias: String,
    val preferences: UserPreferences,
    val transactions: List<BenchmarkTransaction>,
    val receiptSummaries: List<BenchmarkReceiptSummary>,
    val receiptDetails: Map<String, JsonObject>,
)

internal data class ImportBenchmarkEvaluation(
    val selection: Map<String, Boolean>,
    val categories: Map<String, String?>,
    val receiptAssociations: Map<String, BenchmarkReceiptLabel>,
)

internal enum class BenchmarkReceiptExpectation {
    NO_RECEIPT,
    MATCHED,
    AMBIGUOUS,
}

internal data class BenchmarkReceiptLabel(
    val expectation: BenchmarkReceiptExpectation,
    val receiptIds: Set<String>,
)

internal data class BenchmarkTransaction(
    val id: String,
    val occurredAt: String,
    val direction: String,
    val amountMinor: Long,
    val currency: String,
    val merchant: String,
    val description: String,
) {
    val signedAmountMinor: Long
        get() = when (direction) {
            "expense" -> -amountMinor
            "income" -> amountMinor
            else -> error("Validated benchmark direction became invalid.")
        }
}

internal data class BenchmarkReceiptSummary(
    val id: String,
    val receivedAt: String,
    val merchant: String,
    val amountMinor: Long,
    val currency: String,
)

internal object ImportBenchmarkFixtureLoader {
    const val RESOURCE = "/benchmarks/codex-import-september-2026.json"
    private const val MAX_FIXTURE_BYTES = 256 * 1024
    private const val APPROVED_REPETITIONS = 3
    private const val APPROVED_FIXTURE_SHA256 = "c3a25d07ee6ab69ba0bf56688744d53803abb747742333bd26f1b33ba376809d"
    private const val MODEL_SELECTOR = "openai-codex/gpt-6-luna:min"
    private const val REASONING_EFFORT = "medium"
    private val receiptDetailKeys = setOf(
        "id", "received_at", "date_time", "merchant", "settlement_place", "total_minor", "currency", "items",
    )
    private val receiptItemKeys = setOf(
        "name", "quantity", "price_minor", "sum_minor", "vat_rate", "payment_type", "product_type",
    )
    private val forbiddenKeys = setOf(
        "account_ref",
        "account_id",
        "transaction_ref",
        "receipt_key",
        "source_id",
        "source_ref",
        "fiscal_id",
        "fiscal_document",
        "qr_code",
        "phone_number",
        "buyer_name",
        "cashier_name",
    )
    private val phonePattern = Regex("(?<![\\p{L}\\p{N}])(?:\\+?7|8)(?:[\\s().-]*\\p{Nd}){10}(?![\\p{L}\\p{N}])")
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = false
    }

    fun loadBundled(): ImportBenchmarkFixture {
        val stream = ImportBenchmarkFixtureLoader::class.java.getResourceAsStream(RESOURCE)
            ?: throw IllegalArgumentException("Benchmark fixture resource is missing.")
        return stream.use(::load)
    }

    private fun load(input: InputStream): ImportBenchmarkFixture {
        val bytes = input.readNBytes(MAX_FIXTURE_BYTES + 1)
        require(bytes.size <= MAX_FIXTURE_BYTES) { "Benchmark fixture exceeds the size limit." }
        val text = bytes.toString(Charsets.UTF_8)
        require(sha256(text) == APPROVED_FIXTURE_SHA256) {
            "Bundled benchmark fixture differs from the approved scrubbed sample."
        }
        return parse(text)
    }

    internal fun validateForTest(text: String) {
        parse(text)
    }

    private fun parse(text: String): ImportBenchmarkFixture {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_FIXTURE_BYTES) {
            "Benchmark fixture exceeds the size limit."
        }
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (_: SerializationException) {
            invalid("document")
        } catch (_: IllegalArgumentException) {
            invalid("document")
        }
        requireKeys(
            root,
            setOf("model_selector", "reasoning_effort", "repetitions", "scenario", "preferences", "tool_replay", "evaluation_only"),
            "document",
        )
        val modelSelector = root.requiredString("model_selector", "model selector")
        require(modelSelector == MODEL_SELECTOR) { "Benchmark fixture uses an unapproved model selector." }
        val reasoningEffort = root.requiredString("reasoning_effort", "reasoning effort")
        require(reasoningEffort == REASONING_EFFORT) {
            "Benchmark fixture uses an unapproved reasoning effort."
        }
        val repetitionsValue = root.requiredLong("repetitions", "repetitions")
        require(repetitionsValue == APPROVED_REPETITIONS.toLong()) {
            "Benchmark fixture has an unapproved repetition count."
        }
        val repetitions = repetitionsValue.toInt()

        val scenario = root.requiredObject("scenario", "scenario")
        requireKeys(scenario, setOf("user_turn", "account_alias"), "scenario")
        val userTurn = scenario.requiredString("user_turn", "scenario user turn")
        val accountAlias = scenario.requiredString("account_alias", "scenario account alias")

        val preferencesObject = root.requiredObject("preferences", "preferences")
        requireKeys(
            preferencesObject,
            setOf("confirmed_decisions", "merchant_canonical_rules"),
            "preferences",
        )
        val decisions = preferencesObject.requiredArray("confirmed_decisions", "confirmed decisions")
            .mapIndexed { index, value ->
                ConfirmedDecision(
                    id = "benchmark-decision-${index + 1}",
                    text = value.string("confirmed decision"),
                    createdAtEpochMs = index.toLong(),
                )
            }
        require(decisions.size == 12) { "Benchmark fixture has an unexpected confirmed-decision count." }
        val merchantRules = preferencesObject
            .requiredArray("merchant_canonical_rules", "merchant canonical rules")
            .mapIndexed { index, value ->
                val rule = value.objectValue("merchant canonical rule")
                requireKeys(rule, setOf("canonical_name", "aliases", "suffix_policy"), "merchant rule")
                val suffixPolicy = when (rule.requiredString("suffix_policy", "suffix policy")) {
                    "none" -> MerchantSuffixPolicy.NONE
                    "numeric_terminal" -> MerchantSuffixPolicy.NUMERIC_TERMINAL
                    else -> invalid("suffix policy")
                }
                MerchantCanonicalRule(
                    id = "benchmark-merchant-rule-${index + 1}",
                    canonicalName = rule.requiredString("canonical_name", "canonical name"),
                    aliases = rule.requiredArray("aliases", "merchant aliases")
                        .map { it.string("merchant alias") },
                    suffixPolicy = suffixPolicy,
                    createdAtEpochMs = index.toLong(),
                )
            }
        require(merchantRules.size == 6) { "Benchmark fixture has an unexpected merchant-rule count." }

        val replay = root.requiredObject("tool_replay", "tool replay")
        requireKeys(replay, setOf("transactions", "receipts", "receipt_details"), "tool replay")
        val transactionEnvelope = replay.requiredObject("transactions", "transaction replay")
        requireKeys(transactionEnvelope, setOf("operations"), "transaction replay")
        val transactions = transactionEnvelope.requiredArray("operations", "operations")
            .map { value ->
                val operation = value.objectValue("operation")
                requireKeys(
                    operation,
                    setOf("id", "occurred_at", "direction", "amount_minor", "currency", "merchant", "description"),
                    "operation",
                )
                val id = operation.requiredString("id", "operation id")
                require(id.matches(Regex("TX\\d{2}"))) { "Benchmark operation id is not scrubbed." }
                val occurredAt = operation.requiredString("occurred_at", "operation date")
                requireSeptemberDate(occurredAt, "operation date")
                val direction = operation.requiredString("direction", "operation direction")
                require(direction == "expense" || direction == "income") {
                    "Benchmark operation direction is unsupported."
                }
                val amountMinor = operation.requiredLong("amount_minor", "operation amount")
                require(amountMinor >= 0L) { "Benchmark operation amount is invalid." }
                BenchmarkTransaction(
                    id = id,
                    occurredAt = occurredAt,
                    direction = direction,
                    amountMinor = amountMinor,
                    currency = operation.requiredString("currency", "operation currency"),
                    merchant = operation.requiredString("merchant", "operation merchant"),
                    description = operation.requiredText("description", "operation description"),
                )
            }
        require(transactions.size == 50) { "Benchmark fixture has an unexpected operation count." }
        require(transactions.map { it.id }.toSet().size == transactions.size) {
            "Benchmark fixture contains duplicate operation ids."
        }

        val receiptEnvelope = replay.requiredObject("receipts", "receipt replay")
        requireKeys(receiptEnvelope, setOf("has_more", "receipts"), "receipt replay")
        require(!receiptEnvelope.requiredBoolean("has_more", "receipt completeness")) {
            "Benchmark receipt fixture is incomplete."
        }
        val summaries = receiptEnvelope.requiredArray("receipts", "receipt summaries")
            .map { value ->
                val receipt = value.objectValue("receipt summary")
                requireKeys(receipt, setOf("id", "received_at", "merchant", "amount_minor", "currency"), "receipt summary")
                val id = receipt.requiredString("id", "receipt id")
                require(id.matches(Regex("C\\d{3}"))) { "Benchmark receipt id is not scrubbed." }
                val receivedAt = receipt.requiredString("received_at", "receipt date")
                requireSeptemberDate(receivedAt, "receipt date")
                val amountMinor = receipt.requiredLong("amount_minor", "receipt amount")
                require(amountMinor >= 0L) { "Benchmark receipt amount is invalid." }
                BenchmarkReceiptSummary(
                    id = id,
                    receivedAt = receivedAt,
                    merchant = receipt.requiredString("merchant", "receipt merchant"),
                    amountMinor = amountMinor,
                    currency = receipt.requiredString("currency", "receipt currency"),
                )
            }
        require(summaries.size == 51) { "Benchmark fixture has an unexpected receipt count." }
        require(summaries.map { it.id }.toSet().size == summaries.size) {
            "Benchmark fixture contains duplicate receipt ids."
        }

        val detailArray = replay.requiredArray("receipt_details", "receipt details")
        require(detailArray.size == 9) { "Benchmark fixture has an unexpected receipt-detail count." }
        val details = detailArray.associate { value ->
            val detail = value.objectValue("receipt detail")
            requireKeys(detail, receiptDetailKeys, "receipt detail")
            val id = detail.requiredString("id", "receipt detail id")
            require(id.matches(Regex("C\\d{3}"))) { "Benchmark receipt-detail id is not scrubbed." }
            requireSeptemberDate(detail.requiredString("received_at", "receipt-detail receipt date"), "receipt date")
            requireSeptemberDate(detail.requiredString("date_time", "receipt detail date"), "receipt detail date")
            detail.requiredString("merchant", "receipt-detail merchant")
            detail.requiredString("settlement_place", "receipt settlement place")
            detail.requiredString("currency", "receipt-detail currency")
            require(detail.requiredLong("total_minor", "receipt total") >= 0L) {
                "Benchmark receipt total is invalid."
            }
            id to detail
        }
        require(details.size == detailArray.size) { "Benchmark fixture contains duplicate receipt details." }
        require(details.keys.all { id -> summaries.any { it.id == id } }) {
            "Benchmark receipt details do not map to a summary."
        }
        val itemCount = details.values.sumOf { detail ->
            detail.requiredArray("items", "receipt items").also { items ->
                items.forEach { value ->
                    val item = value.objectValue("receipt item")
                    requireKeys(item, receiptItemKeys, "receipt item")
                    item.requiredString("name", "receipt item name")
                    item.requiredString("vat_rate", "receipt item VAT rate")
                    item.requiredString("payment_type", "receipt item payment type")
                    item.requiredString("product_type", "receipt item product type")
                    val quantity = item.requiredDouble("quantity", "receipt item quantity")
                    require(quantity.isFinite() && quantity > 0.0) {
                        "Benchmark receipt item quantity is invalid."
                    }
                    require(item.requiredLong("price_minor", "receipt item price") >= 0L) {
                        "Benchmark receipt item price is invalid."
                    }
                    require(item.requiredLong("sum_minor", "receipt item sum") >= 0L) {
                        "Benchmark receipt item sum is invalid."
                    }
                }
            }.size
        }
        require(itemCount == 25) { "Benchmark fixture has an unexpected receipt-item count." }

        val labelsObject = root.requiredObject("evaluation_only", "evaluation labels")
        requireKeys(labelsObject, setOf("selection", "categories", "receipt_associations"), "evaluation labels")
        val transactionIds = transactions.mapTo(hashSetOf()) { it.id }
        val selectionArray = labelsObject.requiredArray("selection", "selection labels")
        require(selectionArray.size == 50) { "Benchmark fixture has an unexpected selection-label count." }
        val selectionLabels = selectionArray.associate { value ->
            val label = value.objectValue("selection label")
            requireKeys(label, setOf("transaction_id", "included"), "selection label")
            val id = label.requiredString("transaction_id", "selection id")
            require(id in transactionIds) { "Benchmark selection label has no operation." }
            val included = label.requiredBoolean("included", "selection label")
            id to included
        }
        require(selectionLabels.size == selectionArray.size && selectionLabels.keys == transactionIds) {
            "Benchmark fixture has incomplete or duplicate selection labels."
        }
        require(selectionLabels.values.count { it } == 26 && selectionLabels.values.count { !it } == 24) {
            "Benchmark fixture has unexpected selection-label values."
        }

        val categoryArray = labelsObject.requiredArray("categories", "category labels")
        require(categoryArray.size <= transactionIds.size) {
            "Benchmark fixture has too many category labels."
        }
        val categoryLabels = categoryArray.associate { value ->
            val label = value.objectValue("category label")
            requireKeys(label, setOf("transaction_id", "category"), "category label")
            val id = label.requiredString("transaction_id", "category label id")
            require(id in transactionIds) { "Benchmark category label has no operation." }
            val categoryValue = label["category"] ?: invalid("category label")
            val category = when (categoryValue) {
                JsonNull -> null
                is JsonPrimitive -> categoryValue.takeIf { it.isString }
                    ?.contentOrNull?.takeIf(String::isNotBlank) ?: invalid("category label")
                else -> invalid("category label")
            }
            id to category
        }
        require(categoryLabels.size == categoryArray.size) {
            "Benchmark fixture has duplicate category labels."
        }

        val knownReceiptIds = summaries.mapTo(hashSetOf()) { it.id }
        val receiptArray = labelsObject.requiredArray("receipt_associations", "receipt-association labels")
        require(receiptArray.size == 50) { "Benchmark fixture has an unexpected receipt-label count." }
        val receiptLabels = receiptArray.associate { value ->
            val label = value.objectValue("receipt-association label")
            val id = label.requiredString("transaction_id", "receipt-label transaction id")
            require(id in transactionIds) { "Benchmark receipt label has no operation." }
            val expectedStatus = label.requiredString("expected_status", "receipt-label status")
            val parsed = when (expectedStatus) {
                "no_receipt" -> {
                    requireKeys(label, setOf("transaction_id", "expected_status"), "no-receipt label")
                    BenchmarkReceiptLabel(BenchmarkReceiptExpectation.NO_RECEIPT, emptySet())
                }
                "matched" -> {
                    requireKeys(label, setOf("transaction_id", "expected_status", "receipt_id"), "matched receipt label")
                    BenchmarkReceiptLabel(
                        BenchmarkReceiptExpectation.MATCHED,
                        setOf(label.requiredString("receipt_id", "receipt label id")),
                    )
                }
                "ambiguous" -> {
                    requireKeys(label, setOf("transaction_id", "expected_status", "receipt_ids"), "ambiguous receipt label")
                    val ids = label.requiredArray("receipt_ids", "ambiguous receipt ids")
                        .map { it.string("ambiguous receipt id") }
                    require(ids.size >= 2 && ids.toSet().size == ids.size) {
                        "Benchmark ambiguous receipt label has an invalid candidate set."
                    }
                    BenchmarkReceiptLabel(BenchmarkReceiptExpectation.AMBIGUOUS, ids.toSet())
                }
                else -> invalid("receipt-label status")
            }
            require(parsed.receiptIds.all { it in knownReceiptIds }) {
                "Benchmark receipt label refers to an unknown receipt."
            }
            id to parsed
        }
        require(receiptLabels.size == receiptArray.size && receiptLabels.keys == transactionIds) {
            "Benchmark fixture has incomplete or duplicate receipt labels."
        }
        require(
            receiptLabels.values.count { it.expectation == BenchmarkReceiptExpectation.NO_RECEIPT } == 42 &&
                receiptLabels.values.count { it.expectation == BenchmarkReceiptExpectation.MATCHED } == 7 &&
                receiptLabels.values.count { it.expectation == BenchmarkReceiptExpectation.AMBIGUOUS } == 1,
        ) {
            "Benchmark fixture has unexpected receipt-label values."
        }

        require(noForbiddenFields(root)) { "Benchmark fixture contains a raw source identifier or sensitive field." }
        require(noUnredactedPhones(root)) { "Benchmark fixture contains an unredacted phone number." }
        return ImportBenchmarkFixture(
            modelSelector = modelSelector,
            reasoningEffort = reasoningEffort,
            repetitions = repetitions,
            modelInput = ImportBenchmarkModelInput(
                userTurn = userTurn,
                accountAlias = accountAlias,
                preferences = UserPreferences(
                    userPrompt = "",
                    confirmedDecisions = decisions,
                    merchantCanonicalRules = merchantRules,
                ),
                transactions = transactions,
                receiptSummaries = summaries,
                receiptDetails = details,
            ),
            evaluationOnly = ImportBenchmarkEvaluation(
                selection = selectionLabels,
                categories = categoryLabels,
                receiptAssociations = receiptLabels,
            ),
            sha256 = sha256(text),
        )
    }

    private fun requireSeptemberDate(value: String, section: String) {
        val date = try {
            LocalDate.parse(value.take(10))
        } catch (_: DateTimeParseException) {
            invalid(section)
        }
        require(date.year == 2026 && date.monthValue == 9) {
            "Benchmark fixture contains a date outside the approved period."
        }
    }

    private fun noForbiddenFields(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.all { (key, value) ->
            key.lowercase() !in forbiddenKeys && noForbiddenFields(value)
        }
        is JsonArray -> element.all(::noForbiddenFields)
        is JsonPrimitive, JsonNull -> true
    }

    private fun noUnredactedPhones(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.values.all(::noUnredactedPhones)
        is JsonArray -> element.all(::noUnredactedPhones)
        is JsonPrimitive -> element.contentOrNull?.let { !phonePattern.containsMatchIn(it) } ?: true
    }

    private fun requireKeys(value: JsonObject, allowed: Set<String>, section: String) {
        require(value.keys == allowed) { "Benchmark fixture has an unexpected $section schema." }
    }

    private fun JsonObject.requiredObject(key: String, section: String): JsonObject =
        (this[key] as? JsonObject) ?: invalid(section)

    private fun JsonObject.requiredArray(key: String, section: String): JsonArray =
        (this[key] as? JsonArray) ?: invalid(section)

    private fun JsonObject.requiredString(key: String, section: String): String =
        this[key]?.string(section) ?: invalid(section)
    private fun JsonObject.requiredText(key: String, section: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: invalid(section)

    private fun JsonObject.requiredLong(key: String, section: String): Long =
        (this[key] as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.longOrNull
            ?: invalid(section)

    private fun JsonObject.requiredDouble(key: String, section: String): Double =
        (this[key] as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.doubleOrNull
            ?: invalid(section)

    private fun JsonObject.requiredBoolean(key: String, section: String): Boolean =
        (this[key] as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.booleanOrNull
            ?: invalid(section)

    private fun JsonElement.objectValue(section: String): JsonObject = this as? JsonObject ?: invalid(section)

    private fun JsonElement.string(section: String): String =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotBlank)
            ?: invalid(section)

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun invalid(section: String): Nothing =
        throw IllegalArgumentException("Benchmark fixture has invalid $section; values omitted.")
}
