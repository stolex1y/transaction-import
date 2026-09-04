package io.github.stolex1y.transactionimport.cli

import io.github.stolex1y.transactionimport.core.ChatCompletionRequest
import io.github.stolex1y.transactionimport.core.RequestMessage
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeepSeekGatewayTest {
    @Test
    fun postsRequestAndDecodesCompletionWithoutNetwork() = runBlocking {
        var capturedRequest: HttpRequestData? = null
        val engine = MockEngine { request ->
            capturedRequest = request
            respond(
                content = """
                    {
                      "choices": [
                        {
                          "message": {"role": "assistant", "content": "parsed"},
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
                    },
                )
            }
        }

        try {
            val response = DeepSeekGateway(
                httpClient = client,
                apiKey = "test-key",
                baseUrl = "https://api.deepseek.com/",
            ).complete(
                ChatCompletionRequest(
                    model = "deepseek-v4-flash",
                    messages = listOf(RequestMessage("user", "statement")),
                    thinking = ThinkingOptions("disabled"),
                    stream = false,
                ),
            )

            val request = requireNotNull(capturedRequest)
            val body = request.bodyAsString()
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("https://api.deepseek.com/chat/completions", request.url.toString())
            assertEquals("Bearer test-key", request.headers[HttpHeaders.Authorization])
            assertTrue(body.contains("\"deepseek-v4-flash\""))
            assertTrue(body.contains("\"stream\":false"))
            assertTrue(body.contains("\"disabled\""))
            assertFalse(body.contains("test-key"))
            assertEquals("parsed", response.choices.single().message.content)
            assertEquals("stop", response.choices.single().finishReason)
            assertEquals(5, response.usage?.totalTokens)
        } finally {
            client.close()
        }
    }

    private fun HttpRequestData.bodyAsString(): String {
        val content = body as OutgoingContent.ByteArrayContent
        return content.bytes().decodeToString()
    }
}
