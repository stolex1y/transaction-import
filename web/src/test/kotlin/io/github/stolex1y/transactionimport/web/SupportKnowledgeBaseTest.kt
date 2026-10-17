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
    fun doesNotInventEvidenceForUnsupportedAuthenticationQuestion() {
        assertTrue(SupportKnowledgeBase.load().retrieve("Почему не работает авторизация?").isEmpty())
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
            "Как исправить операцию или её описание?" to "Как исправить операцию или её описание?",
            "Как скачать только нужные операции?" to "Как скачать только нужные операции?",
            "Где изменить provider, model, reasoning mode, категории и merchant rules?" to "Настройки",
            "Как поставить периодическое задание на паузу, возобновить и посмотреть историю запусков?" to
                "Фоновые задачи",
            "Что показывает панель MCP и как обновить список интеграций?" to "MCP",
            "Можно ли отправлять реальные финансовые данные внешнему LLM provider?" to
                "Можно ли отправлять реальные финансовые данные внешнему LLM provider?",
        )

        for ((query, expectedSection) in cases) {
            assertTrue(
                knowledgeBase.retrieve(query).any { it.citation.section == expectedSection },
                "No relevant support passage found for: $query",
            )
        }
    }

}
