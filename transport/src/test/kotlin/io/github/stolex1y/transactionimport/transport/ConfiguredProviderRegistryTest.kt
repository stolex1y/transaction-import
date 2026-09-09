package io.github.stolex1y.transactionimport.transport

import io.github.stolex1y.transactionimport.core.AgentConfig
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfiguredProviderRegistryTest {
    @Test
    fun buildsAnOpenAiCompatibleRequestEntirelyFromCatalogControls() = runBlocking {
        var captured: HttpRequestData? = null
        val engine = MockEngine { request ->
            captured = request
            respond(
                content = """
                    {
                      "choices": [
                        {
                          "message": { "role": "assistant", "content": "{}" },
                          "finish_reason": "stop"
                        }
                      ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        try {
            val catalog = decodeProviderCatalog(
                """
                {
                  "providers": [
                    {
                      "id": "future-provider",
                      "display_name": "Future Provider",
                      "base_url": "https://provider.example/v1",
                      "credential_env": "FUTURE_PROVIDER_KEY",
                      "models": [
                        {
                          "id": "future-model",
                          "reasoning_modes": [
                            {
                              "id": "careful",
                              "request_fields": {
                                "reasoning": { "effort": "high" },
                                "provider_preference": "quality"
                              }
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent(),
            )
            val registry = ConfiguredProviderRegistry(client, catalog) { name ->
                "test-secret".takeIf { name == "FUTURE_PROVIDER_KEY" }
            }
            assertTrue(registry.isAvailable("future-provider"))
            assertFalse(registry.isAvailable("unknown"))

            val gateway = registry.resolve(
                AgentConfig("future-provider", "future-model", "careful"),
            )
            val response = gateway.complete(
                ChatCompletionRequest(
                    model = "future-model",
                    messages = listOf(RequestMessage("user", "synthetic statement")),
                    thinking = ThinkingOptions("disabled"),
                    reasoningEffort = "low",
                    responseFormat = ResponseFormat("json_object"),
                    maxTokens = 500,
                    stream = false,
                ),
            )

            val request = requireNotNull(captured)
            assertEquals("https://provider.example/v1/chat/completions", request.url.toString())
            assertEquals("Bearer test-secret", request.headers[HttpHeaders.Authorization])
            val body = Json.parseToJsonElement(request.bodyAsString()).jsonObject
            assertFalse(body.containsKey("thinking"))
            assertFalse(body.containsKey("reasoning_effort"))
            assertEquals(
                "high",
                body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content,
            )
            assertEquals("quality", body.getValue("provider_preference").jsonPrimitive.content)
            assertEquals("json_object", body.getValue("response_format").jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals("{}", response.choices.single().message.content)
        } finally {
            client.close()
        }
    }

    @Test
    fun decodesHiddenRuntimeDefaultsAndValidatesTheirCatalogReferences() {
        val catalog = decodeProviderCatalog(
            """
            {
              "providers": [
                {
                  "id": "deepseek",
                  "display_name": "DeepSeek",
                  "base_url": "https://api.deepseek.com/v1",
                  "credential_env": "DEEPSEEK_API_KEY",
                  "models": [
                    {
                      "id": "deepseek-v4-flash",
                      "reasoning_modes": [
                        { "id": "disabled", "request_fields": {} }
                      ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        val runtime = decodeAgentRuntimeConfig(
            """
            {
              "default_provider_id": "deepseek",
              "default_model_id": "deepseek-v4-flash",
              "default_reasoning_mode_id": "disabled",
              "temperature": null,
              "max_tokens": 100000,
              "default_user_prompt": ""
            }
            """.trimIndent(),
            catalog,
        )

        assertEquals(100_000, runtime.maxTokens)
        assertNull(runtime.temperature)
        assertEquals(
            AgentConfig("deepseek", "deepseek-v4-flash", "disabled"),
            runtime.defaultAgentConfig(),
        )
    }
}

private fun HttpRequestData.bodyAsString(): String =
    (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
