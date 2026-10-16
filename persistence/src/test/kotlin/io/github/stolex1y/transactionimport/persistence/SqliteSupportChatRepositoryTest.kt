package io.github.stolex1y.transactionimport.persistence

import io.github.stolex1y.transactionimport.core.SupportChatInteractionState
import io.github.stolex1y.transactionimport.core.SupportChatMessage
import io.github.stolex1y.transactionimport.core.SupportCitation
import io.github.stolex1y.transactionimport.core.SupportMessageRole
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SqliteSupportChatRepositoryTest {
    @Test
    fun persistsFullTranscriptInteractionStateAndTicketScopedWindow() = runBlocking {
        val database = Files.createTempFile("support-chat-window-", ".sqlite")
        try {
            val repository = SqliteSupportChatRepository(database.toString())
            repeat(12) { index ->
                repository.append(
                    SupportChatMessage(
                        id = "message-$index",
                        role = if (index % 2 == 0) SupportMessageRole.USER else SupportMessageRole.ASSISTANT,
                        text = "ticket-a-message-$index",
                        createdAtEpochMs = 1_000L + index,
                        ticketId = "ticket-a",
                        citations = if (index == 11) {
                            listOf(SupportCitation("guide#import", "Руководство", "Создание импорта"))
                        } else {
                            emptyList()
                        },
                    ),
                )
            }
            repository.append(
                SupportChatMessage(
                    id = "other-ticket-message",
                    role = SupportMessageRole.USER,
                    text = "other-ticket-only",
                    createdAtEpochMs = 2_000,
                    ticketId = "ticket-b",
                ),
            )
            val interaction = SupportChatInteractionState(awaitingFollowUp = true)
            repository.saveInteractionState(interaction)

            val restored = SqliteSupportChatRepository(database.toString())
            val snapshot = restored.snapshot()

            assertEquals(13, snapshot.messages.size)
            assertEquals(interaction, snapshot.interaction)
            assertEquals(
                (2..11).map { "ticket-a-message-$it" },
                restored.messagesForTicket("ticket-a", 10).map(SupportChatMessage::text),
            )
            assertEquals(
                listOf("other-ticket-only"),
                restored.messagesForTicket("ticket-b", 10).map(SupportChatMessage::text),
            )
            assertEquals(
                listOf(SupportCitation("guide#import", "Руководство", "Создание импорта")),
                snapshot.messages.last { it.id == "message-11" }.citations,
            )
            assertEquals(1_011L, restored.latestMessageTimeForTicket("ticket-a"))
        } finally {
            database.deleteIfExists()
            Path.of(database.toString() + "-wal").deleteIfExists()
            Path.of(database.toString() + "-shm").deleteIfExists()
        }
    }

    @Test
    fun assignsOnlyPendingRouteExchangeAndUsesItsTranscriptTime() = runBlocking {
        val database = Files.createTempFile("support-chat-route-", ".sqlite")
        try {
            val repository = SqliteSupportChatRepository(database.toString())
            repository.append(
                SupportChatMessage("orphan", SupportMessageRole.USER, "unrouted earlier", 10),
            )
            repository.append(
                SupportChatMessage("route-question", SupportMessageRole.USER, "continue export issue", 20),
            )
            repository.append(
                SupportChatMessage("route-clarification", SupportMessageRole.ASSISTANT, "Which issue?", 30),
            )
            repository.append(
                SupportChatMessage("route-answer", SupportMessageRole.USER, "the JSON export", 40),
            )
            repository.saveInteractionState(
                SupportChatInteractionState(pendingRouteMessageId = "route-question"),
            )

            assertEquals(
                listOf("route-question", "route-clarification", "route-answer"),
                repository.messagesForRoutingFrom("route-question").map(SupportChatMessage::id),
            )
            repository.associateUnassignedMessagesFrom("route-question", "ticket-reopened")

            val snapshot = repository.snapshot()
            assertNull(snapshot.messages.first { it.id == "orphan" }.ticketId)
            assertEquals(
                listOf("continue export issue", "Which issue?", "the JSON export"),
                repository.messagesForTicket("ticket-reopened", 10).map(SupportChatMessage::text),
            )
            assertEquals(40L, repository.latestMessageTimeForTicket("ticket-reopened"))
        } finally {
            database.deleteIfExists()
            Path.of(database.toString() + "-wal").deleteIfExists()
            Path.of(database.toString() + "-shm").deleteIfExists()
        }
    }
}
