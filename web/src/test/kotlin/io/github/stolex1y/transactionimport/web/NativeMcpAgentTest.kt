package io.github.stolex1y.transactionimport.web

import io.github.stolex1y.transactionimport.core.AgentGatewayResolver
import io.github.stolex1y.transactionimport.core.AgentRuntimeConfig
import io.github.stolex1y.transactionimport.core.ChatChoice
import io.github.stolex1y.transactionimport.core.ChatCompletionGateway
import io.github.stolex1y.transactionimport.core.ChatCompletionRequest
import io.github.stolex1y.transactionimport.core.ChatCompletionResponse
import io.github.stolex1y.transactionimport.core.ChatFunctionCall
import io.github.stolex1y.transactionimport.core.ChatToolCall
import io.github.stolex1y.transactionimport.core.McpToolLoopConfig
import io.github.stolex1y.transactionimport.core.ResponseMessage
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
