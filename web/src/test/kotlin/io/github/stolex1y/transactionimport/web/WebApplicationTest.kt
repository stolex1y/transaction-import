package io.github.stolex1y.transactionimport.web

import io.github.stolex1y.transactionimport.core.ChatCompletionGateway
import io.github.stolex1y.transactionimport.core.ChatCompletionRequest
import io.github.stolex1y.transactionimport.core.ChatCompletionResponse
import io.github.stolex1y.transactionimport.core.ChatChoice
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebApplicationTest {
    @Test
    fun servesPageAndUsesSelectedModelAndReasoning() = testApplication {
        val gateway = RecordingGateway()
        application {
            module(TransactionImportService(gateway))
        }

        val page = client.get("/")
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.bodyAsText().contains("Импорт операций"))

        val response = client.post("/api/extract") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "statement": "synthetic statement",
                  "model": "deepseek-v4-pro",
                  "reasoning": "high"
                }
                """.trimIndent(),
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val responseBody = response.bodyAsText()
        assertTrue(responseBody.contains("parsed transaction"))
        assertTrue(responseBody.contains("\"processing_time_ms\":"))
        assertEquals("deepseek-v4-pro", gateway.request?.model)
        assertEquals("enabled", gateway.request?.thinking?.type)
        assertEquals("high", gateway.request?.reasoningEffort)
    }

    @Test
    fun rejectsUnsupportedModelBeforeCallingGateway() = testApplication {
        val gateway = RecordingGateway()
        application {
            module(TransactionImportService(gateway))
        }

        val response = client.post("/api/extract") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "statement": "statement",
                  "model": "unsupported-model",
                  "reasoning": "high"
                }
                """.trimIndent(),
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Модель не разрешена"))
        assertEquals(null, gateway.request)
    }

    @Test
    fun rejectsBlankStatementBeforeCallingGateway() = testApplication {
        val gateway = RecordingGateway()
        application {
            module(TransactionImportService(gateway))
        }

        val response = client.post("/api/extract") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "statement": "   ",
                  "model": "deepseek-v4-flash",
                  "reasoning": "disabled"
                }
                """.trimIndent(),
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Statement must not be blank"))
        assertEquals(null, gateway.request)
    }

    private class RecordingGateway : ChatCompletionGateway {
        var request: ChatCompletionRequest? = null
            private set

        override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
            this.request = request
            return ChatCompletionResponse(
                choices = listOf(
                    ChatChoice(
                        message = ResponseMessage(content = "parsed transaction"),
                        finishReason = "stop",
                    ),
                ),
                usage = Usage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
            )
        }
    }
}
