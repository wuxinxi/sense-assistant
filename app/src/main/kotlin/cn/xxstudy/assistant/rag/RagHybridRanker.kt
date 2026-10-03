package cn.xxstudy.assistant.rag

/** Lightweight lexical signal mixed with dense similarity for exact technical names. */
object RagHybridRanker {
    const val LEXICAL_OVERRIDE_THRESHOLD = 0.10f

    internal data class PreparedTerm(
        val text: String,
        val boundaryPattern: Regex?
    )

    class PreparedQuery internal constructor(internal val terms: List<PreparedTerm>)

    private val termPattern = Regex("[A-Za-z][A-Za-z0-9_+.#-]{2,}|[\\u4e00-\\u9fff]{2,}")
    private val ignoredTerms = setOf(
        "帮我介绍下", "帮我介绍一下", "介绍下", "介绍一下", "请介绍", "请帮我",
        "怎么", "怎么用", "如何", "如何使用", "什么", "一下", "关于", "相关",
        "知识库", "本地", "数据"
    )

    fun lexicalBonus(
        query: String,
        docName: String,
        sectionTitle: String,
        content: String
    ): Float = lexicalBonus(prepare(query), docName, sectionTitle, content)

    fun prepare(query: String): PreparedQuery = PreparedQuery(
        termPattern.findAll(query.lowercase())
            .map { it.value }
            .filterNot(ignoredTerms::contains)
            .distinct()
            .take(8)
            .map { term ->
                PreparedTerm(
                    text = term,
                    boundaryPattern = if (term.any { it.code > 127 }) {
                        null
                    } else {
                        Regex("(?<![a-z0-9])${Regex.escape(term)}(?![a-z0-9])", RegexOption.IGNORE_CASE)
                    }
                )
            }
            .toList()
    )

    fun lexicalBonus(
        query: PreparedQuery,
        docName: String,
        sectionTitle: String,
        content: String
    ): Float {
        if (query.terms.isEmpty()) return 0f

        val normalizedDocName = docName.lowercase()
        val normalizedSection = sectionTitle.lowercase()
        val normalizedContent = content.lowercase()
        var bonus = 0f
        for (term in query.terms) {
            bonus += when {
                containsTerm(normalizedDocName, term) -> 0.14f
                containsTerm(normalizedSection, term) -> 0.10f
                containsTerm(normalizedContent, term) -> 0.04f
                else -> 0f
            }
        }
        return bonus.coerceAtMost(0.24f)
    }

    fun shouldInclude(
        denseScore: Float,
        denseThreshold: Float,
        lexicalBonus: Float
    ): Boolean = denseScore >= denseThreshold || lexicalBonus >= LEXICAL_OVERRIDE_THRESHOLD

    fun combinedScore(denseScore: Float, lexicalBonus: Float): Float = denseScore + lexicalBonus

    private fun containsTerm(text: String, term: PreparedTerm): Boolean {
        return term.boundaryPattern?.containsMatchIn(text) ?: text.contains(term.text)
    }
}
