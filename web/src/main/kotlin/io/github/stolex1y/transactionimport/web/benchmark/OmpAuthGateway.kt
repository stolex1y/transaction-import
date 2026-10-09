package io.github.stolex1y.transactionimport.web.benchmark

import io.github.stolex1y.transactionimport.core.ChatCompletionGateway
import io.github.stolex1y.transactionimport.core.ChatCompletionRequest
import io.github.stolex1y.transactionimport.core.ChatCompletionResponse
import io.github.stolex1y.transactionimport.web.PreCompletionRequestAuthorizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal data class OmpGatewayMetadata(
    val modelSelector: String,
    val gatewayVersion: String,
    val fallbackOverlaySha256: String,
    val requestCount: Int,
    val requestFingerprints: List<String>,
    val responseModels: List<String>,
)

internal fun interface CodexRequestApproval {
    suspend fun approve(
        prettyProviderBody: String,
        requestFingerprint: String,
        routingSummary: String,
    ): Boolean
}

internal interface OmpJsonLineConnection : Closeable {
    suspend fun readReadyLine(): String?
    suspend fun exchange(requestLine: String): String?
}

internal class OmpStdioChatCompletionGateway(
    private val modelSelector: String,
    private val connection: OmpJsonLineConnection,
    private val approveFirstRequest: CodexRequestApproval,
    private val routingSummary: String,
) : ChatCompletionGateway, PreCompletionRequestAuthorizer, Closeable {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }
    private val previewJson = Json { prettyPrint = true; explicitNulls = false }
    private val mutex = Mutex()
    private val requestFingerprints = mutableListOf<String>()
    private val responseModels = mutableListOf<String>()
    private var nextRequestId = 0L
    private var previewApproved = false
    private var authorizedRequest: AuthorizedRequest? = null
    private var gatewayVersion = ""
    private data class AuthorizedRequest(
        val request: ChatCompletionRequest,
        val body: JsonObject,
        val fingerprint: String,
    )

    suspend fun initialize(): String {
        val line = withTimeout(STARTUP_TIMEOUT_MS) {
            connection.readReadyLine() ?: throw IllegalStateException("OMP stdio gateway closed during startup.")
        }
        val ready = parseObject(line, "OMP stdio readiness response")
        require(ready["ready"]?.jsonPrimitive?.booleanOrNull == true) {
            "OMP stdio gateway did not confirm readiness."
        }
        gatewayVersion = ready["version"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.matches(GATEWAY_VERSION_PATTERN) }
            ?: throw IllegalStateException("OMP stdio gateway omitted a valid version.")
        return gatewayVersion
    }

    override suspend fun authorizeBeforeCompletion(request: ChatCompletionRequest) = mutex.withLock {
        check(gatewayVersion.isNotBlank()) { "OMP stdio gateway was not initialized." }
        check(authorizedRequest == null) { "OMP gateway already has an authorized request." }
        val body = OmpCodexRequestProjection.body(request, modelSelector, json)
        val bodyText = json.encodeToString(JsonObject.serializer(), body)
        val fingerprint = sha256(bodyText)
        if (!previewApproved) {
            val pretty = previewJson.encodeToString(JsonObject.serializer(), body)
            if (!approveFirstRequest.approve(pretty, fingerprint, routingSummary)) {
                throw ImportBenchmarkCancelledException()
            }
            previewApproved = true
        }
        authorizedRequest = AuthorizedRequest(request, body, fingerprint)
    }

    override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse = mutex.withLock {
        check(gatewayVersion.isNotBlank()) { "OMP stdio gateway was not initialized." }
        val authorized = authorizedRequest
        if (authorized != null) {
            check(authorized.request === request) {
                "OMP completion request changed after its authorization preview."
            }
            authorizedRequest = null
        } else {
            check(previewApproved) {
                "OMP completion request was not authorized before the model-call deadline."
            }
        }
        val body = authorized?.body ?: OmpCodexRequestProjection.body(request, modelSelector, json)
        val fingerprint = authorized?.fingerprint
            ?: sha256(json.encodeToString(JsonObject.serializer(), body))
        val requestId = ++nextRequestId
        val line = buildJsonObject {
            put("id", requestId)
            put("path", CHAT_COMPLETIONS_PATH)
            put("body", body)
        }.toString()
        val responseLine = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            connection.exchange(line)
        } ?: throw IllegalStateException("OMP stdio gateway timed out or closed; raw request and response omitted.")
        val envelope = parseObject(responseLine, "OMP stdio completion response")
        val responseId = envelope["id"]?.jsonPrimitive?.contentOrNull
        require(responseId == requestId.toString()) { "OMP stdio gateway returned a mismatched response id." }
        val status = envelope["status"]?.jsonPrimitive?.intOrNull
            ?: throw IllegalStateException("OMP stdio gateway omitted its response status.")
        if (status !in 200..299) throw OmpGatewayHttpStatusException(status)
        val responseBody = envelope["body"] as? JsonObject
            ?: throw IllegalStateException("OMP stdio gateway returned a non-JSON completion body.")
        val actualModel = responseBody["model"]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("OMP stdio gateway response omitted its model id.")
        check(responseModelMatches(modelSelector, actualModel)) {
            "OMP stdio gateway response named an unexpected model; benchmark stopped."
        }
        responseModels += actualModel
        requestFingerprints += fingerprint
        try {
            json.decodeFromJsonElement<ChatCompletionResponse>(responseBody).copy(
                rawUsage = responseBody["usage"] as? JsonObject,
            )
        } catch (_: SerializationException) {
            throw IllegalStateException("OMP stdio gateway returned an invalid completion shape; raw response omitted.")
        }
    }

    fun metadata(fallbackOverlaySha256: String): OmpGatewayMetadata = OmpGatewayMetadata(
        modelSelector = modelSelector,
        gatewayVersion = gatewayVersion,
        fallbackOverlaySha256 = fallbackOverlaySha256,
        requestCount = requestFingerprints.size,
        requestFingerprints = requestFingerprints.toList(),
        responseModels = responseModels.toList(),
    )

    override fun close() = connection.close()

    private fun parseObject(line: String, description: String): JsonObject = try {
        json.parseToJsonElement(line).jsonObject
    } catch (_: SerializationException) {
        throw IllegalStateException("$description was not valid JSON.")
    } catch (_: IllegalArgumentException) {
        throw IllegalStateException("$description was not a JSON object.")
    }

    private companion object {
        const val CHAT_COMPLETIONS_PATH = "/v1/chat/completions"
        const val STARTUP_TIMEOUT_MS = 30_000L
        const val REQUEST_TIMEOUT_MS = 300_000L
        val GATEWAY_VERSION_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}")
    }
}

internal object OmpCodexRequestProjection {
    fun body(
        request: ChatCompletionRequest,
        modelSelector: String,
        json: Json = Json { encodeDefaults = true; explicitNulls = false },
    ): JsonObject {
        require(modelSelector == APPROVED_MODEL_SELECTOR) { "Benchmark model selector changed after approval." }
        require(request.messages.isNotEmpty()) { "Application request has no messages." }
        val fields = json.encodeToJsonElement(request).jsonObject.toMutableMap()
        fields["model"] = JsonPrimitive(modelSelector)
        fields.remove("thinking")
        fields.remove("reasoning_effort")
        return JsonObject(fields)
    }

    private const val APPROVED_MODEL_SELECTOR = "openai-codex/gpt-6-luna:min"
}

internal class OmpAuthGatewaySession private constructor(
    private val overlayPath: Path,
    private val gateway: OmpStdioChatCompletionGateway,
    private val overlaySha256: String,
) : Closeable {
    fun gateway(): OmpStdioChatCompletionGateway = gateway

    fun metadata(): OmpGatewayMetadata = gateway.metadata(overlaySha256)

    override fun close() {
        try {
            gateway.close()
        } finally {
            Files.deleteIfExists(overlayPath)
        }
    }

    companion object {
        suspend fun open(
            modelSelector: String,
            approveFirstRequest: CodexRequestApproval,
            ompCommand: List<String> = listOf("omp"),
            workingDirectory: Path = Path.of("").toAbsolutePath(),
        ): OmpAuthGatewaySession {
            require(modelSelector == OMP_MODEL_SELECTOR) { "Benchmark model selector changed after approval." }
            val overlayPath = Files.createTempFile(workingDirectory, ".import-benchmark-omp-", ".yml")
            var stage = "writing temporary fallback config"
            try {
                restrictPermissions(overlayPath)
                val overlayText = OmpFallbackPolicy.overlay(modelSelector)
                Files.writeString(overlayPath, overlayText, StandardCharsets.UTF_8)
                val overlaySha = sha256(overlayText)
                val inheritedEnvironment = System.getenv()
                val routeSummary = routingSummary(inheritedEnvironment)
                val childEnvironment = processEnvironment(inheritedEnvironment, overlayPath)
                stage = "reading retry.modelFallback"
                val modelFallback = queryConfig(ompCommand, workingDirectory, childEnvironment, "retry.modelFallback")
                stage = "reading retry.usageAwareFallback"
                val usageAwareFallback = queryConfig(
                    ompCommand,
                    workingDirectory,
                    childEnvironment,
                    "retry.usageAwareFallback",
                )
                stage = "reading retry.fallbackChains"
                val fallbackChains = queryConfig(
                    ompCommand,
                    workingDirectory,
                    childEnvironment,
                    "retry.fallbackChains",
                )
                stage = "validating effective fallback settings"
                OmpFallbackPolicy.verifyEffectiveSettings(
                    modelSelector = modelSelector,
                    modelFallback = modelFallback,
                    usageAwareFallback = usageAwareFallback,
                    fallbackChains = fallbackChains,
                )
                stage = "starting local OMP stdio gateway"
                val process = ProcessBuilder(ompCommand + listOf("auth-gateway", "stdio"))
                    .directory(workingDirectory.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .apply { environment().putAll(childEnvironment) }
                    .start()
                val connection = ProcessOmpJsonLineConnection(process)
                val gateway = OmpStdioChatCompletionGateway(
                    modelSelector,
                    connection,
                    approveFirstRequest,
                    routeSummary,
                )
                try {
                    stage = "waiting for OMP gateway readiness"
                    gateway.initialize()
                } catch (error: Throwable) {
                    gateway.close()
                    throw error
                }
                return OmpAuthGatewaySession(overlayPath, gateway, overlaySha)
            } catch (error: Throwable) {
                Files.deleteIfExists(overlayPath)
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (error is OmpGatewayPreflightException) throw error
                throw OmpGatewayPreflightException(stage)
            }
        }

        private suspend fun queryConfig(
            command: List<String>,
            workingDirectory: Path,
            childEnvironment: Map<String, String>,
            key: String,
        ): JsonElement {
            val process = try {
                ProcessBuilder(command + listOf("config", "get", key, "--json"))
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true)
                    .apply { environment().putAll(childEnvironment) }
                    .start()
            } catch (_: Exception) {
                throw OmpGatewayPreflightException("could not start omp config get $key")
            }
            try {
                val bytes = runInterruptible(Dispatchers.IO) {
                    if (!process.waitFor(CONFIG_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        null
                    } else {
                        process.inputStream.use { it.readNBytes(MAX_CONFIG_OUTPUT_BYTES + 1) }
                    }
                } ?: throw OmpGatewayPreflightException("omp config get $key timed out")
                if (bytes.size > MAX_CONFIG_OUTPUT_BYTES) {
                    throw OmpGatewayPreflightException("omp config get $key returned oversized output")
                }
                if (process.exitValue() != 0) {
                    throw OmpGatewayPreflightException(
                        "omp config get $key exited ${process.exitValue()}",
                    )
                }
                val response = try {
                    Json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8)).jsonObject
                } catch (_: Exception) {
                    throw OmpGatewayPreflightException("omp config get $key returned invalid JSON")
                }
                return response["value"] ?: response
            } finally {
                if (process.isAlive) process.destroyForcibly()
                runCatching { process.outputStream.close() }
                runCatching { process.inputStream.close() }
            }
        }

        internal fun processEnvironment(
            inheritedEnvironment: Map<String, String>,
            overlayPath: Path,
        ): Map<String, String> {
            val environment = inheritedEnvironment.toMutableMap()
            // Exclude ambient OMP project overlays; preserve profile/auth routing inherited by OMP.
            environment[PI_CONFIG_FILES] = overlayPath.toAbsolutePath().toString()
            return environment
        }
        internal fun routingSummary(inheritedEnvironment: Map<String, String>): String {
            val profile = inheritedEnvironment["OMP_PROFILE"]
            require(profile == null || profile.matches(PROFILE_PATTERN)) {
                "OMP profile name is not safe to display; benchmark stopped."
            }
            val profileRoute = profile ?: "unset (OMP default profile)"
            val overlayRoute = if (inheritedEnvironment.containsKey(PI_CONFIG_FILES)) {
                "inherited PI_CONFIG_FILES is present but ignored"
            } else {
                "inherited PI_CONFIG_FILES is not set"
            }
            return "OMP routing: profile=$profileRoute; config=benchmark fallback-only overlay; " +
                "$overlayRoute; other inherited environment is preserved. Account identity is not verified."
        }

        private fun restrictPermissions(path: Path) {
            runCatching {
                Files.setPosixFilePermissions(
                    path,
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
            }
        }

        private const val PI_CONFIG_FILES = "PI_CONFIG_FILES"
        private const val OMP_MODEL_SELECTOR = "openai-codex/gpt-6-luna:min"
        private const val MAX_CONFIG_OUTPUT_BYTES = 64 * 1024
        private const val CONFIG_TIMEOUT_SECONDS = 15L
        private val PROFILE_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    }
}

internal class OmpGatewayPreflightException(
    val stage: String,
) : IllegalStateException("Local OMP benchmark preflight failed.")

internal class OmpGatewayHttpStatusException(
    val status: Int,
) : IllegalStateException(
    "OMP stdio gateway returned HTTP $status; raw provider response omitted.",
)

internal object OmpFallbackPolicy {
    fun overlay(modelSelector: String): String {
        require(modelSelector == MODEL_SELECTOR) { "Benchmark model selector changed after approval." }
        val providerQualifiedModel = modelSelector.substringBeforeLast(':')
        val provider = providerQualifiedModel.substringBefore('/')
        return """
            retry:
              modelFallback: false
              usageAwareFallback: false
              fallbackChains:
                "$modelSelector": []
                "$providerQualifiedModel": []
                "$provider/*": []
        """.trimIndent() + "\n"
    }

    fun verifyEffectiveSettings(
        modelSelector: String,
        modelFallback: JsonElement,
        usageAwareFallback: JsonElement,
        fallbackChains: JsonElement,
    ) {
        if ((modelFallback as? JsonPrimitive)?.booleanOrNull != false) {
            throw OmpGatewayPreflightException("effective retry.modelFallback is not false")
        }
        if ((usageAwareFallback as? JsonPrimitive)?.booleanOrNull != false) {
            throw OmpGatewayPreflightException("effective retry.usageAwareFallback is not false")
        }
        val chains = fallbackChains as? JsonObject
            ?: throw OmpGatewayPreflightException("effective retry.fallbackChains is not an object")
        val providerQualifiedModel = modelSelector.substringBeforeLast(':')
        val provider = providerQualifiedModel.substringBefore('/')
        val requiredEmptyChains = setOf(modelSelector, providerQualifiedModel, "$provider/*")
        if (!requiredEmptyChains.all { key -> (chains[key] as? JsonArray)?.isEmpty() == true }) {
            throw OmpGatewayPreflightException("required retry.fallbackChains entries are not empty")
        }
    }

    private const val MODEL_SELECTOR = "openai-codex/gpt-6-luna:min"
}

internal class ProcessOmpJsonLineConnection(
    private val process: Process,
    private val startupTimeoutMs: Long = DEFAULT_STARTUP_TIMEOUT_MS,
    private val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
) : OmpJsonLineConnection {
    private val reader = InputStreamReader(process.inputStream, StandardCharsets.UTF_8)
    private val writer = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "omp-benchmark-stdio").apply { isDaemon = true }
    }

    override suspend fun readReadyLine(): String? =
        runBounded("startup", startupTimeoutMs) { readBoundedLine() }

    override suspend fun exchange(requestLine: String): String? {
        require(requestLine.length <= MAX_JSONL_LINE_CHARS) {
            "OMP stdio request exceeds the safe line limit."
        }
        require(process.isAlive && !closed.get()) { "OMP stdio gateway exited before a request." }
        return runBounded("request", requestTimeoutMs) {
            writer.write(requestLine)
            writer.newLine()
            writer.flush()
            readBoundedLine()
        }
    }

    private suspend fun <T> runBounded(
        operation: String,
        timeoutMs: Long,
        action: () -> T,
    ): T {
        require(!closed.get()) { "OMP stdio gateway is closed." }
        val future = executor.submit<T> { action() }
        try {
            return runInterruptible(Dispatchers.IO) {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            }
        } catch (error: TimeoutException) {
            future.cancel(true)
            abort()
            throw IllegalStateException("OMP stdio $operation timed out; raw request and response omitted.")
        } catch (error: CancellationException) {
            future.cancel(true)
            abort()
            throw error
        } catch (error: InterruptedException) {
            future.cancel(true)
            abort()
            Thread.currentThread().interrupt()
            throw CancellationException("OMP stdio operation was cancelled.")
        } catch (error: ExecutionException) {
            abort()
            var cause = error.cause
            while (cause is ExecutionException && cause.cause != null) cause = cause.cause
            if (cause is IllegalStateException) throw cause
            throw IllegalStateException("OMP stdio $operation failed; raw request and response omitted.")
        }
    }

    private fun readBoundedLine(): String? {
        val line = StringBuilder()
        while (true) {
            val value = reader.read()
            if (value < 0) return if (line.isEmpty()) null else line.toString()
            if (value == '\n'.code) {
                if (line.isNotEmpty() && line.last() == '\r') line.setLength(line.length - 1)
                return line.toString()
            }
            if (line.length == MAX_JSONL_LINE_CHARS) {
                throw IllegalStateException("OMP stdio response line exceeds the safe limit.")
            }
            line.append(value.toChar())
        }
    }

    private fun abort() {
        if (!closed.compareAndSet(false, true)) return
        process.destroyForcibly()
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        executor.shutdownNow()
    }

    override fun close() {
        abort()
        runCatching { process.waitFor(PROCESS_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
    }

    private companion object {
        const val DEFAULT_STARTUP_TIMEOUT_MS = 30_000L
        const val DEFAULT_REQUEST_TIMEOUT_MS = 300_000L
        const val MAX_JSONL_LINE_CHARS = 1_048_576
        const val PROCESS_CLOSE_TIMEOUT_SECONDS = 5L
    }
}

private fun responseModelMatches(selector: String, responseModel: String): Boolean =
    selector == "openai-codex/gpt-6-luna:min" && responseModel == selector

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

