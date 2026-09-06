package io.github.stolex1y.transactionimport.transport

import io.github.stolex1y.transactionimport.core.ChatCompletionGateway
import io.github.stolex1y.transactionimport.core.ChatCompletionRequest
import io.github.stolex1y.transactionimport.core.ChatCompletionResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

private val responseJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

class DeepSeekGateway(
    private val httpClient: HttpClient,
    private val apiKey: String,
    baseUrl: String = DEFAULT_BASE_URL,
) : ChatCompletionGateway {
    private val endpoint = "${baseUrl.trimEnd('/')}/chat/completions"

    init {
        require(apiKey.isNotBlank()) { "API key must not be blank." }
    }

    override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
        val response = httpClient.post(endpoint) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        val raw = response.body<JsonObject>()
        require(response.status.value in 200..299) {
            "DeepSeek returned HTTP ${response.status.value}: ${raw["error"] ?: raw}"
        }
        val parsed = responseJson.decodeFromJsonElement<ChatCompletionResponse>(raw)
        return parsed.copy(rawUsage = raw["usage"] as? JsonObject)
    }
    private companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
    }
}
