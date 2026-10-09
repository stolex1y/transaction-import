package io.github.stolex1y.transactionimport.web.benchmark

import io.github.stolex1y.transactionimport.core.ChatCompletionGateway
import io.github.stolex1y.transactionimport.core.ChatCompletionRequest
import io.github.stolex1y.transactionimport.core.ChatCompletionResponse
import io.github.stolex1y.transactionimport.web.PreCompletionRequestAuthorizer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val input = BenchmarkConsoleInput(BufferedReader(InputStreamReader(System.`in`, StandardCharsets.UTF_8)))
    try {
        runBlocking { runBenchmarkCli(args, input) }
    } catch (error: ImportBenchmarkPayloadPreviewException) {
        println("Локальная OMP route policy до egress: ${safeRouteSummary()}")
        println("Точный первый provider-facing payload; он не отправлен:")
        println("SHA-256: ${error.fingerprint}")
        println(error.providerBody)
        println("Режим inspection завершён; ни модель, ни внешний API не вызывались.")
    } catch (_: ImportBenchmarkCancelledException) {
        println("Benchmark остановлен: запрос не прошёл gate либо preview не был подтверждён; временная база удалена.")
    } catch (error: CliArgumentException) {
        System.err.println(error.message)
        System.err.println(
            "Использование: --mode=inspect | --mode=preflight | --mode=offline | " +
                "--mode=diagnostic | --mode=codex --allow-codex-egress --chat-egress-consent [--report=PATH]",
        )
        exitProcess(2)
    } catch (error: OmpGatewayPreflightException) {
        System.err.println(
            "Локальный OMP preflight не прошёл на этапе '${error.stage}'; completion request не отправлялся.",
        )
        exitProcess(1)
    } catch (error: ImportBenchmarkModelRequestException) {
        System.err.println(
            "Benchmark остановлен без сохранения результата (${error.message}); " +
                "содержимое запросов, ответов и fixture скрыто.",
        )
        exitProcess(1)
    } catch (error: Throwable) {
        System.err.println(
            "Benchmark остановлен без сохранения результата (${safeBenchmarkFailureLabel(error)}); " +
                "содержимое запросов, ответов и fixture скрыто.",
        )
        exitProcess(1)
    } finally {
        input.close()
    }
}

internal fun safeBenchmarkFailureLabel(error: Throwable): String {
    val safeFrameLabel = error.stackTrace.firstNotNullOfOrNull { frame ->
        SAFE_FAILURE_FRAME_LABELS[frame.className to frame.methodName]
    }
    if (safeFrameLabel == null) return error::class.simpleName ?: "ошибка"
    val type = error::class.simpleName ?: "ошибка"
    val safeDetail = SAFE_FAILURE_DETAIL_LABELS[error.message]
        ?.let { " ($it)" }
        .orEmpty()
    return "$type at $safeFrameLabel$safeDetail"
}

private val SAFE_FAILURE_DETAIL_LABELS = mapOf(
    "Benchmark replay rejected an unknown account reference." to "replay rejected account reference",
    "Benchmark replay permits only the reviewed transaction period." to "replay rejected transaction period",
    "Benchmark replay rejected an invalid page limit." to "replay rejected page limit",
    "Benchmark replay rejected a missing tool argument." to "replay rejected missing tool argument",
    "Benchmark replay rejected an invalid date." to "replay rejected invalid date",
    "Benchmark replay rejected an invalid transaction cursor." to "replay rejected transaction cursor",
    "Benchmark model did not produce an import preview." to "model produced no import preview",
    "Benchmark import preview is missing." to "import preview is missing",
    "Benchmark preview did not contain the reviewed operation set." to "preview operation set mismatch",
    "Native MCP preview changed the draft before explicit confirmation." to "preview changed draft before confirmation",
    "Post-confirm inclusion review failed; this repetition cannot be scored." to "post-confirm inclusion review failed",
    "Confirmed benchmark preview did not create a draft." to "confirmed preview did not create a draft",
    "Confirmed benchmark draft did not contain the reviewed operation set." to "confirmed draft operation set mismatch",
    "Benchmark draft row has no source reference." to "draft row lacks source reference",
    "Benchmark result lost the approved operation identity mapping." to "approved operation identity mapping lost",
    "Benchmark category label is unavailable in the active catalog." to "benchmark category label unavailable",
    "Ambiguous receipt preview omitted reviewed candidates." to "ambiguous receipt candidates omitted",
    "Confirmation changed an ambiguous receipt review; this repetition cannot be scored." to
        "ambiguous receipt review changed",
)

private val SAFE_FAILURE_FRAME_LABELS = mapOf(
    ("io.github.stolex1y.transactionimport.web.NativeMcpAgent" to "handle") to "NativeMcpAgent.handle",
    ("io.github.stolex1y.transactionimport.web.benchmark.ImportBenchmarkReplay" to "callConfiguredTool") to
        "ImportBenchmarkReplay.callConfiguredTool",
    ("io.github.stolex1y.transactionimport.web.benchmark.ImportBenchmarkReplay" to "listTransactions") to
        "ImportBenchmarkReplay.listTransactions",
    ("io.github.stolex1y.transactionimport.web.benchmark.ImportBenchmarkReplay" to "searchReceipts") to
        "ImportBenchmarkReplay.searchReceipts",
    ("io.github.stolex1y.transactionimport.web.benchmark.ImportBenchmarkReplay" to "receiptDetail") to
        "ImportBenchmarkReplay.receiptDetail",
    ("io.github.stolex1y.transactionimport.web.benchmark.ImportBenchmarkReplay" to "requiredText") to
        "ImportBenchmarkReplay.requiredText",
    ("io.github.stolex1y.transactionimport.web.benchmark.ImportBenchmarkReplay" to "parseDate") to
        "ImportBenchmarkReplay.parseDate",
    ("io.github.stolex1y.transactionimport.web.benchmark.ImportBenchmarkRunner" to "runOne") to
        "ImportBenchmarkRunner.runOne",
    ("io.github.stolex1y.transactionimport.web.benchmark.ImportBenchmarkRunner" to "score") to
        "ImportBenchmarkRunner.score",
    ("io.github.stolex1y.transactionimport.web.benchmark.ImportBenchmarkRunner" to "requireAmbiguousReviewsPreserved") to
        "ImportBenchmarkRunner.requireAmbiguousReviewsPreserved",
)

internal class BenchmarkConsoleInput(
    private val reader: BufferedReader,
) : Closeable {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "import-benchmark-console-input").apply { isDaemon = true }
    }

    suspend fun readLine(): String? = suspendCancellableCoroutine { continuation ->
        val future = executor.submit<Unit> {
            try {
                val line = reader.readLine()
                if (continuation.isActive) continuation.resume(line)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            Unit
        }
        continuation.invokeOnCancellation { future.cancel(true) }
    }

    override fun close() {
        executor.shutdownNow()
        // System.in is borrowed; closing a reader blocked on terminal input can wait indefinitely.
    }
}

private fun safeRouteSummary(): String = try {
    OmpAuthGatewaySession.routingSummary(System.getenv())
} catch (_: IllegalArgumentException) {
    "OMP route policy cannot be safely displayed; preflight will fail."
}

private suspend fun runBenchmarkCli(args: Array<String>, input: BenchmarkConsoleInput) {
    val options = BenchmarkOptions.parse(args)
    val fixture = ImportBenchmarkFixtureLoader.loadBundled()
    when (options.mode) {
        BenchmarkMode.INSPECT -> inspectFirstProviderRequest(fixture)
        BenchmarkMode.PREFLIGHT -> runOmpPreflight(fixture)
        BenchmarkMode.OFFLINE -> runOffline(fixture, options)
        BenchmarkMode.CODEX -> runCodex(fixture, options)
        BenchmarkMode.DIAGNOSTIC -> runCodexDiagnostic(fixture, options, input)
    }
}

private suspend fun inspectFirstProviderRequest(fixture: ImportBenchmarkFixture): Nothing {
    val runner = ImportBenchmarkRunner(
        fixture = fixture,
        gatewayForRun = { PayloadInspectionGateway(fixture.modelSelector, fixture.reasoningEffort) },
        confirmPreview = { _, _ -> false },
    )
    runner.run(1)
    throw IllegalStateException("Inspection did not capture an application request.")
}

private suspend fun runOmpPreflight(fixture: ImportBenchmarkFixture) {
    val session = OmpAuthGatewaySession.open(
        modelSelector = fixture.modelSelector,
        reasoningEffort = fixture.reasoningEffort,
        approveFirstRequest = CodexRequestApproval { _, _, _ -> false },
    )
    try {
        val metadata = session.metadata()
        println("OMP stdio gateway готов: ${metadata.gatewayVersion}.")
        println(OmpAuthGatewaySession.routingSummary(System.getenv()))
        println("Fallback disabled; effective-настройки прошли локальную проверку до model request.")
        println("Временный overlay SHA-256: ${metadata.fallbackOverlaySha256}.")
        println("Model/API request не отправлялся; доступность selector без egress не подтверждалась.")
    } finally {
        session.close()
    }
}

private suspend fun runOffline(
    fixture: ImportBenchmarkFixture,
    options: BenchmarkOptions,
) {
    val runner = ImportBenchmarkRunner(
        fixture = fixture,
        gatewayForRun = { OfflineBenchmarkGateway(fixture) },
        confirmPreview = { repetition, _ ->
            println("Повтор $repetition: offline fake; preview будет подтверждён только во временной БД.")
            true
        },
        emit = ::println,
    )
    val metrics = runner.run(fixture.repetitions)
    writeReport("offline", fixture, metrics.map(::ImportBenchmarkRun), options.reportPath)
    metrics.forEachIndexed { index, result -> printMetrics(index + 1, result) }
}

private suspend fun runCodex(
    fixture: ImportBenchmarkFixture,
    options: BenchmarkOptions,
) {
    check(options.allowCodexEgress) { "Codex mode requires --allow-codex-egress." }
    check(options.chatEgressConsent) { "Codex mode requires separate chat egress consent." }
    val gatewayMetadata = mutableListOf<OmpGatewayMetadata>()
    var activeSession: OmpAuthGatewaySession? = null
    try {
        val runner = ImportBenchmarkRunner(
            fixture = fixture,
            gatewayForRun = { repetition ->
                activeSession?.let { previous ->
                    gatewayMetadata += previous.metadata()
                    previous.close()
                    activeSession = null
                }
                val session = OmpAuthGatewaySession.open(
                    modelSelector = fixture.modelSelector,
                    reasoningEffort = fixture.reasoningEffort,
                    approveFirstRequest = CodexRequestApproval { _, fingerprint, routingSummary ->
                        autoApproveCodexBenchmarkRequest(repetition, fingerprint, routingSummary)
                    },
                )
                activeSession = session
                println("Повтор $repetition: локальный OMP gateway ${session.metadata().gatewayVersion} готов; fallback проверен.")
                session.gateway()
            },
            confirmPreview = { repetition, summary ->
                println(
                    "Повтор $repetition: preview проверен; подтверждаю его production confirm() только во временной БД.",
                )
                println(
                    "Операции ${summary.transactionCount}, категоризировано ${summary.categorizedCount}; " +
                        "чеки: совпало ${summary.matchedReceiptCount}, неоднозначно ${summary.ambiguousReceiptCount}, " +
                        "не сопоставлено ${summary.unmatchedReceiptCount}.",
                )
                true
            },
            emit = ::println,
        )
        val metrics = runner.run(fixture.repetitions)
        activeSession?.let { last -> gatewayMetadata += last.metadata() }
        val reportRuns = metrics.mapIndexed { index, result ->
            ImportBenchmarkRun(
                result,
                gatewayMetadata.getOrNull(index)
                    ?: throw IllegalStateException("Missing gateway metadata for a completed repetition."),
            )
        }
        writeReport("codex", fixture, reportRuns, options.reportPath)
        metrics.forEachIndexed { index, result -> printMetrics(index + 1, result) }
    } finally {
        activeSession?.let { session -> runCatching(session::close) }
    }
}

internal const val APPROVED_BENCHMARK_BODY_SHA256 =
    "05b0b60cae7052872e48a425e501eff943f67f04b2e0691b04240e39ee2f38a3"
internal const val APPROVED_BENCHMARK_ROUTING_SUMMARY =
    "OMP routing: profile=unset (OMP default profile); config=benchmark fallback-only overlay; " +
        "inherited PI_CONFIG_FILES is not set; other inherited environment is preserved. Account identity is not verified."

internal fun isApprovedCodexRequestTarget(bodySha256: String, routingSummary: String): Boolean =
    bodySha256 == APPROVED_BENCHMARK_BODY_SHA256 && routingSummary == APPROVED_BENCHMARK_ROUTING_SUMMARY

internal fun autoApproveCodexBenchmarkRequest(
    repetition: Int,
    bodySha256: String,
    routingSummary: String,
): Boolean {
    if (!isApprovedCodexRequestTarget(bodySha256, routingSummary)) {
        println("Повтор $repetition: request SHA или OMP route отличается от утверждённого; egress запрещён.")
        return false
    }
    println("Повтор $repetition: утверждённые SHA-256 и OMP route совпали; completion отправляется автоматически.")
    println("SHA-256: $bodySha256")
    println(routingSummary)
    return true
}

private suspend fun runCodexDiagnostic(
    fixture: ImportBenchmarkFixture,
    options: BenchmarkOptions,
    input: BenchmarkConsoleInput,
) {
    check(options.allowCodexEgress) { "Diagnostic mode requires --allow-codex-egress." }
    check(options.chatEgressConsent) { "Diagnostic mode requires separate chat egress consent." }
    var activeSession: OmpAuthGatewaySession? = null
    try {
        val runner = ImportBenchmarkRunner(
            fixture = fixture,
            gatewayForRun = { repetition ->
                check(repetition == 1) { "One-completion diagnostic cannot start another repetition." }
                val session = OmpAuthGatewaySession.open(
                    modelSelector = fixture.modelSelector,
                    reasoningEffort = fixture.reasoningEffort,
                    approveFirstRequest = CodexRequestApproval { body, fingerprint, routingSummary ->
                        if (!isApprovedCodexRequestTarget(fingerprint, routingSummary)) {
                            println("Diagnostic request body or OMP route differs from the approved target; no request sent.")
                            false
                        } else {
                            println("Одноразовая диагностика; маршрут OMP: $routingSummary")
                            println("Точный provider-facing request перед отправкой; SHA-256: $fingerprint")
                            println(body)
                            println("Введите SEND, чтобы отправить ровно один completion; любой другой ввод отменит диагностику.")
                            input.readLine() == "SEND"
                        }
                    },
                )
                activeSession = session
                OneCompletionDiagnosticGateway(
                    delegate = session.gateway(),
                    requestAuthorizer = session.gateway(),
                    responseModelAfterCompletion = { session.metadata().responseModels.lastOrNull() },
                )
            },
            confirmPreview = { _, _ ->
                throw IllegalStateException("One-completion diagnostic unexpectedly reached preview.")
            },
            emit = ::println,
        )
        try {
            runner.run(1)
            throw IllegalStateException("One-completion diagnostic unexpectedly completed scoring.")
        } catch (error: ImportBenchmarkModelRequestException) {
            println(
                "Одноразовая диагностика остановлена до preview/scoring/report: " +
                    (error.safeSummary ?: "provider details omitted"),
            )
        }
    } finally {
        activeSession?.let { session -> runCatching(session::close) }
    }
}

private fun writeReport(
    mode: String,
    fixture: ImportBenchmarkFixture,
    runs: List<ImportBenchmarkRun>,
    requestedPath: Path?,
) {
    val path = requestedPath ?: defaultReportPath()
    val sourceCommit = System.getProperty("import.benchmark.sourceCommit")?.takeIf(String::isNotBlank) ?: "unknown"
    val report = ImportBenchmarkReportWriter.render(mode, fixture, runs, sourceCommit)
    ImportBenchmarkReportWriter.writeNew(path, report)
    println("Redacted report saved to ${path.toAbsolutePath().normalize()}.")
}

private fun defaultReportPath(): Path {
    val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC)
        .format(Instant.now())
    return Path.of("build", "reports", "import-benchmark", "codex-import-$timestamp.json")
}

private fun printMetrics(repetition: Int, metrics: ImportBenchmarkMetrics) {
    println(
        "Повтор $repetition: selection precision=${metrics.selectionPrecision}, " +
            "recall=${metrics.selectionRecall}, category accuracy=${metrics.categoryAccuracy}, " +
            "coverage=${metrics.categoryCoverage}, receipt labels=${metrics.receiptLabelCount}.",
    )
}

private class PayloadInspectionGateway(
    private val modelSelector: String,
    private val reasoningEffort: String,
) : ChatCompletionGateway {
    override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
        val body = OmpCodexRequestProjection.body(request, modelSelector, reasoningEffort)
        val prettyJson = Json { prettyPrint = true; explicitNulls = false }
        val pretty = prettyJson.encodeToString(JsonObject.serializer(), body)
        val compactBody = Json { encodeDefaults = true; explicitNulls = false }
            .encodeToString(JsonObject.serializer(), body)
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
            .digest(compactBody.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        throw ImportBenchmarkPayloadPreviewException(pretty, fingerprint)
    }
}
internal class OneCompletionDiagnosticGateway(
    private val delegate: ChatCompletionGateway,
    private val requestAuthorizer: PreCompletionRequestAuthorizer,
    private val responseModelAfterCompletion: () -> String?,
) : ChatCompletionGateway, PreCompletionRequestAuthorizer {
    private val completionAttempted = java.util.concurrent.atomic.AtomicBoolean(false)

    override val contextWindowTokens: Int?
        get() = delegate.contextWindowTokens

    override val maxOutputTokens: Int?
        get() = delegate.maxOutputTokens

    override suspend fun authorizeBeforeCompletion(request: ChatCompletionRequest) {
        requestAuthorizer.authorizeBeforeCompletion(request)
    }

    override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
        check(completionAttempted.compareAndSet(false, true)) {
            "One-completion diagnostic already used."
        }
        delegate.complete(request)
        throw OmpGatewayDiagnosticCompletedException(responseModelAfterCompletion())
    }
}

private class CliArgumentException(message: String) : IllegalArgumentException(message)

internal enum class BenchmarkMode { INSPECT, PREFLIGHT, OFFLINE, CODEX, DIAGNOSTIC }

internal data class BenchmarkOptions(
    val mode: BenchmarkMode,
    val allowCodexEgress: Boolean,
    val chatEgressConsent: Boolean,
    val reportPath: Path?,
) {
    companion object {
        fun parse(args: Array<String>): BenchmarkOptions {
            var mode: BenchmarkMode? = null
            var allowCodexEgress = false
            var chatEgressConsent = false
            var reportPath: Path? = null
            val seen = mutableSetOf<String>()
            args.forEach { arg ->
                when {
                    arg.startsWith("--mode=") -> {
                        if (!seen.add("mode")) throw CliArgumentException("--mode задан повторно.")
                        mode = when (arg.removePrefix("--mode=")) {
                            "inspect" -> BenchmarkMode.INSPECT
                            "preflight" -> BenchmarkMode.PREFLIGHT
                            "offline" -> BenchmarkMode.OFFLINE
                            "codex" -> BenchmarkMode.CODEX
                            "diagnostic" -> BenchmarkMode.DIAGNOSTIC
                            else -> throw CliArgumentException(
                                "Допустимы режимы inspect, preflight, offline, diagnostic и codex.",
                            )
                        }
                    }
                    arg == "--allow-codex-egress" -> {
                        if (!seen.add("egress")) throw CliArgumentException("--allow-codex-egress задан повторно.")
                        allowCodexEgress = true
                    }
                    arg == "--chat-egress-consent" -> {
                        if (!seen.add("chat-egress-consent")) {
                            throw CliArgumentException("--chat-egress-consent задан повторно.")
                        }
                        chatEgressConsent = true
                    }
                    arg.startsWith("--report=") -> {
                        if (!seen.add("report")) throw CliArgumentException("--report задан повторно.")
                        val path = arg.removePrefix("--report=").takeIf(String::isNotBlank)
                            ?: throw CliArgumentException("--report требует путь.")
                        reportPath = Path.of(path)
                    }
                    else -> throw CliArgumentException("Неизвестный аргумент benchmark.")
                }
            }
            val selectedMode = mode ?: throw CliArgumentException("Задайте --mode.")
            val requiresCodexEgress = selectedMode in setOf(BenchmarkMode.CODEX, BenchmarkMode.DIAGNOSTIC)
            if (requiresCodexEgress && !allowCodexEgress) {
                throw CliArgumentException("Codex/diagnostic mode требует --allow-codex-egress.")
            }
            if (requiresCodexEgress && !chatEgressConsent) {
                throw CliArgumentException("--chat-egress-consent требует отдельного согласия на egress в чате.")
            }
            if (!requiresCodexEgress && allowCodexEgress) {
                throw CliArgumentException("--allow-codex-egress разрешён только в mode codex или diagnostic.")
            }
            if (!requiresCodexEgress && chatEgressConsent) {
                throw CliArgumentException("--chat-egress-consent разрешён только в mode codex или diagnostic.")
            }
            if (selectedMode in setOf(BenchmarkMode.INSPECT, BenchmarkMode.PREFLIGHT, BenchmarkMode.DIAGNOSTIC) &&
                reportPath != null
            ) {
                throw CliArgumentException("Inspect/preflight/diagnostic не создают отчёты.")
            }
            return BenchmarkOptions(selectedMode, allowCodexEgress, chatEgressConsent, reportPath)
        }
    }
}

internal class ImportBenchmarkPayloadPreviewException(
    val providerBody: String,
    val fingerprint: String,
) : IllegalStateException("Provider payload preview captured without sending a request.")
