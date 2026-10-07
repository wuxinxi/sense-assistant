package cn.xxstudy.assistant.rag

data class RagSource(
    val referenceIds: List<Int>,
    val docName: String,
    val sectionTitle: String,
    val content: String
)

data class RagPresentedAnswer(
    val text: String,
    val isFallback: Boolean,
    val kind: cn.xxstudy.assistant.conversation.AnswerKind? = null,
    val issues: List<RagAnswerIssue> = emptyList(),
    val generalRejections: List<RagGeneralRejection> = emptyList()
) {
    val validationNotice: String? get() = issues.takeIf { it.isNotEmpty() }
        ?.joinToString("；", prefix = "摘要检查：") { it.label }
    /** Report rejected blocks even if another block was accepted; never include the draft. */
    val validationEvent: String? get() = issues.takeIf { it.isNotEmpty() }
        ?.joinToString(", ", prefix = "RAG answer validation: kind=${kind?.name ?: "UNCLASSIFIED"}, issues=") { it.name }
}

/** Source code stays application-owned; the model only writes an explanation. */
object RagSourcePresenter {
    fun sources(seeds: List<KnowledgeMatch>, expanded: List<KnowledgeMatch> = seeds): List<RagSource> {
        return seeds.withIndex().groupBy { Triple(it.value.docId, it.value.docName, it.value.sectionTitle) }.map { (key, refs) ->
            val section = expanded.filter { it.docName == key.second && it.sectionTitle == key.third &&
                (key.first == null || it.docId == key.first) }
                .ifEmpty { refs.map { it.value } }
            RagSource(
                referenceIds = refs.map { it.index + 1 },
                docName = key.second,
                sectionTitle = key.third,
                content = RagGroundingGuard.sourceText(section)
            )
        }
    }

    fun present(
        generated: String,
        matches: List<KnowledgeMatch>,
        policy: RagAnswerPolicy? = null,
        hasFinalAnswer: Boolean = true
    ): RagPresentedAnswer {
        if (matches.isNotEmpty() && policy != null) {
            // App-generated empty/thinking-only notices are not model claims to validate.
            val composed = RagAnswerComposer.compose(if (hasFinalAnswer) generated else "", matches, policy)
            return RagPresentedAnswer(composed.text, composed.isFallback, composed.kind, composed.issues, composed.generalRejections)
        }
        if (matches.isEmpty() || RagGroundingGuard.isGrounded(generated, matches)) {
            return RagPresentedAnswer(generated, false)
        }
        return RagPresentedAnswer("本轮未生成可靠的资料摘要。请展开下方知识库原文查看，或在 Obsidian 中打开笔记。", true)
    }
}
