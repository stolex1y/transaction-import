package io.github.stolex1y.transactionimport.web

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupportBrowserTest {
    @Test
    fun a01SupportAnswerKeepsRagEvidenceHiddenAndRestoresChronologicalHistory() = withSupportApp(
        listOf(ANSWER, ANSWER),
    ) { fixture, page ->
        openSupport(page, fixture.baseUrl)
        sendQuestion(page, QUESTION)

        val firstAnswer = page.locator("#support-message-list .support-message.assistant").last()
        assertTrue(firstAnswer.textContent().contains("Новый импорт"))
        assertTrue(firstAnswer.textContent().contains("Есть ли ещё вопросы?"))
        assertEquals(0, page.locator(".support-citations").count())
        assertNoTicketMetadata(page)

        clickAndWaitForSupportPost(page, "Продолжить с ответом «Да»") {
            page.locator("#support-quick-replies button").first().click()
        }
        sendQuestion(page, QUESTION)
        assertEquals(6, page.locator("#support-message-list .support-message").count())
        val transcriptBeforeReload = page.locator("#support-message-list").innerText()

        page.reload()
        waitForInitialization(page)
        assertEquals("true", page.locator("#show-support-panel").getAttribute("aria-pressed"))
        page.waitForFunction(
            "() => document.querySelectorAll('#support-message-list .support-message').length === 6",
        )
        assertEquals(transcriptBeforeReload, page.locator("#support-message-list").innerText())
        assertEquals(0, page.locator(".support-citations").count())
        assertNoTicketMetadata(page)
    }

    @Test
    fun a02SupportFollowUpYesContinuesAndNoClosesTheInternalTicket() = withSupportApp(
        listOf(ANSWER, ANSWER),
    ) { fixture, page ->
        openSupport(page, fixture.baseUrl)
        sendQuestion(page, QUESTION)
        clickAndWaitForSupportPost(page, "Выбрать ответ «Да»") {
            page.locator("#support-quick-replies button").first().click()
        }
        assertEquals(listOf("open"), fixture.ticketStatuses())
        sendQuestion(page, QUESTION)
        clickAndWaitForSupportPost(page, "Выбрать ответ «Нет»") {
            page.locator("#support-quick-replies button").last().click()
        }

        assertEquals(
            "Хорошо! Если появятся вопросы, напишите их здесь.",
            page.locator("#support-message-list .support-message.assistant").last().locator("p").textContent(),
        )
        assertEquals(listOf("closed"), fixture.ticketStatuses())
        assertEquals(0, page.locator("#support-quick-replies button").count())
        assertNoTicketMetadata(page)
    }

    @Test
    fun supportSelectionSurvivesReloadFromAnImportSessionUrl() = withSupportApp(emptyList()) { fixture, page ->
        page.navigate("${fixture.baseUrl}/agent")
        waitForInitialization(page)
        page.locator("#new-session").click()
        page.waitForFunction("() => window.location.pathname.startsWith('/agent/sessions/')")
        val sessionUrl = page.url()

        page.locator("#show-support-panel").click()
        page.waitForFunction("() => window.location.pathname === '/agent'")
        assertEquals("true", page.locator("#show-support-panel").getAttribute("aria-pressed"))
        page.reload()
        waitForInitialization(page)
        assertEquals("true", page.locator("#show-support-panel").getAttribute("aria-pressed"))
        page.locator("#open-session-drawer").click()
        page.waitForFunction("() => document.querySelectorAll('#session-list button').length > 0")
        page.locator("#session-list button").first().click()
        page.waitForFunction("() => window.location.pathname.startsWith('/agent/sessions/')")
        assertEquals(sessionUrl, page.url())
        assertTrue(page.locator("#agent-workspace").isVisible())

        page.locator("#show-support-panel").click()
        page.waitForFunction("() => window.location.pathname === '/agent'")
        assertEquals("true", page.locator("#show-support-panel").getAttribute("aria-pressed"))

        val activeSessionId = page.evaluate("() => localStorage.getItem('smart-expense-active-session')")
        page.goBack()
        page.waitForFunction("() => window.location.pathname.startsWith('/agent/sessions/')")
        assertEquals(sessionUrl, page.url())
        assertEquals("true", page.locator("#open-session-drawer").getAttribute("aria-pressed"))

        page.goForward()
        page.waitForFunction(
            "() => window.location.pathname === '/agent' && " +
                "document.querySelector('#show-support-panel')?.getAttribute('aria-pressed') === 'true'",
        )
        assertEquals(activeSessionId, page.evaluate("() => localStorage.getItem('smart-expense-active-session')"))
    }

    @Test
    fun staleSupportHistoryResponseCannotOverwriteSuccessfulMessage() = withSupportApp(
        listOf(ANSWER),
    ) { fixture, page ->
        page.addInitScript(
            """
            (() => {
              const originalFetch = window.fetch.bind(window);
              let held = false;
              let release;
              window.__supportHistoryCaptured = false;
              window.__supportHistoryBodyRead = false;
              window.__releaseSupportHistoryResponse = () => release?.();
              window.fetch = async (input, init = {}) => {
                const url = typeof input === "string" ? input : input.url;
                const method = (init.method || (typeof input === "string" ? "" : input.method) || "GET").toUpperCase();
                const response = await originalFetch(input, init);
                if (held || method !== "GET" || !url.endsWith("/api/agent/support/messages")) {
                  return response;
                }
                held = true;
                window.__supportHistoryCaptured = true;
                await new Promise((resolve) => { release = resolve; });
                const readText = response.text.bind(response);
                response.text = async () => {
                  const text = await readText();
                  window.__supportHistoryBodyRead = true;
                  return text;
                };
                return response;
              };
            })();
            """.trimIndent(),
        )
        openSupport(page, fixture.baseUrl)
        page.waitForFunction("() => window.__supportHistoryCaptured === true")

        sendQuestion(page, QUESTION)
        assertEquals(2, page.locator("#support-message-list .support-message").count())
        assertEquals(2, page.locator("#support-quick-replies button").count())

        page.evaluate("() => window.__releaseSupportHistoryResponse()")
        page.waitForFunction("() => window.__supportHistoryBodyRead === true")
        page.evaluate("() => new Promise((resolve) => requestAnimationFrame(() => resolve(true)))")
        assertEquals(2, page.locator("#support-message-list .support-message").count())
        assertTrue(page.locator("#support-message-list").textContent().contains("Новый импорт"))
        assertEquals(2, page.locator("#support-quick-replies button").count())
        assertNoTicketMetadata(page)
    }

    @Test
    fun ticketStoreFailureDoesNotHidePersistedChatHistory() = withSupportApp(
        listOf(ANSWER),
    ) { fixture, page ->
        openSupport(page, fixture.baseUrl)
        sendQuestion(page, QUESTION)
        fixture.breakTicketStore()

        page.reload()
        waitForInitialization(page)
        page.waitForFunction(
            "() => document.querySelectorAll('#support-message-list .support-message').length === 2",
        )
        assertTrue(page.locator("#support-status").textContent().contains("временно недоступен"))
        assertTrue(page.locator("#support-message-list").textContent().contains("Новый импорт"))
        assertNoTicketMetadata(page)
    }

    @Test
    fun a03ClosedTicketIsReopenedWhenTheFakeRouterChoosesContinuation() = withSupportApp(
        listOf(ANSWER, """{"decision":"reopen_previous"}""", ANSWER),
    ) { fixture, page ->
        openSupport(page, fixture.baseUrl)
        closeFirstTicket(page)
        sendQuestion(page, "Как продолжить прежний вопрос про импорт?")

        assertEquals(listOf("open"), fixture.ticketStatuses())
        assertEquals(1, fixture.ticketCount())
        assertTrue(
            page.locator("#support-message-list .support-message.assistant").last().textContent()
                .contains("Есть ли ещё вопросы?"),
        )
        assertNoTicketMetadata(page)
    }

    @Test
    fun a03ClosedTicketIsNotReopenedWhenTheFakeRouterChoosesNewQuestion() = withSupportApp(
        listOf(ANSWER, """{"decision":"new"}""", ANSWER),
    ) { fixture, page ->
        openSupport(page, fixture.baseUrl)
        closeFirstTicket(page)
        sendQuestion(page, "Как начать новый импорт банковской выписки?")

        assertEquals(listOf("closed", "open"), fixture.ticketStatuses())
        assertEquals(2, fixture.ticketCount())
        assertNoTicketMetadata(page)
    }

    @Test
    fun a03AmbiguousRouteAsksBeforeChangingTicketAndAcceptsClarification() = withSupportApp(
        listOf(
            ANSWER,
            """{"decision":"clarify","question":"Вы продолжаете прежний вопрос об импорте?"}""",
            """{"decision":"reopen_previous"}""",
            ANSWER,
        ),
    ) { fixture, page ->
        openSupport(page, fixture.baseUrl)
        closeFirstTicket(page)
        sendQuestion(page, "Как продолжить импорт?")
        assertEquals(listOf("closed"), fixture.ticketStatuses())
        assertTrue(
            page.locator("#support-message-list .support-message.assistant").last().textContent()
                .contains("Вы продолжаете прежний вопрос"),
        )

        clickAndWaitForSupportPost(page, "Ответить на уточнение") {
            page.locator("#support-quick-replies button").first().click()
        }
        assertEquals(listOf("open"), fixture.ticketStatuses())
        assertEquals(1, fixture.ticketCount())
        assertNoTicketMetadata(page)
    }

    private fun openSupport(page: Page, baseUrl: String) {
        page.navigate("$baseUrl/agent")
        waitForInitialization(page)
        page.locator("#show-support-panel").click()
        page.locator("#support-message-list").waitFor()
        page.waitForFunction("() => document.querySelector('#support-status')?.textContent === ''")
    }

    private fun waitForInitialization(page: Page) {
        page.waitForFunction(
            "() => typeof initialize === 'function' && document.querySelector('#empty-new-session')?.disabled === false",
        )
    }

    private fun sendQuestion(page: Page, message: String) {
        val expectedCount = page.locator("#support-message-list .support-message").count() + 2
        page.locator("#support-input").fill(message)
        clickAndWaitForSupportPost(page, "Отправить вопрос") {
            page.locator("#support-send").click()
        }
        page.waitForFunction(
            "() => document.querySelectorAll('#support-message-list .support-message').length >= $expectedCount",
        )
    }

    private fun clickAndWaitForSupportPost(page: Page, action: String, click: () -> Unit) {
        val response = page.waitForResponse(
            { candidate ->
                candidate.request().method() == "POST" &&
                    candidate.url().endsWith("/api/agent/support/messages")
            },
            click,
        )
        assertEquals(200, response.status(), "$action failed: ${page.locator("#support-status").textContent()}")
    }

    private fun closeFirstTicket(page: Page) {
        sendQuestion(page, QUESTION)
        clickAndWaitForSupportPost(page, "Закрыть первое обращение") {
            page.locator("#support-quick-replies button").last().click()
        }
    }

    private fun assertNoTicketMetadata(page: Page) {
        val visibleText = page.locator("body").innerText()
        assertFalse(visibleText.contains("support-ticket-"), visibleText)
        assertFalse(visibleText.contains("ticket_id", ignoreCase = true), visibleText)
        assertFalse(visibleText.contains("ticketId", ignoreCase = true), visibleText)
        assertFalse(visibleText.contains("closed_at_epoch_ms", ignoreCase = true), visibleText)
    }

    private fun withSupportApp(
        responses: List<String>,
        test: (SupportBrowserAppFixture, Page) -> Unit,
    ) {
        SupportBrowserAppFixture(responses).use { fixture ->
            Playwright.create().use { playwright ->
                playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
                    browser.newContext(
                        Browser.NewContextOptions().setViewportSize(1_280, 900),
                    ).use { context ->
                        test(fixture, context.newPage())
                    }
                }
            }
        }
    }

    private companion object {
        const val QUESTION = "Как начать новый импорт банковской выписки?"
        const val ANSWER = "Нажмите «Новый импорт», чтобы открыть пустую сессию."
    }
}
