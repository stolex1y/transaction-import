package io.github.stolex1y.transactionimport.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

interface ChatCompletionGateway {
    suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse
}

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<RequestMessage>,
    val thinking: ThinkingOptions,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
    val stream: Boolean = false,
)

@Serializable
data class RequestMessage(
    val role: String,
    val content: String,
)

@Serializable
data class ThinkingOptions(
    val type: String,
)

@Serializable
data class ResponseFormat(
    val type: String,
)

enum class ReasoningLevel(
    val thinkingType: String,
    val effort: String?,
) {
    DISABLED(thinkingType = "disabled", effort = null),
    LOW(thinkingType = "enabled", effort = "low"),
    HIGH(thinkingType = "enabled", effort = "high"),
    MAX(thinkingType = "enabled", effort = "max"),
}

enum class ResponseMode {
    UNRESTRICTED,
    CONTROLLED_JSON,
}

data class ExtractionOptions(
    val model: String = DEFAULT_MODEL,
    val reasoning: ReasoningLevel = ReasoningLevel.DISABLED,
    val responseMode: ResponseMode = ResponseMode.UNRESTRICTED,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
) {
    fun thinkingOptions(): ThinkingOptions =
        ThinkingOptions(type = reasoning.thinkingType)
}

@Serializable
data class ChatCompletionResponse(
    val choices: List<ChatChoice> = emptyList(),
    val usage: Usage? = null,
    val rawUsage: JsonObject? = null,
)

@Serializable
data class ChatChoice(
    val message: ResponseMessage,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ResponseMessage(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    val reasoning: String? = null,
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)

@Serializable
data class AppliedResponseControls(
    @SerialName("response_format") val responseFormat: ResponseFormat?,
    @SerialName("max_tokens") val maxTokens: Int?,
    @SerialName("completion_condition") val completionCondition: String?,
    @SerialName("category_ids") val categoryIds: List<String>,
)

data class ExtractionResult(
    val text: String,
    val finishReason: String?,
    val usage: Usage?,
    val reasoningContentLength: Int? = null,
    val responseMode: ResponseMode,
    val controls: AppliedResponseControls,
    val structured: StructuredImport?,
    val validation: StructuredValidation?,
    val rawUsage: JsonObject? = null,
)
