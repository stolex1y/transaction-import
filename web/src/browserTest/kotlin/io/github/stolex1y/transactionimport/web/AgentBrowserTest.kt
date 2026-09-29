package io.github.stolex1y.transactionimport.web

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import io.github.stolex1y.transactionimport.core.ContextManagementConfig
import io.github.stolex1y.transactionimport.core.AgentGatewayResolver
import io.github.stolex1y.transactionimport.core.ContextStrategy
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import java.net.ServerSocket
import java.time.ZoneId
import java.time.LocalDate
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

                        page.locator("#show-settings-panel").click()
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
                        page.locator("#open-session-drawer").click()
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

                        page.locator("#show-settings-panel").click()
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

                        page.locator("#open-session-drawer").click()
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

                        page.locator("#show-settings-panel").click()
                        page.locator("#system-invariant-list .category-item").first().waitFor()
                        assertEquals(5, page.locator("#system-invariant-list .category-item").count())
                        assertEquals(0, page.locator("#profile-name").count())
                        page.locator("#open-session-drawer").click()

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

    @Test
    fun navigationKeepsFourPanelsIndependentAndSessionUrlsStable() {
        val database = Files.createTempFile("agent-browser-navigation-", ".sqlite")
        val gateway = FakeAgentGateway()
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
            module(agentDependencies = fakeAgentDependencies(database.toString(), gateway))
        }.start(wait = false)
        try {
            Playwright.create().use { playwright ->
                playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
                    browser.newContext().use { context ->
                        val page = context.newPage()
                        val baseUrl = "http://127.0.0.1:$port"
                        page.navigate("$baseUrl/agent")
                        waitForAgentInitialized(page)

                        val settingsSurfaceSelectors = listOf(
                            "#preferences-form",
                            "#merchant-rule-form",
                            "#merchant-rule-list",
                            "#category-form",
                            "#category-list",
                            "#system-invariant-list",
                            "#confirmed-decisions-list",
                        )
                        page.locator("#show-mcp-panel").click()
                        assertTrue(page.locator("#receipts-panel").isVisible)
                        assertFalse(page.locator("#session-list").isVisible)
                        assertEquals(
                            0,
                            page.locator(
                                "#receipts-panel input[type='tel'], #receipts-panel input[type='password'], " +
                                    "#receipts-panel input[name='phone'], #receipts-panel input[name='otp']",
                            ).count(),
                        )
                        settingsSurfaceSelectors.forEach { selector ->
                            assertFalse(page.locator(selector).isVisible)
                        }
                        page.locator("#show-scheduler-panel").click()
                        assertTrue(page.locator("#scheduler-form").isVisible)
                        assertFalse(page.locator("#receipts-panel").isVisible)
                        assertEquals(0, page.locator("#scheduler-time-zone").count())
                        settingsSurfaceSelectors.forEach { selector ->
                            assertFalse(page.locator(selector).isVisible)
                        }
                        page.locator("#show-settings-panel").click()
                        assertTrue(page.locator("#preferences-form").isVisible)
                        settingsSurfaceSelectors.forEach { selector ->
                            assertTrue(page.locator(selector).isVisible)
                        }
                        assertFalse(page.locator("#scheduler-form").isVisible)
                        page.locator("#open-session-drawer").click()
                        assertTrue(page.locator("#empty-session").isVisible)
                        settingsSurfaceSelectors.forEach { selector ->
                            assertFalse(page.locator(selector).isVisible)
                        }

                        page.locator("#empty-new-session").click()
                        waitForSessionReady(page)
                        val firstPath = page.evaluate("() => window.location.pathname").toString()
                        assertTrue(firstPath.matches(Regex("/agent/sessions/[^/]+")))
                        page.locator("#agent-message").fill("Первая сессия")
                        page.locator("#send-message").click()
                        page.locator("#operation-table").waitFor()

                        page.locator("#new-session").click()
                        page.waitForFunction(
                            "expected => window.location.pathname !== expected",
                            firstPath,
                        )
                        waitForSessionReady(page)
                        val secondPath = page.evaluate("() => window.location.pathname").toString()
                        assertTrue(secondPath.matches(Regex("/agent/sessions/[^/]+")))
                        assertFalse(firstPath == secondPath)
                        page.locator("#agent-message").fill("Вторая сессия")
                        page.locator("#send-message").click()
                        page.locator("#operation-table").waitFor()

                        page.goBack()
                        page.waitForFunction("expected => window.location.pathname === expected", firstPath)
                        page.waitForFunction(
                            "expected => document.querySelector('#message-list')?.textContent.includes(expected)",
                            "Первая сессия",
                        )
                        assertFalse(page.locator("#message-list").textContent().contains("Вторая сессия"))
                        page.goForward()
                        page.waitForFunction("expected => window.location.pathname === expected", secondPath)
                        page.waitForFunction(
                            "expected => document.querySelector('#message-list')?.textContent.includes(expected)",
                            "Вторая сессия",
                        )
                        page.reload()
                        waitForSessionReady(page)
                        assertEquals(secondPath, page.evaluate("() => window.location.pathname").toString())
                        assertTrue(page.locator("#message-list").textContent().contains("Вторая сессия"))

                        val unknownPath = "/agent/sessions/not-a-real-session"
                        page.navigate("$baseUrl$unknownPath")
                        page.locator("#session-not-found").waitFor()
                        assertTrue(page.locator("#session-not-found").isVisible)
                        assertFalse(page.locator("#agent-workspace").isVisible)
                        assertEquals(unknownPath, page.evaluate("() => window.location.pathname").toString())
                    }
                }
            }
        } finally {
            server.stop(1_000, 5_000)
            database.deleteIfExists()
        }
    }

    @Test
    fun backNavigationWinsOverPendingSessionCreationAndOpen() {
        val database = Files.createTempFile("agent-browser-pending-navigation-", ".sqlite")
        val gateway = FakeAgentGateway()
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
            module(agentDependencies = fakeAgentDependencies(database.toString(), gateway))
        }.start(wait = false)
        try {
            Playwright.create().use { playwright ->
                playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
                    browser.newContext().use { context ->
                        val page = context.newPage()
                        page.navigate("http://127.0.0.1:$port/agent")
                        waitForAgentInitialized(page)
                        page.locator("#empty-new-session").click()
                        waitForSessionReady(page)
                        val firstSessionId = page.evaluate("() => sessionIdFromLocation()").toString()

                        page.locator("#new-session").click()
                        page.waitForFunction(
                            "expected => window.location.pathname !== expected",
                            "/agent/sessions/$firstSessionId",
                        )
                        waitForSessionReady(page)
                        val secondSessionId = page.evaluate("() => sessionIdFromLocation()").toString()
                        assertTrue(firstSessionId != secondSessionId)

                        page.evaluate(
                            """
                                () => {
                                  const originalFetch = window.fetch.bind(window);
                                  let creationHeld = false;
                                  let releaseCreation;
                                  const creationGate = new Promise(resolve => { releaseCreation = resolve; });
                                  window.__pendingCreateStarted = false;
                                  window.__releasePendingCreate = () => releaseCreation();
                                  window.fetch = (input, init) => {
                                    const url = typeof input === 'string' ? input : input.url;
                                    const path = new URL(url, window.location.href).pathname;
                                    const method = (init?.method ||
                                      (input instanceof Request ? input.method : 'GET')).toUpperCase();
                                    if (!creationHeld && method === 'POST' && path === '/api/agent/sessions') {
                                      creationHeld = true;
                                      window.__pendingCreateStarted = true;
                                      return creationGate.then(() => originalFetch(input, init));
                                    }
                                    return originalFetch(input, init);
                                  };
                                }
                            """.trimIndent(),
                        )
                        page.evaluate("() => { window.__pendingCreate = createSession(); }")
                        page.waitForFunction("() => window.__pendingCreateStarted === true")
                        page.goBack()
                        page.waitForFunction(
                            "expected => window.location.pathname === '/agent/sessions/' + expected && " +
                                "activeState?.session.id === expected",
                            firstSessionId,
                        )
                        page.evaluate("() => window.__releasePendingCreate()")
                        page.waitForFunction(
                            "() => document.querySelector('.agent-app')?.getAttribute('aria-busy') === 'false'",
                        )
                        assertEquals(
                            firstSessionId,
                            page.evaluate("() => activeState?.session.id").toString(),
                        )
                        assertEquals(3, page.evaluate("() => sessions.length").toString().toInt())
                        assertEquals(
                            "/agent/sessions/$firstSessionId",
                            page.evaluate("() => window.location.pathname").toString(),
                        )

                        page.goForward()
                        page.waitForFunction(
                            "expected => window.location.pathname === '/agent/sessions/' + expected && " +
                                "activeState?.session.id === expected",
                            secondSessionId,
                        )
                        page.evaluate(
                            """
                                id => {
                                  const originalFetch = window.fetch.bind(window);
                                  let openHeld = false;
                                  let releaseOpen;
                                  const openGate = new Promise(resolve => { releaseOpen = resolve; });
                                  window.__pendingOpenStarted = false;
                                  window.__releasePendingOpen = () => releaseOpen();
                                  window.fetch = (input, init) => {
                                    const url = typeof input === 'string' ? input : input.url;
                                    const path = new URL(url, window.location.href).pathname;
                                    const method = (init?.method ||
                                      (input instanceof Request ? input.method : 'GET')).toUpperCase();
                                    if (!openHeld && method === 'GET' &&
                                      path === '/api/agent/sessions/' + encodeURIComponent(id)) {
                                      openHeld = true;
                                      window.__pendingOpenStarted = true;
                                      return openGate.then(() => originalFetch(input, init));
                                    }
                                    return originalFetch(input, init);
                                  };
                                  window.__pendingOpen = openSession(id);
                                }
                            """.trimIndent(),
                            secondSessionId,
                        )
                        page.waitForFunction("() => window.__pendingOpenStarted === true")
                        page.goBack()
                        page.waitForFunction(
                            "expected => window.location.pathname === '/agent/sessions/' + expected && " +
                                "activeState?.session.id === expected",
                            firstSessionId,
                        )
                        page.evaluate("() => window.__releasePendingOpen()")
                        page.waitForFunction(
                            "() => document.querySelector('.agent-app')?.getAttribute('aria-busy') === 'false'",
                        )
                        assertEquals(
                            firstSessionId,
                            page.evaluate("() => activeState?.session.id").toString(),
                        )
                        assertEquals(
                            "/agent/sessions/$firstSessionId",
                            page.evaluate("() => window.location.pathname").toString(),
                        )
                    }
                }
            }
        } finally {
            server.stop(1_000, 1_000)
            database.deleteIfExists()
        }
    }

    @Test
    fun failedHistoryLoadClearsPreviousSessionWorkspace() {
        val database = Files.createTempFile("agent-browser-history-failure-", ".sqlite")
        val gateway = FakeAgentGateway()
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
            module(agentDependencies = fakeAgentDependencies(database.toString(), gateway))
        }.start(wait = false)
        try {
            Playwright.create().use { playwright ->
                playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
                    browser.newContext().use { context ->
                        val page = context.newPage()
                        page.navigate("http://127.0.0.1:$port/agent")
                        waitForAgentInitialized(page)
                        page.locator("#empty-new-session").click()
                        waitForSessionReady(page)
                        val firstSessionId = page.evaluate("() => sessionIdFromLocation()").toString()

                        page.locator("#new-session").click()
                        page.waitForFunction(
                            "expected => window.location.pathname !== expected",
                            "/agent/sessions/$firstSessionId",
                        )
                        waitForSessionReady(page)
                        assertTrue(page.locator("#agent-workspace").isVisible)

                        page.evaluate(
                            """
                                id => {
                                  const originalFetch = window.fetch.bind(window);
                                  let failed = false;
                                  window.fetch = (input, init) => {
                                    const url = typeof input === 'string' ? input : input.url;
                                    const path = new URL(url, window.location.href).pathname;
                                    const method = (init?.method ||
                                      (input instanceof Request ? input.method : 'GET')).toUpperCase();
                                    if (!failed && method === 'GET' &&
                                      path === '/api/agent/sessions/' + encodeURIComponent(id)) {
                                      failed = true;
                                      return Promise.resolve(new Response(
                                        JSON.stringify({ error: 'synthetic history failure' }),
                                        { status: 503, headers: { 'Content-Type': 'application/json' } },
                                      ));
                                    }
                                    return originalFetch(input, init);
                                  };
                                }
                            """.trimIndent(),
                            firstSessionId,
                        )
                        page.goBack()
                        page.waitForFunction(
                            "() => window.location.pathname.endsWith('" + firstSessionId + "') && " +
                                "document.querySelector('.agent-toast.error')?.textContent === " +
                                "'synthetic history failure'",
                        )
                        assertEquals(null, page.evaluate("() => activeState"))
                        assertTrue(page.locator("#empty-session").isVisible)
                        assertTrue(page.locator("#agent-workspace").isHidden)
                        assertTrue(page.locator("#delete-session").isHidden)
                        assertTrue(page.locator("#send-message").isDisabled)
                    }
                }
            }
        } finally {
            server.stop(1_000, 1_000)
            database.deleteIfExists()
        }
    }

    @Test
    fun receiptsSearchAndDetailsStayReadOnlyAndNeverRenderRawKeys() {
        val database = Files.createTempFile("agent-browser-receipts-", ".sqlite")
        val gateway = FakeAgentGateway()
        val receipts = BrowserReceiptsFixture()
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
            module(
                agentDependencies = fakeAgentDependencies(
                    database.toString(),
                    gateway,
                    receiptsProxy = ReceiptsProxyService(receipts),
                ),
            )
        }.start(wait = false)
        try {
            Playwright.create().use { playwright ->
                playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
                    browser.newContext().use { context ->
                        val page = context.newPage()
                        page.navigate("http://127.0.0.1:$port/agent")
                        waitForAgentInitialized(page)
                        page.locator("#empty-new-session").click()
                        waitForSessionReady(page)
                        val before = page.evaluate(
                            """
                                async () => {
                                  const id = window.location.pathname.split("/").pop();
                                  const response = await fetch('/api/agent/sessions/' + encodeURIComponent(id));
                                  const state = await response.json();
                                  return JSON.stringify({ revision: state.session.revision, draft: state.draft });
                                }
                            """.trimIndent(),
                        ).toString()

                        page.locator("#show-mcp-panel").click()
                        page.waitForFunction(
                            "() => document.querySelector('#receipts-auth-detail')?.textContent" +
                                ".includes('Проверочная session живёт только в памяти.')",
                        )
                        assertTrue(page.locator("#receipts-browser-login").isHidden)
                        assertTrue(page.locator("#receipts-refresh-session").isVisible)
                        assertTrue(page.locator("#receipts-logout").isVisible)
                        page.locator("#receipts-from").fill("2026-09-10")
                        page.locator("#receipts-to").fill("2026-09-10")
                        page.locator("#receipts-seller").fill("Кофейня")
                        page.locator("#receipts-search-submit").click()
                        page.locator("#receipts-summary-list .receipt-summary").waitFor()
                        assertEquals(1, page.locator("#receipts-summary-list .receipt-summary").count())
                        assertTrue(page.locator("#receipts-summary-list").textContent().contains("Кофейня у дома"))
                        assertFalse(page.locator("#receipts-panel").textContent().contains(BrowserReceiptsFixture.RAW_KEY))

                        page.locator("#receipts-summary-list .receipt-summary").click()
                        page.locator("#receipt-detail-panel").waitFor()
                        page.waitForFunction(
                            "() => document.querySelector('#receipt-detail')?.textContent.includes('Кофе')",
                        )
                        assertTrue(page.locator("#receipt-detail").textContent().contains("Кофе"))
                        assertTrue(page.locator("#receipt-detail").textContent().contains("499"))
                        assertFalse(
                            page.evaluate("() => document.querySelector('#receipts-panel').outerHTML")
                                .toString().contains(BrowserReceiptsFixture.RAW_KEY),
                        )
                        page.locator("#receipts-logout").click()
                        page.waitForFunction(
                            "() => document.querySelector('#receipts-browser-login')?.hidden === false",
                        )
                        assertTrue(page.locator("#receipts-browser-login").isVisible)
                        val after = page.evaluate(
                            """
                                async () => {
                                  const id = window.location.pathname.split("/").pop();
                                  const response = await fetch('/api/agent/sessions/' + encodeURIComponent(id));
                                  const state = await response.json();
                                  return JSON.stringify({ revision: state.session.revision, draft: state.draft });
                                }
                            """.trimIndent(),
                        ).toString()
                        assertEquals(before, after)
                        assertEquals(1, receipts.searchCalls)
                        assertEquals(1, receipts.detailCalls)
                        assertEquals(0, gateway.requests.size)
                    }
                }
            }
        } finally {
            server.stop(1_000, 5_000)
            database.deleteIfExists()
        }
    }

    @Test
    fun schedulerLinkedSessionChatsAndPreservesManualEditsAcrossRuns() {
        val database = Files.createTempFile("agent-browser-linked-task-", ".sqlite")
        val gateway = FakeAgentGateway()
        var schedulerNow = LocalDate.parse("2026-09-10")
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
            module(
                agentDependencies = fakeAgentDependencies(
                    database.toString(),
                    gateway,
                    schedulerAccountAvailable = true,
                    schedulerNowEpochMs = { schedulerNow },
                ),
            )
        }.start(wait = false)
        try {
            Playwright.create().use { playwright ->
                playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
                    browser.newContext().use { context ->
                        val page = context.newPage()
                        page.navigate("http://127.0.0.1:$port/agent")
                        waitForAgentInitialized(page)
                        page.locator("#show-scheduler-panel").click()
                        page.waitForFunction(
                            "() => Array.from(document.querySelector('#scheduler-accounts').options)" +
                                ".some(option => option.value === 'fixture-account')",
                        )
                        page.locator("#scheduler-accounts").selectOption("fixture-account")
                        page.locator("#scheduler-name").fill("Linked task")
                        page.locator("#scheduler-start-date").fill("2026-09-10")
                        page.locator("#scheduler-interval").fill("60")
                        page.locator("#scheduler-submit").click()
                        page.locator(".scheduler-task a[data-session-id]").waitFor()
                        val taskCard = page.locator(".scheduler-task")
                        assertTrue(taskCard.textContent().contains("часовой пояс ${ZoneId.systemDefault().id}"))
                        val linkedSessionId =
                            page.locator(".scheduler-task a[data-session-id]").getAttribute("data-session-id")!!
                        page.locator(".scheduler-task a[data-session-id]").click()
                        page.waitForFunction(
                            "expected => window.location.pathname === '/agent/sessions/' + expected",
                            linkedSessionId,
                        )
                        waitForSessionReady(page)
                        assertTrue(page.locator("#agent-message").isVisible)
                        assertFalse(page.locator("#draft-panel").isVisible)
                        page.locator("#agent-message").fill("Обсудить импорт до первого фонового запуска")
                        page.locator("#send-message").click()
                        page.locator("#operation-table").waitFor()
                        assertTrue(page.locator("#message-list").textContent().contains("Обсудить импорт"))
                        assertTrue(page.locator("#linked-scheduler-tasks").isVisible)
                        assertTrue(page.locator("#linked-scheduler-task-list").textContent().contains("Linked task"))

                        page.locator("#linked-scheduler-task-list button[data-linked-task-id]").click()
                        page.waitForFunction(
                            "() => document.querySelector('.agent-app')?.dataset.activePanel === 'scheduler'",
                        )
                        val taskId = page.evaluate(
                            """() => document.querySelector('.scheduler-task')?.dataset.taskId""",
                        ).toString()
                        val schedulerCard = page.locator(".scheduler-task[data-task-id='$taskId']")
                        assertTrue(schedulerCard.isVisible)
                        schedulerCard.locator("button[data-scheduler-action='run']").click()
                        page.waitForFunction(
                            """id => {
                                const card = [...document.querySelectorAll('.scheduler-task')]
                                  .find(item => item.dataset.taskId === id);
                                return card?.textContent.includes('Последний запуск:') || false;
                            }""",
                            taskId,
                        )
                        schedulerCard.locator("a[data-session-id]").click()
                        page.waitForFunction(
                            "() => document.querySelector('.agent-app')?.dataset.activePanel === 'sessions' && " +
                                "!document.querySelector('#agent-workspace')?.hidden",
                        )
                        waitForSessionReady(page)
                        assertEquals(
                            linkedSessionId,
                            page.evaluate("() => window.location.pathname.split('/').pop()").toString(),
                        )
                        val operationRows = page.locator("#operation-table tbody tr")
                        var importedIndex = -1
                        for (index in 0 until operationRows.count()) {
                            if (operationRows.nth(index).locator("input[name='merchant']").inputValue() ==
                                "Browser scheduled purchase"
                            ) {
                                importedIndex = index
                                break
                            }
                        }
                        assertTrue(importedIndex >= 0, "The scheduled operation must be in the linked session draft.")
                        val importedRow = operationRows.nth(importedIndex)
                        importedRow.locator("input[name='merchant']").fill("Магазин после ручной правки")
                        importedRow.locator("input[name='description']").fill("Описание сохранено вручную")
                        importedRow.locator("button[data-action='save-operation']").click()
                        page.waitForFunction(
                            """() => [...document.querySelectorAll('#operation-table tbody tr')].some(row =>
                                row.querySelector("input[name='merchant']")?.value === 'Магазин после ручной правки' &&
                                row.querySelector("input[name='description']")?.value === 'Описание сохранено вручную')""",
                        )
                        page.waitForFunction(
                            "() => document.querySelector('.agent-toast.success')?.textContent === 'Операция сохранена.'",
                        )
                        page.waitForFunction(
                            "() => document.querySelector('.agent-app')?.getAttribute('aria-busy') === 'false'",
                        )

                        page.locator("#show-scheduler-panel").click()
                        schedulerNow = LocalDate.parse("2026-09-11")
                            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        page.locator(".scheduler-task[data-task-id='$taskId'] button[data-scheduler-action='run']").click()
                        page.waitForFunction(
                            """async id => {
                                const response = await fetch(
                                  '/api/agent/scheduler/tasks/' + encodeURIComponent(id) + '/history?limit=20'
                                );
                                return (await response.json()).length === 2;
                            }""",
                            taskId,
                        )
                        page.waitForFunction(
                            "() => document.querySelector('.agent-app')?.getAttribute('aria-busy') === 'false'",
                        )
                        page.locator(".scheduler-task[data-task-id='$taskId'] a[data-session-id]").click()
                        page.waitForFunction(
                            "() => document.querySelector('.agent-app')?.dataset.activePanel === 'sessions' && " +
                                "!document.querySelector('#agent-workspace')?.hidden",
                        )
                        waitForSessionReady(page)
                        assertEquals(
                            linkedSessionId,
                            page.evaluate("() => window.location.pathname.split('/').pop()").toString(),
                        )
                        page.waitForFunction(
                            "() => document.querySelector('.agent-toast.success')?.textContent === 'Сессия открыта.'",
                        )
                        val operationRowsAfterSecond = page.locator("#operation-table tbody tr")
                        val operationFields = (0 until operationRowsAfterSecond.count()).map { index ->
                            val row = operationRowsAfterSecond.nth(index)
                            row.locator("input[name='merchant']").inputValue() to
                                row.locator("input[name='description']").inputValue()
                        }
                        assertTrue(
                            ("Магазин после ручной правки" to "Описание сохранено вручную") in operationFields,
                            "Session table lost edited fields: $operationFields",
                        )
                        assertTrue(
                            operationFields.any { it.first == "Second scheduled purchase" },
                            "Second run result is missing from the linked session: $operationFields",
                        )
                    }
                }
            }
        } finally {
            server.stop(1_000, 5_000)
            database.deleteIfExists()
        }
    }

    @Test
    fun deletingLinkedSessionWarnsAndKeepsUnrelatedTaskAndSession() {
        val database = Files.createTempFile("agent-browser-linked-delete-", ".sqlite")
        val gateway = FakeAgentGateway()
        val schedulerNow = LocalDate.parse("2026-09-10")
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
            module(
                agentDependencies = fakeAgentDependencies(
                    database.toString(),
                    gateway,
                    schedulerAccountAvailable = true,
                    schedulerNowEpochMs = { schedulerNow },
                ),
            )
        }.start(wait = false)
        try {
            Playwright.create().use { playwright ->
                playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
                    browser.newContext().use { context ->
                        val page = context.newPage()
                        page.navigate("http://127.0.0.1:$port/agent")
                        waitForAgentInitialized(page)
                        page.locator("#show-scheduler-panel").click()
                        page.waitForFunction(
                            "() => Array.from(document.querySelector('#scheduler-accounts').options)" +
                                ".some(option => option.value === 'fixture-account')",
                        )
                        fun createTask(name: String) {
                            page.locator("#scheduler-accounts").selectOption("fixture-account")
                            page.locator("#scheduler-name").fill(name)
                            page.locator("#scheduler-start-date").fill("2026-09-10")
                            page.locator("#scheduler-interval").fill("60")
                            page.locator("#scheduler-submit").click()
                            page.waitForFunction(
                                """expected => [...document.querySelectorAll('.scheduler-task h3')]
                                    .some(heading => heading.textContent.includes(expected))""",
                                name,
                            )
                        }
                        createTask("Linked task")
                        createTask("Unrelated task")
                        val linkedTaskId = page.evaluate(
                            """() => [...document.querySelectorAll('.scheduler-task')]
                                .find(card => card.querySelector('h3')?.textContent.includes('Linked task'))
                                ?.dataset.taskId""",
                        ).toString()
                        val unrelatedTaskId = page.evaluate(
                            """() => [...document.querySelectorAll('.scheduler-task')]
                                .find(card => card.querySelector('h3')?.textContent.includes('Unrelated task'))
                                ?.dataset.taskId""",
                        ).toString()
                        val linkedCard = page.locator(".scheduler-task[data-task-id='$linkedTaskId']")
                        val unrelatedCard = page.locator(".scheduler-task[data-task-id='$unrelatedTaskId']")
                        val linkedSessionId = linkedCard.locator("a[data-session-id]").getAttribute("data-session-id")!!
                        val unrelatedSessionId =
                            unrelatedCard.locator("a[data-session-id]").getAttribute("data-session-id")!!
                        linkedCard.locator("button[data-scheduler-action='run']").click()
                        page.waitForFunction(
                            """id => [...document.querySelectorAll('.scheduler-task')]
                                .find(card => card.dataset.taskId === id)?.textContent.includes('Последний запуск:')""",
                            linkedTaskId,
                        )
                        unrelatedCard.locator("button[data-scheduler-action='run']").click()
                        page.waitForFunction(
                            """id => [...document.querySelectorAll('.scheduler-task')]
                                .find(card => card.dataset.taskId === id)?.textContent.includes('Последний запуск:')""",
                            unrelatedTaskId,
                        )
                        val historyBeforeDelete = JSON.parseToJsonElement(
                            page.evaluate(
                                """
                                    async () => {
                                      const linked = await (await fetch(
                                        '/api/agent/scheduler/tasks/' + encodeURIComponent('$linkedTaskId') + '/history?limit=20'
                                      )).json();
                                      const unrelated = await (await fetch(
                                        '/api/agent/scheduler/tasks/' + encodeURIComponent('$unrelatedTaskId') + '/history?limit=20'
                                      )).json();
                                      return JSON.stringify({ linked: linked.length, unrelated: unrelated.length });
                                    }
                                """.trimIndent(),
                            ).toString(),
                        ).jsonObject
                        assertEquals(1, historyBeforeDelete["linked"]!!.jsonPrimitive.content.toInt())
                        assertEquals(1, historyBeforeDelete["unrelated"]!!.jsonPrimitive.content.toInt())

                        linkedCard.locator("a[data-session-id]").click()
                        page.waitForFunction(
                            "expected => window.location.pathname === '/agent/sessions/' + expected",
                            linkedSessionId,
                        )
                        page.locator("#open-session-drawer").click()
                        page.locator("#session-list .session-item:has-text('Unrelated task')").waitFor()
                        val confirmations = mutableListOf<String>()
                        var dismissUnexpectedConfirmation = true
                        var deleteRequestCount = 0
                        page.onDialog { dialog ->
                            confirmations += dialog.message()
                            if (dismissUnexpectedConfirmation) dialog.dismiss() else dialog.accept()
                        }
                        page.onRequest { request ->
                            if (request.method() == "DELETE" && request.url().contains("/api/agent/sessions/")) {
                                deleteRequestCount += 1
                            }
                        }
                        page.evaluate(
                            """
                                () => {
                                  const originalFetch = window.fetch.bind(window);
                                  let releaseTaskList;
                                  let releaseSessionRefresh;
                                  const taskListGate = new Promise(resolve => { releaseTaskList = resolve; });
                                  const sessionRefreshGate = new Promise(resolve => { releaseSessionRefresh = resolve; });
                                  let taskListHeld = false;
                                  let sessionRefreshHeld = false;
                                  window.__schedulerTaskListStarted = false;
                                  window.__sessionRefreshStarted = false;
                                  window.__releaseSchedulerTaskList = () => releaseTaskList();
                                  window.__releaseSessionRefresh = () => releaseSessionRefresh();
                                  window.fetch = (input, init) => {
                                    const url = typeof input === 'string' ? input : input.url;
                                    const path = new URL(url, window.location.href).pathname;
                                    if (!taskListHeld && path === '/api/agent/scheduler/tasks') {
                                      taskListHeld = true;
                                      window.__schedulerTaskListStarted = true;
                                      return taskListGate.then(() => originalFetch(input, init));
                                    }
                                    if (!sessionRefreshHeld && path === '/api/agent/tbank/session') {
                                      sessionRefreshHeld = true;
                                      window.__sessionRefreshStarted = true;
                                      return sessionRefreshGate.then(() => originalFetch(input, init));
                                    }
                                    return originalFetch(input, init);
                                  };
                                }
                            """.trimIndent(),
                        )
                        page.locator("#delete-session").click()
                        page.waitForFunction("() => window.__schedulerTaskListStarted === true")
                        page.locator("#session-list .session-item:has-text('Unrelated task')").click()
                        page.waitForFunction("() => window.__sessionRefreshStarted === true")
                        page.evaluate("() => window.__releaseSchedulerTaskList()")
                        page.waitForFunction(
                            "() => document.body.textContent.includes('Дождитесь завершения текущей операции; сессия не удалена.')",
                        )
                        assertEquals(emptyList(), confirmations)
                        assertEquals(0, deleteRequestCount)
                        page.evaluate("() => window.__releaseSessionRefresh()")
                        page.waitForFunction(
                            "expected => window.location.pathname === '/agent/sessions/' + expected",
                            unrelatedSessionId,
                        )
                        page.waitForFunction(
                            "() => document.querySelector('.agent-toast.success')?.textContent === 'Сессия открыта.'",
                        )
                        page.locator("#session-list .session-item:has-text('Linked task')").click()
                        page.waitForFunction(
                            "expected => window.location.pathname === '/agent/sessions/' + expected",
                            linkedSessionId,
                        )
                        page.waitForFunction(
                            "() => document.querySelector('.agent-toast.success')?.textContent === 'Сессия открыта.'",
                        )
                        dismissUnexpectedConfirmation = false
                        page.evaluate(
                            """
                                () => {
                                  const originalFetch = window.fetch.bind(window);
                                  let releaseTaskList;
                                  const taskListGate = new Promise(resolve => { releaseTaskList = resolve; });
                                  let held = false;
                                  window.__schedulerTaskListStarted = false;
                                  window.__releaseSchedulerTaskList = () => releaseTaskList();
                                  window.fetch = (input, init) => {
                                    const url = typeof input === 'string' ? input : input.url;
                                    if (!held && new URL(url, window.location.href).pathname ===
                                      '/api/agent/scheduler/tasks') {
                                      held = true;
                                      window.__schedulerTaskListStarted = true;
                                      return taskListGate.then(() => originalFetch(input, init));
                                    }
                                    return originalFetch(input, init);
                                  };
                                }
                            """.trimIndent(),
                        )
                        page.locator("#delete-session").click()
                        page.waitForFunction("() => window.__schedulerTaskListStarted === true")
                        page.locator("#session-list .session-item:has-text('Unrelated task')").click()
                        page.waitForFunction(
                            "expected => window.location.pathname === '/agent/sessions/' + expected",
                            unrelatedSessionId,
                        )
                        val deleteResponse = page.waitForResponse(
                            { response ->
                                response.request().method() == "DELETE" &&
                                    response.url().endsWith("/api/agent/sessions/$linkedSessionId")
                            },
                            { page.evaluate("() => window.__releaseSchedulerTaskList()") },
                        )
                        assertEquals(204, deleteResponse.status(), "DELETE body ${deleteResponse.request().postData()}")
                        assertEquals(1, deleteRequestCount)
                        page.waitForFunction(
                            """async id => (await fetch('/api/agent/sessions/' + encodeURIComponent(id))).status === 404""",
                            linkedSessionId,
                        )
                        assertEquals(
                            unrelatedSessionId,
                            page.evaluate("() => window.location.pathname.split('/').pop()").toString(),
                        )
                        assertEquals(1, confirmations.size)
                        assertTrue(confirmations.single().contains("Linked task"))
                        assertTrue(confirmations.single().contains("история их запусков"))
                        assertFalse(confirmations.single().contains("Unrelated task"))

                        val snapshot = page.evaluate(
                            """
                                async () => {
                                  const tasks = await (await fetch('/api/agent/scheduler/tasks')).json();
                                  const sessions = await (await fetch('/api/agent/sessions')).json();
                                  const linkedHistory = await (await fetch(
                                    '/api/agent/scheduler/tasks/' + encodeURIComponent('$linkedTaskId') + '/history?limit=20'
                                  )).json();
                                  const unrelatedHistory = await (await fetch(
                                    '/api/agent/scheduler/tasks/' + encodeURIComponent('$unrelatedTaskId') + '/history?limit=20'
                                  )).json();
                                  const linkedSession = await fetch(
                                    '/api/agent/sessions/' + encodeURIComponent('$linkedSessionId')
                                  );
                                  const unrelatedSession = await fetch(
                                    '/api/agent/sessions/' + encodeURIComponent('$unrelatedSessionId')
                                  );
                                  return JSON.stringify({
                                    taskIds: tasks.map(task => task.id),
                                    sessionIds: sessions.map(session => session.id),
                                    linkedHistory,
                                    unrelatedHistory,
                                    linkedSessionStatus: linkedSession.status,
                                    unrelatedSessionStatus: unrelatedSession.status,
                                  });
                                }
                            """.trimIndent(),
                        ).toString()
                        val result = JSON.parseToJsonElement(snapshot).jsonObject
                        assertEquals(
                            listOf(unrelatedTaskId),
                            result["taskIds"]!!.jsonArray.map { it.jsonPrimitive.content },
                            "DELETE ${deleteResponse.url()} ${deleteResponse.request().postData()}; linked task $linkedTaskId; linked session $linkedSessionId; state $result",
                        )
                        assertEquals(emptyList(), result["linkedHistory"]!!.jsonArray)
                        assertEquals(1, result["unrelatedHistory"]!!.jsonArray.size)
                        assertEquals(
                            listOf(unrelatedSessionId),
                            result["sessionIds"]!!.jsonArray.map { it.jsonPrimitive.content },
                        )
                        assertEquals("404", result["linkedSessionStatus"]!!.jsonPrimitive.content)
                        assertEquals("200", result["unrelatedSessionStatus"]!!.jsonPrimitive.content)
                    }
                }
            }
        } finally {
            server.stop(1_000, 5_000)
            database.deleteIfExists()
        }
    }

    @Test
    fun deletingCurrentSessionPreservesBackAndForwardNavigationDuringRefresh() {
        val database = Files.createTempFile("agent-browser-delete-navigation-", ".sqlite")
        val gateway = FakeAgentGateway()
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
            module(agentDependencies = fakeAgentDependencies(database.toString(), gateway))
        }.start(wait = false)
        try {
            Playwright.create().use { playwright ->
                playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
                    browser.newContext().use { context ->
                        val page = context.newPage()
                        val baseUrl = "http://127.0.0.1:$port"
                        page.navigate("$baseUrl/agent")
                        waitForAgentInitialized(page)

                        page.locator("#empty-new-session").click()
                        waitForSessionReady(page)
                        val deletedSessionId = page.evaluate("() => sessionIdFromLocation()").toString()
                        page.locator("#new-session").click()
                        page.waitForFunction(
                            "expected => window.location.pathname !== '/agent/sessions/' + expected",
                            deletedSessionId,
                        )
                        waitForSessionReady(page)
                        val secondSessionId = page.evaluate("() => sessionIdFromLocation()").toString()
                        page.locator("#new-session").click()
                        page.waitForFunction(
                            "expected => window.location.pathname !== '/agent/sessions/' + expected",
                            secondSessionId,
                        )
                        waitForSessionReady(page)
                        val thirdSessionId = page.evaluate("() => sessionIdFromLocation()").toString()

                        val orderedSessionIds = JSON.parseToJsonElement(
                            page.evaluate(
                                """
                                    async () => JSON.stringify(
                                      (await (await fetch('/api/agent/sessions')).json()).map(session => session.id)
                                    )
                                """.trimIndent(),
                            ).toString(),
                        ).jsonArray.map { it.jsonPrimitive.content }
                        assertEquals(3, orderedSessionIds.size)
                        val remainingSessionIds = orderedSessionIds.filterNot { it == deletedSessionId }
                        val selectedSessionId = remainingSessionIds.last()
                        assertTrue(remainingSessionIds.first() != selectedSessionId)
                        val targetIndex = orderedSessionIds.indexOf(deletedSessionId)
                        assertTrue(targetIndex >= 0)

                        page.locator("#open-session-drawer").click()
                        page.locator("#session-list .session-item").nth(targetIndex).click()
                        page.waitForFunction(
                            "expected => window.location.pathname === '/agent/sessions/' + expected",
                            deletedSessionId,
                        )
                        page.waitForFunction(
                            "() => document.querySelector('.agent-toast.success')?.textContent === 'Сессия открыта.'",
                        )

                        val confirmations = mutableListOf<String>()
                        page.onDialog { dialog ->
                            confirmations += dialog.message()
                            dialog.accept()
                        }
                        page.evaluate(
                            """
                                () => {
                                  const originalFetch = window.fetch.bind(window);
                                  let deletionCompleted = false;
                                  let sessionListHeld = false;
                                  let thirdSessionHeld = false;
                                  let deletedSessionHeld = false;
                                  let releaseSessionList;
                                  let releaseThirdSession;
                                  let releaseDeletedSession;
                                  const sessionListGate = new Promise(resolve => { releaseSessionList = resolve; });
                                  const thirdSessionGate = new Promise(resolve => { releaseThirdSession = resolve; });
                                  const deletedSessionGate = new Promise(resolve => { releaseDeletedSession = resolve; });
                                  window.__postDeleteSessionListStarted = false;
                                  window.__thirdSessionFetchStarted = false;
                                  window.__deletedSessionFetchStarted = false;
                                  window.__releasePostDeleteSessionList = () => releaseSessionList();
                                  window.__releaseThirdSessionFetch = () => releaseThirdSession();
                                  window.__releaseDeletedSessionFetch = () => releaseDeletedSession();
                                  window.fetch = (input, init) => {
                                    const url = typeof input === 'string' ? input : input.url;
                                    const path = new URL(url, window.location.href).pathname;
                                    const method = (init?.method ||
                                      (input instanceof Request ? input.method : 'GET')).toUpperCase();
                                    if (method === 'DELETE' &&
                                      path === '/api/agent/sessions/' + '$deletedSessionId') {
                                      return originalFetch(input, init).then(response => {
                                        deletionCompleted = response.status === 204;
                                        return response;
                                      });
                                    }
                                    if (deletionCompleted && method === 'GET' &&
                                      path === '/api/agent/sessions/' + '$thirdSessionId' && !thirdSessionHeld) {
                                      thirdSessionHeld = true;
                                      window.__thirdSessionFetchStarted = true;
                                      return thirdSessionGate.then(() => originalFetch(input, init));
                                    }
                                    if (deletionCompleted && method === 'GET' &&
                                      path === '/api/agent/sessions/' + '$deletedSessionId' &&
                                      !deletedSessionHeld) {
                                      deletedSessionHeld = true;
                                      window.__deletedSessionFetchStarted = true;
                                      return deletedSessionGate.then(() => originalFetch(input, init));
                                    }
                                    if (deletionCompleted && method === 'GET' &&
                                      path === '/api/agent/sessions' && !sessionListHeld) {
                                      sessionListHeld = true;
                                      window.__postDeleteSessionListStarted = true;
                                      return sessionListGate.then(() => originalFetch(input, init));
                                    }
                                    return originalFetch(input, init);
                                  };
                                }
                            """.trimIndent(),
                        )
                        val deleteResponse = page.waitForResponse(
                            { response ->
                                response.request().method() == "DELETE" &&
                                    response.url().endsWith("/api/agent/sessions/$deletedSessionId")
                            },
                            { page.locator("#delete-session").click() },
                        )
                        assertEquals(204, deleteResponse.status())
                        page.waitForFunction("() => window.__postDeleteSessionListStarted === true")

                        page.goBack()
                        page.waitForFunction("() => window.__thirdSessionFetchStarted === true")
                        page.goBack()
                        page.waitForFunction(
                            "expected => window.location.pathname === '/agent/sessions/' + expected",
                            secondSessionId,
                        )
                        page.waitForFunction(
                            "expected => activeState?.session.id === expected",
                            secondSessionId,
                        )
                        assertTrue(page.locator("#new-session").isDisabled)

                        val staleSessionResponse = page.waitForResponse(
                            { response ->
                                response.request().method() == "GET" &&
                                    response.url().endsWith("/api/agent/sessions/$thirdSessionId")
                            },
                            { page.evaluate("() => window.__releaseThirdSessionFetch()") },
                        )
                        assertEquals(200, staleSessionResponse.status())
                        page.waitForFunction(
                            "expected => window.location.pathname === '/agent/sessions/' + expected && " +
                                "activeState?.session.id === expected",
                            secondSessionId,
                        )
                        assertTrue(page.locator("#new-session").isDisabled)

                        if (selectedSessionId == thirdSessionId) {
                            page.goForward()
                            page.waitForFunction(
                                "expected => window.location.pathname === '/agent/sessions/' + expected",
                                thirdSessionId,
                            )
                            page.waitForFunction(
                                "expected => activeState?.session.id === expected",
                                thirdSessionId,
                            )
                        }
                        if (selectedSessionId != thirdSessionId) {
                            page.goForward()
                            page.waitForFunction(
                                "expected => window.location.pathname === '/agent/sessions/' + expected",
                                thirdSessionId,
                            )
                            page.waitForFunction(
                                "expected => activeState?.session.id === expected",
                                thirdSessionId,
                            )
                        }
                        page.goForward()
                        page.waitForFunction(
                            "expected => window.location.pathname === '/agent/sessions/' + expected",
                            deletedSessionId,
                        )
                        page.waitForFunction("() => window.__deletedSessionFetchStarted === true")

                        if (selectedSessionId != thirdSessionId) {
                            page.goBack()
                            page.waitForFunction(
                                "expected => window.location.pathname === '/agent/sessions/' + expected",
                                thirdSessionId,
                            )
                            page.waitForFunction(
                                "expected => activeState?.session.id === expected",
                                thirdSessionId,
                            )
                        }
                        page.goBack()
                        page.waitForFunction(
                            "expected => window.location.pathname === '/agent/sessions/' + expected && " +
                                "activeState?.session.id === expected",
                            selectedSessionId,
                        )
                        assertTrue(page.locator("#session-not-found").isHidden)
                        val staleNotFoundResponse = page.waitForResponse(
                            { response ->
                                response.request().method() == "GET" &&
                                    response.url().endsWith("/api/agent/sessions/$deletedSessionId")
                            },
                            { page.evaluate("() => window.__releaseDeletedSessionFetch()") },
                        )
                        assertEquals(404, staleNotFoundResponse.status())
                        page.waitForFunction(
                            "expected => window.location.pathname === '/agent/sessions/' + expected && " +
                                "activeState?.session.id === expected",
                            selectedSessionId,
                        )
                        assertTrue(page.locator("#session-not-found").isHidden)
                        assertTrue(page.locator("#new-session").isDisabled)

                        page.evaluate("() => window.__releasePostDeleteSessionList()")
                        page.waitForFunction(
                            "expected => window.location.pathname === '/agent/sessions/' + expected && " +
                                "activeState?.session.id === expected",
                            selectedSessionId,
                        )
                        page.waitForFunction(
                            "() => document.querySelector('.agent-toast.success')?.textContent === " +
                                "'Сессия и связанные фоновые задачи удалены.'",
                        )

                        assertEquals(1, confirmations.size)
                        assertTrue(confirmations.single().contains("Удалить сессию"))
                        assertEquals(
                            selectedSessionId,
                            page.evaluate("() => activeState?.session.id").toString(),
                        )
                        val sessionsAfterDelete = JSON.parseToJsonElement(
                            page.evaluate(
                                """
                                    async () => JSON.stringify(
                                      (await (await fetch('/api/agent/sessions')).json()).map(session => session.id)
                                    )
                                """.trimIndent(),
                            ).toString(),
                        ).jsonArray.map { it.jsonPrimitive.content }
                        assertFalse(deletedSessionId in sessionsAfterDelete)
                        assertTrue(selectedSessionId in sessionsAfterDelete)
                    }
                }
            }
        } finally {
            server.stop(1_000, 5_000)
            database.deleteIfExists()
        }
    }

    @Test
    fun deletingCurrentSessionShowsEmptyStateWhenRefreshFails() {
        val database = Files.createTempFile("agent-browser-delete-refresh-failure-", ".sqlite")
        val gateway = FakeAgentGateway()
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
            module(agentDependencies = fakeAgentDependencies(database.toString(), gateway))
        }.start(wait = false)
        try {
            Playwright.create().use { playwright ->
                playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
                    browser.newContext().use { context ->
                        val page = context.newPage()
                        page.navigate("http://127.0.0.1:$port/agent")
                        waitForAgentInitialized(page)
                        page.locator("#empty-new-session").click()
                        waitForSessionReady(page)
                        val deletedSessionId = page.evaluate("() => sessionIdFromLocation()").toString()

                        val confirmations = mutableListOf<String>()
                        page.onDialog { dialog ->
                            confirmations += dialog.message()
                            dialog.accept()
                        }
                        page.evaluate(
                            """
                                () => {
                                  const originalFetch = window.fetch.bind(window);
                                  let deletionCompleted = false;
                                  window.fetch = (input, init) => {
                                    const url = typeof input === 'string' ? input : input.url;
                                    const path = new URL(url, window.location.href).pathname;
                                    const method = (init?.method ||
                                      (input instanceof Request ? input.method : 'GET')).toUpperCase();
                                    if (method === 'DELETE' &&
                                      path === '/api/agent/sessions/' + '$deletedSessionId') {
                                      return originalFetch(input, init).then(response => {
                                        deletionCompleted = response.status === 204;
                                        return response;
                                      });
                                    }
                                    if (deletionCompleted && method === 'GET' &&
                                      path === '/api/agent/sessions') {
                                      return Promise.resolve(new Response(
                                        JSON.stringify({ message: 'synthetic refresh failure' }),
                                        { status: 503, headers: { 'Content-Type': 'application/json' } },
                                      ));
                                    }
                                    return originalFetch(input, init);
                                  };
                                }
                            """.trimIndent(),
                        )
                        val deleteResponse = page.waitForResponse(
                            { response ->
                                response.request().method() == "DELETE" &&
                                    response.url().endsWith("/api/agent/sessions/$deletedSessionId")
                            },
                            { page.locator("#delete-session").click() },
                        )
                        assertEquals(204, deleteResponse.status())
                        page.waitForFunction(
                            "() => document.querySelector('.agent-toast.error')?.textContent === " +
                                "'Сессия удалена, но интерфейс не обновился. Перезагрузите страницу.'",
                        )

                        assertEquals(
                            "/agent",
                            page.evaluate("() => window.location.pathname").toString(),
                        )
                        assertTrue(page.evaluate("() => activeState === null") as Boolean)
                        assertTrue(page.locator("#empty-session").isVisible)
                        assertTrue(page.locator("#agent-workspace").isHidden)
                        assertTrue(page.locator("#delete-session").isHidden)
                        assertTrue(page.locator("#session-not-found").isHidden)
                        assertEquals(1, confirmations.size)
                        assertEquals(
                            "404",
                            page.evaluate(
                                """async id => (await fetch('/api/agent/sessions/' + encodeURIComponent(id))).status""",
                                deletedSessionId,
                            ).toString(),
                        )
                    }
                }
            }
        } finally {
            server.stop(1_000, 5_000)
            database.deleteIfExists()
        }
    }

    @Test
    fun fnsLoginButtonHidesOnlyForConfirmedActiveAuthorization() {
        val database = Files.createTempFile("agent-browser-receipt-auth-", ".sqlite")
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
            module(agentDependencies = fakeAgentDependencies(database.toString()))
        }.start(wait = false)
        try {
            Playwright.create().use { playwright ->
                playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
                    browser.newContext().use { context ->
                        val page = context.newPage()
                        page.navigate("http://127.0.0.1:$port/agent")
                        waitForAgentInitialized(page)
                        val state = page.evaluate(
                            """
                                () => {
                                  const login = document.querySelector("#receipts-browser-login");
                                  const refresh = document.querySelector("#receipts-refresh-session");
                                  const logout = document.querySelector("#receipts-logout");
                                  renderReceiptsAuth({ status: "active", authenticated: false });
                                  const activeButUnconfirmed = login.hidden;
                                  renderReceiptsAuth({ status: "active", authenticated: true });
                                  const confirmed = [login.hidden, refresh.hidden, logout.hidden];
                                  renderReceiptsAuth({ status: "login_required", authenticated: false });
                                  const required = login.hidden;
                                  return [activeButUnconfirmed, ...confirmed, required].join(",");
                                }
                            """.trimIndent(),
                        ).toString()
                        assertEquals("false,true,false,false,false", state)
                    }
                }
            }
        } finally {
            server.stop(1_000, 5_000)
            database.deleteIfExists()
        }
    }

    @Test
    fun receiptRuleAndMatchStatesReachCurrentAndFutureRunsInBrowser() {
        val database = Files.createTempFile("agent-browser-receipt-memory-", ".sqlite")
        val ruleInstruction =
            "Запомни: для пополнений транспортной карты выбирай электронную квитанцию от станции Северная."
        val draftJson = """
            {
              "status": "ready",
              "rejection_reason": null,
              "transactions": [
                {
                  "source_index": 1, "direction": "expense",
                  "occurred_at": "2026-02-08T12:10:00", "posted_at": null,
                  "included": true, "amount_minor": 1000, "currency": "RUB",
                  "merchant": "Synthetic unique operation", "description": "Синтетическая операция 1",
                  "category_id": "food.groceries", "needs_review": false, "issues": []
                },
                {
                  "source_index": 2, "direction": "expense",
                  "occurred_at": "2026-02-08T13:10:00", "posted_at": null,
                  "included": true, "amount_minor": 2000, "currency": "RUB",
                  "merchant": "Synthetic ambiguous operation", "description": "Синтетическая операция 2",
                  "category_id": "food.groceries", "needs_review": false, "issues": []
                },
                {
                  "source_index": 3, "direction": "expense",
                  "occurred_at": "2026-02-08T14:10:00", "posted_at": null,
                  "included": true, "amount_minor": 3000, "currency": "RUB",
                  "merchant": "Synthetic unmatched operation", "description": "Синтетическая операция 3",
                  "category_id": "food.groceries", "needs_review": false, "issues": []
                }
              ],
              "unparsed_fragments": []
            }
        """.trimIndent()
        val gateway = FakeAgentGateway(
            ArrayDeque(listOf(draftJson, """{"intent":"remember_rule"}""")),
        )
        val schedulerRules = mutableListOf<List<String>>()
        val schedulerNow = LocalDate.parse("2026-09-10")
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val baseDependencies = fakeAgentDependencies(
            databasePath = database.toString(),
            gateway = gateway,
            schedulerAccountAvailable = true,
            schedulerNowEpochMs = { schedulerNow },
            schedulerMatchSelector = ReceiptMatchSelector { _, _, choices, _, rules ->
                schedulerRules += rules
                choices.singleOrNull()?.let { ReceiptSelectionResponseView(it.alias, 0.96) }
            },
        )
        var searchCalls = 0
        var detailCalls = 0
        val receiptSource = object : McpToolProvider {
            override suspend fun allowedTools(): List<McpCallableTool> = emptyList()

            override suspend fun callConfiguredTool(
                serverId: String,
                tool: String,
                arguments: JsonObject,
            ): TbankToolCallResponse {
                assertEquals("receipts", serverId)
                return when (tool) {
                    "search-receipts" -> {
                        searchCalls += 1
                        TbankToolCallResponse(
                            tool = tool,
                            text = """
                                {"receipts":[
                                  {"receipt_key":"synthetic-unique-key","merchant":"Северная станция","received_at":"2026-02-08T12:15:00Z","amount_minor":1000,"currency":"RUB"},
                                  {"receipt_key":"synthetic-ambiguous-key-1","merchant":"Северная станция","received_at":"2026-02-08T13:15:00Z","amount_minor":2000,"currency":"RUB"},
                                  {"receipt_key":"synthetic-ambiguous-key-2","merchant":"Южная станция","received_at":"2026-02-08T13:16:00Z","amount_minor":2000,"currency":"RUB"}
                                ],"has_more":false}
                            """.trimIndent(),
                        )
                    }
                    "get-receipt" -> {
                        detailCalls += 1
                        val key = arguments["receipt_key"]?.jsonPrimitive?.content
                        val (amount, merchant, time) = when (key) {
                            "synthetic-unique-key" -> Triple(1000, "Северная станция", "12:15:00")
                            "synthetic-ambiguous-key-1" -> Triple(2000, "Северная станция", "13:15:00")
                            "synthetic-ambiguous-key-2" -> Triple(2000, "Южная станция", "13:16:00")
                            else -> error("Unexpected synthetic receipt key")
                        }
                        TbankToolCallResponse(
                            tool = tool,
                            text = """
                                {"date_time":"2026-02-08T${time}Z","total_minor":$amount,
                                 "currency":"RUB","merchant":"$merchant",
                                 "settlement_place":"Синтетическая торговая точка",
                                 "items":[{"name":"Synthetic receipt item","quantity":1,"price_minor":$amount,"sum_minor":$amount}]}
                            """.trimIndent(),
                        )
                    }
                    else -> error("Unexpected synthetic receipt tool: $tool")
                }
            }
        }
        val currentSelectorRules = mutableListOf<List<String>>()
        val nativeMcpAgent = NativeMcpAgent(
            agent = baseDependencies.agent,
            gatewayResolver = AgentGatewayResolver { gateway },
            mcpTools = receiptSource,
            runtimeConfig = baseDependencies.runtimeConfig,
            receiptMatchSelector = ReceiptMatchSelector { _, transaction, choices, _, rules ->
                currentSelectorRules += rules
                when (transaction.merchant) {
                    "Synthetic unique operation" ->
                        ReceiptSelectionResponseView(choices.single().alias, 0.96)
                    "Synthetic ambiguous operation" ->
                        ReceiptSelectionResponseView(receiptAlias = null, confidence = 0.0)
                    else -> error("An unmatched synthetic transaction must not reach the selector")
                }
            },
        )
        val dependencies = AgentWebDependencies(
            agent = baseDependencies.agent,
            catalog = baseDependencies.catalog,
            runtimeConfig = baseDependencies.runtimeConfig,
            availableProviderIds = baseDependencies.availableProviderIds,
            newSessionTitle = baseDependencies.newSessionTitle,
            mcpCatalog = baseDependencies.mcpCatalog,
            tbankMcp = baseDependencies.tbankMcp,
            nativeMcpAgent = nativeMcpAgent,
            scheduler = baseDependencies.scheduler,
            receiptsProxy = baseDependencies.receiptsProxy,
        )
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
            module(agentDependencies = dependencies)
        }.start(wait = false)
        try {
            Playwright.create().use { playwright ->
                playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
                    browser.newContext().use { context ->
                        val page = context.newPage()
                        page.navigate("http://127.0.0.1:$port/agent")
                        waitForAgentInitialized(page)
                        page.locator("#empty-new-session").click()
                        waitForSessionReady(page)
                        page.locator("#agent-message").fill("Синтетическая выписка")
                        page.locator("#send-message").click()
                        page.locator("#operation-table").waitFor()
                        assertEquals(
                            3,
                            page.locator("#operation-table tbody tr").count(),
                            "Initial draft failed: ${page.locator("#agent-status").textContent()}",
                        )
                        page.locator("#agent-message").fill(ruleInstruction)
                        val associationResponse = page.waitForResponse(
                            { response ->
                                response.request().method() == "POST" &&
                                    response.url().endsWith("/messages")
                            },
                            { page.locator("#send-message").click() },
                        )
                        val associationResponseBody = associationResponse.text()
                        assertTrue(
                            associationResponse.status() in 200..299,
                            "$associationResponseBody; searches=$searchCalls; details=$detailCalls; " +
                                "selectorCalls=${currentSelectorRules.size}; gatewayRequests=${gateway.requests.size}",
                        )
                        page.waitForFunction(
                            "() => document.querySelector('#agent-message')?.value === '' || " +
                                "document.querySelector('#agent-status')?.classList.contains('error')",
                        )
                        assertTrue(
                            page.locator(".receipt-association-status.matched").count() > 0,
                            "Receipt association response: $associationResponseBody",
                        )

                        val matched = page.locator(".receipt-association-status.matched")
                        val ambiguous = page.locator(".receipt-association-status.ambiguous")
                        val unmatched = page.locator(".receipt-association-status.unmatched")
                        matched.first().waitFor()
                        ambiguous.first().waitFor()
                        unmatched.first().waitFor()
                        assertEquals(1, matched.count())
                        assertEquals(1, ambiguous.count())
                        assertEquals(1, unmatched.count())
                        assertEquals("Без чека", unmatched.first().textContent())
                        assertEquals(1, searchCalls)
                        assertEquals(3, detailCalls)
                        assertTrue(currentSelectorRules.all { it.isEmpty() })

                        page.locator("#show-scheduler-panel").click()
                        page.waitForFunction(
                            "() => Array.from(document.querySelector('#scheduler-accounts').options)" +
                                ".some(option => option.value === 'fixture-account')",
                        )
                        page.locator("#scheduler-accounts").selectOption("fixture-account")
                        page.locator("#scheduler-name").fill("Synthetic receipt rule")
                        page.locator("#scheduler-start-date").fill("2026-09-10")
                        page.locator("#scheduler-interval").fill("60")
                        page.locator("#scheduler-submit").click()
                        page.locator(".scheduler-task a[data-session-id]").waitFor()
                        val taskId = page.locator(".scheduler-task").getAttribute("data-task-id")!!
                        val taskCard = page.locator(".scheduler-task[data-task-id='$taskId']")
                        taskCard.locator("button[data-scheduler-action='run']").click()
                        page.waitForFunction(
                            """id => {
                                const card = [...document.querySelectorAll('.scheduler-task')]
                                  .find(item => item.dataset.taskId === id);
                                return card?.textContent.includes('Последний запуск:') || false;
                            }""",
                            taskId,
                        )
                        assertEquals(
                            1,
                            schedulerRules.size,
                            "Scheduler selector was not called. Task: ${taskCard.textContent()}",
                        )
                        assertEquals(listOf(ruleInstruction), schedulerRules.single())
                        taskCard.locator("a[data-session-id]").click()
                        page.waitForFunction(
                            "() => document.querySelector('.agent-app')?.dataset.activePanel === 'sessions' && " +
                                "!document.querySelector('#agent-workspace')?.hidden",
                        )
                        waitForSessionReady(page)
                        val linkedSessionId =
                            page.evaluate("() => window.location.pathname.split('/').pop()").toString()
                        page.locator("#operation-table").waitFor()
                        assertEquals(1, page.locator(".receipt-association-status.matched").count())
                        val linkedState = page.evaluate(
                            """async id => JSON.stringify(
                                await (await fetch('/api/agent/sessions/' + encodeURIComponent(id))).json()
                            )""",
                            linkedSessionId,
                        ).toString()
                        val schedulerRows = Json.parseToJsonElement(linkedState)
                            .jsonObject["draft"]!!.jsonObject["transactions"]!!.jsonArray
                        assertEquals(1, schedulerRows.size)
                        val scheduledItems = schedulerRows.single().jsonObject["transaction"]!!
                            .jsonObject["items"]!!.jsonArray
                        assertEquals(1, scheduledItems.size)
                        assertEquals(
                            "Imported item",
                            scheduledItems.single().jsonObject["name"]?.jsonPrimitive?.content,
                        )
                        assertFalse(linkedState.contains("fixture-receipt-private"))
                    }
                }
            }
        } finally {
            baseDependencies.scheduler?.close()
            server.stop(1_000, 5_000)
            database.deleteIfExists()
        }
    }

    @Test
    fun receiptSourceFailureIsVisibleAsSourceErrorInBrowser() {
        val database = Files.createTempFile("agent-browser-receipt-source-error-", ".sqlite")
        val gateway = FakeAgentGateway(
            ArrayDeque(
                listOf(
                    FakeAgentGateway.READY_DRAFT_JSON,
                    """{"intent":"associate"}""",
                ),
            ),
        )
        val baseDependencies = fakeAgentDependencies(database.toString(), gateway)
        val rawFailure = "synthetic-private-fiscal-response"
        val failingSource = object : McpToolProvider {
            override suspend fun allowedTools(): List<McpCallableTool> = emptyList()

            override suspend fun callConfiguredTool(
                serverId: String,
                tool: String,
                arguments: JsonObject,
            ): TbankToolCallResponse {
                assertEquals("receipts", serverId)
                throw IllegalStateException(rawFailure)
            }
        }
        val nativeMcpAgent = NativeMcpAgent(
            agent = baseDependencies.agent,
            gatewayResolver = AgentGatewayResolver { gateway },
            mcpTools = failingSource,
            runtimeConfig = baseDependencies.runtimeConfig,
            receiptMatchSelector = ReceiptMatchSelector { _, _, _, _, _ ->
                error("Selector must not run after source failure")
            },
        )
        val dependencies = AgentWebDependencies(
            agent = baseDependencies.agent,
            catalog = baseDependencies.catalog,
            runtimeConfig = baseDependencies.runtimeConfig,
            availableProviderIds = baseDependencies.availableProviderIds,
            newSessionTitle = baseDependencies.newSessionTitle,
            mcpCatalog = baseDependencies.mcpCatalog,
            tbankMcp = baseDependencies.tbankMcp,
            nativeMcpAgent = nativeMcpAgent,
            scheduler = baseDependencies.scheduler,
            receiptsProxy = baseDependencies.receiptsProxy,
        )
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
            module(agentDependencies = dependencies)
        }.start(wait = false)
        try {
            Playwright.create().use { playwright ->
                playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
                    browser.newContext().use { context ->
                        val page = context.newPage()
                        page.navigate("http://127.0.0.1:$port/agent")
                        waitForAgentInitialized(page)
                        page.locator("#empty-new-session").click()
                        waitForSessionReady(page)
                        page.locator("#agent-message").fill("Синтетическая выписка")
                        page.locator("#send-message").click()
                        page.locator("#operation-table").waitFor()
                        page.locator("#agent-message").fill(
                            "Сверь операцию «пополнение проездного» с подходящим электронным документом.",
                        )
                        page.locator("#send-message").click()

                        val sourceErrors = page.locator(".receipt-association-status.source_error")
                        sourceErrors.first().waitFor()
                        assertEquals(2, sourceErrors.count())
                        assertEquals("Источник чеков недоступен", sourceErrors.first().textContent())
                        assertFalse(page.locator("body").textContent().contains(rawFailure))
                    }
                }
            }
        } finally {
            baseDependencies.scheduler?.close()
            server.stop(1_000, 5_000)
            database.deleteIfExists()
        }
    }

    @Test
    fun noReceiptMatchIsNeutralAndExportableInBrowser() {
        val database = Files.createTempFile("agent-browser-receipt-unmatched-", ".sqlite")
        val gateway = FakeAgentGateway(
            ArrayDeque(
                listOf(
                    FakeAgentGateway.READY_DRAFT_JSON,
                    """{"intent":"associate"}""",
                ),
            ),
        )
        val baseDependencies = fakeAgentDependencies(database.toString(), gateway)
        var searchCalls = 0
        val emptyReceiptSource = object : McpToolProvider {
            override suspend fun allowedTools(): List<McpCallableTool> = emptyList()

            override suspend fun callConfiguredTool(
                serverId: String,
                tool: String,
                arguments: JsonObject,
            ): TbankToolCallResponse {
                assertEquals("receipts", serverId)
                assertEquals("search-receipts", tool)
                searchCalls += 1
                return TbankToolCallResponse(
                    tool = tool,
                    text = """{"receipts":[],"has_more":false}""",
                )
            }
        }
        val nativeMcpAgent = NativeMcpAgent(
            agent = baseDependencies.agent,
            gatewayResolver = AgentGatewayResolver { gateway },
            mcpTools = emptyReceiptSource,
            runtimeConfig = baseDependencies.runtimeConfig,
            receiptMatchSelector = ReceiptMatchSelector { _, _, _, _, _ ->
                error("The selector must not run when search returns no candidates")
            },
        )
        val dependencies = AgentWebDependencies(
            agent = baseDependencies.agent,
            catalog = baseDependencies.catalog,
            runtimeConfig = baseDependencies.runtimeConfig,
            availableProviderIds = baseDependencies.availableProviderIds,
            newSessionTitle = baseDependencies.newSessionTitle,
            mcpCatalog = baseDependencies.mcpCatalog,
            tbankMcp = baseDependencies.tbankMcp,
            nativeMcpAgent = nativeMcpAgent,
            scheduler = baseDependencies.scheduler,
            receiptsProxy = baseDependencies.receiptsProxy,
        )
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
            module(agentDependencies = dependencies)
        }.start(wait = false)
        try {
            Playwright.create().use { playwright ->
                playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
                    browser.newContext().use { context ->
                        val page = context.newPage()
                        page.navigate("http://127.0.0.1:$port/agent")
                        waitForAgentInitialized(page)
                        page.locator("#empty-new-session").click()
                        waitForSessionReady(page)
                        page.locator("#agent-message").fill("Синтетическая выписка")
                        page.locator("#send-message").click()
                        page.locator("#operation-table").waitFor()

                        val excludedRow = page.locator("tr[data-transaction-id='2']")
                        excludedRow.locator("input[name='included']").uncheck()
                        excludedRow.locator("button[data-action='save-operation']").click()
                        page.waitForFunction(
                            "() => document.querySelector('#build-batch')?.disabled === false",
                        )

                        page.locator("#agent-message").fill(
                            "Сверь пополнение проездного с подходящим электронным документом.",
                        )
                        page.locator("#send-message").click()

                        val unmatched = page.locator(".receipt-association-status.unmatched")
                        unmatched.first().waitFor()
                        assertEquals(2, unmatched.count())
                        assertEquals("Без чека", unmatched.first().textContent())
                        assertEquals("Готово к экспорту", page.locator("#receipt-status").textContent())
                        assertFalse(page.locator("#build-batch").isDisabled)
                        assertEquals(1, searchCalls)
                        val download = page.waitForDownload {
                            page.locator("#build-batch").click()
                        }
                        assertTrue(download.suggestedFilename().endsWith(".json"))
                    }
                }
            }
        } finally {
            baseDependencies.scheduler?.close()
            server.stop(1_000, 5_000)
            database.deleteIfExists()
        }
    }

    private class BrowserReceiptsFixture : ReceiptsMcpProvider {
        var searchCalls = 0
        var detailCalls = 0

        override suspend fun browserLogin() =
            ReceiptsAuthStatus(authenticated = false, status = "authenticating")

        override suspend fun receiptsSession() =
            ReceiptsAuthStatus(
                authenticated = true,
                status = "active",
                persistenceStatus = "memory_only",
                persistenceMessage = "Проверочная session живёт только в памяти.",
            )

        override suspend fun receiptsRetrySession() =
            ReceiptsAuthStatus(authenticated = true, status = "active")

        override suspend fun receiptsLogout() =
            ReceiptsAuthStatus(authenticated = false, status = "login_required")

        override suspend fun callReceiptTool(
            tool: String,
            arguments: JsonObject,
        ): TbankToolCallResponse = when (tool) {
            "search-receipts" -> {
                searchCalls += 1
                TbankToolCallResponse(
                    tool = tool,
                    text = """{"receipts":[{"receipt_key":"$RAW_KEY","merchant":"Кофейня у дома","received_at":"2026-09-10T10:00:00Z","amount_minor":49900,"currency":"RUB"}],"has_more":false}""",
                )
            }
            "get-receipt" -> {
                detailCalls += 1
                TbankToolCallResponse(
                    tool = tool,
                    text = """{"receipt_key":"$RAW_KEY","date_time":"2026-09-10T10:00:00Z","fiscal_document_number":"123","total_minor":49900,"currency":"RUB","items":[{"name":"Кофе","quantity":1,"price_minor":49900,"sum_minor":49900}]}""",
                )
            }
            else -> TbankToolCallResponse(tool = tool, isError = true, text = "unexpected receipt tool")
        }

        companion object {
            const val RAW_KEY = "browser-receipt-raw-secret"
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
