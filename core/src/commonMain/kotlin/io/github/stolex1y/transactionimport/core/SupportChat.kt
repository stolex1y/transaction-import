package io.github.stolex1y.transactionimport.core

import kotlinx.serialization.Serializable

@Serializable
enum class SupportMessageRole {
    USER,
    ASSISTANT,
}

@Serializable
data class SupportCitation(
    val sourceId: String,
    val title: String,
    val section: String,
)

@Serializable
data class SupportChatMessage(
    val id: String,
    val role: SupportMessageRole,
    val text: String,
    val createdAtEpochMs: Long,
    val ticketId: String? = null,
    val citations: List<SupportCitation> = emptyList(),
)

@Serializable
data class SupportChatInteractionState(
    val awaitingFollowUp: Boolean = false,
    val pendingRouteMessageId: String? = null,
)

data class SupportChatSnapshot(
    val messages: List<SupportChatMessage>,
    val interaction: SupportChatInteractionState,
)

interface SupportChatRepository {
    suspend fun snapshot(): SupportChatSnapshot
    suspend fun append(message: SupportChatMessage): SupportChatMessage
    suspend fun saveInteractionState(state: SupportChatInteractionState)
    suspend fun messagesForTicket(ticketId: String, limit: Int): List<SupportChatMessage>
    suspend fun messagesForRoutingFrom(messageId: String): List<SupportChatMessage>
    suspend fun associateUnassignedMessagesFrom(messageId: String, ticketId: String)
    suspend fun associateAllUnassignedMessages(ticketId: String)
    suspend fun latestMessageTimeForTicket(ticketId: String): Long?
}
