package io.github.stolex1y.transactionimport.web

import io.github.stolex1y.transactionimport.core.TransactionImportService
import io.github.stolex1y.transactionimport.transport.DeepSeekGateway
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.serialization.json.Json
import kotlin.system.exitProcess

private const val API_KEY_ENV = "DEEPSEEK_API_KEY"
private const val DEFAULT_PORT = 8080

fun main() {
    val apiKey = System.getenv(API_KEY_ENV)?.takeIf { it.isNotBlank() }
    if (apiKey == null) {
        System.err.println("Не задана обязательная переменная окружения: $API_KEY_ENV")
        exitProcess(2)
    }

    val port = System.getenv("PORT")
        ?.toIntOrNull()
        ?.takeIf { it in 1..65_535 }
        ?: DEFAULT_PORT
    val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                    explicitNulls = false
                },
            )
        }
    }

    try {
        val service = TransactionImportService(DeepSeekGateway(httpClient, apiKey))
        embeddedServer(Netty, host = "127.0.0.1", port = port) {
            module(service)
        }.start(wait = true)
    } finally {
        httpClient.close()
    }
}
