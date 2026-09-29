package io.github.stolex1y.transactionimport.web

import io.github.stolex1y.transactionimport.core.AgentGatewayResolver
import io.github.stolex1y.transactionimport.core.AgentResponseException
import io.github.stolex1y.transactionimport.core.AgentRuntimeConfig
import io.github.stolex1y.transactionimport.core.ChatChoice
import io.github.stolex1y.transactionimport.core.ChatCompletionGateway
import io.github.stolex1y.transactionimport.core.ChatCompletionRequest
import io.github.stolex1y.transactionimport.core.ChatCompletionResponse
import io.github.stolex1y.transactionimport.core.ChatFunctionCall
import io.github.stolex1y.transactionimport.core.ChatToolCall
import io.github.stolex1y.transactionimport.core.McpToolLoopConfig
import io.github.stolex1y.transactionimport.core.ResponseMessage
import io.github.stolex1y.transactionimport.core.ReceiptAssociationStatus
import io.github.stolex1y.transactionimport.core.ReceiptStatus
import io.github.stolex1y.transactionimport.core.ConfirmedDecisionScope
import io.github.stolex1y.transactionimport.core.Usage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
class NativeMcpAgentTest {
    @Test
    fun toolLoopNormalizesOpaqueRefsAndRequiresConfirmation() = runBlocking {
        val database = Files.createTempFile("native-mcp-agent-", ".sqlite")
        try {
            val gateway = NativeLoopGateway()
            val dependencies = fakeAgentDependencies(database.toString(), gateway)
            val runtime = dependencies.runtimeConfig.copy(
                mcpToolLoop = McpToolLoopConfig(
                    enabled = true,
                    maxIterations = 4,
                    maxToolCalls = 4,
                ),
            )
            val provider = FakeNativeMcpProvider()
            val native = NativeMcpAgent(
                agent = dependencies.agent,
                gatewayResolver = AgentGatewayResolver { gateway },
                mcpTools = provider,
                runtimeConfig = runtime,
                idGenerator = { "preview-token" },
                dateContextProvider = { NativeMcpDateContext("2026-09-30", "UTC") },
            )
            val session = dependencies.agent.createSession(
                title = "MCP test",
                config = runtime.defaultAgentConfig(),
                contextManagement = runtime.sessionContextManagement(),
            )

            val result = native.handle(
                sessionId = session.session.id,
                expectedRevision = session.session.revision,
                text = "Загрузи операции за сентябрь",
            )
            val handled = assertIs<NativeMcpHandlingResult.Handled>(result)
            val preview = assertNotNull(handled.response.mcpPreview)
            assertEquals(1, preview.transactions.size)
            assertEquals("Основной счёт", preview.transactions.single().sourceLabel)
            assertEquals("", preview.transactions.single().description)
            assertEquals("RUB", preview.transactions.single().currency)
            assertEquals("food.cafe", preview.transactions.single().categoryId)
            val firstMessages = gateway.requests.first().messages
            assertTrue(firstMessages[0].content.contains("Ты маршрутизатор"))
            assertTrue(firstMessages[1].content.contains("Текущий календарный день: 2026-09-30"))
            assertEquals("user", firstMessages.last().role)
            assertEquals("Загрузи операции за сентябрь", firstMessages.last().content)
            assertEquals(0L, handled.response.state.session.revision - 1L)
            assertTrue(handled.response.state.draft == null)
            assertTrue(handled.response.state.messages.size == 2)
            assertTrue(handled.response.state.messages.none { it.content.contains("raw-account") })
            assertTrue(handled.response.state.messages.none { it.content.contains("raw-transaction") })
            assertEquals(
                "raw-account-001",
                provider.transactionArguments["account_ref"]?.jsonPrimitive?.content,
            )
            assertTrue(gateway.requests.all { request ->
                request.messages.none { message ->
                    message.content.contains("raw-account") || message.content.contains("raw-transaction")
                }
            })
            assertTrue(gateway.requests[1].messages.any { it.content.contains("account_alias") })
            assertTrue(gateway.requests[1].messages.none { it.content.contains("account_ref") })
            assertTrue(gateway.requests[1].messages.any { it.content.contains("\"balance\":\"1000.00 RUB\"") })

            assertEquals(1, handled.response.state.merchantCanonicalCandidates.size, handled.response.state.toString())
            val canonicalCandidate = handled.response.state.merchantCanonicalCandidates.first()
            val acceptedCanonical = native.acceptMerchantCanonicalCandidate(
                sessionId = session.session.id,
                expectedRevision = handled.response.state.session.revision,
                candidateId = canonicalCandidate.id,
                previewId = preview.id,
            )
            val updatedPreview = assertNotNull(acceptedCanonical.mcpPreview)
            assertEquals("КофеБон", updatedPreview.transactions.single().merchant)

            val confirmed = native.confirm(
                sessionId = session.session.id,
                expectedRevision = acceptedCanonical.state.session.revision,
                previewId = updatedPreview.id,
            )
            val row = assertNotNull(confirmed.draft).transactions.single()
            assertFalse(row.included)
            assertEquals("expense", row.transaction.direction.name.lowercase())
            assertEquals(12_500L, row.transaction.amountMinor)
            assertEquals("КофеБон", row.transaction.merchant)
            assertEquals("food.cafe", row.transaction.categoryId)
            assertEquals(4L, confirmed.sessionRevision())
            val correctionGateway = NoToolGateway()
            val correctionNative = NativeMcpAgent(
                agent = dependencies.agent,
                gatewayResolver = AgentGatewayResolver { correctionGateway },
                mcpTools = provider,
                runtimeConfig = runtime,
                receiptInstructionClassifier = NO_RECEIPT_INTENT_CLASSIFIER,
                dateContextProvider = { NativeMcpDateContext("2026-09-30", "UTC") },
            )
            val routed = correctionNative.handle(
                sessionId = session.session.id,
                expectedRevision = confirmed.sessionRevision(),
                text = "Примени правило к текущему черновику.",
            )
            assertIs<NativeMcpHandlingResult.NotHandled>(routed)
            assertTrue(correctionGateway.requests.isEmpty())
            val unmarkedRouted = correctionNative.handle(
                sessionId = session.session.id,
                expectedRevision = confirmed.sessionRevision(),
                text = "Coffeebon в КофеБон",
            )
            assertIs<NativeMcpHandlingResult.NotHandled>(unmarkedRouted)
            assertTrue(correctionGateway.requests.isEmpty())
            val appendRouteGateway = NoToolGateway()
            val appendRouteNative = NativeMcpAgent(
                agent = dependencies.agent,
                gatewayResolver = AgentGatewayResolver { appendRouteGateway },
                mcpTools = provider,
                runtimeConfig = runtime,
                receiptInstructionClassifier = NO_RECEIPT_INTENT_CLASSIFIER,
                dateContextProvider = { NativeMcpDateContext("2026-09-30", "UTC") },
            )
            val ambiguousAppend = appendRouteNative.handle(
                sessionId = session.session.id,
                expectedRevision = confirmed.sessionRevision(),
                text = "Добавь транзакции с совместного счета",
            )
            assertIs<NativeMcpHandlingResult.NotHandled>(ambiguousAppend)
            assertTrue(appendRouteGateway.requests.isEmpty())
            val missingPeriodAppend = appendRouteNative.handle(
                sessionId = session.session.id,
                expectedRevision = confirmed.sessionRevision(),
                text = "Добавь вторую выписку",
            )
            assertIs<NativeMcpHandlingResult.NotHandled>(missingPeriodAppend)
            assertTrue(appendRouteGateway.requests.isEmpty())
            val appendRouted = appendRouteNative.handle(
                sessionId = session.session.id,
                expectedRevision = confirmed.sessionRevision(),
                text = "Добавь транзакции с совместного счета за неделю с 8 сентября",
            )
            assertIs<NativeMcpHandlingResult.NotHandled>(appendRouted)
            assertEquals(1, appendRouteGateway.requests.size)

            val appendGateway = NativeLoopGateway(
                from = "2026-09-08",
                to = "2026-09-14",
                reviewTransactionId = "2",
            )
            val appendProvider = FakeNativeMcpProvider(transactionDate = "2026-09-10")
            val appendNative = NativeMcpAgent(
                agent = dependencies.agent,
                gatewayResolver = AgentGatewayResolver { appendGateway },
                mcpTools = appendProvider,
                runtimeConfig = runtime,
                dateContextProvider = { NativeMcpDateContext("2026-09-30", "UTC") },
                receiptInstructionClassifier = NO_RECEIPT_INTENT_CLASSIFIER,
            )
            val appendHandled = assertIs<NativeMcpHandlingResult.Handled>(
                appendNative.handle(
                    sessionId = session.session.id,
                    expectedRevision = confirmed.sessionRevision(),
                    text = "Добавь транзакции с совместного счета за неделю с 8 сентября",
                ),
            )
            val appendPreview = assertNotNull(appendHandled.response.mcpPreview)
            assertEquals(1, appendPreview.transactions.size)
            assertEquals("2026-09-08", appendProvider.transactionArguments["from"]?.jsonPrimitive?.content)
            assertEquals("2026-09-14", appendProvider.transactionArguments["to"]?.jsonPrimitive?.content)
            val appended = appendNative.confirm(
                sessionId = session.session.id,
                expectedRevision = appendHandled.response.state.session.revision,
                previewId = appendPreview.id,
            )
            val appendedDraft = assertNotNull(appended.draft)
            assertEquals(2, appendedDraft.transactions.size)
            assertEquals("КофеБон", appendedDraft.transactions.last().transaction.merchant)
            assertEquals(
                confirmed.draft!!.transactions.first().id,
                appendedDraft.transactions.first().id,
            )
            assertEquals(
                confirmed.draft!!.transactions.first().transaction.amountMinor,
                appendedDraft.transactions.first().transaction.amountMinor,
            )
            assertEquals(confirmed.sessionRevision() + 2, appended.sessionRevision())
        } finally {
            database.deleteIfExists()
        }
    }
    @Test
    fun receiptQuestionDoesNotStartAssociationOrMutateDraft() = runBlocking {
        val database = Files.createTempFile("native-receipt-question-", ".sqlite")
        val gateway = FakeAgentGateway(
            ArrayDeque(
                listOf(
                    FakeAgentGateway.READY_DRAFT_JSON,
                    """{"intent":"none"}""",
                ),
            ),
        )
        val dependencies = fakeAgentDependencies(database.toString(), gateway)
        val source = ReceiptAssociationFixture()
        val session = dependencies.agent.createSession(
            "Синтетический вопрос о квитанциях",
            dependencies.runtimeConfig.defaultAgentConfig(),
        )
        try {
            val imported = dependencies.agent.sendMessage(
                sessionId = session.session.id,
                expectedRevision = session.session.revision,
                text = "Синтетическая выписка",
            )
            val native = NativeMcpAgent(
                agent = dependencies.agent,
                gatewayResolver = AgentGatewayResolver { gateway },
                mcpTools = source,
                runtimeConfig = dependencies.runtimeConfig,
            )

            val currentQuestion =
                "Можно ли в принципе сопоставлять электронные документы с операциями?" +
                    "x".repeat(1_400)
            val result = native.handle(
                sessionId = session.session.id,
                expectedRevision = imported.session.revision,
                text = currentQuestion,
            )

            assertIs<NativeMcpHandlingResult.NotHandled>(result)
            val after = dependencies.agent.getSession(session.session.id)
            assertEquals(imported.session.revision, after.session.revision)
            assertEquals(imported.messages, after.messages)
            assertEquals(imported.draft, after.draft)
            assertEquals(0, source.searchCalls)
            assertTrue(dependencies.agent.getPreferences().confirmedDecisions.isEmpty())
            assertEquals(currentQuestion.take(1_200), gateway.requests.last().messages.last().content)
            assertEquals(64, gateway.requests.last().maxTokens)
        } finally {
            dependencies.scheduler?.close()
            database.deleteIfExists()
        }
    }
    @Test
    fun malformedIntentClassificationLeavesDraftAndRulesUnchanged() = runBlocking {
        val database = Files.createTempFile("native-receipt-invalid-intent-", ".sqlite")
        val rawOutput = "synthetic-private-model-output"
        val gateway = FakeAgentGateway(
            ArrayDeque(
                listOf(
                    FakeAgentGateway.READY_DRAFT_JSON,
                    """{"intent":{"secret":"$rawOutput"}}""",
                ),
            ),
        )
        val dependencies = fakeAgentDependencies(database.toString(), gateway)
        val source = ReceiptAssociationFixture()
        val session = dependencies.agent.createSession(
            "Синтетический неверный ответ классификатора",
            dependencies.runtimeConfig.defaultAgentConfig(),
        )
        try {
            val imported = dependencies.agent.sendMessage(
                sessionId = session.session.id,
                expectedRevision = session.session.revision,
                text = "Синтетическая выписка",
            )
            val native = NativeMcpAgent(
                agent = dependencies.agent,
                gatewayResolver = AgentGatewayResolver { gateway },
                mcpTools = source,
                runtimeConfig = dependencies.runtimeConfig,
            )

            val failure = assertFailsWith<AgentResponseException> {
                native.handle(
                    sessionId = session.session.id,
                    expectedRevision = imported.session.revision,
                    text = "Свяжи операцию с электронной квитанцией и запомни это правило.",
                )
            }

            val after = dependencies.agent.getSession(session.session.id)
            assertFalse(failure.message.orEmpty().contains(rawOutput))
            assertEquals(imported.session.revision, after.session.revision)
            assertEquals(imported.messages, after.messages)
            assertEquals(imported.draft, after.draft)
            assertTrue(dependencies.agent.getPreferences().confirmedDecisions.isEmpty())
            assertEquals(0, source.searchCalls)
        } finally {
            dependencies.scheduler?.close()
            database.deleteIfExists()
        }
    }


    @Test
    fun naturalLanguageAssociationUpdatesDraftAndPersistsScopedRule() = runBlocking {
        val database = Files.createTempFile("native-receipt-association-", ".sqlite")
        val gateway = FakeAgentGateway(
            ArrayDeque(
                listOf(
                    FakeAgentGateway.READY_DRAFT_JSON,
                    """{"intent":"remember_rule"}""",
                ),
            ),
        )
        val dependencies = fakeAgentDependencies(database.toString(), gateway)
        val source = ReceiptAssociationFixture()
        val session = dependencies.agent.createSession(
            "Синтетическая связь чека",
            dependencies.runtimeConfig.defaultAgentConfig(),
        )
        try {
            val imported = dependencies.agent.sendMessage(
                sessionId = session.session.id,
                expectedRevision = session.session.revision,
                text = "Синтетическая выписка",
            )
            val original = imported.draft!!.transactions.first()
            val selectorInputs = mutableListOf<ReceiptMatchChoice>()
            val native = NativeMcpAgent(
                agent = dependencies.agent,
                gatewayResolver = AgentGatewayResolver { gateway },
                mcpTools = source,
                runtimeConfig = dependencies.runtimeConfig,
                receiptMatchSelector = ReceiptMatchSelector { _, _, choices, instruction, rules ->
                    assertTrue(instruction.contains("Сохрани"))
                    assertTrue(rules.isEmpty())
                    selectorInputs += choices
                    ReceiptSelectionResponseView(choices.single().alias, 0.96)
                },
            )

            val handled = assertIs<NativeMcpHandlingResult.Handled>(
                native.handle(
                    sessionId = session.session.id,
                    expectedRevision = imported.session.revision,
                    text = "Сохрани для будущего: операция «пополнение проездного» сопоставляется с электронной квитанцией от Синтетического продавца.",
                ),
            )
            val associated = handled.response.state.draft!!.transactions.first()
            val preferences = dependencies.agent.getPreferences()

            assertEquals(imported.session.revision + 1, handled.response.state.session.revision)
            assertEquals(original.transaction.merchant, associated.transaction.merchant)
            assertEquals(original.transaction.categoryId, associated.transaction.categoryId)
            assertEquals(original.description, associated.description)
            assertEquals(ReceiptAssociationStatus.MATCHED, associated.transaction.receiptAssociation?.status)
            assertEquals(listOf("Синтетическая позиция"), associated.transaction.items.map { it.name })
            assertEquals(1, source.detailCalls)
            assertEquals(1, selectorInputs.size)
            assertEquals("Синтетическая торговая точка", selectorInputs.single().settlementPlace)
            assertFalse(selectorInputs.toString().contains("synthetic-private-receipt-key"))
            assertFalse(selectorInputs.toString().contains("synthetic-fiscal-id"))
            assertFalse(selectorInputs.toString().contains("synthetic-qr-payload"))
            assertFalse(selectorInputs.toString().contains("Синтетический кассир"))
            assertFalse(selectorInputs.toString().contains("Синтетическая позиция"))
            assertEquals(1, preferences.confirmedDecisions.size)
            assertEquals(
                "Сохрани для будущего: операция «пополнение проездного» сопоставляется с электронной квитанцией от Синтетического продавца.",
                preferences.confirmedDecisions.single().text,
            )
            assertEquals(
                ConfirmedDecisionScope.RECEIPT_MATCHING,
                preferences.confirmedDecisions.single().scope,
            )
            assertEquals(
                ReceiptAssociationStatus.UNMATCHED,
                handled.response.state.draft!!.transactions.last().transaction.receiptAssociation?.status,
            )
            assertTrue(handled.response.assistantText.contains("не мешает экспорту"))
        } finally {
            dependencies.scheduler?.close()
            database.deleteIfExists()
        }
    }

    @Test
    fun malformedExplicitReceiptSelectionLeavesDraftMessagesAndRulesUnchanged() = runBlocking {
        val database = Files.createTempFile("native-receipt-invalid-selection-", ".sqlite")
        val gateway = FakeAgentGateway(
            ArrayDeque(
                listOf(
                    FakeAgentGateway.READY_DRAFT_JSON,
                    """{"intent":"remember_rule"}""",
                ),
            ),
        )
        val dependencies = fakeAgentDependencies(database.toString(), gateway)
        val source = ReceiptAssociationFixture()
        val session = dependencies.agent.createSession(
            "Синтетическая ошибка выбора",
            dependencies.runtimeConfig.defaultAgentConfig(),
        )
        try {
            val imported = dependencies.agent.sendMessage(
                sessionId = session.session.id,
                expectedRevision = session.session.revision,
                text = "Синтетическая выписка",
            )
            val native = NativeMcpAgent(
                agent = dependencies.agent,
                gatewayResolver = AgentGatewayResolver { gateway },
                mcpTools = source,
                runtimeConfig = dependencies.runtimeConfig,
                receiptMatchSelector = ReceiptMatchSelector { _, _, _, _, _ ->
                    ReceiptSelectionResponseView("unknown-synthetic-alias", 0.99)
                },
            )

            assertFailsWith<ReceiptMatchingSelectionException> {
                native.handle(
                    sessionId = session.session.id,
                    expectedRevision = imported.session.revision,
                    text = "Привяжи чек и запомни правило на будущее.",
                )
            }

            val afterFailure = dependencies.agent.getSession(session.session.id)
            assertEquals(imported.session.revision, afterFailure.session.revision)
            assertEquals(imported.messages, afterFailure.messages)
            assertEquals(imported.draft, afterFailure.draft)
            assertTrue(dependencies.agent.getPreferences().confirmedDecisions.isEmpty())
        } finally {
            dependencies.scheduler?.close()
            database.deleteIfExists()
        }
    }

    @Test
    fun receiptSourceFailureIsRecordedSeparatelyFromUnmatchedWithoutLeakingErrorText() = runBlocking {
        val database = Files.createTempFile("native-receipt-source-error-", ".sqlite")
        val gateway = FakeAgentGateway(
            ArrayDeque(
                listOf(
                    FakeAgentGateway.READY_DRAFT_JSON,
                    """{"intent":"associate"}""",
                ),
            ),
        )
        val dependencies = fakeAgentDependencies(database.toString(), gateway)
        val rawFailure = "synthetic-private-fiscal-response"
        val source = ReceiptAssociationFixture(requestFailure = rawFailure)
        val session = dependencies.agent.createSession(
            "Синтетическая ошибка источника чеков",
            dependencies.runtimeConfig.defaultAgentConfig(),
        )
        try {
            val imported = dependencies.agent.sendMessage(
                sessionId = session.session.id,
                expectedRevision = session.session.revision,
                text = "Синтетическая выписка",
            )
            val original = imported.draft!!.transactions.first().transaction
            val native = NativeMcpAgent(
                agent = dependencies.agent,
                gatewayResolver = AgentGatewayResolver { gateway },
                mcpTools = source,
                runtimeConfig = dependencies.runtimeConfig,
                receiptMatchSelector = ReceiptMatchSelector { _, _, _, _, _ ->
                    error("Selector must not run after source failure")
                },
            )

            val handled = assertIs<NativeMcpHandlingResult.Handled>(
                native.handle(
                    sessionId = session.session.id,
                    expectedRevision = imported.session.revision,
                    text = "Соотнеси платеж «пополнение проездного» с подходящей электронной квитанцией.",
                ),
            )
            val state = handled.response.state
            val rows = state.draft!!.transactions

            assertEquals(imported.session.revision + 1, state.session.revision)
            assertTrue(rows.all { it.transaction.receiptAssociation?.status == ReceiptAssociationStatus.SOURCE_ERROR })
            assertEquals(ReceiptStatus.HAS_ERRORS, state.receiptState.status)
            assertFailsWith<IllegalArgumentException> {
                dependencies.agent.buildImportBatch(session.session.id)
            }
            assertEquals(original.merchant, rows.first().transaction.merchant)
            assertEquals(0, source.detailCalls)
            assertTrue(handled.response.assistantText.contains("Источник чеков недоступен"))
            assertFalse(handled.response.assistantText.contains(rawFailure))
            assertFalse(state.messages.any { it.content.contains(rawFailure) || it.displayText.contains(rawFailure) })
        } finally {
            dependencies.scheduler?.close()
            database.deleteIfExists()
        }
    }

    @Test
    fun pendingPreviewCorrectionRerunsNativeFetchAndKeepsPreviewImmutable() = runBlocking {
        val database = Files.createTempFile("native-mcp-correction-", ".sqlite")
        try {
            val gateway = CorrectionNativeLoopGateway()
            val dependencies = fakeAgentDependencies(database.toString(), gateway)
            val runtime = dependencies.runtimeConfig.copy(
                mcpToolLoop = McpToolLoopConfig(
                    enabled = true,
                    maxIterations = 4,
                    maxToolCalls = 4,
                ),
            )
            val provider = FakeNativeMcpProvider(transactionDate = "2026-09-10")
            var previewNumber = 0
            val native = NativeMcpAgent(
                agent = dependencies.agent,
                gatewayResolver = AgentGatewayResolver { gateway },
                mcpTools = provider,
                runtimeConfig = runtime,
                idGenerator = { "preview-${++previewNumber}" },
                dateContextProvider = { NativeMcpDateContext("2026-09-30", "UTC") },
            )
            val session = dependencies.agent.createSession(
                title = "MCP correction",
                config = runtime.defaultAgentConfig(),
                contextManagement = runtime.sessionContextManagement(),
            )

            val first = assertIs<NativeMcpHandlingResult.Handled>(
                native.handle(
                    sessionId = session.session.id,
                    expectedRevision = session.session.revision,
                    text = "Добавь операции с совместного счета за неделю с 8 сентября",
                ),
            )
            val firstPreview = assertNotNull(first.response.mcpPreview)
            assertEquals("2026-09-08", provider.transactionArguments["from"]?.jsonPrimitive?.content)
            assertEquals("2026-09-14", provider.transactionArguments["to"]?.jsonPrimitive?.content)

            val corrected = assertIs<NativeMcpHandlingResult.Handled>(
                native.handle(
                    sessionId = session.session.id,
                    expectedRevision = first.response.state.session.revision,
                    text = "Ой, я ошибся, надо с 14 сентября",
                ),
            )
            val correctedPreview = assertNotNull(corrected.response.mcpPreview)
            assertEquals("2026-09-14", provider.transactionArguments["from"]?.jsonPrimitive?.content)
            assertEquals("2026-09-20", provider.transactionArguments["to"]?.jsonPrimitive?.content)
            assertTrue(
                gateway.requests[4].messages.any {
                    it.content.contains("Предыдущие аргументы get-account-transactions")
                },
            )
            assertTrue(
                gateway.requests[4].messages.any {
                    it.content.contains("2026-09-08") && it.content.contains("2026-09-14")
                },
            )
            assertTrue(correctedPreview.id != firstPreview.id)

            val additional = assertIs<NativeMcpHandlingResult.Handled>(
                native.handle(
                    sessionId = session.session.id,
                    expectedRevision = corrected.response.state.session.revision,
                    text = "Добавь ещё один счёт за тот же период",
                ),
            )
            val additionalPreview = assertNotNull(additional.response.mcpPreview)
            assertEquals("2026-09-14", provider.transactionArguments["from"]?.jsonPrimitive?.content)
            assertEquals("2026-09-20", provider.transactionArguments["to"]?.jsonPrimitive?.content)
            assertTrue(additionalPreview.id != correctedPreview.id)

            val requestCountBeforePreviewEdit = gateway.requests.size
            val previewEdit = native.handle(
                sessionId = session.session.id,
                expectedRevision = additional.response.state.session.revision,
                text = "Измени описание в превью",
            )
            assertIs<NativeMcpHandlingResult.NotHandled>(previewEdit)
            assertEquals(requestCountBeforePreviewEdit, gateway.requests.size)
        } finally {
            database.deleteIfExists()
        }
    }

    private fun io.github.stolex1y.transactionimport.core.ImportSessionState.sessionRevision(): Long =
        session.revision
}
private val NO_RECEIPT_INTENT_CLASSIFIER =
    ReceiptInstructionClassifier { _, _ -> ReceiptInstructionIntent.NONE }


private class ReceiptAssociationFixture(
    private val requestFailure: String? = null,
) : McpToolProvider {
    var detailCalls = 0
    var searchCalls = 0

    override suspend fun allowedTools(): List<McpCallableTool> = emptyList()

    override suspend fun callConfiguredTool(
        serverId: String,
        tool: String,
        arguments: JsonObject,
    ): TbankToolCallResponse {
        assertEquals("receipts", serverId)
        requestFailure?.let { throw IllegalStateException(it) }
        return when (tool) {
            "search-receipts" -> {
                searchCalls += 1
                TbankToolCallResponse(
                    tool = tool,
                    text = """{"receipts":[{"receipt_key":"synthetic-private-receipt-key","merchant":"Синтетический продавец","received_at":"2026-02-08T12:20:00Z","amount_minor":125050,"currency":"RUB"}],"has_more":false}""",
                )
            }
            "get-receipt" -> {
                detailCalls += 1
                assertEquals("synthetic-private-receipt-key", arguments["receipt_key"]?.jsonPrimitive?.content)
                TbankToolCallResponse(
                    tool = tool,
                    text = """{"receipt_key":"synthetic-private-receipt-key","date_time":"2026-02-08T12:20:00Z","total_minor":125050,"currency":"RUB","merchant":"Синтетический продавец","settlement_place":"Синтетическая торговая точка","fiscal_document_number":"synthetic-fiscal-id","qr_code":"synthetic-qr-payload","cashier":"Синтетический кассир","items":[{"name":"Синтетическая позиция","quantity":1,"price_minor":125050,"sum_minor":125050}]}""",
                )
            }
            else -> TbankToolCallResponse(tool = tool, isError = true, text = "unexpected synthetic receipt tool")
        }
    }
}

private class NativeLoopGateway(
    private val from: String = "2026-09-01",
    private val to: String = "2026-09-30",
    private val reviewTransactionId: String = "1",
) : ChatCompletionGateway {
    val requests = mutableListOf<ChatCompletionRequest>()
    private var index = 0

    override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
        requests += request
        val message = when (index++) {
            0 -> ResponseMessage(
                content = null,
                toolCalls = listOf(
                    ChatToolCall(
                        id = "call-list",
                        function = ChatFunctionCall(
                            name = "mcp_tbank-transactions_list-accounts",
                            arguments = "{}",
                        ),
                    ),
                ),
            )
            1 -> ResponseMessage(
                content = null,
                toolCalls = listOf(
                    ChatToolCall(
                        id = "call-transactions",
                        function = ChatFunctionCall(
                            name = "mcp_tbank-transactions_get-account-transactions",
                            arguments = """
                                {"account_alias":"Основной счёт","from":"$from","to":"$to","limit":50}
                            """.trimIndent(),
                        ),
                    ),
                ),
            )
            2 -> ResponseMessage(content = "Готово: raw-account-001, raw-transaction-001")
            3 -> ResponseMessage(
                content = """{"items":[{"index":0,"merchant":"Coffeebon 37","description":"","category_id":"food.cafe"}],"merchant_canonical_candidates":[{"canonical_name":"КофеБон","aliases":["Coffeebon"],"suffix_policy":"numeric_terminal","reason":"Явное правило пользователя."}]}""",
            )
            4 -> ResponseMessage(
                content = """{"operations":[{"transaction_id":"$reviewTransactionId","included":false}],"message":"Перевод не включён."}""",
            )
            else -> ResponseMessage(
                content = """{"items":[{"index":0,"merchant":"Coffeebon 37","description":"","category_id":"food.cafe"}],"merchant_canonical_candidates":[{"canonical_name":"КофеБон","aliases":["Coffeebon"],"suffix_policy":"numeric_terminal","reason":"Явное правило пользователя."}]}""",
            )
        }
        return ChatCompletionResponse(
            choices = listOf(ChatChoice(message = message, finishReason = "stop")),
            usage = Usage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
        )
    }
}
private class CorrectionNativeLoopGateway : ChatCompletionGateway {
    val requests = mutableListOf<ChatCompletionRequest>()
    private var index = 0

    override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
        requests += request
        val current = index++
        val message = when (current) {
            0, 4, 8 -> ResponseMessage(
                content = null,
                toolCalls = listOf(
                    ChatToolCall(
                        id = "call-list-$current",
                        function = ChatFunctionCall(
                            name = "mcp_tbank-transactions_list-accounts",
                            arguments = "{}",
                        ),
                    ),
                ),
            )

            1 -> ResponseMessage(
                content = null,
                toolCalls = listOf(
                    ChatToolCall(
                        id = "call-transactions-$current",
                        function = ChatFunctionCall(
                            name = "mcp_tbank-transactions_get-account-transactions",
                            arguments = """{"account_alias":"Основной счёт","from":"2026-09-08","to":"2026-09-14","limit":50}""",
                        ),
                    ),
                ),
            )

            5 -> ResponseMessage(
                content = null,
                toolCalls = listOf(
                    ChatToolCall(
                        id = "call-transactions-$current",
                        function = ChatFunctionCall(
                            name = "mcp_tbank-transactions_get-account-transactions",
                            arguments = """{"account_alias":"Основной счёт","from":"2026-09-14","to":"2026-09-20","limit":50}""",
                        ),
                    ),
                ),
            )
            9 -> ResponseMessage(
                content = null,
                toolCalls = listOf(
                    ChatToolCall(
                        id = "call-transactions-$current",
                        function = ChatFunctionCall(
                            name = "mcp_tbank-transactions_get-account-transactions",
                            arguments = """{"account_alias":"Основной счёт","from":"2026-09-14","to":"2026-09-20","limit":50}""",
                        ),
                    ),
                ),
            )

            2, 6, 10 -> ResponseMessage(content = "Операции получены.")
            3, 7, 11 -> ResponseMessage(
                content = """{"items":[{"index":0,"merchant":"Т-Банк","description":"","category_id":"food.cafe"}]}""",
            )
            else -> ResponseMessage(content = "Операции получены.")
        }
        return ChatCompletionResponse(
            choices = listOf(ChatChoice(message = message, finishReason = "stop")),
            usage = Usage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
        )
    }
}

private class NoToolGateway : ChatCompletionGateway {
    val requests = mutableListOf<ChatCompletionRequest>()

    override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
        requests += request
        return ChatCompletionResponse(
            choices = listOf(
                ChatChoice(
                    message = ResponseMessage(content = "Передаю запрос обычному агенту."),
                    finishReason = "stop",
                ),
            ),
        )
    }
}

private class FakeNativeMcpProvider(
    private val transactionDate: String = "2026-09-02",
) : McpToolProvider {
    var transactionArguments: JsonObject = JsonObject(emptyMap())

    override suspend fun allowedTools(): List<McpCallableTool> = listOf(
        McpCallableTool(
            serverId = "tbank-transactions",
            serverDisplayName = "Т-Банк",
            name = "list-accounts",
            description = "Счета",
            inputSchema = buildJsonObject { put("type", "object") },
        ),
        McpCallableTool(
            serverId = "tbank-transactions",
            serverDisplayName = "Т-Банк",
            name = "get-account-transactions",
            description = "Операции",
            inputSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put("account_ref", buildJsonObject { put("type", "string") })
                        put("from", buildJsonObject { put("type", "string") })
                        put("to", buildJsonObject { put("type", "string") })
                    },
                )
                put("required", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("account_ref"))
                    add(kotlinx.serialization.json.JsonPrimitive("from"))
                    add(kotlinx.serialization.json.JsonPrimitive("to"))
                })
            },
        ),
    )

    override suspend fun callConfiguredTool(
        serverId: String,
        tool: String,
        arguments: JsonObject,
    ): TbankToolCallResponse = when (tool) {
        "list-accounts" -> TbankToolCallResponse(
            tool = tool,
            text = """{"data":{"accounts":[{"id":"raw-account-001","account_name":"Основной счёт","currency":"643","balance_minor":100000}]}}""",
        )
        "get-account-transactions" -> {
            transactionArguments = arguments
            TbankToolCallResponse(
                tool = tool,
                text = """
                    {"account_ref":"raw-account-001","account":{"name":"Основной счёт"},"transactions":[{"id":"raw-transaction-001","account_id":"raw-account-001","date":"$transactionDate","amount_minor":-12500,"currency":"643","merchant":"B121","description":"Булочная Ф. Вол"}],"next_cursor":null}
                """.trimIndent(),
            )
        }
        else -> error("Unexpected tool: $tool")
    }
}
