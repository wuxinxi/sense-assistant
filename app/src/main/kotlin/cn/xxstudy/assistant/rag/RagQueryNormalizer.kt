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
    private val sourceScopePrefix = Regex("^\\s*(?:请|帮我|请帮我)?(?:只|仅|严格|完全)?(?:根据|依据|按照|按)(?:我的|本地|当前)?(?:笔记|资料|知识库)(?:内容)?\\s*[:：,，]?\\s*")
    private val knowledgeBaseStatusPatterns = listOf(
        Regex("""^(?:你|您)?(?:是否|能否|能不能|可不可以|可以|能)?(?:了解|知道|访问|读取|查看|使用|连接)(?:我的|我|本地|这个)?知识库(?:吗|么|呢)?[?？。！!]*$"""),
        Regex("""^(?:我的|我|本地|这个)?知识库(?:里|中|内)?(?:有|包含)(?:什么|哪些)(?:内容|笔记|资料)?(?:吗|么|呢)?[?？。！!]*$"""),
        Regex("""^(?:我的|我|本地|这个)?知识库(?:的)?(?:状态|概况|信息|统计)(?:是什么|怎么样)?(?:吗|么|呢)?[?？。！!]*$"""),
        Regex("""^(?:我的|我|本地|这个)?知识库(?:同步|连接|索引)(?:好|完成|成功|了吗|了没有|没有|了)?[?？。！!]*$""")
    )

    fun normalize(rawQuery: String): String {
        val original = rawQuery.trim()
        val normalized = original
            .replace(sourceScopePrefix, "")
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

    /**
     * 识别“你能否访问我的知识库”一类元问题。这些问题没有可用于向量检索的主题，
     * 应该由本地索引状态直接回答，不能交给大模型猜测。
     */
    fun isKnowledgeBaseStatusQuery(rawQuery: String): Boolean {
        val compact = rawQuery.trim().replace(Regex("\\s+"), "")
        return knowledgeBaseStatusPatterns.any { it.matches(compact) }
    }
}
