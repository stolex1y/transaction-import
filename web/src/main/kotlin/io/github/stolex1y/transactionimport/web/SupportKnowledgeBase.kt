package io.github.stolex1y.transactionimport.web

import io.github.stolex1y.transactionimport.core.SupportCitation
import java.util.Locale
import kotlin.math.ln

internal data class RetrievedSupportPassage(
    val citation: SupportCitation,
    val text: String,
)

internal class SupportKnowledgeBase private constructor(
    private val passages: List<KnowledgePassage>,
) {
    init {
        require(passages.isNotEmpty()) { "Support knowledge base должна содержать документы." }
    }

    fun retrieve(query: String, limit: Int = MAX_PASSAGES): List<RetrievedSupportPassage> {
        require(limit in 1..MAX_PASSAGES) { "Лимит support passages должен быть от 1 до $MAX_PASSAGES." }
        val queryTerms = tokenize(query).toSet()
        if (queryTerms.isEmpty()) return emptyList()
        val averageLength = passages.sumOf { it.terms.size }.toDouble() / passages.size
        return passages.mapIndexedNotNull { index, passage ->
            val score = score(queryTerms, passage, averageLength)
            if (score <= 0.0) null else RankedPassage(index, score, passage)
        }
            .sortedWith(compareByDescending<RankedPassage> { it.score }.thenBy { it.index })
            .take(limit)
            .map { RetrievedSupportPassage(it.passage.citation, it.passage.text) }
    }

    private fun score(queryTerms: Set<String>, passage: KnowledgePassage, averageLength: Double): Double {
        var total = 0.0
        for (term in queryTerms) {
            val frequency = passage.termFrequencies[term] ?: continue
            val documentFrequency = documentFrequencies.getValue(term)
            val inverseDocumentFrequency = ln(
                1.0 + (passages.size - documentFrequency + 0.5) / (documentFrequency + 0.5),
            )
            val lengthNormalization = 1.2 * (1.0 - 0.75 + 0.75 * passage.terms.size / averageLength)
            total += inverseDocumentFrequency * frequency * (1.2 + 1.0) / (frequency + lengthNormalization)
        }
        return total
    }

    private val documentFrequencies: Map<String, Int> = passages
        .flatMap { passage -> passage.termFrequencies.keys }
        .groupingBy { it }
        .eachCount()

    private data class KnowledgePassage(
        val citation: SupportCitation,
        val text: String,
        val terms: List<String>,
        val termFrequencies: Map<String, Int>,
    )

    private data class RankedPassage(
        val index: Int,
        val score: Double,
        val passage: KnowledgePassage,
    )

    companion object {
        private const val MAX_PASSAGES = 4
        private val TOKEN_PATTERN = Regex("[\\p{L}\\p{N}]{2,}")
        private val STOP_WORDS = setOf(
            "а", "без", "бы", "быть", "в", "вам", "вас", "весь", "во", "вот", "все", "всего",
            "всех", "вы", "где", "да", "для", "до", "его", "ее", "если", "есть", "ещё", "же",
            "за", "здесь", "и", "из", "или", "им", "их", "к", "как", "ко", "когда", "кто",
            "ли", "мне", "может", "мы", "на", "надо", "наш", "не", "него", "нее", "нет", "ни",
            "них", "но", "о", "об", "однако", "он", "она", "они", "оно", "от", "очень", "по",
            "под", "при", "с", "со", "так", "также", "такой", "там", "те", "тем", "то", "того",
            "тоже", "тут", "ты", "у", "уже", "хотя", "чего", "чем", "что", "чтобы", "эта", "эти",
            "это", "я",
        )
        private val DOCUMENTS = listOf(
            KnowledgeDocument("support-faq.md", "Частые вопросы Transaction Import"),
            KnowledgeDocument("user-guide.md", "Руководство по интерфейсу Transaction Import"),
        )

        fun load(classLoader: ClassLoader = Thread.currentThread().contextClassLoader): SupportKnowledgeBase {
            val passages = DOCUMENTS.flatMap { document ->
                val resourcePath = "support-knowledge/${document.resourceName}"
                val text = classLoader.getResourceAsStream(resourcePath)?.bufferedReader()?.use { it.readText() }
                    ?: error("Support knowledge resource is missing: $resourcePath")
                parseDocument(document, text)
            }
            return SupportKnowledgeBase(passages)
        }

        private fun parseDocument(document: KnowledgeDocument, markdown: String): List<KnowledgePassage> {
            val passages = mutableListOf<KnowledgePassage>()
            var section = document.title
            val content = StringBuilder()

            fun flush() {
                val passageText = content.toString().trim()
                content.setLength(0)
                if (passageText.isBlank()) return
                val terms = tokenize(passageText)
                if (terms.isEmpty()) return
                passages += KnowledgePassage(
                    citation = SupportCitation(document.resourceName, document.title, section),
                    text = passageText,
                    terms = terms,
                    termFrequencies = terms.groupingBy { it }.eachCount(),
                )
            }

            markdown.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                val heading = HEADING_PATTERN.matchEntire(line)
                if (heading != null) {
                    flush()
                    section = heading.groupValues[1].trim()
                } else if (line.isNotBlank() && !line.startsWith("```") && !line.startsWith("|")) {
                    if (content.isNotEmpty()) content.append('\n')
                    content.append(line.removePrefix("- ").removePrefix("* "))
                }
            }
            flush()
            return passages
        }

        private fun tokenize(text: String): List<String> = TOKEN_PATTERN.findAll(text.lowercase(Locale.ROOT))
            .map { it.value }
            .filterNot(STOP_WORDS::contains)
            .toList()


        private val HEADING_PATTERN = Regex("^#{1,6}\\s+(.+?)\\s*#*$")
    }
}

private data class KnowledgeDocument(
    val resourceName: String,
    val title: String,
)
