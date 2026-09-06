package io.github.stolex1y.transactionimport.web

import io.github.stolex1y.transactionimport.core.AppliedResponseControls
import io.github.stolex1y.transactionimport.core.DEFAULT_MODEL
import io.github.stolex1y.transactionimport.core.ExtractionOptions
import io.github.stolex1y.transactionimport.core.ReasoningLevel
import io.github.stolex1y.transactionimport.core.ResponseMode
import io.github.stolex1y.transactionimport.core.StructuredValidation
import io.github.stolex1y.transactionimport.core.TransactionImportService
import io.github.stolex1y.transactionimport.core.Usage
import io.ktor.serialization.kotlinx.json.json
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
data class ExtractRequest(
    val statement: String,
    val model: String,
    val reasoning: String,
    val mode: String,
)

@Serializable
data class ExtractResponse(
    val text: String,
    @SerialName("text_length") val textLength: Int,
    @SerialName("finish_reason") val finishReason: String?,
    val usage: Usage?,
    @SerialName("reasoning_content_length") val reasoningContentLength: Int?,
    val model: String,
    val reasoning: String,
    val mode: String,
    val controls: AppliedResponseControls,
    val validation: StructuredValidation?,
    @SerialName("processing_time_ms") val processingTimeMs: Long,
)

@Serializable
data class ErrorResponse(
    val error: String,
)

private val supportedModels = setOf(
    DEFAULT_MODEL,
    "deepseek-v4-pro",
)

private val webJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

fun Application.module(
    service: TransactionImportService,
    experimentService: ExperimentService? = null,
) {
    val jobManager = experimentService?.let(::ExperimentJobManager)
    jobManager?.let { manager ->
        environment.monitor.subscribe(ApplicationStopped) {
            manager.close()
        }
    }

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
        get("/") {
            call.respondText(
                text = loadResource("web/index.html"),
                contentType = ContentType.Text.Html,
            )
        }
        get("/experiments") {
            call.respondText(
                text = loadResource("web/experiments.html"),
                contentType = ContentType.Text.Html,
            )
        }
        get("/experiments/{page}") {
            val page = call.parameters["page"]
                ?.takeIf { it in experimentPages }
                ?: throw IllegalArgumentException("Раздел эксперимента не найден.")
            call.respondText(
                text = loadResource("web/$page.html"),
                contentType = ContentType.Text.Html,
            )
        }
        staticResources("/assets", "web")

        post("/api/extract") {
            val request = call.receive<ExtractRequest>()
            val model = request.model.trim()
            require(model in supportedModels) {
                "Модель не разрешена: $model"
            }
            val reasoning = parseReasoning(request.reasoning)
            val responseMode = parseResponseMode(request.mode)
            val startedAt = System.nanoTime()
            val result = service.extract(
                statement = request.statement,
                options = ExtractionOptions(
                    model = model,
                    reasoning = reasoning,
                    responseMode = responseMode,
                ),
            )
            val processingTimeMs = (System.nanoTime() - startedAt) / 1_000_000
            call.respond(
                ExtractResponse(
                    text = result.text,
                    textLength = result.text.length,
                    finishReason = result.finishReason,
                    usage = result.usage,
                    reasoningContentLength = result.reasoningContentLength,
                    model = model,
                    reasoning = request.reasoning.trim().lowercase(),
                    mode = responseMode.apiValue(),
                    controls = result.controls,
                    validation = result.validation,
                    processingTimeMs = processingTimeMs,
                ),
            )
        }

        post("/api/experiments/{kind}") {
            val manager = jobManager
                ?: run {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ErrorResponse("Экспериментальный runner не настроен."),
                    )
                    return@post
                }
            val kind = call.parameters["kind"]
                ?.takeIf { it in experimentKinds }
                ?: throw IllegalArgumentException("Эксперимент не разрешён.")
            val request = call.receive<ExperimentRequest>()
            val accepted = manager.submit(kind, request)
            call.respond(HttpStatusCode.Accepted, ExperimentAccepted(accepted.id))
        }

        get("/api/experiments/jobs/{id}") {
            val id = call.parameters["id"].orEmpty()
            val response = jobManager?.get(id)
            if (response == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse("Экспериментальная сессия не найдена."),
                )
            } else {
                call.respond(response)
            }
        }
    }
}

private val experimentPages = setOf("d02", "d03", "d04", "d05")
private val experimentKinds = setOf("d03", "d04", "d05")

internal fun parseReasoning(value: String): ReasoningLevel =
    when (value.trim().lowercase()) {
        "disabled" -> ReasoningLevel.DISABLED
        "low" -> ReasoningLevel.LOW
        "high" -> ReasoningLevel.HIGH
        "max" -> ReasoningLevel.MAX
        else -> throw IllegalArgumentException("Неизвестный уровень reasoning: $value")
    }

internal fun parseResponseMode(value: String): ResponseMode =
    when (value.trim().lowercase()) {
        "unrestricted" -> ResponseMode.UNRESTRICTED
        "controlled_json" -> ResponseMode.CONTROLLED_JSON
        else -> throw IllegalArgumentException("Неизвестный режим ответа: $value")
    }

private fun ResponseMode.apiValue(): String =
    when (this) {
        ResponseMode.UNRESTRICTED -> "unrestricted"
        ResponseMode.CONTROLLED_JSON -> "controlled_json"
    }

private fun loadResource(path: String): String {
    val resource = requireNotNull(Thread.currentThread().contextClassLoader.getResource(path)) {
        "Ресурс не найден: $path"
    }
    return resource.openStream().bufferedReader().use { it.readText() }
}
