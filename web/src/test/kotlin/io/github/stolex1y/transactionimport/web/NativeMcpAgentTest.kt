package io.github.stolex1y.transactionimport.web

import io.github.stolex1y.transactionimport.core.AgentGatewayResolver
import io.github.stolex1y.transactionimport.core.AgentResponseException
import io.github.stolex1y.transactionimport.core.AgentRuntimeConfig
import io.github.stolex1y.transactionimport.core.ChatChoice
import io.github.stolex1y.transactionimport.core.ChatCompletionGateway
import io.github.stolex1y.transactionimport.core.ChatCompletionRequest
import io.github.stolex1y.transactionimport.core.ChatCompletionResponse
import io.github.stolex1y.transactionimport.core.ChatFunctionCall
import io.github.stolex1y.transactionimport.core.ChatFunctionDefinition
import io.github.stolex1y.transactionimport.core.ChatToolCall
import io.github.stolex1y.transactionimport.core.ChatToolDefinition
import io.github.stolex1y.transactionimport.core.ExternalTransactionCandidate
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
import java.sql.DriverManager
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
            assertTrue(firstMessages[0].content.contains("единый последовательный план"))
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
                dateContextProvider = { NativeMcpDateContext("2026-09-30", "UTC") },
            )
            val routed = correctionNative.handle(
                sessionId = session.session.id,
                expectedRevision = confirmed.sessionRevision(),
                text = "Примени правило к текущему черновику.",
            )
            assertIs<NativeMcpHandlingResult.NotHandled>(routed)
            assertEquals(1, correctionGateway.requests.size)
            val unmarkedRouted = correctionNative.handle(
                sessionId = session.session.id,
                expectedRevision = confirmed.sessionRevision(),
                text = "Coffeebon в КофеБон",
            )
            assertIs<NativeMcpHandlingResult.NotHandled>(unmarkedRouted)
            assertEquals(2, correctionGateway.requests.size)
            val appendRouteGateway = NoToolGateway()
            val appendRouteNative = NativeMcpAgent(
                agent = dependencies.agent,
                gatewayResolver = AgentGatewayResolver { appendRouteGateway },
                mcpTools = provider,
                runtimeConfig = runtime,
                dateContextProvider = { NativeMcpDateContext("2026-09-30", "UTC") },
            )
            val ambiguousAppend = appendRouteNative.handle(
                sessionId = session.session.id,
                expectedRevision = confirmed.sessionRevision(),
                text = "Добавь транзакции с совместного счета",
            )
            assertIs<NativeMcpHandlingResult.NotHandled>(ambiguousAppend)
            assertEquals(1, appendRouteGateway.requests.size)
            val missingPeriodAppend = appendRouteNative.handle(
                sessionId = session.session.id,
                expectedRevision = confirmed.sessionRevision(),
                text = "Добавь вторую выписку",
            )
            assertIs<NativeMcpHandlingResult.NotHandled>(missingPeriodAppend)
            assertEquals(2, appendRouteGateway.requests.size)
            val appendRouted = appendRouteNative.handle(
                sessionId = session.session.id,
                expectedRevision = confirmed.sessionRevision(),
                text = "Добавь транзакции с совместного счета за неделю с 8 сентября",
            )
            assertIs<NativeMcpHandlingResult.NotHandled>(appendRouted)
            assertEquals(3, appendRouteGateway.requests.size)

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
        val planner = ApplicationActionPlanGateway("app_route_to_agent")
        val dependencies = fakeAgentDependencies(database.toString(), FakeAgentGateway())
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
                gatewayResolver = AgentGatewayResolver { planner },
                mcpTools = source,
                runtimeConfig = nativeMcpEnabled(dependencies.runtimeConfig),
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
            assertEquals(currentQuestion, planner.requests.single().messages.last().content)
            assertTrue(planner.requests.single().tools.orEmpty().any {
                it.function.name == "app_route_to_agent"
            })
        } finally {
            dependencies.scheduler?.close()
            database.deleteIfExists()
        }
    }
    @Test
    fun explicitReceiptInfoReturnsAllowlistedDetailsWithoutChangingDraft() = runBlocking {
        val database = Files.createTempFile("native-receipt-info-", ".sqlite")
        val planner = ApplicationActionPlanGateway(
            action = "app_read_receipt_catalog",
            followingActions = listOf(
                "app_read_receipt_info" to
                    """{"operation_ref":"receipt-operation-1","fields":["settlement_place","total","items"]}""",
            ),
        )
        val dependencies = fakeAgentDependencies(database.toString(), FakeAgentGateway())
        val source = ReceiptAssociationFixture()
        val session = dependencies.agent.createSession(
            "Synthetic receipt info",
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
                gatewayResolver = AgentGatewayResolver { planner },
                mcpTools = source,
                runtimeConfig = nativeMcpEnabled(dependencies.runtimeConfig),
                receiptMatchSelector = ReceiptMatchSelector { _, _, choices, _, _ ->
                    ReceiptSelectionResponseView(choices.single().alias, 0.98)
                },
            )
            val result = native.handle(
                sessionId = session.session.id,
                expectedRevision = imported.session.revision,
                text = "Сколько было позиций и где рассчитались по чеку?",
            )

            assertIs<NativeMcpHandlingResult.Handled>(result)
            assertEquals(1, source.searchCalls)
            assertEquals(1, source.detailCalls)
            assertEquals(3, planner.requests.size)
            assertTrue(planner.requests.first().tools.orEmpty().any {
                it.function.name == "app_read_receipt_catalog"
            })
            assertTrue(planner.requests.first().tools.orEmpty().any {
                it.function.name == "app_read_receipt_info"
            })
            val infoSchema = planner.requests.first().tools.orEmpty()
                .single { it.function.name == "app_read_receipt_info" }
                .function.parameters.toString()
            assertTrue(infoSchema.contains("\"items\":{\"type\":\"string\""), infoSchema)
            assertFalse(infoSchema.contains("\"items\":["), infoSchema)
            assertFalse(planner.requests.first().tools.orEmpty().any {
                it.function.name.startsWith("mcp_receipts_")
            })
            val infoResult = planner.requests.last().messages.last().content
            assertTrue(infoResult.contains("Синтетическая торговая точка"))
            assertTrue(infoResult.contains("Синтетическая позиция"))
            assertTrue(infoResult.contains("settlement_place"))
            assertFalse(infoResult.contains("synthetic-private-receipt-key"))
            assertFalse(infoResult.contains("synthetic-fiscal-id"))
            assertFalse(infoResult.contains("synthetic-qr-payload"))
            assertFalse(infoResult.contains("Синтетический кассир"))
            val after = dependencies.agent.getSession(session.session.id)
            assertEquals(imported.session.revision + 1, after.session.revision)
            assertEquals(imported.draft?.transactions, after.draft?.transactions)
            assertNull(after.draft!!.transactions.first().transaction.receiptAssociation)
            assertTrue(after.draft!!.transactions.first().transaction.items.isEmpty())
        } finally {
            dependencies.scheduler?.close()
            database.deleteIfExists()
        }
    }

    @Test
    fun receiptInfoUsesPersistedSnapshotWithoutRefetchingSource() = runBlocking {
        val database = Files.createTempFile("native-receipt-info-snapshot-", ".sqlite")
        val planner = ApplicationActionPlanGateway(
            action = "app_read_receipt_catalog",
            followingActions = listOf(
                "app_read_receipt_info" to
                    """{"operation_ref":"receipt-operation-1","fields":["settlement_place","items"]}""",
            ),
        )
        val dependencies = fakeAgentDependencies(database.toString(), FakeAgentGateway())
        val source = ReceiptAssociationFixture()
        val session = dependencies.agent.createSession(
            "Synthetic saved receipt info",
            dependencies.runtimeConfig.defaultAgentConfig(),
        )
        try {
            val imported = dependencies.agent.sendMessage(
                sessionId = session.session.id,
                expectedRevision = session.session.revision,
                text = "Синтетическая выписка",
            )
            val row = imported.draft!!.transactions.first()
            val savedReceipt = dependencies.agent.recordReceiptAssociation(
                sessionId = session.session.id,
                expectedRevision = imported.session.revision,
                userText = "Свяжи synthetic receipt.",
                assistantText = "Связь принята.",
                updates = mapOf(
                    row.id to io.github.stolex1y.transactionimport.core.ReceiptMatchUpdate(
                        status = ReceiptAssociationStatus.MATCHED,
                        items = listOf(
                            io.github.stolex1y.transactionimport.core.TransactionItem(
                                name = "Сохранённая позиция",
                                quantity = 1.0,
                                priceMinor = row.transaction.amountMinor,
                                sumMinor = row.transaction.amountMinor,
                                currency = row.transaction.currency,
                            ),
                        ),
                        summary = io.github.stolex1y.transactionimport.core.ReceiptAssociationSummary(
                            date = "2026-02-08",
                            merchant = "Синтетический продавец",
                            amountMinor = row.transaction.amountMinor,
                            currency = row.transaction.currency,
                            dateTime = "2026-02-08T12:20:00Z",
                            settlementPlace = "Сохранённое место",
                            privacyVersion =
                                io.github.stolex1y.transactionimport.core.ReceiptAssociationSummary
                                    .CURRENT_PRIVACY_VERSION,
                        ),
                    ),
                ),
                persistRule = false,
            )
            val native = NativeMcpAgent(
                agent = dependencies.agent,
                gatewayResolver = AgentGatewayResolver { planner },
                mcpTools = source,
                runtimeConfig = nativeMcpEnabled(dependencies.runtimeConfig),
            )
            val result = native.handle(
                sessionId = session.session.id,
                expectedRevision = savedReceipt.session.revision,
                text = "Что куплено и где рассчитались?",
            )

            assertIs<NativeMcpHandlingResult.Handled>(result)
            assertEquals(0, source.searchCalls)
            assertEquals(0, source.detailCalls)
            val infoResult = planner.requests.last().messages.last().content
            assertTrue(infoResult.contains("Сохранённая позиция"))
            assertTrue(infoResult.contains("Сохранённое место"))
            assertTrue(infoResult.contains("available"))
            val after = dependencies.agent.getSession(session.session.id)
            assertEquals(savedReceipt.session.revision + 1, after.session.revision)
            assertEquals(savedReceipt.draft?.transactions, after.draft?.transactions)
        } finally {
            dependencies.scheduler?.close()
            database.deleteIfExists()
        }
    }

    @Test
    fun readOnlyReceiptPlanCannotContinueIntoAssociation() = runBlocking {
        val database = Files.createTempFile("native-receipt-read-only-plan-", ".sqlite")
        val planner = ApplicationActionPlanGateway(
            action = "app_read_receipt_catalog",
            followingActions = listOf(
                "app_read_receipt_info" to
                    """{"operation_ref":"receipt-operation-1","fields":["items"]}""",
                "app_associate_receipts" to """{"respect_explicit_instruction":false}""",
            ),
        )
        val dependencies = fakeAgentDependencies(database.toString(), FakeAgentGateway())
        val source = ReceiptAssociationFixture()
        val session = dependencies.agent.createSession(
            "Synthetic read-only plan",
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
                gatewayResolver = AgentGatewayResolver { planner },
                mcpTools = source,
                runtimeConfig = nativeMcpEnabled(dependencies.runtimeConfig),
                receiptMatchSelector = ReceiptMatchSelector { _, _, choices, _, _ ->
                    ReceiptSelectionResponseView(choices.single().alias, 0.98)
                },
            )
            val failure = runCatching {
                native.handle(
                    sessionId = session.session.id,
                    expectedRevision = imported.session.revision,
                    text = "Покажи позиции чека.",
                )
            }.exceptionOrNull()

            assertIs<AgentResponseException>(failure)
            val after = dependencies.agent.getSession(session.session.id)
            assertEquals(imported.draft?.transactions, after.draft?.transactions)
            assertTrue(after.draft!!.transactions.all { it.transaction.receiptAssociation == null })
        } finally {
            dependencies.scheduler?.close()
            database.deleteIfExists()
        }
    }

    @Test
    fun receiptInfoAmbiguityReturnsSafeOptionsWithoutAssociation() = runBlocking {
        val database = Files.createTempFile("native-receipt-info-ambiguous-", ".sqlite")
        val planner = ApplicationActionPlanGateway(
            action = "app_read_receipt_catalog",
            followingActions = listOf(
                "app_read_receipt_info" to
                    """{"operation_ref":"receipt-operation-1","fields":["items"]}""",
            ),
        )
        val dependencies = fakeAgentDependencies(database.toString(), FakeAgentGateway())
        val source = ReceiptAssociationFixture(includeSecondReceipt = true)
        val session = dependencies.agent.createSession(
            "Synthetic ambiguous receipt info",
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
                gatewayResolver = AgentGatewayResolver { planner },
                mcpTools = source,
                runtimeConfig = nativeMcpEnabled(dependencies.runtimeConfig),
                receiptMatchSelector = ReceiptMatchSelector { _, _, _, _, _ ->
                    ReceiptSelectionResponseView(receiptAlias = null, confidence = 0.0)
                },
            )
            val result = native.handle(
                sessionId = session.session.id,
                expectedRevision = imported.session.revision,
                text = "Покажи позиции чека по этой операции.",
            )

            assertIs<NativeMcpHandlingResult.Handled>(result)
            assertEquals(1, source.searchCalls)
            assertEquals(2, source.detailCalls)
            val infoResult = planner.requests.last().messages.last().content
            assertTrue(infoResult.contains("ambiguous"), infoResult)
            assertTrue(infoResult.contains("multiple_candidates"))
            assertTrue(infoResult.contains("Синтетическая торговая точка"))
            assertFalse(infoResult.contains("synthetic-private-receipt-key"))
            assertFalse(infoResult.contains("synthetic-fiscal-id"))
            val after = dependencies.agent.getSession(session.session.id)
            assertEquals(
                imported.draft?.transactions,
                after.draft?.transactions,
            )
            assertTrue(after.draft!!.transactions.all { it.transaction.receiptAssociation == null })
        } finally {
            dependencies.scheduler?.close()
            database.deleteIfExists()
        }
    }

    @Test
    fun receiptInfoSourceFailureIsDistinctAndLeavesDraftUnchanged() = runBlocking {
        val database = Files.createTempFile("native-receipt-info-error-", ".sqlite")
        val planner = ApplicationActionPlanGateway(
            action = "app_read_receipt_catalog",
            followingActions = listOf(
                "app_read_receipt_info" to
                    """{"operation_ref":"receipt-operation-1","fields":["items"]}""",
            ),
        )
        val dependencies = fakeAgentDependencies(database.toString(), FakeAgentGateway())
        val source = ReceiptAssociationFixture(requestFailure = "synthetic-private-upstream-error")
        val session = dependencies.agent.createSession(
            "Synthetic receipt info failure",
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
                gatewayResolver = AgentGatewayResolver { planner },
                mcpTools = source,
                runtimeConfig = nativeMcpEnabled(dependencies.runtimeConfig),
            )
            val result = native.handle(
                sessionId = session.session.id,
                expectedRevision = imported.session.revision,
                text = "Покажи позиции чека.",
            )

            assertIs<NativeMcpHandlingResult.Handled>(result)
            val infoResult = planner.requests.last().messages.last().content
            assertTrue(infoResult.contains("source_error"))
            assertFalse(infoResult.contains("не найден"))
            assertFalse(infoResult.contains("synthetic-private-upstream-error"))
            val after = dependencies.agent.getSession(session.session.id)
            assertEquals(imported.session.revision + 1, after.session.revision)
            assertEquals(imported.draft?.transactions, after.draft?.transactions)
            assertEquals(0, source.detailCalls)
        } finally {
            dependencies.scheduler?.close()
            database.deleteIfExists()
        }
    }


    @Test
    fun receiptAssociationRequiresBooleanExplicitScopeArgument() = runBlocking {
        for (arguments in listOf(
            "{}",
            """{"respect_explicit_instruction":"false"}""",
        )) {
            val database = Files.createTempFile("native-receipt-scope-schema-", ".sqlite")
            val planner = ApplicationActionPlanGateway("app_associate_receipts", arguments)
            val dependencies = fakeAgentDependencies(database.toString(), FakeAgentGateway())
            val source = ReceiptAssociationFixture()
            val session = dependencies.agent.createSession(
                "Synthetic receipt scope validation",
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
                    gatewayResolver = AgentGatewayResolver { planner },
                    mcpTools = source,
                    runtimeConfig = nativeMcpEnabled(dependencies.runtimeConfig),
                )
                val failure = runCatching {
                    native.handle(
                        sessionId = session.session.id,
                        expectedRevision = imported.session.revision,
                        text = "Сопоставь чек только с операцией из моего условия.",
                    )
                }.exceptionOrNull()

                assertIs<AgentResponseException>(failure)
                assertEquals(0, source.searchCalls)
                assertEquals(imported.draft?.transactions, dependencies.agent.getSession(session.session.id).draft?.transactions)
            } finally {
                dependencies.scheduler?.close()
                database.deleteIfExists()
            }
        }
    }

    @Test
    fun plannerSelectedReceiptActionRunsForCompositeRequestWithoutAssociationVerb() = runBlocking {
        val database = Files.createTempFile("native-receipt-planner-semantic-", ".sqlite")
        val planner = ApplicationActionPlanGateway("app_associate_receipts")
        val dependencies = fakeAgentDependencies(database.toString(), FakeAgentGateway())
        val source = ReceiptAssociationFixture()
        val session = dependencies.agent.createSession(
            "Planner receipt plan",
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
                gatewayResolver = AgentGatewayResolver { planner },
                mcpTools = source,
                runtimeConfig = nativeMcpEnabled(dependencies.runtimeConfig),
                receiptMatchSelector = ReceiptMatchSelector { _, _, choices, _, _ ->
                    ReceiptSelectionResponseView(choices.single().alias, 0.96)
                },
            )

            val handled = assertIs<NativeMcpHandlingResult.Handled>(
                native.handle(
                    sessionId = session.session.id,
                    expectedRevision = imported.session.revision,
                    text = "Импортируй операции за сентябрь и чеки к ним",
                ),
            )

            assertEquals(
                ReceiptAssociationStatus.MATCHED,
                handled.response.state.draft!!.transactions.first()
                    .transaction.receiptAssociation?.status,
            )
            assertEquals(1, source.searchCalls)
            assertEquals(1, source.detailCalls)
            assertTrue(planner.requests.first().tools.orEmpty().any {
                it.function.name == "app_associate_receipts"
            })
        } finally {
            dependencies.scheduler?.close()
            database.deleteIfExists()
        }
    }


    @Test
    fun naturalLanguageAssociationUpdatesDraftAndPersistsScopedRule() = runBlocking {
        val database = Files.createTempFile("native-receipt-association-", ".sqlite")
        val planner = ApplicationActionPlanGateway(
            "app_associate_receipts",
            """{"remember_rule":true,"respect_explicit_instruction":false}""",
        )
        val dependencies = fakeAgentDependencies(database.toString(), FakeAgentGateway())
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
                gatewayResolver = AgentGatewayResolver { planner },
                mcpTools = source,
                runtimeConfig = nativeMcpEnabled(dependencies.runtimeConfig),
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
        } finally {
            dependencies.scheduler?.close()
            database.deleteIfExists()
        }
    }

    @Test
    fun receiptApprovalRejectAndDetachRevalidateRevisionAndPreserveBankFields() = runBlocking {
        val database = Files.createTempFile("native-receipt-actions-", ".sqlite")
        val dependencies = fakeAgentDependencies(database.toString())
        val source = ReceiptAssociationFixture(
            amountMinor = 9_900,
            includeSecondReceipt = true,
            secondReceiptMerchant = "Синтетический продавец 2",
        )
        val session = dependencies.agent.createSession(
            "Synthetic receipt actions",
            dependencies.runtimeConfig.defaultAgentConfig(),
        )
        try {
            val imported = dependencies.agent.sendMessage(
                sessionId = session.session.id,
                expectedRevision = session.session.revision,
                text = "Synthetic expense",
            )
            val original = imported.draft!!.transactions.single { it.id == "2" }
            assertNull(original.transaction.categoryId)
            val native = NativeMcpAgent(
                agent = dependencies.agent,
                gatewayResolver = AgentGatewayResolver { FakeAgentGateway() },
                mcpTools = source,
                runtimeConfig = nativeMcpEnabled(dependencies.runtimeConfig),
            )
            val review = native.refreshReceiptAssociation(
                session.session.id,
                imported.session.revision,
                original.id,
            )
            val refreshedOriginal = review.draft!!.transactions.single { it.id == original.id }
            val candidates = refreshedOriginal.transaction.receiptAssociation?.review?.candidates
                ?: error("Synthetic ambiguous receipt candidates are missing.")
            val summary = candidates.single { it.merchant == "Синтетический продавец 2" }

            val approved = native.approveReceiptCandidate(
                session.session.id,
                review.session.revision,
                original.id,
                candidateIndex = candidates.indexOf(summary),
            )
            val approvedRow = approved.draft!!.transactions.single { it.id == original.id }
            assertEquals(ReceiptAssociationStatus.MATCHED, approvedRow.transaction.receiptAssociation?.status)
            assertEquals(summary, approvedRow.transaction.receiptAssociation?.summary)
            assertEquals(listOf("Синтетическая позиция"), approvedRow.transaction.items.map { it.name })
            assertEquals(original.transaction, approvedRow.transaction.copy(
                items = original.transaction.items,
                receiptAssociation = original.transaction.receiptAssociation,
            ))

            val detached = native.unlinkReceiptAssociation(
                session.session.id,
                approved.session.revision,
                original.id,
            )
            val detachedRow = detached.draft!!.transactions.single { it.id == original.id }
            assertEquals(ReceiptAssociationStatus.UNMATCHED, detachedRow.transaction.receiptAssociation?.status)
            assertNull(detachedRow.transaction.receiptAssociation?.summary)
            assertTrue(detachedRow.transaction.items.isEmpty())
            assertEquals(original.transaction.direction, detachedRow.transaction.direction)
            assertEquals(original.transaction.amountMinor, detachedRow.transaction.amountMinor)
            assertEquals(original.transaction.currency, detachedRow.transaction.currency)
            assertEquals(original.transaction.occurredAt, detachedRow.transaction.occurredAt)
            assertEquals(original.transaction.categoryId, detachedRow.transaction.categoryId)
            assertEquals(original.transaction.merchant, detachedRow.transaction.merchant)
            assertEquals(original.description, detachedRow.description)

            val reviewAgain = native.refreshReceiptAssociation(
                session.session.id,
                detached.session.revision,
                original.id,
            )
            assertEquals(
                ReceiptAssociationStatus.AMBIGUOUS,
                reviewAgain.draft!!.transactions.single { it.id == original.id }
                    .transaction.receiptAssociation?.status,
            )
            val rejected = native.rejectReceiptCandidate(
                session.session.id,
                reviewAgain.session.revision,
                original.id,
                candidateIndex = candidates.indexOf(summary),
            )
            val rejectedRow = rejected.draft!!.transactions.single { it.id == original.id }
            assertEquals(ReceiptAssociationStatus.AMBIGUOUS, rejectedRow.transaction.receiptAssociation?.status)
            assertTrue(rejectedRow.transaction.items.isEmpty())
            val rematched = native.refreshReceiptAssociation(
                session.session.id,
                rejected.session.revision,
                original.id,
            )
            assertEquals(
                ReceiptAssociationStatus.AMBIGUOUS,
                rematched.draft!!.transactions.single { it.id == original.id }
                    .transaction.receiptAssociation?.status,
            )
            assertTrue(
                rematched.draft!!.transactions.single { it.id == original.id }
                    .transaction.receiptAssociation?.review?.candidates?.contains(summary) == true,
            )
            assertFailsWith<io.github.stolex1y.transactionimport.core.RevisionConflictException> {
                native.unlinkReceiptAssociation(
                    session.session.id,
                    approved.session.revision,
                    original.id,
                )
            }
            assertFalse(rematched.toString().contains("synthetic-private-receipt-key"))
            assertFalse(rematched.toString().contains("synthetic-fiscal-id"))
            assertFalse(rematched.toString().contains("synthetic-qr-payload"))
        } finally {
            dependencies.scheduler?.close()
            database.deleteIfExists()
        }
    }

    @Test
    fun plannerSelectedRememberRulePersistsWithoutSaveVerb() = runBlocking {
        val database = Files.createTempFile("native-receipt-always-rule-", ".sqlite")
        val planner = ApplicationActionPlanGateway(
            "app_associate_receipts",
            """{"remember_rule":true,"respect_explicit_instruction":false}""",
        )
        val dependencies = fakeAgentDependencies(database.toString(), FakeAgentGateway())
        val source = ReceiptAssociationFixture()
        val session = dependencies.agent.createSession(
            "Synthetic future receipt rule",
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
                gatewayResolver = AgentGatewayResolver { planner },
                mcpTools = source,
                runtimeConfig = nativeMcpEnabled(dependencies.runtimeConfig),
                receiptMatchSelector = ReceiptMatchSelector { _, _, choices, _, _ ->
                    ReceiptSelectionResponseView(choices.single().alias, 0.96)
                },
            )

            val result = assertIs<NativeMcpHandlingResult.Handled>(
                native.handle(
                    sessionId = session.session.id,
                    expectedRevision = imported.session.revision,
                    text = "Всегда сопоставляй чеки этого продавца с такими операциями.",
                ),
            )

            assertEquals(
                ReceiptAssociationStatus.MATCHED,
                result.response.state.draft!!.transactions.first().transaction.receiptAssociation?.status,
            )

            val savedRule = dependencies.agent.getPreferences().confirmedDecisions.single()
            assertEquals(ConfirmedDecisionScope.RECEIPT_MATCHING, savedRule.scope)
            assertEquals(1, source.searchCalls)
        } finally {
            dependencies.scheduler?.close()
            database.deleteIfExists()
        }
    }

    @Test
    fun plannerSelectedActionWithoutRuleBitDoesNotPersistRule() = runBlocking {
        val database = Files.createTempFile("native-receipt-rule-plan-false-", ".sqlite")
        val planner = ApplicationActionPlanGateway(
            "app_associate_receipts",
            """{"remember_rule":false,"respect_explicit_instruction":false}""",
        )
        val dependencies = fakeAgentDependencies(database.toString(), FakeAgentGateway())
        val source = ReceiptAssociationFixture()
        val session = dependencies.agent.createSession(
            "Synthetic receipt rule plan",
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
                gatewayResolver = AgentGatewayResolver { planner },
                mcpTools = source,
                runtimeConfig = nativeMcpEnabled(dependencies.runtimeConfig),
                receiptMatchSelector = ReceiptMatchSelector { _, _, choices, _, _ ->
                    ReceiptSelectionResponseView(choices.single().alias, 0.96)
                },
            )

            val handled = assertIs<NativeMcpHandlingResult.Handled>(
                native.handle(
                    sessionId = session.session.id,
                    expectedRevision = imported.session.revision,
                    text = "Сохрани receipt rule для будущих операций и сопоставь чек.",
                ),
            )

            assertEquals(
                ReceiptAssociationStatus.MATCHED,
                handled.response.state.draft!!.transactions.first()
                    .transaction.receiptAssociation?.status,
            )
            assertTrue(dependencies.agent.getPreferences().confirmedDecisions.isEmpty())
            assertEquals(1, source.searchCalls)
        } finally {
            dependencies.scheduler?.close()
            database.deleteIfExists()
        }
    }


    @Test
    fun malformedExplicitReceiptSelectionLeavesDraftMessagesUnchanged() = runBlocking {
        val database = Files.createTempFile("native-receipt-invalid-selection-", ".sqlite")
        val planner = ApplicationActionPlanGateway("app_associate_receipts")
        val dependencies = fakeAgentDependencies(database.toString(), FakeAgentGateway())
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
                gatewayResolver = AgentGatewayResolver { planner },
                mcpTools = source,
                runtimeConfig = nativeMcpEnabled(dependencies.runtimeConfig),
                receiptMatchSelector = ReceiptMatchSelector { _, _, _, _, _ ->
                    ReceiptSelectionResponseView("unknown-synthetic-alias", 0.99)
                },
            )

            assertFailsWith<ReceiptMatchingSelectionException> {
                native.handle(
                    sessionId = session.session.id,
                    expectedRevision = imported.session.revision,
                    text = "Привяжи чек к текущей операции.",
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
        val planner = ApplicationActionPlanGateway("app_associate_receipts")
        val dependencies = fakeAgentDependencies(database.toString(), FakeAgentGateway())
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
                gatewayResolver = AgentGatewayResolver { planner },
                mcpTools = source,
                runtimeConfig = nativeMcpEnabled(dependencies.runtimeConfig),
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
    fun mixedRequestMatchesReceiptsBeforePreviewAndConfirmsSameAssociationWithAlternateSource() = runBlocking {
        val database = Files.createTempFile("native-mixed-receipt-preview-", ".sqlite")
        val classifier = FakeAgentGateway(ArrayDeque(listOf(FakeAgentGateway.PURCHASE_CLASSIFICATION_JSON)))
        val dependencies = fakeAgentDependencies(database.toString(), classifier)
        val receiptSource = ReceiptAssociationFixture(
            dateTime = "2026-09-10T12:00:00Z",
            amountMinor = 34900,
        )
        val source = SyntheticSourceWithReceipts(receiptSource)
        val planner = SyntheticMixedPlanGateway()
        var selectorCalls = 0
        val session = dependencies.agent.createSession(
            "Synthetic mixed import",
            dependencies.runtimeConfig.defaultAgentConfig(),
        )
        try {
            val runtime = nativeMcpEnabled(dependencies.runtimeConfig)
            val native = NativeMcpAgent(
                agent = dependencies.agent,
                gatewayResolver = AgentGatewayResolver { planner },
                mcpTools = source,
                runtimeConfig = runtime,
                transactionSourceAdapters = listOf(SyntheticTransactionAdapter()),
                idGenerator = { "mixed-preview" },
                receiptMatchSelector = ReceiptMatchSelector { _, _, choices, _, _ ->
                    selectorCalls += 1
                    ReceiptSelectionResponseView(
                        receiptAlias = choices.single().alias,
                        confidence = 0.99,
                    )
                },
            )

            val handled = assertIs<NativeMcpHandlingResult.Handled>(
                native.handle(
                    sessionId = session.session.id,
                    expectedRevision = session.session.revision,
                    text = "Импортируй операции за сентябрь и чеки к ним",
                ),
            )
            val preview = assertNotNull(handled.response.mcpPreview)
            assertEquals("completed", preview.receiptMatching?.status?.name?.lowercase())
            assertEquals(0, selectorCalls)
            assertEquals(1, preview.receiptMatching?.matchedCount)
            assertEquals(1, preview.transactions.size)
            assertEquals("food.groceries", preview.transactions.single().categoryId)
            assertEquals(1, classifier.requests.size)
            val previewAssociation = assertNotNull(preview.transactions.single().receiptAssociation)
            assertEquals(ReceiptAssociationStatus.MATCHED, previewAssociation.status)
            val previewReceipt = assertNotNull(previewAssociation.summary)
            assertEquals("2026-09-10", previewReceipt.date)
            assertEquals("Синтетический продавец", previewReceipt.merchant)
            assertEquals(34900L, previewReceipt.amountMinor)
            assertEquals("Синтетическая позиция", preview.transactions.single().items.single().name)
            assertEquals(1, source.transactionFetchCalls)
            assertEquals(1, receiptSource.searchCalls)
            assertEquals(1, receiptSource.detailCalls)
            assertTrue(
                handled.response.assistantText.contains("Сопоставление чеков завершено: привязано 1"),
            )
            assertFalse(handled.response.assistantText.contains("в очеред"))
            assertFalse(handled.response.assistantText.contains("выполнится после завершения текущего плана"))
            assertTrue(
                planner.requests.last().messages.any { message ->
                    message.role == "tool" &&
                        message.name == "app_associate_receipts" &&
                        message.content.contains("в этом же запросе")
                },
            )
            assertTrue(planner.requests.first().tools.orEmpty().any {
                it.function.name == "app_associate_receipts"
            })
            assertFalse(planner.requests.first().tools.orEmpty().any {
                it.function.name.startsWith("mcp_receipts_")
            })
            val confirmed = native.confirm(
                sessionId = session.session.id,
                expectedRevision = preview.sessionRevision,
                previewId = preview.id,
            )
            val imported = assertNotNull(confirmed.draft).transactions.single()
            assertEquals("ДЕМО МАРКЕТ", imported.transaction.merchant)
            assertEquals("food.groceries", imported.transaction.categoryId)
            assertEquals(34900L, imported.transaction.amountMinor)
            assertEquals("expense", imported.transaction.direction.name.lowercase())
            assertEquals(ReceiptAssociationStatus.MATCHED, imported.transaction.receiptAssociation?.status)
            assertEquals("Синтетическая позиция", imported.transaction.items.single().name)
            assertTrue(dependencies.agent.getPreferences().confirmedDecisions.isEmpty())
        } finally {
            dependencies.scheduler?.close()
            database.deleteIfExists()
        }
    }

    @Test
    fun explicitReceiptScopeIsNotBypassedForUniquePreviewCandidate() = runBlocking {
        val database = Files.createTempFile("native-explicit-receipt-scope-", ".sqlite")
        val classifier = FakeAgentGateway(ArrayDeque(listOf(FakeAgentGateway.PURCHASE_CLASSIFICATION_JSON)))
        val selectorGateway = FakeAgentGateway(
            ArrayDeque(listOf("""{"receipt_alias":null,"confidence":0.0,"not_target":true}""")),
        )
        val dependencies = fakeAgentDependencies(database.toString(), classifier)
        val receiptSource = ReceiptAssociationFixture(
            dateTime = "2026-09-10T12:00:00Z",
            amountMinor = 34900,
        )
        val source = SyntheticSourceWithReceipts(receiptSource)
        val planner = SyntheticMixedPlanGateway(respectExplicitInstruction = false)

        val session = dependencies.agent.createSession(
            "Synthetic explicit receipt scope",
            dependencies.runtimeConfig.defaultAgentConfig(),
        )
        try {
            val native = NativeMcpAgent(
                agent = dependencies.agent,
                gatewayResolver = AgentGatewayResolver { planner },
                mcpTools = source,
                runtimeConfig = nativeMcpEnabled(dependencies.runtimeConfig),
                transactionSourceAdapters = listOf(SyntheticTransactionAdapter()),
                receiptMatchSelector = LlmReceiptMatchSelector(AgentGatewayResolver { selectorGateway }),
            )

            val scopedRequest = "Import operations for September. " +
                "ordinary context ".repeat(130) +
                "only link the receipt to DEMO MARKET."
            assertTrue(scopedRequest.indexOf("only link the receipt to DEMO MARKET") > 2_000)
            val handled = assertIs<NativeMcpHandlingResult.Handled>(
                native.handle(
                    sessionId = session.session.id,
                    expectedRevision = session.session.revision,
                    text = scopedRequest,
                ),
            )
            val preview = assertNotNull(handled.response.mcpPreview)

            assertEquals(1, selectorGateway.requests.size)
            assertTrue(
                selectorGateway.requests.single().messages.last().content
                    .contains("only link the receipt to DEMO MARKET"),
            )
            assertEquals(0, preview.receiptMatching?.matchedCount)
            assertEquals(1, preview.receiptMatching?.unmatchedCount)
            assertEquals(ReceiptAssociationStatus.UNMATCHED, preview.transactions.single().receiptAssociation?.status)
            assertEquals(1, receiptSource.searchCalls)
        } finally {
            dependencies.scheduler?.close()
            database.deleteIfExists()
        }
    }
    @Test
    fun sequentialImportsCannotReuseAnAlreadyAssociatedReceipt() = runBlocking {
        val database = Files.createTempFile("native-sequential-missing-source-refs-", ".sqlite")
        val dependencies = fakeAgentDependencies(
            database.toString(),
            FakeAgentGateway(
                ArrayDeque(
                    List(4) { FakeAgentGateway.PURCHASE_CLASSIFICATION_JSON },
                ),
            ),
        )
        val receiptSource = ReceiptAssociationFixture(
            dateTime = "2026-09-10T12:00:00Z",
            amountMinor = 34900,
            searchReceiptKeys = listOf(
                "synthetic-private-receipt-key-first",
                "synthetic-private-receipt-key-second",
            ),
        )
        val source = SyntheticSourceWithReceipts(receiptSource)
        val planner = SyntheticMixedPlanGateway(
            requestDates = listOf("2026-09-10", "2026-09-10"),
        )
        val adapter = SyntheticTransactionAdapter(
            candidates = listOf(
                ExternalTransactionCandidate(
                    occurredAt = "2026-09-10",
                    amountMinor = -34900,
                    currency = "RUB",
                    merchant = "ПЕРВАЯ ОПЕРАЦИЯ",
                    description = "Первая операция",
                ),
                ExternalTransactionCandidate(
                    occurredAt = "2026-09-10",
                    amountMinor = -34900,
                    currency = "RUB",
                    merchant = "ВТОРАЯ ОПЕРАЦИЯ",
                    description = "Вторая операция",
                ),
            ),
        )
        val session = dependencies.agent.createSession(
            "Sequential operations without source refs",
            dependencies.runtimeConfig.defaultAgentConfig(),
        )
        try {
            val native = NativeMcpAgent(
                agent = dependencies.agent,
                gatewayResolver = AgentGatewayResolver { planner },
                mcpTools = source,
                runtimeConfig = nativeMcpEnabled(dependencies.runtimeConfig),
                transactionSourceAdapters = listOf(adapter),
                receiptMatchSelector = ReceiptMatchSelector { _, _, choices, _, _ ->
                    ReceiptSelectionResponseView(choices.single().alias, 0.99)
                },
            )

            val firstPreviewResponse = assertIs<NativeMcpHandlingResult.Handled>(
                native.handle(
                    sessionId = session.session.id,
                    expectedRevision = session.session.revision,
                    text = "Импортируй операции за 10 сентября и найди к ним чеки.",
                ),
            )
            val firstPreview = assertNotNull(firstPreviewResponse.response.mcpPreview)
            assertEquals(
                ReceiptAssociationStatus.MATCHED,
                firstPreview.transactions.single().receiptAssociation?.status,
            )
            val firstConfirmed = native.confirm(
                sessionId = session.session.id,
                expectedRevision = firstPreview.sessionRevision,
                previewId = firstPreview.id,
            )
            val firstDraft = assertNotNull(firstConfirmed.draft)
            assertEquals(1, firstDraft.transactions.size)
            assertEquals(null, firstDraft.transactions.single().transaction.sourceRef)
            assertEquals(
                ReceiptAssociationStatus.MATCHED,
                firstDraft.transactions.single().transaction.receiptAssociation?.status,
            )

            DriverManager.getConnection("jdbc:sqlite:${database}").use { connection ->
                connection.prepareStatement(
                    """
                        UPDATE draft_transactions
                        SET transaction_json = replace(
                            transaction_json, '"privacy_version":1', '"privacy_version":0'
                        )
                        WHERE session_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, session.session.id)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            val legacyState = dependencies.agent.getSession(session.session.id)
            assertNull(legacyState.draft!!.transactions.first().transaction.receiptAssociation?.summary)
            assertTrue(legacyState.draft!!.transactions.first().transaction.items.isEmpty())
            assertFalse(legacyState.toString().contains("synthetic-private-receipt-key-first"))

            val secondPreviewResponse = assertIs<NativeMcpHandlingResult.Handled>(
                native.handle(
                    sessionId = session.session.id,
                    expectedRevision = firstConfirmed.session.revision,
                    text = "Добавь операции за 10 сентября и найди к ним чеки.",
                ),
            )
            val secondPreview = assertNotNull(secondPreviewResponse.response.mcpPreview)
            assertEquals("food.groceries", secondPreview.transactions.single().categoryId)
            assertEquals(0, secondPreview.receiptMatching?.matchedCount)
            assertEquals(1, secondPreview.receiptMatching?.ambiguousCount)
            assertEquals(
                ReceiptAssociationStatus.AMBIGUOUS,
                secondPreview.transactions.single().receiptAssociation?.status,
            )
            assertTrue(secondPreview.transactions.single().items.isEmpty())
            val secondConfirmed = native.confirm(
                sessionId = session.session.id,
                expectedRevision = secondPreview.sessionRevision,
                previewId = secondPreview.id,
            )
            val rows = secondConfirmed.draft!!.transactions

            assertEquals(2, rows.size)
            assertEquals(
                listOf("ПЕРВАЯ ОПЕРАЦИЯ", "ВТОРАЯ ОПЕРАЦИЯ"),
                rows.map { it.transaction.merchant },
            )
            assertTrue(rows.all { it.transaction.sourceRef == null })
            assertEquals(2, rows.map { it.transaction.sourceIndex }.distinct().size)
            assertEquals(
                ReceiptAssociationStatus.MATCHED,
                rows.first().transaction.receiptAssociation?.status,
            )
            assertEquals(
                ReceiptAssociationStatus.AMBIGUOUS,
                rows.last().transaction.receiptAssociation?.status,
            )
            assertTrue(rows.last().transaction.items.isEmpty())
            assertEquals(2, receiptSource.searchCalls)
            assertEquals(2, source.transactionFetchCalls)
            assertEquals(2, receiptSource.detailCalls)
        } finally {
            dependencies.scheduler?.close()
            database.deleteIfExists()
        }
    }

    @Test
    fun sameBatchReceiptProjectionCollisionMakesEveryRowAmbiguous() = runBlocking {
        val database = Files.createTempFile("native-same-batch-receipt-collision-", ".sqlite")
        val dependencies = fakeAgentDependencies(
            database.toString(),
            FakeAgentGateway(ArrayDeque(listOf(FakeAgentGateway.TWO_PURCHASES_CLASSIFICATION_JSON))),
        )
        val receiptSource = ReceiptAssociationFixture(
            dateTime = "2026-09-10T12:00:00Z",
            amountMinor = 34900,
            includeSecondReceipt = true,
            searchReceiptKeys = listOf(
                "synthetic-private-receipt-key-first",
                "synthetic-private-receipt-key-second",
            ),
        )
        val source = SyntheticSourceWithReceipts(receiptSource)
        val planner = SyntheticMixedPlanGateway(requestDates = listOf("2026-09-10"))
        val adapter = SyntheticTransactionAdapter(
            candidates = listOf(
                ExternalTransactionCandidate(
                    occurredAt = "2026-09-10T12:00:00Z",
                    amountMinor = -34900,
                    currency = "RUB",
                    merchant = "Синтетический продавец",
                    description = "Одинаковая операция",
                    sourceRef = "synthetic-batch-transaction-first",
                ),
                ExternalTransactionCandidate(
                    occurredAt = "2026-09-10T12:00:00Z",
                    amountMinor = -34900,
                    currency = "RUB",
                    merchant = "Синтетический продавец",
                    description = "Одинаковая операция",
                    sourceRef = "synthetic-batch-transaction-second",
                ),
            ),
            returnAllCandidatesInOneFetch = true,
        )
        val session = dependencies.agent.createSession(
            "Same-batch receipt projection collision",
            dependencies.runtimeConfig.defaultAgentConfig(),
        )
        try {
            val native = NativeMcpAgent(
                agent = dependencies.agent,
                gatewayResolver = AgentGatewayResolver { planner },
                mcpTools = source,
                runtimeConfig = nativeMcpEnabled(dependencies.runtimeConfig),
                transactionSourceAdapters = listOf(adapter),
                receiptMatchSelector = ReceiptMatchSelector { _, transaction, choices, _, _ ->
                    val choiceIndex = if (transaction.sourceRef.orEmpty().endsWith("first")) 0 else 1
                    ReceiptSelectionResponseView(choices[choiceIndex].alias, 0.99)
                },
            )

            val handled = assertIs<NativeMcpHandlingResult.Handled>(
                native.handle(
                    sessionId = session.session.id,
                    expectedRevision = session.session.revision,
                    text = "Загрузи две синтетические операции и найди к ним чеки.",
                ),
            )
            val preview = assertNotNull(handled.response.mcpPreview)

            assertEquals(2, preview.transactions.size)
            assertEquals(0, preview.receiptMatching?.matchedCount)
            assertEquals(2, preview.receiptMatching?.ambiguousCount)
            assertTrue(preview.transactions.all {
                it.receiptAssociation?.status == ReceiptAssociationStatus.AMBIGUOUS &&
                    it.items.isEmpty()
            })
            assertEquals(1, source.transactionFetchCalls)
            assertEquals(1, receiptSource.searchCalls)
            assertEquals(2, receiptSource.detailCalls)
        } finally {
            dependencies.scheduler?.close()
            database.deleteIfExists()
        }
    }

    @Test
    fun emptyNewFetchDoesNotMatchReceiptsOnAnExistingDraft() = runBlocking {
        val database = Files.createTempFile("native-empty-fetch-existing-draft-", ".sqlite")
        val dependencies = fakeAgentDependencies(database.toString(), FakeAgentGateway())
        val receiptSource = ReceiptAssociationFixture()
        val source = SyntheticSourceWithReceipts(receiptSource)
        val planner = SyntheticMixedPlanGateway(requestDates = listOf("2026-09-10"))
        val adapter = SyntheticTransactionAdapter(
            candidates = emptyList(),
            allowEmptyFetches = true,
        )
        val session = dependencies.agent.createSession(
            "Empty fetch with existing draft",
            dependencies.runtimeConfig.defaultAgentConfig(),
        )
        try {
            val initial = dependencies.agent.sendMessage(
                sessionId = session.session.id,
                expectedRevision = session.session.revision,
                text = "Синтетическая выписка",
            )
            val existingDraft = assertNotNull(initial.draft)
            val native = NativeMcpAgent(
                agent = dependencies.agent,
                gatewayResolver = AgentGatewayResolver { planner },
                mcpTools = source,
                runtimeConfig = nativeMcpEnabled(dependencies.runtimeConfig),
                transactionSourceAdapters = listOf(adapter),
            )

            val handled = assertIs<NativeMcpHandlingResult.Handled>(
                native.handle(
                    sessionId = session.session.id,
                    expectedRevision = initial.session.revision,
                    text = "Импортируй операции за сентябрь и чеки к ним",
                ),
            )

            assertNull(handled.response.mcpPreview)
            assertEquals(existingDraft.transactions, handled.response.state.draft?.transactions)
            assertTrue(handled.response.assistantText.contains("Новых банковских операций не найдено"))
            assertTrue(
                handled.response.assistantText.contains(
                    "Сопоставление чеков с существующими операциями не выполнялось",
                ),
            )
            assertEquals(1, source.transactionFetchCalls)
            assertEquals(0, receiptSource.searchCalls)
            assertEquals(0, receiptSource.detailCalls)
        } finally {
            dependencies.scheduler?.close()
            database.deleteIfExists()
        }
    }
    
    @Test
    fun mixedRequestShowsBankOnlyPreviewWhenReceiptSourceFails() = runBlocking {
        val database = Files.createTempFile("native-mixed-receipt-source-error-", ".sqlite")
        val dependencies = fakeAgentDependencies(
            database.toString(),
            FakeAgentGateway(ArrayDeque(listOf(FakeAgentGateway.PURCHASE_CLASSIFICATION_JSON))),
        )
        val rawFailure = "synthetic-private-receipt-response"
        val receiptSource = ReceiptAssociationFixture(
            requestFailure = rawFailure,
            dateTime = "2026-09-10T12:00:00Z",
            amountMinor = 34900,
        )
        val source = SyntheticSourceWithReceipts(receiptSource)
        val planner = SyntheticMixedPlanGateway()
        val session = dependencies.agent.createSession(
            "Synthetic receipt source error",
            dependencies.runtimeConfig.defaultAgentConfig(),
        )
        try {
            val native = NativeMcpAgent(
                agent = dependencies.agent,
                gatewayResolver = AgentGatewayResolver { planner },
                mcpTools = source,
                runtimeConfig = nativeMcpEnabled(dependencies.runtimeConfig),
                transactionSourceAdapters = listOf(SyntheticTransactionAdapter()),
                idGenerator = { "source-error-preview" },
            )
            val handled = assertIs<NativeMcpHandlingResult.Handled>(
                native.handle(
                    sessionId = session.session.id,
                    expectedRevision = session.session.revision,
                    text = "Загрузи операции за 10 сентября и найди соответствующие чеки.",
                ),
            )
            val preview = assertNotNull(handled.response.mcpPreview)
            assertEquals("source_error", preview.receiptMatching?.status?.name?.lowercase())
            assertEquals(1, preview.transactions.size)
            assertTrue(handled.response.assistantText.contains("Источник чеков недоступен"))
            assertFalse(handled.response.assistantText.contains(rawFailure))

            val confirmed = native.confirm(
                sessionId = session.session.id,
                expectedRevision = preview.sessionRevision,
                previewId = preview.id,
            )
            val imported = assertNotNull(confirmed.draft).transactions.single()
            assertEquals("ДЕМО МАРКЕТ", imported.transaction.merchant)
            assertEquals(34900L, imported.transaction.amountMinor)
            assertEquals("expense", imported.transaction.direction.name.lowercase())
            assertEquals(null, imported.transaction.receiptAssociation)
            assertFalse(confirmed.messages.any { it.content.contains(rawFailure) || it.displayText.contains(rawFailure) })
        } finally {
            dependencies.scheduler?.close()
            database.deleteIfExists()
        }
    }

    @Test
    fun bankToolAndMalformedResponsesAreNotReportedAsEmptyFetches() = runBlocking {
        val cases = listOf(
            true to null,
            false to """{"unexpected":"synthetic-private-bank-payload"}""",
        )
        cases.forEachIndexed { index, (toolError, responseText) ->
            val database = Files.createTempFile("native-bank-fetch-error-$index-", ".sqlite")
            val dependencies = fakeAgentDependencies(database.toString(), FakeAgentGateway())
            val runtime = nativeMcpEnabled(dependencies.runtimeConfig)
            val provider = FakeNativeMcpProvider(
                transactionDate = "2026-09-10",
                transactionFetchError = toolError,
                transactionResponseText = responseText,
            )
            val planner = NativeLoopGateway()
            val session = dependencies.agent.createSession(
                "Synthetic bank fetch error",
                runtime.defaultAgentConfig(),
            )
            try {
                val native = NativeMcpAgent(
                    agent = dependencies.agent,
                    gatewayResolver = AgentGatewayResolver { planner },
                    mcpTools = provider,
                    runtimeConfig = runtime,
                    idGenerator = { "bank-error-$index" },
                )
                val handled = assertIs<NativeMcpHandlingResult.Handled>(
                    native.handle(
                        sessionId = session.session.id,
                        expectedRevision = session.session.revision,
                        text = "Загрузи операции за 10 сентября.",
                    ),
                )

                assertNull(handled.response.mcpPreview)
                assertTrue(
                    handled.response.assistantText.contains(
                        "Не удалось загрузить операции из банковского источника.",
                    ),
                )
                assertFalse(handled.response.assistantText.contains("Новых банковских операций не найдено"))
                assertFalse(handled.response.assistantText.contains("synthetic-private-bank"))
                assertNull(handled.response.state.draft)
                assertEquals(2, provider.callCount)
                assertFalse(handled.response.state.messages.any { message ->
                    message.content.contains("synthetic-private-bank")
                })
            } finally {
                dependencies.scheduler?.close()
                database.deleteIfExists()
            }
        }
    }

    @Test
    fun newFetchCanReplacePendingPreviewAndStillAssociateReceipts() = runBlocking {
        val database = Files.createTempFile("native-replace-pending-preview-", ".sqlite")
        val dependencies = fakeAgentDependencies(
            database.toString(),
            FakeAgentGateway(
                ArrayDeque(
                    listOf(
                        FakeAgentGateway.PURCHASE_CLASSIFICATION_JSON,
                        FakeAgentGateway.PURCHASE_CLASSIFICATION_JSON,
                    ),
                ),
            ),
        )
        val receiptSource = ReceiptAssociationFixture(
            dateTime = "2026-09-10T12:00:00Z",
            amountMinor = 34900,
            searchReceiptKeys = listOf(
                "synthetic-private-receipt-key-first",
                "synthetic-private-receipt-key-second",
            ),
        )
        val source = SyntheticSourceWithReceipts(receiptSource)
        val planner = SyntheticMixedPlanGateway(
            requestDates = listOf("2026-09-10", "2026-09-11"),
        )
        val adapter = SyntheticTransactionAdapter(
            candidates = listOf(
                ExternalTransactionCandidate(
                    occurredAt = "2026-09-10T12:00:00Z",
                    amountMinor = -34900,
                    currency = "RUB",
                    merchant = "Синтетический продавец",
                    description = "Первая операция",
                    sourceRef = "synthetic-replacement-transaction-first",
                ),
                ExternalTransactionCandidate(
                    occurredAt = "2026-09-11T12:00:00Z",
                    amountMinor = -34900,
                    currency = "RUB",
                    merchant = "Синтетический продавец",
                    description = "Вторая операция",
                    sourceRef = "synthetic-replacement-transaction-second",
                ),
            ),
        )
        val session = dependencies.agent.createSession(
            "Replace pending preview",
            dependencies.runtimeConfig.defaultAgentConfig(),
        )
        try {
            val native = NativeMcpAgent(
                agent = dependencies.agent,
                gatewayResolver = AgentGatewayResolver { planner },
                mcpTools = source,
                runtimeConfig = nativeMcpEnabled(dependencies.runtimeConfig),
                transactionSourceAdapters = listOf(adapter),
                receiptMatchSelector = ReceiptMatchSelector { _, _, choices, _, _ ->
                    ReceiptSelectionResponseView(choices.single().alias, 0.99)
                },
            )
            val first = assertIs<NativeMcpHandlingResult.Handled>(
                native.handle(
                    sessionId = session.session.id,
                    expectedRevision = session.session.revision,
                    text = "Загрузи операции за 10 сентября и найди чеки.",
                ),
            )
            val firstPreview = assertNotNull(first.response.mcpPreview)
            val second = assertIs<NativeMcpHandlingResult.Handled>(
                native.handle(
                    sessionId = session.session.id,
                    expectedRevision = firstPreview.sessionRevision,
                    text = "Замени незавершённый preview операциями за 11 сентября и найди чеки.",
                ),
            )
            val secondPreview = assertNotNull(second.response.mcpPreview)

            assertTrue(firstPreview.id != secondPreview.id)
            assertEquals("2026-09-11T12:00:00Z", secondPreview.transactions.single().occurredAt)
            assertEquals(
                ReceiptAssociationStatus.MATCHED,
                secondPreview.transactions.single().receiptAssociation?.status,
            )
            assertTrue(planner.requests[3].tools.orEmpty().any {
                it.function.name == "app_associate_receipts"
            })
            assertEquals(2, source.transactionFetchCalls)
            assertEquals(2, receiptSource.searchCalls)
            assertEquals(2, receiptSource.detailCalls)
            assertFailsWith<IllegalArgumentException> {
                native.confirm(
                    sessionId = session.session.id,
                    expectedRevision = secondPreview.sessionRevision,
                    previewId = firstPreview.id,
                )
            }
            assertNull(dependencies.agent.getSession(session.session.id).draft)
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
                    it.content.contains("Предыдущие аргументы transaction-source fetch")
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
            assertEquals(requestCountBeforePreviewEdit + 1, gateway.requests.size)
        } finally {
            database.deleteIfExists()
        }
    }

    @Test
    fun mixedRequestKeepsPreviewWhenReceiptSelectorFails() = runBlocking {
        val database = Files.createTempFile("native-mixed-selector-error-", ".sqlite")
        val dependencies = fakeAgentDependencies(
            database.toString(),
            FakeAgentGateway(ArrayDeque(listOf(FakeAgentGateway.PURCHASE_CLASSIFICATION_JSON))),
        )
        val receiptSource = ReceiptAssociationFixture(
            dateTime = "2026-09-10T12:00:00Z",
            amountMinor = 34900,
            includeSecondReceipt = true,
            secondReceiptMerchant = "Синтетический продавец 2",
        )
        val source = SyntheticSourceWithReceipts(receiptSource)
        val planner = SyntheticMixedPlanGateway()
        var selectionInstruction: String? = null
        val session = dependencies.agent.createSession(
            "Synthetic receipt selector error",
            dependencies.runtimeConfig.defaultAgentConfig(),
        )
        try {
            val native = NativeMcpAgent(
                agent = dependencies.agent,
                gatewayResolver = AgentGatewayResolver { planner },
                mcpTools = source,
                runtimeConfig = nativeMcpEnabled(dependencies.runtimeConfig),
                transactionSourceAdapters = listOf(SyntheticTransactionAdapter()),
                idGenerator = { "selector-error-preview" },
                receiptMatchSelector = ReceiptMatchSelector { _, _, _, explicitInstruction, _ ->
                    selectionInstruction = explicitInstruction
                    error("synthetic-selector-private-response")
                },
            )
            val handled = assertIs<NativeMcpHandlingResult.Handled>(
                native.handle(
                    sessionId = session.session.id,
                    expectedRevision = session.session.revision,
                    text = "Загрузи операции за 10 сентября и найди соответствующие чеки.",
                ),
            )
            assertEquals("", selectionInstruction)

            val preview = assertNotNull(handled.response.mcpPreview)
            assertEquals("completed", preview.receiptMatching?.status?.name?.lowercase())
            assertEquals(0, preview.receiptMatching?.matchedCount)
            assertEquals(1, preview.receiptMatching?.ambiguousCount)
            assertEquals(0, preview.receiptMatching?.unmatchedCount)
            assertEquals(1, preview.transactions.size)
            val previewAssociation = assertNotNull(preview.transactions.single().receiptAssociation)
            assertEquals(ReceiptAssociationStatus.AMBIGUOUS, previewAssociation.status)
            assertEquals(2, previewAssociation.review?.candidates?.size)
            assertTrue(handled.response.assistantText.contains("неоднозначных 1"))
            assertFalse(handled.response.assistantText.contains("synthetic-selector-private-response"))
            assertEquals(2, receiptSource.detailCalls)

            val confirmed = native.confirm(
                sessionId = session.session.id,
                expectedRevision = preview.sessionRevision,
                previewId = preview.id,
            )
            val imported = assertNotNull(confirmed.draft).transactions.single()
            assertEquals("ДЕМО МАРКЕТ", imported.transaction.merchant)
            assertEquals(34900L, imported.transaction.amountMinor)
            assertEquals("expense", imported.transaction.direction.name.lowercase())
            assertEquals(ReceiptAssociationStatus.AMBIGUOUS, imported.transaction.receiptAssociation?.status)
        } finally {
            dependencies.scheduler?.close()
            database.deleteIfExists()
        }
    }

    @Test
    fun missingReceiptToolsProducesBankOnlyPreviewWhenPlannerSelectsReceiptAction() = runBlocking {
        val database = Files.createTempFile("native-bank-only-missing-receipts-", ".sqlite")
        val dependencies = fakeAgentDependencies(database.toString(), FakeAgentGateway())
        val receiptSource = ReceiptAssociationFixture()
        val source = SyntheticSourceWithReceipts(receiptSource, exposeReceiptTools = false)
        val planner = SyntheticMixedPlanGateway(requestReceiptAssociation = true)
        val adapter = SyntheticTransactionAdapter()
        val session = dependencies.agent.createSession(
            "Bank-only receipt capability error",
            dependencies.runtimeConfig.defaultAgentConfig(),
        )
        try {
            val native = NativeMcpAgent(
                agent = dependencies.agent,
                gatewayResolver = AgentGatewayResolver { planner },
                mcpTools = source,
                runtimeConfig = nativeMcpEnabled(dependencies.runtimeConfig),
                transactionSourceAdapters = listOf(adapter),
                idGenerator = { "missing-receipts-preview" },
            )

            val handled = assertIs<NativeMcpHandlingResult.Handled>(
                native.handle(
                    sessionId = session.session.id,
                    expectedRevision = session.session.revision,
                    text = "Импортируй операции за сентябрь и чеки к ним",
                ),
            )
            val preview = assertNotNull(handled.response.mcpPreview)

            assertEquals("source_error", preview.receiptMatching?.status?.name?.lowercase())
            assertEquals(1, preview.transactions.size)
            assertEquals(null, preview.transactions.single().receiptAssociation)
            assertTrue(preview.transactions.single().items.isEmpty())
            assertTrue(
                handled.response.assistantText.contains(
                    "требуемые инструменты источника не предоставлены",
                ),
            )
            assertEquals(1, source.transactionFetchCalls)
            assertEquals(0, receiptSource.searchCalls)
            assertEquals(0, receiptSource.detailCalls)
            assertTrue(planner.requests.first().tools.orEmpty().any {
                it.function.name == "mcp_synthetic-source_fetch-transactions"
            })
            assertTrue(planner.requests.first().tools.orEmpty().any {
                it.function.name == "app_associate_receipts"
            })
            assertFalse(planner.requests.first().tools.orEmpty().any {
                it.function.name.startsWith("mcp_receipts_")
            })

            val confirmed = native.confirm(
                sessionId = session.session.id,
                expectedRevision = preview.sessionRevision,
                previewId = preview.id,
            )
            val imported = assertNotNull(confirmed.draft).transactions.single()
            assertEquals("synthetic-transaction-001", imported.transaction.sourceRef)
            assertEquals(1, imported.transaction.sourceIndex)
            assertEquals("2026-09-10T00:00:00Z", imported.transaction.occurredAt)
            assertEquals("RUB", imported.transaction.currency)
            assertEquals("ДЕМО МАРКЕТ", imported.transaction.merchant)
            assertEquals(34900L, imported.transaction.amountMinor)
            assertEquals(null, imported.transaction.receiptAssociation)
            assertTrue(imported.transaction.items.isEmpty())
        } finally {
            dependencies.scheduler?.close()
            database.deleteIfExists()
        }
    }

    @Test
    fun bankOnlyPlannerPlanDoesNotCallMissingReceiptSource() = runBlocking {
        val database = Files.createTempFile("native-bank-only-no-receipt-intent-", ".sqlite")
        val dependencies = fakeAgentDependencies(database.toString(), FakeAgentGateway())
        val receiptSource = ReceiptAssociationFixture()
        val source = SyntheticSourceWithReceipts(receiptSource, exposeReceiptTools = false)
        val planner = SyntheticMixedPlanGateway(requestReceiptAssociation = false)
        val session = dependencies.agent.createSession(
            "Bank-only without receipt intent",
            dependencies.runtimeConfig.defaultAgentConfig(),
        )
        try {
            val native = NativeMcpAgent(
                agent = dependencies.agent,
                gatewayResolver = AgentGatewayResolver { planner },
                mcpTools = source,
                runtimeConfig = nativeMcpEnabled(dependencies.runtimeConfig),
                transactionSourceAdapters = listOf(SyntheticTransactionAdapter()),
                idGenerator = { "no-receipt-intent-preview" },
            )

            val handled = assertIs<NativeMcpHandlingResult.Handled>(
                native.handle(
                    sessionId = session.session.id,
                    expectedRevision = session.session.revision,
                    text = "Загрузи операции за 10 сентября.",
                ),
            )
            val preview = assertNotNull(handled.response.mcpPreview)

            assertNull(preview.receiptMatching)
            assertFalse(handled.response.assistantText.contains("источник чеков недоступен"))
            assertEquals(1, source.transactionFetchCalls)
            assertEquals(0, receiptSource.searchCalls)
            assertEquals(0, receiptSource.detailCalls)
            assertTrue(planner.requests.first().tools.orEmpty().any {
                it.function.name == "app_associate_receipts"
            })
            assertFalse(planner.requests.first().tools.orEmpty().any {
                it.function.name.startsWith("mcp_receipts_")
            })
        } finally {
            dependencies.scheduler?.close()
            database.deleteIfExists()
        }
    }

    @Test
    fun entireToolBatchIsValidatedBeforeAnyExternalCall() = runBlocking {
        val malformedCalls = listOf(
            ChatToolCall(
                id = "unknown-late-call",
                function = ChatFunctionCall(
                    name = "mcp_tbank-transactions_missing-tool",
                    arguments = "{}",
                ),
            ),
            ChatToolCall(
                id = "missing-required-arguments",
                function = ChatFunctionCall(
                    name = "mcp_tbank-transactions_get-account-transactions",
                    arguments = "{}",
                ),
            ),
        )
        malformedCalls.forEachIndexed { index, malformedCall ->
            val database = Files.createTempFile("native-atomic-tool-batch-$index-", ".sqlite")
            val dependencies = fakeAgentDependencies(database.toString(), FakeAgentGateway())
            val provider = FakeNativeMcpProvider()
            val gateway = object : ChatCompletionGateway {
                override var contextWindowTokens: Int? = null
                override var maxOutputTokens: Int? = null

                override suspend fun complete(
                    request: ChatCompletionRequest,
                ): ChatCompletionResponse = ChatCompletionResponse(
                    choices = listOf(
                        ChatChoice(
                            message = ResponseMessage(
                                content = null,
                                toolCalls = listOf(
                                    ChatToolCall(
                                        id = "valid-early-call",
                                        function = ChatFunctionCall(
                                            name = "mcp_tbank-transactions_list-accounts",
                                            arguments = "{}",
                                        ),
                                    ),
                                    malformedCall,
                                ),
                            ),
                            finishReason = "tool_calls",
                        ),
                    ),
                )
            }
            val runtime = nativeMcpEnabled(dependencies.runtimeConfig)
            val session = dependencies.agent.createSession(
                "Atomic tool batch $index",
                runtime.defaultAgentConfig(),
            )
            try {
                val native = NativeMcpAgent(
                    agent = dependencies.agent,
                    gatewayResolver = AgentGatewayResolver { gateway },
                    mcpTools = provider,
                    runtimeConfig = runtime,
                )
                var failure: Throwable? = null
                try {
                    native.handle(
                        sessionId = session.session.id,
                        expectedRevision = session.session.revision,
                        text = "Прочитай банковский счёт.",
                    )
                } catch (error: Throwable) {
                    failure = error
                }

                assertIs<AgentResponseException>(failure)
                assertEquals(0, provider.callCount)
                assertEquals(session, dependencies.agent.getSession(session.session.id))
            } finally {
                dependencies.scheduler?.close()
                database.deleteIfExists()
            }
        }
    }

    @Test
    fun accountAliasFetchAutomaticallyResolvesOneExactAccount() = runBlocking {
        val database = Files.createTempFile("native-account-alias-single-", ".sqlite")
        val dependencies = fakeAgentDependencies(database.toString(), FakeAgentGateway())
        val runtime = nativeMcpEnabled(dependencies.runtimeConfig)
        val gateway = GetTransactionsOnlyGateway("Основной счёт")
        val provider = FakeNativeMcpProvider(transactionResponseText = """{"transactions":[]}""")
        val session = dependencies.agent.createSession(
            "Single account alias",
            runtime.defaultAgentConfig(),
        )
        try {
            val native = NativeMcpAgent(
                agent = dependencies.agent,
                gatewayResolver = AgentGatewayResolver { gateway },
                mcpTools = provider,
                runtimeConfig = runtime,
            )
            val handled = assertIs<NativeMcpHandlingResult.Handled>(
                native.handle(
                    sessionId = session.session.id,
                    expectedRevision = session.session.revision,
                    text = "Загрузи операции по Основному счёту.",
                ),
            )

            assertEquals(listOf("list-accounts", "get-account-transactions"), provider.calls)
            assertEquals("raw-account-001", provider.transactionArguments["account_ref"]?.jsonPrimitive?.content)
            assertTrue(handled.response.state.messages.none { it.content.contains("raw-account-001") })
            assertTrue(gateway.requests.flatMap { it.messages }.none {
                it.content.contains("raw-account-001")
            })
        } finally {
            dependencies.scheduler?.close()
            database.deleteIfExists()
        }
    }

    @Test
    fun ambiguousAccountAliasAsksBeforeFetchingTransactions() = runBlocking {
        val database = Files.createTempFile("native-account-alias-ambiguous-", ".sqlite")
        val dependencies = fakeAgentDependencies(database.toString(), FakeAgentGateway())
        val runtime = nativeMcpEnabled(dependencies.runtimeConfig)
        val gateway = GetTransactionsOnlyGateway("Неизвестный счёт")
        val provider = FakeNativeMcpProvider(
            transactionResponseText = """{"transactions":[]}""",
            accountResponseText = """{"accounts":[
                {"id":"synthetic-account-1","name":"Синтетический счёт A"},
                {"id":"synthetic-account-2","name":"Синтетический счёт B"}
            ]}""",
        )
        val session = dependencies.agent.createSession(
            "Ambiguous account alias",
            runtime.defaultAgentConfig(),
        )
        try {
            val native = NativeMcpAgent(
                agent = dependencies.agent,
                gatewayResolver = AgentGatewayResolver { gateway },
                mcpTools = provider,
                runtimeConfig = runtime,
            )
            val handled = assertIs<NativeMcpHandlingResult.Handled>(
                native.handle(
                    sessionId = session.session.id,
                    expectedRevision = session.session.revision,
                    text = "Загрузи операции за сентябрь.",
                ),
            )

            assertTrue(handled.response.assistantText.contains("Уточните, какой счёт использовать"))
            assertTrue(handled.response.assistantText.contains("Синтетический счёт A"))
            assertTrue(handled.response.assistantText.contains("Синтетический счёт B"))
            assertEquals(listOf("list-accounts"), provider.calls)
            assertTrue(provider.transactionArguments.isEmpty())
            assertTrue(handled.response.state.messages.none {
                it.content.contains("synthetic-account-1") || it.content.contains("synthetic-account-2")
            })
            assertTrue(gateway.requests.flatMap { it.messages }.none {
                it.content.contains("synthetic-account-1") || it.content.contains("synthetic-account-2")
            })
        } finally {
            dependencies.scheduler?.close()
            database.deleteIfExists()
        }
    }

    @Test
    fun duplicateAccountAliasesAreExplicitlySelectable() = runBlocking {
        val database = Files.createTempFile("native-account-alias-duplicates-", ".sqlite")
        val dependencies = fakeAgentDependencies(database.toString(), FakeAgentGateway())
        val runtime = nativeMcpEnabled(dependencies.runtimeConfig)
        val gateway = DuplicateAccountAliasGateway()
        val provider = FakeNativeMcpProvider(
            transactionResponseText = """{"transactions":[]}""",
            accountResponseText = """{"accounts":[
                {"id":"synthetic-account-a","name":"Синтетический счёт"},
                {"id":"synthetic-account-b","name":"Синтетический счёт"}
            ]}""",
        )
        val session = dependencies.agent.createSession(
            "Duplicate account aliases",
            runtime.defaultAgentConfig(),
        )
        try {
            val native = NativeMcpAgent(
                agent = dependencies.agent,
                gatewayResolver = AgentGatewayResolver { gateway },
                mcpTools = provider,
                runtimeConfig = runtime,
            )
            val ambiguous = assertIs<NativeMcpHandlingResult.Handled>(
                native.handle(
                    sessionId = session.session.id,
                    expectedRevision = session.session.revision,
                    text = "Загрузи операции по Синтетическому счёту.",
                ),
            )
            assertTrue(ambiguous.response.assistantText.contains("Синтетический счёт #1"))
            assertTrue(ambiguous.response.assistantText.contains("Синтетический счёт #2"))
            assertEquals(listOf("list-accounts"), provider.calls)

            val firstChoice = assertIs<NativeMcpHandlingResult.Handled>(
                native.handle(
                    sessionId = session.session.id,
                    expectedRevision = ambiguous.response.state.session.revision,
                    text = "Используй «Синтетический счёт #1».",
                ),
            )
            assertEquals(
                "synthetic-account-a",
                provider.transactionArguments["account_ref"]?.jsonPrimitive?.content,
            )
            assertEquals(
                listOf("list-accounts", "list-accounts", "get-account-transactions"),
                provider.calls,
            )

            val secondChoice = assertIs<NativeMcpHandlingResult.Handled>(
                native.handle(
                    sessionId = session.session.id,
                    expectedRevision = firstChoice.response.state.session.revision,
                    text = "Используй «Синтетический счёт #2».",
                ),
            )
            assertEquals(
                "synthetic-account-b",
                provider.transactionArguments["account_ref"]?.jsonPrimitive?.content,
            )
            assertEquals(
                listOf(
                    "list-accounts",
                    "list-accounts",
                    "get-account-transactions",
                    "list-accounts",
                    "get-account-transactions",
                ),
                provider.calls,
            )
            assertTrue(secondChoice.response.state.messages.none {
                it.content.contains("synthetic-account-a") || it.content.contains("synthetic-account-b")
            })
            assertTrue(gateway.requests.flatMap { it.messages }.none {
                it.content.contains("synthetic-account-a") || it.content.contains("synthetic-account-b")
            })
        } finally {
            dependencies.scheduler?.close()
            database.deleteIfExists()
        }
    }

    private fun io.github.stolex1y.transactionimport.core.ImportSessionState.sessionRevision(): Long =
        session.revision
}
private fun nativeMcpEnabled(config: AgentRuntimeConfig): AgentRuntimeConfig =
    config.copy(mcpToolLoop = config.mcpToolLoop.copy(enabled = true))

private class ApplicationActionPlanGateway(
    private val action: String,
    private val arguments: String = if (action == "app_associate_receipts") {
        """{"respect_explicit_instruction":false}"""
    } else {
        "{}"
    },
    private val followingActions: List<Pair<String, String>> = emptyList(),
) : ChatCompletionGateway {
    val requests = mutableListOf<ChatCompletionRequest>()
    private var index = 0

    override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
        requests += request
        val actions = listOf(action to arguments) + followingActions
        val responseIndex = index++
        val message = actions.getOrNull(responseIndex)?.let { (name, args) ->
            ResponseMessage(
                content = null,
                toolCalls = listOf(
                    ChatToolCall(
                        id = "application-action-$responseIndex",
                        function = ChatFunctionCall(name = name, arguments = args),
                    ),
                ),
            )
        } ?: ResponseMessage(content = "Синтетическое действие завершено.")
        return ChatCompletionResponse(
            choices = listOf(ChatChoice(message = message, finishReason = "stop")),
        )
    }
}

private class ReceiptAssociationFixture(
    private val requestFailure: String? = null,
    private val dateTime: String = "2026-02-08T12:20:00Z",
    private val amountMinor: Long = 125050,
    private val includeSecondReceipt: Boolean = false,
    private val searchReceiptKeys: List<String> = listOf("synthetic-private-receipt-key"),
    private val secondReceiptMerchant: String = "Синтетический продавец",
) : McpToolProvider {
    var detailCalls = 0
    var searchCalls = 0

    override suspend fun allowedTools(): List<McpCallableTool> = listOf(
        McpCallableTool(
            serverId = "receipts",
            serverDisplayName = "Синтетические чеки",
            name = "search-receipts",
            description = "Поиск синтетических чеков",
            inputSchema = buildJsonObject { put("type", "object") },
        ),
        McpCallableTool(
            serverId = "receipts",
            serverDisplayName = "Синтетические чеки",
            name = "get-receipt",
            description = "Детали синтетического чека",
            inputSchema = buildJsonObject { put("type", "object") },
        ),
    )

    override suspend fun callConfiguredTool(
        serverId: String,
        tool: String,
        arguments: JsonObject,
    ): TbankToolCallResponse {
        assertEquals("receipts", serverId)
        requestFailure?.let { throw IllegalStateException(it) }
        return when (tool) {
            "search-receipts" -> {
                val receiptKey = searchReceiptKeys.getOrElse(searchCalls) { searchReceiptKeys.last() }
                searchCalls += 1
                val secondReceipt = if (includeSecondReceipt) {
                    """,{"receipt_key":"synthetic-private-receipt-key-2","merchant":"$secondReceiptMerchant","received_at":"$dateTime","amount_minor":$amountMinor,"currency":"RUB"}"""
                } else {
                    ""
                }
                TbankToolCallResponse(
                    tool = tool,
                    text = """{"receipts":[{"receipt_key":"$receiptKey","merchant":"Синтетический продавец","received_at":"$dateTime","amount_minor":$amountMinor,"currency":"RUB"}$secondReceipt],"has_more":false}""",
                )
            }
            "get-receipt" -> {
                detailCalls += 1
                val receiptKey = requireNotNull(arguments["receipt_key"]?.jsonPrimitive?.content)
                val merchant = if (receiptKey == "synthetic-private-receipt-key-2") {
                    secondReceiptMerchant
                } else {
                    "Синтетический продавец"
                }
                assertTrue(
                    receiptKey in searchReceiptKeys + "synthetic-private-receipt-key-2",
                )
                TbankToolCallResponse(
                    tool = tool,
                    text = """{"receipt_key":"$receiptKey","date_time":"$dateTime","total_minor":$amountMinor,"currency":"RUB","merchant":"$merchant","settlement_place":"Синтетическая торговая точка","fiscal_document_number":"synthetic-fiscal-id","qr_code":"synthetic-qr-payload","cashier":"Синтетический кассир","items":[{"name":"Синтетическая позиция","quantity":1,"price_minor":$amountMinor,"sum_minor":$amountMinor}]}""",
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
            else -> ResponseMessage(
                content = null,
                toolCalls = listOf(
                    ChatToolCall(
                        id = "route-preview-edit",
                        function = ChatFunctionCall(
                            name = "app_route_to_agent",
                            arguments = "{}",
                        ),
                    ),
                ),
            )
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
                    message = ResponseMessage(
                        content = null,
                        toolCalls = listOf(
                            ChatToolCall(
                                id = "route-to-agent",
                                function = ChatFunctionCall(
                                    name = "app_route_to_agent",
                                    arguments = "{}",
                                ),
                            ),
                        ),
                    ),
                    finishReason = "tool_calls",
                ),
            ),
        )
    }
}

private class DuplicateAccountAliasGateway : ChatCompletionGateway {
    val requests = mutableListOf<ChatCompletionRequest>()
    private val aliases = listOf("Синтетический счёт", "Синтетический счёт #1", "Синтетический счёт #2")
    private var nextAlias = 0

    override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
        requests += request
        val message = if (request.messages.lastOrNull()?.role == "tool") {
            ResponseMessage(content = "Операции получены.")
        } else {
            val alias = aliases.getOrNull(nextAlias++)
                ?: error("Unexpected synthetic account resolution request")
            ResponseMessage(
                content = null,
                toolCalls = listOf(
                    ChatToolCall(
                        id = "duplicate-account-fetch-$nextAlias",
                        function = ChatFunctionCall(
                            name = "mcp_tbank-transactions_get-account-transactions",
                            arguments = """
                                {"account_alias":"$alias","from":"2026-09-01","to":"2026-09-30"}
                            """.trimIndent(),
                        ),
                    ),
                ),
            )
        }
        return ChatCompletionResponse(
            choices = listOf(
                ChatChoice(
                    message = message,
                    finishReason = if (message.toolCalls.isNullOrEmpty()) "stop" else "tool_calls",
                ),
            ),
        )
    }
}

private class GetTransactionsOnlyGateway(
    private val accountAlias: String,
) : ChatCompletionGateway {
    val requests = mutableListOf<ChatCompletionRequest>()

    override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
        requests += request
        val message = if (requests.size == 1) {
            ResponseMessage(
                content = null,
                toolCalls = listOf(
                    ChatToolCall(
                        id = "get-transactions",
                        function = ChatFunctionCall(
                            name = "mcp_tbank-transactions_get-account-transactions",
                            arguments = """
                                {"account_alias":"$accountAlias","from":"2026-09-01","to":"2026-09-30","limit":50}
                            """.trimIndent(),
                        ),
                    ),
                ),
            )
        } else {
            ResponseMessage(content = "Операции получены.")
        }
        return ChatCompletionResponse(
            choices = listOf(ChatChoice(message = message, finishReason = if (requests.size == 1) "tool_calls" else "stop")),
            usage = Usage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
        )
    }
}

private class FakeNativeMcpProvider(
    private val transactionDate: String = "2026-09-02",
    private val transactionFetchError: Boolean = false,
    private val transactionResponseText: String? = null,
    private val accountResponseText: String =
        """{"data":{"accounts":[{"id":"raw-account-001","account_name":"Основной счёт","currency":"643","balance_minor":100000}]}}""",
) : McpToolProvider {
    var transactionArguments: JsonObject = JsonObject(emptyMap())
    var callCount = 0
    val calls = mutableListOf<String>()
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
    ): TbankToolCallResponse {
        callCount += 1
        calls += tool
        return when (tool) {
            "list-accounts" -> TbankToolCallResponse(
                tool = tool,
                text = accountResponseText,
            )
            "get-account-transactions" -> {
                transactionArguments = arguments
                if (transactionFetchError) {
                    TbankToolCallResponse(
                        tool = tool,
                        isError = true,
                        text = "synthetic-private-bank-error",
                    )
                } else {
                    TbankToolCallResponse(
                        tool = tool,
                        text = transactionResponseText ?: """
                            {"account_ref":"raw-account-001","account":{"name":"Основной счёт"},"transactions":[{"id":"raw-transaction-001","account_id":"raw-account-001","date":"$transactionDate","amount_minor":-12500,"currency":"643","merchant":"B121","description":"Булочная Ф. Вол"}],"next_cursor":null}
                        """.trimIndent(),
                    )
                }
            }
            else -> error("Unexpected tool: $tool")
        }
    }
}
private class SyntheticSourceWithReceipts(
    private val receiptSource: ReceiptAssociationFixture,
    private val exposeReceiptTools: Boolean = true,
) : McpToolProvider {
    var transactionFetchCalls = 0

    override suspend fun allowedTools(): List<McpCallableTool> = listOf(
        McpCallableTool(
            serverId = "synthetic-source",
            serverDisplayName = "Synthetic transaction source",
            name = "fetch-transactions",
            description = "Read-only transaction fetch",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("from", buildJsonObject { put("type", "string") })
                    put("to", buildJsonObject { put("type", "string") })
                })
            },
        ),
    ) + (if (exposeReceiptTools) receiptSource.allowedTools() else emptyList<McpCallableTool>())

    override suspend fun callConfiguredTool(
        serverId: String,
        tool: String,
        arguments: JsonObject,
    ): TbankToolCallResponse = if (serverId == "synthetic-source") {
        assertEquals("fetch-transactions", tool)
        transactionFetchCalls += 1
        TbankToolCallResponse(tool = tool, text = """{"rows":1}""")
    } else {
        receiptSource.callConfiguredTool(serverId, tool, arguments)
    }
}

private class SyntheticTransactionAdapter(
    private val candidates: List<ExternalTransactionCandidate> = listOf(
        ExternalTransactionCandidate(
            occurredAt = "2026-09-10",
            amountMinor = -34900,
            currency = "RUB",
            merchant = "ДЕМО МАРКЕТ",
            description = "Оплата в ДЕМО МАРКЕТ",
            sourceRef = "synthetic-transaction-001",
        ),
    ),
    private val allowEmptyFetches: Boolean = false,
    private val returnAllCandidatesInOneFetch: Boolean = false,
) : NativeMcpTransactionSourceAdapter {
    private var candidateIndex = 0

    override fun supports(tool: McpCallableTool): Boolean =
        tool.serverId == "synthetic-source" && tool.name == "fetch-transactions"

    override fun toolDefinition(tool: McpCallableTool): ChatToolDefinition =
        ChatToolDefinition(
            function = ChatFunctionDefinition(
                name = "mcp_synthetic-source_fetch-transactions",
                description = "Fetch synthetic transactions",
                parameters = tool.inputSchema,
            ),
        )

    override fun prepareArguments(tool: McpCallableTool, arguments: JsonObject): JsonObject = arguments

    override suspend fun normalize(
        tool: McpCallableTool,
        arguments: JsonObject,
        result: NativeMcpToolCallResult,
    ): NativeMcpTransactionSourceResult {
        assertFalse(result.isError)
        val batch = if (returnAllCandidatesInOneFetch) {
            candidateIndex = candidates.size
            candidates
        } else {
            listOfNotNull(candidates.getOrNull(candidateIndex++))
        }
        if (batch.isEmpty()) {
            check(allowEmptyFetches) { "No synthetic transaction candidate remains." }
            return NativeMcpTransactionSourceResult(
                text = """{"transactions":[]}""",
                candidates = emptyList(),
            )
        }
        val normalizedTransactions = batch.joinToString(",") { candidate ->
            """{"date":"${candidate.occurredAt}","amount_minor":${candidate.amountMinor},"currency":"${candidate.currency}","merchant":"${candidate.merchant}"}"""
        }
        return NativeMcpTransactionSourceResult(
            text = """{"transactions":[$normalizedTransactions]}""",
            candidates = batch,
        )
    }

    override fun isTransactionFetch(tool: McpCallableTool): Boolean = true
}

private class SyntheticMixedPlanGateway(
    private val rememberRule: Boolean = false,
    private val requestReceiptAssociation: Boolean = true,
    private val requestDates: List<String> = listOf("2026-09-10", "2026-09-20"),
    private val respectExplicitInstruction: Boolean = false,
) : ChatCompletionGateway {
    val requests = mutableListOf<ChatCompletionRequest>()
    private var index = 0

    override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
        requests += request
        val responseIndex = index++
        val message = if (!requestReceiptAssociation && responseIndex == 1) {
            ResponseMessage(content = "Synthetic bank-only preview is ready.")
        } else when (responseIndex % 3) {
            0 -> {
                val requestedDate = requestDates.getOrElse(responseIndex / 3) { requestDates.last() }
                ResponseMessage(
                    content = null,
                    toolCalls = listOf(
                        ChatToolCall(
                            id = "synthetic-fetch",
                            function = ChatFunctionCall(
                                name = "mcp_synthetic-source_fetch-transactions",
                                arguments = """{"from":"$requestedDate","to":"$requestedDate"}""",
                            ),
                        ),
                    ),
                )
            }
            1 -> ResponseMessage(
                content = null,
                toolCalls = listOf(
                    ChatToolCall(
                        id = "associate-receipts",
                        function = ChatFunctionCall(
                            name = "app_associate_receipts",
                            arguments = when {
                                rememberRule && respectExplicitInstruction ->
                                    """{"remember_rule":true,"respect_explicit_instruction":true}"""
                                rememberRule ->
                                    """{"remember_rule":true,"respect_explicit_instruction":false}"""
                                respectExplicitInstruction -> """{"respect_explicit_instruction":true}"""
                                else -> """{"respect_explicit_instruction":false}"""
                            },
                        ),
                    ),
                ),
            )
            else -> ResponseMessage(content = "Synthetic import and receipt match are ready.")
        }
        return ChatCompletionResponse(
            choices = listOf(
                ChatChoice(
                    message = message,
                    finishReason = if (message.toolCalls.isNullOrEmpty()) "stop" else "tool_calls",
                ),
            ),
        )
    }
}

