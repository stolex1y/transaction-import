package io.github.stolex1y.transactionimport.web.benchmark

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
