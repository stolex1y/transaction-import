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
            cardLast4 = null,
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
        assertEquals(listOf("6960", "9818"), gosuslugi.map { it.transaction.cardLast4 })
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
        assertFalse(systemPrompt.contains("Между своими счетами"))
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
            "Проверь ещё раз",
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
            receiptState = receiptState,
        ) { state ->
            state.messages + userMessage + assistantMessage
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
                "\"merchant\": \"DEMO MARKET\",\n                  \"description\": \"Яблоки, 2 кг\",",
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
                  "card_last4": null,
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
                  "text": "Не включать переводы себе.",
                  "reason": "Пользователь явно указал это правило в подтверждённых решениях."
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
                  "card_last4": "1234",
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
                  "card_last4": null,
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
                  "card_last4": null,
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
                  "card_last4": "6960",
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
                  "card_last4": "9818",
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
                  "card_last4": "1234",
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
                  "card_last4": null,
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
