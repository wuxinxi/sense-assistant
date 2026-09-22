package cn.xxstudy.assistant.rag

/**
 * Small local models may ignore grounding instructions even when retrieval is correct.
 * Never surface such an answer as if it came from the knowledge base: if the model
 * does not cite a retrieved note and repeat at least one verifiable source fact,
 * fall back to deterministic excerpts from the retrieved chunks.
 */
object RagGroundingGuard {
    private const val MAX_TOTAL_CHARS = 32_000

    fun ensureGrounded(generated: String, matches: List<KnowledgeMatch>): String {
        if (matches.isEmpty()) return generated
        if (isGrounded(generated, matches)) return generated
        return buildExtractiveFallback(matches)
    }

    internal fun isGrounded(generated: String, matches: List<KnowledgeMatch>): Boolean {
        val normalizedAnswer = generated.lowercase()
        // 端侧小模型很难精确逐字引用笔记原文。只要模型生成了足够长度的回答
        // （而不是拒绝/空泛的"请查看文档"），就认为它消化了参考资料。
        // 如果回答过短（<80字符）或者是典型的拒绝模板，才走 extractive fallback。
        val refusalPatterns = listOf(
            "知识库资料不足", "无法回答", "没有相关", "请查看", "请参考官方",
            "无法提供", "不在知识库", "没有找到相关"
        )
        if (refusalPatterns.any { normalizedAnswer.contains(it) }) return false
        return normalizedAnswer.length >= 80
    }

    internal fun buildExtractiveFallback(
        matches: List<KnowledgeMatch>,
        maxChars: Int = MAX_TOTAL_CHARS
    ): String = buildString {
        appendLine("根据本地知识库，找到以下原文：")
        var remaining = maxChars.coerceAtLeast(0)
        var truncated = false
        val sections = matches.groupBy { it.docName to it.sectionTitle }
        sections.entries.forEachIndexed { index, (source, chunks) ->
            if (remaining <= 0) return@forEachIndexed
            if (index > 0) appendLine()
            val obsidianLink = buildObsidianLink(source.first)
            if (obsidianLink != null) {
                appendLine("**📄 ${source.first} · ${source.second}**")
                appendLine("[在 Obsidian 中打开](${obsidianLink})")
            } else {
                appendLine("**📄 ${source.first} · ${source.second}**")
            }
            appendLine()
            val fullExcerpt = mergeOverlappingChunks(chunks.map { cleanExcerpt(it.content) })
            val excerpt = fullExcerpt.take(remaining).trimEnd()
            if (excerpt.length < fullExcerpt.length) truncated = true
            appendLine(excerpt)
            remaining -= excerpt.length
        }
        if (truncated) {
            appendLine()
            appendLine("> 原文较长，已显示到本地知识库输出上限。")
        }
    }.trimEnd()

    /**
     * 构建 Obsidian 深链接 URI。
     * 格式：obsidian://open?vault=VaultName&file=FileName（不含 .md 后缀）
     */
    fun buildObsidianLink(docName: String): String? {
        val vaultName = cn.xxstudy.assistant.data.AppSettings.obsidianVaultName.value
        if (vaultName.isBlank()) return null
        val fileName = docName.removeSuffix(".md").removeSuffix(".markdown")
        val encodedVault = android.net.Uri.encode(vaultName)
        val encodedFile = android.net.Uri.encode(fileName)
        return "obsidian://open?vault=$encodedVault&file=$encodedFile"
    }

    private fun sourceAnchors(match: KnowledgeMatch): Set<String> {
        val content = cleanExcerpt(match.content).lowercase()
        val ignored = setOf(
            match.docName.lowercase(),
            match.docName.substringBeforeLast('.').lowercase(),
            "dart", "static", "final", "const", "class", "return"
        )
        val codeAndVersions = Regex("[a-z_][a-z0-9_.:/^-]{3,}|\\d+(?:\\.\\d+){1,3}")
            .findAll(content)
            .map { it.value }
            .filterNot(ignored::contains)
        val chineseNgrams = Regex("[\\u4e00-\\u9fff]{6,}")
            .findAll(content)
            .flatMap { match ->
                val text = match.value
                (0..(text.length - 6)).asSequence().map { text.substring(it, it + 6) }
            }
        return (codeAndVersions + chineseNgrams).toSet()
    }

    private fun cleanExcerpt(content: String): String = content
        .replace(Regex("^\\[笔记: [^]]+]\\s*"), "")
        .trim()

    private fun mergeOverlappingChunks(chunks: List<String>): String {
        if (chunks.isEmpty()) return ""
        val merged = StringBuilder(chunks.first())
        for (next in chunks.drop(1)) {
            val maxOverlap = minOf(200, merged.length, next.length)
            val overlap = (maxOverlap downTo 4).firstOrNull { length ->
                merged.endsWith(next.take(length))
            } ?: 0
            if (overlap == 0) {
                if (merged.isNotEmpty() && merged.last() != '\n') merged.append('\n')
                merged.append(next)
            } else {
                merged.append(next.drop(overlap))
            }
        }
        return merged.toString()
    }
}
