package io.github.stolex1y.transactionimport.d04

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

private const val API_KEY_ENV = "DEEPSEEK_API_KEY"
private const val DEFAULT_FIXTURE = "examples/demo-statement-hard.txt"

fun main(args: Array<String>) {
    val apiKey = System.getenv(API_KEY_ENV)?.takeIf(String::isNotBlank)
    if (apiKey == null) {
        System.err.println("Missing required environment variable: $API_KEY_ENV")
        exitProcess(2)
    }

    val fixturePath = args.firstOrNull() ?: DEFAULT_FIXTURE
    val statement = try {
        Files.readString(Path.of(fixturePath))
    } catch (exception: Exception) {
        System.err.println("Cannot read fixture '$fixturePath': ${exception.message}")
        exitProcess(2)
    }

    val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                },
            )
        }
    }

    try {
        val report = runBlocking {
            runD04Experiment(
                statement = statement,
                fixture = fixturePath,
                httpClient = httpClient,
                apiKey = apiKey,
            )
        }
        print(reportJson().encodeToString(report))
        print("\n")
    } catch (exception: Exception) {
        System.err.println("D04 experiment failed: ${exception.message ?: exception::class.simpleName}")
        exitProcess(1)
    } finally {
        httpClient.close()
    }
}
