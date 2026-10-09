package io.github.stolex1y.transactionimport.web.benchmark

import io.github.stolex1y.transactionimport.core.ChatCompletionGateway
import io.github.stolex1y.transactionimport.core.ChatCompletionRequest
import io.github.stolex1y.transactionimport.core.ChatCompletionResponse
import io.github.stolex1y.transactionimport.core.RequestMessage
import io.github.stolex1y.transactionimport.core.ThinkingOptions
import io.github.stolex1y.transactionimport.web.PreCompletionRequestAuthorizer
import java.io.BufferedReader
import java.io.PipedReader
import java.io.PipedWriter
import java.io.Reader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportBenchmarkCliTest {
    @Test
    fun codexModeRequiresSeparateChatConsentAndEgressFlag() {
        assertFailsWith<IllegalArgumentException> {
            BenchmarkOptions.parse(arrayOf("--mode=codex"))
        }
        assertFailsWith<IllegalArgumentException> {
            BenchmarkOptions.parse(arrayOf("--mode=codex", "--allow-codex-egress"))
        }
        assertFailsWith<IllegalArgumentException> {
            BenchmarkOptions.parse(arrayOf("--mode=codex", "--chat-egress-consent"))
        }
        assertFailsWith<IllegalArgumentException> {
            BenchmarkOptions.parse(arrayOf("--mode=offline", "--allow-codex-egress"))
        }
        assertFailsWith<IllegalArgumentException> {
            BenchmarkOptions.parse(arrayOf("--mode=offline", "--chat-egress-consent"))
        }

        val options = BenchmarkOptions.parse(
            arrayOf("--mode=codex", "--allow-codex-egress", "--chat-egress-consent"),
        )
        assertEquals(BenchmarkMode.CODEX, options.mode)
        assertTrue(options.allowCodexEgress)
        assertTrue(options.chatEgressConsent)
    }

    @Test
    fun diagnosticModeRequiresSeparateEgressApprovalsAndCannotWriteReport() {
        assertFailsWith<IllegalArgumentException> {
            BenchmarkOptions.parse(arrayOf("--mode=diagnostic"))
        }
        assertFailsWith<IllegalArgumentException> {
            BenchmarkOptions.parse(arrayOf("--mode=diagnostic", "--allow-codex-egress"))
        }
        assertFailsWith<IllegalArgumentException> {
            BenchmarkOptions.parse(arrayOf("--mode=diagnostic", "--chat-egress-consent"))
        }
        assertFailsWith<IllegalArgumentException> {
            BenchmarkOptions.parse(
                arrayOf(
                    "--mode=diagnostic",
                    "--allow-codex-egress",
                    "--chat-egress-consent",
                    "--report=report.json",
                ),
            )
        }

        val options = BenchmarkOptions.parse(
            arrayOf("--mode=diagnostic", "--allow-codex-egress", "--chat-egress-consent"),
        )
        assertEquals(BenchmarkMode.DIAGNOSTIC, options.mode)
    }

    @Test
    fun diagnosticCleanupFailureIsSurfacedWithoutRawDetails() {
        val rawMarker = "private-overlay-path"
        val cleanupError = AssertionError(rawMarker)
        val emitted = mutableListOf<String>()

        val failure = assertFailsWith<BenchmarkCleanupException> {
            closeDiagnosticSession(
                closeSession = { throw cleanupError },
                primaryFailure = null,
                emit = emitted::add,
            )
        }

        assertTrue(failure.suppressed.single() === cleanupError)
        assertTrue(emitted.single().contains("cleanup failed"))
        assertFalse(emitted.toString().contains(rawMarker))
    }

    @Test
    fun diagnosticCleanupFailureIsSuppressedOntoPrimaryFailure() {
        val primaryFailure = IllegalStateException("original")
        val cleanupError = IllegalStateException("private-overlay-path")
        val emitted = mutableListOf<String>()

        closeDiagnosticSession(
            closeSession = { throw cleanupError },
            primaryFailure = primaryFailure,
            emit = emitted::add,
        )

        assertTrue(primaryFailure.suppressed.single() === cleanupError)
        assertEquals(1, emitted.size)
        assertFalse(emitted.toString().contains("private-overlay-path"))
    }

    @Test
    fun codexAutomaticApprovalRequiresExactApprovedBodyAndRoute() {
        assertTrue(
            autoApproveCodexBenchmarkRequest(
                1,
                APPROVED_BENCHMARK_BODY_SHA256,
                APPROVED_BENCHMARK_ROUTING_SUMMARY,
            ),
        )
        assertFalse(autoApproveCodexBenchmarkRequest(1, "different-body", APPROVED_BENCHMARK_ROUTING_SUMMARY))
        assertFalse(autoApproveCodexBenchmarkRequest(1, APPROVED_BENCHMARK_BODY_SHA256, "different route"))
    }

    @Test
    fun diagnosticGatewayStopsAfterOneCompletion() = runBlocking {
        val request = ChatCompletionRequest(
            model = "diagnostic-model",
            messages = listOf(RequestMessage(role = "user", content = "diagnostic")),
            thinking = ThinkingOptions(type = "disabled"),
        )
        var authorizationCalls = 0
        var completionCalls = 0
        val delegate = object : ChatCompletionGateway, PreCompletionRequestAuthorizer {
            override suspend fun authorizeBeforeCompletion(request: ChatCompletionRequest) {
                authorizationCalls += 1
            }

            override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
                completionCalls += 1
                return ChatCompletionResponse()
            }
        }
        val diagnostic = OneCompletionDiagnosticGateway(delegate, delegate) { "openai-codex/gpt-6-luna" }

        diagnostic.authorizeBeforeCompletion(request)
        val completion = assertFailsWith<OmpGatewayDiagnosticCompletedException> { diagnostic.complete(request) }
        assertEquals("openai-codex/gpt-6-luna", completion.safeReportedModelId)
        assertEquals(1, authorizationCalls)
        assertEquals(1, completionCalls)
        assertFailsWith<IllegalStateException> { diagnostic.complete(request) }
        assertEquals(1, completionCalls)
    }

    @Test
    fun safeFailureLabelShowsOnlyExactAllowlistedFrameAndNeverMessage() {
        val error = IllegalArgumentException("sensitive provider content").apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    "io.github.stolex1y.transactionimport.web.NativeMcpAgent",
                    "handle",
                    "NativeMcpAgent.kt",
                    540,
                ),
            )
        }

        assertEquals(
            "IllegalArgumentException at NativeMcpAgent.handle",
            safeBenchmarkFailureLabel(error),
        )
        assertFalse(safeBenchmarkFailureLabel(error).contains("sensitive provider content"))

        error.stackTrace = arrayOf(
            StackTraceElement(
                "io.github.stolex1y.transactionimport.web.ForgedTrustedPrefix",
                "attackerControlledMethod",
                "Forged.kt",
                1,
            ),
        )
        assertEquals("IllegalArgumentException", safeBenchmarkFailureLabel(error))

        error.stackTrace = arrayOf(StackTraceElement("untrusted.Provider", "call", "Provider.kt", 1))
        assertEquals("IllegalArgumentException", safeBenchmarkFailureLabel(error))
    }

    @Test
    fun safeFailureLabelMapsKnownReplayMessagesAndDropsUnknownMessage() {
        val frame = StackTraceElement(
            "io.github.stolex1y.transactionimport.web.benchmark.ImportBenchmarkReplay",
            "listTransactions",
            "ImportBenchmarkReplay.kt",
            99,
        )
        val known = IllegalArgumentException("Benchmark replay rejected an unknown account reference.").apply {
            stackTrace = arrayOf(frame)
        }
        val unknown = IllegalArgumentException("raw account-alias value is private").apply {
            stackTrace = arrayOf(frame)
        }

        assertEquals(
            "IllegalArgumentException at ImportBenchmarkReplay.listTransactions (replay rejected account reference)",
            safeBenchmarkFailureLabel(known),
        )
        assertEquals(
            "IllegalArgumentException at ImportBenchmarkReplay.listTransactions",
            safeBenchmarkFailureLabel(unknown),
        )
        assertFalse(safeBenchmarkFailureLabel(unknown).contains("private"))
    }

    @Test
    fun safeFailureLabelMapsKnownRunnerInvariantAndDropsUnknownMessage() {
        val frame = StackTraceElement(
            "io.github.stolex1y.transactionimport.web.benchmark.ImportBenchmarkRunner",
            "runOne",
            "ImportBenchmarkRunner.kt",
            131,
        )
        val known = IllegalStateException("Benchmark model did not produce an import preview.").apply {
            stackTrace = arrayOf(frame)
        }
        val unknown = IllegalStateException("raw tool arguments include account value").apply {
            stackTrace = arrayOf(frame)
        }

        assertEquals(
            "IllegalStateException at ImportBenchmarkRunner.runOne (model produced no import preview)",
            safeBenchmarkFailureLabel(known),
        )
        assertEquals(
            "IllegalStateException at ImportBenchmarkRunner.runOne",
            safeBenchmarkFailureLabel(unknown),
        )
        assertFalse(safeBenchmarkFailureLabel(unknown).contains("account value"))
    }
    @Test
    fun nonNetworkModesCannotWriteOrEnableCodexEgress() {
        listOf("inspect", "preflight").forEach { mode ->
            assertFailsWith<IllegalArgumentException> {
                BenchmarkOptions.parse(arrayOf("--mode=$mode", "--report=report.json"))
            }
        }
        assertFalse(BenchmarkOptions.parse(arrayOf("--mode=offline")).allowCodexEgress)
        assertFalse(BenchmarkOptions.parse(arrayOf("--mode=offline")).chatEgressConsent)
    }

    @Test
    fun cancellationReleasesConsoleApprovalWait() {
        runBlocking {
            val reader = PipedReader()
            val writer = PipedWriter(reader)
            val bufferedReader = BufferedReader(reader)
            val input = BenchmarkConsoleInput(bufferedReader)
            try {
                assertFailsWith<TimeoutCancellationException> {
                    withTimeout(100) { input.readLine() }
                }
            } finally {
                writer.close()
                input.close()
                bufferedReader.close()
            }
        }
    }

    @Test
    fun closeDoesNotBlockBehindNonInterruptibleConsoleRead() {
        runBlocking {
            val readStarted = CountDownLatch(1)
            val releaseRead = CountDownLatch(1)
            val reader = object : Reader() {
                override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                    readStarted.countDown()
                    while (true) {
                        try {
                            releaseRead.await()
                            return -1
                        } catch (_: InterruptedException) {
                            // A terminal read may ignore interruption until input arrives.
                        }
                    }
                }

                override fun close() {
                    releaseRead.countDown()
                }
            }
            val bufferedReader = BufferedReader(reader)
            val input = BenchmarkConsoleInput(bufferedReader)
            val closeExecutor = Executors.newSingleThreadExecutor { task ->
                Thread(task, "benchmark-console-close-test").apply { isDaemon = true }
            }
            var closeFuture: Future<*>? = null
            try {
                val readJob = async(Dispatchers.Default) { input.readLine() }
                assertTrue(readStarted.await(1, TimeUnit.SECONDS))
                readJob.cancelAndJoin()

                closeFuture = closeExecutor.submit { input.close() }
                requireNotNull(closeFuture).get(1, TimeUnit.SECONDS)
            } finally {
                releaseRead.countDown()
                runCatching { bufferedReader.close() }
                runCatching { closeFuture?.get(1, TimeUnit.SECONDS) }
                closeExecutor.shutdownNow()
                input.close()
            }
        }
    }
}
