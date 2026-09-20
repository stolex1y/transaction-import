package io.github.stolex1y.transactionimport.web

import io.github.stolex1y.transactionimport.core.AgentResponseException
import io.github.stolex1y.transactionimport.core.InvariantViolationException
import io.github.stolex1y.transactionimport.core.ProviderUnavailableException
import io.github.stolex1y.transactionimport.core.RevisionConflictException
import io.github.stolex1y.transactionimport.core.SessionNotFoundException
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
data class ErrorResponse(
    val error: String,
)

private val webJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

fun Application.module(agentDependencies: AgentWebDependencies? = null) {
    install(ContentNegotiation) {
        json(webJson)
    }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(cause.message ?: "Некорректный запрос."),
            )
        }
        exception<SessionNotFoundException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, ErrorResponse(cause.message ?: "Сессия не найдена."))
        }
        exception<RevisionConflictException> { call, cause ->
            call.respond(HttpStatusCode.Conflict, ErrorResponse(cause.message ?: "Конфликт ревизий."))
        }
        exception<ProviderUnavailableException> { call, cause ->
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse(cause.message ?: "Провайдер недоступен."),
            )
        }
        exception<InvariantViolationException> { call, cause ->
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse(cause.message ?: "Операция нарушает инвариант задачи."),
            )
        }
        exception<AgentResponseException> { call, cause ->
            call.respond(
                HttpStatusCode.BadGateway,
                ErrorResponse(cause.message ?: "Ответ агента не прошёл локальную проверку."),
            )
        }
        exception<SerializationException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Тело запроса должно быть корректным JSON."),
            )
        }
        exception<Throwable> { call, _ ->
            call.respond(
                HttpStatusCode.BadGateway,
                ErrorResponse("Не удалось получить ответ провайдера."),
            )
        }
    }

    routing {
        get("/agent") {
            call.respondText(
                text = loadResource("web/agent.html"),
                contentType = ContentType.Text.Html,
            )
        }
        staticResources("/assets", "web")
        agentRoutes(agentDependencies)
    }
}

private fun loadResource(path: String): String {
    val resource = requireNotNull(Thread.currentThread().contextClassLoader.getResource(path)) {
        "Ресурс не найден: $path"
    }
    return resource.openStream().bufferedReader().use { it.readText() }
}
