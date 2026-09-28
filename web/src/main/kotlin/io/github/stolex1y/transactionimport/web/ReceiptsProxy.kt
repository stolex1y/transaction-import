package io.github.stolex1y.transactionimport.web

import io.github.stolex1y.transactionimport.core.ProviderUnavailableException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.LinkedHashMap
import java.util.UUID

@Serializable
data class ReceiptsAuthStatus(
    val authenticated: Boolean,
    val status: String,
    val retryable: Boolean = false,
    @SerialName("retry_after_seconds") val retryAfterSeconds: Long = 0,
    @SerialName("persistence_status") val persistenceStatus: String = "not_configured",
    @SerialName("persistence_message") val persistenceMessage: String? = null,
    val message: String? = null,
)

internal fun safeReceiptAuthMessage(message: String?): String? =
    message?.trim()?.take(MAX_AUTH_MESSAGE_LENGTH)
        ?.takeIf {
            it.isNotEmpty() &&
                !UNSAFE_AUTH_MESSAGE.containsMatchIn(it) &&
                !LONG_SECRET.containsMatchIn(it)
        }

private const val MAX_AUTH_MESSAGE_LENGTH = 240
private val UNSAFE_AUTH_MESSAGE =
    Regex("""(?i)(access[_ -]?token|refresh[_ -]?token|receipt[_ -]?key|otp|password|phone|cookie)\s*[:=]""")
private val LONG_SECRET = Regex("""[A-Za-z0-9_-]{40,}""")

interface ReceiptsMcpProvider {
    suspend fun browserLogin(): ReceiptsAuthStatus
    suspend fun receiptsSession(): ReceiptsAuthStatus
    suspend fun receiptsRetrySession(): ReceiptsAuthStatus
    suspend fun receiptsLogout(): ReceiptsAuthStatus
    suspend fun callReceiptTool(tool: String, arguments: JsonObject): TbankToolCallResponse
}

@Serializable
data class ReceiptSearchRequest(
    val from: String,
    val to: String,
    val seller: String? = null,
)

@Serializable
data class ReceiptSummaryResponse(
    @SerialName("receipt_alias") val receiptAlias: String,
    val merchant: String,
    @SerialName("received_at") val receivedAt: String,
    @SerialName("amount_minor") val amountMinor: Long,
    val currency: String,
)

@Serializable
data class ReceiptSearchResponse(
    val receipts: List<ReceiptSummaryResponse>,
    @SerialName("has_more") val hasMore: Boolean,
)

@Serializable
data class ReceiptItemResponse(
    val name: String,
    val quantity: Double,
    @SerialName("price_minor") val priceMinor: Long,
    @SerialName("sum_minor") val sumMinor: Long,
    @SerialName("vat_rate") val vatRate: String? = null,
    @SerialName("payment_type") val paymentType: String? = null,
    @SerialName("product_type") val productType: String? = null,
)

@Serializable
data class ReceiptDetailResponse(
    @SerialName("receipt_alias") val receiptAlias: String,
    val merchant: String,
    @SerialName("date_time") val dateTime: String,
    @SerialName("fiscal_document_number") val fiscalDocumentNumber: String? = null,
    @SerialName("fiscal_drive_number") val fiscalDriveNumber: String? = null,
    @SerialName("fiscal_sign") val fiscalSign: String? = null,
    @SerialName("kkt_reg_id") val kktRegId: String? = null,
    @SerialName("total_minor") val totalMinor: Long,
    val currency: String,
    val items: List<ReceiptItemResponse>,
)

class ReceiptsProxyService(
    private val provider: ReceiptsMcpProvider,
    private val aliases: ReceiptAliasRegistry = ReceiptAliasRegistry(),
) {
    suspend fun browserLogin(): ReceiptsAuthStatus = provider.browserLogin().safeForBrowser()
    suspend fun session(): ReceiptsAuthStatus = provider.receiptsSession().safeForBrowser()
    suspend fun retrySession(): ReceiptsAuthStatus = provider.receiptsRetrySession().safeForBrowser()
    suspend fun logout(): ReceiptsAuthStatus = provider.receiptsLogout().safeForBrowser()

    suspend fun search(request: ReceiptSearchRequest): ReceiptSearchResponse {
        val from = parseDate(request.from, "from")
        val to = parseDate(request.to, "to")
        require(!from.isAfter(to)) { "Дата начала не должна быть позже даты окончания." }
        val seller = request.seller?.trim()?.takeIf(String::isNotEmpty)
        require(seller == null || seller.length <= MAX_SELLER_LENGTH) {
            "Название продавца не должно превышать $MAX_SELLER_LENGTH символов."
        }
        val arguments = buildJsonObject {
            put("from", from.toString())
            put("to", to.toString())
            put("limit", MAX_SEARCH_RESULTS)
            put("offset", 0)
            seller?.let { put("query", it) }
        }
        val result = provider.callReceiptTool(SEARCH_TOOL, arguments)
        if (result.isError || result.text.length > MAX_RESPONSE_CHARS) {
            throw unavailableReceipts()
        }
        val root = parseObject(result.text) ?: throw unavailableReceipts()
        val sourceReceipts = root["receipts"] as? JsonArray ?: throw unavailableReceipts()
        val boundedReceipts = sourceReceipts.take(MAX_SEARCH_RESULTS).mapNotNull(::summaryFrom)
        val summaries = boundedReceipts.map { (key, summary) ->
            summary.copy(receiptAlias = aliases.register(key, summary))
        }
        return ReceiptSearchResponse(
            receipts = summaries,
            hasMore = root["has_more"]?.jsonPrimitive?.booleanOrNull
                ?: (sourceReceipts.size > summaries.size),
        )
    }

    suspend fun detail(alias: String): ReceiptDetailResponse {
        val entry = aliases.resolve(alias) ?: throw IllegalArgumentException("Ссылка на чек недоступна или устарела.")
        val result = provider.callReceiptTool(
            DETAIL_TOOL,
            buildJsonObject { put("receipt_key", entry.key) },
        )
        if (result.isError || result.text.length > MAX_RESPONSE_CHARS) {
            throw unavailableReceipts()
        }
        val root = parseObject(result.text) ?: throw unavailableReceipts()
        val dateTime = root.safeString("date_time", 80) ?: throw unavailableReceipts()
        val total = root["total_minor"]?.jsonPrimitive?.longOrNull
            ?.takeIf { it >= 0L } ?: throw unavailableReceipts()
        val currency = root.safeString("currency", 8) ?: entry.summary.currency
        val sourceItems = root["items"] as? JsonArray ?: throw unavailableReceipts()
        if (sourceItems.size > MAX_DETAIL_ITEMS) throw unavailableReceipts()
        val items = sourceItems.map { value ->
            val item = value as? JsonObject ?: throw unavailableReceipts()
            val name = item.safeString("name", 200) ?: throw unavailableReceipts()
            val quantity = item["quantity"]?.jsonPrimitive?.doubleOrNull
                ?.takeIf { it.isFinite() && it > 0.0 && it <= MAX_ITEM_QUANTITY }
                ?: throw unavailableReceipts()
            val price = item["price_minor"]?.jsonPrimitive?.longOrNull
                ?.takeIf { it >= 0L } ?: throw unavailableReceipts()
            val sum = item["sum_minor"]?.jsonPrimitive?.longOrNull
                ?.takeIf { it >= 0L } ?: throw unavailableReceipts()
            ReceiptItemResponse(
                name = name,
                quantity = quantity,
                priceMinor = price,
                sumMinor = sum,
                vatRate = item.safeString("vat_rate", 40),
                paymentType = item.safeString("payment_type", 80),
                productType = item.safeString("product_type", 80),
            )
        }
        return ReceiptDetailResponse(
            receiptAlias = alias,
            merchant = entry.summary.merchant,
            dateTime = dateTime,
            fiscalDocumentNumber = root.safeString("fiscal_document_number", 120),
            fiscalDriveNumber = root.safeString("fiscal_drive_number", 120),
            fiscalSign = root.safeString("fiscal_sign", 120),
            kktRegId = root.safeString("kkt_reg_id", 120),
            totalMinor = total,
            currency = currency,
            items = items,
        )
    }

    private fun parseDate(value: String, field: String): LocalDate = try {
        LocalDate.parse(value.trim())
    } catch (_: DateTimeParseException) {
        throw IllegalArgumentException("Поле $field должно содержать дату YYYY-MM-DD.")
    }

    private fun parseObject(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()

    private fun summaryFrom(value: kotlinx.serialization.json.JsonElement): Pair<String, ReceiptSummaryResponse>? {
        val receipt = value as? JsonObject ?: return null
        val key = receipt.safeString("receipt_key", MAX_RECEIPT_KEY_LENGTH) ?: return null
        val merchant = receipt.safeString("merchant", 200) ?: return null
        val receivedAt = receipt.safeString("received_at", 80) ?: return null
        val amountMinor = receipt["amount_minor"]?.jsonPrimitive?.longOrNull ?: return null
        val currency = receipt.safeString("currency", 8) ?: "RUB"
        return key to ReceiptSummaryResponse(
            receiptAlias = "",
            merchant = merchant,
            receivedAt = receivedAt,
            amountMinor = amountMinor,
            currency = currency,
        )
    }

    private fun ReceiptsAuthStatus.safeForBrowser(): ReceiptsAuthStatus {
        val safeStatus = status.takeIf(SAFE_AUTH_STATUSES::contains) ?: "recoverable_error"
        return copy(
            authenticated = authenticated && safeStatus == "active",
            status = safeStatus,
            retryable = retryable && safeStatus == "recoverable_error",
            retryAfterSeconds = retryAfterSeconds.coerceIn(0L, MAX_RETRY_DELAY_SECONDS),
            persistenceStatus = persistenceStatus.takeIf(SAFE_PERSISTENCE_STATUSES::contains) ?: "unknown",
            persistenceMessage = safeReceiptAuthMessage(persistenceMessage),
            message = safeReceiptAuthMessage(message),
        )
    }

    private fun unavailableReceipts() =
        ProviderUnavailableException("Сервис чеков недоступен или вернул неполные данные.")

    private fun JsonObject.safeString(name: String, maxLength: Int): String? =
        this[name]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= maxLength }

    companion object {
        private const val SEARCH_TOOL = "search-receipts"
        private const val DETAIL_TOOL = "get-receipt"
        private const val MAX_SEARCH_RESULTS = 100
        private const val MAX_DETAIL_ITEMS = 100
        private const val MAX_RESPONSE_CHARS = 1_000_000
        private const val MAX_RECEIPT_KEY_LENGTH = 512
        private const val MAX_SELLER_LENGTH = 120
        private const val MAX_ITEM_QUANTITY = 100_000.0
        private const val MAX_RETRY_DELAY_SECONDS = 3_600L
        private val SAFE_AUTH_STATUSES = setOf(
            "authenticating",
            "active",
            "login_required",
            "recoverable_error",
            "logout_failed",
        )
        private val SAFE_PERSISTENCE_STATUSES = setOf(
            "available",
            "stored",
            "persisted",
            "memory_only",
            "not_configured",
            "unavailable",
            "error",
        )
        private val json = Json { ignoreUnknownKeys = true }
    }
}

class ReceiptAliasRegistry(private val maxEntries: Int = 500) {
    private val entries = LinkedHashMap<String, ReceiptAliasEntry>()

    init {
        require(maxEntries > 0)
    }

    @Synchronized
    fun register(key: String, summary: ReceiptSummaryResponse): String {
        val alias = UUID.randomUUID().toString()
        entries[alias] = ReceiptAliasEntry(key, summary)
        while (entries.size > maxEntries) {
            val oldest = entries.entries.iterator()
            oldest.next()
            oldest.remove()
        }
        return alias
    }

    @Synchronized
    fun resolve(alias: String): ReceiptAliasEntry? = entries[alias]
}

data class ReceiptAliasEntry(
    val key: String,
    val summary: ReceiptSummaryResponse,
)
