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
)

@Serializable
data class ReceiptAssociation(
    val status: ReceiptAssociationStatus,
    val summary: ReceiptAssociationSummary? = null,
)

/** A fully validated, safe-to-persist result from the receipt matching boundary. */
data class ReceiptMatchUpdate(
    val status: ReceiptAssociationStatus,
    val items: List<TransactionItem> = emptyList(),
    val summary: ReceiptAssociationSummary? = null,
)
