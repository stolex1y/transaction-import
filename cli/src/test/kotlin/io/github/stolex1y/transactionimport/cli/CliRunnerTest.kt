package io.github.stolex1y.transactionimport.cli

import io.github.stolex1y.transactionimport.core.ExtractionResult
import io.github.stolex1y.transactionimport.core.Usage
import kotlinx.coroutines.runBlocking
import java.io.StringReader
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliRunnerTest {
    @Test
    fun printsTextToStdoutAndMetadataToStderr() = runBlocking {
        var receivedStatement: String? = null
        val expectedResult = ExtractionResult(
            text = "parsed transaction",
            finishReason = "stop",
            usage = Usage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
        )
        val runner = CliRunner { statement ->
            receivedStatement = statement
            expectedResult
        }
        val output = StringWriter()
        val error = StringWriter()

        val exitCode = runner.run(
            args = listOf("parse"),
            input = StringReader("  statement text  "),
            output = output,
            error = error,
        )

        assertEquals(0, exitCode)
        assertEquals("  statement text  ", receivedStatement)
        assertEquals("parsed transaction\n", output.toString())
        assertTrue(error.toString().contains("\"finish_reason\""), error.toString())
        assertTrue(error.toString().contains("\"total_tokens\":15"), error.toString())
    }

    @Test
    fun rejectsUnknownCommandWithoutCallingService() = runBlocking {
        var called = false
        val runner = CliRunner {
            called = true
            error("service must not be called")
        }
        val error = StringWriter()

        val exitCode = runner.run(
            args = listOf("serve"),
            input = StringReader("statement"),
            output = StringWriter(),
            error = error,
        )

        assertEquals(2, exitCode)
        assertEquals(false, called)
        assertTrue(error.toString().contains("Usage: transaction-import parse"))
    }

    @Test
    fun printsHelpWithoutCallingService() = runBlocking {
        var called = false
        val runner = CliRunner {
            called = true
            error("service must not be called")
        }
        val output = StringWriter()

        val exitCode = runner.run(
            args = listOf("--help"),
            input = StringReader(""),
            output = output,
            error = StringWriter(),
        )

        assertEquals(0, exitCode)
        assertEquals(false, called)
        assertTrue(output.toString().contains("Usage: transaction-import parse"))
    }
}
