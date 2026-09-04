package io.github.stolex1y.transactionimport.cli

import io.github.stolex1y.transactionimport.core.ExtractionResult
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.Reader

class CliRunner(
    private val service: suspend (String) -> ExtractionResult,
) {
    suspend fun run(
        args: List<String>,
        input: Reader,
        output: Appendable,
        error: Appendable,
    ): Int {
        if (args == listOf("--help") || args == listOf("-h")) {
            output.append(usageText())
            return 0
        }
        if (args != listOf("parse")) {
            error.append("Usage: transaction-import parse < statement.txt\n")
            return 2
        }

        val result = service(input.readText())
        output.append(result.text).append('\n')
        error.append("[api] ").append(metadataJson(result)).append('\n')
        return 0
    }
}

fun usageText(): String = """
    Usage: transaction-import parse < statement.txt

    Reads a bank statement from stdin and prints the model response.
""".trimIndent() + "\n"

private fun metadataJson(result: ExtractionResult): String {
    val metadata = buildJsonObject {
        put(
            "finish_reason",
            result.finishReason?.let { JsonPrimitive(it) } ?: JsonNull,
        )
        put(
            "usage",
            result.usage?.let { usage ->
                buildJsonObject {
                    usage.promptTokens?.let { put("prompt_tokens", it) }
                    usage.completionTokens?.let { put("completion_tokens", it) }
                    usage.totalTokens?.let { put("total_tokens", it) }
                }
            } ?: JsonNull,
        )
    }
    return metadata.toString()
}
