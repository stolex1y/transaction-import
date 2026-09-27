package io.github.stolex1y.transactionimport.web

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import io.github.stolex1y.transactionimport.core.ContextManagementConfig
import io.github.stolex1y.transactionimport.core.ContextStrategy
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
                        waitForAgentInitialized(page)
                        assertTrue(
                            page.evaluate(
                                """
                                    () => {
                                      const form = document.querySelector("#tbank-query-form");
                                      const event = new Event("submit", { bubbles: true, cancelable: true });
                                      form.dispatchEvent(event);
                                      return event.defaultPrevented;
                                    }
                                """.trimIndent(),
                            ) as Boolean,
                        )
                        assertEquals(
                            1,
                            page.evaluate(
                                """() => normalizeTbankAccounts({
                                    content: [{
                                        text: JSON.stringify({
                                            data: { accounts: [{ id: 'account-1', account_name: 'Основной счёт' }] }
                                        })
                                    }]
                                }).length""",
                            ).toString().toInt(),
                        )
                        val emptyAccountsStatus = page.evaluate(
                            """
                                async () => {
                                  const originalFetch = window.fetch;
                                  window.fetch = async () => new Response(
                                    JSON.stringify({ is_error: false, text: "[]" }),
                                    { status: 200, headers: { "Content-Type": "application/json" } },
                                  );
                                  try {
                                    const result = await loadTbankAccounts();
                                    return JSON.stringify({
                                      result,
                                      className: document.querySelector("#tbank-login-status").className,
                                      text: document.querySelector("#tbank-login-status").textContent,
                                    });
                                  } finally {
                                    window.fetch = originalFetch;
                                  }
                                }
                            """.trimIndent(),
                        ).toString()
                        assertTrue(emptyAccountsStatus.contains("\"result\":false"))
                        assertTrue(emptyAccountsStatus.contains("control-note error"))
                        assertTrue(emptyAccountsStatus.contains("session отвечает"))
                        page.locator("#empty-new-session").click()
                        waitForSessionReady(page)
                        assertTrue(page.locator("#context-strategy-note").textContent().contains("Summary"))
                        assertTrue(page.locator("#fork-session").isHidden)
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
                            "Покупка яблок",
                            page.locator("tr[data-transaction-id='1'] input[name='description']").inputValue(),
                        )
                        assertEquals(
                            "Ответ агента получен. Черновик обновлён.",
                            page.locator("#agent-status").textContent(),
                        )
                        page.evaluate(
                            """
                                () => renderMcpPreview({
                                  id: "browser-preview",
                                  session_revision: 1,
                                  transactions: [{
                                    occurred_at: "2026-02-08",
                                    amount_minor: -499,
                                    currency: "RUB",
                                    merchant: "Булочная Ф. Вол",
                                    description: "Свежая выпечка",
                                    category_id: "food.cafe",
                                    category_issue: null,
                                    source_label: "Основной счёт"
                                  }]
                                })
                            """.trimIndent(),
                        )
                        assertEquals(0, page.locator("#mcp-preview-panel").count())
                        assertEquals(1, page.locator("#message-list .message.assistant .mcp-inline-preview").count())
                        assertEquals(6, page.locator(".mcp-inline-preview-table th").count())
                        assertEquals("Принять операции", page.locator(".mcp-inline-preview button").last().textContent())
                        page.evaluate("() => renderMcpPreview(null)")
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
                        val memoryLayer = page.locator(".memory-layer").first()
                        memoryLayer.waitFor()
                        val lightStyleProbe = page.evaluate(
                            """
                                () => {
                                  const body = getComputedStyle(document.body);
                                  const toast = getComputedStyle(document.querySelector("#agent-status"));
                                  const memory = getComputedStyle(document.querySelector(".memory-layer"));
                                  const checkbox = getComputedStyle(document.querySelector("input[type='checkbox']"));
                                  return [
                                    body.colorScheme,
                                    body.getPropertyValue("--agent-muted").trim(),
                                    toast.backgroundColor,
                                    toast.borderTopColor,
                                    memory.backgroundColor,
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
                        memoryLayer.locator(".memory-layer-toggle").hover()
                        val lightMemoryHover = page.evaluate(
                            """
                                () => {
                                  const card = getComputedStyle(document.querySelector(".memory-layer"));
                                  const toggle = getComputedStyle(document.querySelector(".memory-layer-toggle"));
                                  return card.backgroundColor + "|" + toggle.color;
                                }
                            """.trimIndent(),
                        ).toString()
                        assertEquals(
                            "rgb(219, 234, 254)|rgb(23, 32, 51)",
                            lightMemoryHover,
                            "Unexpected light memory hover style: $lightMemoryHover",
                        )
                        page.locator("#theme-toggle").click()
                        memoryLayer.locator(".memory-layer-toggle").hover()
                        val darkMemoryHover = page.evaluate(
                            """
                                () => {
                                  const card = getComputedStyle(document.querySelector(".memory-layer"));
                                  const toggle = getComputedStyle(document.querySelector(".memory-layer-toggle"));
                                  return card.backgroundColor + "|" + toggle.color;
                                }
                            """.trimIndent(),
                        ).toString()
                        assertEquals(
                            "rgb(23, 37, 84)|rgb(229, 231, 235)",
                            darkMemoryHover,
                            "Unexpected dark memory hover style: $darkMemoryHover",
                        )
                        page.waitForFunction(
                            "() => document.querySelector('#agent-status')?.textContent === ''",
                        )

                        assertEquals("table", page.locator("#draft-editor").getAttribute("data-view"))
                        assertEquals(12, page.locator("#operation-table thead th").count())
                        assertFalse(page.locator("#operation-table thead th").allTextContents().contains("Карта"))
                        assertEquals(0, page.locator("input[name='card_last4']").count())
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
                        assertEquals(0, page.locator("input[name='card_last4']").count())

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

                        val download = page.waitForDownload {
                            page.locator("#build-batch").click()
                        }
                        assertTrue(download.suggestedFilename().endsWith(".json"))
                        val transactions = JSON.parseToJsonElement(
                            Files.readString(download.path()),
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
                        assertFalse(transactions.single().jsonObject.containsKey("card_last4"))

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
    fun showsMemoryRoutingAndConfirmedDecisionInBrowser() {
        val database = Files.createTempFile("agent-memory-browser-", ".sqlite")
        val gateway = FakeAgentGateway(
            ArrayDeque(
                listOf(
                    FakeAgentGateway.READY_DRAFT_WITH_MEMORY_CANDIDATE_JSON,
                    FakeAgentGateway.FOLLOW_UP_NOOP_JSON,
                ),
            ),
        )
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
            module(agentDependencies = fakeAgentDependencies(database.toString(), gateway))
        }.start(wait = false)
        try {
            Playwright.create().use { playwright ->
                playwright.chromium().launch(
                    BrowserType.LaunchOptions().setHeadless(true),
                ).use { browser ->
                    browser.newContext(
                        Browser.NewContextOptions()
                            .setPermissions(listOf("clipboard-read", "clipboard-write")),
                    ).use { context ->
                        val page = context.newPage()
                        val baseUrl = "http://127.0.0.1:$port"
                        page.navigate("$baseUrl/agent")
                        waitForAgentInitialized(page)
                        assertEquals(0, page.locator("#download-memory-report").count())

                        page.locator("#open-session-drawer").click()
                        page.locator("#global-user-prompt").fill("Профиль для memory browser")
                        page.locator("#save-preferences").click()
                        page.waitForFunction(
                            "() => document.querySelector('#agent-status')?.textContent === 'Общие инструкции сохранены.'",
                        )

                        page.locator("#merchant-rule-canonical-name").fill("У дома")
                        page.locator("#merchant-rule-aliases").fill("U doma")
                        page.locator("#merchant-rule-suffix-policy").selectOption("numeric_terminal")
                        page.locator("#merchant-rule-form button[type='submit']").click()
                        page.waitForFunction(
                            "() => document.querySelector('#agent-status')?.textContent === 'Merchant rule добавлено.'",
                        )
                        assertTrue(page.locator("#merchant-rule-list").textContent().contains("У дома"))
                        page.locator("#drawer-new-session").click()
                        page.locator("#agent-workspace").waitFor()
                        assertEquals(1, page.locator("#memory-layer-list .memory-layer").count())
                        assertTrue(page.locator("#memory-request-note").textContent().contains("long-term"))
                        page.locator("#agent-message").fill("Синтетическая browser выписка")
                        page.locator("#send-message").click()
                        page.locator("#operation-table").waitFor()
                        page.waitForFunction(
                            "() => document.querySelector('#memory-request-note')?.textContent.includes('short-term')",
                        )
                        assertEquals(3, page.locator("#memory-layer-list .memory-layer").count())
                        page.locator(".memory-layer-toggle").nth(1).click()
                        assertTrue(page.locator(".memory-layer-detail").textContent().contains("Продукты"))
                        assertFalse(page.locator("#memory-layer-list").textContent().contains("food.groceries"))
                        assertEquals(2, page.locator(".message-copy-actions button").count())
                        page.locator("article.message.user .message-copy-actions button").click()
                        assertEquals(
                            "Синтетическая browser выписка",
                            page.evaluate("async () => await navigator.clipboard.readText()").toString(),
                        )
                        page.locator("article.message.assistant .message-copy-actions button").click()
                        assertEquals(
                            "Черновик готов: 2 операций.",
                            page.evaluate("async () => await navigator.clipboard.readText()").toString().trim(),
                        )

                        assertEquals(1, page.locator(".memory-candidate").count())
                        page.locator(".memory-candidate button").click()
                        page.waitForFunction(
                            "() => document.querySelector('#agent-status')?.textContent === 'Кандидат добавлен в подтверждённые решения.'",
                        )
                        assertEquals(0, page.locator(".memory-candidate button").count())
                        assertTrue(page.locator(".candidate-status").textContent().contains("Добавлено"))

                        page.locator("#open-session-drawer").click()
                        val decisionInput = page.locator("#confirmed-decisions-list input.decision-editor")
                        assertEquals(
                            "Исключать переводы между своими счетами",
                            decisionInput.inputValue(),
                        )
                        decisionInput.fill("Изменённое browser-решение")
                        page.locator("#confirmed-decisions-list .confirmed-decision-actions .secondary-button").click()
                        page.waitForFunction(
                            "() => document.querySelector('#agent-status')?.textContent === 'Подтверждённое решение обновлено.'",
                        )
                        assertEquals(
                            "Изменённое browser-решение",
                            page.locator("#confirmed-decisions-list input.decision-editor").inputValue(),
                        )
                        page.onDialog { dialog -> dialog.accept() }
                        page.locator("#confirmed-decisions-list .confirmed-decision-actions .danger-button").click()
                        page.waitForFunction(
                            "() => document.querySelector('#agent-status')?.textContent === 'Подтверждённое решение удалено.'",
                        )
                        assertTrue(
                            page.locator("#confirmed-decisions-list").textContent()
                                .contains("Подтверждённых решений пока нет."),
                        )

                        page.locator("#close-session-drawer").click()
                        page.locator("#agent-message").fill("Уточнение browser draft")
                        page.locator("#send-message").click()
                        page.waitForFunction(
                            "() => document.querySelectorAll('#metrics-table-body tr').length === 2",
                        )
                        assertEquals(3, page.locator("#memory-layer-list .memory-layer").count())
                        assertTrue(page.locator("#memory-request-note").textContent().contains("short-term"))
                        assertTrue(page.locator("#memory-request-note").textContent().contains("working"))
                        assertTrue(page.locator("#memory-request-note").textContent().contains("long-term"))
                    }
                }
            }
        } finally {
            server.stop(1_000, 5_000)
            database.deleteIfExists()
        }
    }


    @Test
    fun tokenAwareStrategyShowsEffectiveBudgetReadOnly() {
        val database = Files.createTempFile("agent-browser-token-aware-", ".sqlite")
        val gateway = FakeAgentGateway()
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
            module(
                agentDependencies = fakeAgentDependencies(
                    databasePath = database.toString(),
                    gateway = gateway,
                    contextManagement = ContextManagementConfig(
                        strategy = ContextStrategy.TOKEN_AWARE_SUMMARY,
                        recentMessages = 2,
                        summaryBatchMessages = 2,
                        summaryMaxTokens = 256,
                        summaryKeepRecentTokens = 500,
                    ),
                ),
            )
        }.start(wait = false)
        try {
            Playwright.create().use { playwright ->
                playwright.chromium().launch(
                    BrowserType.LaunchOptions().setHeadless(true),
                ).use { browser ->
                    browser.newContext(
                        Browser.NewContextOptions().setViewportSize(1_280, 900),
                    ).use { context ->
                        val page = context.newPage()
                        page.navigate("http://127.0.0.1:$port/agent")
                        waitForAgentInitialized(page)
                        page.locator("#empty-new-session").click()
                        waitForSessionReady(page)
                        val note = page.locator("#context-strategy-note").textContent()
                        assertTrue(note.contains("Token-aware Summary"))
                        assertTrue(note.contains("порог"))
                        assertTrue(note.contains("reserve"))
                        assertTrue(note.contains("fresh tail"))
                    }
                }
            }
        } finally {
            server.stop(1_000, 5_000)
            database.deleteIfExists()
        }
    }

    @Test
    fun branchingButtonCreatesIndependentCheckpointSession() {
        val database = Files.createTempFile("agent-browser-branch-", ".sqlite")
        val gateway = FakeAgentGateway()
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
            module(
                agentDependencies = fakeAgentDependencies(
                    databasePath = database.toString(),
                    gateway = gateway,
                    contextManagement = ContextManagementConfig(strategy = ContextStrategy.BRANCHING),
                ),
            )
        }.start(wait = false)
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
                        waitForAgentInitialized(page)
                        page.locator("#empty-new-session").click()
                        waitForSessionReady(page)
                        assertTrue(page.locator("#context-strategy-note").textContent().contains("Branching"))
                        page.locator("#agent-message").fill("Требования импорта")
                        page.locator("#send-message").click()
                        page.locator("#operation-table").waitFor()
                        page.locator("#fork-session").waitFor()
                        assertTrue(page.locator("#fork-session").isVisible)

                        page.locator("#fork-session").click()
                        page.waitForFunction(
                            "() => document.querySelectorAll('#session-list .session-item').length === 2",
                        )
                        assertTrue(page.locator("#active-session-title").textContent().contains("ветка"))
                        assertEquals(2, page.locator("#message-list .message").count())

                        page.locator("#open-session-drawer").click()
                        val sessionItems = page.locator("#session-list .session-item")
                        val sourceIndex = if (sessionItems.nth(0).textContent().contains("ветка")) 1 else 0
                        sessionItems.nth(sourceIndex).click()
                        page.waitForFunction(
                            "() => !document.querySelector('#active-session-title')?.textContent.includes('ветка')",
                        )
                        assertFalse(page.locator("#active-session-title").textContent().contains("ветка"))
                        assertEquals(2, page.locator("#message-list .message").count())
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
                        waitForAgentInitialized(page)
                        page.locator("#empty-new-session").click()
                        waitForSessionReady(page)
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
                        waitForAgentInitialized(page)
                        page.locator("#empty-new-session").click()
                        waitForSessionReady(page)
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

    @Test
    fun exposesReceiptStatusAndSystemInvariantsInBrowser() {
        val database = Files.createTempFile("agent-browser-stateful-", ".sqlite")
        val gateway = FakeAgentGateway()
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
            module(agentDependencies = fakeAgentDependencies(database.toString(), gateway))
        }.start(wait = false)
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
                        waitForAgentInitialized(page)
                        page.locator("#empty-new-session").click()
                        waitForSessionReady(page)
                        assertEquals("Не начато", page.locator("#receipt-status").textContent())
                        assertEquals(0, page.locator("#task-stage").count())
                        assertEquals(0, page.locator("#task-transition-actions").count())

                        page.locator("#open-session-drawer").click()
                        page.locator("#system-invariant-list .category-item").first().waitFor()
                        assertEquals(5, page.locator("#system-invariant-list .category-item").count())
                        assertEquals(0, page.locator("#profile-name").count())
                        page.locator("#close-session-drawer").click()

                        page.locator("#agent-message").fill("Синтетическая выписка")
                        page.locator("#send-message").click()
                        page.locator("#operation-table").waitFor()
                        assertEquals("Содержит ошибки", page.locator("#receipt-status").textContent())
                        assertEquals(0, page.locator("#task-stage").count())
                        assertEquals(0, page.locator("#task-transition-actions").count())

                        val secondRow = page.locator("tr[data-transaction-id='2']")
                        secondRow.locator("input[name='included']").uncheck()
                        secondRow.locator("button[data-action='save-operation']").click()
                        page.waitForFunction(
                            "() => document.querySelector('#build-batch')?.disabled === false",
                        )
                        assertEquals("Готово к экспорту", page.locator("#receipt-status").textContent())

                        val download = page.waitForDownload {
                            page.locator("#build-batch").click()
                        }
                        assertTrue(download.suggestedFilename().endsWith(".json"))
                        val firstRow = page.locator("tr[data-transaction-id='1']")
                        firstRow.locator("input[name='merchant']").fill("ПОСЛЕ ЭКСПОРТА")
                        firstRow.locator("button[data-action='save-operation']").click()
                        page.waitForFunction(
                            "() => document.querySelector('#receipt-status')?.textContent === 'Готово к экспорту'",
                        )
                        val repeatedDownload = page.waitForDownload {
                            page.locator("#build-batch").click()
                        }
                        assertTrue(repeatedDownload.suggestedFilename().endsWith(".json"))
                        assertEquals(0, page.locator("#batch-result").count())
                        page.reload()
                        page.waitForFunction(
                            "() => document.querySelector('#receipt-status')?.textContent === 'Готово к экспорту'",
                        )
                        assertEquals(0, page.locator("#task-stage").count())
                        assertEquals(0, page.locator("#task-transition-actions").count())
                    }
                }
            }
        } finally {
            server.stop(1_000, 5_000)
            database.deleteIfExists()
        }
    }

    @Test
    fun rendersSafeMarkdownSubsetWithoutRawHtml() {
        val database = Files.createTempFile("agent-browser-markdown-", ".sqlite")
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
            module(agentDependencies = fakeAgentDependencies(database.toString()))
        }.start(wait = false)
        try {
            Playwright.create().use { playwright ->
                playwright.chromium().launch(
                    BrowserType.LaunchOptions().setHeadless(true),
                ).use { browser ->
                    browser.newContext().use { context ->
                        val page = context.newPage()
                        page.navigate("http://127.0.0.1:$port/agent")
                        waitForAgentInitialized(page)
                        val result = JSON.parseToJsonElement(
                            page.evaluate(
                                """
                                    () => {
                                      const host = document.createElement("div");
                                      renderMarkdown(
                                        host,
                                        "| Название | Сумма |\n| --- | --- |\n| **Кафе** | <script>bad</script> |\n\n- Проверено",
                                      );
                                      return JSON.stringify({
                                        tables: host.querySelectorAll("table").length,
                                        strong: host.querySelectorAll("strong").length,
                                        scripts: host.querySelectorAll("script").length,
                                        text: host.textContent,
                                        html: host.innerHTML,
                                      });
                                    }
                                """.trimIndent(),
                            ).toString(),
                        ).jsonObject
                        assertEquals(1, result["tables"]!!.jsonPrimitive.content.toInt())
                        assertEquals(1, result["strong"]!!.jsonPrimitive.content.toInt())
                        assertEquals(0, result["scripts"]!!.jsonPrimitive.content.toInt())
                        assertTrue(result["text"]!!.jsonPrimitive.content.contains("<script>bad</script>"))
                        assertTrue(result["html"]!!.jsonPrimitive.content.contains("&lt;script&gt;"))
                    }
                }
            }
        } finally {
            server.stop(1_000, 1_000)
            database.deleteIfExists()
        }
    }

    @Test
    fun keepsTbankSpacingAndParsesAccountEnvelope() {
        val database = Files.createTempFile("agent-browser-tbank-", ".sqlite")
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
            module(agentDependencies = fakeAgentDependencies(database.toString()))
        }.start(wait = false)
        try {
            Playwright.create().use { playwright ->
                playwright.chromium().launch(
                    BrowserType.LaunchOptions().setHeadless(true),
                ).use { browser ->
                    browser.newContext().use { context ->
                        val page = context.newPage()
                        page.navigate("http://127.0.0.1:$port/agent")
                        waitForAgentInitialized(page)
                        assertEquals(1, page.locator("#tbank-login-form").count())
                        assertEquals(0, page.locator("#tbank-show-fake").count())
                        assertEquals(0, page.locator("#tbank-fake-login-form").count())
                        val result = JSON.parseToJsonElement(
                            page.evaluate(
                                """
                                    () => {
                                      const preview = renderInlineMcpPreview({
                                        transactions: [{
                                          occurred_at: "2026-02-08",
                                          amount_minor: -100,
                                          currency: "RUB",
                                          merchant: "Додо Пицца",
                                          description: "",
                                        }],
                                      });
                                      tbankSession = {
                                        authenticated: true,
                                        persistence_status: "persisted",
                                        persistence_message: null,
                                      };
                                      renderTbankSession();
                                      return JSON.stringify({
                                        margin: getComputedStyle(
                                          document.querySelector("#tbank-logout").parentElement,
                                        ).marginTop,
                                        direct: normalizeTbankAccounts([
                                          { account_ref: "a", name: "Основной счёт" },
                                        ]).length,
                                        nested: normalizeTbankAccounts({
                                          payload: {
                                            accounts: [{ account_ref: "b", name: "Накопительный счёт" }],
                                          },
                                        }).length,
                                        status: document.querySelector("#tbank-session-status").textContent,
                                        description: preview.querySelector("tbody td:nth-child(4)").textContent,
                                      });
                                    }
                                """.trimIndent(),
                            ).toString(),
                        ).jsonObject
                        assertEquals("12px", result["margin"]!!.jsonPrimitive.content)
                        assertEquals(1, result["direct"]!!.jsonPrimitive.content.toInt())
                        assertEquals(1, result["nested"]!!.jsonPrimitive.content.toInt())
                        assertEquals(
                            "Сессия активна · Сессия восстановима после перезапуска",
                            result["status"]!!.jsonPrimitive.content,
                        )
                        assertEquals("", result["description"]!!.jsonPrimitive.content)
                    }
                }
            }
        } finally {
            server.stop(1_000, 1_000)
            database.deleteIfExists()
        }
    }

    private fun waitForAgentInitialized(page: Page) {
        page.waitForFunction(
            """
                () => typeof initialize === 'function' &&
                    document.querySelector('#empty-new-session') &&
                    !document.querySelector('#empty-new-session').disabled
            """.trimIndent(),
        )
    }

    private fun waitForSessionReady(page: Page) {
        page.waitForFunction(
            """
                () => {
                  const el = document.querySelector('#agent-message');
                  const status = document.querySelector('#agent-status');
                  return (el && !el.disabled && el.offsetParent !== null) ||
                      Boolean(status?.textContent);
                }
            """.trimIndent(),
        )
        assertTrue(
            page.locator("#agent-workspace").isVisible,
            "Сессия не открылась: ${page.locator("#agent-status").textContent()}",
        )
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
