package io.github.stolex1y.transactionimport.web

import io.github.stolex1y.transactionimport.core.AgentConfig
import io.github.stolex1y.transactionimport.core.AgentRuntimeConfig
import io.github.stolex1y.transactionimport.core.ProviderCatalog
import io.github.stolex1y.transactionimport.core.ProviderUnavailableException
import io.github.stolex1y.transactionimport.core.SmartExpenseAgent
import io.github.stolex1y.transactionimport.core.StructuredTransaction
import io.github.stolex1y.transactionimport.core.MerchantSuffixPolicy
import io.github.stolex1y.transactionimport.core.CategoryCatalog
import io.github.stolex1y.transactionimport.core.CategoryType
import io.github.stolex1y.transactionimport.core.TransactionCategory
import io.github.stolex1y.transactionimport.core.TransactionDirection
import io.github.stolex1y.transactionimport.core.SYSTEM_TASK_INVARIANTS
import io.github.stolex1y.transactionimport.core.TaskInvariant
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class AgentWebDependencies(
    val agent: SmartExpenseAgent,
    val catalog: ProviderCatalog,
    val runtimeConfig: AgentRuntimeConfig,
    val availableProviderIds: Set<String>,
    val newSessionTitle: () -> String = ::defaultSessionTitle,
    val mcpCatalog: McpCatalogProvider = McpCatalogProvider {
        McpCatalogResponse(emptyList())
    },
    val tbankMcp: TbankMcpProvider = UnavailableTbankMcpProvider,
    val nativeMcpAgent: NativeMcpAgent? = null,
    val scheduler: SchedulerService? = null,
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
data class SystemInvariantCatalogResponse(
    @SerialName("system") val systemInvariants: List<TaskInvariant>,
)

@Serializable
data class UpdateUserPreferencesRequest(
    @SerialName("user_prompt") val userPrompt: String,
)

@Serializable
data class UpdateMerchantCanonicalRuleRequest(
    @SerialName("canonical_name") val canonicalName: String,
    val aliases: List<String>,
    @SerialName("suffix_policy") val suffixPolicy: MerchantSuffixPolicy = MerchantSuffixPolicy.NONE,
)
@Serializable
data class AcceptMemoryCandidateRequest(
    val revision: Long,
)
@Serializable
data class AcceptMerchantCanonicalCandidateRequest(
    val revision: Long,
    @SerialName("preview_id") val previewId: String? = null,
)
@Serializable
data class UpdateConfirmedDecisionRequest(
    val text: String,
)

@Serializable
data class CreateCategoryRequest(
    @SerialName("display_name") val displayName: String,
    val type: CategoryType? = null,
    @SerialName("parent_id") val parentId: String? = null,
    val hint: String = "",
)

@Serializable
data class UpdateCategoryRequest(
    @SerialName("display_name") val displayName: String,
    @SerialName("parent_id") val parentId: String? = null,
    val hint: String = "",
)

@Serializable
data class CreateSchedulerTaskRequest(
    val name: String,
    @SerialName("account_refs") val accountRefs: List<String>,
    @SerialName("start_date") val startDate: String,
    @SerialName("interval_minutes") val intervalMinutes: Long,
    @SerialName("time_zone") val timeZone: String = "UTC",
)

@Serializable
data class SchedulerAccountResponse(
    @SerialName("account_ref") val accountRef: String,
    val name: String,
    val currency: String = "RUB",
    @SerialName("balance_minor") val balanceMinor: Long? = null,
)

@Serializable
data class SchedulerAccountsResponse(
    val accounts: List<SchedulerAccountResponse>,
    val error: String? = null,
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
                categories = CategoryCatalog(runtime.agent.listCategories()).activeLeafCategories(),
            ),
        )
    }

    get("/api/agent/mcp") {
        call.respond(dependencies.requireAgentRuntime().mcpCatalog.catalog())
    }

    get("/api/agent/scheduler/accounts") {
        val runtime = dependencies.requireAgentRuntime()
        val response = runtime.tbankMcp.callTool(TbankToolCallRequest("list-accounts"))
        if (response.isError) {
            call.respond(SchedulerAccountsResponse(emptyList(), response.text.take(240)))
        } else {
            val accounts = runCatching { parseSchedulerAccounts(response.text) }.getOrElse {
                call.respond(
                    SchedulerAccountsResponse(
                        accounts = emptyList(),
                        error = "Список счетов временно недоступен: некорректный ответ источника.",
                    ),
                )
                return@get
            }
            call.respond(SchedulerAccountsResponse(accounts))
        }
    }
    get("/api/agent/scheduler/tasks") {
        call.respond(dependencies.requireAgentRuntime().requireScheduler().listTasks())
    }
    post("/api/agent/scheduler/tasks") {
        val runtime = dependencies.requireAgentRuntime()
        val request = call.receive<CreateSchedulerTaskRequest>()
        call.respond(
            HttpStatusCode.Created,
            runtime.requireScheduler().createTask(
                name = request.name,
                accountRefs = request.accountRefs,
                startDate = request.startDate,
                intervalMinutes = request.intervalMinutes,
                timeZone = request.timeZone,
            ),
        )
    }
    get("/api/agent/scheduler/tasks/{id}/history") {
        val runtime = dependencies.requireAgentRuntime()
        val id = call.parameters["id"].requiredPathParameter("id")
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
        call.respond(runtime.requireScheduler().history(id, limit))
    }
    post("/api/agent/scheduler/tasks/{id}/pause") {
        val runtime = dependencies.requireAgentRuntime()
        val id = call.parameters["id"].requiredPathParameter("id")
        call.respond(runtime.requireScheduler().pause(id))
    }
    post("/api/agent/scheduler/tasks/{id}/resume") {
        val runtime = dependencies.requireAgentRuntime()
        val id = call.parameters["id"].requiredPathParameter("id")
        call.respond(runtime.requireScheduler().resume(id))
    }
    post("/api/agent/scheduler/tasks/{id}/run") {
        val runtime = dependencies.requireAgentRuntime()
        val id = call.parameters["id"].requiredPathParameter("id")
        call.respond(runtime.requireScheduler().runNow(id))
    }

    post("/api/agent/tbank/login") {
        val runtime = dependencies.requireAgentRuntime()
        call.respond(runtime.tbankMcp.login(call.receive()))
    }
    post("/api/agent/tbank/otp/resend") {
        val request = call.receive<TbankOtpResendRequest>()
        call.respond(dependencies.requireAgentRuntime().tbankMcp.resendOtp(request.phone))
    }
    post("/api/agent/tbank/logout") {
        call.respond(dependencies.requireAgentRuntime().tbankMcp.logout())
    }
    get("/api/agent/tbank/session") {
        call.respond(dependencies.requireAgentRuntime().tbankMcp.session())
    }

    get("/api/agent/tbank/session/retry") {
        call.respond(dependencies.requireAgentRuntime().tbankMcp.retrySession())
    }
    post("/api/agent/tbank/tools/call") {
        call.respond(
            dependencies.requireAgentRuntime().tbankMcp.callTool(call.receive()),
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
    put("/api/agent/preferences/decisions/{decisionId}") {
        val decisionId = call.parameters["decisionId"].requiredPathParameter("decisionId")
        val request = call.receive<UpdateConfirmedDecisionRequest>()
        call.respond(
            dependencies.requireAgentRuntime().agent.updateConfirmedDecision(decisionId, request.text),
        )
    }
    delete("/api/agent/preferences/decisions/{decisionId}") {
        val decisionId = call.parameters["decisionId"].requiredPathParameter("decisionId")
        call.respond(
            dependencies.requireAgentRuntime().agent.deleteConfirmedDecision(decisionId),
        )
    }
    post("/api/agent/preferences/merchant-rules") {
        val request = call.receive<UpdateMerchantCanonicalRuleRequest>()
        call.respond(
            HttpStatusCode.Created,
            dependencies.requireAgentRuntime().agent.createMerchantCanonicalRule(
                canonicalName = request.canonicalName,
                aliases = request.aliases,
                suffixPolicy = request.suffixPolicy,
            ),
        )
    }
    put("/api/agent/preferences/merchant-rules/{ruleId}") {
        val ruleId = call.parameters["ruleId"].requiredPathParameter("ruleId")
        val request = call.receive<UpdateMerchantCanonicalRuleRequest>()
        call.respond(
            dependencies.requireAgentRuntime().agent.updateMerchantCanonicalRule(
                ruleId = ruleId,
                canonicalName = request.canonicalName,
                aliases = request.aliases,
                suffixPolicy = request.suffixPolicy,
            ),
        )
    }
    delete("/api/agent/preferences/merchant-rules/{ruleId}") {
        val ruleId = call.parameters["ruleId"].requiredPathParameter("ruleId")
        call.respond(
            dependencies.requireAgentRuntime().agent.deleteMerchantCanonicalRule(ruleId),
        )
    }


    get("/api/agent/categories") {
        call.respond(dependencies.requireAgentRuntime().agent.listCategories(includeArchived = true))
    }
    post("/api/agent/categories") {
        val runtime = dependencies.requireAgentRuntime()
        val request = call.receive<CreateCategoryRequest>()
        call.respond(
            HttpStatusCode.Created,
            runtime.agent.createCategory(
                displayName = request.displayName,
                type = request.type,
                parentId = request.parentId,
                hint = request.hint,
            ),
        )
    }
    put("/api/agent/categories/{categoryId}") {
        val runtime = dependencies.requireAgentRuntime()
        val categoryId = call.parameters["categoryId"].requiredPathParameter("categoryId")
        val request = call.receive<UpdateCategoryRequest>()
        call.respond(
            runtime.agent.updateCategory(
                id = categoryId,
                displayName = request.displayName,
                parentId = request.parentId,
                hint = request.hint,
            ),
        )
    }
    delete("/api/agent/categories/{categoryId}") {
        val runtime = dependencies.requireAgentRuntime()
        val categoryId = call.parameters["categoryId"].requiredPathParameter("categoryId")
        call.respond(runtime.agent.archiveCategory(categoryId))
    }

    get("/api/agent/sessions/{id}/invariants") {
        call.respond(
            SystemInvariantCatalogResponse(systemInvariants = SYSTEM_TASK_INVARIANTS),
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
    get("/api/agent/sessions/{id}/memory") {
        val id = call.parameters["id"].requiredPathParameter("id")
        call.respond(dependencies.requireAgentRuntime().agent.getMemoryTrace(id))
    }
    get("/api/agent/sessions/{id}/memory/projection") {
        val id = call.parameters["id"].requiredPathParameter("id")
        call.respond(dependencies.requireAgentRuntime().agent.getMemoryProjection(id))
    }
    post("/api/agent/sessions/{id}/memory-candidates/{candidateId}/accept") {
        val id = call.parameters["id"].requiredPathParameter("id")
        val candidateId = call.parameters["candidateId"].requiredPathParameter("candidateId")
        val request = call.receive<AcceptMemoryCandidateRequest>()
        call.respond(
            dependencies.requireAgentRuntime().agent.acceptMemoryCandidate(
                sessionId = id,
                expectedRevision = request.revision,
                candidateId = candidateId,
            ),
        )
    }
    post("/api/agent/sessions/{id}/merchant-canonical-candidates/{candidateId}/accept") {
        val id = call.parameters["id"].requiredPathParameter("id")
        val candidateId = call.parameters["candidateId"].requiredPathParameter("candidateId")
        val request = call.receive<AcceptMerchantCanonicalCandidateRequest>()
        val runtime = dependencies.requireAgentRuntime()
        val response = if (request.previewId != null && runtime.nativeMcpAgent != null) {
            runtime.nativeMcpAgent.acceptMerchantCanonicalCandidate(
                sessionId = id,
                expectedRevision = request.revision,
                candidateId = candidateId,
                previewId = request.previewId,
            )
        } else {
            runtime.agent.acceptMerchantCanonicalCandidate(
                sessionId = id,
                expectedRevision = request.revision,
                candidateId = candidateId,
            )
        }
        call.respond(response)
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
        val runtime = dependencies.requireAgentRuntime()
        when (
            val nativeResult = runtime.nativeMcpAgent?.handle(
                sessionId = id,
                expectedRevision = request.revision,
                text = request.text,
            )
        ) {
            is NativeMcpHandlingResult.Handled -> call.respond(nativeResult.response)
            NativeMcpHandlingResult.NotHandled,
            null,
            -> call.respond(
                runtime.agent.sendMessage(
                    sessionId = id,
                    expectedRevision = request.revision,
                    text = request.text,
                ),
            )
        }
    }

    post("/api/agent/sessions/{id}/mcp-previews/{previewId}/confirm") {
        val id = call.parameters["id"].requiredPathParameter("id")
        val previewId = call.parameters["previewId"].requiredPathParameter("previewId")
        val request = call.receive<ConfirmMcpPreviewRequest>()
        val native = dependencies.requireAgentRuntime().nativeMcpAgent
            ?: throw ProviderUnavailableException("Native MCP loop не настроен.")
        call.respond(native.confirm(id, request.revision, previewId))
    }

    delete("/api/agent/sessions/{id}/mcp-previews/{previewId}") {
        val id = call.parameters["id"].requiredPathParameter("id")
        val previewId = call.parameters["previewId"].requiredPathParameter("previewId")
        val native = dependencies.requireAgentRuntime().nativeMcpAgent
            ?: throw ProviderUnavailableException("Native MCP loop не настроен.")
        native.cancel(id, previewId)
        call.respond(HttpStatusCode.NoContent)
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
                    needsReview = false,
                    issues = emptyList(),
                    sourceLabel = existing.sourceLabel,
                    items = existing.items,
                    sourceRef = existing.sourceRef,
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

private fun AgentWebDependencies.requireScheduler(): SchedulerService =
    scheduler ?: throw ProviderUnavailableException("Scheduler service не настроен.")

private fun parseSchedulerAccounts(text: String): List<SchedulerAccountResponse> {
    val payload = runCatching { Json.parseToJsonElement(text) }.getOrElse {
        throw IllegalStateException("Некорректный JSON списка счетов.")
    }
    val root = payload as? JsonObject
    val array = (payload as? JsonArray) ?: listOf(
        root?.get("accounts"),
        (root?.get("data") as? JsonObject)?.get("accounts"),
        (root?.get("payload") as? JsonObject)?.get("accounts"),
    ).firstNotNullOfOrNull { it as? JsonArray }
        ?: throw IllegalStateException("Источник не вернул список счетов.")
    return array.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val ref = sequenceOf("account_ref", "ref", "id")
            .mapNotNull { item[it]?.jsonPrimitive?.contentOrNull?.trim() }
            .firstOrNull(String::isNotBlank)
            ?: return@mapNotNull null
        val name = sequenceOf("name", "account_name", "display_name", "title")
            .mapNotNull { item[it]?.jsonPrimitive?.contentOrNull?.trim() }
            .firstOrNull(String::isNotBlank)
            ?: "Счёт / карта"
        SchedulerAccountResponse(
            accountRef = ref,
            name = name,
            currency = item["currency"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { "RUB" },
            balanceMinor = item["balance_minor"]?.jsonPrimitive?.longOrNull,
        )
    }.also {
        if (it.isEmpty() && array.isNotEmpty()) {
            throw IllegalStateException("Источник вернул счета без безопасных ссылок.")
        }
    }
}

private fun String?.requiredPathParameter(name: String): String =
    this?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("Параметр пути $name не задан.")

private val sessionTitleFormatter = DateTimeFormatter.ofPattern("'Импорт' dd.MM HH:mm")

private fun defaultSessionTitle(): String = LocalDateTime.now().format(sessionTitleFormatter)
