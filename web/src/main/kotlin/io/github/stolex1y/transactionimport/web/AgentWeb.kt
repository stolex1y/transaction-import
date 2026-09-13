package io.github.stolex1y.transactionimport.web

import io.github.stolex1y.transactionimport.core.AgentConfig
import io.github.stolex1y.transactionimport.core.AgentRuntimeConfig
import io.github.stolex1y.transactionimport.core.ProviderCatalog
import io.github.stolex1y.transactionimport.core.ProviderUnavailableException
import io.github.stolex1y.transactionimport.core.SmartExpenseAgent
import io.github.stolex1y.transactionimport.core.StructuredTransaction
import io.github.stolex1y.transactionimport.core.TRANSACTION_CATEGORIES
import io.github.stolex1y.transactionimport.core.TransactionCategory
import io.github.stolex1y.transactionimport.core.TransactionDirection
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class AgentWebDependencies(
    val agent: SmartExpenseAgent,
    val catalog: ProviderCatalog,
    val runtimeConfig: AgentRuntimeConfig,
    val availableProviderIds: Set<String>,
    val newSessionTitle: () -> String = ::defaultSessionTitle,
)

@Serializable
data class AgentProviderCatalogResponse(
    val providers: List<AgentProviderResponse>,
    val categories: List<TransactionCategory>,
)

@Serializable
data class AgentProviderResponse(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val available: Boolean,
    val models: List<AgentModelResponse>,
)

@Serializable
data class AgentModelResponse(
    val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("reasoning_modes") val reasoningModes: List<AgentReasoningResponse>,
    @SerialName("context_window_tokens") val contextWindowTokens: Int? = null,
)

@Serializable
data class AgentReasoningResponse(
    val id: String,
    @SerialName("display_name") val displayName: String,
)

@Serializable
data class SendAgentMessageRequest(
    val revision: Long,
    val text: String,
)

@Serializable
data class UpdateSessionConfigRequest(
    val revision: Long,
    val config: AgentConfig,
)

@Serializable
data class UpdateDraftTransactionRequest(
    val revision: Long,
    val included: Boolean,
    val direction: TransactionDirection,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("posted_at") val postedAt: String? = null,
    @SerialName("amount_minor") val amountMinor: Long,
    val merchant: String,
    val description: String = "",
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("card_last4") val cardLast4: String? = null,
)

@Serializable
data class SetAllIncludedRequest(
    val revision: Long,
    val included: Boolean,
)

@Serializable
data class DeleteImportSessionRequest(
    val revision: Long,
)

@Serializable
data class ForkAgentSessionRequest(
    val revision: Long,
)
@Serializable
data class UpdateUserPreferencesRequest(
    @SerialName("user_prompt") val userPrompt: String,
)

internal fun Route.agentRoutes(dependencies: AgentWebDependencies?) {
    get("/api/agent/providers") {
        val runtime = dependencies.requireAgentRuntime()
        call.respond(
            AgentProviderCatalogResponse(
                providers = runtime.catalog.providers.map { provider ->
                    AgentProviderResponse(
                        id = provider.id,
                        displayName = provider.displayName,
                        available = provider.id in runtime.availableProviderIds,
                        models = provider.models.map { model ->
                            AgentModelResponse(
                                id = model.id,
                                displayName = model.displayName,
                                reasoningModes = model.reasoningModes.map { mode ->
                                    AgentReasoningResponse(mode.id, mode.displayName)
                                },
                                contextWindowTokens = model.contextWindowTokens,
                            )
                        },
                    )
                },
                categories = TRANSACTION_CATEGORIES,
            ),
        )
    }

    get("/api/agent/preferences") {
        call.respond(dependencies.requireAgentRuntime().agent.getPreferences())
    }

    put("/api/agent/preferences") {
        val request = call.receive<UpdateUserPreferencesRequest>()
        call.respond(
            dependencies.requireAgentRuntime().agent.updatePreferences(request.userPrompt),
        )
    }

    get("/api/agent/sessions") {
        call.respond(dependencies.requireAgentRuntime().agent.listSessions())
    }

    post("/api/agent/sessions") {
        val runtime = dependencies.requireAgentRuntime()
        val state = runtime.agent.createSession(
            title = runtime.newSessionTitle(),
            config = runtime.runtimeConfig.defaultAgentConfig(),
            contextManagement = runtime.runtimeConfig.sessionContextManagement(),
        )
        call.respond(HttpStatusCode.Created, state)
    }

    post("/api/agent/sessions/{id}/fork") {
        val id = call.parameters["id"].requiredPathParameter("id")
        val request = call.receive<ForkAgentSessionRequest>()
        call.respond(
            HttpStatusCode.Created,
            dependencies.requireAgentRuntime().agent.forkSession(
                sessionId = id,
                expectedRevision = request.revision,
            ),
        )
    }

    get("/api/agent/sessions/{id}") {
        val id = call.parameters["id"].requiredPathParameter("id")
        call.respond(dependencies.requireAgentRuntime().agent.getSession(id))
    }

    put("/api/agent/sessions/{id}/config") {
        val id = call.parameters["id"].requiredPathParameter("id")
        val request = call.receive<UpdateSessionConfigRequest>()
        call.respond(
            dependencies.requireAgentRuntime().agent.updateSessionConfig(
                sessionId = id,
                expectedRevision = request.revision,
                config = request.config,
            ),
        )
    }

    post("/api/agent/sessions/{id}/messages") {
        val id = call.parameters["id"].requiredPathParameter("id")
        val request = call.receive<SendAgentMessageRequest>()
        call.respond(
            dependencies.requireAgentRuntime().agent.sendMessage(
                sessionId = id,
                expectedRevision = request.revision,
                text = request.text,
            ),
        )
    }

    put("/api/agent/sessions/{id}/transactions/{transactionId}") {
        val id = call.parameters["id"].requiredPathParameter("id")
        val transactionId = call.parameters["transactionId"].requiredPathParameter("transactionId")
        val request = call.receive<UpdateDraftTransactionRequest>()
        val runtime = dependencies.requireAgentRuntime()
        val current = runtime.agent.getSession(id)
        val existing = current.draft
            ?.transactions
            ?.singleOrNull { it.id == transactionId }
            ?.transaction
            ?: throw IllegalArgumentException("Операция не найдена: $transactionId")
        call.respond(
            runtime.agent.replaceTransaction(
                sessionId = id,
                expectedRevision = request.revision,
                transactionId = transactionId,
                included = request.included,
                description = request.description,
                replacement = StructuredTransaction(
                    sourceIndex = existing.sourceIndex,
                    direction = request.direction,
                    occurredAt = request.occurredAt.trim(),
                    postedAt = request.postedAt?.trim()?.ifEmpty { null },
                    amountMinor = request.amountMinor,
                    currency = existing.currency,
                    merchant = request.merchant.trim(),
                    categoryId = request.categoryId?.trim()?.ifEmpty { null },
                    cardLast4 = request.cardLast4?.trim()?.ifEmpty { null },
                    needsReview = false,
                    issues = emptyList(),
                ),
            ),
        )
    }

    put("/api/agent/sessions/{id}/selection") {
        val id = call.parameters["id"].requiredPathParameter("id")
        val request = call.receive<SetAllIncludedRequest>()
        call.respond(
            dependencies.requireAgentRuntime().agent.setAllIncluded(
                sessionId = id,
                expectedRevision = request.revision,
                included = request.included,
            ),
        )
    }

    delete("/api/agent/sessions/{id}") {
        val id = call.parameters["id"].requiredPathParameter("id")
        val request = call.receive<DeleteImportSessionRequest>()
        dependencies.requireAgentRuntime().agent.deleteSession(id, request.revision)
        call.respond(HttpStatusCode.NoContent)
    }

    get("/api/agent/sessions/{id}/batch") {
        val id = call.parameters["id"].requiredPathParameter("id")
        call.respond(dependencies.requireAgentRuntime().agent.buildImportBatch(id))
    }
}

private fun AgentWebDependencies?.requireAgentRuntime(): AgentWebDependencies =
    this ?: throw ProviderUnavailableException("Сервис временно недоступен.")

private fun String?.requiredPathParameter(name: String): String =
    this?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("Параметр пути $name не задан.")

private val sessionTitleFormatter = DateTimeFormatter.ofPattern("'Импорт' dd.MM HH:mm")

private fun defaultSessionTitle(): String = LocalDateTime.now().format(sessionTitleFormatter)
