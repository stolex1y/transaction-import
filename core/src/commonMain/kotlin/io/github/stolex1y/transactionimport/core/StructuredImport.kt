package io.github.stolex1y.transactionimport.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

const val STRUCTURED_SCHEMA_SIGNATURE = "transaction-import.v1"

@Serializable
enum class CategoryType {
    @SerialName("income")
    INCOME,

    @SerialName("expense")
    EXPENSE,
}

internal data class CategoryDefinition(
    val id: String,
    val displayName: String,
    val description: String,
    val type: CategoryType = CategoryType.EXPENSE,
    val parentId: String? = null,
)

internal val DEFAULT_CATEGORY_DEFINITIONS = listOf(
    CategoryDefinition("food.groceries", "Продукты", "groceries and supermarkets"),
    CategoryDefinition("income.salary", "Зарплата", "salary income", CategoryType.INCOME),
    CategoryDefinition("services.digital", "Цифровые сервисы", "online and digital services"),
    CategoryDefinition("transfer.internal", "Между своими счетами", "transfer between the user's own accounts"),
    CategoryDefinition("health.pharmacy", "Аптеки и здоровье", "pharmacies and medicines"),
    CategoryDefinition("food.cafe", "Кафе и рестораны", "cafes and restaurants"),
    CategoryDefinition("transport", "Транспорт", "public transport, taxis, and fuel"),
    CategoryDefinition("housing", "Жильё", "rent, utilities, and home expenses"),
)

@Serializable
data class TransactionCategory(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val type: CategoryType = CategoryType.EXPENSE,
    @SerialName("parent_id") val parentId: String? = null,
    val hint: String = "",
    val archived: Boolean = false,
)

val TRANSACTION_CATEGORIES: List<TransactionCategory> = DEFAULT_CATEGORY_DEFINITIONS.map {
    TransactionCategory(
        id = it.id,
        displayName = it.displayName,
        type = it.type,
        parentId = it.parentId,
        hint = it.description,
    )
}

val TRANSACTION_CATEGORY_IDS: List<String> = TRANSACTION_CATEGORIES.map(TransactionCategory::id)

val DEFAULT_AGENT_CATEGORIES: List<TransactionCategory> = listOf(
    TransactionCategory(
        id = "category.food",
        displayName = "Еда",
        type = CategoryType.EXPENSE,
        hint = "Группа расходов на продукты и питание.",
    ),
    TransactionCategory(
        id = "food.groceries",
        displayName = "Продукты",
        type = CategoryType.EXPENSE,
        parentId = "category.food",
        hint = "groceries and supermarkets",
    ),
    TransactionCategory(
        id = "income.salary",
        displayName = "Зарплата",
        type = CategoryType.INCOME,
        hint = "salary income",
    ),
    TransactionCategory(
        id = "services.digital",
        displayName = "Цифровые сервисы",
        type = CategoryType.EXPENSE,
        hint = "online and digital services",
    ),
    TransactionCategory(
        id = "transfer.internal",
        displayName = "Между своими счетами",
        type = CategoryType.EXPENSE,
        hint = "legacy internal transfer category",
        archived = true,
    ),
    TransactionCategory(
        id = "health.pharmacy",
        displayName = "Аптеки и здоровье",
        type = CategoryType.EXPENSE,
        hint = "pharmacies and medicines",
    ),
    TransactionCategory(
        id = "food.cafe",
        displayName = "Кафе и рестораны",
        type = CategoryType.EXPENSE,
        parentId = "category.food",
        hint = "cafes and restaurants",
    ),
    TransactionCategory(
        id = "transport",
        displayName = "Транспорт",
        type = CategoryType.EXPENSE,
        hint = "public transport, taxis, and fuel",
    ),
    TransactionCategory(
        id = "housing",
        displayName = "Жильё",
        type = CategoryType.EXPENSE,
        hint = "rent, utilities, and home expenses",
    ),
)

class CategoryCatalog(
    categories: List<TransactionCategory>,
) {
    val categories: List<TransactionCategory> = categories.toList()
    private val byId = this.categories.associateBy(TransactionCategory::id)

    init {
        require(this.categories.map(TransactionCategory::id).distinct().size == this.categories.size) {
            "Идентификаторы категорий должны быть уникальными."
        }
        require(
            this.categories.all {
                it.id.isNotBlank() && it.displayName.trim().isNotEmpty()
            },
        ) {
            "Идентификатор и отображаемое название категории не должны быть пустыми."
        }
        require(
            this.categories
                .map { it.displayName.trim().lowercase() }
                .distinct()
                .size == this.categories.size,
        ) {
            "Отображаемые названия категорий должны быть уникальными."
        }
        this.categories.forEach { category ->
            val parent = category.parentId?.let { parentId ->
                byId[parentId] ?: throw IllegalArgumentException(
                    "Родительская категория не найдена: $parentId",
                )
            }
            require(parent == null || parent.type == category.type) {
                "Тип дочерней категории должен совпадать с типом родителя."
            }
            val visited = mutableSetOf<String>()
            var ancestor = category.parentId
            while (ancestor != null && visited.add(ancestor)) {
                require(ancestor != category.id) {
                    "Иерархия категорий не должна содержать циклы."
                }
                ancestor = byId[ancestor]?.parentId
            }
        }
    }

    fun find(id: String): TransactionCategory? = byId[id]

    fun isLeaf(id: String): Boolean =
        categories.none { !it.archived && it.parentId == id }

    fun activeLeafCategories(direction: TransactionDirection? = null): List<TransactionCategory> =
        categories.filter { category ->
            !category.archived &&
                isLeaf(category.id) &&
                (direction == null || category.type.matches(direction))
        }

    fun displayPath(id: String): String {
        val path = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        var current = byId[id]
        while (current != null && visited.add(current.id)) {
            path += current.displayName
            current = current.parentId?.let(byId::get)
        }
        return path.asReversed().joinToString(" / ")
    }
}

private fun CategoryType.matches(direction: TransactionDirection): Boolean =
    when (this) {
        CategoryType.INCOME -> direction == TransactionDirection.INCOME
        CategoryType.EXPENSE -> direction == TransactionDirection.EXPENSE
    }

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
private val defaultCategoryCatalog = CategoryCatalog(TRANSACTION_CATEGORIES)

internal fun validateStructuredResponse(
    text: String,
    finishReason: String?,
    reasoningContentLength: Int? = null,
    categories: List<TransactionCategory> = TRANSACTION_CATEGORIES,
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
    validateTransactions(decoded.transactions, errors, CategoryCatalog(categories))

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

internal fun decodeDraftResponse(text: String, finishReason: String?): StructuredImport {
    require(finishReason == "stop") {
        "Ответ завершён с finish_reason=${finishReason ?: "null"}, ожидается stop."
    }
    require(text.isNotBlank()) { "Провайдер вернул пустой message.content вместо JSON." }
    val decoded = try {
        strictJson.decodeFromString<StructuredImport>(text)
    } catch (_: SerializationException) {
        throw IllegalArgumentException("Ответ не соответствует строгому JSON-контракту.")
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Ответ не соответствует строгому JSON-контракту.")
    }
    val errors = mutableListOf<String>()
    validateRoot(decoded, errors)
    if (decoded.unparsedFragments.any(String::isBlank)) {
        errors += "unparsed_fragments не должен содержать пустые строки."
    }
    val sourceIndexes = mutableSetOf<Int>()
    decoded.transactions.forEachIndexed { index, transaction ->
        if (transaction.sourceIndex <= 0) {
            errors += "transactions[$index].source_index должен быть положительным."
        } else if (!sourceIndexes.add(transaction.sourceIndex)) {
            errors += "transactions[$index].source_index должен быть уникальным."
        }
    }
    require(errors.isEmpty()) { errors.joinToString(separator = " ") }
    return decoded
}

private val cyrillicLetterPattern = Regex("[А-Яа-яЁё]")
private val latinLetterPattern = Regex("[A-Za-z]")

internal fun localizeIssueMessage(message: String): String {
    val normalized = message.trim()
    if (normalized.isEmpty()) return "Проверьте операцию."

    val lower = normalized.lowercase()
    return when {
        lower.contains("not in category catalog") -> {
            if (lower.contains("investment top-up")) {
                "Пополнение инвестиционного счёта не входит в справочник категорий."
            } else {
                "Категория не входит в справочник категорий."
            }
        }

        lower.contains("inferred from context") && lower.contains("non-transport") ->
            "Категория выведена из контекста; операция может относиться к досугу, а не к транспорту."

        lower.contains("ambiguous") && lower.contains("categor") ->
            "Категория неоднозначна; проверьте операцию."

        cyrillicLetterPattern.containsMatchIn(normalized) -> normalized
        latinLetterPattern.containsMatchIn(normalized) ->
            "Модель не смогла надёжно классифицировать операцию; проверьте поле вручную."
        else -> normalized
    }
}

internal fun localizeIssue(issue: String): String {
    val separator = issue.indexOf(':')
    if (separator <= 0) return localizeIssueMessage(issue)

    val field = issue.substring(0, separator).trim()
    return "$field: ${localizeIssueMessage(issue.substring(separator + 1))}"
}

internal fun transactionFieldErrors(
    transaction: StructuredTransaction,
    categoryCatalog: CategoryCatalog = defaultCategoryCatalog,
): Map<String, List<String>> {
    val errors = linkedMapOf<String, MutableList<String>>()
    fun add(field: String, message: String) {
        errors.getOrPut(field, ::mutableListOf).add(message)
    }

    if (!occurredAtPattern.matches(transaction.occurredAt)) {
        add("occurred_at", "Укажите дату и время операции.")
    }
    if (transaction.postedAt != null && !postedAtPattern.matches(transaction.postedAt)) {
        add("posted_at", "Укажите корректную дату списания.")
    }
    if (transaction.amountMinor < 0) {
        add("amount_minor", "Сумма не может быть отрицательной.")
    }
    if (!currencyPattern.matches(transaction.currency)) {
        add("currency", "Валюта должна состоять из трёх латинских букв.")
    }
    if (transaction.merchant.isBlank()) {
        add("merchant", "Укажите контрагента.")
    }
    val category = transaction.categoryId?.let(categoryCatalog::find)
    when {
        transaction.categoryId == null -> add("category_id", "Выберите категорию.")
        category == null -> add("category_id", "Выбрана неизвестная категория.")
        category.archived && !category.type.matches(transaction.direction) -> {
            add(
                "category_id",
                "Архивная категория «${categoryCatalog.displayPath(category.id)}» не соответствует типу операции.",
            )
        }
        category.archived -> Unit
        !categoryCatalog.isLeaf(category.id) -> {
            add("category_id", "Выберите конечную категорию, а не группу.")
        }
        !category.type.matches(transaction.direction) -> {
            val directionLabel = when (transaction.direction) {
                TransactionDirection.INCOME -> "доходов"
                TransactionDirection.EXPENSE -> "расходов"
            }
            add(
                "category_id",
                "Категория «${categoryCatalog.displayPath(category.id)}» не относится к $directionLabel.",
            )
        }
    }
    if (transaction.cardLast4 != null && !cardLast4Pattern.matches(transaction.cardLast4)) {
        add("card_last4", "Номер карты должен содержать последние четыре цифры.")
    }
    if (transaction.needsReview) {
        transaction.issues.filter(String::isNotBlank).forEach { issue ->
            val field = issue.substringBefore(':').trim().takeIf {
                it in DRAFT_ERROR_FIELDS
            } ?: "_transaction"
            val message = localizeIssueMessage(issue.substringAfter(':', issue).trim())
            add(field, message.ifBlank { "Проверьте операцию." })
        }
        if (transaction.issues.isEmpty() && errors.isEmpty()) {
            add("_transaction", "Проверьте операцию.")
        }
    }
    return errors.mapValues { (_, messages) -> messages.distinct() }
}

private val DRAFT_ERROR_FIELDS = setOf(
    "direction",
    "occurred_at",
    "posted_at",
    "amount_minor",
    "currency",
    "merchant",
    "category_id",
    "card_last4",
)

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
    categoryCatalog: CategoryCatalog,
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
        if (transaction.categoryId != null) {
            val category = categoryCatalog.find(transaction.categoryId)
            when {
                category == null -> {
                    errors += "$path.category_id отсутствует в синтетическом справочнике."
                }
                category.archived -> {
                    errors += "$path.category_id указывает на архивную категорию."
                }
                !categoryCatalog.isLeaf(category.id) -> {
                    errors += "$path.category_id должен указывать на конечную категорию."
                }
                !category.type.matches(transaction.direction) -> {
                    errors += "$path.category_id не соответствует direction."
                }
            }
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
