package cn.xxstudy.assistant.rag

/** Removes UI/search command wording that dilutes semantic retrieval. */
object RagQueryNormalizer {
    private val knowledgeBasePrefix = Regex(
        """^\s*(?:请帮我|帮我|请)?\s*(?:通过|从|在|用)?\s*(?:本地)?知识库(?:中|里|内)?\s*(?:查询|搜索|查找|检索|找一下|搜一下)?\s*[:：,，]?\s*""",
        RegexOption.IGNORE_CASE
    )
    private val searchKnowledgeBasePrefix = Regex(
        """^\s*(?:请帮我|帮我|请)?\s*(?:查询|搜索|查找|检索)\s*(?:本地)?知识库(?:中|里|内)?\s*[:：,，]?\s*""",
        RegexOption.IGNORE_CASE
    )

    fun normalize(rawQuery: String): String {
        val original = rawQuery.trim()
        val normalized = original
            .replace(searchKnowledgeBasePrefix, "")
            .replace(knowledgeBasePrefix, "")
            .trim()
        return normalized.ifBlank { original }
    }

    fun isExplicitKnowledgeLookup(rawQuery: String): Boolean {
        val trimmed = rawQuery.trim()
        return knowledgeBasePrefix.containsMatchIn(trimmed) ||
            searchKnowledgeBasePrefix.containsMatchIn(trimmed)
    }
}
