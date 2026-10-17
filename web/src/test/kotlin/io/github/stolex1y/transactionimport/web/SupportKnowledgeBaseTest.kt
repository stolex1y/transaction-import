package io.github.stolex1y.transactionimport.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupportKnowledgeBaseTest {
    @Test
    fun retrievesFAQEvidenceForImportQuestionAndReturnsItsSource() {
        val passage = SupportKnowledgeBase.load()
            .retrieve("Как начать новый импорт банковской выписки?")
            .first()

        assertEquals("support-faq.md", passage.citation.sourceId)
        assertEquals("Как начать новый импорт выписки?", passage.citation.section)
        assertTrue(passage.text.contains("Новый импорт"))
    }

    @Test
    fun doesNotRetrieveForUnrelatedQuestion() {
        assertTrue(SupportKnowledgeBase.load().retrieve("Как испечь хлеб на закваске?").isEmpty())
    }

    @Test
    fun returnsOnlyTheConfiguredPassageLimit() {
        val passages = SupportKnowledgeBase.load()
            .retrieve("импорт выписки сессия экспорт JSON операции", limit = 2)

        assertEquals(2, passages.size)
        assertFalse(passages.any { it.text.isBlank() })
    }

    @Test
    fun retrievesRelevantEvidenceAcrossDocumentedUserFacingAreas() {
        val knowledgeBase = SupportKnowledgeBase.load()
        val cases = listOf(
            "Как начать новый импорт выписки?" to "Как начать новый импорт выписки?",
            "Можно загрузить банковскую выписку файлом?" to "Можно ли загрузить выписку файлом?",
            "Как продолжить прошлую сессию или начать независимый импорт?" to
                "Как продолжить импорт или начать независимую обработку?",
            "Что означают состояния выписки и ошибки?" to "Что означают состояния выписки?",
            "Как исправить операцию или её описание?" to "Как исправить операцию или её описание?",
            "Как скачать только выбранные валидные операции?" to "Как скачать только нужные операции?",
            "Как переключить таблицу, карточки и тему?" to "Как переключить таблицу, карточки и тему?",
            "Где настроить provider, model и reasoning mode?" to "Где настроить provider, model и reasoning mode?",
            "Как добавить категорию расхода и правило для варианта названия магазина?" to
                "Как управлять категориями и правилами магазинов?",
            "Что показывает панель Memory layers для текущего запроса?" to "Что показывает панель Memory layers?",
            "Как посмотреть input, ответ и лимит контекста?" to "Что означают показатели токенов?",
            "Как создать периодическое задание, поставить его на паузу и посмотреть историю?" to
                "Для чего нужны фоновые задачи?",
            "Что показывает панель MCP и как обновить подключения?" to "Что показывает панель MCP?",
            "Как войти в Т-Банк MCP и загрузить операции по счёту?" to
                "Как подключить Т-Банк и получить операции?",
            "Как найти чек по датам и продавцу в ФНС?" to "Как искать чеки?",
            "Как сопоставить чек с банковской операцией?" to "Как связать чек с банковской операцией?",
            "Можно ли отправлять реальные финансовые данные внешнему LLM provider?" to
                "Можно ли отправлять реальные финансовые данные внешнему LLM provider?",
            "Поддержка отвечает на вопросы об интерфейсе" to "Что делает вкладка «Поддержка»?",
            "Почему ассистент не ответил на мой вопрос?" to "Почему ассистент не ответил на вопрос?",
            "Как приложение превращает текст банковской выписки в редактируемый черновик?" to "Что делает Transaction Import?",
            "Как различить расход, доход и возврат?" to "Что означают расход, доход и возврат?",
            "Что показывает Sticky facts для сессии?" to "Что такое Sticky facts?",
            "Как создать ветку и скопировать сообщение?" to "Как создать ветку и скопировать сообщение?",
            "Можно ли общими инструкциями изменить системные инварианты?" to
                "Могут ли общие инструкции изменить системные инварианты?",
        )

        for ((query, expectedSection) in cases) {
            assertTrue(
                knowledgeBase.retrieve(query).any { it.citation.section == expectedSection },
                "No relevant support passage found for: $query",
            )
        }
    }

}
