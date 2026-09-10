package io.github.stolex1y.transactionimport.web

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentBrowserTest {
    @Test
    fun userCanEditBothViewsAndRestorePersistentStateWithoutExternalApi() {
        val database = Files.createTempFile("agent-browser-", ".sqlite")
        val gateway = FakeAgentGateway()
        val port = ServerSocket(0).use { it.localPort }
        fun startServer() = embeddedServer(Netty, host = "127.0.0.1", port = port) {
            module(agentDependencies = fakeAgentDependencies(database.toString(), gateway))
        }.start(wait = false)

        var server = startServer()
        try {
            Playwright.create().use { playwright ->
                playwright.chromium().launch(
                    BrowserType.LaunchOptions().setHeadless(true),
                ).use { browser ->
                    browser.newContext(
                        Browser.NewContextOptions().setViewportSize(1_280, 900),
                    ).use { context ->
                        val page = context.newPage()
                        val baseUrl = "http://127.0.0.1:$port"
                        page.navigate("$baseUrl/agent")
                        page.locator("#empty-new-session").click()
                        page.locator("#agent-message").fill(
                            "Списание 1250,50 ₽ и неизвестный платёж 99 ₽",
                        )
                        page.locator("#send-message").click()
                        page.locator("#operation-table").waitFor()
                        assertEquals(
                            "08-02-2026 12:10",
                            page.locator("tr[data-transaction-id='1'] input[name='occurred_at']").inputValue(),
                        )
                        assertEquals(
                            "Ответ агента получен. Черновик обновлён.",
                            page.locator("#agent-status").textContent(),
                        )
                        assertEquals(1, page.locator("#metrics-table-body tr").count())
                        assertTrue(page.locator("#metrics-summary").textContent().contains("За сессию всего"))
                        page.locator("#agent-message").fill("уточнение ".repeat(700))
                        page.locator("#send-message").click()
                        page.waitForFunction(
                            "() => document.querySelectorAll('#metrics-table-body tr').length === 2",
                        )
                        val firstPromptTokens = page.locator("#metrics-table-body tr").nth(0)
                            .locator("td").nth(2).textContent().filter(Char::isDigit).toInt()
                        val secondPromptTokens = page.locator("#metrics-table-body tr").nth(1)
                            .locator("td").nth(2).textContent().filter(Char::isDigit).toInt()
                        assertTrue(secondPromptTokens > firstPromptTokens)
                        assertEquals(2, gateway.requests.size)
                        val initialTheme = page.evaluate(
                            "() => document.documentElement.dataset.theme",
                        ).toString()
                        if (initialTheme == "dark") page.locator("#theme-toggle").click()
                        assertEquals(
                            "light",
                            page.evaluate("() => document.documentElement.dataset.theme").toString(),
                        )
                        val lightStyleProbe = page.evaluate(
                            """
                                () => {
                                  const body = getComputedStyle(document.body);
                                  const toast = getComputedStyle(document.querySelector("#agent-status"));
                                  const json = getComputedStyle(document.querySelector("#batch-json"));
                                  const checkbox = getComputedStyle(document.querySelector("input[type='checkbox']"));
                                  return [
                                    body.colorScheme,
                                    body.getPropertyValue("--agent-muted").trim(),
                                    toast.backgroundColor,
                                    toast.borderTopColor,
                                    json.backgroundColor,
                                    checkbox.accentColor,
                                  ].join("|");
                                }
                            """.trimIndent(),
                        ).toString()
                        assertTrue(lightStyleProbe.startsWith("light|#475569|"))
                        assertTrue(!lightStyleProbe.contains("|rgba(0, 0, 0, 0)|"))
                        assertTrue(!lightStyleProbe.endsWith("|auto"))
                        assertTrue(
                            page.locator("tr[data-transaction-id='1'] td:nth-child(2)")
                                .boundingBox()
                                .let { it != null && it.width < 100 },
                        )
                        page.waitForFunction(
                            "() => document.querySelector('#agent-status')?.textContent === ''",
                        )

                        assertEquals("table", page.locator("#draft-editor").getAttribute("data-view"))
                        assertEquals(0, page.locator(".operation-card").count())
                        assertEquals(
                            "1",
                            page.locator("tr[data-transaction-id='1'] .transaction-id").textContent(),
                        )
                        assertTrue(page.locator("#build-batch").isDisabled)
                        assertTrue(
                            page.locator("tr[data-transaction-id='2'] .field-error").count() > 0,
                        )

                        assertTrue(
                            page.evaluate(
                                """
                                    () => {
                                      const row = document.querySelector("tr[data-transaction-id='2']");
                                      const cells = [...row.querySelectorAll("td")];
                                      const invalid = cells.filter(cell => cell.classList.contains("invalid"));
                                      const invalidShadows = invalid.filter(
                                          cell => getComputedStyle(cell).boxShadow !== "none",
                                      );
                                      const neighborShadows = cells.filter(
                                          cell => !cell.classList.contains("invalid"),
                                      ).filter(cell => getComputedStyle(cell).boxShadow !== "none");
                                      return invalid.length > 0 &&
                                          invalidShadows.length === invalid.length &&
                                          neighborShadows.length === 0;
                                    }
                                """.trimIndent(),
                            ) as Boolean,
                        )
                        val selectStyle = page.evaluate(
                            """
                                () => {
                                  const select = document.querySelector(
                                      "tr[data-transaction-id='1'] select[name='category_id']",
                                  );
                                  const style = getComputedStyle(select);
                                  return `${'$'}{style.paddingRight}|${'$'}{style.textOverflow}|${'$'}{style.overflow}`;
                                }
                            """.trimIndent(),
                        ).toString()
                        assertTrue(selectStyle.startsWith("32px|ellipsis|"), selectStyle)

                        val firstTableRow = page.locator("tr[data-transaction-id='1']")
                        firstTableRow.locator("input[name='occurred_at']").fill("09-02-2026 13:20")
                        firstTableRow.locator("input[name='amount']").fill("6.60.1")
                        firstTableRow.locator("button[data-action='save-operation']").click()
                        assertEquals(
                            "Сумма должна быть числом с допустимым количеством знаков после запятой.",
                            page.locator("#agent-status").textContent(),
                        )
                        assertTrue(page.locator("#agent-status").getAttribute("class").contains("error"))
                        page.waitForFunction(
                            "() => document.querySelector('#agent-status')?.textContent === ''",
                        )
                        firstTableRow.locator("input[name='amount']").fill("1251,25")
                        firstTableRow.locator("input[name='description']").fill("Проверено в браузере")
                        assertTrue(page.locator("#build-batch").isDisabled)

                        page.locator("#card-view").click()
                        waitForView(page, "cards")
                        var firstCard = page.locator("article[data-transaction-id='1']")
                        assertEquals("1251,25", firstCard.locator("input[name='amount']").inputValue())
                        assertEquals(
                            "Проверено в браузере",
                            firstCard.locator("input[name='description']").inputValue(),
                        )

                        var secondCard = page.locator("article[data-transaction-id='2']")
                        assertTrue(secondCard.locator(".field-error").count() > 0)
                        secondCard.locator("input[name='included']").uncheck()

                        page.locator("#table-view").click()
                        waitForView(page, "table")
                        val unsavedSecondRow = page.locator("tr[data-transaction-id='2']")
                        assertFalse(unsavedSecondRow.locator("input[name='included']").isChecked)
                        assertEquals(0, unsavedSecondRow.locator(".field-error").count())
                        assertEquals(
                            "Проверено в браузере",
                            page.locator("tr[data-transaction-id='1'] input[name='description']").inputValue(),
                        )

                        page.locator("#card-view").click()
                        waitForView(page, "cards")
                        secondCard = page.locator("article[data-transaction-id='2']")
                        assertFalse(secondCard.locator("input[name='included']").isChecked)
                        secondCard.locator("button[data-action='save-operation']").click()

                        page.locator("#table-view").click()
                        waitForView(page, "table")
                        val restoredFirstRow = page.locator("tr[data-transaction-id='1']")
                        assertEquals(
                            "Проверено в браузере",
                            restoredFirstRow.locator("input[name='description']").inputValue(),
                        )
                        restoredFirstRow.locator("button[data-action='save-operation']").click()
                        page.waitForFunction(
                            "() => document.querySelector('#build-batch')?.disabled === false",
                        )
                        assertEquals(
                            "09-02-2026 13:20",
                            page.locator("tr[data-transaction-id='1'] input[name='occurred_at']").inputValue(),
                        )
                        assertFalse(page.locator("#build-batch").isDisabled)

                        page.locator("#build-batch").click()
                        page.locator("#batch-result:not([hidden])").waitFor()
                        val transactions = JSON.parseToJsonElement(
                            page.locator("#batch-json").textContent(),
                        ).jsonObject["transactions"]!!.jsonArray
                        assertEquals(1, transactions.size)
                        assertEquals(
                            125125L,
                            transactions.single().jsonObject["amount_minor"]!!.jsonPrimitive.content.toLong(),
                        )
                        assertEquals(
                            "Проверено в браузере",
                            transactions.single().jsonObject["description"]!!.jsonPrimitive.content,
                        )

                        page.locator("#card-view").click()
                        page.reload()
                        waitForView(page, "cards")
                        firstCard = page.locator("article[data-transaction-id='1']")
                        assertEquals(
                            "Проверено в браузере",
                            firstCard.locator("input[name='description']").inputValue(),
                        )

                        page.locator("#table-view").click()
                        waitForView(page, "table")
                        assertEquals(
                            "table",
                            page.evaluate("() => localStorage.getItem('smart-expense-operation-view')"),
                        )
                        page.setViewportSize(375, 900)
                        waitForView(page, "cards")
                        assertEquals(
                            "table",
                            page.evaluate("() => localStorage.getItem('smart-expense-operation-view')"),
                        )
                        assertTrue(
                            page.evaluate(
                                "() => document.documentElement.scrollWidth <= document.documentElement.clientWidth",
                            ) as Boolean,
                        )
                        page.setViewportSize(1_280, 900)
                        waitForView(page, "table")

                        server.stop(1_000, 5_000)
                        server = startServer()
                        page.locator("#operation-table").waitFor()
                        assertEquals(2, page.locator("#metrics-table-body tr").count())
                        assertTrue(page.locator("#metrics-summary").textContent().contains("За сессию всего"))
                        assertEquals(
                            "Проверено в браузере",
                            page.locator("tr[data-transaction-id='1'] input[name='description']").inputValue(),
                        )
                        assertFalse(
                            page.locator("tr[data-transaction-id='2'] input[name='included']").isChecked,
                        )
                        assertEquals(2, gateway.requests.size)

                        page.onDialog { dialog -> dialog.accept() }
                        page.locator("#open-session-drawer").click()
                        page.locator("#delete-session").click()
                        page.locator("#empty-session:not([hidden])").waitFor()
                        assertEquals(0, page.locator("#session-list .session-item").count())
                    }
                }
            }
        } finally {
            server.stop(1_000, 5_000)
            database.deleteIfExists()
        }
    }

    @Test
    fun appendsSecondStatementWithDeduplicationAndPersistence() {
        val database = Files.createTempFile("agent-browser-append-", ".sqlite")
        val gateway = FakeAgentGateway(
            responses = ArrayDeque(
                listOf(
                    FakeAgentGateway.READY_DRAFT_JSON,
                    FakeAgentGateway.APPEND_STATEMENT_JSON,
                ),
            ),
        )
        val port = ServerSocket(0).use { it.localPort }
        fun startServer() = embeddedServer(Netty, host = "127.0.0.1", port = port) {
            module(agentDependencies = fakeAgentDependencies(database.toString(), gateway))
        }.start(wait = false)

        var server = startServer()
        try {
            Playwright.create().use { playwright ->
                playwright.chromium().launch(
                    BrowserType.LaunchOptions().setHeadless(true),
                ).use { browser ->
                    browser.newContext(
                        Browser.NewContextOptions().setViewportSize(1_280, 900),
                    ).use { context ->
                        val page = context.newPage()
                        val baseUrl = "http://127.0.0.1:$port"
                        page.navigate("$baseUrl/agent")
                        page.locator("#empty-new-session").click()
                        page.locator("#agent-message").fill("Первая выписка")
                        page.locator("#send-message").click()
                        page.locator("#operation-table").waitFor()
                        assertEquals(2, page.locator("#operation-table tbody tr").count())

                        page.locator("#agent-message").fill("Вторая выписка")
                        page.locator("#send-message").click()
                        page.waitForFunction(
                            "() => document.querySelectorAll('#operation-table tbody tr').length === 3",
                        )
                        assertEquals(
                            listOf("1", "2", "3"),
                            (0 until 3).map { index ->
                                page.locator("#operation-table tbody tr").nth(index)
                                    .getAttribute("data-transaction-id")
                            },
                        )
                        assertEquals(
                            "НОВЫЙ КАФЕ",
                            page.locator("tr[data-transaction-id='3'] input[name='merchant']").inputValue(),
                        )
                        assertTrue(
                            page.locator("#message-list .assistant").nth(1).textContent()
                                .contains("Добавлено операций: 1"),
                        )
                        assertTrue(
                            page.locator("#message-list .assistant").nth(1).textContent()
                                .contains("Пропущено точных дубликатов: 1"),
                        )
                        assertEquals(2, page.locator("#metrics-table-body tr").count())

                        server.stop(1_000, 5_000)
                        server = startServer()
                        page.reload()
                        page.locator("#operation-table").waitFor()
                        assertEquals(3, page.locator("#operation-table tbody tr").count())
                        assertEquals(2, page.locator("#metrics-table-body tr").count())
                        assertEquals(
                            "НОВЫЙ КАФЕ",
                            page.locator("tr[data-transaction-id='3'] input[name='merchant']").inputValue(),
                        )
                        assertEquals(2, gateway.requests.size)
                    }
                }
            }
        } finally {
            server.stop(1_000, 5_000)
            database.deleteIfExists()
        }
    }

    @Test
    fun reportsContextOverflowWithoutChangingDraftOrConversation() {
        val database = Files.createTempFile("agent-browser-overflow-", ".sqlite")
        val gateway = FakeAgentGateway()
        val port = ServerSocket(0).use { it.localPort }
        fun startServer() = embeddedServer(Netty, host = "127.0.0.1", port = port) {
            module(agentDependencies = fakeAgentDependencies(database.toString(), gateway))
        }.start(wait = false)

        var server = startServer()
        try {
            Playwright.create().use { playwright ->
                playwright.chromium().launch(
                    BrowserType.LaunchOptions().setHeadless(true),
                ).use { browser ->
                    browser.newContext(
                        Browser.NewContextOptions().setViewportSize(1_280, 900),
                    ).use { context ->
                        val page = context.newPage()
                        val baseUrl = "http://127.0.0.1:$port"
                        page.navigate("$baseUrl/agent")
                        page.locator("#empty-new-session").click()
                        page.locator("#agent-message").fill("Списание 1250 ₽")
                        page.locator("#send-message").click()
                        page.locator("#operation-table").waitFor()

                        val messagesBeforeOverflow = page.locator("#message-list .message").count()
                        page.locator("#agent-message").fill("длинный ".repeat(5_000))
                        page.locator("#send-message").click()
                        page.waitForFunction(
                            "() => document.querySelector('#agent-status')?.textContent?.includes('Диалог превысил')",
                        )

                        assertEquals(messagesBeforeOverflow, page.locator("#message-list .message").count())
                        assertEquals(2, page.locator("#operation-table tbody tr").count())
                        assertEquals(2, page.locator("#metrics-table-body tr").count())
                        assertEquals(
                            "Переполнение контекста",
                            page.locator("#metrics-table-body tr").nth(1).locator("td").nth(1).textContent(),
                        )
                        assertTrue(page.locator("#agent-message").inputValue().startsWith("длинный"))

                        page.reload()
                        page.locator("#operation-table").waitFor()
                        assertEquals(2, page.locator("#operation-table tbody tr").count())
                        assertEquals(2, page.locator("#metrics-table-body tr").count())
                    }
                }
            }
        } finally {
            server.stop(1_000, 5_000)
            database.deleteIfExists()
        }
    }

    private fun waitForView(page: Page, view: String) {
        page.waitForFunction(
            "expected => document.querySelector('#draft-editor')?.dataset.view === expected",
            view,
        )
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
