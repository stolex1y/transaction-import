package io.github.stolex1y.transactionimport.web

import io.github.stolex1y.transactionimport.core.ChatChoice
import io.github.stolex1y.transactionimport.core.ChatCompletionGateway
import io.github.stolex1y.transactionimport.core.ChatCompletionRequest
import io.github.stolex1y.transactionimport.core.ChatCompletionResponse
import io.github.stolex1y.transactionimport.core.ResponseMessage
import io.github.stolex1y.transactionimport.core.TransactionImportService
import io.github.stolex1y.transactionimport.core.Usage
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebApplicationTest {
    @Test
    fun servesPageAndReturnsUnrestrictedRunMetadata() = testApplication {
        val gateway = RecordingGateway()
        application {
            module(TransactionImportService(gateway))
        }

        val page = client.get("/")
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.bodyAsText().contains("Формат ответа"))

        val response = postExtraction(
            """
            {
              "statement": "synthetic statement",
              "model": "deepseek-v4-pro",
              "reasoning": "high",
              "mode": "unrestricted"
            }
            """.trimIndent(),
        )

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("parsed transaction", body.getValue("text").jsonPrimitive.content)
        assertEquals("unrestricted", body.getValue("mode").jsonPrimitive.content)
        assertEquals("18", body.getValue("text_length").jsonPrimitive.content)
        assertTrue(body.containsKey("processing_time_ms"))
        assertFalse(body.getValue("controls").jsonObject.containsKey("response_format"))
        assertNull(gateway.request?.responseFormat)
        assertNull(gateway.request?.maxTokens)
        assertEquals("deepseek-v4-pro", gateway.request?.model)
        assertEquals("enabled", gateway.request?.thinking?.type)
        assertEquals("high", gateway.request?.reasoningEffort)
    }

    @Test
    fun returnsControlledValidationAndAppliedControls() = testApplication {
        val gateway = RecordingGateway()
        application {
            module(TransactionImportService(gateway))
        }

        val response = postExtraction(
            """
            {
              "statement": "synthetic statement",
              "model": "deepseek-v4-flash",
              "reasoning": "disabled",
              "mode": "controlled_json"
            }
            """.trimIndent(),
        )

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val controls = body.getValue("controls").jsonObject
        val validation = body.getValue("validation").jsonObject
        assertEquals("controlled_json", body.getValue("mode").jsonPrimitive.content)
        assertEquals(
            "json_object",
            controls.getValue("response_format").jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertEquals("1200", controls.getValue("max_tokens").jsonPrimitive.content)
        assertEquals("true", validation.getValue("valid").jsonPrimitive.content)
        assertEquals("true", validation.getValue("importable").jsonPrimitive.content)
        assertEquals(
            "transaction-import.v1",
            validation.getValue("schema_signature").jsonPrimitive.content,
        )
        assertEquals("json_object", gateway.request?.responseFormat?.type)
        assertEquals(1200, gateway.request?.maxTokens)
    }

    @Test
    fun returnsEmptyControlledContentAsValidationError() = testApplication {
        val gateway = RecordingGateway(controlledContent = "")
        application {
            module(TransactionImportService(gateway))
        }

        val response = postExtraction(
            """
            {
              "statement": "synthetic statement",
              "model": "deepseek-v4-flash",
              "reasoning": "high",
              "mode": "controlled_json"
            }
            """.trimIndent(),
        )

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val validation = body.getValue("validation").jsonObject
        assertEquals("false", validation.getValue("valid").jsonPrimitive.content)
        assertEquals("0", body.getValue("text_length").jsonPrimitive.content)
        assertEquals("9", body.getValue("reasoning_content_length").jsonPrimitive.content)
        assertTrue(response.bodyAsText().contains("пустой message.content"))
    }


    @Test
    fun rejectsUnsupportedModelBeforeCallingGateway() = testApplication {
        val gateway = RecordingGateway()
        application {
            module(TransactionImportService(gateway))
        }

        val response = postExtraction(
            """
            {
              "statement": "statement",
              "model": "unsupported-model",
              "reasoning": "high",
              "mode": "unrestricted"
            }
            """.trimIndent(),
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Модель не разрешена"))
        assertNull(gateway.request)
    }

    @Test
    fun rejectsUnsupportedResponseModeBeforeCallingGateway() = testApplication {
        val gateway = RecordingGateway()
        application {
            module(TransactionImportService(gateway))
        }

        val response = postExtraction(
            """
            {
              "statement": "statement",
              "model": "deepseek-v4-flash",
              "reasoning": "disabled",
              "mode": "unknown"
            }
            """.trimIndent(),
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Неизвестный режим ответа"))
        assertNull(gateway.request)
    }

    @Test
    fun rejectsBlankStatementBeforeCallingGateway() = testApplication {
        val gateway = RecordingGateway()
        application {
            module(TransactionImportService(gateway))
        }

        val response = postExtraction(
            """
            {
              "statement": "   ",
              "model": "deepseek-v4-flash",
              "reasoning": "disabled",
              "mode": "controlled_json"
            }
            """.trimIndent(),
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Statement must not be blank"))
        assertNull(gateway.request)
    }

    @Test
    fun servesExperimentPagesAndRunsAnAsyncD04Job() = testApplication {
        val gateway = RecordingGateway()
        application {
            module(
                service = TransactionImportService(gateway),
                experimentService = ExperimentService(gateway, openRouterGateway = null),
            )
        }

        val page = client.get("/experiments/d04")
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.bodyAsText().contains("Влияние temperature"))

        val accepted = client.post("/api/experiments/d04") {
            contentType(ContentType.Application.Json)
            setBody("""{"task":"Сравните два алгоритма на синтетическом примере."}""")
        }
        assertEquals(HttpStatusCode.Accepted, accepted.status)
        val jobId = Json.parseToJsonElement(accepted.bodyAsText())
            .jsonObject
            .getValue("job_id")
            .jsonPrimitive
            .content

        var job = client.get("/api/experiments/jobs/$jobId")
        repeat(20) {
            if (job.bodyAsText().contains("\"status\":\"completed\"")) return@repeat
            kotlinx.coroutines.delay(10)
            job = client.get("/api/experiments/jobs/$jobId")
        }
        assertEquals(HttpStatusCode.OK, job.status)
        val body = Json.parseToJsonElement(job.bodyAsText()).jsonObject
        assertEquals("completed", body.getValue("status").jsonPrimitive.content)
        assertEquals("3", body.getValue("completed").jsonPrimitive.content)
        assertEquals(
            "3",
            body.getValue("result").jsonObject.getValue("runs").jsonArray.size.toString(),
        )
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.postExtraction(
        body: String,
    ) = client.post("/api/extract") {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private class RecordingGateway(
        private val controlledContent: String = readyJson,
    ) : ChatCompletionGateway {
        var request: ChatCompletionRequest? = null
            private set

        override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
            this.request = request
            val controlled = request.responseFormat?.type == "json_object"
            val content = if (controlled) controlledContent else "parsed transaction"
            return ChatCompletionResponse(
                choices = listOf(
                    ChatChoice(
                        message = ResponseMessage(
                            content = content,
                            reasoningContent = if (controlled && content.isEmpty()) "reasoning" else null,
                        ),
                        finishReason = if (controlled && content.isEmpty()) "length" else "stop",
                    ),
                ),
                usage = Usage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
            )
        }
    }

    private companion object {
        val readyJson = """
            {
              "status": "ready",
              "rejection_reason": null,
              "transactions": [
                {
                  "source_index": 1,
                  "direction": "expense",
                  "occurred_at": "2026-01-15T12:10:00",
                  "posted_at": null,
                  "amount_minor": 125050,
                  "currency": "RUB",
                  "merchant": "DEMO MARKET",
                  "category_id": "food.groceries",
                  "card_last4": "1234",
                  "needs_review": false,
                  "issues": []
                }
              ],
              "unparsed_fragments": []
            }
        """.trimIndent()
    }
}
