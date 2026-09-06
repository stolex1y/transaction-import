package io.github.stolex1y.transactionimport.d05

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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExperimentTest {
    @Test
    fun runnerUsesFiveControlledCallsPerModelAndPreservesProviderControls() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            if (request.method == HttpMethod.Get) {
                respond(
                    content = """
                        {"data":[{"id":"z-ai/glm-5.2:free","supported_parameters":["reasoning"]}]}
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            } else {
                respond(
                    content = """
                        {
                          "choices": [{
                            "message": {"role":"assistant","content":"{\"status\":\"ready\",\"rejection_reason\":null,\"transactions\":[],\"unparsed_fragments\":[]}"},
                            "finish_reason":"stop"
                          }],
                          "usage": {"prompt_tokens":10,"completion_tokens":5,"total_tokens":15,"cost":0}
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; explicitNulls = false })
            }
        }

        try {
            val report = runD05Experiment(
                statement = "synthetic statement",
                fixture = "fixture.txt",
                httpClient = client,
                deepSeekApiKey = "deepseek-test-key",
                openRouterApiKey = "openrouter-test-key",
            )

            assertEquals(3, report.summaries.size)
            assertTrue(report.summaries.all { it.preflight.passed })
            assertTrue(report.summaries.all { it.runs.size == 5 })
            assertTrue(report.summaries.all { it.runs.all { run -> run.error == null } })
            assertTrue(report.summaries.all { it.runs.all { run -> run.finishReason == "stop" } })
            assertEquals(16, requests.size)

            val postBodies = requests.filter { it.method == HttpMethod.Post }.map { it.bodyAsString() }
            assertEquals(15, postBodies.size)
            assertTrue(postBodies.count { it.contains("\"thinking\":{\"type\":\"enabled\"}") } == 10)
            assertTrue(postBodies.count { it.contains("\"reasoning\":{\"effort\":\"high\"}") } == 5)
            assertTrue(postBodies.all { it.contains("\"response_format\":{\"type\":\"json_object\"}") })
            assertTrue(postBodies.all { it.contains("\"max_tokens\":4096") })
            assertTrue(postBodies.all { it.contains("\"temperature\":0.0") })
            assertNotNull(report.summaries[1].runs.first().rawUsage)
            assertEquals("0", report.summaries[1].runs.first().rawUsage?.getValue("cost")?.jsonPrimitive?.content)
        } finally {
            client.close()
        }
    }

    private fun HttpRequestData.bodyAsString(): String {
        val content = body as OutgoingContent.ByteArrayContent
        return content.bytes().decodeToString()
    }
}
