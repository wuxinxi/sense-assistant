package cn.xxstudy.assistant.rag.chunker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownChunkerTest {
    @Test
    fun keepsShortFactNotes() {
        val chunks = MarkdownChunker.chunk("端口.md", "端口 8080")

        assertEquals(1, chunks.size)
        assertTrue(chunks.single().text.contains("端口 8080"))
    }

    @Test
    fun stripsFrontmatterAndKeepsHeadingBreadcrumbs() {
        val markdown = """
            ---
            tags: [rag, android]
            ---
            # Android
            这是 Android 章节中需要被索引的一段正文内容。
            ## SAF
            持久化目录权限需要调用 takePersistableUriPermission 才能在重启后继续访问。
        """.trimIndent()

        val chunks = MarkdownChunker.chunk("知识库.md", markdown)

        assertEquals(2, chunks.size)
        assertTrue(chunks[0].text.startsWith("[笔记: 知识库 > Android]"))
        assertTrue(chunks[1].text.startsWith("[笔记: 知识库 > Android > SAF]"))
        assertFalse(chunks.any { it.text.contains("tags:") })
    }

    @Test
    fun prefersSentenceBoundariesForLongSections() {
        val sentences = (1..12).map { index ->
            "第${index}句用于验证分块器会优先保留完整句子，而不是从一句话的中间截断语义。"
        }
        val markdown = "# 分块测试\n${sentences.joinToString("")}" 

        val chunks = MarkdownChunker.chunk(
            docName = "测试.md",
            rawContent = markdown,
            targetChunkSize = 120,
            overlap = 24
        )

        assertTrue(chunks.size > 1)
        chunks.dropLast(1).forEach { chunk ->
            assertTrue(chunk.text.trimEnd().endsWith("。"))
        }
        sentences.forEach { sentence ->
            assertTrue("missing sentence: $sentence", chunks.any { it.text.contains(sentence) })
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOverlapThatCannotAdvanceWindow() {
        MarkdownChunker.chunk(
            docName = "测试.md",
            rawContent = "这是一段足够长的测试内容，用来触发参数校验并避免滑动窗口进入死循环。",
            targetChunkSize = 20,
            overlap = 20
        )
    }
}
