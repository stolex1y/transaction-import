package io.github.stolex1y.transactionimport.core

internal data class TokenAwareBudget(
    val contextWindowTokens: Int,
    val thresholdTokens: Int,
    val reserveTokens: Int,
    val keepRecentTokens: Int,
)

internal data class ContextTokenEstimate(
    val tokens: Int,
    val source: String,
)

internal object ContextTokenEstimator {
    const val UTF8_UPPER_BOUND_SOURCE = "utf8_upper_bound"

    fun estimate(messages: List<RequestMessage>): ContextTokenEstimate {
        var bytes = messages.size * 4 + 2
        messages.forEach { message ->
            bytes = bytes.saturatingAdd(message.role.encodeToByteArray().size)
            bytes = bytes.saturatingAdd(1)
            bytes = bytes.saturatingAdd(message.content.encodeToByteArray().size)
            bytes = bytes.saturatingAdd(1)
        }
        return ContextTokenEstimate(
            tokens = bytes.coerceAtLeast(1),
            source = UTF8_UPPER_BOUND_SOURCE,
        )
    }
}

internal fun ContextManagementConfig.tokenAwareBudget(contextWindowTokens: Int): TokenAwareBudget {
    require(contextWindowTokens > 1) { "Context window должен содержать хотя бы два токена." }
    val maximumReserve = (contextWindowTokens / 2).coerceAtLeast(1)
    val defaultReserve = maxOf(16_384, (contextWindowTokens * 0.15).toInt().coerceAtLeast(1))
    val requestedReserve = summaryReserveTokens ?: defaultReserve
    val reserve = requestedReserve.coerceIn(1, maximumReserve)
    val threshold = when {
        summaryThresholdTokens != null -> summaryThresholdTokens.coerceIn(1, contextWindowTokens - 1)
        summaryThresholdPercent != null ->
            (contextWindowTokens * summaryThresholdPercent / 100).coerceIn(1, contextWindowTokens - 1)
        else -> contextWindowTokens - reserve
    }
    return TokenAwareBudget(
        contextWindowTokens = contextWindowTokens,
        thresholdTokens = threshold,
        reserveTokens = contextWindowTokens - threshold,
        keepRecentTokens = summaryKeepRecentTokens.coerceAtMost(threshold),
    )
}

private fun Int.saturatingAdd(value: Int): Int =
    if (Int.MAX_VALUE - this < value) Int.MAX_VALUE else this + value
