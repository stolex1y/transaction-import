package io.github.stolex1y.transactionimport.web

import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalProviderAcceptanceBrowserTest {
    @Test
    fun selectedLocalProviderCompletesImportAndRetainsSessionChoice() {
        val baseUrl = acceptanceBaseUrl() ?: return
        Playwright.create().use { playwright ->
            playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
                browser.newContext().use { context ->
                    val page = context.newPage()
                    page.navigate("$baseUrl/agent")
                    waitForAgentInitialized(page)
                    page.locator("#new-session").click()
                    waitForSessionReady(page)
                    selectLocalProvider(page)

                    val response = sendMessage(page, SYNTHETIC_STATEMENT)
                    assertTrue(
                        response.status() in 200..299,
                        "Локальный импорт завершился ошибкой: ${page.locator("#agent-status").textContent()}",
                    )
                    page.waitForFunction(
                        "() => document.querySelectorAll('#operation-table tbody tr').length === 2",
                    )
                    assertEquals(2, page.locator("#operation-table tbody tr").count())
                    assertEquals(
                        "ДЕМО МАРКЕТ",
                        page.locator("tr[data-transaction-id='1'] input[name='merchant']").inputValue(),
                    )
                    assertTrue(page.locator("#message-list .assistant").count() > 0)
                    assertTrue(page.locator("#message-list .assistant").last().textContent().isNotBlank())
                    capture(page, "d27-local-provider-import.png")

                    page.reload()
                    waitForSessionReady(page)
                    assertLocalProviderSelected(page)

                    page.locator("#new-session").click()
                    waitForSessionReady(page)
                    page.waitForFunction(
                        "() => document.querySelector('#agent-provider')?.value === 'deepseek'",
                    )
                    assertEquals("deepseek", page.locator("#agent-provider").inputValue())
                }
            }
        }
    }

    @Test
    fun comparesSameSyntheticInputAcrossProviderSessions() {
        val baseUrl = acceptanceBaseUrl() ?: return
        Playwright.create().use { playwright ->
            playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
                browser.newContext().use { context ->
                    val page = context.newPage()
                    page.navigate("$baseUrl/agent")
                    waitForAgentInitialized(page)
                    page.locator("#new-session").click()
                    waitForSessionReady(page)
                    assertEquals("deepseek", page.locator("#agent-provider").inputValue())
                    assertEquals("deepseek-flash", page.locator("#agent-model").inputValue())
                    val deepseekSessionId = page.evaluate("() => sessionIdFromLocation()").toString()

                    val deepseekResponse = sendMessage(page, SYNTHETIC_STATEMENT)
                    assertTrue(
                        deepseekResponse.status() in 200..299,
                        "Fake DeepSeek import failed: ${page.locator("#agent-status").textContent()}",
                    )
                    page.waitForFunction(
                        "() => document.querySelectorAll('#operation-table tbody tr').length === 1",
                    )
                    val deepseekDraft = visibleDraftFingerprint(page)
                    assertEquals(
                        "ТЕСТОВЫЙ DEEPSEEK",
                        page.locator("tr[data-transaction-id='1'] input[name='merchant']").inputValue(),
                    )
                    assertTrue(page.locator("#message-list .assistant").count() > 0)
                    capture(page, "d27-provider-comparison-deepseek.png")

                    page.locator("#new-session").click()
                    waitForSessionReady(page)
                    selectLocalProvider(page)
                    val ollamaSessionId = page.evaluate("() => sessionIdFromLocation()").toString()
                    assertTrue(deepseekSessionId != ollamaSessionId)

                    val ollamaResponse = sendMessage(page, SYNTHETIC_STATEMENT)
                    assertTrue(
                        ollamaResponse.status() in 200..299,
                        "Fake Ollama import failed: ${page.locator("#agent-status").textContent()}",
                    )
                    page.waitForFunction(
                        "() => document.querySelectorAll('#operation-table tbody tr').length === 2",
                    )
                    val ollamaDraft = visibleDraftFingerprint(page)
                    assertEquals(
                        "ДЕМО МАРКЕТ",
                        page.locator("tr[data-transaction-id='1'] input[name='merchant']").inputValue(),
                    )
                    assertTrue(!ollamaDraft.contains("ТЕСТОВЫЙ DEEPSEEK"))
                    assertTrue(deepseekDraft != ollamaDraft)
                    capture(page, "d27-provider-comparison-ollama.png")

                    page.navigate("$baseUrl/agent/sessions/$deepseekSessionId")
                    waitForAgentInitialized(page)
                    waitForSessionReady(page)
                    assertEquals("deepseek", page.locator("#agent-provider").inputValue())
                    assertEquals("deepseek-flash", page.locator("#agent-model").inputValue())
                    assertEquals(
                        "ТЕСТОВЫЙ DEEPSEEK",
                        page.locator("tr[data-transaction-id='1'] input[name='merchant']").inputValue(),
                    )

                    page.navigate("$baseUrl/agent/sessions/$ollamaSessionId")
                    waitForAgentInitialized(page)
                    waitForSessionReady(page)
                    assertLocalProviderSelected(page)
                    assertEquals(
                        "ДЕМО МАРКЕТ",
                        page.locator("tr[data-transaction-id='1'] input[name='merchant']").inputValue(),
                    )
                }
            }
        }
    }

    @Test
    fun localProviderFailureShowsErrorWithoutFallbackOrDraftMutation() {
        val baseUrl = acceptanceBaseUrl() ?: return
        Playwright.create().use { playwright ->
            playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
                browser.newContext().use { context ->
                    val page = context.newPage()
                    page.navigate("$baseUrl/agent")
                    waitForAgentInitialized(page)
                    page.locator("#new-session").click()
                    waitForSessionReady(page)
                    selectLocalProvider(page)

                    val initialResponse = sendMessage(page, SYNTHETIC_STATEMENT)
                    assertTrue(initialResponse.status() in 200..299)
                    page.waitForFunction(
                        "() => document.querySelectorAll('#operation-table tbody tr').length === 2",
                    )
                    val draftBeforeFailure = visibleDraftFingerprint(page)

                    val failedResponse = sendMessage(page, "SIMULATE_LOCAL_PROVIDER_FAILURE")
                    assertTrue(failedResponse.status() !in 200..299)
                    page.waitForFunction(
                        "() => document.querySelector('#agent-status')?.classList.contains('error') === true",
                    )
                    assertTrue(page.locator("#agent-status").textContent().isNotBlank())
                    assertEquals(draftBeforeFailure, visibleDraftFingerprint(page))
                    assertLocalProviderSelected(page)
                    capture(page, "d27-local-provider-failure.png")
                }
            }
        }
    }

    @Test
    fun realLocalProviderCompletesImport() {
        if (System.getenv("LOCAL_PROVIDER_REAL_REQUIRED") != "true") return
        val baseUrl = requireNotNull(System.getenv("LOCAL_PROVIDER_ACCEPTANCE_BASE_URL")) {
            "Real local-provider smoke requires LOCAL_PROVIDER_ACCEPTANCE_BASE_URL."
        }.trimEnd('/')
        Playwright.create().use { playwright ->
            playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
                browser.newContext().use { context ->
                    val page = context.newPage()
                    page.setDefaultTimeout(180_000.0)
                    page.navigate("$baseUrl/agent")
                    waitForAgentInitialized(page)
                    page.locator("#new-session").click()
                    waitForSessionReady(page)
                    selectLocalProvider(page)

                    val response = sendMessage(page, SYNTHETIC_STATEMENT)
                    assertTrue(
                        response.status() in 200..299,
                        "Реальный локальный импорт завершился ошибкой: ${page.locator("#agent-status").textContent()}",
                    )
                    page.locator("#operation-table tbody tr").first().waitFor()
                    assertTrue(page.locator("#operation-table tbody tr").count() >= 1)
                    assertTrue(page.locator("#message-list .assistant").count() > 0)
                    assertLocalProviderSelected(page)
                    capture(page, "d27-local-provider-real-import.png")
                }
            }
        }
    }

    private fun selectLocalProvider(page: Page) {
        val response = page.waitForResponse(
            { candidate ->
                candidate.request().method() == "PUT" && candidate.url().endsWith("/config")
            },
            { page.locator("#agent-provider").selectOption("ollama") },
        )
        assertEquals(200, response.status())
        page.waitForFunction(
            "() => document.querySelector('#agent-provider')?.value === 'ollama' && " +
                "document.querySelector('#agent-model')?.value === 'qwen2.5:3b' && " +
                "document.querySelector('#agent-reasoning')?.value === 'disabled'",
        )
        assertLocalProviderSelected(page)
    }

    private fun assertLocalProviderSelected(page: Page) {
        assertEquals("ollama", page.locator("#agent-provider").inputValue())
        assertEquals("qwen2.5:3b", page.locator("#agent-model").inputValue())
        assertEquals("disabled", page.locator("#agent-reasoning").inputValue())
    }

    private fun sendMessage(page: Page, text: String) = page.waitForResponse(
        { candidate ->
            candidate.request().method() == "POST" && candidate.url().endsWith("/messages")
        },
        {
            page.locator("#agent-message").fill(text)
            page.locator("#send-message").click()
        },
    )

    private fun waitForAgentInitialized(page: Page) {
        page.waitForFunction(
            "() => typeof initialize === 'function' && " +
                "document.querySelector('#empty-new-session') && " +
                "!document.querySelector('#empty-new-session').disabled",
        )
    }

    private fun waitForSessionReady(page: Page) {
        page.waitForFunction(
            "() => document.querySelector('#agent-message') && " +
                "!document.querySelector('#agent-message').disabled",
        )
        assertTrue(page.locator("#agent-workspace").isVisible)
    }

    private fun visibleDraftFingerprint(page: Page): String =
        page.evaluate(
            "() => JSON.stringify(Array.from(document.querySelectorAll(" +
                "'#operation-table tbody input, #operation-table tbody select'), " +
                "control => [control.getAttribute('name'), control.value, control.checked]))",
        ).toString()

    private fun acceptanceBaseUrl(): String? {
        val baseUrl = System.getenv("LOCAL_PROVIDER_ACCEPTANCE_BASE_URL")
            ?.takeIf(String::isNotBlank)
            ?.trimEnd('/')
        if (baseUrl == null) {
            check(System.getenv("LOCAL_PROVIDER_ACCEPTANCE_REQUIRED") != "true") {
                "Local-provider browser acceptance requires LOCAL_PROVIDER_ACCEPTANCE_BASE_URL."
            }
        }
        return baseUrl
    }

    private fun capture(page: Page, name: String) {
        val evidenceDirectory = System.getenv("DELIVERY_EVIDENCE_DIR")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?: return
        Files.createDirectories(evidenceDirectory)
        page.screenshot(
            Page.ScreenshotOptions()
                .setPath(evidenceDirectory.resolve(name))
                .setFullPage(true),
        )
    }

    private companion object {
        const val SYNTHETIC_STATEMENT =
            "Выписка: 08.02.2026 12:10 ДЕМО МАРКЕТ -1250,50 RUB; " +
                "08.02.2026 18:45 НЕИЗВЕСТНЫЙ ПЛАТЁЖ -99,00 RUB"
    }
}
