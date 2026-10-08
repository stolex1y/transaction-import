package io.github.stolex1y.transactionimport.web.benchmark

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
        assertEquals("8e9e549a09315da7f9348847ff52b17012e44f12cf927237047ff6973c9f231b", fixture.sha256)
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
        assertEquals(4, fixture.evaluationOnly.categories.size)
        assertTrue(fixture.evaluationOnly.categories.values.all { it == "Кафе и рестораны" })
        assertTrue(fixture.modelInput.transactions.all { transaction ->
            when (transaction.direction) {
                "expense" -> transaction.signedAmountMinor < 0L
                "income" -> transaction.signedAmountMinor > 0L
                else -> false
            }
        })
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
