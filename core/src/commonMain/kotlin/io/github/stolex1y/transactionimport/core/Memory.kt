package io.github.stolex1y.transactionimport.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MemoryLayer {
    @SerialName("short_term")
    SHORT_TERM,

    @SerialName("working")
    WORKING,

    @SerialName("long_term")
    LONG_TERM,
}

@Serializable
enum class ConfirmedDecisionScope {
    @SerialName("receipt_matching")
    RECEIPT_MATCHING,
}

@Serializable
data class ConfirmedDecision(
    val id: String,
    val text: String,
    @SerialName("created_at_epoch_ms") val createdAtEpochMs: Long,
    val scope: ConfirmedDecisionScope? = null,
)

@Serializable
enum class MerchantSuffixPolicy {
    @SerialName("none")
    NONE,

    @SerialName("numeric_terminal")
    NUMERIC_TERMINAL,
}

@Serializable
data class MerchantCanonicalRule(
    val id: String,
    @SerialName("canonical_name") val canonicalName: String,
    val aliases: List<String>,
    @SerialName("suffix_policy") val suffixPolicy: MerchantSuffixPolicy = MerchantSuffixPolicy.NONE,
    @SerialName("created_at_epoch_ms") val createdAtEpochMs: Long,
)

@Serializable
data class MerchantCanonicalRuleProposal(
    @SerialName("canonical_name") val canonicalName: String,
    val aliases: List<String>,
    @SerialName("suffix_policy") val suffixPolicy: MerchantSuffixPolicy =
        MerchantSuffixPolicy.NONE,
    val reason: String = "",
)

@Serializable
data class MerchantCanonicalCandidate(
    val id: String,
    @SerialName("canonical_name") val canonicalName: String,
    val aliases: List<String>,
    @SerialName("suffix_policy") val suffixPolicy: MerchantSuffixPolicy,
    val reason: String,
    @SerialName("source_message_id") val sourceMessageId: String,
    @SerialName("created_at_epoch_ms") val createdAtEpochMs: Long,
    val status: MemoryCandidateStatus = MemoryCandidateStatus.PENDING,
    @SerialName("accepted_rule_id") val acceptedRuleId: String? = null,
    @SerialName("accepted_at_epoch_ms") val acceptedAtEpochMs: Long? = null,
)


@Serializable
data class ShortTermMemorySnapshot(
    val messages: List<ConversationMessage>,
    val summary: ConversationSummary? = null,
    val facts: List<StickyFact> = emptyList(),
)

@Serializable
data class WorkingMemorySnapshot(
    val draft: ImportDraft? = null,
)

@Serializable
data class MemorySnapshot(
    @SerialName("selected_layers") val selectedLayers: List<MemoryLayer>,
    @SerialName("short_term") val shortTerm: ShortTermMemorySnapshot? = null,
    val working: WorkingMemorySnapshot? = null,
    val longTerm: UserPreferences? = null,
) {
    init {
        require(selectedLayers.distinct().size == selectedLayers.size) {
            "Снимок памяти не должен содержать повторяющиеся слои."
        }
    }
}

@Serializable
data class MemoryLayerTrace(
    val layer: MemoryLayer,
    val scope: String,
    @SerialName("item_count") val itemCount: Int,
    val labels: List<String>,
    val reason: String,
)

@Serializable
data class MemoryTrace(
    @SerialName("session_id") val sessionId: String,
    @SerialName("request_kind") val requestKind: String,
    @SerialName("selected_layers") val selectedLayers: List<MemoryLayer>,
    val layers: List<MemoryLayerTrace>,
    @SerialName("created_at_epoch_ms") val createdAtEpochMs: Long,
)

@Serializable
data class MemoryProjection(
    @SerialName("selected_layers") val selectedLayers: List<MemoryLayer>,
    @SerialName("short_term") val shortTerm: MemoryShortTermProjection? = null,
    val working: MemoryWorkingProjection? = null,
    @SerialName("long_term") val longTerm: MemoryLongTermProjection? = null,
)

@Serializable
data class MemoryShortTermProjection(
    val messages: List<MemoryMessageProjection>,
    val summary: String? = null,
    val facts: List<MemoryFactProjection> = emptyList(),
)

@Serializable
data class MemoryMessageProjection(
    val role: String,
    @SerialName("display_text") val displayText: String,
    @SerialName("created_at_epoch_ms") val createdAtEpochMs: Long,
)

@Serializable
data class MemoryFactProjection(
    val key: String,
    val value: String,
)

@Serializable
data class MemoryWorkingProjection(
    @SerialName("has_draft") val hasDraft: Boolean,
    val status: ImportStatus? = null,
    @SerialName("rejection_reason") val rejectionReason: String? = null,
    @SerialName("unparsed_fragments") val unparsedFragments: List<String> = emptyList(),
    val transactions: List<MemoryTransactionProjection> = emptyList(),
)

@Serializable
data class MemoryTransactionProjection(
    val id: String,
    val included: Boolean,
    val description: String,
    val direction: TransactionDirection,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("posted_at") val postedAt: String? = null,
    @SerialName("amount_minor") val amountMinor: Long,
    val currency: String,
    val merchant: String,
    @SerialName("category_display_name") val categoryDisplayName: String? = null,
    @SerialName("source_label") val sourceLabel: String? = null,
    @SerialName("needs_review") val needsReview: Boolean,
    val issues: List<String> = emptyList(),
)

@Serializable
data class MemoryLongTermProjection(
    @SerialName("user_prompt") val userPrompt: String,
    @SerialName("confirmed_decisions") val confirmedDecisions: List<ConfirmedDecision>,
    @SerialName("merchant_canonical_rules")
    val merchantCanonicalRules: List<MerchantCanonicalRule> = emptyList(),
)


@Serializable
enum class MemoryCandidateStatus {
    @SerialName("pending")
    PENDING,

    @SerialName("accepted")
    ACCEPTED,
}

@Serializable
data class MemoryCandidate(
    val id: String,
    val text: String,
    val reason: String,
    @SerialName("source_message_id") val sourceMessageId: String,
    @SerialName("created_at_epoch_ms") val createdAtEpochMs: Long,
    val status: MemoryCandidateStatus = MemoryCandidateStatus.PENDING,
    @SerialName("accepted_decision_id") val acceptedDecisionId: String? = null,
    @SerialName("accepted_at_epoch_ms") val acceptedAtEpochMs: Long? = null,
)
