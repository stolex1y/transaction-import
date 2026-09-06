package io.github.stolex1y.transactionimport.transport

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

class DeepSeekGatewayTest {
    @Test
    fun postsControlledRequestAndOmitsControlsFromUnrestrictedRequest() = runBlocking {
        val capturedRequests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            capturedRequests += request
            respond(
                content = """
                    {
                      "choices": [
                        {
                          "message": {
                            "role": "assistant",
                            "content": "parsed",
                            "reasoning_content": "internal reasoning"
                          },
                          "finish_reason": "stop"
                        }
                      ],
                      "usage": {
                        "prompt_tokens": 3,
                        "completion_tokens": 2,
                        "total_tokens": 5
                      }
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        encodeDefaults = true
                        explicitNulls = false
                    },
                )
            }
        }

        try {
            val gateway = DeepSeekGateway(
                httpClient = client,
                apiKey = "test-key",
                baseUrl = "https://api.deepseek.com/",
            )
            val response = gateway.complete(
                ChatCompletionRequest(
                    model = "deepseek-v4-pro",
                    messages = listOf(RequestMessage("user", "statement")),
                    thinking = ThinkingOptions("enabled"),
                    reasoningEffort = "high",
                    responseFormat = ResponseFormat(type = "json_object"),
                    maxTokens = 1200,
                    temperature = 0.7,
                    stream = false,
                ),
            )

            val request = capturedRequests.single()
            val body = request.bodyAsString()
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("https://api.deepseek.com/chat/completions", request.url.toString())
            assertEquals("Bearer test-key", request.headers[HttpHeaders.Authorization])
            assertTrue(body.contains("\"deepseek-v4-pro\""))
            assertTrue(body.contains("\"stream\":false"))
            val bodyJson = Json.parseToJsonElement(body).jsonObject
            assertEquals("enabled", bodyJson.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals("high", bodyJson.getValue("reasoning_effort").jsonPrimitive.content)
            assertTrue(!bodyJson.getValue("thinking").jsonObject.containsKey("reasoning_effort"))
            assertEquals(
                "json_object",
                bodyJson.getValue("response_format").jsonObject.getValue("type").jsonPrimitive.content,
            )
            assertEquals("1200", bodyJson.getValue("max_tokens").jsonPrimitive.content)
            assertEquals("0.7", bodyJson.getValue("temperature").jsonPrimitive.content)
            assertTrue(!body.contains("test-key"))
            assertEquals("parsed", response.choices.single().message.content)
            assertEquals("internal reasoning", response.choices.single().message.reasoningContent)
            assertEquals("stop", response.choices.single().finishReason)
            assertEquals(5, response.usage?.totalTokens)

            gateway.complete(
                ChatCompletionRequest(
                    model = "deepseek-v4-flash",
                    messages = listOf(RequestMessage("user", "statement")),
                    thinking = ThinkingOptions("disabled"),
                ),
            )
            val unrestrictedBody = Json.parseToJsonElement(
                capturedRequests.last().bodyAsString(),
            ).jsonObject
            assertFalse(unrestrictedBody.containsKey("response_format"))
            assertFalse(unrestrictedBody.containsKey("max_tokens"))
        } finally {
            client.close()
        }
    }

    private fun HttpRequestData.bodyAsString(): String {
        val content = body as OutgoingContent.ByteArrayContent
        return content.bytes().decodeToString()
    }
}
