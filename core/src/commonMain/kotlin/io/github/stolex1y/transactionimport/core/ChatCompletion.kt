package io.github.stolex1y.transactionimport.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

interface ChatCompletionGateway {
    val contextWindowTokens: Int?
        get() = null
    val maxOutputTokens: Int?
        get() = null

    suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse
}

class ContextWindowExceededException : IllegalStateException("Context window exceeded.")

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
    @Transient val useConfiguredReasoning: Boolean = true,
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
