package io.github.stolex1y.transactionimport.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ReceiptAssociationStatus {
    @SerialName("unmatched")
    UNMATCHED,

    @SerialName("matched")
    MATCHED,

    @SerialName("ambiguous")
    AMBIGUOUS,

    @SerialName("source_error")
    SOURCE_ERROR,
}

/** Allowlisted receipt summary; raw receipt keys and fiscal data have no persistence field. */
@Serializable
data class ReceiptAssociationSummary(
    val date: String,
    val merchant: String,
    @SerialName("amount_minor") val amountMinor: Long,
    val currency: String,
    @SerialName("date_time") val dateTime: String? = null,
    @SerialName("settlement_place") val settlementPlace: String? = null,
    @SerialName("privacy_version") val privacyVersion: Int = 0,
) {
    companion object {
        const val CURRENT_PRIVACY_VERSION = 1
    }
}

@Serializable
enum class ReceiptReviewReason {
    @SerialName("multiple_candidates")
    MULTIPLE_CANDIDATES,

    @SerialName("selector_uncertain")
    SELECTOR_UNCERTAIN,

    @SerialName("shared_by_operations")
    SHARED_BY_OPERATIONS,

    @SerialName("duplicate_selection")
    DUPLICATE_SELECTION,

    @SerialName("operation_type_unknown")
    OPERATION_TYPE_UNKNOWN,

    @SerialName("bank_direction_unknown")
    BANK_DIRECTION_UNKNOWN,

    @SerialName("receipt_operation_type_unknown")
    RECEIPT_OPERATION_TYPE_UNKNOWN,

    @SerialName("receipt_operation_type_incompatible")
    RECEIPT_OPERATION_TYPE_INCOMPATIBLE,

    @SerialName("receipt_detail_unavailable")
    RECEIPT_DETAIL_UNAVAILABLE,

}

/** Bounded, private-key-free evidence for explaining an unresolved match. */
@Serializable
data class ReceiptReviewEvidence(
    val reason: ReceiptReviewReason,
    val candidates: List<ReceiptAssociationSummary> = emptyList(),
)

@Serializable
data class ReceiptAssociation(
    val status: ReceiptAssociationStatus,
    val summary: ReceiptAssociationSummary? = null,
    val review: ReceiptReviewEvidence? = null,
)

/** A fully validated, safe-to-persist result from the receipt matching boundary. */
data class ReceiptMatchUpdate(
    val status: ReceiptAssociationStatus,
    val items: List<TransactionItem> = emptyList(),
    val summary: ReceiptAssociationSummary? = null,
    val review: ReceiptReviewEvidence? = null,
    val refinedCategoryId: String? = null,
)

