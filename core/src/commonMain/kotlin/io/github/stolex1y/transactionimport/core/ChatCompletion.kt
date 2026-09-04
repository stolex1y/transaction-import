package io.github.stolex1y.transactionimport.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

interface ChatCompletionGateway {
    suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse
}

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<RequestMessage>,
    val thinking: ThinkingOptions,
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
data class ChatCompletionResponse(
    val choices: List<ChatChoice> = emptyList(),
    val usage: Usage? = null,
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
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)

data class ExtractionResult(
    val text: String,
    val finishReason: String?,
    val usage: Usage?,
)
