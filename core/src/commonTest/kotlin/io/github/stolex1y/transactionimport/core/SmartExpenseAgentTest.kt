package io.github.stolex1y.transactionimport.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmartExpenseAgentTest {
    @Test
    fun preservesStateAcrossConfigChangesAndMasksOnlyExplicitPhones() = runBlocking {
        val gateway = QueuedGateway(initialDraftJson, excludePatchJson, notApplicableJson)
        val repository = MemoryImportSessionRepository()
        val agent = testAgent(repository, gateway)
        val created = agent.createSession("Синтетическая выписка", defaultConfig)
        val storedPreference = agent.updatePreferences(
            "Не выбирай переводы. Мой телефон: +7 (912) 345-67-89.",
        )

        val extracted = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = created.session.revision,
            text = "Телефон +7 (999) 111-22-33; reference 1234567890123456; DEMO MARKET 1250 RUB",
        )
        val reconfigured = agent.updateSessionConfig(
            sessionId = created.session.id,
            expectedRevision = extracted.session.revision,
            config = defaultConfig.copy(modelId = "next-model"),
        )
        val corrected = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = reconfigured.session.revision,
            text = "Не включай 2",
        )
        val secondSession = agent.createSession("Другая сессия", defaultConfig)
        agent.sendMessage(secondSession.session.id, 0, "Как приготовить суп?")

        assertFalse(storedPreference.userPrompt.contains("912"))
        assertTrue(storedPreference.userPrompt.contains("67-89"))
        assertEquals(3, gateway.requests.size)
        assertEquals(100_000, gateway.requests[0].maxTokens)
        assertNull(gateway.requests[0].temperature)
        assertTrue(gateway.requests[0].messages.first().content.contains("Не выбирай переводы"))
        assertFalse(gateway.requests[0].messages.first().content.contains("912"))
        assertFalse(gateway.requests[0].messages.last().content.contains("999"))
        assertTrue(gateway.requests[0].messages.last().content.contains("22-33"))
        assertTrue(gateway.requests[0].messages.last().content.contains("1234567890123456"))
        assertFalse(extracted.messages.first().content.contains("999"))
        assertTrue(extracted.messages.first().displayText.contains("22-33"))
        assertTrue(gateway.requests[2].messages.first().content.contains("Не выбирай переводы"))
        assertFalse(gateway.requests[2].messages.first().content.contains("912"))

        assertEquals("next-model", gateway.requests[1].model)
        assertEquals(listOf("system", "system", "user", "assistant", "user"), gateway.requests[1].messages.map { it.role })
        assertEquals(4, corrected.messages.size)
        assertTrue(corrected.draft!!.transactions.single { it.id == "1" }.included)
        assertFalse(corrected.draft!!.transactions.single { it.id == "2" }.included)
    }

    @Test
    fun emptyGlobalPromptDoesNotAddPreferenceBlockToNewSession() = runBlocking {
        val gateway = QueuedGateway(initialDraftJson)
        val agent = testAgent(MemoryImportSessionRepository(), gateway)
        val created = agent.createSession("Проверка телефона", defaultConfig)

        agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = created.session.revision,
            text = "Внешний перевод по номеру телефона +7 999 111-22-33",
        )

        val systemPrompt = gateway.requests.single().messages.first().content
        assertFalse(systemPrompt.contains("<user-preference>"))
        assertTrue(systemPrompt.contains("prove who owns a number"))
        assertTrue(systemPrompt.contains("internal or external"))
    }

    @Test
    fun localizesEnglishModelIssuesBeforeDisplayingDraftErrors() = runBlocking {
        val gateway = QueuedGateway(englishIssuesDraftJson)
        val agent = testAgent(MemoryImportSessionRepository(), gateway)
        val created = agent.createSession("Локализация ошибок", defaultConfig)

        val result = agent.sendMessage(created.session.id, 0, "Синтетическая выписка")
        val row = result.draft!!.transactions.single()

        assertEquals(
            listOf(
                "category_id: Пополнение инвестиционного счёта не входит в справочник категорий.",
                "category_id: Категория выведена из контекста; операция может относиться к досугу, а не к транспорту.",
            ),
            row.transaction.issues,
        )
        assertTrue(row.fieldErrors.getValue("category_id").all { !it.contains("category catalog") })
    }

    @Test
    fun acceptsAppliedNoOpWithoutError() = runBlocking {
        val gateway = QueuedGateway(initialDraftJson, emptyAppliedPatchJson)
        val agent = testAgent(MemoryImportSessionRepository(), gateway)
        val created = agent.createSession("Пустая правка", defaultConfig)

        val extracted = agent.sendMessage(created.session.id, 0, "Синтетическая выписка")
        val result = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = extracted.session.revision,
            text = "Проверь ещё раз",
        )

        assertEquals(extracted.draft!!.transactions, result.draft!!.transactions)
        assertEquals("Изменений не найдено.", result.messages.last().displayText)
        assertEquals(extracted.session.revision + 1, result.session.revision)
    }

    @Test
    fun merchantPromptRequiresReviewingEveryKnownName() = runBlocking {
        val gateway = QueuedGateway(initialDraftJson)
        val agent = testAgent(MemoryImportSessionRepository(), gateway)
        val created = agent.createSession("Названия магазинов", defaultConfig)

        agent.sendMessage(created.session.id, 0, "Выписка с DIXY и LYUDI LYUBYAT")

        val prompt = gateway.requests.single().messages.first().content
        assertTrue(prompt.contains("Review every merchant"))
        assertTrue(prompt.contains("DIXY -> Дикси"))
        assertTrue(prompt.contains("unambiguous Russian transliteration"))
    }

    @Test
    fun rejectsUnsupportedPhoneOwnershipInference() = runBlocking {
        val gateway = QueuedGateway(phoneTransferDraftJson)
        val agent = testAgent(MemoryImportSessionRepository(), gateway)
        val created = agent.createSession("Перевод по телефону", defaultConfig)

        val result = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = created.session.revision,
            text = "Выписка с переводом по номеру телефона",
        )
        val transaction = result.draft!!.transactions.single().transaction

        assertNull(transaction.categoryId)
        assertTrue(transaction.needsReview)
        assertEquals(
            listOf("category_id: Невозможно определить тип перевода по выписке."),
            transaction.issues,
        )
    }

    @Test
    fun validCategoryPatchClearsPreviousReviewState() = runBlocking {
        val gateway = QueuedGateway(reviewDraftJson, categoryPatchJson)
        val agent = testAgent(MemoryImportSessionRepository(), gateway)
        val created = agent.createSession("Исправление категории", defaultConfig)

        val extracted = agent.sendMessage(created.session.id, 0, "Синтетическая выписка")
        val patched = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = extracted.session.revision,
            text = "Отнеси операцию 1 к продуктам.",
        )

        val row = patched.draft!!.transactions.single()
        assertEquals("food.groceries", row.transaction.categoryId)
        assertFalse(row.transaction.needsReview)
        assertTrue(row.transaction.issues.isEmpty())
        assertTrue(row.fieldErrors.isEmpty())
        assertTrue(agent.getSession(created.session.id).draft!!.transactions.single().fieldErrors.isEmpty())
    }

    @Test
    fun normalizesMerchantInDraftAndPreparedBatch() = runBlocking {
        val gateway = QueuedGateway(initialDraftJson)
        val agent = testAgent(MemoryImportSessionRepository(), gateway)
        val created = agent.createSession("Нормализация merchant", defaultConfig)
        val extracted = agent.sendMessage(created.session.id, 0, "Синтетическая выписка")

        val replaced = agent.replaceTransaction(
            sessionId = created.session.id,
            expectedRevision = extracted.session.revision,
            transactionId = "1",
            included = true,
            description = "",
            replacement = extracted.draft!!.transactions.first().transaction.copy(
                merchant = "COFFEEBON 37 SANKT-PETERBU RUS",
            ),
        )

        assertEquals(
            "COFFEEBON",
            replaced.draft!!.transactions.first().transaction.merchant,
        )
        assertEquals(
            "COFFEEBON",
            agent.buildImportBatch(created.session.id).transactions.first().merchant,
        )
        assertEquals("DIXY", normalizeMerchantLabel("DIXY-78383D"))
    }

    @Test
    fun merchantNormalizationPreservesSourceLanguageAndBrands() {
        assertEquals("Оплата в ДИКСИ", normalizeMerchantLabel("Оплата в ДИКСИ"))
        assertEquals("Coca-Cola", normalizeMerchantLabel("Coca-Cola"))
    }

    @Test
    fun globalPreferenceIsPresentInInitialAndPatchRequests() = runBlocking {
        val gateway = QueuedGateway(initialDraftJson, excludePatchJson)
        val repository = MemoryImportSessionRepository()
        val agent = testAgent(repository, gateway)
        val created = agent.createSession("Общие правила", defaultConfig)
        agent.updatePreferences("Не включай переводы по телефону.")

        val extracted = agent.sendMessage(created.session.id, 0, "Синтетическая выписка")
        agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = extracted.session.revision,
            text = "Перепроверь выбор.",
        )

        assertTrue(gateway.requests[0].messages.first().content.contains("Не включай переводы"))
        assertTrue(gateway.requests[1].messages.first().content.contains("Не включай переводы"))
        assertTrue(gateway.requests[1].messages.first().content.contains("initial extraction"))
    }

    @Test
    fun blocksOnlyIncludedInvalidRowsAndKeepsLocalDescription() = runBlocking {
        val gateway = QueuedGateway(initialDraftJson)
        val repository = MemoryImportSessionRepository()
        val agent = testAgent(repository, gateway)
        val created = agent.createSession("Редактирование", defaultConfig)
        val extracted = agent.sendMessage(created.session.id, 0, "Синтетическая выписка")
        val first = extracted.draft!!.transactions.first().transaction
        val invalid = first.copy(
            occurredAt = "",
            amountMinor = -1,
            categoryId = null,
            cardLast4 = null,
        )

        val withErrors = agent.replaceTransaction(
            sessionId = created.session.id,
            expectedRevision = extracted.session.revision,
            transactionId = "1",
            included = true,
            description = "Личная заметка",
            replacement = invalid,
        )
        val invalidRow = withErrors.draft!!.transactions.first()
        assertTrue(invalidRow.fieldErrors.keys.containsAll(listOf("occurred_at", "amount_minor", "category_id")))
        assertFalse(invalidRow.fieldErrors.containsKey("card_last4"))
        assertFailsWith<IllegalArgumentException> { agent.buildImportBatch(created.session.id) }

        val excluded = agent.replaceTransaction(
            sessionId = created.session.id,
            expectedRevision = withErrors.session.revision,
            transactionId = "1",
            included = false,
            description = "Личная заметка",
            replacement = invalid,
        )
        val second = excluded.draft!!.transactions.last().transaction
        val described = agent.replaceTransaction(
            sessionId = created.session.id,
            expectedRevision = excluded.session.revision,
            transactionId = "2",
            included = true,
            description = "Поездка на встречу",
            replacement = second.copy(cardLast4 = null),
        )

        val batch = agent.buildImportBatch(created.session.id)
        assertEquals(1, batch.transactions.size)
        assertEquals(2, batch.transactions.single().sourceIndex)
        assertEquals("Поездка на встречу", batch.transactions.single().description)
        assertNull(batch.transactions.single().cardLast4)
        assertEquals("RUB", batch.transactions.single().currency)
        assertEquals(described.session.revision, agent.getSession(created.session.id).session.revision)
    }

    @Test
    fun localizesNotApplicableInput() = runBlocking {
        val agent = testAgent(MemoryImportSessionRepository(), QueuedGateway(notApplicableJson))
        val created = agent.createSession("Нефинансовый текст", defaultConfig)

        val result = agent.sendMessage(created.session.id, 0, "Как приготовить суп?")

        assertEquals(NOT_APPLICABLE_MESSAGE, result.draft!!.rejectionReason)
        assertEquals(NOT_APPLICABLE_MESSAGE, result.messages.last().displayText)
        assertTrue(result.draft!!.transactions.isEmpty())
    }

    private fun testAgent(
        repository: MemoryImportSessionRepository,
        gateway: QueuedGateway,
    ): SmartExpenseAgent {
        var nextId = 0
        var now = 1_000L
        return SmartExpenseAgent(
            repository = repository,
            gatewayResolver = AgentGatewayResolver { gateway },
            idGenerator = { "id-${++nextId}" },
            nowEpochMs = { ++now },
            runtimeConfig = AgentRuntimeConfig(maxTokens = 100_000),
        )
    }

    private class QueuedGateway(vararg responseContents: String) : ChatCompletionGateway {
        private val responses = responseContents.toMutableList()
        val requests = mutableListOf<ChatCompletionRequest>()

        override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
            requests += request
            return ChatCompletionResponse(
                choices = listOf(
                    ChatChoice(
                        message = ResponseMessage(content = responses.removeFirst()),
                        finishReason = "stop",
                    ),
                ),
            )
        }
    }

    private class MemoryImportSessionRepository : ImportSessionRepository {
        private val states = linkedMapOf<String, ImportSessionState>()
        private var preferences: UserPreferences? = null

        override suspend fun create(session: ImportSession): ImportSessionState =
            ImportSessionState(session, emptyList()).also { states[session.id] = it }

        override suspend fun list(): List<ImportSession> = states.values.map { it.session }

        override suspend fun get(id: String): ImportSessionState? = states[id]

        override suspend fun saveExchange(
            sessionId: String,
            expectedRevision: Long,
            userMessage: ConversationMessage,
            assistantMessage: ConversationMessage,
            draft: ImportDraft,
            updatedAtEpochMs: Long,
        ): ImportSessionState = update(sessionId, expectedRevision, updatedAtEpochMs, draft) { state ->
            state.messages + userMessage + assistantMessage
        }

        override suspend fun saveDraft(
            sessionId: String,
            expectedRevision: Long,
            draft: ImportDraft,
            updatedAtEpochMs: Long,
        ): ImportSessionState = update(sessionId, expectedRevision, updatedAtEpochMs, draft) { it.messages }

        override suspend fun saveConfig(
            sessionId: String,
            expectedRevision: Long,
            config: AgentConfig,
            updatedAtEpochMs: Long,
        ): ImportSessionState {
            val current = checkedState(sessionId, expectedRevision)
            return current.copy(
                session = current.session.copy(
                    config = config,
                    revision = expectedRevision + 1,
                    updatedAtEpochMs = updatedAtEpochMs,
                ),
                draft = current.draft?.copy(version = expectedRevision + 1),
            ).also { states[sessionId] = it }
        }

        override suspend fun delete(sessionId: String, expectedRevision: Long) {
            checkedState(sessionId, expectedRevision)
            states.remove(sessionId)
        }

        override suspend fun getOrCreatePreferences(defaultUserPrompt: String): UserPreferences =
            preferences ?: UserPreferences(defaultUserPrompt).also { preferences = it }

        override suspend fun savePreferences(preferences: UserPreferences): UserPreferences =
            preferences.also { this.preferences = it }

        private fun update(
            sessionId: String,
            expectedRevision: Long,
            updatedAtEpochMs: Long,
            draft: ImportDraft,
            messages: (ImportSessionState) -> List<ConversationMessage>,
        ): ImportSessionState {
            val current = checkedState(sessionId, expectedRevision)
            val updated = ImportSessionState(
                session = current.session.copy(
                    revision = expectedRevision + 1,
                    updatedAtEpochMs = updatedAtEpochMs,
                    hasDraft = true,
                ),
                messages = messages(current),
                draft = draft,
            )
            states[sessionId] = updated
            return updated
        }

        private fun checkedState(sessionId: String, expectedRevision: Long): ImportSessionState {
            val current = states[sessionId] ?: throw SessionNotFoundException(sessionId)
            if (current.session.revision != expectedRevision) {
                throw RevisionConflictException(expectedRevision, current.session.revision)
            }
            return current
        }
    }

    private companion object {
        val defaultConfig = AgentConfig("fake", "fake-model", "disabled")

        val initialDraftJson = """
            {
              "status": "ready",
              "rejection_reason": null,
              "transactions": [
                {
                  "source_index": 1,
                  "included": true,
                  "direction": "expense",
                  "occurred_at": "2026-01-15T12:10:00",
                  "posted_at": null,
                  "amount_minor": 125050,
                  "currency": "RUB",
                  "merchant": "DEMO MARKET",
                  "category_id": "food.groceries",
                  "card_last4": "1234",
                  "needs_review": false,
                  "issues": []
                },
                {
                  "source_index": 2,
                  "included": true,
                  "direction": "expense",
                  "occurred_at": "2026-01-16T08:30:00",
                  "posted_at": null,
                  "amount_minor": 45000,
                  "currency": "RUB",
                  "merchant": "DEMO TAXI",
                  "category_id": "transport",
                  "card_last4": null,
                  "needs_review": false,
                  "issues": []
                }
              ],
              "unparsed_fragments": []
            }
        """.trimIndent()

        val englishIssuesDraftJson = """
            {
              "status": "ready",
              "rejection_reason": null,
              "transactions": [
                {
                  "source_index": 1,
                  "included": true,
                  "direction": "expense",
                  "occurred_at": "2026-01-15T12:10:00",
                  "posted_at": null,
                  "amount_minor": 125050,
                  "currency": "RUB",
                  "merchant": "DEMO MARKET",
                  "category_id": "food.groceries",
                  "card_last4": "1234",
                  "needs_review": true,
                  "issues": [
                    "category_id: investment top-up, not in category catalog",
                    "category_id: inferred from context, but may be non-transport leisure"
                  ]
                }
              ],
              "unparsed_fragments": []
            }
        """.trimIndent()

        val emptyAppliedPatchJson = """
            {
              "status": "applied",
              "message": "Изменений не найдено.",
              "operations": []
            }
        """.trimIndent()

        val phoneTransferDraftJson = """
            {
              "status": "ready",
              "rejection_reason": null,
              "transactions": [
                {
                  "source_index": 1,
                  "included": true,
                  "direction": "expense",
                  "occurred_at": "2026-01-15T12:10:00",
                  "posted_at": null,
                  "amount_minor": 125050,
                  "currency": "RUB",
                  "merchant": "Внешний перевод по номеру телефона +*******6539",
                  "category_id": "transfer.internal",
                  "card_last4": null,
                  "needs_review": true,
                  "issues": ["category_id: номер телефона не совпадает с собственными, но перевод внешний"]
                }
              ],
              "unparsed_fragments": []
            }
        """.trimIndent()

        val reviewDraftJson = """
            {
              "status": "ready",
              "rejection_reason": null,
              "transactions": [
                {
                  "source_index": 1,
                  "included": true,
                  "direction": "expense",
                  "occurred_at": "2026-01-15T12:10:00",
                  "posted_at": null,
                  "amount_minor": 125050,
                  "currency": "RUB",
                  "merchant": "DEMO MARKET",
                  "category_id": null,
                  "card_last4": "1234",
                  "needs_review": true,
                  "issues": ["category_id: категория не определена"]
                }
              ],
              "unparsed_fragments": []
            }
        """.trimIndent()

        val categoryPatchJson = """
            {
              "status": "applied",
              "message": "Категория исправлена.",
              "operations": [
                {
                  "transaction_id": "1",
                  "action": "set_field",
                  "field": "category_id",
                  "value": "food.groceries"
                }
              ]
            }
        """.trimIndent()

        val excludePatchJson = """
            {
              "status": "applied",
              "message": "Операция исключена.",
              "operations": [
                {
                  "transaction_id": "2",
                  "action": "set_included",
                  "field": null,
                  "value": false
                }
              ]
            }
        """.trimIndent()

        val notApplicableJson = """
            {
              "status": "not_applicable",
              "rejection_reason": "irrelevant",
              "transactions": [],
              "unparsed_fragments": ["Как приготовить суп?"]
            }
        """.trimIndent()
    }
}
