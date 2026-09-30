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
        assertEquals(
            listOf("system", "system", "user", "assistant", "user"),
            gateway.requests[1].messages.map { it.role },
        )
        assertEquals(4, corrected.messages.size)
        assertTrue(corrected.draft!!.transactions.single { it.id == "1" }.included)
        assertFalse(corrected.draft!!.transactions.single { it.id == "2" }.included)
    }
    @Test
    fun acceptsCanonicalMerchantProposalAndAppliesItToExternalCandidates() = runBlocking {
        val repository = MemoryImportSessionRepository()
        val agent = testAgent(
            repository,
            QueuedGateway(
                """{"items":[{"index":0,"merchant":"КофеБон","description":"","category_id":null}],"merchant_canonical_candidates":[{"canonical_name":"КофеБон","aliases":["Coffeebon"],"suffix_policy":"numeric_terminal","reason":"Явное правило пользователя."}]}""",
            ),
        )
        val session = agent.createSession("Canonical merchant", defaultConfig)
        val classification = agent.classifyExternalTransactionsWithProposals(
            sessionId = session.session.id,
            userText = "Coffeebon с любым числовым суффиксом — это КофеБон.",
            candidates = listOf(
                ExternalTransactionCandidate(
                    occurredAt = "2026-09-02",
                    amountMinor = -640,
                    currency = "RUB",
                    merchant = "Coffeebon 37",
                ),
            ),
        )
        assertEquals(1, classification.merchantCanonicalProposals.size)

        val withCandidate = agent.recordExternalExchange(
            sessionId = session.session.id,
            expectedRevision = session.session.revision,
            userText = "Coffeebon с любым числовым суффиксом — это КофеБон.",
            assistantText = "Предлагаю сохранить правило названия.",
            merchantCanonicalProposals = listOf(
                MerchantCanonicalRuleProposal(
                    canonicalName = "КофеБон",
                    aliases = listOf("Coffeebon"),
                    suffixPolicy = MerchantSuffixPolicy.NUMERIC_TERMINAL,
                    reason = "Пользователь явно задал правило для числовых суффиксов.",
                ),
            ),
        )
        val candidate = withCandidate.merchantCanonicalCandidates.single()
        assertEquals("КофеБон", candidate.canonicalName)
        assertEquals(MerchantSuffixPolicy.NUMERIC_TERMINAL, candidate.suffixPolicy)
        assertEquals(MemoryCandidateStatus.PENDING, candidate.status)

        val accepted = agent.acceptMerchantCanonicalCandidate(
            sessionId = session.session.id,
            expectedRevision = withCandidate.session.revision,
            candidateId = candidate.id,
        )
        assertEquals(MemoryCandidateStatus.ACCEPTED, accepted.merchantCanonicalCandidates.single().status)
        assertEquals("КофеБон", agent.getPreferences().merchantCanonicalRules.single().canonicalName)
        val applied = agent.applyMerchantCanonicalRulesToExternalCandidates(
            listOf(
                ExternalTransactionCandidate(
                    occurredAt = "2026-09-02",
                    amountMinor = -640,
                    currency = "RUB",
                    merchant = "Coffeebon 37",
                ),
            ),
        )
        assertEquals("КофеБон", applied.single().merchant)
    }
 
    @Test
    fun acceptingCanonicalRuleAppliesToExistingDraftTransactions() = runBlocking {
        val repository = MemoryImportSessionRepository()
        val agent = testAgent(
            repository,
            QueuedGateway(initialDraftJson.replace("\"DEMO MARKET\"", "\"Your Smile\"")),
        )
        val created = agent.createSession("Canonical draft", defaultConfig)
        val imported = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = created.session.revision,
            text = "Выписка с Your Smile",
        )
        val withCandidate = agent.recordExternalExchange(
            sessionId = created.session.id,
            expectedRevision = imported.session.revision,
            userText = "Запомни: Your Smile — это Два дантиста.",
            assistantText = "Предлагаю сохранить правило.",
            merchantCanonicalProposals = listOf(
                MerchantCanonicalRuleProposal(
                    canonicalName = "Два дантиста",
                    aliases = listOf("Your Smile"),
                    suffixPolicy = MerchantSuffixPolicy.NONE,
                    reason = "Пользователь явно задал правило.",
                ),
            ),
        )
        val candidate = withCandidate.merchantCanonicalCandidates.single()

        val accepted = agent.acceptMerchantCanonicalCandidate(
            sessionId = created.session.id,
            expectedRevision = withCandidate.session.revision,
            candidateId = candidate.id,
        )

        assertEquals("Два дантиста", accepted.draft!!.transactions.first().transaction.merchant)
        assertEquals("DEMO TAXI", accepted.draft.transactions[1].transaction.merchant)
    }

    @Test
    fun selectedRuleCandidateIndicesSaveAndApplyMemoryRuleToCurrentDraft() = runBlocking {
        val gateway = QueuedGateway(
            initialDraftJson
                .replace("\"DEMO MARKET\"", "\"Два Дантиста\"")
                .replace("\"food.groceries\"", "null"),
            """
                {
                  "intent": "needs_clarification",
                  "message": "Сохраняю правило категории.",
                  "operations": [],
                  "transactions": [],
                  "memory_candidates": [{
                    "text": "Операции с мерчантом «Два Дантиста» относить к категории «Здоровье».",
                    "reason": "Пользователь явно задал правило категории."
                  }],
                  "merchant_canonical_candidates": [],
                  "review_draft": true,
                  "persist_memory_candidate_indices": [0],
                  "persist_merchant_canonical_candidate_indices": []
                }
            """.trimIndent(),
            """
                {
                  "operations": [{
                    "transaction_id": "1",
                    "action": "set_field",
                    "field": "category_id",
                    "value": "health.pharmacy"
                  }],
                  "message": "Правило применено ко всем подходящим строкам."
                }
            """.trimIndent(),
        )
        val agent = testAgent(MemoryImportSessionRepository(), gateway)
        val created = agent.createSession("Selected rule", defaultConfig)
        val imported = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = created.session.revision,
            text = "Выписка с Два Дантиста",
        )

        val updated = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = imported.session.revision,
            text = "Запомни, что Два Дантиста относится к категории Здоровье",
        )

        assertEquals(
            "health.pharmacy",
            updated.draft!!.transactions.first().transaction.categoryId,
            updated.toString(),
        )
        assertEquals(
            MemoryCandidateStatus.ACCEPTED,
            updated.memoryCandidates.single().status,
        )
        assertEquals(
            "Операции с мерчантом «Два Дантиста» относить к категории «Здоровье».",
            agent.getPreferences().confirmedDecisions.single().text,
        )
    }

    @Test
    fun previouslyPendingMemoryCandidateCanBeSelectedAndAppliedToCurrentDraft() = runBlocking {
        val candidateText = "Операции с мерчантом «Два Дантиста» относить к категории «Здоровье»."
        val requests = mutableListOf<ChatCompletionRequest>()
        val gateway = object : ChatCompletionGateway {
            override var contextWindowTokens: Int? = null
            override var maxOutputTokens: Int? = null

            override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
                requests += request
                val firstMessage = request.messages.firstOrNull()?.content.orEmpty()
                val content = when {
                    firstMessage.contains("Ты выполняешь явную перепроверку") -> {
                        if (request.messages.any { it.content.contains(candidateText) }) {
                            """
                                {"operations":[{"transaction_id":"1","action":"set_field",
                                  "field":"category_id","value":"health.pharmacy"}],
                                  "message":"Правило применено."}
                            """.trimIndent()
                        } else {
                            """{"operations":[],"message":"Без изменений."}"""
                        }
                    }

                    firstMessage.contains("Return exactly one JSON object with exactly these fields:") -> {
                        if (request.messages.lastOrNull()?.content.orEmpty()
                                .contains("Примени ранее предложенное правило")
                        ) {
                            """
                                {
                                  "intent":"needs_clarification",
                                  "message":"Применяю прежнее правило.",
                                  "operations":[],
                                  "transactions":[],
                                  "memory_candidates":[],
                                  "merchant_canonical_candidates":[],
                                  "persist_memory_candidate_indices":[],
                                  "persist_merchant_canonical_candidate_indices":[],
                                  "persist_pending_memory_candidate_indices":[0],
                                  "persist_pending_merchant_canonical_candidate_indices":[]
                                }
                            """.trimIndent()
                        } else {
                            """
                                {
                                  "intent":"needs_clarification",
                                  "message":"Предлагаю правило.",
                                  "operations":[],
                                  "transactions":[],
                                  "memory_candidates":[{
                                    "text":"$candidateText",
                                    "reason":"Пользовательское правило категории."
                                  }],
                                  "merchant_canonical_candidates":[],
                                  "persist_memory_candidate_indices":[],
                                  "persist_merchant_canonical_candidate_indices":[]
                                }
                            """.trimIndent()
                        }
                    }

                    else -> initialDraftJson
                        .replace("\"DEMO MARKET\"", "\"Два Дантиста\"")
                        .replace("\"food.groceries\"", "null")
                }
                return ChatCompletionResponse(
                    choices = listOf(
                        ChatChoice(
                            message = ResponseMessage(content = content),
                            finishReason = "stop",
                        ),
                    ),
                )
            }
        }
        val agent = testAgent(MemoryImportSessionRepository(), gateway)
        val created = agent.createSession("Pending memory candidate", defaultConfig)
        val imported = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = created.session.revision,
            text = "Синтетическая выписка Два Дантиста",
        )

        val proposed = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = imported.session.revision,
            text = "Предложи правило для этого продавца, но пока не применяй.",
        )
        assertEquals(MemoryCandidateStatus.PENDING, proposed.memoryCandidates.single().status)

        val updated = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = proposed.session.revision,
            text = "Примени ранее предложенное правило к текущему draft.",
        )

        assertEquals("health.pharmacy", updated.draft!!.transactions.first().transaction.categoryId)
        assertEquals(MemoryCandidateStatus.ACCEPTED, updated.memoryCandidates.single().status)
        assertEquals(candidateText, agent.getPreferences().confirmedDecisions.single().text)
        assertTrue(requests.any { request ->
            request.messages.any {
                it.content.contains("pending_memory_candidates") && it.content.contains(candidateText)
            }
        })
        assertTrue(requests.any { request ->
            request.messages.any {
                it.content.contains("Explicitly user-selected rule candidates") &&
                    it.content.contains(candidateText)
            }
        })
    }

    @Test
    fun failedReviewOfSelectedPendingRuleDoesNotPersistOrClaimApplication() = runBlocking {
        val candidateText = "Операции с мерчантом «Два Дантиста» относить к категории «Здоровье»."
        val gateway = QueuedGateway(
            initialDraftJson
                .replace("\"DEMO MARKET\"", "\"Два Дантиста\"")
                .replace("\"food.groceries\"", "null"),
            """
                {
                  "intent":"needs_clarification",
                  "message":"Предлагаю правило.",
                  "operations":[],
                  "transactions":[],
                  "memory_candidates":[{
                    "text":"$candidateText",
                    "reason":"Пользовательское правило категории."
                  }],
                  "merchant_canonical_candidates":[],
                  "persist_memory_candidate_indices":[],
                  "persist_merchant_canonical_candidate_indices":[]
                }
            """.trimIndent(),
            emptyAppliedPatchJson,
            """
                {
                  "intent":"needs_clarification",
                  "message":"Применяю прежнее правило.",
                  "operations":[],
                  "transactions":[],
                  "memory_candidates":[],
                  "merchant_canonical_candidates":[],
                  "persist_memory_candidate_indices":[],
                  "persist_merchant_canonical_candidate_indices":[],
                  "persist_pending_memory_candidate_indices":[0],
                  "persist_pending_merchant_canonical_candidate_indices":[]
                }
            """.trimIndent(),
            "not-json",
        )
        val repository = MemoryImportSessionRepository()
        val agent = testAgent(repository, gateway)
        val created = agent.createSession("Review failure leaves pending rule intact", defaultConfig)
        val imported = agent.sendMessage(created.session.id, created.session.revision, "Выписка Два Дантиста")
        val proposed = agent.sendMessage(
            created.session.id,
            imported.session.revision,
            "Предложи правило для продавца, но пока не применяй.",
        )

        val failure = assertFailsWith<AgentResponseException> {
            agent.sendMessage(
                created.session.id,
                proposed.session.revision,
                "Примени ранее предложенное правило к текущему draft.",
            )
        }

        assertTrue(failure.message.orEmpty().contains("не удалось перепроверить"))
        assertEquals(proposed, repository.get(created.session.id))
        assertEquals(MemoryCandidateStatus.PENDING, proposed.memoryCandidates.single().status)
        assertNull(proposed.draft!!.transactions.first().transaction.categoryId)
        assertTrue(agent.getPreferences().confirmedDecisions.isEmpty())
    }

    @Test
    fun previouslyPendingMerchantCandidateCanBeSelectedAndAppliedToCurrentDraft() = runBlocking {
        val gateway = QueuedGateway(
            initialDraftJson.replace("\"DEMO MARKET\"", "\"Coffeebon 37\""),
            """
                {
                  "intent":"needs_clarification",
                  "message":"Предложено правило названия.",
                  "operations":[],
                  "transactions":[],
                  "memory_candidates":[],
                  "merchant_canonical_candidates":[{
                    "canonical_name":"КофеБон",
                    "aliases":["Coffeebon"],
                    "suffix_policy":"numeric_terminal",
                    "reason":"Пользователь предложил общее название."
                  }],
                  "persist_memory_candidate_indices":[],
                  "persist_merchant_canonical_candidate_indices":[]
                }
            """.trimIndent(),
            """{"operations":[],"message":"Без изменений."}""",
            """
                {
                  "intent":"needs_clarification",
                  "message":"Применяю прежнее правило названия.",
                  "operations":[],
                  "transactions":[],
                  "memory_candidates":[],
                  "merchant_canonical_candidates":[],
                  "persist_memory_candidate_indices":[],
                  "persist_merchant_canonical_candidate_indices":[],
                  "persist_pending_memory_candidate_indices":[],
                  "persist_pending_merchant_canonical_candidate_indices":[0]
                }
            """.trimIndent(),
            """{"operations":[],"message":"Проверка завершена."}""",
        )
        val agent = testAgent(MemoryImportSessionRepository(), gateway)
        val created = agent.createSession("Pending merchant candidate", defaultConfig)
        val imported = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = created.session.revision,
            text = "Выписка Coffeebon 37",
        )
        val proposed = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = imported.session.revision,
            text = "Предложи общее правило названия, пока не применяй.",
        )
        assertEquals(MemoryCandidateStatus.PENDING, proposed.merchantCanonicalCandidates.single().status)

        val updated = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = proposed.session.revision,
            text = "Примени ранее предложенное правило названия к текущему draft.",
        )

        assertEquals("КофеБон", updated.draft!!.transactions.first().transaction.merchant)
        assertEquals(MemoryCandidateStatus.ACCEPTED, updated.merchantCanonicalCandidates.single().status)
        assertEquals("КофеБон", agent.getPreferences().merchantCanonicalRules.single().canonicalName)
        assertTrue(gateway.requests.any { request ->
            request.messages.any {
                it.content.contains("pending_merchant_canonical_candidates") &&
                    it.content.contains("Coffeebon")
            }
        })
        assertTrue(gateway.requests.any { request ->
            request.messages.any {
                it.content.contains("Explicitly user-selected rule candidates") &&
                    it.content.contains("Coffeebon")
            }
        })
    }

    @Test
    fun unselectedRuleCandidatesRemainPendingWhileCorrectionSucceeds() = runBlocking {
        val gateway = QueuedGateway(
            initialDraftJson,
            """
                {
                  "intent": "correction",
                  "message": "Исправляю описание и предлагаю правила.",
                  "operations": [{
                    "transaction_id": "1",
                    "action": "set_field",
                    "field": "description",
                    "value": "Моя заметка"
                  }],
                  "transactions": [],
                  "memory_candidates": [{
                    "text": "Всегда сопоставлять чеки продавца с операциями.",
                    "reason": "Провайдер предложил сохранить правило."
                  }],
                  "merchant_canonical_candidates": [{
                    "canonical_name": "КофеБон",
                    "aliases": ["DEMO MARKET"],
                    "suffix_policy": "none",
                    "reason": "Провайдер предложил сохранить правило."
                  }],
                  "review_draft": false,
                  "persist_memory_candidate_indices": [],
                  "persist_merchant_canonical_candidate_indices": []
                }
            """.trimIndent(),
            """{"operations":[],"message":"Перепроверка завершена."}""",
        )
        val agent = testAgent(MemoryImportSessionRepository(), gateway)
        val created = agent.createSession("Unauthorized persistence", defaultConfig)
        val imported = agent.sendMessage(created.session.id, 0, "Выписка")

        val corrected = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = imported.session.revision,
            text = "Измени описание первой операции на «Моя заметка». Сохрани файл с отчётом отдельно.",
        )
        val preferences = agent.getPreferences()

        assertEquals(
            "Моя заметка",
            corrected.draft!!.transactions.first().description,
        )
        assertEquals(MemoryCandidateStatus.PENDING, corrected.memoryCandidates.single().status)
        assertEquals(
            MemoryCandidateStatus.PENDING,
            corrected.merchantCanonicalCandidates.single().status,
        )
        assertTrue(preferences.confirmedDecisions.isEmpty())
        assertTrue(preferences.merchantCanonicalRules.isEmpty())
    }

    @Test
    fun selectedRuleCandidateIndexPersistsOnlyItsProposalWithoutLexicalOverlap() = runBlocking {
        val gateway = QueuedGateway(
            initialDraftJson,
            """
                {
                  "intent": "correction",
                  "message": "Правило выбрано.",
                  "operations": [{
                    "transaction_id": "1",
                    "action": "set_field",
                    "field": "description",
                    "value": "Моя заметка"
                  }],
                  "transactions": [],
                  "memory_candidates": [
                    {
                      "text": "Всегда сопоставлять чеки продавца с операциями.",
                      "reason": "Контекстное правило."
                    },
                    {
                      "text": "Не включать переводы.",
                      "reason": "Несвязанное предложение."
                    }
                  ],
                  "merchant_canonical_candidates": [],
                  "review_draft": false,
                  "persist_memory_candidate_indices": [0],
                  "persist_merchant_canonical_candidate_indices": []
                }
            """.trimIndent(),
            """{"operations":[],"message":"Перепроверка завершена."}""",
        )
        val agent = testAgent(MemoryImportSessionRepository(), gateway)
        val created = agent.createSession("Typed rule selection", defaultConfig)
        val imported = agent.sendMessage(created.session.id, 0, "Выписка")

        val corrected = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = imported.session.revision,
            text = "Сделай это постоянным и примени к текущему draft.",
        )

        assertEquals("Моя заметка", corrected.draft!!.transactions.first().description)
        assertEquals(
            listOf(MemoryCandidateStatus.ACCEPTED, MemoryCandidateStatus.PENDING),
            corrected.memoryCandidates.map { it.status },
        )
        assertEquals(
            "Всегда сопоставлять чеки продавца с операциями.",
            agent.getPreferences().confirmedDecisions.single().text,
        )
        assertTrue(corrected.merchantCanonicalCandidates.isEmpty())
    }

    @Test
    fun invalidSelectedRuleCandidateIndicesRejectFollowUpWithoutChangingDraft() = runBlocking {
        listOf("[1]", "[0,0]").forEachIndexed { index, selectedIndices ->
            val gateway = QueuedGateway(
                initialDraftJson,
                """
                    {
                      "intent": "correction",
                      "message": "Правило выбрано.",
                      "operations": [{
                        "transaction_id": "1",
                        "action": "set_field",
                        "field": "description",
                        "value": "Новое описание"
                      }],
                      "transactions": [],
                      "memory_candidates": [{
                        "text": "Не включать переводы.",
                        "reason": "Синтетическое правило."
                      }],
                      "merchant_canonical_candidates": [],
                      "review_draft": false,
                      "persist_memory_candidate_indices": $selectedIndices,
                      "persist_merchant_canonical_candidate_indices": []
                    }
                """.trimIndent(),
            )
            val agent = testAgent(MemoryImportSessionRepository(), gateway)
            val created = agent.createSession("Invalid selected rule $index", defaultConfig)
            val imported = agent.sendMessage(created.session.id, 0, "Выписка")

            assertFailsWith<AgentResponseException> {
                agent.sendMessage(
                    sessionId = created.session.id,
                    expectedRevision = imported.session.revision,
                    text = "Сделай это постоянным и измени описание.",
                )
            }

            val unchanged = agent.getSession(created.session.id)
            assertEquals(imported.session, unchanged.session)
            assertEquals(imported.messages, unchanged.messages)
            assertEquals(imported.draft, unchanged.draft)
            assertTrue(agent.getPreferences().confirmedDecisions.isEmpty())
        }
    }

    @Test
    fun repeatImportWithSameSourceRefDoesNotTransferReceiptAssociationToExistingRow() = runBlocking {
        val agent = testAgent(MemoryImportSessionRepository(), QueuedGateway())
        val created = agent.createSession("Repeat import", defaultConfig)
        val originalCandidate = ExternalTransactionCandidate(
            occurredAt = "2026-09-10T12:00:00Z",
            postedAt = "2026-09-11",
            amountMinor = -34_900,
            currency = "RUB",
            merchant = "Синтетический продавец",
            description = "Банковская операция",
            sourceLabel = "Основной счёт",
            sourceRef = "stable-bank-row-1",
        )
        val imported = agent.appendExternalTransactions(
            sessionId = created.session.id,
            expectedRevision = created.session.revision,
            candidates = listOf(originalCandidate),
        )
        val originalRow = imported.draft!!.transactions.single()
        val verifiedRepeat = originalCandidate.copy(
            items = listOf(
                TransactionItem(
                    name = "Позиция из проверенного чека",
                    quantity = 1.0,
                    priceMinor = 34_900,
                    sumMinor = 34_900,
                ),
            ),
            receiptAssociation = ReceiptAssociation(
                status = ReceiptAssociationStatus.MATCHED,
                summary = ReceiptAssociationSummary(
                    date = "2026-09-10",
                    merchant = "Синтетический продавец",
                    amountMinor = 34_900,
                    currency = "RUB",
                ),
            ),
        )

        val repeated = agent.appendExternalTransactions(
            sessionId = created.session.id,
            expectedRevision = imported.session.revision,
            candidates = listOf(verifiedRepeat),
        )
        val preservedRow = repeated.draft!!.transactions.single()

        assertEquals(1, repeated.draft.transactions.size)
        assertEquals(originalRow.transaction.sourceIndex, preservedRow.transaction.sourceIndex)
        assertEquals(originalRow.transaction.sourceRef, preservedRow.transaction.sourceRef)
        assertEquals(originalRow.transaction.merchant, preservedRow.transaction.merchant)
        assertEquals(originalRow.transaction.occurredAt, preservedRow.transaction.occurredAt)
        assertEquals(originalRow.transaction.postedAt, preservedRow.transaction.postedAt)
        assertEquals(originalRow.transaction.currency, preservedRow.transaction.currency)
        assertEquals(originalRow.transaction.sourceLabel, preservedRow.transaction.sourceLabel)
        assertEquals(originalRow.transaction.amountMinor, preservedRow.transaction.amountMinor)
        assertEquals(originalRow.description, preservedRow.description)
        assertNull(preservedRow.transaction.receiptAssociation)
        assertTrue(preservedRow.transaction.items.isEmpty())
    }

    @Test
    fun recheckReviewsAllDraftRowsAgainstRules() = runBlocking {
        val gateway = QueuedGateway(
            initialDraftJson,
            """
                {
                  "intent": "needs_clarification",
                  "message": "Перепроверяю весь draft.",
                  "operations": [],
                  "transactions": [],
                  "review_draft": true
                }
            """.trimIndent(),
            """
                {
                  "operations": [{
                    "transaction_id": "2",
                    "action": "set_included",
                    "field": null,
                    "value": false
                  }],
                  "message": "Все строки перепроверены."
                }
            """.trimIndent(),
        )
        val agent = testAgent(MemoryImportSessionRepository(), gateway)
        val created = agent.createSession("Draft recheck", defaultConfig)
        val imported = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = created.session.revision,
            text = "Выписка",
        )
        agent.createMerchantCanonicalRule(
            canonicalName = "Магазин",
            aliases = listOf("DEMO MARKET"),
            suffixPolicy = MerchantSuffixPolicy.NONE,
        )

        val checked = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = imported.session.revision,
            text = "Перепроверь",
        )

        assertEquals("Магазин", checked.draft!!.transactions[0].transaction.merchant)
        assertFalse(checked.draft.transactions[1].included)
        assertTrue(gateway.requests.any { request ->
            request.messages.any { it.content.contains("DEMO MARKET") }
        })
    }

    @Test
    fun persistsSelectedCanonicalRuleAndLeavesOtherProposalPending() = runBlocking {
        val gateway = QueuedGateway(
            initialDraftJson.replace("\"DEMO MARKET\"", "\"Coffeebon 37\""),
            """
                {
                  "intent": "needs_clarification",
                  "message": "Сохраняю правила названия и выбора.",
                  "operations": [],
                  "transactions": [],
                  "memory_candidates": [{
                    "text": "Не включать переводы",
                    "reason": "Отдельное пользовательское правило."
                  }],
                  "merchant_canonical_candidates": [{
                    "canonical_name": "КофеБон",
                    "aliases": ["Coffeebon"],
                    "suffix_policy": "numeric_terminal",
                    "reason": "Пользователь явно задал правило."
                  }],
                  "review_draft": true,
                  "persist_memory_candidate_indices": [],
                  "persist_merchant_canonical_candidate_indices": [0]
                }
            """.trimIndent(),
            """
                {
                  "operations": [{
                    "transaction_id": "1",
                    "action": "set_field",
                    "field": "merchant",
                    "value": "КофеБон"
                  }],
                  "message": "Правила применены."
                }
            """.trimIndent(),
        )
        val agent = testAgent(MemoryImportSessionRepository(), gateway)
        val created = agent.createSession("Memory-only follow-up", defaultConfig)
        val initial = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = created.session.revision,
            text = "Первая выписка",
        )

        val updated = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = initial.session.revision,
            text = "Запомни, что все названия Coffeebon с различными численными суффиксами - это КофеБон",
        )

        assertTrue(updated.session.revision > initial.session.revision)
        assertEquals("КофеБон", updated.draft!!.transactions.first().transaction.merchant)
        assertEquals("КофеБон", updated.merchantCanonicalCandidates.single().canonicalName)
        assertEquals(
            MemoryCandidateStatus.ACCEPTED,
            updated.merchantCanonicalCandidates.single().status,
        )
        assertEquals(listOf("Не включать переводы"), updated.memoryCandidates.map { it.text })
        assertEquals(MemoryCandidateStatus.PENDING, updated.memoryCandidates.single().status)
        assertEquals(
            "Выбранные правила сохранены; остальные предложения оставлены на подтверждение.",
            updated.messages.last().displayText,
        )
        assertTrue(agent.getPreferences().confirmedDecisions.isEmpty())
    }

    @Test
    fun appliesPatchWhenFollowUpOmitsIntentAndUsesNullOptionalFields() = runBlocking {
        val gateway = QueuedGateway(
            initialDraftJson,
            """
                {
                  "message": null,
                  "operations": [{
                    "transaction_id": "1",
                    "action": "set_included",
                    "field": null,
                    "value": false
                  }],
                  "transactions": null,
                  "memory_candidates": null,
                  "merchant_canonical_candidates": null
                }
            """.trimIndent(),
        )
        val agent = testAgent(MemoryImportSessionRepository(), gateway)
        val created = agent.createSession("Nullable correction", defaultConfig)
        val initial = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = created.session.revision,
            text = "Первая выписка",
        )

        val updated = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = initial.session.revision,
            text = "Не включай операцию 1",
        )

        assertFalse(updated.draft!!.transactions.first { it.id == "1" }.included)
        assertEquals("Черновик обновлён.", updated.messages.last().displayText)
    }

    @Test
    fun appliesPatchWhenFollowUpUsesCommonAliasesAndCodeFence() = runBlocking {
        val gateway = QueuedGateway(
            initialDraftJson,
            """
                ```json
                {
                  "type": "update",
                  "message": "Операции исправлены.",
                  "changes": [
                    {
                      "id": 1,
                      "field": "included",
                      "value": false
                    },
                    {
                      "id": 1,
                      "action": "update",
                      "field": "category",
                      "value": "food.cafe"
                    }
                  ]
                }
                ```
            """.trimIndent(),
        )
        val agent = testAgent(MemoryImportSessionRepository(), gateway)
        val created = agent.createSession("Aliased correction", defaultConfig)
        val initial = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = created.session.revision,
            text = "Первая выписка",
        )

        val updated = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = initial.session.revision,
            text = "Не включай операцию 1",
        )

        val row = updated.draft!!.transactions.first { it.id == "1" }
        assertFalse(row.included)
        assertEquals("food.cafe", row.transaction.categoryId)
        assertEquals("Операции исправлены.", updated.messages.last().displayText)
    }


    @Test
    fun failedPostImportReviewKeepsAcceptedTransactions() = runBlocking {
        val gateway = QueuedGateway(initialDraftJson, "not-json")
        val agent = testAgent(MemoryImportSessionRepository(), gateway)
        val created = agent.createSession("Post-import review failure", defaultConfig)
        val initial = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = created.session.revision,
            text = "Первая выписка",
        )
        val appended = agent.appendExternalTransactions(
            sessionId = created.session.id,
            expectedRevision = initial.session.revision,
            candidates = listOf(
                ExternalTransactionCandidate(
                    occurredAt = "2026-09-02",
                    amountMinor = -640,
                    currency = "RUB",
                    merchant = "КофеБон",
                    categoryId = "food.cafe",
                ),
            ),
        )
        val importedId = appended.draft!!.transactions.last().id

        val reviewed = agent.reviewImportedDraftInclusion(
            sessionId = created.session.id,
            expectedRevision = appended.session.revision,
            importedTransactionIds = listOf(importedId),
            userText = "Загрузи операции",
        )

        assertEquals(appended.session.revision, reviewed.session.revision)
        assertEquals(appended.draft, reviewed.draft)
        assertTrue(reviewed.lastError.orEmpty().contains("не выполнена"))
    }

    @Test
    fun receiptAssociationAddsOnlyReceiptItemsAndDoesNotBlockNoReceiptExport() = runBlocking {
        val agent = testAgent(MemoryImportSessionRepository(), QueuedGateway(initialDraftJson))
        val created = agent.createSession("Receipt association", defaultConfig)
        val imported = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = created.session.revision,
            text = "Синтетическая выписка",
        )
        val originalRows = imported.draft!!.transactions
        val bankRow = originalRows.first()
        val noReceiptRow = originalRows.last()
        val original = bankRow.transaction
        val updated = agent.recordReceiptAssociation(
            sessionId = created.session.id,
            expectedRevision = imported.session.revision,
            userText = "Привяжи найденный синтетический чек.",
            assistantText = "Синтетическая проверка завершена.",
            updates = mapOf(
                bankRow.id to ReceiptMatchUpdate(
                    status = ReceiptAssociationStatus.MATCHED,
                    items = listOf(
                        TransactionItem(
                            name = "Синтетические яблоки",
                            quantity = 1.0,
                            priceMinor = original.amountMinor,
                            sumMinor = original.amountMinor,
                        ),
                    ),
                    summary = ReceiptAssociationSummary(
                        date = "2026-01-15",
                        merchant = "Синтетический магазин",
                        amountMinor = original.amountMinor,
                        currency = original.currency,
                    ),
                ),
                noReceiptRow.id to ReceiptMatchUpdate(ReceiptAssociationStatus.UNMATCHED),
            ),
            persistRule = false,
        )
        val rows = updated.draft!!.transactions
        val enriched = rows.first { it.id == bankRow.id }
        val unmatched = rows.first { it.id == noReceiptRow.id }

        assertEquals(original.merchant, enriched.transaction.merchant)
        assertEquals(original.categoryId, enriched.transaction.categoryId)
        assertEquals(original.occurredAt, enriched.transaction.occurredAt)
        assertEquals(original.amountMinor, enriched.transaction.amountMinor)
        assertEquals(original.currency, enriched.transaction.currency)
        assertEquals(bankRow.description, enriched.description)
        assertEquals(listOf("Синтетические яблоки"), enriched.transaction.items.map(TransactionItem::name))
        assertEquals(ReceiptAssociationStatus.MATCHED, enriched.transaction.receiptAssociation?.status)
        assertEquals(ReceiptAssociationStatus.UNMATCHED, unmatched.transaction.receiptAssociation?.status)
        assertTrue(unmatched.transaction.issues.isEmpty())
        assertFalse(unmatched.transaction.needsReview)
        assertEquals(ReceiptStatus.READY_FOR_EXPORT, updated.receiptState.status)
        assertEquals(2, agent.buildImportBatch(created.session.id).transactions.size)
    }

    @Test
    fun preservesSourceMerchantWhenModelLeavesItUnknownAndKeepsAmbiguousCategoryOnReview() = runBlocking {
        val gateway = QueuedGateway(
            """{"items":[{"index":0,"merchant":null,"category_id":null},{"index":1,"merchant":null,"category_id":null}]}""",
        )
        val repository = MemoryImportSessionRepository()
        val agent = testAgent(repository, gateway)
        val session = agent.createSession("MCP операции", defaultConfig)

        val enriched = agent.classifyExternalTransactions(
            sessionId = session.session.id,
            candidates = listOf(
                ExternalTransactionCandidate(
                    occurredAt = "2026-02-08",
                    amountMinor = -499,
                    currency = "RUB",
                    merchant = "B121",
                    description = "Булочная Ф. Вол",
                ),
                ExternalTransactionCandidate(
                    occurredAt = "2026-02-08",
                    amountMinor = -1_200,
                    currency = "RUB",
                    merchant = "Ozon Bank",
                    description = "Алексей Ф.",
                ),
            ),
        )

        assertEquals("B121", enriched[0].merchant)
        assertNull(enriched[0].categoryId)
        assertTrue(enriched[0].categoryIssue != null)
        assertEquals("Алексей Ф.", enriched[1].description)
        assertNull(enriched[1].categoryId)
        assertTrue(gateway.requests.single().messages.last().content.contains("description=Булочная Ф. Вол"))
    }

    @Test
    fun externalPreviewUsesMemoryAndCanonicalizesMerchantDescriptionAndTransfers() = runBlocking {
        val gateway = QueuedGateway(
            """{"items":[
                {"index":0,"merchant":"Додо Пицца","description":"Без описания","category_id":"food.cafe"},
                {"index":1,"merchant":"Альфа-Банк","description":"Яна К.","category_id":null},
                {"index":2,"merchant":"BeFit","description":"","category_id":"food.groceries"},
                {"index":3,"merchant":"Яндекс","description":"Себе в другой банк","category_id":null},
                {"index":4,"merchant":"Ozon Bank","description":"Алексей Ф.","category_id":null},
                {"index":5,"merchant":"КофеБон","description":"Описание отсутствует","category_id":"food.cafe"}
            ]}""".trimIndent(),
        )
        val repository = MemoryImportSessionRepository()
        val agent = testAgent(repository, gateway)
        agent.updatePreferences("BeFit относить к еде.")
        repository.saveLongTermMemory(
            repository.readLongTermMemory().copy(
                confirmedDecisions = listOf(
                    ConfirmedDecision(
                        id = "decision-befit",
                        text = "Транзакции с systemletbefit/letbefit относить к Еде и использовать название BeFit.",
                        createdAtEpochMs = 1L,
                    ),
                ),
            ),
        )
        val session = agent.createSession("MCP memory", defaultConfig)

        val enriched = agent.classifyExternalTransactions(
            sessionId = session.session.id,
            userText = "Получи операции и учти мои правила.",
            candidates = listOf(
                ExternalTransactionCandidate(
                    occurredAt = "2026-02-08",
                    amountMinor = -1_135,
                    currency = "RUB",
                    merchant = "DODOPIZZA",
                    description = "Без описания",
                ),
                ExternalTransactionCandidate(
                    occurredAt = "2026-02-08",
                    amountMinor = -10_990,
                    currency = "RUB",
                    merchant = "Альфа-Банк",
                    description = "Яна К.",
                ),
                ExternalTransactionCandidate(
                    occurredAt = "2026-02-08",
                    amountMinor = -2_500,
                    currency = "RUB",
                    merchant = "systemletbefit",
                    description = "systemletbefit",
                ),
                ExternalTransactionCandidate(
                    occurredAt = "2026-02-08",
                    amountMinor = -3_000,
                    currency = "RUB",
                    merchant = "Яндекс",
                    description = "Себе в другой банк",
                ),
                ExternalTransactionCandidate(
                    occurredAt = "2026-02-08",
                    amountMinor = -2_613,
                    currency = "RUB",
                    merchant = "Ozon Bank",
                    description = "Алексей Ф.",
                ),
                ExternalTransactionCandidate(
                    occurredAt = "2026-02-08",
                    amountMinor = -640,
                    currency = "RUB",
                    merchant = "COFFEBON",
                    description = "Описание отсутствует",
                ),
            ),
        )

        assertEquals("Додо Пицца", enriched[0].merchant)
        assertEquals("", enriched[0].description)
        assertEquals("food.cafe", enriched[0].categoryId)
        assertEquals("Перевод", enriched[1].merchant)
        assertEquals("Яна К.", enriched[1].description)
        assertNull(enriched[1].categoryId)
        assertEquals("BeFit", enriched[2].merchant)
        assertEquals("", enriched[2].description)
        assertEquals("food.groceries", enriched[2].categoryId)
        assertEquals("Перевод", enriched[3].merchant)
        assertEquals("Себе в другой банк", enriched[3].description)
        assertNull(enriched[3].categoryId)
        assertEquals("Перевод", enriched[4].merchant)
        assertEquals("Алексей Ф.", enriched[4].description)
        assertNull(enriched[4].categoryId)
        assertEquals("КофеБон", enriched[5].merchant)
        assertEquals("", enriched[5].description)
        assertEquals("food.cafe", enriched[5].categoryId)

        val request = gateway.requests.single()
        assertTrue(request.messages.first().content.contains("systemletbefit/letbefit"))
        assertTrue(request.messages.first().content.contains("<general-user-instructions>"))

        assertTrue(request.messages.first().content.contains("<confirmed-decisions>"))
        assertTrue(request.messages.last().content.contains("Получи операции"))
    }

    @Test
    fun externalPreviewAppliesConfirmedCategoryRuleToMatchingMerchant() = runBlocking {
        val repository = MemoryImportSessionRepository()
        repository.saveLongTermMemory(
            repository.readLongTermMemory().copy(
                confirmedDecisions = listOf(
                    ConfirmedDecision(
                        id = "decision-category-preview",
                        text = "Операции с мерчантом «Два Дантиста» относить к категории «Здоровье».",
                        createdAtEpochMs = 1L,
                    ),
                ),
            ),
        )
        val agent = testAgent(
            repository = repository,
            gateway = QueuedGateway(
                """{"items":[{"index":0,"merchant":"Два Дантиста","description":"","category_id":"health.pharmacy"}]}""",
            ),
        )
        val session = agent.createSession("Category preview", defaultConfig)

        val enriched = agent.classifyExternalTransactions(
            sessionId = session.session.id,
            userText = "Получи новые операции.",
            candidates = listOf(
                ExternalTransactionCandidate(
                    occurredAt = "2026-02-08",
                    amountMinor = -1_500,
                    currency = "RUB",
                    merchant = "Два Дантиста",
                    description = "",
                ),
            ),
        )

        assertEquals("health.pharmacy", enriched.single().categoryId)
        assertNull(enriched.single().categoryIssue)
    }

    @Test
    fun externalPreviewAppliesConfirmedDescriptionRules() = runBlocking {
        val repository = MemoryImportSessionRepository()
        repository.saveLongTermMemory(
            repository.readLongTermMemory().copy(
                confirmedDecisions = listOf(
                    ConfirmedDecision(
                        id = "decision-location",
                        text = "Если в операции «Стритфуд» (IP Zamiralova) в описании нет обозначения места, добавлять место «ЦПКиО».",
                        createdAtEpochMs = 1L,
                    ),
                    ConfirmedDecision(
                        id = "decision-cleanup",
                        text = "У операций с мерчантом «Подорожник» удалять описание «Организатор перевозок» как бессмысленное.",
                        createdAtEpochMs = 2L,
                    ),
                ),
            ),
        )
        val gateway = QueuedGateway(
            """{"items":[
                {"index":0,"merchant":"Стритфуд","description":"Бургер, ЦПКиО","category_id":null},
                {"index":1,"merchant":"Подорожник","description":"","category_id":null}
            ]}""".trimIndent(),
        )
        val agent = testAgent(repository, gateway)
        val session = agent.createSession("Description rules", defaultConfig)

        val enriched = agent.classifyExternalTransactions(
            sessionId = session.session.id,
            userText = "Учти подтверждённые правила описаний.",
            candidates = listOf(
                ExternalTransactionCandidate(
                    occurredAt = "2026-02-08",
                    amountMinor = -1_000,
                    currency = "RUB",
                    merchant = "IP Zamiralova",
                    description = "Бургер",
                ),
                ExternalTransactionCandidate(
                    occurredAt = "2026-02-08",
                    amountMinor = -500,
                    currency = "RUB",
                    merchant = "Подорожник",
                    description = "Организатор перевозок",
                ),
            ),
        )

        assertEquals("Бургер, ЦПКиО", enriched[0].description)
        assertEquals("", enriched[1].description)
        assertTrue(
            gateway.requests.single().messages.any {
                it.content.contains("добавлять место «ЦПКиО»") &&
                    it.content.contains("удалять описание «Организатор перевозок»")
            },
        )
    }

    @Test
    fun draftReviewAppliesConfirmedDescriptionRulesToEveryMatchingRow() = runBlocking {
        val repository = MemoryImportSessionRepository()
        repository.saveLongTermMemory(
            repository.readLongTermMemory().copy(
                confirmedDecisions = listOf(
                    ConfirmedDecision(
                        id = "decision-location-draft",
                        text = "Если в операции «Стритфуд» (IP Zamiralova) в описании нет обозначения места, добавлять место «ЦПКиО».",
                        createdAtEpochMs = 1L,
                    ),
                    ConfirmedDecision(
                        id = "decision-cleanup-draft",
                        text = "У операций с мерчантом «Подорожник» удалять описание «Организатор перевозок» как бессмысленное.",
                        createdAtEpochMs = 2L,
                    ),
                ),
            ),
        )
        val agent = testAgent(
            repository = repository,
            gateway = QueuedGateway(
                initialDraftJson,
                """{"intent":"needs_clarification","message":"Проверяю подтверждённые правила.","operations":[],"transactions":[],"review_draft":true}""",
                """{"operations":[
                    {"transaction_id":"1","action":"set_field","field":"description","value":"Бургер, ЦПКиО"},
                    {"transaction_id":"2","action":"set_field","field":"description","value":""}
                ],"message":"Проверил все строки."}""",
            ),
        )
        val created = agent.createSession("Draft description rules", defaultConfig)
        val imported = agent.sendMessage(created.session.id, 0, "Выписка")
        val first = imported.draft!!.transactions.first()
        val second = imported.draft.transactions[1]
        val described = agent.replaceTransaction(
            sessionId = imported.session.id,
            expectedRevision = imported.session.revision,
            transactionId = first.id,
            included = first.included,
            description = "Бургер",
            replacement = first.transaction.copy(merchant = "IP Zamiralova"),
        )
        val prepared = described.draft!!.transactions[1]
        val describedAgain = agent.replaceTransaction(
            sessionId = described.session.id,
            expectedRevision = described.session.revision,
            transactionId = prepared.id,
            included = prepared.included,
            description = "Организатор перевозок",
            replacement = prepared.transaction.copy(merchant = "Подорожник"),
        )

        val reviewed = agent.sendMessage(
            sessionId = describedAgain.session.id,
            expectedRevision = describedAgain.session.revision,
            text = "Перепроверь",
        )

        assertEquals("Бургер, ЦПКиО", reviewed.draft!!.transactions.first().description)
        assertEquals("", reviewed.draft.transactions[1].description)
        assertEquals(second.id, prepared.id)
    }

    @Test
    fun conversationalRuleAutomaticallyReviewsEveryDraftRow() = runBlocking {
        val gateway = QueuedGateway(
            initialDraftJson,
            """{
              "intent":"needs_clarification",
              "message":"Правило принято, перепроверяю весь draft.",
              "operations":[],
              "transactions":[],
              "memory_candidates":[{
                "text":"Не дублировать название продавца в описании операции.",
                "reason":"Пользователь явно сформулировал правило описаний."
              }],
              "review_draft":true
            }""".trimIndent(),
            """{"operations":[
              {"transaction_id":"1","action":"set_field","field":"description","value":"Яблоки"},
              {"transaction_id":"2","action":"set_field","field":"description","value":"Поездка"}
            ],"message":"Проверил все строки."}""",
        )
        val repository = MemoryImportSessionRepository()
        val agent = testAgent(repository, gateway)
        val created = agent.createSession("Automatic rule review", defaultConfig)
        val imported = agent.sendMessage(created.session.id, 0, "Выписка")
        val first = imported.draft!!.transactions.first()
        val described = agent.replaceTransaction(
            sessionId = imported.session.id,
            expectedRevision = imported.session.revision,
            transactionId = first.id,
            included = first.included,
            description = "DEMO MARKET, Яблоки",
            replacement = first.transaction.copy(merchant = "DEMO MARKET"),
        )
        val second = described.draft!!.transactions[1]
        val describedAgain = agent.replaceTransaction(
            sessionId = described.session.id,
            expectedRevision = described.session.revision,
            transactionId = second.id,
            included = second.included,
            description = "DEMO TAXI, Поездка",
            replacement = second.transaction.copy(merchant = "DEMO TAXI"),
        )

        val reviewed = agent.sendMessage(
            sessionId = describedAgain.session.id,
            expectedRevision = describedAgain.session.revision,
            text = "Не дублируй в описании название продавца",
        )

        assertEquals("Яблоки", reviewed.draft!!.transactions.first().description)
        assertEquals("Поездка", reviewed.draft.transactions[1].description)
        assertEquals(
            "Все операции текущего draft перепроверены по этому правилу.",
            reviewed.messages.last().displayText,
        )
        assertEquals(MemoryCandidateStatus.PENDING, reviewed.memoryCandidates.single().status)
        assertTrue(agent.getPreferences().confirmedDecisions.isEmpty())
        assertEquals(3, gateway.requests.size)
        assertTrue(
            gateway.requests.last().messages.any {
                it.role == "user" &&
                    it.content.contains("Не дублируй в описании название продавца")
            },
        )
        assertTrue(
            gateway.requests.last().messages.any {
                it.content.contains("DEMO MARKET") && it.content.contains("DEMO TAXI")
            },
        )
    }

    @Test
    fun fallbackCategoryLeavesUnknownModelSelectionForReview() = runBlocking {
        val repository = MemoryImportSessionRepository()
        val agent = testAgent(
            repository,
            QueuedGateway(
                """{"items":[{"index":0,"merchant":"Додо Пицца","description":"Нет описания","category_id":null}]}""",
            ),
        )
        agent.archiveCategory("food.cafe")
        val customCategory = agent.createCategory(
            displayName = "Рестораны",
            type = CategoryType.EXPENSE,
            parentId = null,
            hint = "cafes and restaurants",
        ).single { it.displayName == "Рестораны" }
        val session = agent.createSession("Custom catalog", defaultConfig)

        val enriched = agent.classifyExternalTransactions(
            sessionId = session.session.id,
            userText = "Получи операции.",
            candidates = listOf(
                ExternalTransactionCandidate(
                    occurredAt = "2026-02-08",
                    amountMinor = -1_135,
                    currency = "RUB",
                    merchant = "DODOPIZZA",
                    description = "Нет описания",
                ),
            ),
        )

        assertNull(enriched.single().categoryId)
        assertTrue(enriched.single().categoryIssue != null)
        assertEquals("Рестораны", customCategory.displayName)
    }
    @Test
    fun appliesPerUserMerchantCanonicalRuleBeforeAndAfterModelResponse() = runBlocking {
        val gateway = QueuedGateway(
            """{"items":[
                {"index":0,"merchant":"Other","description":"U doma 23 кофе","category_id":null},
                {"index":1,"merchant":"Other","description":"U doma Coffee","category_id":null}
            ]}""".trimIndent(),
        )
        val repository = MemoryImportSessionRepository()
        val agent = testAgent(repository, gateway)
        agent.createMerchantCanonicalRule(
            canonicalName = "У дома",
            aliases = listOf("U doma"),
            suffixPolicy = MerchantSuffixPolicy.NUMERIC_TERMINAL,
        )
        val session = agent.createSession("Canonical merchant", defaultConfig)

        val enriched = agent.classifyExternalTransactions(
            sessionId = session.session.id,
            candidates = listOf(
                ExternalTransactionCandidate(
                    occurredAt = "2026-02-08",
                    amountMinor = -1_000,
                    currency = "RUB",
                    merchant = "U doma 23",
                    description = "U doma 23 кофе",
                ),
                ExternalTransactionCandidate(
                    occurredAt = "2026-02-08",
                    amountMinor = -1_000,
                    currency = "RUB",
                    merchant = "U doma Coffee",
                    description = "U doma Coffee",
                ),
            ),
        )

        assertEquals("У дома", enriched[0].merchant)
        assertEquals("кофе", enriched[0].description)
        assertEquals("Other", enriched[1].merchant)
        assertTrue(gateway.requests.single().messages.last().content.contains("merchant=U doma 23"))
        assertTrue(gateway.requests.single().messages.first().content.contains("<merchant-canonical-rules>"))
        assertEquals(
            listOf("U doma"),
            repository.readLongTermMemory().merchantCanonicalRules.single().aliases,
        )
    }

    @Test
    fun doesNotMigrateTextOnlyConfirmedDecisionIntoStructuredMerchantRule() = runBlocking {
        val gateway = QueuedGateway(
            """{"items":[{"index":0,"merchant":"Other","description":"U doma 23 кофе","category_id":null}]}""",
        )
        val repository = MemoryImportSessionRepository()
        repository.saveLongTermMemory(
            UserPreferences(
                userPrompt = "",
                confirmedDecisions = listOf(
                    ConfirmedDecision(
                        id = "decision-u-doma",
                        text = "Магазины, похожим образом на U doma нужно называть У дома.",
                        createdAtEpochMs = 1L,
                    ),
                ),
            ),
        )
        val agent = testAgent(repository, gateway)
        val session = agent.createSession("Decision canonical merchant", defaultConfig)

        val enriched = agent.classifyExternalTransactions(
            sessionId = session.session.id,
            candidates = listOf(
                ExternalTransactionCandidate(
                    occurredAt = "2026-02-08",
                    amountMinor = -1_000,
                    currency = "RUB",
                    merchant = "U doma 23",
                    description = "U doma 23 кофе",
                ),
            ),
        )

        assertEquals("Other", enriched.single().merchant)
        assertEquals("U doma 23 кофе", enriched.single().description)
    }

    @Test
    fun derivesReceiptStateFromCurrentDraft() {
        val transaction = StructuredTransaction(
            sourceIndex = 1,
            direction = TransactionDirection.EXPENSE,
            occurredAt = "2026-01-15T12:10:00",
            postedAt = null,
            amountMinor = 100,
            currency = "RUB",
            merchant = "Магазин",
            categoryId = "food.groceries",
            needsReview = false,
            issues = emptyList(),
        )
        val ready = ImportDraft(
            status = ImportStatus.READY,
            rejectionReason = null,
            transactions = listOf(
                DraftTransaction(
                    id = "1",
                    included = true,
                    transaction = transaction,
                ),
            ),
            unparsedFragments = emptyList(),
            version = 0,
        )

        assertEquals(ReceiptStatus.NOT_STARTED, receiptStateFor(null).status)
        assertEquals(
            ReceiptStatus.HAS_ERRORS,
            receiptStateFor(ready.copy(transactions = ready.transactions.map { it.copy(included = false) })).status,
        )
        assertEquals(ReceiptStatus.READY_FOR_EXPORT, receiptStateFor(ready).status)
        assertEquals(
            ReceiptStatus.HAS_ERRORS,
            receiptStateFor(
                ready,
                InvariantCheckResult(
                    status = InvariantCheckStatus.CONFLICT,
                    checkedInvariantIds = SYSTEM_TASK_INVARIANTS.map(TaskInvariant::id),
                    nextAction = "Исправьте конфликт.",
                ),
            ).status,
        )
    }

    @Test
    fun exportedReceiptRemainsEditableAndExportable() = runBlocking {
        val repository = MemoryImportSessionRepository()
        val agent = testAgent(repository, QueuedGateway(initialDraftJson))
        val created = agent.createSession("Экспортируемая выписка", defaultConfig)
        val extracted = agent.sendMessage(created.session.id, 0, "Выписка")

        val firstBatch = agent.buildImportBatch(created.session.id)
        assertEquals(2, firstBatch.transactions.size)
        assertEquals(ReceiptStatus.READY_FOR_EXPORT, extracted.receiptState.status)

        val first = extracted.draft!!.transactions.first()
        val edited = agent.replaceTransaction(
            sessionId = created.session.id,
            expectedRevision = extracted.session.revision,
            transactionId = first.id,
            included = true,
            description = "После экспорта",
            replacement = first.transaction.copy(merchant = "AFTER EXPORT"),
        )

        assertEquals(ReceiptStatus.READY_FOR_EXPORT, edited.receiptState.status)
        assertEquals(
            "AFTER EXPORT",
            agent.buildImportBatch(created.session.id).transactions.first().merchant,
        )
        assertEquals(
            ReceiptStatus.READY_FOR_EXPORT,
            agent.getSession(created.session.id).receiptState.status,
        )
    }

    @Test
    fun exportedReceiptAcceptsAppendedTransactions() = runBlocking {
        val gateway = QueuedGateway(initialDraftJson, appendStatementJson)
        val agent = testAgent(MemoryImportSessionRepository(), gateway)
        val created = agent.createSession("Добавляемая выписка", defaultConfig)
        val extracted = agent.sendMessage(created.session.id, 0, "Первая выписка")

        agent.buildImportBatch(created.session.id)
        val appended = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = extracted.session.revision,
            text = "Добавь вторую выписку.",
        )

        assertEquals(3, appended.draft!!.transactions.size)
        assertEquals(ReceiptStatus.HAS_ERRORS, appended.receiptState.status)
        val added = appended.draft!!.transactions.last()
        val repaired = agent.replaceTransaction(
            sessionId = created.session.id,
            expectedRevision = appended.session.revision,
            transactionId = added.id,
            included = true,
            description = added.description,
            replacement = added.transaction.copy(categoryId = "food.cafe"),
        )
        assertEquals(ReceiptStatus.READY_FOR_EXPORT, repaired.receiptState.status)
        assertEquals(3, agent.buildImportBatch(created.session.id).transactions.size)
    }
    @Test
    fun modelDescriptionIsSanitizedAndIncludedInExport() = runBlocking {
        val agent = testAgent(
            repository = MemoryImportSessionRepository(),
            gateway = QueuedGateway(initialDraftWithGeneratedDescriptionsJson),
        )
        val created = agent.createSession("Описание операции", defaultConfig)

        val extracted = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = created.session.revision,
            text = "Выписка с дополнительными сведениями.",
        )

        assertEquals("Яблоки, 2 кг", extracted.draft!!.transactions[0].description)
        assertEquals("", extracted.draft!!.transactions[1].description)
        val batch = agent.buildImportBatch(created.session.id)
        assertEquals("Яблоки, 2 кг", batch.transactions[0].description)
        assertEquals("", batch.transactions[1].description)
    }

    @Test
    fun appendedIncomeGetsDescriptionAndManualDescriptionSurvivesFollowUp() = runBlocking {
        val agent = testAgent(
            repository = MemoryImportSessionRepository(),
            gateway = QueuedGateway(
                initialDraftWithGeneratedDescriptionsJson,
                appendIncomeWithDescriptionJson,
                emptyAppliedPatchJson,
            ),
        )
        val created = agent.createSession("Описание и ручная заметка", defaultConfig)
        val extracted = agent.sendMessage(created.session.id, 0, "Первая выписка")
        val first = extracted.draft!!.transactions.first()
        val manuallyEdited = agent.replaceTransaction(
            sessionId = created.session.id,
            expectedRevision = extracted.session.revision,
            transactionId = first.id,
            included = true,
            description = "Моя ручная заметка",
            replacement = first.transaction,
        )

        val appended = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = manuallyEdited.session.revision,
            text = "Добавь доход.",
        )
        val added = appended.draft!!.transactions.last()
        assertEquals("Моя ручная заметка", appended.draft!!.transactions.first().description)
        assertEquals(TransactionDirection.INCOME, added.transaction.direction)
        assertEquals("Зарплата за январь", added.description)
        assertEquals(
            "Зарплата за январь",
            agent.buildImportBatch(created.session.id).transactions.last().description,
        )

        val corrected = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = appended.session.revision,
            text = "Проверь без изменения заметок.",
        )
        assertEquals("Моя ручная заметка", corrected.draft!!.transactions.first().description)
    }


    @Test
    fun systemInvariantConflictBlocksDraftPersistence() = runBlocking {
        val repository = MemoryImportSessionRepository()
        val conflictDraftJson = initialDraftJson.replace(
            "\"merchant\": \"DEMO MARKET\"",
            "\"merchant\": \"DEMO MARKET +7 (999) 111-22-33\"",
        )
        val agent = testAgent(repository, QueuedGateway(conflictDraftJson))
        val created = agent.createSession("Системный инвариант", defaultConfig)

        val error = assertFailsWith<InvariantViolationException> {
            agent.sendMessage(created.session.id, created.session.revision, "Выписка")
        }

        assertEquals(InvariantCheckStatus.CONFLICT, error.result.status)
        assertEquals("system.mask-explicit-phones", error.result.conflicts.single().invariantId)
        val stored = repository.get(created.session.id)!!
        assertNull(stored.draft)
        assertTrue(stored.messages.isEmpty())
        assertEquals(ReceiptStatus.HAS_ERRORS, stored.receiptState.status)
        assertEquals(error.result, stored.receiptState.lastCompliance)
    }
    @Test
    fun capsRuntimeOutputBudgetToSelectedModelLimit() = runBlocking {
        val gateway = QueuedGateway(initialDraftJson).also { it.maxOutputTokens = 2_048 }
        val agent = testAgent(MemoryImportSessionRepository(), gateway)
        val created = agent.createSession("Ограничение ответа", defaultConfig)

        agent.sendMessage(created.session.id, 0, "Т")

        assertEquals(2_048, gateway.requests.single().maxTokens)
    }

    @Test
    fun compressesOldHistoryIntoSummaryBeforeNextFollowUp() = runBlocking {
        val responses = buildList {
            add(initialDraftJson)
            repeat(9) { add(emptyAppliedPatchJson) }
            add("""{"summary":"Сводка старых решений OLD-START"}""")
            add(emptyAppliedPatchJson)
            add("""{"summary":"Сводка старых решений OLD-START плюс новые уточнения"}""")
            add(emptyAppliedPatchJson)
        }
        val gateway = QueuedGateway(*responses.toTypedArray())
        val repository = MemoryImportSessionRepository()
        val agent = testAgent(repository, gateway)
        val created = agent.createSession("Сжатие истории", defaultConfig)

        var state = agent.sendMessage(created.session.id, 0, "OLD-START")
        repeat(9) { index ->
            state = agent.sendMessage(
                sessionId = state.session.id,
                expectedRevision = state.session.revision,
                text = "Уточнение $index",
            )
        }
        val compressed = agent.sendMessage(
            sessionId = state.session.id,
            expectedRevision = state.session.revision,
            text = "Уточнение 9",
        )

        assertEquals(12, gateway.requests.size)
        assertEquals(22, compressed.messages.size)
        assertEquals(10, compressed.summary?.summarizedMessageCount)
        assertEquals("Сводка старых решений OLD-START", compressed.summary?.text)
        assertEquals(1, compressed.metrics.count { it.callType == ModelCallType.SUMMARY })
        assertEquals(11, compressed.metrics.count { it.callType == ModelCallType.NORMAL })
        assertFalse(gateway.requests[10].useConfiguredReasoning)

        val mainRequest = gateway.requests.last()
        assertEquals(14, mainRequest.messages.size)
        assertTrue(mainRequest.messages.any { it.content.contains("Сводка старых решений OLD-START") })
        assertTrue(mainRequest.messages.none { it.content == "OLD-START" })
        assertTrue(mainRequest.messages.any { it.content == "Уточнение 8" })
        val compressedAgain = agent.sendMessage(
            sessionId = compressed.session.id,
            expectedRevision = compressed.session.revision,
            text = "Уточнение 10",
        )
        assertEquals(14, gateway.requests.size)
        assertEquals(20, compressedAgain.summary?.summarizedMessageCount)
        assertEquals(24, compressedAgain.messages.size)
        assertEquals(2, compressedAgain.metrics.count { it.callType == ModelCallType.SUMMARY })
        assertEquals(12, compressedAgain.metrics.count { it.callType == ModelCallType.NORMAL })
        assertEquals(6, gateway.requests.last().messages.size)
        assertTrue(gateway.requests[12].messages.last().content.contains("Сводка старых решений OLD-START"))
        val fullGateway = QueuedGateway(emptyAppliedPatchJson)
        val fullAgent = testAgent(
            repository = repository,
            gateway = fullGateway,
            runtimeConfig = AgentRuntimeConfig(
                maxTokens = 100_000,
                contextCompression = ContextCompressionConfig(enabled = false),
            ),
        )
        fullAgent.sendMessage(
            sessionId = compressedAgain.session.id,
            expectedRevision = compressedAgain.session.revision,
            text = "Full baseline",
        )
        assertTrue(fullGateway.requests.single().messages.any { it.content.contains("OLD-START") })
        assertTrue(
            fullGateway.requests.single().messages.none {
                it.content.contains("Compressed conversation summary")
            },
        )
    }

    @Test
    fun summaryFailureDoesNotSendNormalRequestOrPersistBoundary() = runBlocking {
        val responses = buildList {
            add(initialDraftJson)
            repeat(9) { add(emptyAppliedPatchJson) }
            add("""{"unexpected":"broken"}""")
        }
        val gateway = QueuedGateway(*responses.toTypedArray())
        val repository = MemoryImportSessionRepository()
        val agent = testAgent(repository, gateway)
        val created = agent.createSession("Ошибка summary", defaultConfig)

        var state = agent.sendMessage(created.session.id, 0, "OLD-START")
        repeat(9) { index ->
            state = agent.sendMessage(
                sessionId = state.session.id,
                expectedRevision = state.session.revision,
                text = "Уточнение $index",
            )
        }

        assertFailsWith<AgentResponseException> {
            agent.sendMessage(
                sessionId = state.session.id,
                expectedRevision = state.session.revision,
                text = "Не отправляй normal request",
            )
        }
        val stored = repository.get(created.session.id)!!
        assertEquals(11, gateway.requests.size)
        assertNull(stored.summary)
        assertEquals(20, stored.messages.size)
        assertEquals(10, stored.metrics.size)
    }

    @Test
    fun tokenAwareSummaryUsesTokenThresholdAndLocalFallback() = runBlocking {
        val gateway = TokenAwareGateway(
            contextWindowTokens = 16_000,
            initialResponse = initialDraftJson,
            summaryResponse = """{"summary":"TOKEN-AWARE-SUMMARY"}""",
        )
        val repository = MemoryImportSessionRepository()
        val agent = testAgent(
            repository = repository,
            gateway = gateway,
            runtimeConfig = AgentRuntimeConfig(
                maxTokens = 100_000,
                contextManagement = ContextManagementConfig(
                    strategy = ContextStrategy.TOKEN_AWARE_SUMMARY,
                    recentMessages = 1,
                    summaryBatchMessages = 1,
                    summaryMaxTokens = 256,
                    summaryThresholdTokens = 12_000,
                    summaryKeepRecentTokens = 500,
                ),
            ),
        )
        val created = agent.createSession("Token-aware summary", defaultConfig)

        var state = agent.sendMessage(created.session.id, 0, "Первая выписка")
        repeat(6) { index ->
            state = agent.sendMessage(
                sessionId = state.session.id,
                expectedRevision = state.session.revision,
                text = "Старое уточнение $index " + "x".repeat(1_500),
            )
        }

        val summaryRequestIndex = gateway.requests.indexOfFirst {
            it.messages.firstOrNull()?.content?.contains("Summarize an earlier") == true
        }
        assertTrue(summaryRequestIndex >= 0)
        assertTrue(
            gateway.requests.drop(summaryRequestIndex + 1).any { request ->
                request.messages.any { message -> message.content.contains("TOKEN-AWARE-SUMMARY") }
            },
        )
        assertTrue(state.summary!!.summarizedMessageCount > 0)
        assertTrue(state.metrics.any { it.callType == ModelCallType.SUMMARY })
        val normalMetric = state.metrics.last { it.callType == ModelCallType.NORMAL }
        assertTrue(normalMetric.estimatedContextTokens != null)
        assertEquals(12_000, normalMetric.compactionThresholdTokens)
        assertEquals("utf8_upper_bound", normalMetric.contextEstimateSource)
    }

    @Test
    fun tokenAwareBudgetUsesExplicitOverridesAndSmallWindowFallback() {
        val defaultConfig = ContextManagementConfig(
            strategy = ContextStrategy.TOKEN_AWARE_SUMMARY,
            summaryKeepRecentTokens = 100,
        )
        val defaultBudget = defaultConfig.tokenAwareBudget(128)
        assertEquals(64, defaultBudget.thresholdTokens)
        assertEquals(64, defaultBudget.reserveTokens)

        val explicitBudget = defaultConfig.copy(
            summaryThresholdTokens = 10_000,
            summaryThresholdPercent = 90,
            summaryReserveTokens = 50_000,
        ).tokenAwareBudget(20_000)
        assertEquals(10_000, explicitBudget.thresholdTokens)
        assertEquals(10_000, explicitBudget.reserveTokens)
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
        assertFalse(systemPrompt.contains("<general-user-instructions>"))
        assertTrue(systemPrompt.contains("prove who owns a number"))
        assertTrue(systemPrompt.contains("transfer type"))
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
    fun appendsStatementWithStableIdsAndExactDeduplication() = runBlocking {
        val gateway = QueuedGateway(initialDraftJson, appendStatementJson)
        val agent = testAgent(MemoryImportSessionRepository(), gateway)
        val created = agent.createSession("Добавление выписки", defaultConfig)

        val extracted = agent.sendMessage(created.session.id, 0, "Первая выписка")
        val appended = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = extracted.session.revision,
            text = "Вот вторая выписка.",
        )

        assertEquals(listOf("1", "2", "3"), appended.draft!!.transactions.map { it.id })
        assertEquals(
            listOf("DEMO MARKET", "DEMO TAXI", "DEMO CAFE"),
            appended.draft!!.transactions.map { it.transaction.merchant },
        )
        assertEquals(70_000, appended.draft!!.transactions.last().transaction.amountMinor)
        assertEquals(
            "Добавлено операций: 1. Пропущено точных дубликатов: 2.",
            appended.messages.last().displayText,
        )
        assertEquals(extracted.session.revision + 1, appended.session.revision)
        assertEquals(4, appended.messages.size)
    }

    @Test
    fun keepsRowsWhenOptionalIdentityFieldsConflict() = runBlocking {
        val gateway = QueuedGateway(initialDraftJson, conflictingOptionalAppendStatementJson)
        val agent = testAgent(MemoryImportSessionRepository(), gateway)
        val created = agent.createSession("Конфликт optional-полей", defaultConfig)

        val extracted = agent.sendMessage(created.session.id, 0, "Первая выписка")
        val appended = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = extracted.session.revision,
            text = "Выписка с двумя операциями Госуслуги.",
        )

        val gosuslugi = appended.draft!!.transactions.filter {
            it.transaction.merchant == "Госуслуги"
        }
        assertEquals(listOf("3", "4"), gosuslugi.map { it.id })
        assertEquals(
            listOf("2026-08-18T16:02:00", "2026-08-19T14:31:00"),
            gosuslugi.map { it.transaction.postedAt },
        )
        assertEquals(
            "Добавлено операций: 2. Пропущено точных дубликатов: 0.",
            appended.messages.last().displayText,
        )
    }

    @Test
    fun appendsStructurallyValidIncompleteRowsWithFieldErrors() = runBlocking {
        val gateway = QueuedGateway(initialDraftJson, incompleteAppendStatementJson)
        val agent = testAgent(MemoryImportSessionRepository(), gateway)
        val created = agent.createSession("Неполная выписка", defaultConfig)

        val extracted = agent.sendMessage(created.session.id, 0, "Первая выписка")
        val appended = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = extracted.session.revision,
            text = "Вот ещё одна выписка с неполной строкой.",
        )

        val row = appended.draft!!.transactions.last()
        assertEquals("3", row.id)
        assertTrue(row.fieldErrors.keys.containsAll(listOf("occurred_at", "amount_minor")))
        assertEquals(-1, row.transaction.amountMinor)
    }

    @Test
    fun allDuplicateAppendStillPersistsExchangeAndMetric() = runBlocking {
        val gateway = QueuedGateway(initialDraftJson, allDuplicateAppendStatementJson)
        val agent = testAgent(MemoryImportSessionRepository(), gateway)
        val created = agent.createSession("Только дубликаты", defaultConfig)

        val extracted = agent.sendMessage(created.session.id, 0, "Первая выписка")
        val appended = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = extracted.session.revision,
            text = "Повторная выписка.",
        )

        assertEquals(extracted.draft!!.transactions, appended.draft!!.transactions)
        assertEquals(2, appended.metrics.size)
        assertEquals(4, appended.messages.size)
        assertEquals(
            "Добавлено операций: 0. Пропущено точных дубликатов: 1.",
            appended.messages.last().displayText,
        )
    }

    @Test
    fun rejectsInvalidAppendResponseWithoutPartialMutation() = runBlocking {
        val gateway = QueuedGateway(initialDraftJson, invalidAppendResponseJson)
        val repository = MemoryImportSessionRepository()
        val agent = testAgent(repository, gateway)
        val created = agent.createSession("Атомарное добавление", defaultConfig)
        val extracted = agent.sendMessage(created.session.id, 0, "Первая выписка")

        assertFailsWith<AgentResponseException> {
            agent.sendMessage(
                sessionId = created.session.id,
                expectedRevision = extracted.session.revision,
                text = "Сломанный ответ.",
            )
        }

        val unchanged = agent.getSession(created.session.id)
        assertEquals(extracted.session, unchanged.session)
        assertEquals(extracted.messages, unchanged.messages)
        assertEquals(extracted.draft, unchanged.draft)
        assertEquals(extracted.metrics, unchanged.metrics)
    }

    @Test
    fun ambiguousAppendAndCorrectionDoesNotChangeTransactions() = runBlocking {
        val gateway = QueuedGateway(initialDraftJson, needsClarificationJson)
        val agent = testAgent(MemoryImportSessionRepository(), gateway)
        val created = agent.createSession("Неоднозначное добавление", defaultConfig)
        val extracted = agent.sendMessage(created.session.id, 0, "Первая выписка")

        val result = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = extracted.session.revision,
            text = "Добавь выписку и исправь первую строку.",
        )

        assertEquals(extracted.draft!!.transactions, result.draft!!.transactions)
        assertEquals("Разделите добавление и исправление на два сообщения.", result.messages.last().displayText)
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
    fun replacementCannotChangeTransactionSourceIndex() = runBlocking {
        val agent = testAgent(MemoryImportSessionRepository(), QueuedGateway(initialDraftJson))
        val created = agent.createSession("Stable transaction identity", defaultConfig)
        val imported = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = created.session.revision,
            text = "Синтетическая выписка",
        )
        val original = imported.draft!!.transactions.first()

        assertFailsWith<IllegalArgumentException> {
            agent.replaceTransaction(
                sessionId = created.session.id,
                expectedRevision = imported.session.revision,
                transactionId = original.id,
                included = original.included,
                description = original.description,
                replacement = original.transaction.copy(
                    sourceIndex = original.transaction.sourceIndex + 1,
                ),
            )
        }

        val unchanged = agent.getSession(created.session.id)
        val retained = unchanged.draft!!.transactions.first()
        assertEquals(imported.session.revision, unchanged.session.revision)
        assertEquals(original.id, retained.id)
        assertEquals(original.transaction.sourceIndex, retained.transaction.sourceIndex)
        assertEquals(imported.draft, unchanged.draft)
    }

    @Test
    fun merchantNormalizationPreservesSourceLanguageWithoutGlobalBrandMap() {
        assertEquals("Оплата в ДИКСИ", normalizeMerchantLabel("Оплата в ДИКСИ"))
        assertEquals("Coca-Cola", normalizeMerchantLabel("Coca-Cola"))
        assertEquals("DODOPIZZA", normalizeMerchantLabel("DODOPIZZA"))
        assertEquals("COFFEEBON 37", normalizeMerchantLabel("COFFEEBON 37"))
        assertEquals("LAMODA", normalizeMerchantLabel("LAMODA"))
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
    }
    @Test
    fun routesShortWorkingAndLongTermMemoryWithoutCrossSessionLeakage() = runBlocking {
        val gateway = QueuedGateway(initialDraftWithMemoryCandidateJson, excludePatchJson, initialDraftJson)
        val repository = MemoryImportSessionRepository()
        val agent = testAgent(repository, gateway)
        agent.updatePreferences("Профиль для всех сессий")
        val sessionA = agent.createSession("Сессия A", defaultConfig)

        val extractedA = agent.sendMessage(sessionA.session.id, 0, "A-PRIVATE-CONTEXT")
        val candidate = extractedA.memoryCandidates.single()
        assertEquals("Подтверждённое правило импорта", candidate.text)
        assertEquals(extractedA.messages.last().id, candidate.sourceMessageId)
        assertEquals(MemoryCandidateStatus.PENDING, candidate.status)
        assertTrue(gateway.requests[0].messages.first().content.contains("<general-user-instructions>"))
        assertTrue(agent.getPreferences().confirmedDecisions.isEmpty())

        val accepted = agent.acceptMemoryCandidate(
            sessionId = extractedA.session.id,
            expectedRevision = extractedA.session.revision,
            candidateId = candidate.id,
        )
        val decision = agent.getPreferences().confirmedDecisions.single()
        assertEquals(candidate.text, decision.text)
        assertEquals(extractedA.session.revision + 1, accepted.session.revision)
        assertEquals(MemoryCandidateStatus.ACCEPTED, accepted.memoryCandidates.single().status)
        assertEquals(decision.id, accepted.memoryCandidates.single().acceptedDecisionId)

        val correctedA = agent.sendMessage(
            sessionId = accepted.session.id,
            expectedRevision = accepted.session.revision,
            text = "A follow-up",
        )
        assertEquals(
            listOf(MemoryLayer.SHORT_TERM, MemoryLayer.WORKING, MemoryLayer.LONG_TERM),
            correctedA.memoryTrace!!.selectedLayers,
        )
        assertTrue(gateway.requests[1].messages.any { it.content.contains("A-PRIVATE-CONTEXT") })
        assertTrue(gateway.requests[1].messages.any { it.content.contains("Current import draft JSON") })
        assertTrue(gateway.requests[1].messages.first().content.contains(candidate.text))
        assertEquals(MemoryCandidateStatus.ACCEPTED, correctedA.memoryCandidates.single().status)

        val sessionB = agent.createSession("Сессия B", defaultConfig)
        val emptyB = agent.getSession(sessionB.session.id)
        assertTrue(emptyB.messages.isEmpty())
        assertNull(emptyB.draft)

        val extractedB = agent.sendMessage(sessionB.session.id, 0, "B-ONLY-CONTEXT")
        assertEquals(
            listOf(MemoryLayer.LONG_TERM),
            extractedB.memoryTrace!!.selectedLayers,
        )
        assertTrue(gateway.requests[2].messages.first().content.contains("Профиль для всех сессий"))
        assertTrue(gateway.requests[2].messages.first().content.contains(candidate.text))
        assertFalse(gateway.requests[2].messages.any { it.content.contains("A-PRIVATE-CONTEXT") })
        assertFalse(gateway.requests[2].messages.any { it.content.contains("Current import draft JSON") })

        val edited = agent.updateConfirmedDecision(decision.id, "Изменённое правило импорта")
        assertEquals("Изменённое правило импорта", edited.confirmedDecisions.single().text)
        assertTrue(agent.deleteConfirmedDecision(decision.id).confirmedDecisions.isEmpty())
    }
    @Test
    fun confirmedDecisionsAreNotReproposedAsMemoryCandidates() = runBlocking {
        val gateway = QueuedGateway(
            initialDraftWithTransferMemoryCandidateJson,
            followUpWithRepeatedAndNewMemoryCandidatesJson,
        )
        val agent = testAgent(
            repository = MemoryImportSessionRepository(),
            gateway = gateway,
        )
        val created = agent.createSession("Повторное решение", defaultConfig)

        val extracted = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = 0,
            text = "Не включай переводы себе.",
        )
        val firstCandidate = extracted.memoryCandidates.single()
        val accepted = agent.acceptMemoryCandidate(
            sessionId = extracted.session.id,
            expectedRevision = extracted.session.revision,
            candidateId = firstCandidate.id,
        )

        val followUp = agent.sendMessage(
            sessionId = accepted.session.id,
            expectedRevision = accepted.session.revision,
            text = "И ещё не включать покупки без чека.",
        )
        val followUpCandidates = followUp.memoryCandidates.filter {
            it.sourceMessageId == followUp.messages.last().id
        }

        assertEquals(
            listOf("Не включать покупки без чека."),
            followUpCandidates.map(MemoryCandidate::text),
        )
    }


    @Test
    fun memoryProjectionShowsSelectedLayersAndSafeDisplayFields() = runBlocking {
        val repository = MemoryImportSessionRepository()
        val agent = testAgent(repository, QueuedGateway(initialDraftJson))
        agent.updatePreferences("Показывай только синтетические операции")
        val created = agent.createSession("Проекция памяти", defaultConfig)

        val emptyProjection = agent.getMemoryProjection(created.session.id)
        assertEquals(listOf(MemoryLayer.LONG_TERM), emptyProjection.selectedLayers)
        assertNull(emptyProjection.shortTerm)
        assertNull(emptyProjection.working)
        assertEquals(
            "Показывай только синтетические операции",
            emptyProjection.longTerm?.userPrompt,
        )

        val extracted = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = created.session.revision,
            text = "DEMO MARKET 1250 RUB",
        )
        val projection = agent.getMemoryProjection(extracted.session.id)
        assertEquals(
            listOf(MemoryLayer.SHORT_TERM, MemoryLayer.WORKING, MemoryLayer.LONG_TERM),
            projection.selectedLayers,
        )
        val shortTerm = projection.shortTerm ?: error("Ожидался short-term слой.")
        assertTrue(shortTerm.messages.any { it.displayText == "DEMO MARKET 1250 RUB" })
        val working = projection.working ?: error("Ожидался working слой.")
        assertTrue(working.hasDraft)
        assertEquals("Еда / Продукты", working.transactions.first().categoryDisplayName)
        assertEquals("Показывай только синтетические операции", projection.longTerm?.userPrompt)
    }



    @Test
    fun categoryCatalogEnforcesHierarchyTypesAndArchiveLifecycle() = runBlocking {
        val agent = testAgent(MemoryImportSessionRepository(), QueuedGateway(initialDraftJson))
        val root = agent.createCategory(
            displayName = "Кино",
            type = CategoryType.EXPENSE,
            parentId = null,
            hint = "Билеты и подписки",
        ).single { it.displayName == "Кино" }
        val child = agent.createCategory(
            displayName = "Премьеры",
            type = null,
            parentId = root.id,
            hint = "",
        ).single { it.displayName == "Премьеры" }

        assertEquals(CategoryType.EXPENSE, child.type)
        assertEquals(root.id, child.parentId)
        assertFailsWith<IllegalArgumentException> {
            agent.createCategory("Доход с проката", CategoryType.INCOME, root.id, "")
        }
        assertFailsWith<IllegalArgumentException> {
            agent.createCategory(" кино ", CategoryType.EXPENSE, null, "")
        }
        assertFailsWith<IllegalArgumentException> {
            agent.archiveCategory(root.id)
        }
        assertFailsWith<IllegalArgumentException> {
            agent.updateCategory(root.id, "Кино", child.id, "")
        }

        agent.archiveCategory(child.id)
        val archived = agent.archiveCategory(root.id)
        assertTrue(archived.single { it.id == root.id }.archived)
        assertTrue(agent.listCategories().none { it.id == root.id || it.id == child.id })
        assertFailsWith<IllegalArgumentException> {
            agent.createCategory("КИНО", CategoryType.EXPENSE, null, "")
        }
        Unit
    }

    @Test
    fun categoryPromptContainsOnlyActiveLeavesWithMachineIdsAndDisplayPaths() = runBlocking {
        val gateway = QueuedGateway(initialDraftJson)
        val agent = testAgent(MemoryImportSessionRepository(), gateway)
        agent.createCategory("Кино", CategoryType.EXPENSE, null, "Билеты")
        val session = agent.createSession("Каталог в prompt", defaultConfig)

        agent.sendMessage(session.session.id, session.session.revision, "Синтетическая выписка")

        val systemPrompt = gateway.requests.single().messages.first().content
        assertTrue(systemPrompt.contains("food.groceries"))
        assertTrue(systemPrompt.contains("Еда / Продукты"))
        assertTrue(systemPrompt.contains("Кино"))
        assertFalse(systemPrompt.contains("category.food"))
        assertFalse(systemPrompt.contains("- transfer.internal"))
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
            replacement = second,
        )

        val batch = agent.buildImportBatch(created.session.id)
        assertEquals(1, batch.transactions.size)
        assertEquals(2, batch.transactions.single().sourceIndex)
        assertEquals("Поездка на встречу", batch.transactions.single().description)
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

    @Test
    fun appendsStatementAfterNotApplicableInput() = runBlocking {
        val gateway = QueuedGateway(notApplicableJson, appendStatementJson)
        val agent = testAgent(MemoryImportSessionRepository(), gateway)
        val created = agent.createSession("Сначала не выписка", defaultConfig)

        val notApplicable = agent.sendMessage(created.session.id, 0, "Как приготовить суп?")
        val result = agent.sendMessage(
            sessionId = created.session.id,
            expectedRevision = notApplicable.session.revision,
            text = "Теперь вот банковская выписка.",
        )

        assertEquals(ImportStatus.READY, result.draft!!.status)
        assertEquals(2, result.draft!!.transactions.size)
        assertNull(result.draft!!.rejectionReason)
    }

    @Test
    fun storesNativeUsageAndAccumulatesSuccessfulSessionCalls() = runBlocking {
        val gateway = UsageGateway(
            responseContents = listOf(initialDraftJson, emptyAppliedPatchJson),
            usages = listOf(
                Usage(promptTokens = 120, completionTokens = 30, totalTokens = 150),
                Usage(promptTokens = 260, completionTokens = 20, totalTokens = 280),
            ),
        )
        val repository = MemoryImportSessionRepository()
        val agent = testAgent(repository, gateway)
        val created = agent.createSession("Токены", defaultConfig)

        val extracted = agent.sendMessage(created.session.id, 0, "Короткая выписка")
        val corrected = agent.sendMessage(
            created.session.id,
            extracted.session.revision,
            "Оставь текущий draft без изменений",
        )

        assertEquals(listOf(150, 280), corrected.metrics.map { it.totalTokens })
        assertEquals(260, corrected.metrics.last().promptTokens)
        assertEquals(20, corrected.metrics.last().completionTokens)
        assertEquals(4, corrected.messages.size)
        assertTrue(gateway.requests[1].messages.any { it.role == "assistant" })
    }

    @Test
    fun contextOverflowAddsFailureMetricWithoutChangingConversationState() = runBlocking {
        val repository = MemoryImportSessionRepository()
        val agent = testAgent(repository, OverflowGateway())
        val created = agent.createSession("Переполнение", defaultConfig)

        val result = agent.sendMessage(created.session.id, 0, "Очень длинный контекст")

        assertEquals(CONTEXT_OVERFLOW_MESSAGE, result.lastError)
        assertEquals(0, result.session.revision)
        assertTrue(result.messages.isEmpty())
        assertNull(result.draft)
        assertEquals(1, result.metrics.size)
        assertEquals(ModelCallStatus.CONTEXT_OVERFLOW, result.metrics.single().status)
        assertNull(result.metrics.single().totalTokens)
        assertEquals(128, result.metrics.single().contextWindowTokens)
    }

    @Test
    fun slidingWindowUsesOnlyConfiguredRecentMessagesButKeepsArchive() = runBlocking {
        val gateway = QueuedGateway(initialDraftJson, emptyAppliedPatchJson, emptyAppliedPatchJson, emptyAppliedPatchJson)
        val repository = MemoryImportSessionRepository()
        val agent = testAgent(
            repository = repository,
            gateway = gateway,
            runtimeConfig = AgentRuntimeConfig(
                maxTokens = 100_000,
                contextManagement = ContextManagementConfig(
                    strategy = ContextStrategy.SLIDING_WINDOW,
                    recentMessages = 2,
                ),
            ),
        )
        val created = agent.createSession("Sliding Window", defaultConfig)
        var state = agent.sendMessage(created.session.id, 0, "first")
        repeat(3) { index ->
            state = agent.sendMessage(state.session.id, state.session.revision, "follow-up-$index")
        }

        val request = gateway.requests.last()
        assertEquals(8, state.messages.size)
        assertTrue(state.messages.any { it.content.contains("first") })
        assertFalse(request.messages.any { it.content == "first" })
        assertTrue(request.messages.any { it.content == "follow-up-1" })
        assertTrue(request.messages.any { it.content == "follow-up-2" })
    }

    @Test
    fun stickyFactsRunsSeparateUpdaterAndPersistsOnlyAfterNormalSuccess() = runBlocking {
        val gateway = QueuedGateway(
            """{"upserts":[{"key":"import_code","value":"SEPTEMBER-FAMILY"}],"delete_keys":[]}""",
            initialDraftJson,
        )
        val repository = MemoryImportSessionRepository()
        val agent = testAgent(
            repository = repository,
            gateway = gateway,
            runtimeConfig = AgentRuntimeConfig(
                maxTokens = 100_000,
                contextManagement = ContextManagementConfig(
                    strategy = ContextStrategy.STICKY_FACTS,
                    recentMessages = 2,
                    maxFacts = 4,
                ),
            ),
        )
        val created = agent.createSession("Sticky Facts", defaultConfig)
        val result = agent.sendMessage(created.session.id, 0, "Код импорта SEPTEMBER-FAMILY")

        assertEquals(2, gateway.requests.size)
        assertEquals(ModelCallType.FACTS, result.metrics[0].callType)
        assertEquals(ModelCallType.NORMAL, result.metrics[1].callType)
        assertEquals("SEPTEMBER-FAMILY", result.facts.single().value)
        assertTrue(gateway.requests[1].messages.any { it.content.contains("SEPTEMBER-FAMILY") })
    }

    @Test
    fun stickyFactsFailureDoesNotSendNormalRequestOrPersistFacts() = runBlocking {
        val gateway = QueuedGateway("""{"unexpected":"broken"}""")
        val repository = MemoryImportSessionRepository()
        val agent = testAgent(
            repository = repository,
            gateway = gateway,
            runtimeConfig = AgentRuntimeConfig(
                maxTokens = 100_000,
                contextManagement = ContextManagementConfig(
                    strategy = ContextStrategy.STICKY_FACTS,
                    recentMessages = 2,
                ),
            ),
        )
        val created = agent.createSession("Sticky Facts error", defaultConfig)

        assertFailsWith<AgentResponseException> {
            agent.sendMessage(created.session.id, 0, "Не сохраняй это")
        }
        val stored = repository.get(created.session.id)!!
        assertEquals(1, gateway.requests.size)
        assertTrue(stored.messages.isEmpty())
        assertTrue(stored.facts.isEmpty())
        assertTrue(stored.metrics.isEmpty())
    }

    @Test
    fun sessionContextSnapshotWinsOverLaterRuntimeConfiguration() = runBlocking {
        val gateway = QueuedGateway(initialDraftJson, emptyAppliedPatchJson)
        val repository = MemoryImportSessionRepository()
        val slidingAgent = testAgent(
            repository = repository,
            gateway = gateway,
            runtimeConfig = AgentRuntimeConfig(
                maxTokens = 100_000,
                contextManagement = ContextManagementConfig(
                    strategy = ContextStrategy.BRANCHING,
                ),
            ),
        )
        val created = slidingAgent.createSession(
            title = "Snapshot",
            config = defaultConfig,
            contextManagement = ContextManagementConfig(
                strategy = ContextStrategy.SLIDING_WINDOW,
                recentMessages = 1,
            ),
        )
        var state = slidingAgent.sendMessage(created.session.id, 0, "first")
        state = slidingAgent.sendMessage(state.session.id, state.session.revision, "second")

        assertTrue(gateway.requests.last().messages.none { it.content == "first" })
        assertEquals(ContextStrategy.SLIDING_WINDOW, state.session.contextManagement?.strategy)
    }

    @Test
    fun branchingForkCopiesCheckpointAndKeepsSourceIndependent() = runBlocking {
        val gateway = QueuedGateway(initialDraftJson, emptyAppliedPatchJson, emptyAppliedPatchJson)
        val repository = MemoryImportSessionRepository()
        val agent = testAgent(
            repository = repository,
            gateway = gateway,
            runtimeConfig = AgentRuntimeConfig(
                maxTokens = 100_000,
                contextManagement = ContextManagementConfig(strategy = ContextStrategy.BRANCHING),
            ),
        )
        val created = agent.createSession("Branching", defaultConfig)
        var source = agent.sendMessage(created.session.id, 0, "Требования")
        source = agent.sendMessage(source.session.id, source.session.revision, "Соглашение A")
        val fork = agent.forkSession(source.session.id, source.session.revision)
        val forked = agent.sendMessage(fork.session.id, fork.session.revision, "Решение B")
        val sourceAfter = repository.get(source.session.id)!!

        assertEquals(source.messages, fork.messages)
        assertEquals(source.draft, fork.draft)
        assertEquals(source.session.id, fork.session.parentSessionId)
        assertTrue(fork.metrics.all(ModelCallMetric::inherited))
        assertEquals(source.messages, sourceAfter.messages)
        assertEquals(6, forked.messages.size)
        assertTrue(forked.metrics.last().inherited.not())
    }

    private fun testAgent(
        repository: MemoryImportSessionRepository,
        gateway: ChatCompletionGateway,
        runtimeConfig: AgentRuntimeConfig = AgentRuntimeConfig(maxTokens = 100_000),
    ): SmartExpenseAgent {
        var nextId = 0
        var now = 1_000L
        return SmartExpenseAgent(
            repository = repository,
            gatewayResolver = AgentGatewayResolver { gateway },
            idGenerator = { "id-${++nextId}" },
            nowEpochMs = { ++now },
            runtimeConfig = runtimeConfig,
        )
    }
    private class ProfileAwareGateway : ChatCompletionGateway {
        val requests = mutableListOf<ChatCompletionRequest>()

        override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
            requests += request
            val systemPrompt = request.messages.firstOrNull()?.content.orEmpty()
            val content = if (systemPrompt.contains("You process one follow-up message")) {
                val message = if (systemPrompt.contains("style: Кратко")) {
                    "Краткий статус без лишних деталей."
                } else {
                    "Подробный статус с контекстом операции."
                }
                """{"intent":"needs_clarification","message":"$message","operations":[],"transactions":[],"memory_candidates":[]}"""
            } else {
                initialDraftJson
            }
            return ChatCompletionResponse(
                choices = listOf(
                    ChatChoice(
                        message = ResponseMessage(content = content),
                        finishReason = "stop",
                    ),
                ),
            )
        }
    }


    private class QueuedGateway(vararg responseContents: String) : ChatCompletionGateway {
        override var contextWindowTokens: Int? = null
        override var maxOutputTokens: Int? = null
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

    private class TokenAwareGateway(
        override val contextWindowTokens: Int,
        private val initialResponse: String,
        private val summaryResponse: String,
    ) : ChatCompletionGateway {
        val requests = mutableListOf<ChatCompletionRequest>()

        override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
            requests += request
            val firstMessage = request.messages.firstOrNull()?.content.orEmpty()
            val content = when {
                firstMessage.contains("Summarize an earlier") -> summaryResponse
                firstMessage.contains("Extract financial transactions") -> initialResponse
                else -> emptyAppliedPatchJson
            }
            return ChatCompletionResponse(
                choices = listOf(
                    ChatChoice(
                        message = ResponseMessage(content = content),
                        finishReason = "stop",
                    ),
                ),
            )
        }
    }

    private class UsageGateway(
        private val responseContents: List<String>,
        private val usages: List<Usage>,
    ) : ChatCompletionGateway {
        val requests = mutableListOf<ChatCompletionRequest>()
        private var index = 0

        override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
            requests += request
            val currentIndex = index++
            return ChatCompletionResponse(
                choices = listOf(
                    ChatChoice(
                        message = ResponseMessage(content = responseContents[currentIndex]),
                        finishReason = "stop",
                    ),
                ),
                usage = usages[currentIndex],
            )
        }
    }

    private class OverflowGateway : ChatCompletionGateway {
        override val contextWindowTokens: Int = 128

        override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
            throw ContextWindowExceededException()
        }
    }

    private class MemoryImportSessionRepository : ImportSessionRepository {
        private val states = linkedMapOf<String, ImportSessionState>()
        private var preferences: UserPreferences? = null

        override suspend fun create(session: ImportSession): ImportSessionState =
            ImportSessionState(session, emptyList()).also { states[session.id] = it }

        override suspend fun list(): List<ImportSession> = states.values.map { it.session }

        override suspend fun get(id: String): ImportSessionState? = states[id]

        private val categories = DEFAULT_AGENT_CATEGORIES.toMutableList()

        override suspend fun listCategories(includeArchived: Boolean): List<TransactionCategory> =
            categories.filter { includeArchived || !it.archived }

        override suspend fun insertCategory(category: TransactionCategory): TransactionCategory {
            require(categories.none { it.id == category.id })
            require(categories.none { it.displayName.equals(category.displayName, ignoreCase = true) })
            categories += category
            return category
        }

        override suspend fun updateCategory(category: TransactionCategory): TransactionCategory {
            val index = categories.indexOfFirst { it.id == category.id }
            require(index >= 0)
            categories[index] = category
            return category
        }

        override suspend fun archiveCategory(id: String): TransactionCategory {
            val index = categories.indexOfFirst { it.id == id }
            require(index >= 0)
            return categories[index].copy(archived = true).also { categories[index] = it }
        }

        override suspend fun isCategoryReferenced(id: String): Boolean =
            states.values.any { state ->
                state.draft?.transactions?.any { it.transaction.categoryId == id } == true
            }

        override suspend fun saveExchange(
            sessionId: String,
            expectedRevision: Long,
            userMessage: ConversationMessage,
            assistantMessage: ConversationMessage,
            draft: ImportDraft,
            metric: ModelCallMetric,
            updatedAtEpochMs: Long,
            facts: List<StickyFact>?,
            additionalMetrics: List<ModelCallMetric>,
            memoryTrace: MemoryTrace?,
            memoryCandidates: List<MemoryCandidate>,
            merchantCanonicalCandidates: List<MerchantCanonicalCandidate>,
            receiptState: ReceiptState?,
        ): ImportSessionState = update(
            sessionId,
            expectedRevision,
            updatedAtEpochMs,
            draft,
            metrics = { it.metrics + additionalMetrics + metric },
            facts = facts,
            memoryTrace = memoryTrace,
            memoryCandidates = memoryCandidates,
            merchantCanonicalCandidates = merchantCanonicalCandidates,
            receiptState = receiptState,
        ) { state ->
            state.messages + userMessage + assistantMessage
        }

        override suspend fun saveMessageExchange(
            sessionId: String,
            expectedRevision: Long,
            userMessage: ConversationMessage,
            assistantMessage: ConversationMessage,
            metric: ModelCallMetric?,
            updatedAtEpochMs: Long,
            merchantCanonicalCandidates: List<MerchantCanonicalCandidate>,
        ): ImportSessionState {
            val current = checkedState(sessionId, expectedRevision)
            return current.copy(
                session = current.session.copy(
                    revision = expectedRevision + 1,
                    updatedAtEpochMs = updatedAtEpochMs,
                ),
                messages = current.messages + userMessage + assistantMessage,
                metrics = metric?.let { current.metrics + it } ?: current.metrics,
                merchantCanonicalCandidates = current.merchantCanonicalCandidates +
                    merchantCanonicalCandidates,
            ).also { states[sessionId] = it }
        }

        override suspend fun saveReceiptAssociationExchange(
            sessionId: String,
            expectedRevision: Long,
            userMessage: ConversationMessage,
            assistantMessage: ConversationMessage,
            draft: ImportDraft,
            updatedAtEpochMs: Long,
            receiptRule: ConfirmedDecision?,
            receiptState: ReceiptState,
        ): ImportSessionState {
            val current = checkedState(sessionId, expectedRevision)
            require(draft.version == expectedRevision + 1)
            receiptRule?.let { rule ->
                preferences = (preferences ?: UserPreferences("")).copy(
                    confirmedDecisions = (preferences?.confirmedDecisions ?: emptyList()) + rule,
                )
            }
            return current.copy(
                session = current.session.copy(
                    revision = expectedRevision + 1,
                    updatedAtEpochMs = updatedAtEpochMs,
                ),
                messages = current.messages + userMessage + assistantMessage,
                draft = draft,
                receiptState = receiptState,
            ).also { states[sessionId] = it }
        }

        override suspend fun acceptMemoryCandidate(
            sessionId: String,
            expectedRevision: Long,
            candidateId: String,
            decision: ConfirmedDecision,
            updatedAtEpochMs: Long,
        ): ImportSessionState {
            val current = checkedState(sessionId, expectedRevision)
            val candidate = current.memoryCandidates.singleOrNull { it.id == candidateId }
                ?: throw IllegalArgumentException("Кандидат решения не найден: $candidateId")
            require(candidate.status == MemoryCandidateStatus.PENDING)
            preferences = (preferences ?: UserPreferences("")).copy(
                confirmedDecisions = (preferences?.confirmedDecisions ?: emptyList()) + decision,
            )
            return current.copy(
                session = current.session.copy(
                    revision = expectedRevision + 1,
                    updatedAtEpochMs = updatedAtEpochMs,
                ),
                memoryCandidates = current.memoryCandidates.map { stored ->
                    if (stored.id == candidateId) {
                        stored.copy(
                            status = MemoryCandidateStatus.ACCEPTED,
                            acceptedDecisionId = decision.id,
                            acceptedAtEpochMs = updatedAtEpochMs,
                        )
                    } else {
                        stored
                    }
                },
            ).also { states[sessionId] = it }
        }
        override suspend fun acceptMerchantCanonicalCandidate(
            sessionId: String,
            expectedRevision: Long,
            candidateId: String,
            rule: MerchantCanonicalRule,
            updatedAtEpochMs: Long,
        ): ImportSessionState {
            val current = checkedState(sessionId, expectedRevision)
            val candidate = current.merchantCanonicalCandidates.singleOrNull { it.id == candidateId }
                ?: throw IllegalArgumentException("Предложение canonical rule не найдено: $candidateId")
            require(candidate.status == MemoryCandidateStatus.PENDING)
            preferences = (preferences ?: UserPreferences("")).copy(
                merchantCanonicalRules = (preferences?.merchantCanonicalRules ?: emptyList()) + rule,
            )
            return current.copy(
                session = current.session.copy(
                    revision = expectedRevision + 1,
                    updatedAtEpochMs = updatedAtEpochMs,
                ),
                merchantCanonicalCandidates = current.merchantCanonicalCandidates.map { stored ->
                    if (stored.id == candidateId) {
                        stored.copy(
                            status = MemoryCandidateStatus.ACCEPTED,
                            acceptedRuleId = rule.id,
                            acceptedAtEpochMs = updatedAtEpochMs,
                        )
                    } else {
                        stored
                    }
                },
            ).also { states[sessionId] = it }
        }

        override suspend fun saveSummaryBatch(
            sessionId: String,
            expectedRevision: Long,
            updates: List<ConversationSummaryUpdate>,
        ): ImportSessionState {
            val current = checkedState(sessionId, expectedRevision)
            require(updates.isNotEmpty())
            return current.copy(
                summary = updates.last().summary,
                metrics = current.metrics + updates.map(ConversationSummaryUpdate::metric),
            ).also { states[sessionId] = it }
        }
        override suspend fun saveCallMetric(
            sessionId: String,
            expectedRevision: Long,
            metric: ModelCallMetric,
        ): ImportSessionState {
            val current = checkedState(sessionId, expectedRevision)
            return current.copy(metrics = current.metrics + metric).also { states[sessionId] = it }
        }

        override suspend fun saveDraft(
            sessionId: String,
            expectedRevision: Long,
            draft: ImportDraft,
            updatedAtEpochMs: Long,
            receiptState: ReceiptState?,
        ): ImportSessionState = update(
            sessionId = sessionId,
            expectedRevision = expectedRevision,
            updatedAtEpochMs = updatedAtEpochMs,
            draft = draft,
            receiptState = receiptState,
        ) { it.messages }

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
                facts = current.facts,
            ).also { states[sessionId] = it }
        }

        override suspend fun delete(sessionId: String, expectedRevision: Long) {
            checkedState(sessionId, expectedRevision)
            states.remove(sessionId)
        }

        override suspend fun fork(
            sessionId: String,
            expectedRevision: Long,
            forkSession: ImportSession,
        ): ImportSessionState {
            val current = checkedState(sessionId, expectedRevision)
            return ImportSessionState(
                session = forkSession,
                messages = current.messages,
                draft = current.draft,
                facts = current.facts,
                metrics = current.metrics.map { it.copy(inherited = true) },
                summary = current.summary,
                memoryTrace = current.memoryTrace,
                receiptState = current.receiptState,
                memoryCandidates = current.memoryCandidates,
                merchantCanonicalCandidates = current.merchantCanonicalCandidates,
            ).also { states[forkSession.id] = it }
        }

        override suspend fun getOrCreatePreferences(defaultUserPrompt: String): UserPreferences =
            preferences ?: UserPreferences(defaultUserPrompt).also { preferences = it }

        override suspend fun savePreferences(preferences: UserPreferences): UserPreferences =
            preferences.also { this.preferences = it }



        override suspend fun saveInvariantCheck(
            sessionId: String,
            expectedRevision: Long,
            result: InvariantCheckResult,
            updatedAtEpochMs: Long,
        ): ImportSessionState {
            val current = checkedState(sessionId, expectedRevision)
            return current.copy(
                receiptState = receiptStateFor(current.draft, result),
            ).also { states[sessionId] = it }
        }


        private fun update(
            sessionId: String,
            expectedRevision: Long,
            updatedAtEpochMs: Long,
            draft: ImportDraft,
            metrics: (ImportSessionState) -> List<ModelCallMetric> = { it.metrics },
            facts: List<StickyFact>? = null,
            memoryTrace: MemoryTrace? = null,
            memoryCandidates: List<MemoryCandidate>? = null,
            merchantCanonicalCandidates: List<MerchantCanonicalCandidate>? = null,
            receiptState: ReceiptState? = null,
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
                facts = facts ?: current.facts,
                metrics = metrics(current),
                summary = current.summary,
                memoryTrace = memoryTrace ?: current.memoryTrace,
                receiptState = receiptState ?: receiptStateFor(draft, current.receiptState.lastCompliance),
                merchantCanonicalCandidates =
                    current.merchantCanonicalCandidates + (merchantCanonicalCandidates ?: emptyList()),
                memoryCandidates = current.memoryCandidates + (memoryCandidates ?: emptyList()),
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
                  "needs_review": false,
                  "issues": []
                }
              ],
              "unparsed_fragments": []
            }
        """.trimIndent()
        val initialDraftWithMemoryCandidateJson = initialDraftJson.replace(
            "\"unparsed_fragments\": []",
            "\"unparsed_fragments\": [],\n  \"memory_candidates\": [{\"text\":\"Подтверждённое правило импорта\",\"reason\":\"Пользователь явно сформулировал повторяющееся правило.\"}]",
        )
        val initialDraftWithTransferMemoryCandidateJson = initialDraftJson.replace(
            "\"unparsed_fragments\": []",
            "\"unparsed_fragments\": [],\n  \"memory_candidates\": [{\"text\":\"Не включай переводы себе\",\"reason\":\"Пользователь явно сформулировал повторяющееся правило.\"}]",
        )
        val initialDraftWithGeneratedDescriptionsJson = initialDraftJson
            .replace(
                "\"merchant\": \"DEMO MARKET\",",
                "\"merchant\": \"DEMO MARKET\",\n                  \"description\": \"DEMO MARKET, Яблоки, 2 кг\",",
            )
            .replace(
                "\"merchant\": \"DEMO TAXI\",",
                "\"merchant\": \"DEMO TAXI\",\n                  \"description\": \"DEMO TAXI, 16.01.2026, картой, 450,00 RUB\",",
            )
        val appendIncomeWithDescriptionJson = """
            {
              "intent": "append_statement",
              "message": "Найден доход.",
              "operations": [],
              "transactions": [
                {
                  "source_index": 200,
                  "included": true,
                  "direction": "income",
                  "occurred_at": "2026-01-20T09:00:00",
                  "posted_at": null,
                  "amount_minor": 250000,
                  "currency": "RUB",
                  "merchant": "DEMO EMPLOYER",
                  "description": "Зарплата за январь",
                  "category_id": "income.salary",
                  "needs_review": false,
                  "issues": []
                }
              ]
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
              "intent": "correction",
              "message": "Изменений не найдено.",
              "operations": [],
              "transactions": []
            }
        """.trimIndent()
        val followUpWithRepeatedAndNewMemoryCandidatesJson = """
            {
              "intent": "needs_clarification",
              "message": "Уточнение принято.",
              "operations": [],
              "transactions": [],
              "memory_candidates": [
                {
                  "text": "Не включай переводы себе",
                  "reason": "Это уже подтверждённое решение."
                },
                {
                  "text": "Не включать покупки без чека.",
                  "reason": "Пользователь явно сформулировал новое правило в текущем сообщении."
                }
              ]
            }
        """.trimIndent()


        val appendStatementJson = """
            {
              "intent": "append_statement",
              "message": "Найдены операции во второй выписке.",
              "operations": [],
              "transactions": [
                {
                  "source_index": 99,
                  "included": true,
                  "direction": "expense",
                  "occurred_at": "2026-01-15T12:10:00",
                  "posted_at": "2026-01-15T13:00:00",
                  "amount_minor": 125050,
                  "currency": "RUB",
                  "merchant": "DEMO MARKET-ABC123",
                  "category_id": "food.groceries",
                  "needs_review": false,
                  "issues": []
                },
                {
                  "source_index": 100,
                  "included": true,
                  "direction": "expense",
                  "occurred_at": "2026-01-17T09:00:00",
                  "posted_at": null,
                  "amount_minor": 70000,
                  "currency": "RUB",
                  "merchant": "DEMO CAFE",
                  "category_id": "food.cafes",
                  "needs_review": false,
                  "issues": []
                },
                {
                  "source_index": 101,
                  "included": true,
                  "direction": "expense",
                  "occurred_at": "2026-01-17T09:00:00",
                  "posted_at": null,
                  "amount_minor": 70000,
                  "currency": "RUB",
                  "merchant": "DEMO CAFE",
                  "category_id": "food.cafes",
                  "needs_review": false,
                  "issues": []
                }
              ]
            }
        """.trimIndent()

        val conflictingOptionalAppendStatementJson = """
            {
              "intent": "append_statement",
              "message": "Найдены две операции Госуслуги.",
              "operations": [],
              "transactions": [
                {
                  "source_index": 14,
                  "included": true,
                  "direction": "expense",
                  "occurred_at": "2026-08-18T15:55:00",
                  "posted_at": "2026-08-18T16:02:00",
                  "amount_minor": 600000,
                  "currency": "RUB",
                  "merchant": "Госуслуги",
                  "category_id": null,
                  "needs_review": true,
                  "issues": []
                },
                {
                  "source_index": 15,
                  "included": true,
                  "direction": "expense",
                  "occurred_at": "2026-08-18T15:55:00",
                  "posted_at": "2026-08-19T14:31:00",
                  "amount_minor": 600000,
                  "currency": "RUB",
                  "merchant": "Госуслуги",
                  "category_id": null,
                  "needs_review": true,
                  "issues": []
                }
              ]
            }
        """.trimIndent()

        val allDuplicateAppendStatementJson = """
            {
              "intent": "append_statement",
              "message": "Повтор операции.",
              "operations": [],
              "transactions": [
                {
                  "source_index": 300,
                  "included": true,
                  "direction": "expense",
                  "occurred_at": "2026-01-15T12:10:00",
                  "posted_at": null,
                  "amount_minor": 125050,
                  "currency": "RUB",
                  "merchant": "DEMO MARKET",
                  "category_id": "food.groceries",
                  "needs_review": false,
                  "issues": []
                }
              ]
            }
        """.trimIndent()

        val incompleteAppendStatementJson = """
            {
              "intent": "append_statement",
              "message": "Найдена неполная операция.",
              "operations": [],
              "transactions": [
                {
                  "source_index": 44,
                  "included": true,
                  "direction": "expense",
                  "occurred_at": "",
                  "posted_at": null,
                  "amount_minor": -1,
                  "currency": "RUB",
                  "merchant": "DEMO INCOMPLETE",
                  "category_id": null,
                  "needs_review": true,
                  "issues": ["occurred_at: дата не указана"]
                }
              ]
            }
        """.trimIndent()

        val invalidAppendResponseJson = """
            {
              "intent": "append_statement",
              "message": "Недопустимое смешение форматов.",
              "operations": [
                {
                  "transaction_id": "1",
                  "action": "set_included",
                  "field": null,
                  "value": false
                }
              ],
              "transactions": []
            }
        """.trimIndent()

        val needsClarificationJson = """
            {
              "intent": "needs_clarification",
              "message": "Разделите добавление и исправление на два сообщения.",
              "operations": [],
              "transactions": []
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
                  "needs_review": true,
                  "issues": ["category_id: категория не определена"]
                }
              ],
              "unparsed_fragments": []
            }
        """.trimIndent()

        val categoryPatchJson = """
            {
              "intent": "correction",
              "message": "Категория исправлена.",
              "operations": [
                {
                  "transaction_id": "1",
                  "action": "set_field",
                  "field": "category_id",
                  "value": "food.groceries"
                }
              ],
              "transactions": []
            }
        """.trimIndent()

        val excludePatchJson = """
            {
              "intent": "correction",
              "message": "Операция исключена.",
              "operations": [
                {
                  "transaction_id": "2",
                  "action": "set_included",
                  "field": null,
                  "value": false
                }
              ],
              "transactions": []
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
