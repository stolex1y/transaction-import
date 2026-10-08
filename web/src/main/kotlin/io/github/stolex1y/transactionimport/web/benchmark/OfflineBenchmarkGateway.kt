package io.github.stolex1y.transactionimport.web.benchmark

import io.github.stolex1y.transactionimport.core.ChatChoice
import io.github.stolex1y.transactionimport.core.ChatCompletionGateway
import io.github.stolex1y.transactionimport.core.ChatCompletionRequest
import io.github.stolex1y.transactionimport.core.ChatCompletionResponse
import io.github.stolex1y.transactionimport.core.ChatFunctionCall
import io.github.stolex1y.transactionimport.core.ChatToolCall
import io.github.stolex1y.transactionimport.core.RequestMessage
import io.github.stolex1y.transactionimport.core.ResponseMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.concurrent.atomic.AtomicInteger

internal class OfflineBenchmarkGateway(
    private val fixture: ImportBenchmarkFixture,
) : ChatCompletionGateway {
    private val nextToolCallId = AtomicInteger()

    override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
        if (request.tools.orEmpty().isNotEmpty()) return plannerReply(request)
        val latestUser = request.messages.lastOrNull { it.role == "user" }?.content.orEmpty().trimStart()
        val content = when {
            latestUser.startsWith("Операция: дата=") -> RECEIPT_SELECTOR_REPLY
            latestUser.contains("Операции для обогащения:") -> classifierReply(latestUser)
            latestUser.startsWith("Новые операции после принятия MCP preview:") -> inclusionReply(latestUser)
            else -> throw IllegalStateException("Offline benchmark received an unsupported application request.")
        }
        return completion(content)
    }

    private fun plannerReply(request: ChatCompletionRequest): ChatCompletionResponse {
        val toolDefinitions = request.tools.orEmpty()
        val calledTools = request.messages.filter { it.role == "tool" }.mapNotNull(RequestMessage::name)
        if ("app_associate_receipts" in calledTools) return completion(EMPTY_PLANNER_REPLY)
        val function = if (calledTools.isEmpty()) {
            toolDefinitions.firstOrNull { definition ->
                definition.function.parameters["properties"]?.let { properties ->
                    properties is kotlinx.serialization.json.JsonObject && "account_alias" in properties
                } == true
            } ?: throw IllegalStateException("Offline benchmark has no transaction-fetch tool.")
        } else {
            toolDefinitions.firstOrNull { it.function.name == "app_associate_receipts" }
                ?: throw IllegalStateException("Offline benchmark has no receipt-matching action.")
        }
        val arguments = if (function.function.name == "app_associate_receipts") {
            """{"respect_explicit_instruction":true}"""
        } else {
            Json.encodeToString(
                buildJsonObject {
                    put("account_alias", fixture.modelInput.accountAlias)
                    put("from", "2026-09-01")
                    put("to", "2026-09-30")
                    put("limit", 100)
                },
            )
        }
        return completion(
            content = "",
            toolCalls = listOf(
                ChatToolCall(
                    id = "offline-tool-${nextToolCallId.incrementAndGet()}",
                    function = ChatFunctionCall(function.function.name, arguments),
                ),
            ),
        )
    }

    private fun classifierReply(userContent: String): String {
        val operations = userContent.lineSequence().mapNotNull { line ->
            val match = OPERATION_LINE.find(line) ?: return@mapNotNull null
            match.groupValues[1].toInt() to match.groupValues[2]
        }.toList()
        if (operations.size != fixture.modelInput.transactions.size) {
            throw IllegalStateException("Offline benchmark classifier received an incomplete operation set.")
        }
        return buildJsonObject {
            putJsonArray("items") {
                operations.forEach { (index, direction) ->
                    add(
                        buildJsonObject {
                            put("index", index)
                            put("category_id", if (direction == "expense") JsonPrimitive(CAFE_CATEGORY_ID) else JsonNull)
                        },
                    )
                }
            }
            putJsonArray("merchant_canonical_candidates") {}
        }.toString()
    }

    private fun inclusionReply(userContent: String): String {
        val ids = userContent.substringAfter("Новые операции после принятия MCP preview:\n")
            .substringBefore('\n')
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
        if (ids.size != fixture.modelInput.transactions.size) {
            throw IllegalStateException("Offline benchmark inclusion review received an incomplete draft.")
        }
        return buildJsonObject {
            putJsonArray("operations") {
                ids.forEach { id ->
                    add(
                        buildJsonObject {
                            put("transaction_id", id)
                            put("included", true)
                        },
                    )
                }
            }
            put("message", "offline deterministic pipeline smoke")
        }.toString()
    }

    private fun completion(content: String, toolCalls: List<ChatToolCall>? = null) = ChatCompletionResponse(
        choices = listOf(
            ChatChoice(
                message = ResponseMessage(
                    role = "assistant",
                    content = content,
                    toolCalls = toolCalls,
                ),
                finishReason = if (toolCalls.isNullOrEmpty()) "stop" else "tool_calls",
            ),
        ),
    )

    private companion object {
        const val CAFE_CATEGORY_ID = "food.cafe"
        const val RECEIPT_SELECTOR_REPLY =
            """{"receipt_alias":null,"confidence":0,"not_target":false}"""
        const val EMPTY_PLANNER_REPLY = "Операции готовы к проверке."
        val OPERATION_LINE = Regex("^\\s*(\\d+)\\. direction=(expense|income);")
    }
}
