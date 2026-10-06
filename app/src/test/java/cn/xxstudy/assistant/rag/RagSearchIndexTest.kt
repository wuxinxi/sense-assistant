package cn.xxstudy.assistant.rag

import cn.xxstudy.assistant.rag.db.StoredChunk
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.random.Random

class RagSearchIndexTest {
    private val chunks = listOf(
        StoredChunk(1, 1, "Flutter Hive.md", "自定义对象", "Flutter Hive 使用 TypeAdapter 保存自定义对象。", floatArrayOf(1f, 0f)),
        StoredChunk(2, 2, "Apache Hive.md", "查询", "Apache Hive 使用 SQL 查询数据仓库。", floatArrayOf(0f, 1f)),
        StoredChunk(3, 3, "Android 权限.md", "SAF", "申请持久化文件夹授权。", floatArrayOf(0f, 1f))
    )

    @Test fun missingEmbeddingStillFindsTheFlutterHiveNote() {
        val result = RagSearchIndex(chunks).search("帮我介绍下 flutter 数据 Hive", { null }, 3, .8f)
        assertEquals(RagSearchMode.KEYWORD, result.mode)
        assertEquals("Flutter Hive.md", result.matches.firstOrNull()?.docName)
        assertFalse(result.matches.any { it.docName == "Android 权限.md" })
    }

    @Test fun nativeLoadFailureStillFindsKeywords() {
        val result = RagSearchIndex(chunks).search("Flutter Hive", { throw UnsatisfiedLinkError("fixture") }, 3, .8f)
        assertEquals("Flutter Hive.md", result.matches.firstOrNull()?.docName)
        assertEquals(RagSearchMode.KEYWORD, result.mode)
    }

    @Test fun incompatibleDimensionsDoNotRemoveExactKeywordHits() {
        val result = RagSearchIndex(chunks).search("Flutter Hive", { floatArrayOf(1f, 0f, 0f) }, 3, .8f)
        assertEquals(3, result.incompatibleChunkCount)
        assertEquals("Flutter Hive.md", result.matches.firstOrNull()?.docName)
    }

    @Test fun unknownTopicAndBlankQueryDoNotInventSources() {
        val index = RagSearchIndex(chunks)
        assertTrue(index.search("量子纠错", { null }, 3, .8f).matches.isEmpty())
        assertTrue(index.search(" ", { fail("blank query must not embed"); null }, 3, .8f).matches.isEmpty())
    }

    @Test fun denseRetrievalStillWorksWithoutAnyLexicalOverlap() {
        val result = RagSearchIndex(chunks).search("手机的离线持久化方案", { floatArrayOf(1f, 0f) }, 3, .8f)
        assertEquals("Flutter Hive.md", result.matches.firstOrNull()?.docName)
        assertEquals(RagSearchMode.HYBRID, result.mode)
    }

    @Test fun emptyIndexNeverLoadsAnEmbeddingModel() {
        assertTrue(RagSearchIndex(emptyList()).search("Hive", { fail("empty index must not embed"); null }, 3, .8f).matches.isEmpty())
    }

    @Test fun nonFiniteOrEmptyQueryVectorsUseKeywordFallback() {
        val index = RagSearchIndex(chunks)
        for (vector in listOf(floatArrayOf(Float.NaN, 0f), floatArrayOf(Float.POSITIVE_INFINITY, 0f), floatArrayOf(0f, 0f), floatArrayOf())) {
            assertEquals(RagSearchMode.KEYWORD, index.search("Flutter Hive", { vector }, 3, .8f).mode)
        }
    }

    @Test fun embeddingExceptionsUseFallbackButCancellationIsNeverSwallowed() {
        val index = RagSearchIndex(chunks)
        assertEquals(RagSearchMode.KEYWORD, index.search("Flutter Hive", { throw IllegalStateException("fixture") }, 3, .8f).mode)
        try {
            index.search("Flutter Hive", { throw CancellationException("fixture") }, 3, .8f)
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun indexedTechnicalNamesAreNotSubstringsOfDifferentPackages() {
        val index = RagSearchIndex(chunks)
        for (query in listOf("HiveDB", "googleapis_hive", "flutter_hive", "Flutter SQLite")) {
            assertTrue(query, index.search(query, { null }, 3, .8f).matches.isEmpty())
        }
    }

    @Test fun chineseQueriesMatchOverlappingShortTermsWithoutRemovingDatabaseWords() {
        val note = StoredChunk(4, 4, "数据库.md", "存储", "数据库支持加密存储与自定义对象。", floatArrayOf(1f, 0f))
        val index = RagSearchIndex(listOf(note))
        for (query in listOf("介绍一下数据库", "如何实现加密存储", "帮我介绍下自定义对象")) {
            assertEquals(query, 1, index.search(query, { null }, 3, .8f).matches.size)
        }
    }

    @Test fun boundedSelectionMatchesFullSortingIncludingDocumentDiversityAndTies() {
        val random = Random(20261006)
        repeat(50) {
            val data = (1..120).map { id ->
                val score = random.nextInt(50, 101) / 100f
                StoredChunk(id.toLong(), random.nextLong(1, 14), "note-$id", "section", "fixture", floatArrayOf(score, 1f - score))
            }
            val index = RagSearchIndex(data)
            for (k in listOf(1, 3, 8)) {
                val threshold = maxOf(.6f, data.maxOf { it.embedding[0] } - .08f)
                val perDoc = mutableMapOf<Long, Int>()
                val expected = data.sortedByDescending { it.embedding[0] }.filter { chunk ->
                    if (chunk.embedding[0] < threshold || (perDoc[chunk.docId] ?: 0) >= 2) false
                    else { perDoc[chunk.docId] = (perDoc[chunk.docId] ?: 0) + 1; true }
                }.take(k)
                val actual = index.search("无词项重叠", { floatArrayOf(1f, 0f) }, k, .6f).matches
                assertEquals(expected.map { it.docName }, actual.map { it.docName })
            }
        }
    }

    @Test fun aNewIndexSnapshotDoesNotKeepDeletedKeywordResults() {
        assertTrue(RagSearchIndex(chunks).search("Flutter Hive", { null }, 3, .8f).matches.isNotEmpty())
        assertTrue(RagSearchIndex(chunks.drop(1)).search("Flutter Hive", { null }, 3, .8f).matches.isEmpty())
    }
}
