package io.github.stolex1y.transactionimport.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TransactionImportServiceTest {
    @Test
    fun buildsRequestAndExtractsResponseMetadata() = runBlocking {
        val gateway = RecordingGateway(
            ChatCompletionResponse(
                choices = listOf(
                    ChatChoice(
                        message = ResponseMessage(content = "  extracted transaction  "),
                        finishReason = "stop",
                    ),
                ),
                usage = Usage(promptTokens = 12, completionTokens = 8, totalTokens = 20),
            ),
        )

        val result = TransactionImportService(gateway).extract("  synthetic statement  ")

        assertEquals("extracted transaction", result.text)
        assertEquals("stop", result.finishReason)
        assertEquals(20, result.usage?.totalTokens)
        assertEquals("deepseek-v4-flash", gateway.request?.model)
        assertEquals(false, gateway.request?.stream)
        assertEquals("disabled", gateway.request?.thinking?.type)
        assertEquals(listOf("system", "user"), gateway.request?.messages?.map { it.role })
        assertTrue(gateway.request?.messages?.last()?.content?.contains("synthetic statement") == true)
    }

    @Test
    fun rejectsBlankStatementBeforeCallingGateway() = runBlocking {
        val gateway = RecordingGateway(ChatCompletionResponse())

        assertFailsWith<IllegalArgumentException> {
            TransactionImportService(gateway).extract(" \n\t ")
        }

        assertEquals(null, gateway.request)
    }

    @Test
    fun rejectsResponseWithoutChoices() = runBlocking {
        assertFailsWith<IllegalStateException> {
            TransactionImportService(RecordingGateway(ChatCompletionResponse())).extract("statement")
        }
    }

    @Test
    fun rejectsResponseWithBlankContent() = runBlocking {
        val response = ChatCompletionResponse(
            choices = listOf(ChatChoice(message = ResponseMessage(content = "  "))),
        )

        assertFailsWith<IllegalArgumentException> {
            TransactionImportService(RecordingGateway(response)).extract("statement")
        }
    }

    private class RecordingGateway(
        private val response: ChatCompletionResponse,
    ) : ChatCompletionGateway {
        var request: ChatCompletionRequest? = null
            private set

        override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
            this.request = request
            return response
        }
    }
}
