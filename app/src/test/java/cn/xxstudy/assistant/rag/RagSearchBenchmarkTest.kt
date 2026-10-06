package cn.xxstudy.assistant.rag

import cn.xxstudy.assistant.rag.db.StoredChunk
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import kotlin.system.measureNanoTime

/** Synthetic desktop JVM comparison, never a claim about Android decoding speed. */
class RagSearchBenchmarkTest {
    @Test fun compareWarmSearchAndAccountForColdIndexConstruction() {
        val random = Random(20261006)
        val data = (0 until 18_679).map { i ->
            val doc = i / 13
            val hive = doc % 100 == 0
            val embedding = FloatArray(512) { random.nextFloat() * .01f }
            embedding[0] = .5f + random.nextFloat() * .5f
            StoredChunk(i.toLong(), doc.toLong(),
                if (hive) "Flutter Hive $doc.md" else "Android 文件 $doc.md", "章节 ${i % 13}",
                (if (hive) "Flutter Hive 使用 Box 和 TypeAdapter 保存自定义对象。" else "文件授权与持久化存储，Android 支持本地离线读取文件。" ).repeat(10),
                embedding)
        }
        val query = "帮我介绍下 flutter 数据 Hive"
        val vector = FloatArray(512).also { it[0] = 1f }
        lateinit var index: RagSearchIndex
        val buildMillis = measureNanoTime { index = RagSearchIndex(data) } / 1_000_000.0
        fun oldSearch(): List<StoredChunk> {
            data class Candidate(val chunk: StoredChunk, val dense: Float, val lexical: Float)
            val prepared = RagHybridRanker.prepare(query)
            val candidates = data.map { chunk ->
                var dense = 0f
                for (i in vector.indices) dense += vector[i] * chunk.embedding[i]
                Candidate(chunk, dense, RagHybridRanker.lexicalBonus(prepared, chunk.docName, chunk.sectionTitle, chunk.content))
            }
            val cutoff = maxOf(.8f, candidates.maxOf { it.dense } - .08f)
            val perDoc = mutableMapOf<Long, Int>()
            return buildList {
                for (candidate in candidates.sortedByDescending { it.dense + it.lexical }) {
                    if (size >= 3) break
                    val count = perDoc[candidate.chunk.docId] ?: 0
                    if (!RagHybridRanker.shouldInclude(candidate.dense, cutoff, candidate.lexical) || count >= 2) continue
                    perDoc[candidate.chunk.docId] = count + 1
                    add(candidate.chunk)
                }
            }
        }
        repeat(3) { oldSearch(); index.search(query, { vector }, 3, .8f) }
        val before = mutableListOf<Double>()
        val after = mutableListOf<Double>()
        repeat(7) {
            before += measureNanoTime { assertTrue(oldSearch().isNotEmpty()) } / 1_000_000.0
            after += measureNanoTime { assertTrue(index.search(query, { vector }, 3, .8f).matches.isNotEmpty()) } / 1_000_000.0
        }
        println("RAG synthetic JVM benchmark: chunks=${data.size}, dims=512, coldIndexMs=$buildMillis, " +
            "oldWarmMedianMs=${before.sorted()[3]}, newWarmMedianMs=${after.sorted()[3]}, " +
            "lexicalPrimitivePayloadBytes=${index.lexicalPayloadBytes} (excludes objects/strings/vectors)")
        // Deliberately no wall-clock speed assertion: CI scheduling must not make correctness flaky.
    }
}
