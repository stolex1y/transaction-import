package io.github.stolex1y.transactionimport.d05

import io.github.stolex1y.transactionimport.core.ChatCompletionRequest
import io.github.stolex1y.transactionimport.core.RequestMessage
import io.github.stolex1y.transactionimport.core.ResponseFormat
import io.github.stolex1y.transactionimport.core.ThinkingOptions
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenRouterGatewayTest {
    @Test
    fun mapsDeepSeekReasoningContractToOpenRouterReasoningObject() = runBlocking {
        val capturedRequests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            capturedRequests += request
            respond(
                content = """
                    {
                      "choices": [{
                        "message": {
                          "role": "assistant",
                          "content": "{}",
                          "reasoning": "internal reasoning"
                        },
                        "finish_reason": "stop"
                      }],
                      "usage": {
                        "prompt_tokens": 10,
                        "completion_tokens": 5,
                        "total_tokens": 15,
                        "cost": 0
                      }
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; explicitNulls = false })
            }
        }

        try {
            val response = OpenRouterGateway(
                httpClient = client,
                apiKey = "test-key",
                baseUrl = "https://openrouter.ai/api/v1/",
            ).complete(
                ChatCompletionRequest(
                    model = "z-ai/glm-5.2:free",
                    messages = listOf(RequestMessage("user", "statement")),
                    thinking = ThinkingOptions("enabled"),
                    reasoningEffort = "high",
                    responseFormat = ResponseFormat(type = "json_object"),
                    maxTokens = 4096,
                    temperature = 0.0,
                    stream = false,
                ),
            )

            val request = capturedRequests.single()
            val body = request.bodyAsString()
            val bodyJson = Json.parseToJsonElement(body).jsonObject
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(
                "https://openrouter.ai/api/v1/chat/completions",
                request.url.toString(),
            )
            assertEquals("Bearer test-key", request.headers[HttpHeaders.Authorization])
            assertEquals("high", bodyJson.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
            assertFalse(bodyJson.containsKey("thinking"))
            assertFalse(bodyJson.containsKey("reasoning_effort"))
            assertEquals("json_object", bodyJson.getValue("response_format").jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals("4096", bodyJson.getValue("max_tokens").jsonPrimitive.content)
            assertEquals("0.0", bodyJson.getValue("temperature").jsonPrimitive.content)
            assertFalse(body.contains("test-key"))
            assertEquals("internal reasoning", response.choices.single().message.reasoning)
            assertEquals("0", response.rawUsage?.getValue("cost")?.jsonPrimitive?.content)
            assertTrue(response.rawUsage?.containsKey("completion_tokens") == true)
        } finally {
            client.close()
        }
    }

    private fun HttpRequestData.bodyAsString(): String {
        val content = body as OutgoingContent.ByteArrayContent
        return content.bytes().decodeToString()
    }
}
