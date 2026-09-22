package cn.xxstudy.assistant.rag.chunker

data class RawChunk(
    val sectionTitle: String,
    val text: String
)

object MarkdownChunker {
    private const val DEFAULT_TARGET_CHUNK_SIZE = 300
    private const val DEFAULT_OVERLAP_SIZE = 50
    // Obsidian 中常见只有一行的短事实笔记，不能因长度不足而静默丢弃。
    private const val MIN_CHUNK_SIZE = 4

    /**
     * 对 Markdown 笔记内容进行语义层级切片
     *
     * @param docName 笔记文件名（如 "周报计划.md"）
     * @param rawContent 原始 Markdown 字符串
     * @return 切片后的分块列表
     */
    fun chunk(
        docName: String,
        rawContent: String,
        targetChunkSize: Int = DEFAULT_TARGET_CHUNK_SIZE,
        overlap: Int = DEFAULT_OVERLAP_SIZE
    ): List<RawChunk> {
        val cleanDocName = docName.removeSuffix(".md").removeSuffix(".markdown")
        val stripped = stripFrontmatter(rawContent)
        if (stripped.isBlank()) return emptyList()

        val lines = stripped.lines()
        val sections = mutableListOf<RawChunk>()

        var currentHeading = "正文"
        val currentHeadingBreadcrumb = mutableListOf<String>()
        val buffer = StringBuilder()

        val headingRegex = Regex("""^(#{1,6})\s+(.+)$""")

        for (line in lines) {
            val trimmed = line.trim()
            val match = headingRegex.find(trimmed)

            if (match != null) {
                // 遇到新标题：先把前一个 section 的文本结算
                if (buffer.isNotBlank()) {
                    val chunks = splitLongText(
                        currentHeading,
                        buffer.toString().trim(),
                        targetChunkSize,
                        overlap
                    )
                    sections.addAll(chunks)
                    buffer.clear()
                }

                val level = match.groupValues[1].length
                val title = match.groupValues[2].trim()

                // 更新面包屑层级
                while (currentHeadingBreadcrumb.size >= level) {
                    currentHeadingBreadcrumb.removeAt(currentHeadingBreadcrumb.size - 1)
                }
                currentHeadingBreadcrumb.add(title)
                currentHeading = currentHeadingBreadcrumb.joinToString(" > ")
            } else {
                if (trimmed.isNotEmpty()) {
                    buffer.append(line).append("\n")
                } else if (buffer.isNotEmpty()) {
                    buffer.append("\n")
                }
            }
        }

        if (buffer.isNotBlank()) {
            val chunks = splitLongText(
                currentHeading,
                buffer.toString().trim(),
                targetChunkSize,
                overlap
            )
            sections.addAll(chunks)
        }

        // 统一包裹上下文前缀：[来源: 笔记名 > 标题]
        return sections.filter { it.text.length >= MIN_CHUNK_SIZE }.map { chunk ->
            val contextPrefix = "[笔记: $cleanDocName > ${chunk.sectionTitle}]\n"
            RawChunk(
                sectionTitle = chunk.sectionTitle,
                text = contextPrefix + chunk.text
            )
        }
    }

    /**
     * 剥离 Obsidian 常见的 YAML Frontmatter 头部元信息 (--- ... ---)
     */
    private fun stripFrontmatter(text: String): String {
        val trimmed = text.trimStart()
        if (trimmed.startsWith("---")) {
            val endIdx = trimmed.indexOf("\n---", 3)
            if (endIdx != -1) {
                return trimmed.substring(endIdx + 4).trimStart()
            }
        }
        return text
    }

    /**
     * 针对超出目标长度的大文本段落执行滑动窗口切片
     */
    private fun splitLongText(
        sectionTitle: String,
        text: String,
        chunkSize: Int,
        overlap: Int
    ): List<RawChunk> {
        require(chunkSize > 0) { "chunkSize must be positive" }
        require(overlap in 0 until chunkSize) { "overlap must be in [0, chunkSize)" }
        if (text.length <= chunkSize) {
            return listOf(RawChunk(sectionTitle, text))
        }

        val result = mutableListOf<RawChunk>()
        var start = 0
        while (start < text.length) {
            val hardEnd = (start + chunkSize).coerceAtMost(text.length)
            var end = if (hardEnd == text.length) {
                hardEnd
            } else {
                findSemanticBoundary(text, start, hardEnd)
            }
            if (text.length - end < MIN_CHUNK_SIZE) {
                end = text.length
            }
            val sub = text.substring(start, end).trim()
            if (sub.length >= MIN_CHUNK_SIZE) {
                result.add(RawChunk(sectionTitle, sub))
            }
            if (end == text.length) break

            val overlapTarget = (end - overlap).coerceAtLeast(start + 1)
            val nextStart = findNextBoundary(text, overlapTarget, end)
            start = if (nextStart in (start + 1)..end) nextStart else overlapTarget
        }
        return result
    }

    /**
     * 优先在段落、换行或完整句子之后结束分块，最后才退化为字符硬切。
     */
    private fun findSemanticBoundary(text: String, start: Int, hardEnd: Int): Int {
        val minEnd = (start + (hardEnd - start) / 2).coerceAtMost(hardEnd)

        val paragraphBoundary = text.lastIndexOf("\n\n", (hardEnd - 2).coerceAtLeast(start))
        if (paragraphBoundary >= minEnd) return paragraphBoundary + 2

        for (index in hardEnd - 1 downTo minEnd) {
            if (text[index] == '\n') return index + 1
        }

        val sentenceEndings = setOf('。', '！', '？', '；', '.', '!', '?', ';')
        for (index in hardEnd - 1 downTo minEnd) {
            if (text[index] in sentenceEndings) return index + 1
        }

        for (index in hardEnd - 1 downTo minEnd) {
            if (text[index].isWhitespace()) return index + 1
        }
        return hardEnd
    }

    /**
     * 让重叠窗口也从自然边界开始，避免下一块从词语或 Markdown 标记中间起步。
     */
    private fun findNextBoundary(text: String, target: Int, end: Int): Int {
        val sentenceEndings = setOf('。', '！', '？', '；', '.', '!', '?', ';', '\n')
        val searchEnd = (target + 24).coerceAtMost(end)
        for (index in target until searchEnd) {
            if (text[index] in sentenceEndings || text[index].isWhitespace()) {
                var next = index + 1
                while (next < end && text[next].isWhitespace()) next++
                return next
            }
        }
        return target
    }
}
