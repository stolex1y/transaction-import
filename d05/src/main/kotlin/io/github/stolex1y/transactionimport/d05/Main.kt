package io.github.stolex1y.transactionimport.d05

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.readText

suspend fun main(args: Array<String>) {
    val fixturePath = Path.of(args.firstOrNull() ?: "examples/demo-statement-hard.txt")
    val deepSeekApiKey = requireEnvironment("DEEPSEEK_API_KEY")
    val openRouterApiKey = requireEnvironment("OPENROUTER_API_KEY")
    val statement = fixturePath.readText()
    require(statement.isNotBlank()) { "Fixture must not be blank: $fixturePath" }

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        prettyPrint = true
    }
    HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 180_000
        }
        install(ContentNegotiation) {
            json(json)
        }
    }.use { httpClient ->
        val report = runD05Experiment(
            statement = statement,
            fixture = fixturePath.toString(),
            httpClient = httpClient,
            deepSeekApiKey = deepSeekApiKey,
            openRouterApiKey = openRouterApiKey,
        )
        println(json.encodeToString(D05Report.serializer(), report))
    }
}

private fun requireEnvironment(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: error("Required environment variable is missing: $name")
