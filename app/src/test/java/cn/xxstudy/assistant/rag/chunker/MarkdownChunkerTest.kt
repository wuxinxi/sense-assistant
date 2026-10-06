package cn.xxstudy.assistant.rag.chunker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownChunkerTest {
    @Test
    fun dividesVeryLargeCodeOnlyAtLinesAndKeepsEveryLineInOrder() {
        val lines = (1..100).map { "final value$it = Hive.box('settings').get('key$it');" }
        val chunks = MarkdownChunker.chunk("Hive.md", "# 使用\n```dart\n${lines.joinToString("\n")}\n```")
        assertTrue(chunks.size > 1)
        chunks.forEach { assertEquals(2, Regex("(?m)^```").findAll(it.text).count()) }
        val actual = chunks.flatMap { it.text.lines().filter { line -> line.startsWith("final value") } }
        assertEquals(lines, actual)
    }

    @Test
    fun shorterAndDifferentFenceCannotCloseTheActiveCodeBlock() {
        val markdown = "# 使用\n````dart\n```\n~~~\n# still code\n````\n完成说明。"
        val chunks = MarkdownChunker.chunk("Hive.md", markdown)
        assertTrue(chunks.all { it.sectionTitle == "使用" })
        assertTrue(chunks.any { it.text.contains("````dart\n```\n~~~\n# still code\n````") })
    }

    @Test
    fun closesIncompleteFenceWithoutDroppingCode() {
        val chunks = MarkdownChunker.chunk("Hive.md", "# 使用\n```dart\nHive.openBox('settings');")
        assertTrue(chunks.single().text.endsWith("Hive.openBox('settings');\n```"))
    }

    @Test
    fun keepsCompleteCodeBlockEvenWhenLargerThanProseTarget() {
        val code = "```dart\n" + (1..12).joinToString("\n") { "final value$it = Hive.box('settings').get('key$it');" } + "\n```"
        val chunks = MarkdownChunker.chunk("Hive.md", "# 使用\n说明文字。\n$code\n后续说明。", 120, 24)
        assertTrue("code must not be split at the prose window", chunks.any { it.text.contains(code) })
        assertTrue(chunks.all { Regex("(?m)^```").findAll(it.text).count() % 2 == 0 })
    }

    @Test
    fun doesNotTreatHeadingInsideFenceAsSection() {
        val chunks = MarkdownChunker.chunk("脚本.md", "# 安装\n~~~bash\n# not a heading\necho ready\n~~~\n完成安装。")
        assertTrue(chunks.all { it.sectionTitle == "安装" })
        assertTrue(chunks.any { it.text.contains("# not a heading\necho ready") })
    }

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
