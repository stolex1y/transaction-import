package io.github.stolex1y.transactionimport.d05

import io.github.stolex1y.transactionimport.core.ChatCompletionGateway
import io.github.stolex1y.transactionimport.core.ChatCompletionRequest
import io.github.stolex1y.transactionimport.core.ChatCompletionResponse
import io.github.stolex1y.transactionimport.core.RequestMessage
import io.github.stolex1y.transactionimport.core.ResponseFormat
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

@Serializable
private data class OpenRouterReasoning(
    val effort: String,
)

@Serializable
private data class OpenRouterRequest(
    val model: String,
    val messages: List<RequestMessage>,
    val reasoning: OpenRouterReasoning,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
    val stream: Boolean = false,
)

class OpenRouterGateway(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : ChatCompletionGateway {
    private val endpoint = "${baseUrl.trimEnd('/')}/chat/completions"
    private val responseJson = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    init {
        require(apiKey.isNotBlank()) { "API key must not be blank." }
    }

    suspend fun verifyModel(model: String): JsonObject {
        val response = httpClient.get("${baseUrl.trimEnd('/')}/models") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
        }
        val raw = response.body<JsonObject>()
        require(response.status.value in 200..299) {
            "OpenRouter metadata returned HTTP ${response.status.value}: ${raw["error"] ?: raw}"
        }
        return raw["data"]?.jsonArray.orEmpty()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it["id"]?.jsonPrimitive?.content == model }
            ?: error("OpenRouter model '$model' is absent from /models metadata.")
    }

    override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
        val openRouterRequest = OpenRouterRequest(
            model = request.model,
            messages = request.messages,
            reasoning = OpenRouterReasoning(
                effort = request.reasoningEffort
                    ?: if (request.thinking.type == "enabled") "high" else "none",
            ),
            responseFormat = request.responseFormat,
            maxTokens = request.maxTokens,
            temperature = request.temperature,
            stream = request.stream,
        )
        val response = httpClient.post(endpoint) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(openRouterRequest)
        }
        val raw = response.body<JsonObject>()
        require(response.status.value in 200..299) {
            "OpenRouter returned HTTP ${response.status.value}: ${raw["error"] ?: raw}"
        }
        val parsed = responseJson.decodeFromJsonElement<ChatCompletionResponse>(raw)
        return parsed.copy(rawUsage = raw["usage"] as? JsonObject)
    }

    private companion object {
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
    }
}
