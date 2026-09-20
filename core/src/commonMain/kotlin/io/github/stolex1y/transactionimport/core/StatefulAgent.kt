package io.github.stolex1y.transactionimport.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
enum class ReceiptStatus {
    @SerialName("not_started")
    NOT_STARTED,

    @SerialName("has_errors")
    HAS_ERRORS,

    @SerialName("ready_for_export")
    READY_FOR_EXPORT,
}

@Serializable
data class ReceiptState(
    val status: ReceiptStatus = ReceiptStatus.NOT_STARTED,
    @SerialName("last_compliance") val lastCompliance: InvariantCheckResult? = null,
)

fun defaultReceiptState(): ReceiptState = ReceiptState()

fun receiptStateFor(
    draft: ImportDraft?,
    compliance: InvariantCheckResult? = null,
): ReceiptState {
    val status = when {
        draft == null -> {
            if (compliance?.status == InvariantCheckStatus.CONFLICT) {
                ReceiptStatus.HAS_ERRORS
            } else {
                ReceiptStatus.NOT_STARTED
            }
        }

        draft.status != ImportStatus.READY -> ReceiptStatus.HAS_ERRORS
        draft.unparsedFragments.isNotEmpty() -> ReceiptStatus.HAS_ERRORS
        draft.transactions.none { it.included } -> ReceiptStatus.HAS_ERRORS
        draft.transactions.any { it.included && it.fieldErrors.isNotEmpty() } ->
            ReceiptStatus.HAS_ERRORS
        compliance?.status == InvariantCheckStatus.CONFLICT -> ReceiptStatus.HAS_ERRORS
        else -> ReceiptStatus.READY_FOR_EXPORT
    }
    return ReceiptState(status = status, lastCompliance = compliance)
}

@Serializable
enum class TaskInvariantType {
    @SerialName("no_ledger_write")
    NO_LEDGER_WRITE,

    @SerialName("mask_explicit_phones")
    MASK_EXPLICIT_PHONES,

    @SerialName("strict_json")
    STRICT_JSON,

    @SerialName("known_categories")
    KNOWN_CATEGORIES,

    @SerialName("no_silent_ambiguity")
    NO_SILENT_AMBIGUITY,
}

@Serializable
data class TaskInvariant(
    val id: String,
    val title: String,
    val type: TaskInvariantType,
    val value: String,
    val explanation: String,
    val active: Boolean = true,
    val system: Boolean = false,
)

@Serializable
enum class InvariantCheckStatus {
    @SerialName("compliant")
    COMPLIANT,

    @SerialName("conflict")
    CONFLICT,
}

@Serializable
data class InvariantConflict(
    @SerialName("invariant_id") val invariantId: String,
    val title: String,
    val explanation: String,
    @SerialName("next_action") val nextAction: String,
)

@Serializable
data class InvariantCheckResult(
    val status: InvariantCheckStatus,
    @SerialName("checked_invariant_ids") val checkedInvariantIds: List<String>,
    val conflicts: List<InvariantConflict> = emptyList(),
    @SerialName("next_action") val nextAction: String,
)

val SYSTEM_TASK_INVARIANTS: List<TaskInvariant> = listOf(
    TaskInvariant(
        id = "system.no-ledger-write",
        title = "Без записи в реальный ledger",
        type = TaskInvariantType.NO_LEDGER_WRITE,
        value = "true",
        explanation = "Первая версия только готовит batch и не меняет реальный ledger.",
        system = true,
    ),
    TaskInvariant(
        id = "system.mask-explicit-phones",
        title = "Маскировать явные телефоны",
        type = TaskInvariantType.MASK_EXPLICIT_PHONES,
        value = "true",
        explanation = "Явные номера телефонов нельзя сохранять или отправлять провайдеру без маскирования.",
        system = true,
    ),
    TaskInvariant(
        id = "system.strict-json",
        title = "Строгий JSON-контракт",
        type = TaskInvariantType.STRICT_JSON,
        value = STRUCTURED_SCHEMA_SIGNATURE,
        explanation = "Ответ провайдера принимается только после строгой локальной проверки схемы.",
        system = true,
    ),
    TaskInvariant(
        id = "system.known-categories",
        title = "Только известные категории",
        type = TaskInvariantType.KNOWN_CATEGORIES,
        value = "active-leaf-catalog",
        explanation = "Можно использовать только существующие совместимые конечные категории.",
        system = true,
    ),
    TaskInvariant(
        id = "system.no-silent-ambiguity",
        title = "Не исправлять неоднозначность молча",
        type = TaskInvariantType.NO_SILENT_AMBIGUITY,
        value = "true",
        explanation = "Неоднозначные операции остаются на ручной проверке.",
        system = true,
    ),
)


internal fun activeTaskInvariants(): List<TaskInvariant> = SYSTEM_TASK_INVARIANTS

internal fun evaluateTaskInvariants(
    draft: ImportDraft,
    categoryCatalog: CategoryCatalog,
): InvariantCheckResult {
    val invariants = activeTaskInvariants()
    val conflicts = buildList {
        invariants.forEach { invariant ->
            when (invariant.type) {
                TaskInvariantType.NO_LEDGER_WRITE,
                TaskInvariantType.STRICT_JSON,
                -> Unit

                TaskInvariantType.MASK_EXPLICIT_PHONES -> {
                    draft.transactions
                        .filter { it.included }
                        .filter { maskExplicitPhoneNumbers(it.transaction.merchant) != it.transaction.merchant }
                        .forEach { row ->
                            add(
                                InvariantConflict(
                                    invariantId = invariant.id,
                                    title = invariant.title,
                                    explanation = "Операция ${row.id} содержит явный номер телефона в поле контрагента.",
                                    nextAction = "Удалите номер телефона или сохраните только обезличенное имя контрагента.",
                                ),
                            )
                        }
                }

                TaskInvariantType.KNOWN_CATEGORIES -> {
                    draft.transactions
                        .filter { it.included }
                        .forEach { row ->
                            val category = row.transaction.categoryId?.let(categoryCatalog::find)
                            if (category != null &&
                                (category.archived || !categoryCatalog.isLeaf(category.id))
                            ) {
                                add(
                                    InvariantConflict(
                                        invariantId = invariant.id,
                                        title = invariant.title,
                                        explanation = "Операция ${row.id} использует недоступную конечную категорию.",
                                        nextAction = "Выберите активную конечную категорию из справочника.",
                                    ),
                                )
                            }
                        }
                }

                TaskInvariantType.NO_SILENT_AMBIGUITY -> {
                    draft.transactions
                        .filter { it.included && it.fieldErrors.isEmpty() && it.transaction.categoryId == null && !it.transaction.needsReview }
                        .forEach { row ->
                            add(
                                InvariantConflict(
                                    invariantId = invariant.id,
                                    title = invariant.title,
                                    explanation = "Операция ${row.id} не имеет категории и не отмечена для ручной проверки.",
                                    nextAction = "Выберите категорию или отметьте операцию для ручной проверки.",
                                ),
                            )
                        }
                }

            }
        }
    }
    return InvariantCheckResult(
        status = if (conflicts.isEmpty()) InvariantCheckStatus.COMPLIANT else InvariantCheckStatus.CONFLICT,
        checkedInvariantIds = invariants.map(TaskInvariant::id),
        conflicts = conflicts,
        nextAction = conflicts.firstOrNull()?.nextAction
            ?: "Все применимые инварианты соблюдены; продолжайте проверку черновика.",
    )
}
