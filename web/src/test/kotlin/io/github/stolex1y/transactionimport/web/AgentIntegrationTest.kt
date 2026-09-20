package io.github.stolex1y.transactionimport.web

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentIntegrationTest {
    @Test
    fun exposesOnlyAgentSurface() {
        val database = Files.createTempFile("agent-surface-", ".sqlite")
        try {
            testApplication {
                application { module(agentDependencies = fakeAgentDependencies(database.toString())) }

                assertEquals(HttpStatusCode.OK, client.get("/agent").status)
                assertEquals(HttpStatusCode.OK, client.get("/assets/agent.js").status)
                assertEquals(HttpStatusCode.NotFound, client.get("/").status)
                assertEquals(HttpStatusCode.NotFound, client.get("/experiments").status)
                assertEquals(HttpStatusCode.NotFound, client.post("/api/extract").status)
                assertEquals(HttpStatusCode.NotFound, client.post("/api/experiments/d04").status)
            }
        } finally {
            database.deleteIfExists()
        }
    }

    @Test
    fun fakeProviderDrivesHttpAgentWorkflowWithoutExternalApi() {
        val database = Files.createTempFile("agent-integration-", ".sqlite")
        val gateway = FakeAgentGateway()
        try {
            testApplication {
                application { module(agentDependencies = fakeAgentDependencies(database.toString(), gateway)) }

                val preferences = client.put("/api/agent/preferences") {
                    jsonBody("""{"user_prompt":"Не включай переводы между счетами"}""")
                }
                assertEquals(HttpStatusCode.OK, preferences.status)

                val created = client.post("/api/agent/sessions")
                assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
                var state = created.jsonObject()
                assertEquals(
                    "summary",
                    state["session"]!!.jsonObject["context_management"]!!.jsonObject["strategy"]!!.jsonPrimitive.content,
                )
                val sessionId = state.sessionId()

                val extracted = client.post("/api/agent/sessions/$sessionId/messages") {
                    jsonBody("""{"revision":0,"text":"Списание 1250,50 ₽ и неизвестный платёж 99 ₽"}""")
                }
                assertEquals(HttpStatusCode.OK, extracted.status)
                state = extracted.jsonObject()
                assertEquals("ready", state["draft"]!!.jsonObject["status"]!!.jsonPrimitive.content)
                assertEquals(1, state["metrics"]!!.jsonArray.size)
                assertEquals("succeeded", state["metrics"]!!.jsonArray.single().jsonObject["status"]!!.jsonPrimitive.content)
                assertEquals(2, state.transactions().size)
                assertEquals(1, gateway.requests.size)
                assertEquals("fake-model", gateway.requests.single().model)
                assertEquals("json_object", gateway.requests.single().responseFormat?.type)
                assertTrue(
                    gateway.requests.single().messages.first().content.contains(
                        "Не включай переводы между счетами",
                    ),
                )

                val firstSaved = client.put("/api/agent/sessions/$sessionId/transactions/1") {
                    jsonBody(
                        """
                            {
                              "revision":1,
                              "included":true,
                              "direction":"expense",
                              "occurred_at":"2026-02-08T12:10:00",
                              "posted_at":null,
                              "amount_minor":125125,
                              "merchant":"ДЕМО МАРКЕТ",
                              "description":"Проверено вручную",
                              "category_id":"food.groceries",
                              "card_last4":"1234"
                            }
                        """.trimIndent(),
                    )
                }
                assertEquals(HttpStatusCode.OK, firstSaved.status)
                state = firstSaved.jsonObject()
                assertEquals(2L, state.revision())

                val excludedSecond = client.put("/api/agent/sessions/$sessionId/transactions/2") {
                    jsonBody(
                        """
                            {
                              "revision":2,
                              "included":false,
                              "direction":"expense",
                              "occurred_at":"2026-02-08T18:45:00",
                              "posted_at":null,
                              "amount_minor":9900,
                              "merchant":"НЕИЗВЕСТНЫЙ ПЛАТЁЖ",
                              "description":"",
                              "category_id":null,
                              "card_last4":null
                            }
                        """.trimIndent(),
                    )
                }
                assertEquals(HttpStatusCode.OK, excludedSecond.status)
                state = excludedSecond.jsonObject()
                assertFalse(state.transactions()[1].jsonObject["included"]!!.jsonPrimitive.content.toBoolean())

                val batchResponse = client.get("/api/agent/sessions/$sessionId/batch")
                assertEquals(HttpStatusCode.OK, batchResponse.status)
                val batch = batchResponse.jsonObject()
                val transactions = batch["transactions"]!!.jsonArray
                assertEquals(1, transactions.size)
                assertEquals(125125L, transactions.single().jsonObject["amount_minor"]!!.jsonPrimitive.content.toLong())
                assertEquals(
                    "Проверено вручную",
                    transactions.single().jsonObject["description"]!!.jsonPrimitive.content,
                )

                val deleted = client.delete("/api/agent/sessions/$sessionId") {
                    jsonBody("""{"revision":3}""")
                }
                assertEquals(HttpStatusCode.NoContent, deleted.status)
                assertEquals(0, client.get("/api/agent/sessions").jsonObjectArray().size)
            }
        } finally {
            database.deleteIfExists()
        }
    }

    @Test
    fun exposesReceiptStateAndRepeatableExportOverHttp() {
        val database = Files.createTempFile("agent-stateful-", ".sqlite")
        val gateway = FakeAgentGateway(
            ArrayDeque(
                listOf(
                    FakeAgentGateway.READY_DRAFT_JSON,
                    FakeAgentGateway.APPEND_STATEMENT_JSON,
                ),
            ),
        )
        try {
            testApplication {
                application { module(agentDependencies = fakeAgentDependencies(database.toString(), gateway)) }

                val createdResponse = client.post("/api/agent/sessions")
                assertEquals(HttpStatusCode.Created, createdResponse.status, createdResponse.bodyAsText())
                val created = createdResponse.jsonObject()
                val sessionId = created.sessionId()
                assertEquals(
                    "not_started",
                    created["receipt_state"]!!.jsonObject["status"]!!.jsonPrimitive.content,
                )
                assertFalse(created.containsKey("task_state"))

                val systemInvariants = client.get("/api/agent/sessions/$sessionId/invariants")
                assertEquals(HttpStatusCode.OK, systemInvariants.status)
                val invariantBody = systemInvariants.jsonObject()
                assertEquals(5, invariantBody["system"]!!.jsonArray.size)
                assertFalse(invariantBody.containsKey("task"))
                assertEquals(
                    HttpStatusCode.NotFound,
                    client.post("/api/agent/sessions/$sessionId/transition").status,
                )

                val extracted = client.post("/api/agent/sessions/$sessionId/messages") {
                    jsonBody("""{"revision":0,"text":"Синтетическая выписка"}""")
                }
                assertEquals(HttpStatusCode.OK, extracted.status)
                var state = extracted.jsonObject()
                assertEquals(
                    "has_errors",
                    state["receipt_state"]!!.jsonObject["status"]!!.jsonPrimitive.content,
                )

                val excluded = client.put("/api/agent/sessions/$sessionId/transactions/2") {
                    jsonBody(
                        """
                            {
                              "revision":1,
                              "included":false,
                              "direction":"expense",
                              "occurred_at":"2026-02-08T18:45:00",
                              "posted_at":null,
                              "amount_minor":9900,
                              "merchant":"НЕИЗВЕСТНЫЙ ПЛАТЁЖ",
                              "description":"",
                              "category_id":null,
                              "card_last4":null
                            }
                        """.trimIndent(),
                    )
                }
                assertEquals(HttpStatusCode.OK, excluded.status)
                state = excluded.jsonObject()

                val firstBatch = client.get("/api/agent/sessions/$sessionId/batch")
                assertEquals(HttpStatusCode.OK, firstBatch.status)
                assertEquals(1, firstBatch.jsonObject()["transactions"]!!.jsonArray.size)

                val afterFirstExport = client.get("/api/agent/sessions/$sessionId").jsonObject()
                assertEquals(
                    "ready_for_export",
                    afterFirstExport["receipt_state"]!!.jsonObject["status"]!!.jsonPrimitive.content,
                )
                assertFalse(afterFirstExport.containsKey("task_state"))

                val edited = client.put("/api/agent/sessions/$sessionId/transactions/1") {
                    jsonBody(
                        """
                            {
                              "revision":${afterFirstExport.revision()},
                              "included":true,
                              "direction":"expense",
                              "occurred_at":"2026-02-08T12:10:00",
                              "posted_at":null,
                              "amount_minor":125050,
                              "merchant":"ПОСЛЕ ЭКСПОРТА",
                              "description":"",
                              "category_id":"food.groceries",
                              "card_last4":"1234"
                            }
                        """.trimIndent(),
                    )
                }
                assertEquals(HttpStatusCode.OK, edited.status)
                assertEquals(
                    "ready_for_export",
                    edited.jsonObject()["receipt_state"]!!.jsonObject["status"]!!.jsonPrimitive.content,
                )
                val repeatedBatch = client.get("/api/agent/sessions/$sessionId/batch")
                assertEquals(HttpStatusCode.OK, repeatedBatch.status)
                assertEquals(
                    "ПОСЛЕ ЭКСПОРТА",
                    repeatedBatch.jsonObject()["transactions"]!!.jsonArray.single()
                        .jsonObject["merchant"]!!.jsonPrimitive.content,
                )

                val appended = client.post("/api/agent/sessions/$sessionId/messages") {
                    jsonBody("""{"revision":${edited.jsonObject().revision()},"text":"Добавь вторую выписку"}""")
                }
                assertEquals(HttpStatusCode.OK, appended.status)
                state = appended.jsonObject()
                assertEquals(
                    "has_errors",
                    state["receipt_state"]!!.jsonObject["status"]!!.jsonPrimitive.content,
                )
                assertEquals(4, state.transactions().size)

                val repaired = client.put("/api/agent/sessions/$sessionId/transactions/4") {
                    jsonBody(
                        """
                            {
                              "revision":${state.revision()},
                              "included":true,
                              "direction":"expense",
                              "occurred_at":"2026-02-09T10:00:00",
                              "posted_at":null,
                              "amount_minor":35000,
                              "merchant":"НОВЫЙ КАФЕ",
                              "description":"",
                              "category_id":"food.cafe",
                              "card_last4":null
                            }
                        """.trimIndent(),
                    )
                }
                assertEquals(HttpStatusCode.OK, repaired.status)
                assertEquals(
                    "ready_for_export",
                    repaired.jsonObject()["receipt_state"]!!.jsonObject["status"]!!.jsonPrimitive.content,
                )
                assertEquals(
                    3,
                    client.get("/api/agent/sessions/$sessionId/batch")
                        .jsonObject()["transactions"]!!.jsonArray.size,
                )
            }
        } finally {
            database.deleteIfExists()
        }
    }
    @Test
    fun exposesExplicitMemoryTraceAndConfirmedDecision() {
        val database = Files.createTempFile("agent-memory-", ".sqlite")
        val gateway = FakeAgentGateway(
            ArrayDeque(
                listOf(
                    FakeAgentGateway.READY_DRAFT_WITH_MEMORY_CANDIDATE_JSON,
                    FakeAgentGateway.FOLLOW_UP_NOOP_JSON,
                ),
            ),
        )
        try {
            testApplication {
                application { module(agentDependencies = fakeAgentDependencies(database.toString(), gateway)) }

                val preferences = client.put("/api/agent/preferences") {
                    jsonBody("""{"user_prompt":"Общие инструкции для всех сессий"}""")
                }
                assertEquals(HttpStatusCode.OK, preferences.status)

                val created = client.post("/api/agent/sessions").jsonObject()
                val sessionId = created.sessionId()
                assertEquals(
                    listOf("long_term"),
                    client.get("/api/agent/sessions/$sessionId/memory")
                        .jsonObject()["selected_layers"]!!.jsonArray
                        .map { it.jsonPrimitive.content },
                )
                val emptyProjection = client.get("/api/agent/sessions/$sessionId/memory/projection")
                assertEquals(HttpStatusCode.OK, emptyProjection.status)
                assertEquals(
                    listOf("long_term"),
                    emptyProjection.jsonObject()["selected_layers"]!!.jsonArray
                        .map { it.jsonPrimitive.content },
                )
                assertFalse(emptyProjection.bodyAsText().contains("\"content\""))

                val extracted = client.post("/api/agent/sessions/$sessionId/messages") {
                    jsonBody("""{"revision":0,"text":"SECRET-SYNTHETIC-STATEMENT"}""")
                }
                assertEquals(HttpStatusCode.OK, extracted.status)
                var state = extracted.jsonObject()
                val projection = client.get("/api/agent/sessions/$sessionId/memory/projection")
                assertEquals(HttpStatusCode.OK, projection.status)
                val projectionBody = projection.bodyAsText()
                assertTrue(projectionBody.contains("\"short_term\""))
                assertTrue(projectionBody.contains("\"working\""))
                assertTrue(projectionBody.contains("\"display_text\""))
                assertTrue(projectionBody.contains("\"category_display_name\":\"Еда / Продукты\""))
                assertFalse(projectionBody.contains("\"content\""))
                assertFalse(projectionBody.contains("\"category_id\""))
                assertTrue(gateway.requests.first().messages.first().content.contains("Общие инструкции для всех сессий"))
                val candidate = state["memory_candidates"]!!.jsonArray.single().jsonObject
                val candidateId = candidate["id"]!!.jsonPrimitive.content
                assertEquals("pending", candidate["status"]!!.jsonPrimitive.content)
                assertEquals(
                    state["messages"]!!.jsonArray.last().jsonObject["id"]!!.jsonPrimitive.content,
                    candidate["source_message_id"]!!.jsonPrimitive.content,
                )

                val accepted = client.post(
                    "/api/agent/sessions/$sessionId/memory-candidates/$candidateId/accept",
                ) {
                    jsonBody("""{"revision":${state.revision()}}""")
                }
                assertEquals(HttpStatusCode.OK, accepted.status)
                state = accepted.jsonObject()
                assertEquals(
                    "accepted",
                    state["memory_candidates"]!!.jsonArray.single().jsonObject["status"]!!.jsonPrimitive.content,
                )
                val preferencesAfterAccept = client.get("/api/agent/preferences").jsonObject()
                val decision = preferencesAfterAccept["confirmed_decisions"]!!.jsonArray.single().jsonObject
                val decisionId = decision["id"]!!.jsonPrimitive.content
                assertEquals(
                    candidate["text"]!!.jsonPrimitive.content,
                    decision["text"]!!.jsonPrimitive.content,
                )

                val followUp = client.post("/api/agent/sessions/$sessionId/messages") {
                    jsonBody("""{"revision":${state.revision()},"text":"Уточнение текущего draft"}""")
                }
                assertEquals(HttpStatusCode.OK, followUp.status)
                state = followUp.jsonObject()
                assertTrue(gateway.requests[1].messages.first().content.contains(candidate["text"]!!.jsonPrimitive.content))
                assertEquals(
                    listOf("short_term", "working", "long_term"),
                    state["memory_trace"]!!.jsonObject["selected_layers"]!!.jsonArray
                        .map { it.jsonPrimitive.content },
                )

                val edited = client.put("/api/agent/preferences/decisions/$decisionId") {
                    jsonBody("""{"text":"Изменённое решение"}""")
                }
                assertEquals(HttpStatusCode.OK, edited.status)
                assertEquals(
                    "Изменённое решение",
                    edited.jsonObject()["confirmed_decisions"]!!.jsonArray.single().jsonObject["text"]!!
                        .jsonPrimitive.content,
                )
                val deleted = client.delete("/api/agent/preferences/decisions/$decisionId")
                assertEquals(HttpStatusCode.OK, deleted.status)
                assertTrue(deleted.jsonObject()["confirmed_decisions"]!!.jsonArray.isEmpty())
                assertEquals(
                    HttpStatusCode.NotFound,
                    client.get("/api/agent/sessions/$sessionId/memory/report").status,
                )
            }
        } finally {
            database.deleteIfExists()
        }
    }


    @Test
    fun exposesGlobalCategoryCatalogAndArchiveEndpoints() {
        val database = Files.createTempFile("agent-categories-", ".sqlite")
        try {
            testApplication {
                application { module(agentDependencies = fakeAgentDependencies(database.toString())) }

                val providers = client.get("/api/agent/providers").jsonObject()
                val activeCategories = providers["categories"]!!.jsonArray
                assertTrue(activeCategories.any {
                    it.jsonObject["display_name"]!!.jsonPrimitive.content == "Продукты"
                })
                assertTrue(activeCategories.none {
                    it.jsonObject["display_name"]!!.jsonPrimitive.content == "Еда"
                })
                assertTrue(activeCategories.none {
                    it.jsonObject["display_name"]!!.jsonPrimitive.content == "Между своими счетами"
                })

                val initialCategories = client.get("/api/agent/categories").jsonObjectArray()
                val transfer = initialCategories.single {
                    it.jsonObject["display_name"]!!.jsonPrimitive.content == "Между своими счетами"
                }
                assertTrue(transfer.jsonObject["archived"]!!.jsonPrimitive.content.toBoolean())

                val created = client.post("/api/agent/categories") {
                    jsonBody(
                        """{"display_name":"Такси и каршеринг","type":"expense","hint":"городские поездки"}""",
                    )
                }
                assertEquals(HttpStatusCode.Created, created.status)
                val createdCategory = created.jsonObjectArray().single {
                    it.jsonObject["display_name"]!!.jsonPrimitive.content == "Такси и каршеринг"
                }
                val categoryId = createdCategory.jsonObject["id"]!!.jsonPrimitive.content

                val updated = client.put("/api/agent/categories/$categoryId") {
                    jsonBody("""{"display_name":"Наземный транспорт","hint":"городские поездки"}""")
                }
                assertEquals(HttpStatusCode.OK, updated.status)
                assertTrue(updated.jsonObjectArray().any {
                    it.jsonObject["display_name"]!!.jsonPrimitive.content == "Наземный транспорт"
                })

                val archived = client.delete("/api/agent/categories/$categoryId")
                assertEquals(HttpStatusCode.OK, archived.status)
                val archivedCategory = archived.jsonObjectArray().single {
                    it.jsonObject["id"]!!.jsonPrimitive.content == categoryId
                }
                assertTrue(archivedCategory.jsonObject["archived"]!!.jsonPrimitive.content.toBoolean())
            }
        } finally {
            database.deleteIfExists()
        }
    }

    @Test
    fun fakeProviderReturnsStableRussianRejectionForUnrelatedInput() {
        val database = Files.createTempFile("agent-not-applicable-", ".sqlite")
        val gateway = FakeAgentGateway(
            ArrayDeque(listOf(FakeAgentGateway.NOT_APPLICABLE_JSON)),
        )
        try {
            testApplication {
                application { module(agentDependencies = fakeAgentDependencies(database.toString(), gateway)) }
                val created = client.post("/api/agent/sessions").jsonObject()
                val response = client.post(
                    "/api/agent/sessions/${created.sessionId()}/messages",
                ) {
                    jsonBody("""{"revision":0,"text":"Расскажи прогноз погоды"}""")
                }

                assertEquals(HttpStatusCode.OK, response.status)
                val draft = response.jsonObject()["draft"]!!.jsonObject
                assertEquals("not_applicable", draft["status"]!!.jsonPrimitive.content)
                assertEquals(
                    "Ввод не содержит данных о финансовых операциях.",
                    draft["rejection_reason"]!!.jsonPrimitive.content,
                )
                assertEquals(1, gateway.requests.size)
            }
        } finally {
            database.deleteIfExists()
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.jsonBody(value: String) {
        contentType(ContentType.Application.Json)
        setBody(value)
    }

    private suspend fun io.ktor.client.statement.HttpResponse.jsonObject(): JsonObject =
        JSON.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun io.ktor.client.statement.HttpResponse.jsonObjectArray() =
        JSON.parseToJsonElement(bodyAsText()).jsonArray

    private fun JsonObject.sessionId(): String =
        this["session"]!!.jsonObject["id"]!!.jsonPrimitive.content

    private fun JsonObject.revision(): Long =
        this["session"]!!.jsonObject["revision"]!!.jsonPrimitive.content.toLong()

    private fun JsonObject.transactions() = this["draft"]!!.jsonObject["transactions"]!!.jsonArray

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
