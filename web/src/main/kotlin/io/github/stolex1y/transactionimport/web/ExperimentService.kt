package io.github.stolex1y.transactionimport.web

import io.github.stolex1y.transactionimport.core.ChatCompletionGateway
import io.github.stolex1y.transactionimport.core.ChatCompletionRequest
import io.github.stolex1y.transactionimport.core.RequestMessage
import io.github.stolex1y.transactionimport.core.ResponseMessage
import io.github.stolex1y.transactionimport.core.ReasoningLevel
import io.github.stolex1y.transactionimport.core.ThinkingOptions
import io.github.stolex1y.transactionimport.core.TokenBudgetMode
import io.github.stolex1y.transactionimport.core.Usage
import io.github.stolex1y.transactionimport.d05.OpenRouterGateway
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.cancel
private const val DEFAULT_EXPERIMENT_MAX_TOKENS = 1200
private const val D03_MAX_TOKENS = 1600
private const val D05_MAX_TOKENS = 4096
private const val OPENROUTER_MODEL = "z-ai/glm-5.2:free"
private const val DEFAULT_MODEL = "deepseek-v4-flash"
private const val MAX_TASK_LENGTH = 20_000
private const val MAX_CUSTOM_CONFIGURATIONS = 4

@Serializable
data class ExperimentRequest(
    val task: String,
    @SerialName("reference_or_rubric") val referenceOrRubric: String? = null,
    @SerialName("max_tokens_mode") val maxTokensMode: String = "auto",
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("custom_configurations") val customConfigurations: List<CustomExperimentConfiguration> = emptyList(),
)


@Serializable
data class CustomExperimentConfiguration(
    val label: String,
    val model: String = DEFAULT_MODEL,
    val reasoning: String = "disabled",
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("system_prompt") val systemPrompt: String? = null,
)

@Serializable
data class ExperimentAccepted(
    @SerialName("job_id") val jobId: String,
)

@Serializable
data class ExperimentJobResponse(
    val id: String,
    val kind: String,
    val status: String,
    val completed: Int,
    val total: Int,
    val error: String? = null,
    val result: ExperimentReport? = null,
)

@Serializable
data class ExperimentPreflight(
    val provider: String,
    val model: String,
    val passed: Boolean,
    @SerialName("reasoning_support") val reasoningSupport: String,
    val metadata: JsonObject? = null,
    val error: String? = null,
)

@Serializable
data class ExperimentReport(
    val kind: String,
    val task: String,
    @SerialName("reference_or_rubric") val referenceOrRubric: String? = null,
    @SerialName("generated_at") val generatedAt: String,
    @SerialName("max_tokens_mode") val maxTokensMode: String,
    @SerialName("requested_max_tokens") val requestedMaxTokens: Int? = null,
    val preflight: List<ExperimentPreflight> = emptyList(),
    val runs: List<ExperimentRun>,
    val notes: List<String> = emptyList(),
)

@Serializable
data class ExperimentRun(
    val label: String,
    val stage: String? = null,
    val provider: String,
    val model: String,
    val reasoning: String,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("system_prompt") val systemPrompt: String? = null,
    @SerialName("generated_prompt") val generatedPrompt: String? = null,
    val text: String? = null,
    @SerialName("text_length") val textLength: Int? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
    val usage: Usage? = null,
    @SerialName("raw_usage") val rawUsage: JsonObject? = null,
    @SerialName("reasoning_content_length") val reasoningContentLength: Int? = null,
    @SerialName("token_budget_warning") val tokenBudgetWarning: String? = null,
    @SerialName("processing_time_ms") val processingTimeMs: Long? = null,
    @SerialName("response_fingerprint") val responseFingerprint: String? = null,
    val error: String? = null,
)

private data class ExperimentConfiguration(
    val label: String,
    val model: String,
    val reasoning: String,
    val temperature: Double?,
    val maxTokens: Int?,
    val systemPrompt: String,
    val pageOverridable: Boolean = true,
)

private data class CallOutcome(
    val response: ResponseMessage? = null,
    val finishReason: String? = null,
    val usage: Usage? = null,
    val rawUsage: JsonObject? = null,
    val reasoningContentLength: Int? = null,
    val processingTimeMs: Long = 0,
    val error: String? = null,
)

class ExperimentService(
    private val deepSeekGateway: ChatCompletionGateway,
    private val openRouterGateway: OpenRouterGateway?,
) {
    fun configurationCount(kind: String, customConfigurations: List<CustomExperimentConfiguration>): Int =
        presetConfigurations(kind).size + customConfigurations.size

    suspend fun run(
        kind: String,
        request: ExperimentRequest,
        onProgress: suspend (completed: Int, total: Int) -> Unit,
    ): ExperimentReport {
        val task = request.task.trim()
        val tokenBudgetMode = parseTokenBudgetMode(request.maxTokensMode)
        validateTokenBudget(tokenBudgetMode, request.maxTokens)
        validateRequest(kind, task, request.referenceOrRubric, request.customConfigurations)
        val configurations = (
            presetConfigurations(kind) + request.customConfigurations.map {
                customConfiguration(kind, it)
            }
        ).map { configuration ->
            applyPageTokenBudget(configuration, tokenBudgetMode, request.maxTokens)
        }
        val preflight = if (kind == "d05") preflightOpenRouter() else emptyList()
        val runs = mutableListOf<ExperimentRun>()
        configurations.forEachIndexed { index, configuration ->
            val run = if (kind == "d03" && configuration.label == "Metaprompt") {
                runMetaprompt(configuration, task)
            } else {
                runConfiguration(configuration, task)
            }
            runs += run
            onProgress(index + 1, configurations.size)
        }
        return ExperimentReport(
            kind = kind,
            task = task,
            referenceOrRubric = request.referenceOrRubric?.trim()?.takeIf { it.isNotEmpty() },
            generatedAt = Instant.now().toString(),
            maxTokensMode = tokenBudgetApiValue(tokenBudgetMode, request.maxTokens),
            requestedMaxTokens = request.maxTokens,
            preflight = preflight,
            runs = runs,
            notes = notesFor(kind, tokenBudgetMode, request.maxTokens),
        )
    }

    private fun presetConfigurations(kind: String): List<ExperimentConfiguration> = when (kind) {
        "d03" -> listOf(
            ExperimentConfiguration(
                label = "Direct",
                model = DEFAULT_MODEL,
                reasoning = "disabled",
                temperature = 0.0,
                maxTokens = D03_MAX_TOKENS,
                systemPrompt = "Solve the user's task directly. Return a concise final answer with the result first.",
            ),
            ExperimentConfiguration(
                label = "Step-by-step",
                model = DEFAULT_MODEL,
                reasoning = "disabled",
                temperature = 0.0,
                maxTokens = D03_MAX_TOKENS,
                systemPrompt = "Solve the user's task step by step, check the intermediate reasoning, then return a concise final answer with the result first.",
            ),
            ExperimentConfiguration(
                label = "Metaprompt",
                model = DEFAULT_MODEL,
                reasoning = "disabled",
                temperature = 0.0,
                maxTokens = D03_MAX_TOKENS,
                systemPrompt = "Create a concise solver prompt for the user's task, then use that prompt to produce the final answer.",
            ),
            ExperimentConfiguration(
                label = "Multi-expert",
                model = DEFAULT_MODEL,
                reasoning = "disabled",
                temperature = 0.0,
                maxTokens = D03_MAX_TOKENS,
                systemPrompt = "Use three roles for the user's task: analyst proposes a solution, verifier checks it, and critic identifies weaknesses. Return the checked final answer.",
            ),
        )
        "d04" -> listOf(0.0, 0.7, 1.2).map { temperature ->
            ExperimentConfiguration(
                label = "Temperature $temperature",
                model = DEFAULT_MODEL,
                reasoning = "disabled",
                temperature = temperature,
                maxTokens = DEFAULT_EXPERIMENT_MAX_TOKENS,
                systemPrompt = "Solve the user's task. Return a concise final answer and do not invent facts that are absent from the task.",
            )
        }
        "d05" -> listOf(
            ExperimentConfiguration(
                label = "DeepSeek flash",
                model = "deepseek-v4-flash",
                reasoning = "high",
                temperature = 0.0,
                maxTokens = D05_MAX_TOKENS,
                systemPrompt = "Solve the user's task. Return the final answer directly and preserve important details.",
            ),
            ExperimentConfiguration(
                label = "GLM 5.2 free",
                model = OPENROUTER_MODEL,
                reasoning = "high",
                temperature = 0.0,
                maxTokens = D05_MAX_TOKENS,
                systemPrompt = "Solve the user's task. Return the final answer directly and preserve important details.",
            ),
            ExperimentConfiguration(
                label = "DeepSeek pro",
                model = "deepseek-v4-pro",
                reasoning = "high",
                temperature = 0.0,
                maxTokens = D05_MAX_TOKENS,
                systemPrompt = "Solve the user's task. Return the final answer directly and preserve important details.",
            ),
        )
        else -> error("Неизвестный эксперимент: $kind")
    }

    private fun customConfiguration(
        kind: String,
        custom: CustomExperimentConfiguration,
    ): ExperimentConfiguration {
        val model = custom.model.trim()
        val reasoning = parseReasoning(custom.reasoning.trim().lowercase())
        val defaultMaxTokens = defaultMaxTokens(kind)
        val maxTokens = custom.maxTokens ?: defaultMaxTokens
        require(custom.label.trim().isNotEmpty()) { "Название custom configuration не может быть пустым." }
        require(model in supportedExperimentModels) { "Модель не разрешена: $model" }
        require(custom.temperature == null || custom.temperature in 0.0..2.0) {
            "Temperature должна быть в диапазоне от 0 до 2."
        }
        require(custom.maxTokens == null || custom.maxTokens > 0) {
            "max_tokens должен быть положительным целым числом."
        }
        return ExperimentConfiguration(
            label = custom.label.trim(),
            model = model,
            reasoning = reasoning.name.lowercase(),
            temperature = custom.temperature,
            maxTokens = maxTokens,
            systemPrompt = custom.systemPrompt?.trim()?.takeIf { it.isNotEmpty() }
                ?: "Solve the user's task and return a concise final answer.",
            pageOverridable = custom.maxTokens == null,
        )
    }

    private fun applyPageTokenBudget(
        configuration: ExperimentConfiguration,
        mode: TokenBudgetMode,
        maxTokens: Int?,
    ): ExperimentConfiguration {
        if (!configuration.pageOverridable) {
            return configuration
        }
        return when (mode) {
            TokenBudgetMode.DEFAULT -> configuration
            TokenBudgetMode.UNLIMITED -> configuration.copy(maxTokens = null)
        }.let { applied ->
            if (mode == TokenBudgetMode.DEFAULT && maxTokens != null) {
                applied.copy(maxTokens = maxTokens)
            } else {
                applied
            }
        }
    }

    private fun defaultMaxTokens(kind: String): Int = when (kind) {
        "d03" -> D03_MAX_TOKENS
        "d05" -> D05_MAX_TOKENS
        else -> DEFAULT_EXPERIMENT_MAX_TOKENS
    }

    private suspend fun runConfiguration(
        configuration: ExperimentConfiguration,
        task: String,
    ): ExperimentRun {
        val call = call(configuration, configuration.systemPrompt, task)
        return call.toRun(configuration)
    }

    private suspend fun runMetaprompt(
        configuration: ExperimentConfiguration,
        task: String,
    ): ExperimentRun {
        val promptConfiguration = configuration.copy(
            label = "Metaprompt prompt generation",
            systemPrompt = "Create one concise solver system prompt for the user's task. Return only that prompt, with no solution.",
        )
        val promptCall = call(promptConfiguration, promptConfiguration.systemPrompt, task)
        if (promptCall.error != null || promptCall.response == null) {
            return promptCall.toRun(configuration, stage = "prompt_generation")
        }
        val generatedPrompt = promptCall.response.content.orEmpty().trim()
        if (generatedPrompt.isEmpty()) {
            return ExperimentRun(
                label = configuration.label,
                stage = "solution",
                provider = providerFor(configuration.model),
                model = configuration.model,
                reasoning = configuration.reasoning,
                temperature = configuration.temperature,
                maxTokens = configuration.maxTokens,
                generatedPrompt = generatedPrompt,
                processingTimeMs = promptCall.processingTimeMs,
                error = "Metaprompt generation returned empty content.",
            )
        }
        val solutionCall = call(configuration.copy(systemPrompt = generatedPrompt), generatedPrompt, task)
        return solutionCall.toRun(
            configuration,
            stage = "solution",
            generatedPrompt = generatedPrompt,
            processingTimeOffset = promptCall.processingTimeMs,
        )
    }

    private suspend fun call(
        configuration: ExperimentConfiguration,
        systemPrompt: String,
        task: String,
    ): CallOutcome {
        val startedAt = System.nanoTime()
        return try {
            val gateway = gatewayFor(configuration.model)
            val reasoning = parseReasoning(configuration.reasoning)
            val response = gateway.complete(
                ChatCompletionRequest(
                    model = configuration.model,
                    messages = listOf(
                        RequestMessage(role = "system", content = systemPrompt),
                        RequestMessage(role = "user", content = task),
                    ),
                    thinking = ThinkingOptions(type = reasoning.thinkingType),
                    reasoningEffort = reasoning.effort,
                    maxTokens = configuration.maxTokens,
                    temperature = configuration.temperature,
                    stream = false,
                ),
            )
            val choice = response.choices.firstOrNull()
                ?: error("Ответ провайдера не содержит choices.")
            val reasoningText = choice.message.reasoningContent ?: choice.message.reasoning
            CallOutcome(
                response = choice.message,
                finishReason = choice.finishReason,
                usage = response.usage,
                rawUsage = response.rawUsage,
                reasoningContentLength = reasoningText?.length,
                processingTimeMs = elapsedMs(startedAt),
            )
        } catch (error: Throwable) {
            CallOutcome(
                processingTimeMs = elapsedMs(startedAt),
                error = safeError(error),
            )
        }
    }

    private suspend fun preflightOpenRouter(): List<ExperimentPreflight> {
        val gateway = openRouterGateway
        if (gateway == null) {
            return listOf(
                ExperimentPreflight(
                    provider = "openrouter",
                    model = OPENROUTER_MODEL,
                    passed = false,
                    reasoningSupport = "not verified",
                    error = "OPENROUTER_API_KEY не задан.",
                ),
            )
        }
        return try {
            val metadata = gateway.verifyModel(OPENROUTER_MODEL)
            listOf(
                ExperimentPreflight(
                    provider = "openrouter",
                    model = OPENROUTER_MODEL,
                    passed = true,
                    reasoningSupport = if (metadata["supported_parameters"].toString().contains("reasoning")) {
                        "advertised"
                    } else {
                        "metadata does not list reasoning"
                    },
                    metadata = metadata,
                ),
            )
        } catch (error: Throwable) {
            listOf(
                ExperimentPreflight(
                    provider = "openrouter",
                    model = OPENROUTER_MODEL,
                    passed = false,
                    reasoningSupport = "not verified",
                    error = safeError(error),
                ),
            )
        }
    }

    private fun gatewayFor(model: String): ChatCompletionGateway =
        if (model.startsWith("z-ai/")) {
            openRouterGateway ?: error("OPENROUTER_API_KEY не задан.")
        } else {
            deepSeekGateway
        }

    private fun providerFor(model: String): String =
        if (model.startsWith("z-ai/")) "openrouter" else "deepseek"

    private fun validateTokenBudget(mode: TokenBudgetMode, maxTokens: Int?) {
        require(maxTokens == null || maxTokens > 0) {
            "max_tokens должен быть положительным целым числом."
        }
        require(mode != TokenBudgetMode.UNLIMITED || maxTokens == null) {
            "При режиме «Без ограничения» max_tokens должен быть пустым."
        }
    }

    private fun tokenBudgetApiValue(mode: TokenBudgetMode, maxTokens: Int?): String =
        when {
            mode == TokenBudgetMode.UNLIMITED -> "unlimited"
            maxTokens == null -> "auto"
            else -> "explicit"
        }

    private fun validateRequest(
        kind: String,
        task: String,
        referenceOrRubric: String?,
        customConfigurations: List<CustomExperimentConfiguration>,
    ) {
        require(kind in supportedKinds) { "Эксперимент не разрешён: $kind" }
        require(task.isNotEmpty()) { "Задача не должна быть пустой." }
        require(task.length <= MAX_TASK_LENGTH) {
            "Задача не должна превышать $MAX_TASK_LENGTH символов."
        }
        require(referenceOrRubric == null || referenceOrRubric.length <= 10_000) {
            "Reference или rubric не должен превышать 10000 символов."
        }
        require(customConfigurations.size <= MAX_CUSTOM_CONFIGURATIONS) {
            "Можно добавить не более $MAX_CUSTOM_CONFIGURATIONS custom configurations."
        }
    }

    private fun notesFor(
        kind: String,
        mode: TokenBudgetMode,
        maxTokens: Int?,
    ): List<String> {
        val presetNotes = when (kind) {
            "d03" -> listOf("Metaprompt выполняется двумя последовательными вызовами.")
            "d04" -> listOf("Preset меняет только temperature; custom configurations помечаются exploratory.")
            "d05" -> listOf("Preset сравнивает модели последовательно; provider errors не заменяются fallback-моделью.")
            else -> emptyList()
        }
        val budgetNote = when {
            mode == TokenBudgetMode.UNLIMITED ->
                "Token budget override (exploratory): max_tokens не отправляется; provider применяет свой default/maximum."
            maxTokens != null ->
                "Token budget override (exploratory): все preset configurations используют max_tokens=$maxTokens."
            else ->
                "Token budget: preset defaults сохранены."
        }
        return presetNotes + budgetNote
    }

    private fun CallOutcome.toRun(
        configuration: ExperimentConfiguration,
        stage: String? = null,
        generatedPrompt: String? = null,
        processingTimeOffset: Long = 0,
    ): ExperimentRun {
        val text = response?.content?.trim()
        val tokenBudgetWarning = usage?.completionTokens
            ?.takeIf { configuration.maxTokens != null && it > configuration.maxTokens }
            ?.let {
                "Provider reports completion_tokens=$it above max_tokens=${configuration.maxTokens}."
            }
        return ExperimentRun(
            label = configuration.label,
            stage = stage,
            provider = providerFor(configuration.model),
            model = configuration.model,
            reasoning = configuration.reasoning,
            temperature = configuration.temperature,
            maxTokens = configuration.maxTokens,
            systemPrompt = configuration.systemPrompt,
            generatedPrompt = generatedPrompt,
            text = text,
            textLength = text?.length,
            finishReason = finishReason,
            usage = usage,
            rawUsage = rawUsage,
            reasoningContentLength = reasoningContentLength,
            tokenBudgetWarning = tokenBudgetWarning,
            processingTimeMs = processingTimeMs + processingTimeOffset,
            responseFingerprint = text?.let(::fingerprint),
            error = error,
        )
    }

    private companion object {
        val supportedKinds = setOf("d03", "d04", "d05")
        val supportedExperimentModels = setOf(
            "deepseek-v4-flash",
            "deepseek-v4-pro",
            OPENROUTER_MODEL,
        )

        fun elapsedMs(startedAt: Long): Long =
            (System.nanoTime() - startedAt) / 1_000_000L

        fun fingerprint(text: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(text.replace(Regex("\\s+"), " ").trim().toByteArray())
                .joinToString("") { "%02x".format(it) }

        fun safeError(error: Throwable): String =
            error.message ?: error::class.simpleName ?: "unknown provider error"
    }
}

class ExperimentJobManager(
    private val service: ExperimentService,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val jobs = ConcurrentHashMap<String, JobState>()

    fun submit(kind: String, request: ExperimentRequest): ExperimentJobResponse {
        val id = UUID.randomUUID().toString()
        val total = service.configurationCount(kind, request.customConfigurations)
        val state = JobState(id = id, kind = kind, total = total)
        jobs[id] = state
        scope.launch {
            try {
                val result = service.run(kind, request) { completed, progressTotal ->
                    state.completed = completed
                    state.total = progressTotal
                    state.status = "running"
                }
                state.result = result
                state.completed = state.total
                state.status = "completed"
            } catch (error: Throwable) {
                state.error = error.message ?: error::class.simpleName ?: "Ошибка эксперимента"
                state.status = "failed"
            }
        }
        return state.snapshot()
    }

    fun get(id: String): ExperimentJobResponse? = jobs[id]?.snapshot()

    fun close() {
        scope.cancel()
    }

    private class JobState(
        val id: String,
        val kind: String,
        @Volatile var total: Int,
        @Volatile var completed: Int = 0,
        @Volatile var status: String = "queued",
        @Volatile var error: String? = null,
        @Volatile var result: ExperimentReport? = null,
    ) {
        fun snapshot(): ExperimentJobResponse = ExperimentJobResponse(
            id = id,
            kind = kind,
            status = status,
            completed = completed,
            total = total,
            error = error,
            result = result,
        )
    }
}
