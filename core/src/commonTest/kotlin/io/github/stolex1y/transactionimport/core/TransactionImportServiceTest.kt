package io.github.stolex1y.transactionimport.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransactionImportServiceTest {
    @Test
    fun unrestrictedRequestOmitsControlsAndPreservesMetadata() = runBlocking {
        val gateway = RecordingGateway(response(content = "  extracted transaction  "))

        val result = TransactionImportService(gateway).extract("  synthetic statement  ")

        assertEquals("extracted transaction", result.text)
        assertEquals("stop", result.finishReason)
        assertEquals(20, result.usage?.totalTokens)
        assertEquals(ResponseMode.UNRESTRICTED, result.responseMode)
        assertNull(result.validation)
        assertNull(result.structured)
        assertNull(result.controls.responseFormat)
        assertNull(result.controls.maxTokens)
        assertNull(result.controls.completionCondition)

        val request = assertNotNull(gateway.request)
        assertEquals("deepseek-v4-flash", request.model)
        assertFalse(request.stream)
        assertEquals("disabled", request.thinking.type)
        assertNull(request.reasoningEffort)
        assertNull(request.responseFormat)
        assertNull(request.maxTokens)
        assertEquals(listOf("system", "user"), request.messages.map { it.role })
        assertContains(request.messages.first().content, "plain-text list, not JSON")
        assertContains(request.messages.last().content, "synthetic statement")
    }

    @Test
    fun controlledRequestReturnsValidatedDocument() = runBlocking {
        val gateway = RecordingGateway(response(content = readyJson))

        val result = TransactionImportService(gateway).extract(
            statement = "synthetic statement",
            options = ExtractionOptions(responseMode = ResponseMode.CONTROLLED_JSON),
        )

        val request = assertNotNull(gateway.request)
        assertEquals("json_object", request.responseFormat?.type)
        assertEquals(1200, request.maxTokens)
        assertContains(request.messages.first().content, "End immediately after the closing brace")

        val validation = assertNotNull(result.validation)
        assertTrue(validation.valid)
        assertTrue(validation.importable)
        assertEquals(STRUCTURED_SCHEMA_SIGNATURE, validation.schemaSignature)
        assertEquals(ImportStatus.READY, validation.status)
        assertEquals(1, validation.transactionCount)
        assertEquals(125_050L, assertNotNull(result.structured).transactions.single().amountMinor)
        assertEquals("explicit_root_object_end", result.controls.completionCondition)
    }

    @Test
    fun forwardsExplicitMaxTokensForUnrestrictedRequest() = runBlocking {
        val gateway = RecordingGateway(response(content = "plain text"))

        TransactionImportService(gateway).extract(
            statement = "statement",
            options = ExtractionOptions(maxTokens = 50_000),
        )

        assertEquals(50_000, gateway.request?.maxTokens)
    }

    @Test
    fun omitsMaxTokensForExplicitUnlimitedControlledRequest() = runBlocking {
        val gateway = RecordingGateway(response(content = readyJson))

        val result = TransactionImportService(gateway).extract(
            statement = "statement",
            options = ExtractionOptions(
                responseMode = ResponseMode.CONTROLLED_JSON,
                tokenBudgetMode = TokenBudgetMode.UNLIMITED,
            ),
        )

        assertNull(gateway.request?.maxTokens)
        assertNull(result.controls.maxTokens)
        assertTrue(assertNotNull(result.validation).valid)
    }

    @Test
    fun reportsProviderUsageAboveExplicitBudget() = runBlocking {
        val result = TransactionImportService(
            RecordingGateway(response(content = "plain text")),
        ).extract(
            statement = "statement",
            options = ExtractionOptions(maxTokens = 4),
        )

        assertContains(
            assertNotNull(result.tokenBudgetWarning),
            "completion_tokens=8",
        )
    }

    @Test
    fun rejectsUnknownFieldsInControlledResponse() = runBlocking {
        val invalidJson = readyJson.replace(
            oldValue = "\n  \"unparsed_fragments\": []",
            newValue = "\n  \"unexpected\": true,\n  \"unparsed_fragments\": []",
        )

        val result = TransactionImportService(
            RecordingGateway(response(content = invalidJson)),
        ).extract(
            statement = "statement",
            options = ExtractionOptions(responseMode = ResponseMode.CONTROLLED_JSON),
        )

        assertFalse(assertNotNull(result.validation).valid)
        assertNull(result.structured)
        assertContains(result.validation.errors, "Ответ не соответствует строгому JSON-контракту.")
    }

    @Test
    fun rejectsControlledResponseCutOffByTokenLimit() = runBlocking {
        val result = TransactionImportService(
            RecordingGateway(response(content = readyJson, finishReason = "length")),
        ).extract(
            statement = "statement",
            options = ExtractionOptions(responseMode = ResponseMode.CONTROLLED_JSON),
        )

        val validation = assertNotNull(result.validation)
        assertFalse(validation.valid)
        assertFalse(validation.importable)
        assertNull(result.structured)
        assertContains(validation.errors, "Ответ обрезан: finish_reason=length.")
    }

    @Test
    fun acceptsExplicitRefusalWithoutMakingItImportable() = runBlocking {
        val result = TransactionImportService(
            RecordingGateway(response(content = notApplicableJson)),
        ).extract(
            statement = "Write a poem about rain.",
            options = ExtractionOptions(responseMode = ResponseMode.CONTROLLED_JSON),
        )

        val validation = assertNotNull(result.validation)
        assertTrue(validation.valid)
        assertFalse(validation.importable)
        assertEquals(ImportStatus.NOT_APPLICABLE, validation.status)
        assertEquals("Input contains no financial transactions.", validation.rejectionReason)
        assertEquals(emptyList(), assertNotNull(result.structured).transactions)
    }

    @Test
    fun rejectsCategoryOutsideSyntheticCatalog() = runBlocking {
        val result = TransactionImportService(
            RecordingGateway(
                response(
                    content = readyJson.replace("food.groceries", "invented.category"),
                ),
            ),
        ).extract(
            statement = "statement",
            options = ExtractionOptions(responseMode = ResponseMode.CONTROLLED_JSON),
        )

        val validation = assertNotNull(result.validation)
        assertFalse(validation.valid)
        assertTrue(validation.errors.any { it.contains("синтетическом справочнике") })
        assertNull(result.structured)
    }

    @Test
    fun sendsSelectedModelAndReasoningEffort() = runBlocking {
        val gateway = RecordingGateway(
            ChatCompletionResponse(
                choices = listOf(
                    ChatChoice(message = ResponseMessage(content = "result")),
                ),
            ),
        )

        TransactionImportService(gateway).extract(
            statement = "statement",
            options = ExtractionOptions(
                model = "deepseek-v4-pro",
                reasoning = ReasoningLevel.HIGH,
            ),
        )

        assertEquals("deepseek-v4-pro", gateway.request?.model)
        assertEquals("enabled", gateway.request?.thinking?.type)
        assertEquals("high", gateway.request?.reasoningEffort)
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

    @Test
    fun reportsEmptyControlledContentAsValidationError() = runBlocking {
        val result = TransactionImportService(
            RecordingGateway(
                response(
                    content = " ",
                    finishReason = "length",
                    reasoningContent = "budget consumed by reasoning",
                ),
            ),
        ).extract(
            statement = "statement",
            options = ExtractionOptions(responseMode = ResponseMode.CONTROLLED_JSON),
        )

        assertEquals("", result.text)
        assertEquals(28, result.reasoningContentLength)
        assertFalse(assertNotNull(result.validation).valid)
        assertNull(result.structured)
        assertContains(
            assertNotNull(result.validation).errors,
            "Провайдер вернул пустой message.content вместо JSON.",
        )
        assertContains(
            assertNotNull(result.validation).errors,
            "Ответ обрезан: finish_reason=length.",
        )
    }


    private fun response(
        content: String,
        finishReason: String = "stop",
        reasoningContent: String? = null,
    ) = ChatCompletionResponse(
        choices = listOf(
            ChatChoice(
                message = ResponseMessage(
                    content = content,
                    reasoningContent = reasoningContent,
                ),
                finishReason = finishReason,
            ),
        ),
        usage = Usage(promptTokens = 12, completionTokens = 8, totalTokens = 20),
    )

    private val readyJson = """
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

    private val notApplicableJson = """
        {
          "status": "not_applicable",
          "rejection_reason": "Input contains no financial transactions.",
          "transactions": [],
          "unparsed_fragments": ["Write a poem about rain."]
        }
    """.trimIndent()

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
