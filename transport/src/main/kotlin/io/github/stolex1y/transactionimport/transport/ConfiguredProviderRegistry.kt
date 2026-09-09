package io.github.stolex1y.transactionimport.transport

import io.github.stolex1y.transactionimport.core.AgentConfig
import io.github.stolex1y.transactionimport.core.AgentRuntimeConfig
import io.github.stolex1y.transactionimport.core.AgentGatewayResolver
import io.github.stolex1y.transactionimport.core.ChatCompletionGateway
import io.github.stolex1y.transactionimport.core.ChatCompletionRequest
import io.github.stolex1y.transactionimport.core.ChatCompletionResponse
import io.github.stolex1y.transactionimport.core.ProviderCatalog
import io.github.stolex1y.transactionimport.core.ProviderUnavailableException
import io.github.stolex1y.transactionimport.core.ResolvedAgentConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path

class ConfiguredProviderRegistry(
    private val httpClient: HttpClient,
    val catalog: ProviderCatalog,
    private val credentialLookup: (String) -> String? = System::getenv,
) : AgentGatewayResolver {
    init {
        catalog.validated()
    }

    fun isAvailable(providerId: String): Boolean {
        val provider = catalog.providers.singleOrNull { it.id == providerId } ?: return false
        return !credentialLookup(provider.credentialEnv).isNullOrBlank()
    }

    fun availableProviderIds(): Set<String> =
        catalog.providers.asSequence().filter { isAvailable(it.id) }.map { it.id }.toSet()

    override fun resolve(config: AgentConfig): ChatCompletionGateway {
        val resolved = catalog.resolve(config)
        val apiKey = credentialLookup(resolved.provider.credentialEnv)
            ?.takeIf { it.isNotBlank() }
            ?: throw ProviderUnavailableException(
                "Провайдер ${resolved.provider.displayName} недоступен: " +
                    "не задана переменная ${resolved.provider.credentialEnv}.",
            )
        return ConfiguredChatCompletionGateway(httpClient, apiKey, resolved)
    }
}

fun decodeProviderCatalog(text: String): ProviderCatalog =
    providerJson.decodeFromString<ProviderCatalog>(text).validated()

fun loadProviderCatalog(path: Path): ProviderCatalog {
    require(Files.isRegularFile(path)) { "Файл конфигурации провайдеров не найден: $path" }
    return decodeProviderCatalog(Files.readString(path))
}

fun decodeAgentRuntimeConfig(text: String, catalog: ProviderCatalog): AgentRuntimeConfig =
    providerJson.decodeFromString<AgentRuntimeConfig>(text).validated(catalog)

fun loadAgentRuntimeConfig(path: Path, catalog: ProviderCatalog): AgentRuntimeConfig {
    require(Files.isRegularFile(path)) { "Файл конфигурации агента не найден: $path" }
    return decodeAgentRuntimeConfig(Files.readString(path), catalog)
}

private class ConfiguredChatCompletionGateway(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val resolved: ResolvedAgentConfig,
) : ChatCompletionGateway {
    private val endpoint = resolved.provider.baseUrl.trimEnd('/') +
        resolved.provider.chatCompletionsPath

    override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
        require(request.model == resolved.model.id) {
            "Gateway ${resolved.provider.id} настроен для модели ${resolved.model.id}, " +
                "получена ${request.model}."
        }
        val bodyFields = providerJson.encodeToString(request)
            .let(providerJson::parseToJsonElement)
            .jsonObject
            .toMutableMap()
        bodyFields.remove("thinking")
        bodyFields.remove("reasoning_effort")
        bodyFields.putAll(resolved.reasoningMode.requestFields)

        val response = httpClient.post(endpoint) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(JsonObject(bodyFields))
        }
        val raw = response.body<JsonObject>()
        require(response.status.value in 200..299) {
            "Провайдер ${resolved.provider.id} вернул HTTP ${response.status.value}: " +
                (raw["error"] ?: raw)
        }
        return providerJson.decodeFromJsonElement<ChatCompletionResponse>(raw).copy(
            rawUsage = raw["usage"] as? JsonObject,
        )
    }
}

private val providerJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
