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
                assertEquals(HttpStatusCode.Created, created.status)
                var state = created.jsonObject()
                val sessionId = state.sessionId()

                val extracted = client.post("/api/agent/sessions/$sessionId/messages") {
                    jsonBody("""{"revision":0,"text":"Списание 1250,50 ₽ и неизвестный платёж 99 ₽"}""")
                }
                assertEquals(HttpStatusCode.OK, extracted.status)
                state = extracted.jsonObject()
                assertEquals("ready", state["draft"]!!.jsonObject["status"]!!.jsonPrimitive.content)
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
