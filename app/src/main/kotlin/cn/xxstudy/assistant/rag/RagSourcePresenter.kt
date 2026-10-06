package cn.xxstudy.assistant.rag

data class RagSource(
    val referenceIds: List<Int>,
    val docName: String,
    val sectionTitle: String,
    val content: String
)

data class RagPresentedAnswer(val text: String, val isFallback: Boolean)

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

    fun present(generated: String, matches: List<KnowledgeMatch>): RagPresentedAnswer {
        if (matches.isEmpty() || RagGroundingGuard.isGrounded(generated, matches)) {
            return RagPresentedAnswer(generated, false)
        }
        return RagPresentedAnswer("本轮未生成可靠的资料摘要。请展开下方知识库原文查看，或在 Obsidian 中打开笔记。", true)
    }
}
