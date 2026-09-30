package io.github.stolex1y.transactionimport.web

import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeliveryAcceptanceBrowserTest {
    @Test
    fun approvedCompoundImportUsesRunningApplicationAndExternalFakes() {
        val baseUrl = System.getenv("DELIVERY_BASE_URL")
        if (baseUrl.isNullOrBlank()) {
            check(System.getenv("DELIVERY_ACCEPTANCE_REQUIRED") != "true") {
                "G6 acceptance requires DELIVERY_BASE_URL."
            }
            return
        }
        val evidenceDirectory = System.getenv("DELIVERY_EVIDENCE_DIR")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)

        Playwright.create().use { playwright ->
            playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
                browser.newContext().use { context ->
                    val page = context.newPage()
                    page.navigate("$baseUrl/agent")
                    page.waitForFunction(
                        """
                            () => typeof initialize === 'function' &&
                                document.querySelector('#empty-new-session') &&
                                !document.querySelector('#empty-new-session').disabled
                        """.trimIndent(),
                    )

                    page.locator("#show-mcp-panel").click()
                    page.locator("#tbank-phone").fill("+79990000000")
                    page.locator("#tbank-submit").click()
                    page.waitForFunction(
                        "() => !document.querySelector('#tbank-otp-step').hidden",
                    )
                    page.locator("#tbank-otp").fill("000000")
                    page.locator("#tbank-submit").click()
                    page.waitForFunction(
                        "() => !document.querySelector('#tbank-password-field').hidden",
                    )
                    page.locator("#tbank-password").fill("demo")
                    page.locator("#tbank-submit").click()
                    page.waitForFunction(
                        "() => document.querySelector('#tbank-account').options.length >= 1",
                    )
                    page.locator("#open-session-drawer").click()
                    page.locator("#empty-new-session").click()
                    page.waitForFunction(
                        "() => document.querySelector('#agent-message') && !document.querySelector('#agent-message').disabled",
                    )

                    fun sendMessage(text: String) {
                        page.locator("#agent-message").fill(text)
                        val response = page.waitForResponse(
                            { candidate ->
                                candidate.request().method() == "POST" &&
                                    candidate.url().endsWith("/messages")
                            },
                            { page.locator("#send-message").click() },
                        )
                        assertTrue(
                            response.status() in 200..299,
                            "Сообщение завершилось ошибкой: ${page.locator("#agent-status").textContent()}",
                        )
                    }

                    sendMessage("Загрузи операции за сентябрь и найди соответствующие чеки")
                    val bankOnlyPreview = page.locator(".mcp-inline-preview").last()
                    bankOnlyPreview.waitFor()
                    assertEquals(2, bankOnlyPreview.locator("tbody tr").count())
                    val sourceFailure = bankOnlyPreview.locator(".receipt-matching-status")
                    assertEquals("source_error", sourceFailure.getAttribute("data-status"))
                    assertTrue(sourceFailure.textContent().contains("без сопоставления с чеками"))
                    capture(page, evidenceDirectory, "a03-bank-only-preview.png")
                    bankOnlyPreview.locator("button").last().click()
                    page.waitForFunction(
                        "() => document.querySelectorAll('#operation-table tbody tr').length === 2",
                    )

                    val bankFetchTool = "mcp_tbank-transactions_get-account-transactions"
                    val bankFetchCountAfterImport = providerToolCallCount(bankFetchTool)
                    assertEquals(1, bankFetchCountAfterImport)
                    page.locator("#show-mcp-panel").click()
                    page.locator("#receipts-browser-login").waitFor()
                    page.waitForFunction(
                        "() => document.querySelector('#receipts-auth-status')?.textContent === 'Нужно войти'",
                    )
                    page.locator("#receipts-browser-login").click()
                    page.waitForFunction(
                        "() => document.querySelector('#receipts-auth-status')?.textContent === 'Авторизация активна'",
                    )
                    page.locator("#open-session-drawer").click()
                    sendMessage("Привяжи чеки к операциям текущего черновика")
                    page.waitForFunction(
                        "() => document.querySelectorAll('#operation-table .receipt-association-status.matched').length > 0",
                    )
                    assertTrue(page.locator("#operation-table").textContent().contains("Демо товар"))
                    assertFalse(page.locator("#agent-workspace").textContent().contains("receipt-001"))
                    assertEquals(bankFetchCountAfterImport, providerToolCallCount(bankFetchTool))
                    capture(page, evidenceDirectory, "a03-late-association.png")

                    sendMessage("Измени описание первой операции на «Синтетическая покупка — проверка».")
                    val editedDescription =
                        page.locator("#operation-table tbody tr").first().locator("input[name='description']")
                    page.waitForFunction(
                        "() => document.querySelector('#operation-table tbody tr input[name=\"description\"]')?.value === " +
                            "'Синтетическая покупка — проверка'",
                    )
                    assertEquals("Синтетическая покупка — проверка", editedDescription.inputValue())

                    sendMessage("Предложи правило описания для операций ДЕМО МАРКЕТ, но пока не применяй")
                    val pendingRule = page.locator(".memory-candidate").last()
                    pendingRule.waitFor()
                    assertTrue(pendingRule.textContent().contains("Применено выбранное правило"))
                    assertEquals(1, pendingRule.locator("button").count())
                    sendMessage("Примени ранее предложенное правило к текущему draft")
                    page.waitForFunction(
                        "() => document.querySelector('.memory-candidate .candidate-status')?.textContent.includes('Добавлено в подтверждённые решения')",
                    )
                    page.waitForFunction(
                        "() => document.querySelector('#operation-table tbody tr input[name=\"description\"]')?.value === " +
                            "'Применено выбранное правило'",
                    )
                    assertTrue(page.locator(".memory-candidate .candidate-status").textContent().contains("Добавлено"))
                    capture(page, evidenceDirectory, "a02-contextual-rule-applied.png")

                    val draftBeforeInvalidPlan = page.locator("#operation-table").textContent()
                    page.locator("#agent-message").fill("Проверь безопасный отказ неизвестного инструмента")
                    val invalidPlanResponse = page.waitForResponse(
                        { candidate ->
                            candidate.request().method() == "POST" &&
                                candidate.url().endsWith("/messages")
                        },
                        { page.locator("#send-message").click() },
                    )
                    assertEquals(502, invalidPlanResponse.status())
                    page.waitForFunction(
                        "() => document.querySelector('#agent-status')?.classList.contains('error')",
                    )
                    assertTrue(page.locator("#agent-status").textContent().isNotBlank())
                    assertEquals(draftBeforeInvalidPlan, page.locator("#operation-table").textContent())
                    assertEquals(2, page.locator("#operation-table tbody tr").count())
                    capture(page, evidenceDirectory, "a04-invalid-plan-error.png")

                    page.locator("#new-session").click()
                    page.waitForFunction(
                        "() => document.querySelector('#agent-message') && !document.querySelector('#agent-message').disabled",
                    )
                    sendMessage("Импортируй операции за сентябрь и чеки к ним")

                    val preview = page.locator(".mcp-inline-preview")
                    preview.waitFor()
                    assertEquals(2, preview.locator("tbody tr").count())
                    assertEquals(1, preview.locator(".receipt-association-status.matched").count())
                    assertEquals(1, preview.locator(".receipt-association-status.unmatched").count())
                    val previewText = preview.textContent()
                    assertTrue(previewText.contains("ДЕМО МАРКЕТ"))
                    assertTrue(previewText.contains("Демо товар"))
                    assertTrue(previewText.contains("Без чека"))
                    assertFalse(previewText.contains("receipt-001"))
                    capture(page, evidenceDirectory, "a01-preview.png")

                    val confirm = preview.locator("button").last()
                    assertEquals("Принять операции", confirm.textContent())
                    confirm.click()
                    page.waitForFunction(
                        "() => document.querySelectorAll('#operation-table tbody tr').length === 2",
                    )
                    assertEquals(1, page.locator("#operation-table .receipt-association-status.matched").count())
                    assertEquals(1, page.locator("#operation-table .receipt-association-status.unmatched").count())
                    capture(page, evidenceDirectory, "a01-confirmed.png")

                    page.reload()
                    page.locator("#operation-table tbody tr").first().waitFor()
                    assertEquals(2, page.locator("#operation-table tbody tr").count())
                    assertEquals(1, page.locator("#operation-table .receipt-association-status.matched").count())
                    sendMessage("Импортируй операции за октябрь и чеки к ним")
                    page.waitForFunction(
                        """
                            () => Array.from(document.querySelectorAll('#message-list .message.assistant'))
                                .some(message => message.textContent.includes('Новых банковских операций не найдено'))
                        """.trimIndent(),
                    )
                    assertTrue(
                        page.locator("#message-list").textContent()
                            .contains("Сопоставление чеков с существующими операциями не выполнялось"),
                    )
                    assertEquals(2, page.locator("#operation-table tbody tr").count())
                    assertEquals(1, page.locator("#operation-table .receipt-association-status.matched").count())
                    assertEquals(1, page.locator("#operation-table .receipt-association-status.unmatched").count())

                    sendMessage("Добавь операцию со второго синтетического счёта за 11 сентября и чеки к ней")
                    val repeatedReceiptPreview = page.locator(".mcp-inline-preview").last()
                    repeatedReceiptPreview.waitFor()
                    assertEquals(1, repeatedReceiptPreview.locator("tbody tr").count())
                    assertEquals(
                        1,
                        repeatedReceiptPreview.locator(".receipt-association-status.ambiguous").count(),
                    )
                    assertEquals(
                        0,
                        repeatedReceiptPreview.locator(".receipt-association-status.matched").count(),
                    )
                    assertTrue(repeatedReceiptPreview.textContent().contains("Повторный счёт"))
                    assertFalse(repeatedReceiptPreview.textContent().contains("Демо товар"))
                    assertFalse(repeatedReceiptPreview.textContent().contains("receipt-001"))
                    capture(page, evidenceDirectory, "a06-ambiguous-preview.png")
                    repeatedReceiptPreview.locator("button").last().click()
                    page.waitForFunction(
                        "() => document.querySelectorAll('#operation-table tbody tr').length === 3",
                    )
                    assertEquals(1, page.locator("#operation-table .receipt-association-status.matched").count())
                    assertEquals(1, page.locator("#operation-table .receipt-association-status.unmatched").count())
                    assertEquals(1, page.locator("#operation-table .receipt-association-status.ambiguous").count())

                    page.reload()
                    page.waitForFunction(
                        "() => document.querySelectorAll('#operation-table tbody tr').length === 3",
                    )
                    assertEquals(1, page.locator("#operation-table .receipt-association-status.matched").count())
                    assertEquals(1, page.locator("#operation-table .receipt-association-status.ambiguous").count())
                    assertFalse(page.locator("#agent-workspace").textContent().contains("receipt-001"))
                }
            }
        }
    }

    private fun providerToolCallCount(name: String): Int {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:5013/stats"))
            .GET()
            .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Synthetic provider stats are unavailable." }
        return Json.parseToJsonElement(response.body())
            .jsonObject["tool_call_counts"]
            ?.jsonObject
            ?.get(name)
            ?.jsonPrimitive
            ?.intOrNull
            ?: 0
    }

    private fun capture(page: Page, directory: Path?, name: String) {
        if (directory == null) return
        Files.createDirectories(directory)
        page.screenshot(Page.ScreenshotOptions().setPath(directory.resolve(name)))
    }
}
