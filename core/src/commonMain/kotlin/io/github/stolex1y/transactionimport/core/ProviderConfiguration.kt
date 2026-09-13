package io.github.stolex1y.transactionimport.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ProviderCatalog(
    val providers: List<ProviderDefinition>,
) {
    fun validated(): ProviderCatalog {
        require(providers.isNotEmpty()) { "Конфигурация должна содержать хотя бы одного провайдера." }
        require(providers.map { it.id }.distinct().size == providers.size) {
            "Идентификаторы провайдеров должны быть уникальными."
        }
        providers.forEach(ProviderDefinition::validate)
        return this
    }

    fun resolve(config: AgentConfig): ResolvedAgentConfig {
        config.validate()
        val provider = providers.singleOrNull { it.id == config.providerId }
            ?: throw IllegalArgumentException("Провайдер не найден: ${config.providerId}")
        val model = provider.models.singleOrNull { it.id == config.modelId }
            ?: throw IllegalArgumentException(
                "Модель ${config.modelId} не настроена для провайдера ${provider.id}.",
            )
        val reasoningMode = model.reasoningModes.singleOrNull { it.id == config.reasoningModeId }
            ?: throw IllegalArgumentException(
                "Режим reasoning ${config.reasoningModeId} не настроен для модели ${model.id}.",
            )
        return ResolvedAgentConfig(config, provider, model, reasoningMode)
    }
}

@Serializable
data class ProviderDefinition(
    val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("base_url") val baseUrl: String,
    @SerialName("chat_completions_path") val chatCompletionsPath: String = "/chat/completions",
    @SerialName("credential_env") val credentialEnv: String,
    val models: List<ProviderModelDefinition>,
) {
    internal fun validate() {
        require(id.isNotBlank()) { "Идентификатор провайдера не должен быть пустым." }
        require(displayName.isNotBlank()) { "Название провайдера $id не должно быть пустым." }
        require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://localhost")) {
            "base_url провайдера $id должен использовать HTTPS (localhost разрешён для тестов)."
        }
        require(chatCompletionsPath.startsWith("/") && chatCompletionsPath.length > 1) {
            "chat_completions_path провайдера $id должен начинаться с '/'."
        }
        require(ENV_NAME.matches(credentialEnv)) {
            "credential_env провайдера $id должен быть именем переменной окружения."
        }
        require(models.isNotEmpty()) { "Провайдер $id должен содержать хотя бы одну модель." }
        require(models.map { it.id }.distinct().size == models.size) {
            "Идентификаторы моделей провайдера $id должны быть уникальными."
        }
        models.forEach { it.validate(id) }
    }

    private companion object {
        val ENV_NAME = Regex("^[A-Z_][A-Z0-9_]*$")
    }
}

@Serializable
data class ProviderModelDefinition(
    val id: String,
    @SerialName("display_name") val displayName: String = id,
    @SerialName("reasoning_modes") val reasoningModes: List<ReasoningModeDefinition>,
    @SerialName("context_window_tokens") val contextWindowTokens: Int? = null,
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
) {
    internal fun validate(providerId: String) {
        require(id.isNotBlank()) { "Идентификатор модели провайдера $providerId не должен быть пустым." }
        require(displayName.isNotBlank()) { "Название модели $id не должно быть пустым." }
        require(contextWindowTokens == null || contextWindowTokens > 0) {
            "context_window_tokens модели $providerId/$id должен быть положительным."
        }
        require(maxOutputTokens == null || maxOutputTokens > 0) {
            "max_output_tokens модели $providerId/$id должен быть положительным."
        }
        require(reasoningModes.isNotEmpty()) { "Модель $id должна содержать режим reasoning." }
        require(reasoningModes.map { it.id }.distinct().size == reasoningModes.size) {
            "Идентификаторы reasoning модели $id должны быть уникальными."
        }
        reasoningModes.forEach { it.validate(providerId, id) }
    }
}

@Serializable
data class ReasoningModeDefinition(
    val id: String,
    @SerialName("display_name") val displayName: String = id,
    @SerialName("request_fields") val requestFields: JsonObject = JsonObject(emptyMap()),
) {
    internal fun validate(providerId: String, modelId: String) {
        require(id.isNotBlank()) {
            "Идентификатор reasoning для $providerId/$modelId не должен быть пустым."
        }
        require(displayName.isNotBlank()) {
            "Название reasoning $id для $providerId/$modelId не должно быть пустым."
        }
        val reserved = requestFields.keys.intersect(RESERVED_REQUEST_FIELDS)
        require(reserved.isEmpty()) {
            "reasoning $providerId/$modelId/$id переопределяет защищённые поля: ${reserved.sorted().joinToString()}."
        }
    }

    private companion object {
        val RESERVED_REQUEST_FIELDS = setOf(
            "model",
            "messages",
            "response_format",
            "max_tokens",
            "temperature",
            "stream",
        )
    }
}

@Serializable
data class AgentConfig(
    @SerialName("provider_id") val providerId: String,
    @SerialName("model_id") val modelId: String,
    @SerialName("reasoning_mode_id") val reasoningModeId: String,
) {
    fun validate() {
        require(providerId.isNotBlank()) { "provider_id не должен быть пустым." }
        require(modelId.isNotBlank()) { "model_id не должен быть пустым." }
        require(reasoningModeId.isNotBlank()) { "reasoning_mode_id не должен быть пустым." }
    }
}

@Serializable
enum class ContextStrategy {
    @SerialName("sliding_window")
    SLIDING_WINDOW,

    @SerialName("sticky_facts")
    STICKY_FACTS,

    @SerialName("branching")
    BRANCHING,
    @SerialName("summary")
    SUMMARY,

    @SerialName("token_aware_summary")
    TOKEN_AWARE_SUMMARY,
}

@Serializable
data class ContextManagementConfig(
    val strategy: ContextStrategy = ContextStrategy.SUMMARY,
    @SerialName("recent_messages") val recentMessages: Int = 10,
    @SerialName("summary_batch_messages") val summaryBatchMessages: Int = 10,
    @SerialName("summary_max_tokens") val summaryMaxTokens: Int = 1_024,
    @SerialName("summary_threshold_tokens") val summaryThresholdTokens: Int? = null,
    @SerialName("summary_threshold_percent") val summaryThresholdPercent: Int? = null,
    @SerialName("summary_reserve_tokens") val summaryReserveTokens: Int? = null,
    @SerialName("summary_keep_recent_tokens") val summaryKeepRecentTokens: Int = 20_000,
    @SerialName("max_facts") val maxFacts: Int = 32,
    @SerialName("fact_value_max_chars") val factValueMaxChars: Int = 500,
    @SerialName("facts_max_tokens") val factsMaxTokens: Int = 1_024,
) {
    internal fun validate() {
        when (strategy) {
            ContextStrategy.SLIDING_WINDOW,
            ContextStrategy.SUMMARY,
            ContextStrategy.TOKEN_AWARE_SUMMARY -> require(recentMessages > 0) {
                "context_management.recent_messages должен быть положительным."
            }

            ContextStrategy.STICKY_FACTS -> {
                require(recentMessages > 0) {
                    "context_management.recent_messages должен быть положительным."
                }
                require(maxFacts > 0) {
                    "context_management.max_facts должен быть положительным."
                }
                require(factValueMaxChars > 0) {
                    "context_management.fact_value_max_chars должен быть положительным."
                }
                require(factsMaxTokens > 0) {
                    "context_management.facts_max_tokens должен быть положительным."
                }
            }

            ContextStrategy.BRANCHING -> Unit
        }
        if (strategy == ContextStrategy.SUMMARY || strategy == ContextStrategy.TOKEN_AWARE_SUMMARY) {
            require(summaryBatchMessages > 0) {
                "context_management.summary_batch_messages должен быть положительным."
            }
            require(summaryMaxTokens > 0) {
                "context_management.summary_max_tokens должен быть положительным."
            }
        }
        if (strategy == ContextStrategy.TOKEN_AWARE_SUMMARY) {
            require(summaryThresholdTokens == null || summaryThresholdTokens > 0) {
                "context_management.summary_threshold_tokens должен быть положительным."
            }
            require(summaryThresholdPercent == null || summaryThresholdPercent in 1..99) {
                "context_management.summary_threshold_percent должен быть в диапазоне 1..99."
            }
            require(summaryReserveTokens == null || summaryReserveTokens > 0) {
                "context_management.summary_reserve_tokens должен быть положительным."
            }
            require(summaryKeepRecentTokens > 0) {
                "context_management.summary_keep_recent_tokens должен быть положительным."
            }
        }
    }
}

@Serializable
data class ContextCompressionConfig(
    val enabled: Boolean = true,
    @SerialName("recent_messages") val recentMessages: Int = 10,
    @SerialName("summary_batch_messages") val summaryBatchMessages: Int = 10,
    @SerialName("summary_max_tokens") val summaryMaxTokens: Int = 1_024,
) {
    internal fun validate() {
        require(recentMessages > 0) {
            "context_compression.recent_messages должен быть положительным."
        }
        require(summaryBatchMessages > 0) {
            "context_compression.summary_batch_messages должен быть положительным."
        }
        require(summaryMaxTokens > 0) {
            "context_compression.summary_max_tokens должен быть положительным."
        }
    }
}

@Serializable
data class AgentRuntimeConfig(
    @SerialName("default_provider_id") val defaultProviderId: String = "deepseek",
    @SerialName("default_model_id") val defaultModelId: String = "deepseek-v4-flash",
    @SerialName("default_reasoning_mode_id") val defaultReasoningModeId: String = "disabled",
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int = 100_000,
    @SerialName("default_user_prompt") val defaultUserPrompt: String = "",
    @SerialName("context_compression")
    val contextCompression: ContextCompressionConfig = ContextCompressionConfig(),
    @SerialName("context_management")
    val contextManagement: ContextManagementConfig? = null,
) {
    fun defaultAgentConfig(): AgentConfig = AgentConfig(
        providerId = defaultProviderId,
        modelId = defaultModelId,
        reasoningModeId = defaultReasoningModeId,
    )

    fun sessionContextManagement(): ContextManagementConfig? =
        contextManagement ?: contextCompression
            .takeIf(ContextCompressionConfig::enabled)
            ?.let {
                ContextManagementConfig(
                    strategy = ContextStrategy.SUMMARY,
                    recentMessages = it.recentMessages,
                    summaryBatchMessages = it.summaryBatchMessages,
                    summaryMaxTokens = it.summaryMaxTokens,
                )
            }

    fun validated(catalog: ProviderCatalog): AgentRuntimeConfig {
        require(temperature == null || temperature in 0.0..2.0) {
            "temperature должен быть в диапазоне 0.0..2.0."
        }
        require(maxTokens > 0) { "max_tokens должен быть положительным." }
        contextCompression.validate()
        contextManagement?.validate()
        catalog.resolve(defaultAgentConfig())
        return this
    }
}

data class ResolvedAgentConfig(
    val config: AgentConfig,
    val provider: ProviderDefinition,
    val model: ProviderModelDefinition,
    val reasoningMode: ReasoningModeDefinition,
)

fun interface AgentGatewayResolver {
    fun resolve(config: AgentConfig): ChatCompletionGateway
}

class ProviderUnavailableException(message: String) : IllegalStateException(message)
