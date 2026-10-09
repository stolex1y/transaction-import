package io.github.stolex1y.transactionimport.web.benchmark

import io.github.stolex1y.transactionimport.core.ChatCompletionRequest
import io.github.stolex1y.transactionimport.core.ChatFunctionDefinition
import io.github.stolex1y.transactionimport.core.ChatToolDefinition
import io.github.stolex1y.transactionimport.core.RequestMessage
import io.github.stolex1y.transactionimport.core.ResponseFormat
import io.github.stolex1y.transactionimport.core.ThinkingOptions
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Comparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Job
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OmpAuthGatewayTest {
    @Test
    fun providerRequestContainsOnlyProjectedApplicationMessagesAndTools() = runBlocking {
        val connection = RecordingConnection()
        val request = applicationRequest()
        var previewCount = 0
        val gateway = OmpStdioChatCompletionGateway(
            modelSelector = MODEL_SELECTOR,
            reasoningEffort = REASONING_EFFORT,
            connection = connection,
            approveFirstRequest = CodexRequestApproval { preview, fingerprint, routing ->
                previewCount += 1
                assertTrue(preview.contains("app-system-instruction"))
                assertTrue(preview.contains("app-tool"))
                assertFalse(preview.contains("OMP_AGENT_PROMPT"))
                assertEquals(64, fingerprint.length)
                assertEquals("synthetic route", routing)
                assertTrue(connection.requests.isEmpty())
                true
            },
            routingSummary = "synthetic route",
        )
        gateway.initialize()
        gateway.authorizeBeforeCompletion(request)

        val response = gateway.complete(request)

        assertEquals("synthetic response", response.choices.single().message.content)
        assertEquals(1, previewCount)
        assertEquals(1, connection.requests.size)
        val envelope = Json.parseToJsonElement(connection.requests.single()).jsonObject
        assertEquals("/v1/chat/completions", envelope.getValue("path").jsonPrimitive.content)
        val body = envelope.getValue("body").jsonObject
        val appEncoded = Json { encodeDefaults = true; explicitNulls = false }
            .encodeToJsonElement(request)
            .jsonObject
        assertEquals(MODEL_SELECTOR, body.getValue("model").jsonPrimitive.content)
        assertEquals(appEncoded.getValue("messages"), body.getValue("messages"))
        assertEquals(appEncoded.getValue("tools"), body.getValue("tools"))
        assertEquals(2, body.getValue("messages").jsonArray.size)
        assertFalse("thinking" in body)
        assertEquals(REASONING_EFFORT, body.getValue("reasoning_effort").jsonPrimitive.content)
        assertFalse(body.toString().contains("OMP_AGENT_PROMPT"))
        assertEquals(1, gateway.metadata("overlay-hash").requestCount)
        assertEquals(listOf(EXPECTED_MODEL_ID), gateway.metadata("overlay-hash").responseModels)
    }

    @Test
    fun nonSuccessResponseExposesStatusWithoutProviderBody() = runBlocking {
        val connection = RecordingConnection(
            responseStatus = 401,
            providerErrorBody = "RAW_PROVIDER_ERROR_MARKER",
        )
        val gateway = OmpStdioChatCompletionGateway(
            modelSelector = MODEL_SELECTOR,
            reasoningEffort = REASONING_EFFORT,
            connection = connection,
            approveFirstRequest = CodexRequestApproval { _, _, _ -> true },
            routingSummary = "synthetic route",
        )
        gateway.initialize()
        val request = applicationRequest()
        gateway.authorizeBeforeCompletion(request)

        val failure = assertFailsWith<OmpGatewayHttpStatusException> {
            gateway.complete(request)
        }

        assertEquals(401, failure.status)
        assertFalse(failure.message.orEmpty().contains("RAW_PROVIDER_ERROR_MARKER"))
    }

    @Test
    fun decliningExactRequestPreviewSendsNoGatewayRequest() = runBlocking {
        val connection = RecordingConnection()
        val gateway = OmpStdioChatCompletionGateway(
            modelSelector = MODEL_SELECTOR,
            reasoningEffort = REASONING_EFFORT,
            connection = connection,
            approveFirstRequest = CodexRequestApproval { _, _, _ -> false },
            routingSummary = "synthetic route",
        )
        gateway.initialize()

        assertFailsWith<ImportBenchmarkCancelledException> {
            gateway.authorizeBeforeCompletion(applicationRequest())
        }
        assertTrue(connection.requests.isEmpty())
    }
    @Test
    fun completionCannotBypassRequestAuthorization() = runBlocking {
        val connection = RecordingConnection()
        val gateway = OmpStdioChatCompletionGateway(
            modelSelector = MODEL_SELECTOR,
            reasoningEffort = REASONING_EFFORT,
            connection = connection,
            approveFirstRequest = CodexRequestApproval { _, _, _ -> true },
            routingSummary = "synthetic route",
        )
        gateway.initialize()

        assertFailsWith<IllegalStateException> { gateway.complete(applicationRequest()) }
        assertTrue(connection.requests.isEmpty())
    }

    @Test
    fun responseModelMustMatchCatalogModelId() = runBlocking {
        listOf(
            null,
            "openai-codex/other-model",
            "other-provider/gpt-6-luna",
            "openai-codex/gpt-6-luna",
            "gpt-6-luna:medium",
            "gpt-6-luna-high",
        ).forEach { responseModel ->
            val request = applicationRequest()
            val gateway = OmpStdioChatCompletionGateway(
                modelSelector = MODEL_SELECTOR,
                reasoningEffort = REASONING_EFFORT,
                connection = RecordingConnection(responseModel),
                approveFirstRequest = CodexRequestApproval { _, _, _ -> true },
                routingSummary = "synthetic route",
            )
            gateway.initialize()
            gateway.authorizeBeforeCompletion(request)

            assertFailsWith<IllegalStateException> { gateway.complete(request) }
            assertEquals(0, gateway.metadata("overlay-hash").requestCount)
        }
    }

    @Test
    fun modelMismatchExposesOnlyAllowlistedLunaIdentifiers() = runBlocking {
        suspend fun mismatchFor(responseModel: String): OmpGatewayModelMismatchException {
            val request = applicationRequest()
            val gateway = OmpStdioChatCompletionGateway(
                modelSelector = MODEL_SELECTOR,
                reasoningEffort = REASONING_EFFORT,
                connection = RecordingConnection(responseModel),
                approveFirstRequest = CodexRequestApproval { _, _, _ -> true },
                routingSummary = "synthetic route",
            )
            gateway.initialize()
            gateway.authorizeBeforeCompletion(request)
            return assertFailsWith<OmpGatewayModelMismatchException> { gateway.complete(request) }
        }

        listOf(
            "openai-codex/gpt-6-luna" to "openai-codex/gpt-6-luna",
            "gpt-6-luna:medium" to "gpt-6-luna:medium",
            "gpt-6-luna-2026-05-20" to "gpt-6-luna-2026-05-20",
        ).forEach { (responseModel, expectedSafeId) ->
            val failure = mismatchFor(responseModel)
            assertEquals(expectedSafeId, failure.safeReportedModelId)
            assertFalse(failure.message.orEmpty().contains(responseModel))
        }

        val rawMarker = "gpt-6-luna:medium RAW_PROVIDER_ERROR_MARKER"
        val unsafeFailure = mismatchFor(rawMarker)
        assertEquals(null, unsafeFailure.safeReportedModelId)
        assertFalse(unsafeFailure.message.orEmpty().contains(rawMarker))

        val unexpectedSuffix = "gpt-6-luna-secret-opaque"
        val suffixedFailure = mismatchFor(unexpectedSuffix)
        assertEquals(null, suffixedFailure.safeReportedModelId)
        assertFalse(suffixedFailure.message.orEmpty().contains(unexpectedSuffix))

        val unrelatedFailure = mismatchFor("other-provider/model")
        assertEquals(null, unrelatedFailure.safeReportedModelId)
    }

    @Test
    fun followUpCompletionUsesRepeatApprovalWithoutAnotherPrompt() = runBlocking {
        val firstRequest = applicationRequest()
        val followUpRequest = firstRequest.copy(
            messages = listOf(
                RequestMessage(role = "system", content = "app-system-instruction"),
                RequestMessage(role = "user", content = "follow-up"),
            ),
        )
        val connection = RecordingConnection()
        var approvalCount = 0
        val gateway = OmpStdioChatCompletionGateway(
            modelSelector = MODEL_SELECTOR,
            reasoningEffort = REASONING_EFFORT,
            connection = connection,
            approveFirstRequest = CodexRequestApproval { _, _, _ ->
                approvalCount += 1
                true
            },
            routingSummary = "synthetic route",
        )
        gateway.initialize()
        gateway.authorizeBeforeCompletion(firstRequest)

        gateway.complete(firstRequest)
        gateway.complete(followUpRequest)

        assertEquals(1, approvalCount)
        assertEquals(2, connection.requests.size)
        assertEquals(2, gateway.metadata("overlay-hash").requestCount)
    }


    @Test
    fun fallbackPreflightRejectsEnabledOrNonEmptyFallbacks() {
        val emptyChains = buildJsonObject {
            putJsonArray(MODEL_SELECTOR) {}
            putJsonArray("openai-codex/*") {}
        }
        OmpFallbackPolicy.verifyEffectiveSettings(
            MODEL_SELECTOR,
            JsonPrimitive(false),
            JsonPrimitive(false),
            emptyChains,
        )
        assertTrue(OmpFallbackPolicy.overlay(MODEL_SELECTOR).contains("modelFallback: false"))
        assertTrue(OmpFallbackPolicy.overlay(MODEL_SELECTOR).contains("usageAwareFallback: false"))

        assertFailsWith<OmpGatewayPreflightException> {
            OmpFallbackPolicy.verifyEffectiveSettings(
                MODEL_SELECTOR,
                JsonPrimitive(true),
                JsonPrimitive(false),
                emptyChains,
            )
        }
        assertFailsWith<OmpGatewayPreflightException> {
            OmpFallbackPolicy.verifyEffectiveSettings(
                MODEL_SELECTOR,
                JsonPrimitive(false),
                JsonPrimitive(true),
                emptyChains,
            )
        }
        assertFailsWith<OmpGatewayPreflightException> {
            OmpFallbackPolicy.verifyEffectiveSettings(
                MODEL_SELECTOR,
                JsonPrimitive(false),
                JsonPrimitive(false),
                Json.parseToJsonElement(
                    """{"$MODEL_SELECTOR":[],"openai-codex/*":["other/provider"]}""",
                ),
            )
        }
    }
    @Test
    fun routingPreviewShowsProfileAndIgnoredOverlayWithoutLeakingPaths() {
        val summary = OmpAuthGatewaySession.routingSummary(
            mapOf(
                "OMP_PROFILE" to "codex-work",
                "PI_CONFIG_FILES" to "/private/user-settings.yml",
            ),
        )

        assertTrue(summary.contains("profile=codex-work"))
        assertTrue(summary.contains("PI_CONFIG_FILES is present but ignored"))
        assertTrue(summary.contains("Account identity is not verified"))
        assertFalse(summary.contains("/private/user-settings.yml"))
        assertTrue(OmpAuthGatewaySession.routingSummary(emptyMap()).contains("OMP default profile"))
        assertFailsWith<IllegalArgumentException> {
            OmpAuthGatewaySession.routingSummary(mapOf("OMP_PROFILE" to "bad\nprofile"))
        }
    }

    @Test
    fun startupRejectsUnboundedOrUnsafeGatewayVersion() = runBlocking {
        listOf("version\ninjected", "v".repeat(65)).forEach { version ->
            val gateway = OmpStdioChatCompletionGateway(
                modelSelector = MODEL_SELECTOR,
                reasoningEffort = REASONING_EFFORT,
                connection = RecordingConnection(readyVersion = version),
                approveFirstRequest = CodexRequestApproval { _, _, _ -> true },
                routingSummary = "synthetic route",
            )

            assertFailsWith<IllegalStateException> { gateway.initialize() }
        }
    }

    @Test
    fun blockedGatewayReadinessIsTimedOutAndChildIsTerminated() {
        val process = ProcessBuilder("sh", "-c", "exec sleep 30").start()
        val connection = ProcessOmpJsonLineConnection(process, startupTimeoutMs = 100)
        try {
            assertFailsWith<IllegalStateException> { runBlocking { connection.readReadyLine() } }
            assertTrue(process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(process.isAlive)
        } finally {
            connection.close()
        }
    }
    @Test
    fun callerCancellationTerminatesBlockedGatewayChild() {
        val process = ProcessBuilder("sh", "-c", "exec sleep 30").start()
        val connection = ProcessOmpJsonLineConnection(process, startupTimeoutMs = 10_000)
        try {
            assertFailsWith<TimeoutCancellationException> {
                runBlocking { withTimeout(100) { connection.readReadyLine() } }
            }
            assertTrue(process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(process.isAlive)
        } finally {
            connection.close()
        }
    }

    @Test
    fun blockedGatewayCompletionIsTimedOutAndChildIsTerminated() {
        val process = ProcessBuilder(
            "sh",
            "-c",
            "printf '%s\\n' '{\"ready\":true,\"version\":\"test\"}'; exec sleep 30",
        ).start()
        val connection = ProcessOmpJsonLineConnection(process, requestTimeoutMs = 100)
        try {
            runBlocking {
                assertEquals(
                    "{\"ready\":true,\"version\":\"test\"}",
                    connection.readReadyLine(),
                )
                assertFailsWith<IllegalStateException> { connection.exchange("{}") }
            }
            assertTrue(process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(process.isAlive)
        } finally {
            connection.close()
        }
    }

    @Test
    fun overlongGatewayResponseLineIsRejectedAndChildIsTerminated() {
        val process = ProcessBuilder("sh", "-c", "printf '%1048577s\\n' ''").start()
        val connection = ProcessOmpJsonLineConnection(process, startupTimeoutMs = 2_000)
        try {
            val error = assertFailsWith<IllegalStateException> {
                runBlocking { connection.readReadyLine() }
            }
            assertTrue(
                error.message.orEmpty().contains("response line exceeds the safe limit"),
                error.message.orEmpty(),
            )
            assertTrue(process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(process.isAlive)
        } finally {
            connection.close()
        }
    }

    @Test
    fun benchmarkChildReplacesOnlyAmbientConfigOverlay() {
        val overlay = Path.of("/tmp/benchmark/fallback-only.yml")
        val inherited = mapOf(
            "PI_CONFIG_FILES" to "/project/private-settings.yml,/user/settings.yml",
            "OMP_PROFILE" to "openai-codex",
            "PATH" to "/usr/bin",
        )

        val child = OmpAuthGatewaySession.processEnvironment(inherited, overlay)

        assertEquals(overlay.toAbsolutePath().toString(), child["PI_CONFIG_FILES"])
        assertEquals("openai-codex", child["OMP_PROFILE"])
        assertEquals("/usr/bin", child["PATH"])
        assertEquals(inherited.keys, child.keys)
    }

    @Test
    fun failedLocalConfigCommandDoesNotEchoChildOutput() = runBlocking {
        val workDirectory = Files.createTempDirectory("benchmark-omp-config-failure-test-")
        try {
            val command = workDirectory.resolve("fake-omp")
            Files.writeString(
                command,
                "#!/bin/sh\nprintf '%s\\n' '89990000000 secret=synthetic /private/diagnostic' >&2\nexit 23\n",
            )
            Files.setPosixFilePermissions(
                command,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )

            val failure = assertFailsWith<OmpGatewayPreflightException> {
                OmpAuthGatewaySession.open(
                    modelSelector = MODEL_SELECTOR,
                    reasoningEffort = REASONING_EFFORT,
                    approveFirstRequest = CodexRequestApproval { _, _, _ -> true },
                    ompCommand = listOf(command.toString()),
                    workingDirectory = workDirectory,
                )
            }

            assertTrue(failure.stage.contains("retry.modelFallback"))
            assertTrue(failure.stage.contains("23"))
            assertFalse(failure.stage.contains("89990000000"))
            assertFalse(failure.stage.contains("synthetic"))
            assertFalse(failure.stage.contains("/private/diagnostic"))
            assertTrue(Files.list(workDirectory).use { paths -> paths.noneMatch { it.fileName.toString().endsWith(".yml") } })
        } finally {
            Files.walk(workDirectory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
    @Test
    fun cancellingConfigPreflightTerminatesChildAndDeletesOverlay() = runBlocking {
        val workDirectory = Files.createTempDirectory("benchmark-omp-config-cancel-test-")
        val marker = workDirectory.resolve("child.pid")
        val command = workDirectory.resolve("fake-omp")
        var pendingPreflight: Job? = null
        try {
            Files.writeString(
                command,
                "#!/bin/sh\nprintf '%s' \"\$\$\" > '${marker}'\nexec sleep 30\n",
            )
            Files.setPosixFilePermissions(
                command,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
            val preflight = async(Dispatchers.IO) {
                OmpAuthGatewaySession.open(
                    modelSelector = MODEL_SELECTOR,
                    reasoningEffort = REASONING_EFFORT,
                    approveFirstRequest = CodexRequestApproval { _, _, _ -> true },
                    ompCommand = listOf(command.toString()),
                    workingDirectory = workDirectory,
                )
            }
            pendingPreflight = preflight
            withTimeout(2_000) {
                while (!Files.exists(marker)) delay(10)
            }
            val pid = Files.readString(marker).trim().toLong()

            preflight.cancelAndJoin()
            withTimeout(2_000) {
                while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(10)
            }
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
            assertTrue(
                Files.list(workDirectory).use { paths ->
                    paths.noneMatch { it.fileName.toString().endsWith(".yml") }
                },
            )
        } finally {
            pendingPreflight?.cancelAndJoin()
            Files.walk(workDirectory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }


    private fun applicationRequest() = ChatCompletionRequest(
        model = "benchmark-local-model",
        messages = listOf(
            RequestMessage(role = "system", content = "app-system-instruction"),
            RequestMessage(role = "user", content = "app-user-content"),
        ),
        thinking = ThinkingOptions("disabled"),
        reasoningEffort = "disabled",
        responseFormat = ResponseFormat("json_object"),
        tools = listOf(
            ChatToolDefinition(
                function = ChatFunctionDefinition(
                    name = "app-tool",
                    description = "app-defined tool",
                    parameters = JsonObject(emptyMap()),
                ),
            ),
        ),
    )

    private class RecordingConnection(
        private val responseModel: String? = EXPECTED_MODEL_ID,
        private val readyVersion: String = "synthetic-omp",
        private val responseStatus: Int = 200,
        private val providerErrorBody: String? = null,
    ) : OmpJsonLineConnection {
        val requests = mutableListOf<String>()

        override suspend fun readReadyLine(): String = buildJsonObject {
            put("ready", true)
            put("version", readyVersion)
        }.toString()
        override suspend fun exchange(requestLine: String): String {
            requests += requestLine
            val id = Json.parseToJsonElement(requestLine).jsonObject.getValue("id")
            val body = if (providerErrorBody != null) {
                buildJsonObject { put("error", providerErrorBody) }
            } else {
                buildJsonObject {
                    responseModel?.let { put("model", it) }
                    putJsonArray("choices") {
                        add(
                            buildJsonObject {
                                putJsonObject("message") {
                                    put("role", "assistant")
                                    put("content", "synthetic response")
                                }
                                put("finish_reason", "stop")
                            },
                        )
                    }
                }
            }
            return buildJsonObject {
                put("id", id)
                put("status", responseStatus)
                put("body", body)
            }.toString()
        }

        override fun close() = Unit
    }

    private companion object {
        const val MODEL_SELECTOR = "openai-codex/gpt-6-luna"
        const val EXPECTED_MODEL_ID = "gpt-6-luna"
        const val REASONING_EFFORT = "medium"
    }
}
