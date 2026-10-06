package cn.xxstudy.assistant.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagSectionExpanderTest {
    @Test
    fun doesNotMergeDifferentFilesWithTheSameNameAndHeading() {
        val chunks = listOf(
            RagSectionChunk(1, 1, "Hive.md", "使用", "正确资料"),
            RagSectionChunk(2, 2, "Hive.md", "使用", "另一目录中的同名笔记")
        )
        val seed = KnowledgeMatch("Hive.md", "使用", "正确资料", .9f, docId = 1)
        assertEquals(listOf(1L), RagSectionExpander.expand(listOf(seed), chunks, 1000).map { it.id })
    }

    @Test
    fun fillsTheRestOfASectionAfterASeedChunkMatches() {
        val allChunks = listOf(
            RagSectionChunk(10, 1, "Fluro.md", "依赖", "fluro: ^2.0.3"),
            RagSectionChunk(11, 1, "Fluro.md", "Fluro使用步骤", "第一段：创建路由单例"),
            RagSectionChunk(12, 1, "Fluro.md", "Fluro使用步骤", "第二段：定义 Handler"),
            RagSectionChunk(13, 1, "Fluro.md", "Fluro使用步骤", "第三段：注册路由"),
            RagSectionChunk(20, 2, "无关.md", "正文", "不应混入")
        )
        val seeds = listOf(
            KnowledgeMatch("Fluro.md", "Fluro使用步骤", "第一段：创建路由单例", 0.71f)
        )

        val expanded = RagSectionExpander.expand(seeds, allChunks, maxChars = 2_000)

        assertEquals(listOf(11L, 12L, 13L), expanded.map { it.id })
    }

    @Test
    fun obeysTheContextBudgetAtWholeChunkBoundaries() {
        val allChunks = listOf(
            RagSectionChunk(1, 1, "Fluro.md", "步骤", "12345"),
            RagSectionChunk(2, 1, "Fluro.md", "步骤", "67890"),
            RagSectionChunk(3, 1, "Fluro.md", "步骤", "abcde")
        )
        val seeds = listOf(KnowledgeMatch("Fluro.md", "步骤", "12345", 0.8f))

        val expanded = RagSectionExpander.expand(seeds, allChunks, maxChars = 10)

        assertEquals(listOf(1L, 2L), expanded.map { it.id })
    }

    @Test
    fun marksAChunkWhenTheSectionExceedsTheOutputBudget() {
        val allChunks = listOf(
            RagSectionChunk(1, 1, "note.md", "正文", "1234567890")
        )
        val seeds = listOf(KnowledgeMatch("note.md", "正文", "123", 0.8f))

        val expanded = RagSectionExpander.expand(seeds, allChunks, maxChars = 5)

        assertEquals(1, expanded.size)
        assertTrue(expanded.single().content.contains("原文较长"))
    }
}
