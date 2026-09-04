package io.github.stolex1y.transactionimport.cli

import io.github.stolex1y.transactionimport.core.TransactionImportService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.PrintWriter
import kotlin.system.exitProcess

internal const val API_KEY_ENV = "DEEPSEEK_API_KEY"

internal fun readApiKey(environment: Map<String, String> = System.getenv()): String? =
    environment[API_KEY_ENV]?.takeIf { it.isNotBlank() }

fun main(args: Array<String>) {
    val output = PrintWriter(System.out, true)
    val error = PrintWriter(System.err, true)

    if (args.contentEquals(arrayOf("--help")) || args.contentEquals(arrayOf("-h"))) {
        output.print(usageText())
        return
    }
    if (!args.contentEquals(arrayOf("parse"))) {
        error.print("Usage: transaction-import parse < statement.txt\n")
        exitProcess(2)
    }

    val apiKey = readApiKey()
    if (apiKey == null) {
        error.println("Missing required environment variable: $API_KEY_ENV")
        exitProcess(2)
    }

    val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                },
            )
        }
    }

    val exitCode = try {
        val service = TransactionImportService(DeepSeekGateway(httpClient, apiKey))
        runBlocking {
            CliRunner(service::extract).run(
                args = args.toList(),
                input = System.`in`.bufferedReader(),
                output = output,
                error = error,
            )
        }
    } catch (exception: Exception) {
        val message = exception.message ?: exception::class.simpleName.orEmpty()
        error.println("Request failed: ${message.replace(apiKey, "[REDACTED]")}")
        1
    } finally {
        output.flush()
        error.flush()
        httpClient.close()
    }

    exitProcess(exitCode)
}
