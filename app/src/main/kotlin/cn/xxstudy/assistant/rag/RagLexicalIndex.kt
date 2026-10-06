package cn.xxstudy.assistant.rag

import cn.xxstudy.assistant.rag.db.StoredChunk
import java.util.Locale
import kotlin.math.ln

/** Compact, in-memory inverted index. Existing SQLite chunks need no schema migration. */
internal class RagLexicalIndex(chunks: List<StoredChunk>) {
    private class PostingBuilder {
        private var ids = IntArray(4)
        private var frequencies = IntArray(4)
        private var size = 0

        fun add(id: Int, frequency: Int) {
            if (size == ids.size) {
                ids = ids.copyOf(size * 2)
                frequencies = frequencies.copyOf(size * 2)
            }
            ids[size] = id
            frequencies[size++] = frequency
        }

        fun build() = Posting(ids.copyOf(size), frequencies.copyOf(size))
    }

    private data class Posting(val ids: IntArray, val frequencies: IntArray)
    data class Scores(val values: FloatArray, val eligible: BooleanArray)

    private val lengths = IntArray(chunks.size)
    private val postings: Map<String, Posting>
    private val averageLength: Float

    init {
        val builders = mutableMapOf<String, PostingBuilder>()
        chunks.forEachIndexed { index, chunk ->
            val frequencies = mutableMapOf<String, Int>()
            fun addField(text: String, weight: Int) {
                for (term in tokenize(text)) frequencies[term] = (frequencies[term] ?: 0) + weight
            }
            addField(chunk.docName.removeSuffix(".md").removeSuffix(".markdown"), 3)
            addField(chunk.sectionTitle, 2)
            val topicTerms = frequencies.keys.toSet()
            addField(chunk.content, 1)
            lengths[index] = frequencies.values.sum().coerceAtLeast(1)
            frequencies.forEach { (term, frequency) ->
                // The sign bit marks title/heading occurrences without a third posting array.
                builders.getOrPut(term) { PostingBuilder() }.add(index,
                    if (term in topicTerms) frequency or Int.MIN_VALUE else frequency)
            }
        }
        postings = builders.mapValues { it.value.build() }
        averageLength = if (lengths.isEmpty()) 1f else lengths.average().toFloat().coerceAtLeast(1f)
    }

    fun score(query: String): Scores {
        val normalized = politePhrases.fold(query.lowercase(Locale.ROOT)) { text, phrase -> text.replace(phrase, " ") }
            .replace(Regex("(?<![\\u4e00-\\u9fff])数据(?![\\u4e00-\\u9fff])"), " ")
        val terms = tokenize(normalized).filterNot { it in ignoredTerms }.distinct().take(32)
        val required = technicalTerms(normalized).filterNot { it in ignoredTerms }.distinct().take(16)
        val scores = FloatArray(lengths.size)
        val coverage = IntArray(lengths.size)
        val technicalCoverage = IntArray(lengths.size)
        val topicCoverage = IntArray(lengths.size)
        for (term in terms) {
            val posting = postings[term] ?: continue
            val idf = ln(1.0 + (lengths.size - posting.ids.size + .5) / (posting.ids.size + .5)).toFloat()
            for (i in posting.ids.indices) {
                val id = posting.ids[i]
                val tf = (posting.frequencies[i] and Int.MAX_VALUE).toFloat()
                val norm = 1.2f * (.25f + .75f * lengths[id] / averageLength)
                scores[id] += idf * tf * 2.2f / (tf + norm)
                coverage[id]++
            }
        }
        for (term in required) {
            val posting = postings[term] ?: continue
            for (i in posting.ids.indices) {
                val id = posting.ids[i]
                technicalCoverage[id]++
                if (posting.frequencies[i] < 0) topicCoverage[id]++
            }
        }
        val hasNamedTopic = required.isNotEmpty() && topicCoverage.any { it == required.size }
        // Keyword-only fallback must not invent a match from one incidental word.
        // Technical names are exact anchors; Chinese-only queries need two overlapping bigrams
        // (or the single bigram of a two-character topic). Dense matches remain independent.
        val eligible = BooleanArray(lengths.size) { id ->
            scores[id] > 0f && if (required.isNotEmpty()) {
                technicalCoverage[id] == required.size && (!hasNamedTopic || topicCoverage[id] == required.size)
            } else {
                coverage[id] >= minOf(2, terms.size).coerceAtLeast(1)
            }
        }
        return Scores(scores, eligible)
    }

    companion object {
        private val technicalPattern = Regex("[a-z][a-z0-9_+.#-]*")
        private val chinesePattern = Regex("[\\u4e00-\\u9fff]{2,}")
        private val ignoredTerms = setOf("please", "introduce", "how", "what", "is", "the", "use", "using", "and", "for", "to", "in", "of", "package",
            "tell", "me", "about", "explain", "can", "could", "would", "you")
        private val politePhrases = listOf(
            "帮我介绍一下", "帮我介绍下", "请帮我", "介绍一下", "介绍下", "请介绍", "请问",
            "如何使用", "怎么使用", "如何", "怎么", "什么是", "是什么", "知识库", "本地", "相关"
        )

        private fun technicalTerms(text: String): List<String> = technicalPattern.findAll(text)
            .map { it.value.trimEnd('.', '-') }.filter { it.length >= 2 }.toList()

        private fun tokenize(text: String): List<String> {
            val normalized = text.lowercase(Locale.ROOT)
            val technical = technicalTerms(normalized).flatMap { term ->
                // Preserve package names with underscores. Qualified APIs also expose their
                // components, so "Hive openBox" can find "Hive.openBox" without a substring scan.
                listOf(term) + if ('.' in term) term.split('.').filter { it.length >= 2 } else emptyList()
            }
            val chinese = chinesePattern.findAll(normalized).flatMap { it.value.windowed(2).asSequence() }.toList()
            return technical + chinese
        }
    }

    /** Primitive payload only: excludes map/string/object overhead and stored source vectors. */
    val payloadBytes: Long get() = lengths.size * 4L + postings.values.sumOf { it.ids.size * 8L }
}
