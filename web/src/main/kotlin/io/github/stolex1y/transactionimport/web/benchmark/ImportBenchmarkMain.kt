package io.github.stolex1y.transactionimport.web.benchmark

import io.github.stolex1y.transactionimport.core.ChatCompletionGateway
import io.github.stolex1y.transactionimport.core.ChatCompletionRequest
import io.github.stolex1y.transactionimport.core.ChatCompletionResponse
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
        println("Benchmark отменён. Preview не был подтверждён; временная база удалена.")
    } catch (error: CliArgumentException) {
        System.err.println(error.message)
        System.err.println(
            "Использование: --mode=inspect | --mode=preflight | --mode=offline | " +
                "--mode=codex --allow-codex-egress --chat-egress-consent [--report=PATH]",
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
            "Benchmark остановлен без сохранения результата (${error::class.simpleName ?: "ошибка"}); " +
                "содержимое запросов, ответов и fixture скрыто.",
        )
        exitProcess(1)
    } finally {
        input.close()
    }
}

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
        BenchmarkMode.CODEX -> runCodex(fixture, options, input)
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
    input: BenchmarkConsoleInput,
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
                    approveFirstRequest = CodexRequestApproval { body, fingerprint, routingSummary ->
                        println("Повтор $repetition: маршрут OMP: $routingSummary")
                        println("Повтор $repetition: точный provider-facing request перед отправкой")
                        println("SHA-256: $fingerprint")
                        println(body)
                        println(
                            "Введите SEND только после отдельного согласия в чате; любой другой ввод остановит benchmark.",
                        )
                        input.readLine() == "SEND"
                    },
                )
                activeSession = session
                println("Повтор $repetition: локальный OMP gateway ${session.metadata().gatewayVersion} готов; fallback проверен.")
                session.gateway()
            },
            confirmPreview = { repetition, summary ->
                println(
                    "Повтор $repetition: операции ${summary.transactionCount}, категоризировано ${summary.categorizedCount}; " +
                        "чеки: совпало ${summary.matchedReceiptCount}, неоднозначно ${summary.ambiguousReceiptCount}, " +
                        "не сопоставлено ${summary.unmatchedReceiptCount}.",
                )
                println("После подтверждения production confirm() создаст draft только во временной БД, затем база будет удалена.")
                println("Введите CONFIRM, чтобы подтвердить этот preview и оценить inclusion; любой другой ввод отменит прогон.")
                input.readLine() == "CONFIRM"
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

private class CliArgumentException(message: String) : IllegalArgumentException(message)

internal enum class BenchmarkMode { INSPECT, PREFLIGHT, OFFLINE, CODEX }

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
                            else -> throw CliArgumentException("Допустимы режимы inspect, preflight, offline и codex.")
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
            if (selectedMode == BenchmarkMode.CODEX && !allowCodexEgress) {
                throw CliArgumentException("Codex mode требует отдельный флаг --allow-codex-egress.")
            }
            if (selectedMode == BenchmarkMode.CODEX && !chatEgressConsent) {
                throw CliArgumentException(
                    "--chat-egress-consent допустим только после отдельного согласия на egress в чате.",
                )
            }
            if (selectedMode != BenchmarkMode.CODEX && allowCodexEgress) {
                throw CliArgumentException("--allow-codex-egress разрешён только с --mode=codex.")
            }
            if (selectedMode != BenchmarkMode.CODEX && chatEgressConsent) {
                throw CliArgumentException("--chat-egress-consent разрешён только с --mode=codex.")
            }
            if (selectedMode in setOf(BenchmarkMode.INSPECT, BenchmarkMode.PREFLIGHT) && reportPath != null) {
                throw CliArgumentException("Inspect/preflight не создают отчёты.")
            }
            return BenchmarkOptions(selectedMode, allowCodexEgress, chatEgressConsent, reportPath)
        }
    }
}

internal class ImportBenchmarkPayloadPreviewException(
    val providerBody: String,
    val fingerprint: String,
) : IllegalStateException("Provider payload preview captured without sending a request.")
