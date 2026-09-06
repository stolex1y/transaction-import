package io.github.stolex1y.transactionimport.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

const val STRUCTURED_SCHEMA_SIGNATURE = "transaction-import.v1"

internal data class CategoryDefinition(
    val id: String,
    val description: String,
)

internal val D02_CATEGORY_CATALOG = listOf(
    CategoryDefinition("food.groceries", "groceries and supermarkets"),
    CategoryDefinition("income.salary", "salary income"),
    CategoryDefinition("services.digital", "online and digital services"),
    CategoryDefinition("transfer.internal", "transfer between the user's own accounts"),
    CategoryDefinition("health.pharmacy", "pharmacies and medicines"),
    CategoryDefinition("food.cafe", "cafes and restaurants"),
    CategoryDefinition("transport", "public transport, taxis, and fuel"),
    CategoryDefinition("housing", "rent, utilities, and home expenses"),
)

@Serializable
enum class ImportStatus {
    @SerialName("ready")
    READY,

    @SerialName("not_applicable")
    NOT_APPLICABLE,
}

@Serializable
enum class TransactionDirection {
    @SerialName("income")
    INCOME,

    @SerialName("expense")
    EXPENSE,
}

@Serializable
data class StructuredImport(
    val status: ImportStatus,
    @SerialName("rejection_reason") val rejectionReason: String?,
    val transactions: List<StructuredTransaction>,
    @SerialName("unparsed_fragments") val unparsedFragments: List<String>,
)

@Serializable
data class StructuredTransaction(
    @SerialName("source_index") val sourceIndex: Int,
    val direction: TransactionDirection,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("posted_at") val postedAt: String?,
    @SerialName("amount_minor") val amountMinor: Long,
    val currency: String,
    val merchant: String,
    @SerialName("category_id") val categoryId: String?,
    @SerialName("card_last4") val cardLast4: String?,
    @SerialName("needs_review") val needsReview: Boolean,
    val issues: List<String>,
)

@Serializable
data class StructuredValidation(
    val valid: Boolean,
    val importable: Boolean,
    @SerialName("schema_signature") val schemaSignature: String? = null,
    val status: ImportStatus? = null,
    @SerialName("transaction_count") val transactionCount: Int? = null,
    @SerialName("rejection_reason") val rejectionReason: String? = null,
    val errors: List<String> = emptyList(),
)

internal data class StructuredValidationOutcome(
    val document: StructuredImport?,
    val summary: StructuredValidation,
)

private val strictJson = Json {
    ignoreUnknownKeys = false
    isLenient = false
    coerceInputValues = false
    explicitNulls = true
}

private val occurredAtPattern = Regex(
    """^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?(?:Z|[+-]\d{2}:\d{2})?$""",
)
private val postedAtPattern = Regex(
    """^\d{4}-\d{2}-\d{2}(?:T\d{2}:\d{2}(?::\d{2})?(?:Z|[+-]\d{2}:\d{2})?)?$""",
)
private val currencyPattern = Regex("^[A-Z]{3}$")
private val cardLast4Pattern = Regex("""^\d{4}$""")
private val allowedCategoryIds = D02_CATEGORY_CATALOG.mapTo(mutableSetOf()) { it.id }

internal fun validateStructuredResponse(
    text: String,
    finishReason: String?,
    reasoningContentLength: Int? = null,
): StructuredValidationOutcome {
    val errors = mutableListOf<String>()
    if (finishReason != "stop") {
        errors += if (finishReason == "length") {
            "Ответ обрезан: finish_reason=length."
        } else {
            "Ответ завершён с finish_reason=${finishReason ?: "null"}, ожидается stop."
        }
    }
    if (text.isBlank()) {
        errors += "Провайдер вернул пустой message.content вместо JSON."
        if (reasoningContentLength != null && reasoningContentLength > 0) {
            errors += "reasoning_content содержит $reasoningContentLength символов; бюджет max_tokens мог быть израсходован до финального JSON."
        }
        return StructuredValidationOutcome(
            document = null,
            summary = StructuredValidation(
                valid = false,
                importable = false,
                errors = errors,
            ),
        )
    }
    val decoded = try {
        strictJson.decodeFromString<StructuredImport>(text)
    } catch (_: SerializationException) {
        errors += "Ответ не соответствует строгому JSON-контракту."
        null
    } catch (_: IllegalArgumentException) {
        errors += "Ответ не соответствует строгому JSON-контракту."
        null
    }

    if (decoded == null) {
        return StructuredValidationOutcome(
            document = null,
            summary = StructuredValidation(
                valid = false,
                importable = false,
                errors = errors,
            ),
        )
    }

    validateRoot(decoded, errors)
    validateTransactions(decoded.transactions, errors)
    if (decoded.unparsedFragments.any { it.isBlank() }) {
        errors += "unparsed_fragments не должен содержать пустые строки."
    }

    val valid = errors.isEmpty()
    return StructuredValidationOutcome(
        document = decoded.takeIf { valid },
        summary = StructuredValidation(
            valid = valid,
            importable = valid && decoded.status == ImportStatus.READY,
            schemaSignature = STRUCTURED_SCHEMA_SIGNATURE,
            status = decoded.status,
            transactionCount = decoded.transactions.size,
            rejectionReason = decoded.rejectionReason,
            errors = errors,
        ),
    )
}

private fun validateRoot(
    document: StructuredImport,
    errors: MutableList<String>,
) {
    when (document.status) {
        ImportStatus.READY -> {
            if (document.transactions.isEmpty()) {
                errors += "status=ready требует хотя бы одну операцию."
            }
            if (document.rejectionReason != null) {
                errors += "status=ready требует rejection_reason=null."
            }
        }

        ImportStatus.NOT_APPLICABLE -> {
            if (document.transactions.isNotEmpty()) {
                errors += "status=not_applicable требует пустой transactions."
            }
            if (document.rejectionReason.isNullOrBlank()) {
                errors += "status=not_applicable требует непустой rejection_reason."
            }
            if (document.unparsedFragments.isEmpty()) {
                errors += "status=not_applicable требует сохранить вход в unparsed_fragments."
            }
        }
    }
}

private fun validateTransactions(
    transactions: List<StructuredTransaction>,
    errors: MutableList<String>,
) {
    val sourceIndices = mutableSetOf<Int>()
    transactions.forEachIndexed { position, transaction ->
        val path = "transactions[$position]"
        if (transaction.sourceIndex <= 0) {
            errors += "$path.source_index должен быть положительным."
        } else if (!sourceIndices.add(transaction.sourceIndex)) {
            errors += "$path.source_index должен быть уникальным."
        }
        if (!occurredAtPattern.matches(transaction.occurredAt)) {
            errors += "$path.occurred_at должен быть ISO 8601 date-time."
        }
        if (transaction.postedAt != null && !postedAtPattern.matches(transaction.postedAt)) {
            errors += "$path.posted_at должен быть ISO 8601 date/date-time или null."
        }
        if (transaction.amountMinor < 0) {
            errors += "$path.amount_minor должен быть неотрицательным."
        }
        if (!currencyPattern.matches(transaction.currency)) {
            errors += "$path.currency должен быть трёхбуквенным кодом ISO 4217."
        }
        if (transaction.merchant.isBlank()) {
            errors += "$path.merchant не должен быть пустым."
        }
        if (transaction.categoryId != null && transaction.categoryId !in allowedCategoryIds) {
            errors += "$path.category_id отсутствует в синтетическом справочнике."
        }
        if (transaction.cardLast4 != null && !cardLast4Pattern.matches(transaction.cardLast4)) {
            errors += "$path.card_last4 должен содержать четыре цифры или null."
        }
        if (transaction.issues.any { it.isBlank() }) {
            errors += "$path.issues не должен содержать пустые строки."
        }
        if (transaction.needsReview && transaction.issues.isEmpty()) {
            errors += "$path.needs_review=true требует хотя бы одну issue."
        }
        if (!transaction.needsReview && transaction.issues.isNotEmpty()) {
            errors += "$path с issues требует needs_review=true."
        }
        if (transaction.categoryId == null && !transaction.needsReview) {
            errors += "$path без category_id требует needs_review=true."
        }
    }
}
