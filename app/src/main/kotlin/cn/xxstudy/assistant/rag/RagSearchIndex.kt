package cn.xxstudy.assistant.rag

import cn.xxstudy.assistant.rag.db.StoredChunk
import java.util.concurrent.CancellationException

enum class RagSearchMode { HYBRID, KEYWORD, EMPTY }

data class RagSearchResult(
    val matches: List<KnowledgeMatch>,
    val mode: RagSearchMode,
    val incompatibleChunkCount: Int = 0,
    val embeddingFailed: Boolean = false
)

/** Android-independent search seam shared by production retrieval and regression fixtures. */
class RagSearchIndex(private val chunks: List<StoredChunk>) {
    private val lexical = RagLexicalIndex(chunks)
    private val validVectors = BooleanArray(chunks.size) { validVector(chunks[it].embedding) }
    internal val lexicalPayloadBytes: Long get() = lexical.payloadBytes

    fun search(
        query: String,
        embeddingProvider: (String) -> FloatArray?,
        topK: Int,
        threshold: Float
    ): RagSearchResult {
        if (query.isBlank() || chunks.isEmpty()) return RagSearchResult(emptyList(), RagSearchMode.EMPTY)
        var embeddingFailed = false
        val embedding = try {
            embeddingProvider(query)?.takeIf(::validVector)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            embeddingFailed = true
            null
        } catch (_: LinkageError) {
            embeddingFailed = true
            null
        }
        val lexicalScores = lexical.score(query)
        var maxLexical = 0f
        for (index in chunks.indices) {
            if (lexicalScores.eligible[index]) maxLexical = maxOf(maxLexical, lexicalScores.values[index])
        }
        var incompatibleCount = 0
        val denseScores = FloatArray(chunks.size) { Float.NEGATIVE_INFINITY }
        if (embedding != null) {
            chunks.forEachIndexed { index, chunk ->
                if (chunk.embedding.size != embedding.size || !validVectors[index]) {
                    incompatibleCount++
                } else {
                    val score = dotProduct(embedding, chunk.embedding)
                    if (score.isFinite()) denseScores[index] = score else incompatibleCount++
                }
            }
        }
        val best = denseScores.maxOrNull() ?: Float.NEGATIVE_INFINITY
        val minimum = if (threshold.isFinite()) threshold.coerceIn(0f, 1f) else .8f
        val cutoff = maxOf(minimum, best - .08f)
        data class Candidate(val index: Int, val rank: Float)
        val selected = mutableListOf<Candidate>()
        val limit = topK.coerceIn(1, 8)
        for (index in chunks.indices) {
            val keywordHit = lexicalScores.eligible[index]
            if (!keywordHit && denseScores[index] < cutoff) continue
            val lexicalBonus = if (keywordHit && maxLexical > 0f) .24f * lexicalScores.values[index] / maxLexical else 0f
            val dense = denseScores[index].takeIf(Float::isFinite) ?: 0f
            val candidate = Candidate(index, dense + lexicalBonus)
            val sameDoc = selected.filter { chunks[it.index].docId == chunks[index].docId }
            val replace = if (sameDoc.size >= 2) sameDoc.last() else selected.lastOrNull().takeIf { selected.size >= limit }
            if (replace != null) {
                if (candidate.rank <= replace.rank) continue
                selected.remove(replace)
            }
            val position = selected.indexOfFirst { it.rank < candidate.rank }.takeIf { it >= 0 } ?: selected.size
            selected.add(position, candidate)
        }
        val matches = selected.map { candidate ->
            val chunk = chunks[candidate.index]
            KnowledgeMatch(chunk.docName, chunk.sectionTitle, chunk.content,
                denseScores[candidate.index].takeIf(Float::isFinite) ?: 0f, chunk.docId)
        }
        val mode = when {
            best.isFinite() -> RagSearchMode.HYBRID
            matches.isNotEmpty() -> RagSearchMode.KEYWORD
            else -> RagSearchMode.EMPTY
        }
        return RagSearchResult(matches, mode, incompatibleCount, embeddingFailed)
    }

    private fun dotProduct(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    private fun validVector(vector: FloatArray): Boolean =
        vector.isNotEmpty() && vector.all(Float::isFinite) && vector.any { it != 0f }
}
