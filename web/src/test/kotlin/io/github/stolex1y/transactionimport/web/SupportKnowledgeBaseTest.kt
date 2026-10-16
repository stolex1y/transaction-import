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
}
