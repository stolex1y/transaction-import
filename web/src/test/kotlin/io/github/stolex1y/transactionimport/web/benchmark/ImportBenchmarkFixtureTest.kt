package io.github.stolex1y.transactionimport.web.benchmark

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ImportBenchmarkFixtureTest {
    @Test
    fun bundledFixtureKeepsModelInputAndEvaluationLabelsSeparate() {
        val fixture = ImportBenchmarkFixtureLoader.loadBundled()

        assertEquals("openai-codex/gpt-6-luna:min", fixture.modelSelector)
        assertEquals("medium", fixture.reasoningEffort)
        assertEquals("c3a25d07ee6ab69ba0bf56688744d53803abb747742333bd26f1b33ba376809d", fixture.sha256)
        assertEquals(3, fixture.repetitions)
        assertTrue(fixture.modelInput.userTurn.isNotBlank())
        assertEquals(50, fixture.modelInput.transactions.size)
        assertEquals(51, fixture.modelInput.receiptSummaries.size)
        assertEquals(9, fixture.modelInput.receiptDetails.size)
        assertEquals(12, fixture.modelInput.preferences.confirmedDecisions.size)
        assertEquals(6, fixture.modelInput.preferences.merchantCanonicalRules.size)
        assertEquals(50, fixture.evaluationOnly.selection.size)
        assertEquals(26, fixture.evaluationOnly.selection.values.count { it })
        assertEquals(24, fixture.evaluationOnly.selection.values.count { !it })
        assertEquals(50, fixture.evaluationOnly.categories.size)
        assertEquals(23, fixture.evaluationOnly.categories.values.count { it != null })
        assertEquals(27, fixture.evaluationOnly.categories.values.count { it == null })
        assertEquals(null, fixture.evaluationOnly.categories.getValue("TX02"))
        assertEquals("Рацион", fixture.evaluationOnly.categories.getValue("TX30"))
        assertEquals(50, fixture.evaluationOnly.receiptAssociations.size)
        assertEquals(
            42,
            fixture.evaluationOnly.receiptAssociations.values.count {
                it.expectation == BenchmarkReceiptExpectation.NO_RECEIPT
            },
        )
        assertEquals(
            7,
            fixture.evaluationOnly.receiptAssociations.values.count {
                it.expectation == BenchmarkReceiptExpectation.MATCHED
            },
        )
        assertEquals(
            1,
            fixture.evaluationOnly.receiptAssociations.values.count {
                it.expectation == BenchmarkReceiptExpectation.AMBIGUOUS
            },
        )
        assertEquals(
            setOf("C026", "C027"),
            fixture.evaluationOnly.receiptAssociations.getValue("TX34").receiptIds,
        )
        assertTrue(fixture.modelInput.transactions.all { transaction ->
            when (transaction.direction) {
                "expense" -> transaction.signedAmountMinor < 0L
                "income" -> transaction.signedAmountMinor > 0L
                else -> false
            }
        })
    }

    @Test
    fun acceptsMissingCategoryLabelsWithoutConfusingExplicitNoCategoryLabels() {
        val root = Json.parseToJsonElement(bundledText()).jsonObject
        val evaluation = root.getValue("evaluation_only").jsonObject
        val categoryLabels = evaluation.getValue("categories").jsonArray
        val omittedIndex = categoryLabels.indexOfFirst {
            it.jsonObject.getValue("category") !is JsonNull
        }
        assertTrue(omittedIndex >= 0)
        val remainingLabels = JsonArray(categoryLabels.filterIndexed { index, _ -> index != omittedIndex })
        assertTrue(remainingLabels.any { it.jsonObject.getValue("category") is JsonNull })
        val alteredEvaluation = JsonObject(evaluation + ("categories" to remainingLabels))
        val alteredRoot = JsonObject(root + ("evaluation_only" to alteredEvaluation))

        ImportBenchmarkFixtureLoader.validateForTest(alteredRoot.toString())
    }

    @Test
    fun rejectsUnknownReceiptReferenceInEvaluationOnlyLabels() {
        val source = bundledText()
        val receiptId = Regex(
            """("transaction_id"\s*:\s*"TX15",\s*"expected_status"\s*:\s*"matched",\s*"receipt_id"\s*:\s*")C017(")""",
        ).find(source) ?: error("Fixture has no expected receipt association.")
        val altered = source.replaceRange(
            receiptId.range,
            "${receiptId.groupValues[1]}C999${receiptId.groupValues[2]}",
        )

        val error = assertFailsWith<IllegalArgumentException> {
            ImportBenchmarkFixtureLoader.validateForTest(altered)
        }

        assertFalse(error.message.orEmpty().contains("C999"))
        assertTrue(error.message.orEmpty().contains("unknown receipt"))
    }

    @Test
    fun rejectsEvaluationFieldsInsideReceiptDetails() {
        val source = bundledText()
        val detailMarker = Regex(""""receipt_details"\s*:\s*\[\s*\{\s*"id"\s*:\s*"C\d{3}"""")
            .find(source)
            ?: error("Benchmark fixture has no receipt detail.")
        val altered = source.replaceRange(
            detailMarker.range,
            """${detailMarker.value},"evaluation_only":{"categories":{}}""",
        )

        val error = assertFailsWith<IllegalArgumentException> {
            ImportBenchmarkFixtureLoader.validateForTest(altered)
        }

        assertFalse(error.message.orEmpty().contains("evaluation_only"))
    }

    @Test
    fun rejectsNestedEvaluationObjectInReceiptItemScalarField() {
        val source = bundledText()
        val vatRate = Regex(""""vat_rate"\s*:\s*"[^"]*"""")
            .find(source)
            ?: error("Benchmark fixture has no VAT-rate field.")
        val altered = source.replaceRange(
            vatRate.range,
            "\"vat_rate\":{\"evaluation_only\":{\"categories\":{}}}",
        )

        val error = assertFailsWith<IllegalArgumentException> {
            ImportBenchmarkFixtureLoader.validateForTest(altered)
        }

        assertFalse(error.message.orEmpty().contains("evaluation_only"))
    }

    @Test
    fun rejectsRepetitionCountsThatOverflowInt() {
        val source = bundledText()
        val altered = source.replaceFirst(
            Regex(""""repetitions"\s*:\s*3"""),
            "\"repetitions\": 4294967299",
        )
        assertTrue(altered != source)

        assertFailsWith<IllegalArgumentException> {
            ImportBenchmarkFixtureLoader.validateForTest(altered)
        }
    }

    @Test
    fun rejectsIncompleteReceiptReplayBeforeAnyModelInputIsCreated() {
        val altered = bundledText().replaceFirst("\"has_more\": false", "\"has_more\": true")
        assertTrue(altered != bundledText())

        val error = assertFailsWith<IllegalArgumentException> {
            ImportBenchmarkFixtureLoader.validateForTest(altered)
        }

        assertFalse(error.message.orEmpty().contains("receipt_key"))
        assertFalse(error.message.orEmpty().contains("merchant"))
    }

    @Test
    fun rejectsUnredactedPhoneIncludingJsonUnicodeEscapesWithoutEchoingIt() {
        val phones = listOf(
            "89990000000",
            "8 (999) 000-00-00",
        )
        val candidates = phones.flatMap { phone ->
            listOf(
                phone,
                phone.map { "\\u%04x".format(it.code) }.joinToString(""),
            )
        }

        candidates.forEach { candidate ->
            val source = bundledText()
            val match = Regex("""("merchant"\s*:\s*")[^"]+(")""").find(source)
                ?: error("Fixture has no merchant field.")
            val altered = source.replaceRange(
                match.range,
                "${match.groupValues[1]}$candidate${match.groupValues[2]}",
            )
            assertTrue(altered.contains(candidate))

            val error = assertFailsWith<IllegalArgumentException> {
                ImportBenchmarkFixtureLoader.validateForTest(altered)
            }

            assertFalse(error.message.orEmpty().contains(candidate))
            assertFalse(error.message.orEmpty().contains("89990000000"))
        }
    }

    @Test
    fun rejectsQuotedNumericAndBooleanFields() {
        val source = bundledText()
        val quantity = Regex(""""quantity"\s*:\s*([0-9.]+)""").find(source)
            ?: error("Benchmark fixture has no quantity field.")
        val quotedQuantity = source.replaceRange(
            quantity.range,
            "\"quantity\":\"${quantity.groupValues[1]}\"",
        )

        val price = Regex(""""price_minor"\s*:\s*(\d+)""").find(source)
            ?: error("Benchmark fixture has no receipt-item price.")
        val quotedPrice = source.replaceRange(
            price.range,
            "\"price_minor\":\"${price.groupValues[1]}\"",
        )

        val included = Regex(""""included"\s*:\s*(true|false)""").find(source)
            ?: error("Benchmark fixture has no inclusion label.")
        val quotedIncluded = source.replaceRange(
            included.range,
            "\"included\":\"${included.groupValues[1]}\"",
        )

        val hasMore = Regex(""""has_more"\s*:\s*false""").find(source)
            ?: error("Benchmark fixture has no receipt pagination flag.")
        val quotedHasMore = source.replaceRange(hasMore.range, "\"has_more\":\"false\"")

        listOf(quotedQuantity, quotedPrice, quotedIncluded, quotedHasMore).forEach { altered ->
            assertTrue(altered != source)
            assertFailsWith<IllegalArgumentException> {
                ImportBenchmarkFixtureLoader.validateForTest(altered)
            }
        }
    }

    private fun bundledText(): String =
        ImportBenchmarkFixtureLoader::class.java.getResourceAsStream(ImportBenchmarkFixtureLoader.RESOURCE)
            ?.use { stream -> String(stream.readBytes(), StandardCharsets.UTF_8) }
            ?: error("Benchmark fixture resource is missing.")
}
